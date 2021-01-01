#!/bin/bash
set -o errexit -o nounset -o pipefail -o xtrace
cd "`dirname $0`/.."

echo `pwd`
ls -lah
clojure -A:package -M -m grumpy.package
