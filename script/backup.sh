#!/bin/bash
set -o errexit -o nounset -o pipefail -o xtrace
cd "`dirname $0`/.."

rsync -rt grumpy:grumpy_data/posts grumpy_data/
