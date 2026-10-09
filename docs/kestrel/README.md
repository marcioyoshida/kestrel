# Kestrel

Kestrel is an EKS-native fork of [Rundeck](https://github.com/rundeck/rundeck) (Apache-2.0).
It keeps Rundeck's UI, API, job format and plugin SPI, and replaces the parts that assume one
stateful server:

| Upstream | Kestrel |
|---|---|
| deb/rpm or a single container | Helm chart on AWS EKS |
| RDBMS via GORM/Hibernate | DynamoDB (M2) |
| Quartz inside the web JVM | Kubernetes CronJobs + SQS (M1) |
| Workflows run in the web JVM | Runner StatefulSet (pool) or per-run Job (isolated) |
| SCM plugin (server-side git) | GitHub App: push sync + PR checks, no checkout (M3) |
| Mixed GSP/Vue UI | Modernized Vue UI (ADR 0002) |

## Status

**M0**: the Helm chart in [`deploy/helm/kestrel`](../../deploy/helm/kestrel) runs the upstream
`rundeck/rundeck:6.2.1` image (amd64) against RDS, behind an internal ALB used as a CloudFront
VPC Origin. Single web replica, because Quartz is still in-process.

## Decisions

- [ADR 0001: EKS-native architecture](adr/0001-eks-native-architecture.md)
- [ADR 0002: UI modernization](adr/0002-ui-modernization.md)

## Known upstream issue

`/monitoring/**` is `permitAll` (`rundeckapp/grails-app/conf/application.groovy`), and monitoring
defaults to on (`MonitoringController.isMonitoringEnabled`). That appears to expose thread dumps
and metrics without authentication. The chart blocks `/monitoring` at the ALB, and CloudFront
must block it too. To be confirmed in the M0 smoke test.

## Trademark

"Rundeck" is a trademark of its owner. Kestrel is not affiliated with or endorsed by Rundeck or
PagerDuty.
