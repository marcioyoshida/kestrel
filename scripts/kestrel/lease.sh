#!/usr/bin/env bash
# Show or extend the reference environment's lease. KestrelCi's reaper (infra/reaper) deletes
# KestrelRef within 15 minutes after the lease ends; deploy.sh sets it to KESTREL_LEASE_HOURS (8).
# Usage: lease.sh            show the lease and what the reaper would do now
#        lease.sh <hours>    lease until <hours> from now
set -euo pipefail
source "$(dirname "$0")/lib.sh"

[ $# -eq 0 ] || lease "$1"
aws ssm get-parameter --name "$LEASE_PARAM" --query Parameter.Value --output text 2>/dev/null \
  || echo "no lease parameter (the reaper uses stack creation + 8 h)"
fn=$(out KestrelCi ReaperFunctionName)
tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
aws lambda invoke --function-name "$fn" --payload '{"dryRun": true}' --cli-binary-format raw-in-base64-out \
  "$tmp" >/dev/null
echo "reaper now: $(cat "$tmp")"
