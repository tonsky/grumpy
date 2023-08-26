#!/bin/bash
set -o errexit -o nounset -o pipefail -o xtrace
cd "`dirname $0`/.."

rsync --recursive --times grumpy:grumpy_data --include "grumpy_data/" --include "20*/***" --include "db.sqlite" --exclude "*" .
