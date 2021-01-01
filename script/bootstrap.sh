#!/bin/bash
set -o errexit -o nounset -o pipefail
cd "`dirname $0`/.."

cat > grumpy_data/config.edn << 'EOF'
{:grumpy.auth/forced-user "nikitonsky",
 :grumpy.server/hostname "http://localhost:8080"}
EOF
