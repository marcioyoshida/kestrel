# Kestrel

Kestrel is an EKS-native fork of [Rundeck](https://github.com/rundeck/rundeck) (Apache-2.0).
It keeps Rundeck's UI, API, job format and plugin SPI, and replaces the parts that assume one
stateful server:

| Upstream | Kestrel |
|---|---|
| deb/rpm or a single container | Helm chart on AWS EKS |
| RDBMS via GORM/Hibernate | DynamoDB (M2) |
| Quartz inside the web JVM | Kubernetes CronJobs + SQS + runner pods (M1, done) |
| Workflows run in the web JVM | Runner StatefulSet (pool) or per-run Job (isolated) |
| SCM plugin (server-side git) | GitHub App: push sync + PR checks, no checkout (M3) |
| Mixed GSP/Vue UI | Modernized Vue UI (ADR 0002) |

## Status

- **M0 is validated** ([2026-10-09](validation/2026-10-09-m0-reference.md)): Helm on EKS
  Auto Mode, RDS, an oauth2-proxy sign-in sidecar (Cognito or any OIDC issuer), an internal ALB
  and CloudFront.
- **M1 is validated** ([2026-10-09](validation/2026-10-09-m1-kubernetes-scheduler.md),
  [ADR 0003](adr/0003-m1-kubernetes-scheduler.md)). With `scheduler.mode: kubernetes`, no
  Quartz cron trigger exists:
  - the Lease leader converges one CronJob per distinct schedule;
  - each firing goes through SQS FIFO to runner pods and runs exactly once per job and minute
    (DynamoDB ledger);
  - web scales to N.

  Chaos soak: 113/113 firings, no duplicates, through 9 pod kills.

## Build, deploy, prove, tear down

```bash
deploy/helm/kestrel/ci/test.sh        # chart lint, kubeconform, install guards (also in CI)
git push origin main                  # CI: checks, unit test, war + image -> ECR kestrel:<sha12>
scripts/kestrel/deploy.sh             # cdk deploy KestrelCi + KestrelRef (ephemeral), secrets, helm
python3 -I scripts/kestrel/validate.py   # edge, spoofing, sign-in, job run, S3 log, cron
python3 -I scripts/kestrel/soak.py --minutes 45   # M1 exit test: pod kills, exactly-once accounting
scripts/kestrel/teardown.sh           # helm uninstall, cdk destroy KestrelRef (keeps ECR)
```

Set `AWS_PROFILE` (default `my2027`) and `CDK` to point at the CDK CLI, for example
`CDK="node …/aws-cdk/bin/cdk"`. `deploy.sh` refuses to run until CI has pushed the image for
the current commit. Expect about $0.30/hour while the reference environment is up: the EKS
control plane, one m5a.large node with the Auto Mode fee, NAT, RDS micro and the ALB.
Teardown leaves only the ECR repository and the GitHub OIDC role.

## Decisions

- [ADR 0001: EKS-native architecture](adr/0001-eks-native-architecture.md)
- [ADR 0002: UI modernization](adr/0002-ui-modernization.md)
- [ADR 0003: M1 Kubernetes scheduler as built](adr/0003-m1-kubernetes-scheduler.md)
- [ADR 0004: M2 DynamoDB persistence through a GORM datastore](adr/0004-m2-dynamodb-gorm-datastore.md)

## Known upstream issues

- `/monitoring/**` is `permitAll` (`rundeckapp/grails-app/conf/application.groovy`) and
  monitoring is on by default, so thread dumps and metrics are exposed without authentication.
  Kestrel blocks `/monitoring` at both CloudFront and the ALB (verified: 404). The target-group
  health check still reaches `/monitoring/health/readiness` through the sidecar.
- In preauth mode, roles are re-read from request headers on every request, so the proxy in
  front must own those headers on every route. See the validation notes.

## Trademark

"Rundeck" is a trademark of its owner. Kestrel is not affiliated with or endorsed by Rundeck or
PagerDuty.
