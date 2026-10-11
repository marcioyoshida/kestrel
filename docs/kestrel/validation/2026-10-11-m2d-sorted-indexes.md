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

Final image: `kestrel:IMAGE`.

## Results

| Check | Result |
|---|---|
| `DynamoDatastoreSpec` (DynamoDB Local) | **36/36**. Pages equal the in-memory plan both ascending and descending, at several offsets, with ties and nulls. Also covered: a finished run moving up the list, deletes, fallbacks to the old plans, a subclass index, and building indexes for existing rows. |
| Upstream API suite, DynamoDB vs MySQL (`b9c134cd3d`) | **328 passed, 5 failed on both, 0 regressions** |
| EKS `validate.py` / M2c `--eks` / M1 gaps (`b9c134cd3d7e`) | **21/21, 25/25, 7/7** |
| EKS `validate_sorted.py --eks`, 1200 executions and 1200 reports | SORTED |
| EKS `soak.py --minutes 30` | SOAK |
| Local `validate_sorted.py`, 300 executions | 10/10 |

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
5. **Execution JSON could say "running" with an end date.** The API re-read each execution after
   computing its status, and on DynamoDB the second read can see a later state. This made
   upstream's JSch tests fail intermittently on DynamoDB only. The response now renders the one
   object the status came from.
6. **Indexes added later hid existing rows.** Indexes, plain and sorted, are now built at startup
   for rows that existed before them: one scan per table, then a marker.

## Limits

- **Totals still count every matching row.** Upstream's per-pod count cache (30 s) is on by
  default in DynamoDB mode.
- **Deep pages read their offset.** A page at offset k reads k + max rows, as offset paging does
  everywhere; the UI and API page from the top.
- **Adding a sorted index during a rolling upgrade.** Rows that older pods write after the new
  index is built are missing from it. Roll all pods before relying on a new index, or delete its
  marker to rebuild it.
