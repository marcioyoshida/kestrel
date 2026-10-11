# ADR 0004: M2 DynamoDB persistence through a GORM datastore

- Status: Accepted (2026-10-09). M2a is built; M2b, M2c and M2d (no RDBMS, sorted indexes) are validated.
- Refines: ADR 0001 §4 (DynamoDB data model) and the M2 row of its milestone table.

## Context

ADR 0001 planned M2 as "DynamoDB providers for all 12 `DataProvider` interfaces, and rewrite the
~115 direct queries". A survey of the main sources at the fork base found:

- **The provider layer is thin.** About **323 direct GORM call sites** sit outside it: dynamic
  finders, criteria, `where`, `save` and `delete`. **85%** of them are on `Execution` (140) and
  `ScheduledExecution` (124), spread across `ExecutionService`, `ScheduledExecutionService`,
  controllers, jobs and GSP views.
- **Hibernate-only code is concentrated.** HQL strings, `sqlProjection` and
  `sqlRestriction`, `createAlias` and joins, correlated EXISTS subqueries, raw SQL
  (`JobTakeoverQueryBuilder`, BootStrap) and `session.createQuery` together live in roughly
  10 files.

Rewriting 323 call sites would make the monthly upstream rebase (ADR 0001, Consequences)
impractical.

## Decision

**Implement a GORM datastore on DynamoDB, and move domain classes onto it one association
cluster at a time with `static mapWith = "dynamodb"`.** Upstream code keeps calling GORM. Only
Hibernate-only call sites are rewritten.

The datastore is the module `kestrel-gorm-dynamodb`. It is built on GORM's key-value engine,
`NativeEntryEntityPersister`, the same base as GORM's own in-memory datastore and GORM for
Redis. It reuses the in-memory datastore's GORM plumbing: static API, validation, transaction
manager and connection sources.

| Concern | Design |
|---|---|
| Tables | One per entity hierarchy: `<prefix>-<entity>`, key `id` (S). Subclasses share the root's table, marked by a `discriminator` attribute (as GORM single-table inheritance). On-demand billing. All missing tables are created in parallel at startup. GORM routes cascades through the parent's persister, so every entry operation goes to the persister of the entity it is called with. |
| Ids | Numeric, as upstream exposes them (execution ids in URLs). Each JVM reserves blocks of 100 per entity with one atomic `ADD` on `<prefix>--ids`. |
| Index table | `<prefix>--index`, key `k` + `id`: `<root>\|<property>\|<value>` for indexed properties, and `<owner>#<association>#<owner id>` for one-to-many and many-to-many. Maintained by GORM's own indexing hooks. |
| Indexed properties | Every to-one association (its foreign key), anything mapped `index: true`, and properties listed in Kestrel configuration (`Entity.property`). Indexes are declared outside upstream classes. |
| Writes | The writes of one flush are merged per item (DynamoDB allows one operation per item per transaction) and committed with `TransactWriteItems`. An item and its index entries are atomic. Flushes above 100 items commit in atomic chunks of 100, with a warning. |
| Write-through | With no datastore transaction open, every save or delete is written immediately. Rundeck saves without flush on background threads, where no session-close flush happens. Inside `withTransaction`, writes are buffered and a rollback discards them. |
| Sessions and dirty checking | As with Hibernate, an entity loaded and changed without `save()` is written at flush. GORM's key-value engine writes only what was saved, so the session keeps each entity's loaded state and compares it at flush. To-one associations compare by id and lazy collections by their dirty flag, so the comparison never loads anything. The many side of a bidirectional one-to-many (`ScheduledExecution.executions`) is ignored, as Hibernate's inverse collections: counting it rewrote the job on every run. Inside another manager's Spring transaction (Rundeck's Hibernate `@Transactional` services), GORM calls share one session for that transaction, flushed before it commits but not when it is read-only or rolled back. The session is held under a private key with its own synchronization; binding it through GORM's session synchronization fails on rollback. Found live: job delete detaches its executions by setting `exec.scheduledExecution = null`, and without this, project delete then failed on the dangling references. |
| Validation | GORM's key-value base sets no validator registry, so constraints never ran (found by the API suite: duplicate job uuids, invalid option URLs accepted). The datastore creates the registry and registers GORM's generic `unique` constraint, checked with an index lookup at validation. |
| Optimistic locking | Every update is conditional on the item existing, so an item deleted elsewhere is never recreated; updates to items deleted in the same session are dropped. `version` is a conditional update. A conflict raises GORM's `OptimisticLockingException`, which existing upstream retry handlers already catch. |
| No RDBMS (M2d) | With `KESTREL_STORAGE=dynamodb` the app has no database server. The Hibernate plugin stays, because upstream services are `@Transactional` through its transaction manager, but it maps no class. Its datasource is forced to a private in-memory H2 with no schema and Liquibase off, so a leftover SQL query fails ("table not found") instead of reading shared data. Bootstrap fails if any domain class is not stored in DynamoDB. Readiness (`rundeckDataSourceHealthIndicator`) checks DynamoDB with one `GetItem`. RDS is created only for `storage.mode=rdbms` (CDK `-c rdbms=true`). |
| Sorted indexes (M2d) | `Entity.partition>sort` entries in the index table (`k = <root>\|<partition>\|<value>\|><sort>`, `id = <sort token>#<id>`), written in the item's batch. An ordered, paged query whose first order is the sort property and whose top-level conjunction fixes the partition reads entries in order, checks each against its item (stale entries are skipped), filters in memory, and stops at offset + max plus ties, so its result equals the in-memory plan's. Built once at startup for existing rows. Configured: executions by job and by project, by completion and by start; reports by job and by project, by completion. Totals still count every matching row; DynamoDB mode turns on upstream's count cache. Any query reading over 500 rows is logged with its plan. Index names are checked at startup. |
| Queries | Planner over the top-level conjunction: id or id IN → `GetItem`/`BatchGetItem`; equality or small IN on an indexed property → index table; otherwise a paginated `Scan`. Every criterion is then evaluated in memory, so a plan only narrows the result, never changes it. Projections (including `groupProperty`), ordering, offset and max apply after filtering. Unsupported criteria (`sqlRestriction`, correlated subqueries) **throw** instead of returning wrong rows. |

Verified against DynamoDB Local (`DynamoDatastoreSpec`):
- CRUD, finders, numeric ids and index plans;
- partial updates and null removal, with version conflicts;
- ranges, `ilike`, IN, OR, ordering, paging, count, max, group-by and distinct;
- to-one queries, one-to-many collections and association criteria;
- inheritance, transaction rollback, and bulk `updateAll`/`deleteAll`;
- ordered list associations, correlated EXISTS and null-only sparse indexes;
- flush-time dirty checking, the shared session inside a foreign transaction, and no write-back after delete.

## Plan

| Phase | Scope | Exit |
|---|---|---|
| **M2a** (built) | The datastore and its spec in CI | `DynamoDatastoreSpec` green on DynamoDB Local |
| **M2b** (validated on EKS) | Standalone aggregates on DynamoDB: `Project`, `User` + `AuthToken`, `Webhook`, `PluginMeta`, `StoredEvent`, `Storage`. Payloads above about 300 KB move to S3, because `Storage` allows 50 MB blobs and DynamoDB items cap at 400 KB. Grails wiring: datastore bean, `mapWith` switch in Kubernetes mode, IAM, CDK tables or prefix. | `validate.py` and `soak.py` green with these classes on DynamoDB |
| **M2c** (validated on EKS, [record](../validation/2026-10-10-m2c-eks.md); sorted indexes and the upstream suite move to M2d) | The job and execution cluster: `ScheduledExecution`, `Workflow`, `WorkflowStep`, `CommandExec`, `JobExec`, `PluginStep`, `Option`, `Notification`, `Orchestrator`, `Execution`, `ReferencedExecution`, `LogFileStorageRequest`, `JobFileRecord`, `ScheduledExecutionStats`. Rewrite the Hibernate-only sites. Add sorted indexes for the high-volume paths (executions by job/project and date, running executions), so lists don't scan. | Upstream API functional tests for jobs and executions green |
| **M2d** (validated: [no RDBMS](../validation/2026-10-11-m2d-no-rdbms.md), [sorted indexes](../validation/2026-10-11-m2d-sorted-indexes.md)) | No RDBMS in DynamoDB mode (see *No RDBMS* above). The remaining Hibernate-only sites reachable in that mode rewritten: job-list filters (`Restrictions`/`Subqueries`), forecast and cleanup HQL, `createAlias`, the JDBC takeover query. (Reports and execution metrics already moved in M2c, through in-memory projections.) Upstream's API suite runs in CI on DynamoDB Local and on upstream's MySQL (`kestrel-api-suite.yml`). Then sorted indexes for executions and reports. | ADR 0001's M2 exit: no API test fails on DynamoDB that passes on MySQL |

## Consequences and risks

- **Full scans.** Queries with no usable index scan the table. That is fine for small tables
  (projects, users, tokens, webhooks, plugin metadata) but not for executions and reports. M2c
  adds sorted indexes and pushes `max` and ordering down for those paths, and logs every scan on
  a large table.
- **Non-atomic large flushes.** Atomicity is per flush of up to 100 items. Rundeck saves
  aggregates one at a time, but cascades over large jobs (many options and steps) can exceed
  that.
- **Stale indexes after deletes.** An index entry pointing at a deleted item is skipped on read
  and does not corrupt results.
- **Hibernate-only queries.** These must be rewritten. The fallback for `Execution`/`ExecReport`
  free-text search is DynamoDB zero-ETL to OpenSearch (ADR 0001 §4), opt-in.
