# M1 validation run, 2026-10-09

Kubernetes mode (ADR 0003) ran on a throwaway EKS environment with 2 web pods and 2 runner
pods, through CloudFront, under deliberate pod kills, and was then torn down. The ADR 0001 exit
criterion: kill any web pod mid-schedule; no missed and no duplicate firings.

## What ran

| Piece | Version |
|---|---|
| Image | `kestrel:ca5270d5fcd5` (soaks 1 and 2 ran earlier builds of the same scheduler code; see "Fixes found live") |
| Trigger | `kestrel-trigger`, multi-arch (amd64 + arm64), 4.7 MB |
| Topology | `kestrel-web` StatefulSet ×2 (sign-in sidecar, Lease candidates), `kestrel-runner` StatefulSet ×2 |
| Scheduler | One CronJob per (schedule, zone), SQS FIFO fire queue + DLQ, DynamoDB fire ledger |
| Checks | `scripts/kestrel/validate.py` (21 checks), `scripts/kestrel/soak.py` |

## Results

### Live suite: 21/21

All M0 checks still pass in Kubernetes mode: edge blocking, header spoofing, sign-in, the job
run, the S3 log, and the scheduled job. The scheduled job now fires from a Kubernetes CronJob
through SQS onto a runner.

### Chaos soak 2: 45 minutes, a kill every 4 minutes

| Check | Result |
|---|---|
| Kills | 9: Lease leader, other web pod and runners in rotation; 3 forced (`--grace-period=0 --force`) |
| Firings expected in the window | 113 (two every-minute jobs sharing one CronJob, plus an every-2-minutes job in `America/Sao_Paulo`) |
| Fired | **113/113**, none missed |
| Duplicates | **0**: every scheduled execution in Rundeck maps to its own ledger claim |
| Ledger | every claim `STARTED`, none stuck in `CLAIMED`, none `SKIPPED` |
| DLQ | empty |
| Quartz cron triggers on any pod | 0 |
| Runner execution logs in S3 | present |
| Quartz-only schedule (`L`) | rejected at save, with the reason shown |
| Project created on one pod, used on another | visible at once |

Soak 1 (same scheduler, earlier build) had the same outcome: 113/113 with 9 kills, and 191
ledger claims all `STARTED`. Its duplicate check reported a false positive: the accounting window
was wider for executions than for expected minutes. The script now compares against ledger claims
over the same widened window.

### Chaos soak 3: 15 minutes, a kill every 2 minutes, with cross-pod abort

Result: 16/16 checks.

| Check | Result |
|---|---|
| Kills | 5: the leader twice (once forced), the other web pod twice (once forced), a runner once |
| Firings | **37/37**, each exactly once, every scheduled execution with its own ledger claim |
| Cross-pod abort | the API answered `pending` in 0.5 s; the execution on `kestrel-runner-1` ended `aborted` |
| DLQ, Quartz cron triggers, runner logs in S3 | empty, 0, present |

## Fixes found live (all in the repo)

1. **Bean wiring.** Subclassing `QuartzJobScheduleManagerService` broke its GORM `@Listener`
   registration ("target must be an instance of the declaring class"). The application context did
   not start. Kestrel now wraps the stock bean instead.
2. **AWS SDK collision.** Kestrel's AWS SDK on the application classpath broke the S3 log plugin's
   own SDK (`ServiceLoader`: "UrlConnectionSdkHttpService not a subtype"). The SDK is now shaded
   under `org.rundeck.kestrel.shaded`, and CI fails if anything unrelocated lands in the jar.
3. **Hibernate caches.** The per-JVM second-level and query caches (120 s TTL, no cross-server
   invalidation) made a project created on one web pod "not exist" on the other. Kubernetes mode
   disables them.
4. **Abort reply.** Cross-pod abort worked: the runner interrupted the execution 112 ms after the
   request. But the API waited 30 s for a reply, because grails-events drops the reply for
   `@Subscriber` methods. A first fix, a closure subscription in a Grails service, never
   registered. The listener now subscribes on the application `EventBus` from
   `KestrelSchedulerRuntime`, and every pod logs that it is listening.
5. **Docker Hub rate limit.** CI hit Docker Hub's anonymous pull limit. Base images are
   digest-pinned and now pulled through `mirror.gcr.io`.

## Not done

- **The 24-hour soak from ADR 0001.** For cost, the environment ran about 3.5 hours in total, with
  two 45-minute soaks and one 15-minute soak.
- **Scale-down orphan sweeper.**
- **Cross-pod ACL cache invalidation.**
- **Live log tail across pods.**

ADR 0003 lists these as known gaps.
