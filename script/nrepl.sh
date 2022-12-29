#!/bin/bash
set -o errexit -o nounset -o pipefail
cd "`dirname $0`/.."

clj -A:dev:java -M -m nrepl.cmdline --interactive