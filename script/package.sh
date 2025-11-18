#!/bin/bash
set -o errexit -o nounset -o pipefail -o xtrace
cd "`dirname $0`/.."

clojure $(./script/java_opts.sh) -M:package -m grumpy.package
