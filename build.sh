#!/bin/bash
# Build KT Flasher di VM ini. Pola sama seperti build Mousike:
# JAVA_TOOL_OPTIONS meneruskan preferIPv4Stack + trustStore ke semua proses
# JVM; repo Maven lewat mirror lokal.
set -u
cd "$HOME/workspace/kt-flasher" || exit 1

export GRADLE_USER_HOME=/home/hatch/.gradle
export JAVA_HOME="$HOME/workspace/tools/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Djavax.net.ssl.trustStore=/home/hatch/workspace/tools/java-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"

if ! curl -s -o /dev/null --max-time 3 http://127.0.0.1:8899/; then
    nohup python3 "$HOME/workspace/tools/maven_mirror.py" >> "$HOME/workspace/tools/maven_mirror.log" 2>&1 &
    sleep 2
    echo "mirror maven: dinyalakan"
fi

"$HOME/workspace/tools/gradle-8.9/bin/gradle" "$@" --console=plain
echo "BUILD EXIT: $?"
