# Validation: M3 isolated jobs (ADR 0006), locally and on EKS

- Date: 2026-10-11
- Code: `main` at `d0c2122434` (image `kestrel:d0c212243490`), plus the chart change that marks
  isolated pods `karpenter.sh/do-not-disrupt` (commit after `947446a5a7`, applied with
  `helm upgrade`; no image change).
- Script: `scripts/kestrel/local_validate_m3_isolated.py`. Locally it runs against
  `scripts/kestrel/fake_kube.py`; with `--eks` it runs against the reference cluster.
- Environment: KestrelRef (EKS Auto Mode 1.36, 2 web pods, 1 runner, DynamoDB storage, S3 logs),
  with runner classes `default` and `probe`. Each class has its own service account and IAM role.
  The environment was torn down afterwards.

## Results

| Run | Checks | Notes |
|---|---|---|
| Local (fake Kubernetes, at most 2 pods at a time) | **29/29** in 768 s | each "pod" is a local Kestrel JVM |
| EKS: `validate.py` (pool mode, edge, sign-in) | **21/21** | no regression |
| EKS: isolated checks | **31/31** in 1278 s | the 29 local checks plus 2 IAM checks |

### What the checks cover

**Run now:**
- The execution is `queued` and owned by its Job's server UUID.
- The Job is built from the class's PodTemplate.
- The run happens in that pod (role `isolated`, server UUID from the Job name).
- The pod exits 0 and the Job is Complete.

**IAM (EKS only).** Each pod calls STS `GetCallerIdentity` with its Pod Identity credentials,
signed with `curl --aws-sigv4`:
- class `default` → `assumed-role/KestrelRef-IsolatedDefaultPodRole…`;
- class `probe` → `assumed-role/KestrelRef-IsolatedProbePodRole…`.

**Secure options.** A value typed at run time reaches the pod: the job prints its SHA-256 prefix.
- It travels in a Secret owned by the Job (its `ownerReferences` uid is the Job's).
- It never appears in the Job spec.

**Failures and retries:**
- A failing job: the execution fails, the pod exits 3, and the Job is Failed by its
  `podFailurePolicy` with no pod retry.
- A job with `retry: 1`: the retry execution runs in the same pod, and the pod exits 3 only after
  the retry ends.

**Abort:**
- Aborted while queued: upstream records the abort as pending. The pod then starts, ends the
  execution as `aborted` without running it, and exits 3.
- Aborted while running: the pod interrupts the run, and no output appears after the abort.
  The status reads `aborted` 24–28 s after the request on EKS (10 s locally).

**Pod loss.** The pod is force-deleted mid-run on EKS (killed locally). The Job starts a
replacement pod, which has the same server UUID. Its boot marks the run incomplete, and it exits 3
without running it again. The output shows the step started once.

**Sweeper.** A Job deleted before its pod ran: the leader marks the queued execution incomplete
194 s later on EKS (two sweeps, as designed).

**Run later** of an isolated job is refused with an explanation (HTTP 400).

**Schedule:**
- An isolated job gets its own CronJob (`kestrel-i-…`, schedule `0-59/1 * * * *`, time zone
  `UTC`, class `probe`), and no pool bucket CronJob.
- Three consecutive minutes ran **once each**, each in the pod of its own minute (the Job named
  after that minute).
- Switching the job back to the pool deletes its CronJob.

**Other:**
- A job naming an unknown runner class is rejected at import.
- The runner class round-trips through YAML export.
- Pool jobs still run on a web pod with no Job.
- No unexpected ERROR lines on web or isolated pods.

### Timings on EKS

| | Seconds |
|---|---|
| Kubernetes Job created → pod scheduled (Auto Mode added a node) | 23 |
| Job created → container started (image pull included) | 31 |
| Run-now: queued → running (pod scheduling + application start) | 88–100 |
| Scheduled minute → run start, mean / max (3 firings) | 88 / 96 |

Locally, with no node to provision and no image to pull, queued → running is 55–57 s: the
application start alone. Step 2 of the M3 plan (headless engine, AppCDS) aims at that start time.

## Found and fixed during validation

1. **A run-now never started in the isolated pod** (local harness, before EKS). Upstream schedules
   run-now as a *pending* Quartz trigger and releases it on the execution's next save. The pod
   saved the execution before scheduling, so nothing released it. The pod now releases it
   explicitly.
2. **Node consolidation versus running pods** (EKS). As isolated pods came and went, EKS Auto
   Mode added nodes and then consolidated them. It moved both web pods, and one API call during
   the move timed out at CloudFront (504). It could equally have evicted a *running* isolated pod;
   the design survives that (retry, then incomplete), but it would kill long jobs for nothing.
   Isolated pods are now annotated `karpenter.sh/do-not-disrupt: "true"`.
3. **`teardown.sh` left Kestrel's own CronJobs and Jobs behind.** They are not Helm resources and
   would keep starting pods. Teardown now deletes them before `helm uninstall`.
4. **Fixes to the checks:**
   - **Logs:** the first read of a finished run's log returns empty while the web pod fetches it
     from S3 (upstream's normal behaviour for any execution another pod ran), so the check reads
     again.
   - **Finished pods:** Auto Mode removes idle nodes and their finished pods with them, so exit
     codes come from the Job's failure-policy condition, and pod logs are kept as each Job
     finishes.
   - **The local harness** must run Rundeck in cluster mode, as the chart does. Without it,
     upstream aborts a remote execution locally and the pod never hears of it.

## Not covered

- **A spot interruption.** `podFailurePolicy` retries on `DisruptionTarget` without counting it,
  but no node was interrupted.
- **Long runs and big fan-out.** At most about 4 isolated pods ran at once.
- **The abort delay.** Why an abort takes 24–28 s to show on EKS (a 2 s poll in the pod) was not
  investigated.
- **Upstream ERROR lines on clean-up.** When the sweeper (or a replacement pod) ends a run that
  another pod held, upstream logs a failed log-storage request for it: the log is not on that pod.
  The checks expect these lines for the two runs they orphan on purpose.
