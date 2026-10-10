#!/usr/bin/env bash
# Run Kestrel locally in DynamoDB storage mode (ADR 0004), without EKS:
#   - DynamoDB Local on :8000 (java -jar, started if not running)
#   - the war CI built for a commit (artifact kestrel-war), on JDK 17, H2 for RDBMS-mapped classes
# Usage: local-run.sh [commit]   (default HEAD). Stop with: kill $(cat /tmp/kestrel-local/rundeck.pid)
set -euo pipefail
COMMIT=$(git -C "$(dirname "$0")" rev-parse "${1:-HEAD}")
JAVA=${JAVA:-$HOME/.local/jdk17/bin/java}
DDB_HOME=${DDB_HOME:-$HOME/.local/dynamodb-local}
BASE=${KESTREL_LOCAL_BASE:-/tmp/kestrel-local}
mkdir -p "$BASE"

if ! (exec 3<>/dev/tcp/127.0.0.1/8000) 2>/dev/null; then
  echo "starting DynamoDB Local"
  (cd "$DDB_HOME" && nohup "$JAVA" -Djava.library.path=./DynamoDBLocal_lib -jar DynamoDBLocal.jar -inMemory -sharedDb -port 8000 > "$BASE/ddb.log" 2>&1 &)
  sleep 5
fi

# Stateless sessions and the cluster bus use a ledger-shaped table (pk S); create it locally.
AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local aws dynamodb create-table --endpoint-url http://localhost:8000 \
  --region us-east-1 --table-name local-ledger --billing-mode PAY_PER_REQUEST \
  --attribute-definitions AttributeName=pk,AttributeType=S --key-schema AttributeName=pk,KeyType=HASH >/dev/null 2>&1 || true

war="$BASE/rundeck-${COMMIT:0:12}.war"
if [ ! -f "$war" ]; then
  run=$(gh run list -R marcioyoshida/kestrel --commit "$COMMIT" --json databaseId,conclusion --jq '.[] | select(.conclusion=="success") | .databaseId' | head -1)
  [ -n "$run" ] || { echo "no successful CI run for $COMMIT"; exit 1; }
  tmp=$(mktemp -d)
  gh run download "$run" -R marcioyoshida/kestrel -n kestrel-war -D "$tmp"
  mv "$tmp"/rundeck-*.war "$war"
  rm -rf "$tmp"
fi

echo "starting Kestrel ${COMMIT:0:12} (KESTREL_STORAGE=dynamodb, base $BASE/rdeck)"
cd "$BASE"
KESTREL_STORAGE=dynamodb KESTREL_DYNAMODB_ENDPOINT=http://localhost:8000 KESTREL_DYNAMODB_PREFIX=local \
KESTREL_SESSIONS=dynamodb KESTREL_SESSIONS_TABLE=local-ledger \
AWS_REGION=us-east-1 \
nohup "$JAVA" -Xmx2g -Drdeck.base="$BASE/rdeck" -Dserver.http.port=4440 -Dserver.address=127.0.0.1 \
  -Drundeck.server.uuid=00000000-0000-4000-8000-000000000001 \
  -jar "$war" > "$BASE/rundeck.log" 2>&1 &
echo $! > "$BASE/rundeck.pid"
for _ in $(seq 1 90); do
  curl -fsS -o /dev/null http://127.0.0.1:4440/monitoring/health/readiness 2>/dev/null && { echo "up: http://127.0.0.1:4440"; exit 0; }
  kill -0 "$(cat "$BASE/rundeck.pid")" 2>/dev/null || { echo "Kestrel exited; see $BASE/rundeck.log"; tail -40 "$BASE/rundeck.log"; exit 1; }
  sleep 5
done
echo "not ready after 7.5 min; see $BASE/rundeck.log"; exit 1
