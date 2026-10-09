#!/usr/bin/env bash
# Chart checks run by CI and before every commit: lint, schema-validate the rendered manifests,
# and prove each install-time guard rejects the input it exists for.
set -euo pipefail
cd "$(dirname "$0")/.."
K8S=${K8S_VERSION:-1.33.0}
REF=ci/ref-values.yaml

helm lint . -f "$REF"
helm template kestrel . -n kestrel -f "$REF" \
  | kubeconform -strict -summary -kubernetes-version "$K8S" -skip TargetGroupBinding -
# The TGB is a CRD (EKS Auto Mode); check its shape by hand.
helm template kestrel . -n kestrel -f "$REF" -s templates/web-targetgroupbinding.yaml \
  | grep -q 'targetType: ip'

# Auth on: Rundeck must bind to loopback and only the sidecar may own the "http" port.
out=$(helm template kestrel . -n kestrel -f "$REF")
grep -q 'RUNDECK_SERVER_ADDRESS: "127.0.0.1"' <<<"$out"
grep -q -- '--skip-auth-strip-headers=true' <<<"$out"
[ "$(grep -c 'name: http$' <<<"$out")" -eq 2 ]   # sidecar port + Service port

expect_fail() {  # <description> <expected message fragment> <helm --set args...>
  local desc=$1 msg=$2; shift 2
  if err=$(helm template kestrel . -f "$REF" "$@" 2>&1); then
    echo "FAIL (rendered): $desc"; exit 1
  fi
  grep -q -- "$msg" <<<"$err" || { echo "FAIL (wrong error): $desc"; echo "$err"; exit 1; }
  echo "ok   guard: $desc"
}
expect_fail "quartz with 2 replicas"      "requires scheduler.mode=kubernetes" --set web.replicas=2
expect_fail "unbuilt scheduler mode"      "not implemented yet"                --set scheduler.mode=kubernetes
expect_fail "s3 logs without plugin"      "provides=org.rundeck.amazon-s3"     --set plugins=null
expect_fail "bad plugin digest"           "64-char"                            --set plugins[0].sha256=abc
expect_fail "missing database url"        "database.url is required"           --set database.url=
expect_fail "missing key storage secret"  "keyStorage.existingSecret"          --set keyStorage.existingSecret=
expect_fail "unknown auth provider"       "cognito or oidc"                    --set auth.provider=saml
expect_fail "http issuer"                 "https:// OIDC issuer"               --set auth.issuerUrl=http://idp
expect_fail "auth without secret"         "auth.existingSecret is required"    --set auth.existingSecret=
expect_fail "ingress and TGB together"    "exclusive"                          --set ingress.enabled=true
expect_fail "TGB without ARN"             "target group ARN"                   --set targetGroupBinding.targetGroupArn=
echo "chart checks passed"
