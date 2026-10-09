# ADR 0001 — Kestrel: an EKS-native fork of Rundeck

- **Status:** Accepted (direction) — 2026-10-09
- **Fork base:** `rundeck/rundeck@d526b7c` (2026-10-08, Grails 7.2.2 / Spring Boot 3.5.16)
- **Fork depth:** *surgical* — keep Rundeck's web UI, REST API, job definition format, ACL policy
  model and plugin SPI; replace **storage**, **scheduling** and **execution placement**.

## Context

Rundeck is a Grails web application that stores everything in an RDBMS through GORM/Hibernate,
schedules with Quartz inside the web JVM, and executes workflows in that same JVM. It is
packaged as a deb/rpm and a container image, but the container is still "one stateful server".

Kestrel's requirements:

1. 100% Kubernetes runtime, installed by **Helm** on **AWS EKS**.
2. Runners are **CronJobs** or **StatefulSets**.
3. Database on **DynamoDB** (port/adapt the persistence layer).
4. **CloudFront** is the user-facing interface.
5. **GitHub** integration for workflow templates — **no SCM reference** (no server-side git
   checkout, no per-job SCM plugin state).
6. Cron schedules are **Kubernetes CronJobs** — **no Quartz**.
7. AWS performance at reasonable cost.
8. A **modernized UI** following current UX practice (see [ADR 0002](0002-ui-modernization.md)).

### What the upstream code gives us (surveyed at the fork base)

| Area | Upstream reality | Consequence |
|---|---|---|
| Persistence seam | 12 `DataProvider` interfaces in `rundeck-data-models/src/main/java/org/rundeck/app/data/providers/v1/` (Job, Execution, ExecReport, ReferencedExecution, JobStats, Project, Token, Storage, Webhook, User, PluginMeta) | DynamoDB implementations slot in here. |
| Persistence leakage | 23 GORM domain classes in `rundeckapp/grails-app/domain/rundeck/`; ~115 direct `createCriteria`/`withCriteria`/HQL/`findAllBy` calls in services (`ExecutionService` 23, `ScheduledExecutionService` 17, `ProjectService` 10, `FileUploadService` 10) | Each must be routed through a provider or rewritten. This is the bulk of the port. |
| Schema migrations | 37 Liquibase changelogs under `rundeckapp/grails-app/migrations` | Not ported. DynamoDB tables are created by the infra stack; item schema evolution is versioned in code. |
| Scheduling seam | `JobScheduleManager` / `SchedulesManager` interfaces in `core/` | The CronJob reconciler implements these. |
| Quartz leakage | `ScheduledExecutionService` calls `quartzScheduler` directly; Quartz also runs **run-now**, `ExecutionsCleanUp`, and the execution-mode timer plugin (`grails-execution-mode-timer`) | All four uses need a replacement, not just cron. |
| Clustering | `rundeck.clusterMode.enabled` exists, but schedule-ownership takeover / heartbeat is Enterprise-only | Not inherited. Kestrel gets HA by making the control plane **stateless**, not by porting cluster coordination. |
| Container | `docker/official` (remco templating, env `RUNDECK_*`, port 4440, default fixed `RUNDECK_SERVER_UUID`) | Reused as the base image for M0. |
| Name | "Rundeck" is a trademark; code is Apache-2.0 | Product name is **Kestrel**; NOTICE keeps upstream attribution. |

## Decision

### 1. Topology

```
            ┌──────────── CloudFront (+ WAF, Shield Std) ─────────────┐
 users ───▶ │ /assets/*, /static/*  → cached, immutable, 1y            │
            │ /api/*, /*            → CachingDisabled, all viewer hdrs │
            │ /github/webhook       → POST, no cache                   │
            └──────────────┬───────────────────────────────────────────┘
                           │ VPC Origin (no public ALB)
                 internal ALB (AWS Load Balancer Controller Ingress)
                           │
      ┌────────────────────▼─────────────────────┐        EKS (Graviton, Karpenter)
      │ kestrel-web  Deployment (stateless, N≥2) │──┐
      │  UI + REST API + reconciler (leader)     │  │ Lease-based leader election
      └──────┬────────────────┬──────────────────┘  │
             │                │ create/patch/delete  │
             │                ▼                      │
             │      CronJob per schedule bucket ─────┼──▶ SQS (run queue)
             │                                       │        │
             ▼                                       │        ▼
   DynamoDB tables ◀──────────────────────────────── kestrel-runner StatefulSet (pool)
   S3 (logs, large blobs)  ◀─────────────────────── or Job/CronJob pod (isolated mode)
```

### 2. Scheduling without Quartz

- **Source of truth** is the job item in DynamoDB. A **reconciler** (in `kestrel-web`, active only
  on the replica holding a `coordination.k8s.io/Lease`) converges Kubernetes CronJobs to it:
  write-through on job save/delete/enable/disable, plus a full resync every 5 minutes that deletes
  orphaned CronJobs (label `kestrel.io/managed-by=kestrel`).
- **Cron dialect.** Rundeck stores Quartz cron (seconds, year, `?`, `L`, `W`, `#`). Kubernetes
  CronJobs accept 5-field cron + `spec.timeZone`. On save, Kestrel translates expressions that map
  exactly (seconds=`0`, year=`*`, `?`→`*`) and **rejects the rest with a validation error**. Never
  silently approximate a schedule. A migration report lists existing jobs that cannot be
  expressed.
- **Schedule buckets (cost/scale).** In *pool* mode, jobs sharing an identical
  `(cron, timeZone)` share **one** CronJob whose tiny trigger container enqueues one SQS message per
  job in the bucket. This keeps the CronJob object count proportional to distinct schedules, not to
  jobs. *Isolated* mode jobs get their own CronJob.
- **Run-now / API run / retries** create a `queued` execution item and an SQS message. No in-JVM
  scheduler.
- **Execution cleanup** becomes DynamoDB **TTL** + S3 lifecycle rules.
- **Execution-mode timer** (scheduled enable/disable of executions) becomes a leader-only ticker
  in `kestrel-web`.
- **Concurrency policy.** Rundeck "multiple executions" maps to a conditional write on the job's
  `runningCount`, enforced by the runner, not by `concurrencyPolicy` (which can't see pool-mode
  runs).
- **Control plane outage tolerance.** Trigger pods write to SQS directly (Pod Identity), so
  schedules keep firing while `kestrel-web` is down.

### 3. Runners — two modes, chosen per job

| Mode | What runs | Use for |
|---|---|---|
| `pool` (default) | CronJob trigger (static ~10 MB image) → SQS → **`kestrel-runner` StatefulSet** (warm JVM, KEDA-scaled on queue depth, Spot) | Frequent or light jobs; low latency |
| `isolated` | A **Kubernetes Job** (from a CronJob, or created by the API for run-now) running the headless engine for exactly one execution | Heavy, infrequent, or jobs needing their own IAM role / node class / secrets |

Both modes report state to DynamoDB and stream logs to S3 (live tail via a short-lived DynamoDB
item that is compacted into S3 at the end). Abort is a status flag on the execution item, polled
by the runner every 2 s.

**Critical path:** the workflow engine is embedded in Grails services today. M1 runs the runner
as the *same image* with a `runner` Spring profile (web disabled). The slim headless engine needed
for fast-starting isolated pods (with AppCDS to trim JVM start time) is extracted in M3.

### 4. DynamoDB data model

Per-aggregate tables, not one single table: they need different TTL, streams and capacity, and
the providers are already split per aggregate. On-demand capacity, PITR on, KMS CMK, deletion
protection on.

| Table | PK / SK | GSIs | Notes |
|---|---|---|---|
| `jobs` | `JOB#<uuid>` / `DEF` | `byProject` (`project`, `groupPath/name`); sparse `scheduled` (`schedBucket`, `uuid`) | Workflow, options and notifications **embedded** as one document → atomic save with optimistic `version`; >300 KB definitions spill to S3. |
| `executions` | `EXEC#<id>` / `META` | `byJob` (`jobUuid`, `dateStarted`); `byProject` (`project`, `dateStarted`); sparse `running` (`RUN#<shard 0-9>`, `dateStarted`) | Also holds the activity-report projection (`ExecReport`) as attributes. TTL from project retention. |
| `ids` | `SEQ#execution` | — | Rundeck exposes **numeric** execution ids. Each replica reserves blocks of 100 via an atomic `ADD` → ~1 write per 100 executions. |
| `auth` | `TOKEN#<sha256>` / `USER#<login>` / `WEBHOOK#<id>` | `byUser` | Tokens stored hashed. |
| `storage` | `PATH#<keys/... or projects/...>` | `byParent` (directory listing) | Key storage + project config. Already encrypted by Rundeck's converters; values >300 KB to S3. |
| `state` | mixed: plugin meta, job stats, file records, referenced executions, stored events | as needed | Low volume; TTL on stored events. |

**Activity / history queries.** Upstream filters reports with arbitrary criteria. Kestrel supports
the indexed paths (project + time range, job, status via `running`, user via filter expression on
a bounded page) and documents the remainder. If full-text history search turns out to be needed,
add DynamoDB **zero-ETL → OpenSearch Serverless** as an opt-in, not a default (it has a cost
floor).

### 5. GitHub integration (no SCM reference)

- A **GitHub App** (installation per org), not the jgit SCM plugin. No checkout on any pod.
- A repo contains `kestrel.yaml` (maps paths → projects, branch, delete policy) and job/template
  YAML in Rundeck's existing job format (reusing the importer, `uuidOption=preserve`).
- **On push** to the tracked branch: verify `X-Hub-Signature-256`, fetch changed files through the
  Git Trees/Contents API, validate, upsert. Each job stores only `source: {repo, path, commit}` for
  audit and display.
- **On pull request:** run the same validation and publish a **Check Run** (cron dialect, ACL,
  option schema), so broken templates never merge.
- Jobs sourced from GitHub are read-only in the UI with an "Edit in GitHub" action. One-way flow
  means no two-way SCM state to drift.

### 6. Identity and access

- UI/API sign-in via OIDC (Cognito or corporate IdP) through an **oauth2-proxy sidecar** in the
  web pod. The sidecar strips any client-supplied identity headers and sets
  `X-Forwarded-Uuid` / `X-Forwarded-Roles` from the verified token's claims. Rundeck binds to
  `127.0.0.1` (`RUNDECK_SERVER_ADDRESS`) so the sidecar can't be bypassed via the pod IP.
  A NetworkPolicy admits only the ALB to the proxy port.
  - **Rejected: ALB OIDC action + Rundeck preauth.** The ALB sets only `x-amzn-oidc-*` headers.
    It neither sets nor strips `X-Forwarded-Roles`, so a client could send `X-Forwarded-Roles: admin`
    through CloudFront and be trusted. The ALB also can't emit group claims as a header.
  - API tokens bypass the proxy on `/api/*` (`--skip-auth-route`) and are checked by Rundeck itself.
    `skip_auth_strip_headers` must stay on, and the identity headers must be declared as the
    proxy's injected headers, so they are stripped on skipped routes too. Integration test: send
    a forged `X-Forwarded-Roles: admin` to `/api/*` and to `/*` and expect 401/redirect.
- ACL policies (`.aclpolicy`) are kept and stored in the `storage` table.
- HTTP sessions: M0 uses ALB stickiness. M1 moves to stateless (signed JWT session cookie) so any
  replica serves any request.
- Pods use **EKS Pod Identity**: web, runner-pool and each isolated runner class get separate IAM
  roles.

### 7. Cost and performance levers

| Lever | Effect |
|---|---|
| Graviton (arm64) for all node pools; multi-arch image | ~20% lower $/vCPU; JVM runs well on arm64 |
| Karpenter: `web` NodePool on-demand; `runner` NodePool **Spot**, diversified instance families, consolidation on | Runners are interruptible by design (SQS redelivers; executions are checkpointed per step) |
| **Gateway VPC endpoints** for DynamoDB and S3 (free) | Removes NAT data-processing charges for the hottest traffic |
| Interface endpoints only for SQS/ECR/STS/Logs where traffic justifies (~$7/AZ/month each) | Decide per environment |
| DynamoDB on-demand → provisioned + autoscaling once traffic is predictable; TTL instead of delete jobs | TTL deletes are free |
| S3 Intelligent-Tiering for logs; lifecycle expiry per project retention | Logs are the largest data by volume |
| CloudFront caching of hashed static assets; Brotli; HTTP/3 | Most UI bytes never reach the cluster |
| Schedule buckets + `ttlSecondsAfterFinished` + history limits on CronJobs | Bounded etcd object count |
| ECR pull-through cache + small trigger image | Fast pod start, no Docker Hub rate limits |
| JVM: container-aware heap (`MaxRAMPercentage`), G1, AppCDS for runner images | Lower memory requests; faster isolated starts |
| Single NAT gateway in non-prod; one per AZ in prod | ~$32/month each plus data |

Approximate idle floor for a small prod (us-east-1, to be verified against current pricing): EKS
control plane ~$73/mo + 2× `m7g.large` web ~$120/mo + NAT + near-zero DynamoDB/SQS/CloudFront at
low volume. Runners scale to zero on Spot when idle.

## Milestones

| | Scope | Exit criteria |
|---|---|---|
| **M0** | Fork + rebrand scaffold; Helm chart on EKS with upstream image, **RDS still in place**, single control-plane replica, internal ALB + CloudFront VPC origin, S3 log store | `helm install` on a fresh EKS → login via CloudFront, run a job, log in S3 |
| **M1** | Quartz removed: CronJob reconciler, SQS run queue, `kestrel-runner` StatefulSet (same image, runner profile), leader election, stateless sessions → web scales to N | Kill any web pod mid-schedule: no missed or duplicate firings over a 24 h soak |
| **M2** | DynamoDB providers for all 12 interfaces; rewrite the ~115 direct queries; RDS removed; RDS→DynamoDB migration tool | Upstream API functional test suite green against DynamoDB |
| **M3** | GitHub App (push sync + PR checks); headless engine + isolated mode; AppCDS | Template PR → check run → merge → job live in < 60 s |
| **M4** | UI modernization (ADR 0002) running in parallel from M1; cost tuning; load test (10k jobs, 1k distinct schedules) | Documented $/month at reference load |

## Consequences

- Upstream merges get harder as the persistence and scheduler layers diverge. Mitigation: keep
  changes behind the upstream interfaces where they exist, and rebase on upstream releases
  monthly through M2.
- Quartz-only cron features (seconds, `L`/`W`/`#`, year) are not supported. This is deliberate
  and visible to users.
- Arbitrary activity filtering is reduced unless the OpenSearch option is enabled.
- Upstream plugins that use GORM directly will not work on DynamoDB. Plugins written against the
  public SPI continue to work.

## Open questions (owner)

1. AWS account / region / profile for the reference environment.
2. Identity provider: Cognito, or an existing corporate OIDC?
3. "Workflow templates": is Rundeck job YAML enough, or are parameterized templates
   (`template:` + per-environment `values:`) wanted in M3?
4. Data migration from existing Rundeck installations: required for M2, or greenfield only?
