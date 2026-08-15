#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup batch script, version 3.3.2
# ----------------------------------------------------------------------------

set -e

# OS-specific support.
darwin=false
case "$(uname)" in
  Darwin*) darwin=true;;
esac

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD="$(
      'set' +e
      'unset' -f command 2>/dev/null
      'command' -v java
    )" 2>/dev/null
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
fi

# Absolute path to this script, e.g. /home/user/bin/foo.sh
SCRIPT="$(readlink -f "$0" 2>/dev/null || realpath "$0" 2>/dev/null || echo "$0")"
# Absolute path this script is in, thus /home/user/bin
SCRIPT_DIR="$(dirname "$SCRIPT")"

# Detect the project base directory (the directory containing .mvn/)
if [ -z "$MAVEN_BASEDIR" ] ; then
    find_maven_basedir() {
        if [ -z "$1" ] ; then
            echo "."
            return 0
        fi

        basedir="$1"
        wdir="$1"
        while :
        do
            if [ -d "$wdir/.mvn" ] ; then
                basedir=$wdir
                break
            fi
            # Use the parent directory
            wdir=$(cd "$wdir/.."; pwd)
            if [ "$wdir" = "/" ] ; then
                break
            fi
        done
        echo "${basedir}"
    }
    MAVEN_PROJECTBASEDIR=$(find_maven_basedir "$SCRIPT_DIR")
else
    MAVEN_PROJECTBASEDIR="$MAVEN_BASEDIR"
fi

if [ -z "$MAVEN_PROJECTBASEDIR" ] ; then
    MAVEN_PROJECTBASEDIR="."
fi
export MAVEN_PROJECTBASEDIR

WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain
WRAPPER_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"

# Download the wrapper jar if missing
if [ -r "$WRAPPER_JAR" ] ; then
    if [ "$MVNW_VERBOSE" = true ]; then
        echo "Found $WRAPPER_JAR"
    fi
else
    if [ "$MVNW_VERBOSE" = true ]; then
        echo "Couldn't find $WRAPPER_JAR, downloading it ..."
        echo "Downloading from: $WRAPPER_URL"
    fi
    if ! command -v curl > /dev/null; then
        if ! command -v wget > /dev/null; then
            echo "Neither curl nor wget is available. Cannot download Maven wrapper."
            exit 1
        fi
        wget "$WRAPPER_URL" -O "$WRAPPER_JAR" 2>/dev/null
        [ $? -ne 0 ] && rm -f "$WRAPPER_JAR"
    else
        curl -o "$WRAPPER_JAR" "$WRAPPER_URL" -f 2>/dev/null
        [ $? -ne 0 ] && rm -f "$WRAPPER_JAR"
    fi
    if [ -r "$WRAPPER_JAR" ] ; then
        if [ "$MVNW_VERBOSE" = true ]; then
            echo "Finished downloading $WRAPPER_JAR"
        fi
    else
        echo "ERROR: couldn't download $WRAPPER_JAR" >&2
        exit 1
    fi
fi

# Read additional JVM opts from .mvn/jvm.config if it exists
JVM_CONFIG_MAVEN_PROPS=""
JVM_CONFIG="$MAVEN_PROJECTBASEDIR/.mvn/jvm.config"
if [ -r "$JVM_CONFIG" ] ; then
    JVM_CONFIG_MAVEN_PROPS=$(tr '\n' ' ' < "$JVM_CONFIG")
fi

exec "$JAVACMD" \
  $JVM_CONFIG_MAVEN_PROPS \
  $MAVEN_OPTS \
  $MAVEN_DEBUG_OPTS \
  -classpath "$WRAPPER_JAR" \
  "-Dmaven.multiModuleProjectDirectory=${MAVEN_PROJECTBASEDIR}" \
  ${WRAPPER_LAUNCHER} $MAVEN_CONFIG "$@"
