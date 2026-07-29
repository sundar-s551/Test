#!/bin/sh
# Wrapper: Used to run the build command with options and other functions as below
# Sets up environment variables for DBB and Java, forces JVM file I/O to use UTF-8
# Creates a timestamp log directory, rewrites any --outDir argument to point at that directory
# Runs a Groovy based build(groovyz) 
# Retags logs for z/OS, and keeps only most recent logs

# This scripting actually helps to write the logs into newly timestamp folders
# For every user build it creates timestamp directory and makes uss clean


# DBB installation path
# SRVMFBL is a SUPER ID created for running builds
export DBB_HOME = /u/SRVMFBL/IBM/dbb 

# Java installation path
export JAVA_HOME = /usr/lpp/java/J8.0_64

# JVM options applied by the IBM, memory settings(-Xms,-Xmx,-Xss), disable shared classes(-Xshareclasses:no),disable compressed ref(-Xnocompressedrefs)
# forces file encoding to UTF-8(-Dfile.encoding=UTF-8)
export IBM_JAVA_OPTIONS = '-Xshareclasses:none -Xms64m -Xmx512m -Xss256k -Xnocompressdrefs -Dfile.encoding=UTF-8'

# Similar JVM options but applied by many JVMs and tools; exported so any spawned java process inherits the same memory/encoding flags
export JAVA_TOOL_OPTIONS = '-Xms64m -Xmx512m -Xss256k -Xnocompressdrefs -Dfile.encoding=UTF-8'


# Create timestamp
ts=$(date +"%Y%m%d_%H%M%S")

# Defines where logs go
BASE_LOG_DIR=/u/USERID/workspace/logs 
LOG_DIR=${BASE_LOG_DIR}/$ts

# Creates the directory
mkdir -p "$LOG_DIR"

# Argument handling loop
# If a --outDir is found, it consumes the flag and its following value and replaces them with --outDir $LOG_DIR So build always writes logs into timestamp folder
NEW_ARGS=""
while [ $# -gt 0 ]
do 
  if [ "$1" = "--outDir" ]; then
    shift
    NEW_ARGS="$NEW_ARGS --outDir $LOG_DIR"
  else
    NEW_ARGS="$NEW_ARGS $1"
  fi
  shift
done


# Run the build
eval /u/SRVMFBL/IBM/dbb/bin/groovyz "$NEW_ARGS"
rc=$?

# Retag log outputs to UTF-8, So Zowe downloads them in readable text
chtag -R -tc UTF-8 "$LOG_DIR" 2>/dev/null


# Cleanup old logs and keep only latest folder

cd /u/USERID/workspace/logs

# Get the latest folder
latest=$(ls -dt */ 2>/dev/null | head -n 1)

# Remove everything except latest direcory
for d in */
do
    if [ "$d" != "$latest" ]; then
        rm -rf "$d"
    fi 
done

# Returns the build exit code to the caller
exit $rc