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
grep -q '{ name: RUNDECK_SERVER_ADDRESS, value: "127.0.0.1" }' <<<"$out"
grep -q -- '--skip-auth-strip-headers=true' <<<"$out"
[ "$(grep -c 'name: http$' <<<"$out")" -eq 2 ]   # sidecar port + Service port

# Kubernetes scheduler mode (M1): N web replicas, a runner StatefulSet, scheduler RBAC, no fixed UUID.
K8S_REF=(-f "$REF" -f ci/ref-values-kubernetes.yaml)
helm lint . "${K8S_REF[@]}"
kout=$(helm template kestrel . -n kestrel "${K8S_REF[@]}")
kubeconform -strict -summary -kubernetes-version "$K8S" -skip TargetGroupBinding - <<<"$kout"
grep -q 'name: kestrel-runner$' <<<"$kout"                       # runner StatefulSet + its service account
grep -q 'resources: \["cronjobs"\]' <<<"$kout"
grep -q 'KESTREL_SCHEDULER_MODE: kubernetes' <<<"$kout"
grep -q 'KESTREL_STORAGE: dynamodb' <<<"$kout"
grep -q 'KESTREL_SESSIONS: dynamodb' <<<"$kout"
grep -q 'RUNDECK_PLUGIN_EXECUTIONFILESTORAGE_NAME: "kestrel-s3"' <<<"$kout"
grep -q 'RUNDECK_EXECUTION_LOGS_FILESTORAGE_CHECKPOINT_TIME_INTERVAL: "5s"' <<<"$kout"
! grep -q 'fetch-plugins' <<<"$kout"   # bundled log store: no plugin download
grep -q 'KESTREL_DYNAMODB_PREFIX: "kestrel-ref"' <<<"$kout"
! grep -q 'RUNDECK_DATABASE_' <<<"$kout"                         # dynamodb mode: no RDBMS (M2d)
helm template kestrel . -n kestrel "${K8S_REF[@]}" --set database.url= --set database.existingSecret= >/dev/null
! grep -q 'RUNDECK_SERVER_UUID:' <<<"$kout"                      # per-pod UUIDs, never a shared one
[ "$(grep -c 'exec docker-lib/entry.sh' <<<"$kout")" -eq 2 ]     # web + runner derive their UUID
[ "$(grep -c 'name: auth-proxy' <<<"$kout")" -eq 1 ]             # sign-in sidecar on web only
grep -q 'kind: PodDisruptionBudget' <<<"$kout"
# The UUID derivation must survive Kubernetes $(VAR) expansion: run it as the pod would.
script=$(helm template kestrel . -n kestrel "${K8S_REF[@]}" -s templates/web-statefulset.yaml \
  | awk '/"\/tini"/{t=1} t&&/^ *- \|$/{f=1;next} f&&/exec docker-lib/{exit} f' | sed 's/\$\$/$/g')
uuid=$(KESTREL_NAMESPACE=kestrel KESTREL_POD_NAME=kestrel-web-0 bash -ec "$script" | sed -n 's/^server UUID \([^ ]*\) .*/\1/p')
[[ $uuid =~ ^[0-9a-f]{8}-[0-9a-f]{4}-3[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] || { echo "bad UUID: $uuid"; exit 1; }
other=$(KESTREL_NAMESPACE=kestrel KESTREL_POD_NAME=kestrel-web-1 bash -ec "$script" | sed -n 's/^server UUID \([^ ]*\) .*/\1/p')
[ "$uuid" != "$other" ] || { echo "pods share a UUID"; exit 1; }
echo "ok   per-pod server UUIDs: kestrel-web-0=$uuid kestrel-web-1=$other"

# GitHub App (M3): web pods only, credentials from the Secret.
GH=(--set github.enabled=true --set github.appId=123 --set 'github.repos={acme/jobs,other/*}'
    --set 'github.roles={kestrel-github}' --set github.existingSecret=kestrel-github)
gout=$(helm template kestrel . -n kestrel "${K8S_REF[@]}" "${GH[@]}")
kubeconform -strict -summary -kubernetes-version "$K8S" -skip TargetGroupBinding - <<<"$gout"
[ "$(grep -c 'name: KESTREL_GITHUB_PRIVATE_KEY' <<<"$gout")" -eq 1 ]   # web, not the runner
grep -q 'name: KESTREL_GITHUB_REPOS, value: "acme/jobs,other/\*"' <<<"$gout"
grep -q 'secretKeyRef: { name: kestrel-github, key: webhook-secret }' <<<"$gout"
! grep -q KESTREL_GITHUB <<<"$kout"

# Isolated jobs (M3, ADR 0006): a PodTemplate per runner class, Job-derived server UUIDs, web RBAC.
ISO=(--set isolated.enabled=true --set isolated.classes.probe.serviceAccount=kestrel-probe
     --set isolated.classes.probe.activeDeadlineSeconds=900 --set isolated.classes.probe.nodeSelector.pool=probe)
iout=$(helm template kestrel . -n kestrel "${K8S_REF[@]}" "${ISO[@]}")
kubeconform -strict -summary -kubernetes-version "$K8S" -skip TargetGroupBinding - <<<"$iout"
[ "$(grep -c '^kind: PodTemplate' <<<"$iout")" -eq 2 ]
grep -q 'KESTREL_ISOLATED_CLASSES: "default,probe"' <<<"$iout"
grep -q 'KESTREL_ISOLATED_POD_TEMPLATE_PREFIX: "kestrel-isolated-"' <<<"$iout"
[ "$(grep -c "fieldPath: \"metadata.labels\['batch.kubernetes.io/job-name'\]\"" <<<"$iout")" -eq 2 ]
[ "$(grep -c '^kind: ServiceAccount' <<<"$iout")" -eq "$(( $(grep -c '^kind: ServiceAccount' <<<"$kout") + 1 ))" ]  # default class only
grep -q 'serviceAccountName: kestrel-probe' <<<"$iout"
grep -q 'kestrel.io/active-deadline-seconds: "900"' <<<"$iout"
grep -q 'pool: probe' <<<"$iout"
[ "$(grep -c 'karpenter.sh/do-not-disrupt: "true"' <<<"$iout")" -eq 2 ]   # isolated pods only
grep -q 'resources: \["podtemplates"\]' <<<"$iout"
grep -q 'resources: \["secrets"\]' <<<"$iout"
! grep -q 'verbs: \[.*"get".*\]' <<<"$(grep -A2 '"secrets"' <<<"$iout")"   # create only, never read
[ "$(grep -c 'name: auth-proxy' <<<"$iout")" -eq 1 ]
[ "$(grep -c 'readinessProbe' <<<"$iout")" -eq "$(grep -c 'readinessProbe' <<<"$kout")" ]  # none on isolated pods
! grep -q -E 'KESTREL_ISOLATED|podtemplates' <<<"$kout"

# kind (deploy/kind): AWS stand-ins for every pod, a fixed NodePort, no stand-in env on EKS values.
lout=$(helm template kestrel . -n kestrel -f ../../kind/values.yaml)
kubeconform -strict -summary -kubernetes-version "$K8S" - <<<"$lout"
grep -q 'KESTREL_SQS_ENDPOINT: "http://elasticmq.kestrel-aws:9324"' <<<"$lout"
grep -q 'KESTREL_S3_ENDPOINT: "http://s3mock.kestrel-aws:9090"' <<<"$lout"
grep -q 'KESTREL_DYNAMODB_ENDPOINT: "http://dynamodb-local.kestrel-aws:8000"' <<<"$lout"
grep -q 'nodePort: 30440' <<<"$lout"
[ "$(grep -c '^kind: PodTemplate' <<<"$lout")" -eq 2 ]
! grep -q -E '_ENDPOINT|nodePort' <<<"$iout"

expect_fail() {  # <description> <expected message fragment> <helm --set args...>
  local desc=$1 msg=$2; shift 2
  if err=$(helm template kestrel . -f "$REF" "$@" 2>&1); then
    echo "FAIL (rendered): $desc"; exit 1
  fi
  grep -q -- "$msg" <<<"$err" || { echo "FAIL (wrong error): $desc"; echo "$err"; exit 1; }
  echo "ok   guard: $desc"
}
expect_fail "quartz with 2 replicas"      "requires scheduler.mode=kubernetes" --set web.replicas=2
expect_fail "s3 logs without plugin"      "provides=org.rundeck.amazon-s3"     --set plugins=null
expect_fail "bad plugin digest"           "64-char"                            --set plugins[0].sha256=abc
expect_fail "missing database url"        "database.url is required"           --set database.url=
expect_fail "missing key storage secret"  "keyStorage.existingSecret"          --set keyStorage.existingSecret=
expect_fail "unknown auth provider"       "cognito or oidc"                    --set auth.provider=saml
expect_fail "http issuer"                 "https:// OIDC issuer"               --set auth.issuerUrl=http://idp
expect_fail "auth without secret"         "auth.existingSecret is required"    --set auth.existingSecret=
expect_fail "ingress and TGB together"    "exclusive"                          --set ingress.enabled=true
expect_fail "TGB without ARN"             "target group ARN"                   --set targetGroupBinding.targetGroupArn=
expect_fail "unknown scheduler mode"       "quartz or kubernetes"               --set scheduler.mode=cron
expect_fail "k8s mode, standard queue"    "FIFO"                               -f ci/ref-values-kubernetes.yaml --set scheduler.queueUrl=https://sqs.us-east-1.amazonaws.com/1/q
expect_fail "k8s mode, no ledger"         "ledgerTable"                        -f ci/ref-values-kubernetes.yaml --set scheduler.ledgerTable=
expect_fail "k8s mode, no trigger image"  "trigger.image"                      -f ci/ref-values-kubernetes.yaml --set scheduler.trigger.image=
expect_fail "k8s mode, zero runners"      "runner.replicas"                    -f ci/ref-values-kubernetes.yaml --set runner.replicas=0
expect_fail "unknown log store"            "kestrel-s3 or org.rundeck.amazon-s3" --set logStorage.s3.provider=gcs
expect_fail "unknown storage mode"         "rdbms or dynamodb"                  --set storage.mode=mongo
expect_fail "dynamodb without prefix"     "tablePrefix is required"            --set storage.mode=dynamodb
expect_fail "github without repos"        "github.repos is required"           --set github.enabled=true --set github.appId=1 --set 'github.roles={r}' --set github.existingSecret=s
expect_fail "github without roles"        "github.roles is required"           --set github.enabled=true --set github.appId=1 --set 'github.repos={a/b}' --set github.existingSecret=s
expect_fail "github without secret"       "github.existingSecret is required"  --set github.enabled=true --set github.appId=1 --set 'github.repos={a/b}' --set 'github.roles={r}'
expect_fail "isolated in quartz mode"    "requires scheduler.mode=kubernetes" --set isolated.enabled=true
expect_fail "isolated, bad class name"   "DNS label"                          -f ci/ref-values-kubernetes.yaml --set isolated.enabled=true --set isolated.classes.Big_One.resources=null
expect_fail "local AWS without endpoints"  "localAws.dynamodbEndpoint"          --set localAws.enabled=true
echo "chart checks passed"
