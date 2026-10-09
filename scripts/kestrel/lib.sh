# Shared helpers for the Kestrel reference-environment scripts (sourced, not executed).
export AWS_PROFILE=${AWS_PROFILE:-my2027}
export AWS_REGION=${AWS_REGION:-us-east-1} AWS_DEFAULT_REGION=${AWS_REGION:-us-east-1}
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
NS=kestrel
RELEASE=kestrel
KCTX=kestrel-ref
CDK=${CDK:-cdk}

log() { printf '\n==> %s\n' "$*" >&2; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# out <stack> <OutputKey>
out() {
  aws cloudformation describe-stacks --stack-name "$1" \
    --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue" --output text
}

k() { kubectl --context "$KCTX" -n "$NS" "$@"; }
