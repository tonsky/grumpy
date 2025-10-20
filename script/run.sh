#!/bin/bash
set -o errexit -o nounset -o pipefail
cd "`dirname $0`/.."

# java --add-opens java.base/sun.nio.ch=ALL-UNNAMED -cp target/grumpy.jar clojure.main -m grumpy.main
clojure -M:java -m grumpy.main