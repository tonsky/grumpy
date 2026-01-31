#!/bin/bash
set -o errexit -o nounset -o pipefail

# Get Java version
java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)

# Build Java options based on version
opts="-J-Dclojure.main.report=stderr -J-Duser.language=en -J-Duser.country=US -J-Dfile.encoding=UTF-8"

if [ "$java_version" -ge 16 ]; then
  opts="$opts -J--add-modules -Jjdk.incubator.vector"
fi

if [ "$java_version" -ge 24 ]; then
  opts="$opts -J--enable-native-access=ALL-UNNAMED"
fi

echo "$opts"
