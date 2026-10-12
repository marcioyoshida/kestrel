#!/usr/bin/env bash
# Delete the local kind cluster kind_up.sh created (all data is in memory and goes with it).
set -euo pipefail
kind delete cluster --name kestrel
