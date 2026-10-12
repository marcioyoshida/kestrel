#!/usr/bin/env bash
# Kestrel on a local kind cluster: the EKS reference environment's chart and settings, with AWS
# replaced by in-cluster stand-ins (deploy/kind/aws-standins.yaml). Costs nothing to run; use it
# for day-to-day validation and keep EKS for each milestone's exit check (IAM, CloudFront, nodes).
#
# Usage: kind_up.sh [commit]   (default HEAD; the Kestrel CI run of that commit must have passed)
# Then:  python3 scripts/kestrel/local_validate_m3_isolated.py --kind   (UI: http://localhost:4440)
# Stop:  kind_down.sh
#
# Needs docker (Docker Desktop with WSL integration on), kind, kubectl, helm, gh, Go (~/.local/go).
# Images: the CI artifact kestrel-image (kept 3 days), else ECR (`main` commits only); the
# trigger image is built here from kestrel-trigger/.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CLUSTER=kestrel
CTX=kind-$CLUSTER
NS=kestrel
COMMIT=$(git -C "$ROOT" rev-parse "${1:-HEAD}")
TAG=${COMMIT:0:12}
export AWS_PROFILE=${AWS_PROFILE:-my2027} AWS_REGION=${AWS_REGION:-us-east-1}
log() { printf '\n==> %s\n' "$*" >&2; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null && docker info >/dev/null 2>&1 \
  || die "docker is not reachable: start Docker Desktop and enable WSL integration for this distro"

if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  log "kind create cluster $CLUSTER"
  kind create cluster --config "$ROOT/deploy/kind/cluster.yaml" --wait 120s
fi

log "Kestrel image $TAG"
if ! docker image inspect "kestrel:$TAG" >/dev/null 2>&1; then
  run=$(gh run list -R marcioyoshida/kestrel --commit "$COMMIT" --workflow Kestrel --json databaseId,conclusion \
        --jq '.[] | select(.conclusion=="success") | .databaseId' | head -1)
  tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
  if [ -n "$run" ] && gh run download "$run" -R marcioyoshida/kestrel -n kestrel-image -D "$tmp" 2>/dev/null; then
    docker load -i "$tmp/kestrel-image.tar.gz"
    docker tag kestrel:ci "kestrel:$TAG"
  else
    repo=$(aws cloudformation describe-stacks --stack-name KestrelCi \
           --query "Stacks[0].Outputs[?OutputKey=='RepositoryUri'].OutputValue" --output text)
    aws ecr get-login-password | docker login --username AWS --password-stdin "${repo%%/*}" >/dev/null
    docker pull "$repo:$TAG" || die "no CI image for $TAG (artifact expired and not in ECR)"
    docker tag "$repo:$TAG" "kestrel:$TAG"
  fi
fi
kind load docker-image --name "$CLUSTER" "kestrel:$TAG"

log "trigger image"
( cd "$ROOT/kestrel-trigger" && mkdir -p bin \
  && CGO_ENABLED=0 GOOS=linux GOARCH=amd64 "${GO:-$HOME/.local/go/bin/go}" build -trimpath -ldflags="-s -w" \
       -o bin/kestrel-trigger-linux-amd64 . \
  && docker build -q --platform linux/amd64 -t "kestrel-trigger:$TAG" . >/dev/null )
kind load docker-image --name "$CLUSTER" "kestrel-trigger:$TAG"

log "AWS stand-ins"
kubectl --context "$CTX" apply -f "$ROOT/deploy/kind/aws-standins.yaml" >/dev/null
kubectl --context "$CTX" -n kestrel-aws rollout status deploy --timeout 300s >/dev/null
# The fire ledger (also sessions and the cluster bus); KestrelRef creates it with CDK.
for _ in $(seq 1 30); do
  AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local aws dynamodb create-table --endpoint-url http://localhost:8000 \
    --region us-east-1 --table-name kestrel-local-ledger --billing-mode PAY_PER_REQUEST \
    --attribute-definitions AttributeName=pk,AttributeType=S --key-schema AttributeName=pk,KeyType=HASH \
    >/dev/null 2>"/tmp/kind-ddb.err" && break
  grep -q ResourceInUseException /tmp/kind-ddb.err && break
  sleep 2
done

log "secrets"
kubectl --context "$CTX" create namespace "$NS" --dry-run=client -o yaml | kubectl --context "$CTX" apply -f - >/dev/null
kubectl --context "$CTX" -n "$NS" get secret kestrel-keystorage >/dev/null 2>&1 \
  || kubectl --context "$CTX" -n "$NS" create secret generic kestrel-keystorage \
       --from-literal=password="$(openssl rand -base64 32 | tr -d '\n')" >/dev/null

log "helm upgrade --install (kestrel:$TAG)"
helm --kube-context "$CTX" upgrade --install kestrel "$ROOT/deploy/helm/kestrel" -n "$NS" \
  -f "$ROOT/deploy/kind/values.yaml" --set image.tag="$TAG" --set scheduler.trigger.image="kestrel-trigger:$TAG" \
  --wait --timeout 15m

for _ in $(seq 1 60); do
  curl -fsS -o /dev/null http://127.0.0.1:4440/monitoring/health/readiness 2>/dev/null && break
  sleep 5
done
curl -fsS -o /dev/null http://127.0.0.1:4440/monitoring/health/readiness || die "web not ready on :4440"
echo "Kestrel $TAG is up on kind: http://localhost:4440 (admin/admin)"
