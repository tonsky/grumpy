#!/bin/bash
set -o errexit -o nounset -o pipefail
cd "`dirname $0`/.."

export SITE_URL="${SITE_URL:-http://localhost:8080}"
export FORCED_USER="${FORCED_USER:-nikitonsky}"
export HOST="${HOST:-0.0.0.0}"

clojure $(./script/java_opts.sh) -M:dev -m user
