#!/bin/bash
set -o errexit -o nounset -o pipefail
cd "`dirname $0`/.."

exec clojure $(./script/java_opts.sh) -J-Xms64m -J-Xmx1g -M -m grumpy.main "$@"
