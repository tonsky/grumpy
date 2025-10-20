#!/bin/bash
set -o errexit -o nounset -o pipefail
cd "`dirname $0`/.."

clojure -M:dev:java -m user