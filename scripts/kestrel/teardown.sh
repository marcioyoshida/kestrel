#!/usr/bin/env bash
# Remove the reference environment: Helm release first (so the TargetGroupBinding finalizer
# deregisters pods while the controller still runs), then the KestrelRef stack.
# KestrelCi (ECR images, GitHub OIDC role) is kept: it costs cents and CI depends on it.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

if aws eks describe-cluster --name kestrel-ref >/dev/null 2>&1; then
  aws eks update-kubeconfig --name kestrel-ref --alias "$KCTX" >/dev/null
  log "helm uninstall"
  helm --kube-context "$KCTX" -n "$NS" uninstall "$RELEASE" --wait --timeout 10m || true
  # Auto Mode nodes drain away once nothing is scheduled; wait so their ENIs don't block the VPC.
  for _ in $(seq 1 40); do
    n=$(kubectl --context "$KCTX" get nodes --no-headers 2>/dev/null | wc -l)
    [ "$n" -eq 0 ] && break
    sleep 15
  done
fi
log "cdk destroy KestrelRef"
(cd "$ROOT/infra" && $CDK destroy KestrelRef -c ephemeral=true --force)

left=$(aws ec2 describe-instances --filters Name=tag:eks:eks-cluster-name,Values=kestrel-ref \
  Name=instance-state-name,Values=pending,running,stopping,stopped \
  --query 'Reservations[].Instances[].InstanceId' --output text)
[ -z "$left" ] || die "instances still tagged for kestrel-ref: $left"
echo "KestrelRef removed."
