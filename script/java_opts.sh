#!/bin/bash
set -o errexit -o nounset -o pipefail

# Get Java version
java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)

# If version is >= 24, return the flag
if [ "$java_version" -ge 24 ]; then
  echo "-J--enable-native-access=ALL-UNNAMED"
else
  echo ""
fi
