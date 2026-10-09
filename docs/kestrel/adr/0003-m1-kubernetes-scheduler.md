# ADR 0003: M1 Kubernetes scheduler as built

- Status: Accepted (2026-10-09)
- Implements: ADR 0001 §2 (scheduling without Quartz) and §3 (pool runners)

## Decision

`scheduler.mode: kubernetes` replaces Quartz cron triggers end to end:

```
job save ──▶ leader web pod (Lease kestrel-scheduler) ──▶ CronJob per (schedule, time zone)
                                                           │ every firing
                                                           ▼
                                   kestrel-trigger pod ──▶ SQS FIFO (dedup: bucket+minute)
                                                           │
                                                           ▼
       kestrel-runner pod ── claim job@minute in DynamoDB (conditional put) ──▶ ExecutionJob
```

| Piece | Where |
|---|---|
| Quartz → Kubernetes cron translation; exact or rejected at save | `kestrel-scheduler/…/CronTranslator` (checked against Quartz minute by minute over a year) |
| Buckets: jobs with an identical (schedule, zone) share one CronJob | `ScheduleBucket` |
| Lease election (client-go protocol), CronJob reconcile (create/replace/delete, label-scoped) | `LeaseLeaderElector`, `CronJobReconciler` |
| Firing → at most one execution per job and minute; stale-claim takeover | `FiringCoordinator`, `DynamoFireLedger` |
| Trigger: static Go binary, scheduled minute from the Job name | `kestrel-trigger/` |
| Grails wiring (SchedulesManager, hand-off hook, runtime loops, abort handler) | `rundeckapp/src/main/groovy/org/rundeck/kestrel/app`, `KestrelClusterEventsService` |
| Chart: web + runner StatefulSets, per-pod server UUID, RBAC | `deploy/helm/kestrel` |

### Exactly once

1. **SQS FIFO.** A retried trigger pod, or a second Job the CronJob controller creates for the
   same minute, carries the same deduplication ID (`bucket-minute`). SQS drops the copy.
2. **Fire ledger.** `job@minute` is claimed with `attribute_not_exists(pk)`. Every other runner
   and every redelivery sees the claim and does nothing.
3. **Message acknowledgement.** The message is deleted only after every job in the bucket has an
   execution id (reported by the upstream `beforeExecution` hook) or a deliberate skip. A runner
   that dies earlier leaves the message to be redelivered.
4. **Stale claims.** A claim still in `CLAIMED` after 180 s belongs to a dead runner and is taken
   over with a conditional update. Before taking over, the new runner checks whether the dead
   runner already created the execution. If it did, the firing happened: the claim is settled
   instead of re-run, and the dead runner's restart marks that execution incomplete.
5. **Engine hand-off timeout (60 s).** If the hand-off times out before the execution starts, the
   queued one-shot Quartz job is withdrawn, so it can never start late after a takeover.

Firings older than 15 minutes (`kestrel.max-lateness-minutes`), for example after a runner
outage, are recorded as `SKIPPED` rather than replayed as a burst. This is close to Quartz's
misfire behaviour; Quartz fires once on recovery instead.

## Deviations from ADR 0001 (and why)

- **The Quartz library stays as an in-JVM harness in M1.** Nothing is scheduled with Quartz:
  - there are no cron triggers and no schedule ownership;
  - every pod logs a check every 5 minutes, and `soak.py` fails if any cron trigger appears;
  - the execution-history cleaner is disabled in Kubernetes mode.

  Runners hand a firing to the upstream `ExecutionJob` through a one-shot local trigger, exactly
  as the cron trigger would have. This keeps the workflow engine, its auth context and its
  interrupt-based abort untouched. The headless engine (M3) removes the library.
- **Run-now executes on the web pod that received it.** Run-time secure options (passwords) stay
  in that pod's memory instead of crossing SQS. Moving them needs encrypted hand-off, which is M3
  with the isolated mode.
- **Sessions keep ALB stickiness.** Preauth re-authenticates from the oauth2-proxy headers on any
  replica, so losing stickiness costs a new Rundeck session, not a sign-in. Stateless JWT
  sessions are deferred.
- **One runner expands a whole bucket.** There is no per-job fan-out message yet. That is fine
  until a single schedule holds hundreds of jobs.

## Cross-pod behaviour

- **Abort.** Upstream publishes `cluster.abortExecution` when the execution belongs to another
  server, and OSS ships no listener for it. Kestrel's listener records `abortedby`, and the
  owning pod's 2-second poller interrupts the execution.
- **Server identity.** Each pod derives its server UUID from its StatefulSet name. A restarted
  pod's BootStrap therefore cleans up only its own interrupted executions.

## Known gaps (tracked for later milestones)

- The ACL cache is not invalidated across web pods (`cluster.clearAclCache` has no listener yet).
  Policy changes reach other pods only when their cache expires.
- Live log tail of an execution running on another pod shows output only after it completes
  (S3 log storage). Live tail through DynamoDB is ADR 0001 §3, later.
- Scale-down leaves executions of removed StatefulSet ordinals `running` until someone cleans
  them up. An orphan sweeper from the leader is the fix.
