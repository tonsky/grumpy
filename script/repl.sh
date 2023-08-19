#!/bin/bash
set -o errexit -o nounset -o pipefail
cd "`dirname $0`/.."

clj -A:dev:java -X clojure.core.server/start-server :name repl :port 5555 :accept clojure.core.server/repl :server-daemon false
