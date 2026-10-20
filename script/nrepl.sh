#!/bin/bash
set -o errexit -o nounset -o pipefail
cd "`dirname $0`/.."

clojure $(./script/java_opts.sh) -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.7.0"}}}' -M:dev -e "(user/-main)" -m nrepl.cmdline --port 5556
