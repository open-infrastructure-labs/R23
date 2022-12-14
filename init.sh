#!/bin/bash

set -e # exit on error
pushd "$(dirname "$0")" # connect to root

ROOT_DIR=$(pwd)
echo "ROOT_DIR ${ROOT_DIR}"

USER_NAME=${SUDO_USER:=$USER}

${ROOT_DIR}/start.sh

#Initialize ssh configuration
${ROOT_DIR}/scripts/init_ssh.sh

# Initialize tpc-ds on dc1
${ROOT_DIR}/benchmark/src/docker-bench.py --init

# Restart datacenters after initialization phase
${ROOT_DIR}/stop.sh

