#!/bin/sh
# Wrapper : Calls the custom framework scripts instead of dbb-zappbuild


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


# Call custom build.groovy
$DBB_HOME/bin/groovyz /u/USERID/cust-zappbuild/build.groovy "$@"


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