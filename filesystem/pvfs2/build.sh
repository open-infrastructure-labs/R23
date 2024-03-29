#!/usr/bin/env bash

set -e # exit on error
pushd "$(dirname "$0")" # connect to root
ROOT_DIR=$(pwd)

SERVER_DIR=$ROOT_DIR/orangefs_server
FUSE_DIR=$ROOT_DIR/orangefs_fuse

echo "Start building PVFS2 ..."
mkdir -p ${SERVER_DIR}
mkdir -p ${FUSE_DIR}

pushd orangefs
# ./prepare command will generate ./configure script
./prepare

./configure --prefix=$SERVER_DIR --with-db-backend=lmdb

# On a systems with large core count we may run our of memory
make -j$((`nproc`/2))

mkdir -p $SERVER_DIR
# sudo chown $USER:$USER $SERVER_DIR
make install

# Can be <prefix>{#-#,#,#-#,...}
# Coma separated
IOSERVERS="r23-1-storage-0"
METASERVERS="r23-1-storage-0"
$SERVER_DIR/bin/pvfs2-genconfig --quiet --protocol tcp --ioservers ${IOSERVERS} --metaservers ${METASERVERS} \
--logfile /opt/volume/filesystem/orangefs/logs \
--storage /opt/volume/filesystem/orangefs/data \
$SERVER_DIR/etc/server.conf

# Building orangefs_fuse
# need libfuse-dev
./configure --prefix=$FUSE_DIR --disable-server --disable-usrint --disable-opt --enable-fuse --with-db-backend=lmdb

make -j$((`nproc`/2))

make install

# We also need to create pvfs2tab file (potentially separate for each client :) )
# It may be possible to  set the PVFS2TAB_FILE environment variable to the desired path.
echo "tcp://r23-1-storage-0:3334/orangefs /mnt/orangefs_fuse pvfs2 defaults,noauto 0 0" > $FUSE_DIR/pvfs2tab
chmod a+r $FUSE_DIR/pvfs2tab
