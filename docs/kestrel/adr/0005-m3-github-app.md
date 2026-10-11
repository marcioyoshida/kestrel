# ADR 0005: M3 GitHub App sync and pull-request checks, as built

- Status: Accepted (2026-10-11). Push sync and PR checks are built and checked locally against a
  fake GitHub. The EKS exit test needs a real GitHub App, which only the owner can create.
- Refines: ADR 0001 §5 (GitHub integration) and the M3 row of its milestone table.
- Not in this ADR (still M3): the headless engine and isolated run mode, AppCDS, and the
  read-only "Edit in GitHub" UI for synced jobs.

## Decision

### Files in the repository

`kestrel.yaml` at the repository root:

```yaml
branch: main            # the branch that syncs (default main)
onDelete: delete        # delete | keep: what happens to the jobs of a removed file
projects:
  - path: jobs/ops      # *.yaml / *.yml at or below this directory
    project: ops        # the Rundeck project they belong to
```

- A job file belongs to the mapping with the longest matching path. Files outside every mapping are
  ignored.
- Job files hold Rundeck job YAML, the export format, unchanged.
- **Every job needs a `uuid`.** The uuid is the job's identity: a job can move between files or be
  renamed without losing its history.
- Parameterized templates (`template:` + `values:`) wait for owner question 3 in ADR 0001.

### The webhook

`POST /api/<version>/kestrel/github/webhook`:

- **Authentication** is GitHub's `X-Hub-Signature-256` HMAC over the raw body, compared in
  constant time; there is no Rundeck token.
  - `/api/*` already bypasses the sign-in sidecar, so CloudFront and oauth2-proxy need no
    change. `SetUserInterceptor` lets this one action through without a user.
- **Repositories** must be on an allowlist (`owner/repo` or `owner/*`). A delivery from any other
  repository is refused with 403, so installing the App on another repository syncs nothing.
- **Work happens before the response**, so GitHub's delivery log shows the outcome:
  - 200: synced;
  - 422: some files failed, with `path:line: message` for each problem;
  - 202: ignored (another branch, a tag, already synced, no `kestrel.yaml`);
  - 502: GitHub's API failed.

### Push

A push syncs the branch's **current head**, not the pushed commit. Two deliveries racing on two
web pods therefore converge on the same state, and every operation is idempotent: upsert by
uuid, delete by uuid.

**From the last synced commit.** Kestrel reads only the changed files, using the compare API from
the last fully synced commit. That commit is stored in config storage at
`kestrel/github/<repo>/<branch>`.

**Full sync.** In these cases Kestrel instead lists the whole tree and deletes this repository's
managed jobs that no file holds any more:
- there is no previous sync;
- the history diverged (a force push);
- `kestrel.yaml` changed;
- GitHub truncated the compare at 300 files.

**Import** works like upstream's SCM importer:
- `loadImportedJobs` runs with `update` and uuid `preserve`, as the configured identity
  (`kestrel.github.user` and `.roles`). That identity's ACLs decide what a repository may write.
- Each job is marked with plugin meta `kestrel-github`, holding `{repo, path, commit}`.
- `issueJobChangeEvents` fires, so the M1 reconciler converges CronJobs as it would for a UI edit.

**Deletes and ownership:**
- A uuid that left a changed or removed file and appears in no new file is deleted, unless
  `onDelete: keep`.
- Only jobs carrying this repository's mark are ever deleted.
- An existing unmarked job with the same uuid in the mapped project is adopted.
- A uuid synced from **another** repository, or living in another project, is refused.

**Retries.** The commit is recorded only when every file synced. A failure makes the next push
retry from the same base.

### Pull requests

`opened`, `synchronize`, `reopened` and `edited`, plus `rerequested` on the check run or suite,
create a **"Kestrel" check run** on the head commit:

- **What it checks:** `kestrel.yaml`, plus every added or modified mapped job file at the head.
  Nothing is saved.
- **The checks:**
  - YAML syntax;
  - a missing or duplicated uuid;
  - upstream's `validateJobDefinition`, which includes Kestrel's cron dialect;
  - the identity's create or update ACL;
  - name clashes;
  - a uuid owned by another project or repository.
- **Annotations** land on the job's first line, one per problem, sent 50 per request.
- **The summary** names removed files whose jobs the merge would delete.
- **When there is no check run:** the PR targets a branch `kestrel.yaml` does not track, or the
  head has no `kestrel.yaml`.

### Configuration

The Helm chart (`github.*`, web pods only) sets the App's configuration as environment variables:

- `KESTREL_GITHUB_APP_ID`
- `_REPOS`
- `_USER` (default `kestrel-github`)
- `_ROLES`
- `_API_URL` (GitHub Enterprise)
- `_PRIVATE_KEY` and `_WEBHOOK_SECRET`, from `github.existingSecret`

With an App id set, Kestrel refuses to start if any of the others is missing; without one, the
integration is off and the webhook answers 404.

**Authentication to GitHub.** The App authenticates with an RS256 JWT (60 s clock-skew margin,
9-minute lifetime). It exchanges the JWT for installation tokens, cached until 5 minutes before
they expire. GitHub's PKCS#1 key is accepted as downloaded.

**Code layout.** The GitHub client, the sync logic and the file parsing live in `kestrel-github`,
a plain Java module with no Grails. The Rundeck side is the `JobTarget` implementation:
`RundeckJobTarget`.

## Consequences

- **The webhook is synchronous.** GitHub stops waiting after 10 s. A first full sync of a large
  repository can outlast that: GitHub records a timeout, but the sync completes. If it fails, the
  next push retries it. A queue (the M1 SQS run queue) is the fix if this becomes common.
- **Editing a synced job in the UI is not blocked yet.** The next sync that touches its file
  overwrites the edit. The read-only UI is still to come.
- **The check run and the import can disagree** when ACLs or other jobs change between the PR
  and the merge. The push reports that as a 422 with the problem, and the commit stays unsynced.
