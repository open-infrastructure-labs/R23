/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql
package stats.server


import java.io._
import java.net.{HttpURLConnection, URL}
import java.nio.ByteBuffer

import com.github.qflock.newfunc.server.ModSparkClient
import org.slf4j.LoggerFactory

import org.apache.spark.unsafe.types.UTF8String

/** Provides API to access the stats server.
 *
 * @param urlPath - the server url. e.g. http://myServerName:9860/stats
 * @param op - The string representation of the operation to perform.
 */
class StatsServerTableClient(urlPath: String,
                             op: String) extends ModSparkClient {
  private val logger = LoggerFactory.getLogger(getClass)

  override def toString: String = {
    s"StatsServerClient $urlPath $op"
  }
  def getEmptyQueryStream: DataInputStream = {
    // Write a header with a column number of 0.
    val b = ByteBuffer.allocate(4)
    b.putInt(0)
    val s = new DataInputStream(new ByteArrayInputStream(b.array()))
    s
  }
  private val connection: Option[HttpURLConnection] = getConnection
  def close(): Unit = {
    if (connection.isDefined) {
      //      logger.info("close start")
      if (stream.isDefined) {
        stream.get.close()
      }
      connection.get.disconnect()
      //      logger.info("close end")
    }
  }
  private var stream: Option[DataInputStream] = None
  def getStream: DataInputStream = stream.get
  def getOutputStream: OutputStream = connection.get.getOutputStream
  private def getConnection: Option[HttpURLConnection] = {
    //    logger.info(s"opening stream to: $tableName $rgOffset $rgCount")
    val url = new URL(urlPath + "?op=" + op)
    val con = url.openConnection.asInstanceOf[HttpURLConnection]
    con.setRequestMethod("POST")
    con.setChunkedStreamingMode(4096) // .getBytes("utf-8")
    con.setDoOutput(true)
    con.setDoInput(true)
    con.setReadTimeout(0)
    con.setConnectTimeout(0)
    con.connect()
    logger.info("GetTable Connection opened to: " + urlPath)
    Some(con)
  }
  def getInputStream: DataInputStream = {
    val con = connection.get
    val statusCode = con.getResponseCode
    stream = Some(if (statusCode == 200) {
      //      logger.info(s"opening stream done $tableName $rgOffset $rgCount")
      new DataInputStream(new BufferedInputStream(con.getInputStream))
    } else {
      logger.error(s"unexpected http status on connect: $statusCode")
      getEmptyQueryStream
    })
    stream.get
  }

  /**
   *  Returns a string sequence containing all the tables that are available for stats calculations.
   * @return Seq[String] containing all the strings of table names.
   */
  def getTables: Seq[String] = {
    val inStream = getInputStream
    // The API has the number of bytes per string, followed by total number of bytes.
    // byte 0:  Number of bytes per string (fixed for all strings).
    // byte 4:  Total number of bytes in all strings
    // byte 8:  Starts string data.

    val fixedStringBytes = inStream.readInt()
    val numBytes = inStream.readInt()
    val numStrings = numBytes / fixedStringBytes
    val byteBuffer = ByteBuffer.allocate(numBytes)
    inStream.readFully(byteBuffer.array(), 0, numBytes)
    val tables = (0 to numStrings) map { i =>
      UTF8String.fromBytes(byteBuffer.array(),
        fixedStringBytes * i,
        fixedStringBytes).toString
    }
    tables
  }
}
