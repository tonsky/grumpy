#!/bin/bash
set -o errexit -o nounset -o pipefail -o xtrace
cd "`dirname $0`/.."

clojure -M:package -m grumpy.package
