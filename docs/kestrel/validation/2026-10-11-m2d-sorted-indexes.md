# M2d: sorted indexes for execution and report lists, 2026-10-11

Before this change, an ordered, paged list read every row of its project. A list of executions
or of history (reports) loaded all the project's rows from DynamoDB, filtered and sorted them in
memory, and returned 20. A sorted index keeps a partition's rows in sort order, so a page now
reads only the rows up to its offset plus its size. The design is in
[ADR 0004](../adr/0004-m2-dynamodb-gorm-datastore.md) under *Sorted indexes*.

**Configured indexes:**
- executions by job (`jobUuid`) and by project, each by `dateCompleted` and by `dateStarted`;
- reports by job (`jobId`) and by project, by `dateCompleted`.

For single-job execution lists, which filter through `scheduledExecution { uuid }`, DynamoDB mode
adds the redundant `eq('jobUuid')` so the job's index serves them.

Final image: `kestrel:59a38abbf5c0` (commit `59a38abbf5`). The scale check and soak ran on `20d5ce68337e` and `b9c134cd3d7e`; later commits changed only the orphan sweeper's status and scripts.

## Results

| Check | Result |
|---|---|
| `DynamoDatastoreSpec` (DynamoDB Local) | **36/36**. Pages equal the in-memory plan both ascending and descending, at several offsets, with ties and nulls. Also covered: a finished run moving up the list, deletes, fallbacks to the old plans, a subclass index, and building indexes for existing rows. |
| Upstream API suite, DynamoDB vs MySQL (`b9c134cd3d`, `20d5ce6833`) | **328 passed, 5 failed on both, 0 regressions**, both runs |
| EKS `validate.py`, M2c `--eks` and M1 gaps | `b9c134cd3d7e`: **21/21, 25/25, 7/7**. Final image `59a38abbf5c0`: **21/21, 25/25, 8/8**; the eighth check was added for the sweeper, see below. |
| EKS `validate_sorted.py --eks`, 1200 executions and 1200 reports (`20d5ce68337e`) | **11/11** |
| EKS `soak.py --minutes 30`, 6 pod kills (`b9c134cd3d7e`) | **16/16**: 75 of 75 firings exactly once (33, 33, 16 executions), 0 duplicates, a cross-pod abort, an empty DLQ |
| Local `validate_sorted.py`, 300 executions | all page checks pass |

**Scale check on EKS, in detail.** The scale check imported a project with one job, 1200
executions and 1200 reports, then checked the following:
- **Order:** execution pages at offsets 0, 20, 600 and 1180 match Rundeck's order exactly
  (`dateCompleted desc, id desc`, with ties), with total 1200. The job's page and the project
  history are newest first.
- **Reads:** reads below 500 rows are not logged, so pages 1 and 2 read fewer. The pages at
  offset 600 and 1180 read 622 and 1200 rows: offset + max, plus ties.
- **Totals:** totals read their 1200 rows through `index:project`, never by scanning the table.
- **Latency:** each page took 0.6–1.0 s through CloudFront. The job page took **0.9 s**; before
  fix 3 it took 6.8 s.
- **Import:** 1200 executions and their reports imported in 559 s, about 0.25 s per execution
  with all its indexes. An earlier run's 1030 s overlapped another import still running on the
  server. The import outlasts CloudFront's 60 s origin timeout, so the check waits it out.

## Found on the way (all fixed)

1. **Report indexes never applied.** The report's project property is `project`; its column is
   named `ctx_project`. The M2c entry `BaseReport.ctxProject` was silently ignored. Even with the
   right name, an entry named for `BaseReport` did not reach `ExecReport`, the class reports are
   saved and queried as. History totals therefore scanned the whole report table (seen locally:
   1805 rows read for a 300-report project). Now:
   - entries apply to subclasses;
   - a misnamed entry fails startup.
2. **Every execution insert read all earlier runs of its job.** The job's `executions` collection
   index read its whole partition to compute a position, which costs O(n) per run. Positions
   matter only for lists such as workflow steps; for sets the insert is now one put.
3. **An association target was read once per candidate row.** A job page counting 1200
   executions read the same job 1200 times and took 6.8 s on EKS. Targets are now read once per
   query.
4. **Index build failed on EKS.** It used `BatchWriteItem`, which the pod role does not allow
   (DynamoDB Local has no IAM). It now writes transactions of puts, authorized as `PutItem`.
5. **The orphan sweeper reported a user abort.** The sweeper marked a departed pod's executions
   with no status, which Rundeck records as cancelled (`aborted`). It now uses `incomplete`, as
   upstream's boot cleanup does. A new check re-owns a running execution, in DynamoDB, by a server
   UUID that no pod carries, and expects `incomplete` (Rundeck shows `other`/`incomplete`). The
   existing scale-away check races: the scale-down signals the pod, which may still finish its own
   execution as `failed` first.
6. **Execution JSON could say "running" with an end date.** The API re-read each execution after
   computing its status, and on DynamoDB the second read can see a later state. This made
   upstream's JSch tests fail intermittently on DynamoDB only. The response now renders the one
   object the status came from.
7. **Indexes added later hid existing rows.** Indexes, plain and sorted, are now built at startup
   for rows that existed before them: one scan per table, then a marker.

## Limits

- **Totals still count every matching row.** Upstream's per-pod count cache (30 s) is on by
  default in DynamoDB mode.
- **Deep pages read their offset.** A page at offset k reads k + max rows, as offset paging does
  everywhere; the UI and API page from the top.
- **Adding a sorted index during a rolling upgrade.** Rows that older pods write after the new
  index is built are missing from it. Roll all pods before relying on a new index, or delete its
  marker to rebuild it.
