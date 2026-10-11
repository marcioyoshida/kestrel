# ADR 0006: M3 isolated jobs, a Kubernetes Job per run, as built

- Status: Accepted (2026-10-11), step 1 of the M3 runner work: isolated runs on the same image.
  The slim headless engine and AppCDS (step 2) come later, measured against this baseline.
- Refines: ADR 0001 §3 (runner modes) and ADR 0003 ("run-now stays on the web pod").

## Decision

### Choosing isolated mode

A job opts in with a built-in execution plugin, so the choice lives in the job definition and
round-trips through export, import and GitHub sync:

```yaml
plugins:
  ExecutionLifecycle:
    kestrel-isolated:
      runnerClass: default
```

- **In the UI**, the job editor's Execution Plugins tab shows it as "Run in its own Kubernetes
  pod", with the runner class as a select.
- **Unknown classes are rejected** when the job is saved or imported, because the select only
  offers configured classes.
- **The plugin does nothing at run time** except note in the execution log where the run
  happened. A run that is not isolated (see Limits) gets a warning there.

### Runner classes

The chart's `isolated.classes` define the classes. Each class is rendered as a Kubernetes
`PodTemplate` named `<release>-isolated-<class>`. It has the same image and configuration as
the web and runner pods, run in role `isolated`, plus the class's own settings:

- service account (and so its EKS Pod Identity IAM role); by default the chart creates
  `<release>-isolated-<class>`;
- resources, node selector, tolerations, extra environment;
- `activeDeadlineSeconds`, stored as an annotation, which caps pod start plus run.

Kestrel reads the PodTemplate whenever it creates a run or renders a CronJob. A `helm upgrade`
(a new image, new resources) therefore reaches the next run without touching any job.

### Run now

On a web pod, upstream's run-now creates the execution as usual. Then, instead of handing it to
the local engine:

1. The execution is set to `queued` and given the server UUID of a new Job,
   `kestrel-run-<id>-<random>`.
2. The Job is created from the class's PodTemplate, with `KESTREL_ISOLATED_EXECUTION=<id>`.
3. **Secure options typed at run time** go into a Secret with the same name, owned by the Job.
   The pod mounts it read-only, and Kubernetes deletes it together with the Job. The values never
   appear in the Job spec. Web pods may create Secrets, not read them. This closes the ADR 0003
   gap that kept run-now on the web pod.

**In the pod.** The pod boots the full application. If the execution was aborted while queued,
it is marked aborted without running. Otherwise it is set to running, with `dateStarted` = now,
and handed to the upstream engine exactly as on a web pod.

**How the pod exits.** The pod waits until:
- the engine is idle, including a retry with a delay;
- every execution of this server has ended;
- the log store has nothing in flight.

Then it exits 0 if the run succeeded, or 3 if it failed or was aborted.

### Schedules

An enabled, scheduled isolated job gets its own CronJob, `kestrel-i-<hash of uuid>`, with the
schedule and time zone of its job. The leader's reconciler converges these together with the
pool's bucket CronJobs, and the same label scopes both.

Each firing's pod reads the scheduled minute from its Job name (as the trigger does) and claims
`job@minute` in the M1 fire ledger. It then runs the job through the same launcher as pool runners.

- Pool buckets never include isolated jobs.
- A job switched between modes at the same minute still runs at most once, because both paths
  claim the same ledger key.

### Identity, retries and failures

Every isolated pod takes its server UUID from its **Job name**: the chart maps
`KESTREL_POD_NAME` to the `batch.kubernetes.io/job-name` label. Each Job has:

- `backoffLimit: 2`, so a pod that crashes or is lost is replaced;
- a `podFailurePolicy`:
  - exit code 3 fails the Job without a retry, because the execution ran and failed;
  - `DisruptionTarget` (spot interruption, eviction) retries without spending the backoff budget;
- `ttlSecondsAfterFinished: 3600`.

A retried pod has the same server UUID as the pod it replaces:
- **It never runs the execution twice.** Upstream's boot clean-up marks the previous pod's
  running execution incomplete. A queued execution is not cleaned up, so it still runs.
- **The firing is not claimed twice.** The retry takes over its own claim at once, and settles
  it if the earlier pod had already created the execution.

The leader's orphan sweeper treats a server UUID as alive while its Job is unfinished. When a Job
fails or is deleted with an execution still running or queued, that execution is marked
incomplete after two sweeps, about 2 to 4 minutes later.

### RBAC and IAM

Only web pods call the Kubernetes API. They may now also:
- list, create and delete Jobs;
- get PodTemplates;
- create Secrets.

Isolated pods have no service-account token; Pod Identity uses its own. In the reference
environment, each class's service account has its own IAM role with the same data, ledger and log
access as runners, and no SQS.

## Limits (step 1)

- **Start-up time.** A run waits for a full application boot (see the validation record for the
  measured times), so isolated mode suits heavy or infrequent jobs. Pool jobs are unaffected.
  Step 2 (headless engine, AppCDS) targets this.
- **Run later is refused.** The run would otherwise happen on a web pod with the web pod's
  identity.
- **Job references run in place.** A job reference step runs the referenced job inside the
  calling pod, as upstream does. The execution log warns that the run is not isolated.
- **Aborting a queued run takes effect when its pod starts.** It never runs. A pod that cannot
  start at all is ended by the Job's deadline and then the sweeper.

## Consequences

- **Exit codes are a contract** between the chart, the Job template and the runner:
  - 0: succeeded, or nothing to do;
  - 3: the run failed, was aborted, or cannot run;
  - anything else: retry the pod.
- Every isolated run is a Kubernetes Job, so `kubectl get jobs -l app.kubernetes.io/component=isolated`
  lists them, labelled with job, execution and class.
- **The local harness** (`scripts/kestrel/fake_kube.py`) fakes the parts of Kubernetes these
  checks need, running each "pod" as a local JVM. It makes the flow testable without a cluster.
  It does not prove IAM, scheduling or node behaviour; the EKS check does.
