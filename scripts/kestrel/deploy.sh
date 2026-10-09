#!/usr/bin/env bash
# Deploy the Kestrel reference environment end to end:
#   1. CDK stacks KestrelCi (ECR) + KestrelRef (VPC, EKS Auto Mode, RDS, ALB, CloudFront, Cognito)
#   2. Kubernetes secrets derived from stack resources (never written to the repo)
#   3. the Helm chart, running the image CI built from this commit
#
# Env: IMAGE_TAG (default: this commit, as pushed by .github/workflows/kestrel.yml),
#      IMAGE_REPOSITORY (default: the KestrelCi ECR repo; rundeck/rundeck for upstream),
#      EPHEMERAL=true|false (default true: `teardown.sh` leaves nothing behind),
#      SKIP_CDK=1 to reuse already-deployed stacks.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

if [ "${SKIP_CDK:-0}" != 1 ]; then
  log "CDK deploy (ephemeral=${EPHEMERAL:-true})"
  (cd "$ROOT/infra" && $CDK deploy KestrelCi KestrelRef -c "ephemeral=${EPHEMERAL:-true}" \
    --require-approval never)
fi

CLUSTER=$(out KestrelRef ClusterName)
PUBLIC_URL=$(out KestrelRef PublicUrl)
TG_ARN=$(out KestrelRef TargetGroupArn)
LOG_BUCKET=$(out KestrelRef LogBucket)
DB_HOST=$(out KestrelRef DbEndpoint)
DB_SECRET=$(out KestrelRef DbSecretArn)
ISSUER=$(out KestrelRef OidcIssuer)
POOL_ID=$(out KestrelRef UserPoolId)
CLIENT_ID=$(out KestrelRef ClientId)
IMAGE_REPOSITORY=${IMAGE_REPOSITORY:-$(out KestrelCi RepositoryUri)}
IMAGE_TAG=${IMAGE_TAG:-$(git -C "$ROOT" rev-parse HEAD | cut -c1-12)}

if [[ $IMAGE_REPOSITORY == *.dkr.ecr.* ]]; then
  aws ecr describe-images --repository-name "${IMAGE_REPOSITORY#*/}" \
    --image-ids "imageTag=$IMAGE_TAG" >/dev/null 2>&1 \
    || die "image $IMAGE_REPOSITORY:$IMAGE_TAG not found; wait for the Kestrel CI run of this commit (or set IMAGE_TAG)"
fi

log "kubeconfig for $CLUSTER"
aws eks update-kubeconfig --name "$CLUSTER" --alias "$KCTX" >/dev/null
kubectl --context "$KCTX" create namespace "$NS" --dry-run=client -o yaml | kubectl --context "$KCTX" apply -f -

log "secrets"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT; chmod 700 "$tmp"
aws secretsmanager get-secret-value --secret-id "$DB_SECRET" --query SecretString --output text \
  | python3 -c 'import json,sys,os; d=json.load(sys.stdin); t=sys.argv[1]
open(os.path.join(t,"username"),"w").write(d["username"]); open(os.path.join(t,"password"),"w").write(d["password"])' "$tmp"
k create secret generic kestrel-db --from-file="$tmp/username" --from-file="$tmp/password" \
  --dry-run=client -o yaml | k apply -f -

# Generated once and kept: rotating these breaks stored keys / signed-in sessions.
if ! k get secret kestrel-keystorage >/dev/null 2>&1; then
  openssl rand -base64 32 | tr -d '\n' > "$tmp/ks"
  k create secret generic kestrel-keystorage --from-file=password="$tmp/ks"
fi
aws cognito-idp describe-user-pool-client --user-pool-id "$POOL_ID" --client-id "$CLIENT_ID" \
  --query UserPoolClient.ClientSecret --output text | tr -d '\n' > "$tmp/client-secret"
printf %s "$CLIENT_ID" > "$tmp/client-id"
cookie=$(k get secret kestrel-auth -o jsonpath='{.data.cookie-secret}' 2>/dev/null | base64 -d || true)
[ -n "$cookie" ] || cookie=$(openssl rand -base64 32 | tr -- '+/' '-_' | tr -d '\n')
printf %s "$cookie" > "$tmp/cookie-secret"
k create secret generic kestrel-auth --from-file="$tmp/client-id" --from-file="$tmp/client-secret" \
  --from-file="$tmp/cookie-secret" --dry-run=client -o yaml | k apply -f -

uuid=$(helm --kube-context "$KCTX" -n "$NS" get values "$RELEASE" -o json 2>/dev/null \
  | python3 -c 'import json,sys; print((json.load(sys.stdin) or {}).get("web",{}).get("serverUuid",""))' 2>/dev/null || true)
[ -n "$uuid" ] || uuid=$(python3 -c 'import uuid; print(uuid.uuid4())')

cat > "$tmp/values.yaml" <<VALUES
publicUrl: $PUBLIC_URL
image: { repository: $IMAGE_REPOSITORY, tag: "$IMAGE_TAG" }
web: { serverUuid: $uuid }
database:
  url: jdbc:postgresql://$DB_HOST:5432/kestrel
  existingSecret: kestrel-db
keyStorage: { existingSecret: kestrel-keystorage }
logStorage: { s3: { enabled: true, bucket: $LOG_BUCKET, region: $AWS_REGION } }
plugins:
  - name: rundeck-s3-log-plugin-3.0.6.jar
    url: https://github.com/rundeck-plugins/rundeck-s3-log-plugin/releases/download/3.0.6/rundeck-s3-log-plugin-3.0.6.jar
    sha256: f28abecc4708afdf7ada3ed936d56bb9ec907be78d6337666bffa27a2e45e4ab
    provides: org.rundeck.amazon-s3
auth:
  enabled: true
  provider: cognito
  issuerUrl: $ISSUER
  existingSecret: kestrel-auth
ingress: { enabled: false }
targetGroupBinding: { enabled: true, targetGroupArn: $TG_ARN }
VALUES

log "helm upgrade --install ($IMAGE_REPOSITORY:$IMAGE_TAG)"
helm --kube-context "$KCTX" upgrade --install "$RELEASE" "$ROOT/deploy/helm/kestrel" -n "$NS" \
  -f "$tmp/values.yaml" --wait --timeout 20m

log "target health"
for _ in $(seq 1 40); do
  state=$(aws elbv2 describe-target-health --target-group-arn "$TG_ARN" \
    --query 'TargetHealthDescriptions[].TargetHealth.State' --output text)
  [ "$state" = healthy ] && break
  sleep 15
done
[ "$state" = healthy ] || die "target group not healthy: ${state:-no targets}"
echo "Kestrel is up: $PUBLIC_URL"
