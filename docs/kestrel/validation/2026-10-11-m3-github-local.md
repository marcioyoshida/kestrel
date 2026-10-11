# M3 GitHub App: local end-to-end check (2026-10-11)

**What ran:**
- Kestrel `bc1c0d70b086`, the CI war for that commit, ran locally with `scripts/kestrel/local-run.sh`.
- Storage was `storage.mode: dynamodb` on DynamoDB Local, with no RDBMS.
- `scripts/kestrel/local_validate_m3.py` played GitHub's side:
  - It served a fake GitHub REST API (`KESTREL_GITHUB_API_URL`).
  - It verified the App's RS256 JWT against the App's public key.
  - It sent HMAC-signed webhook deliveries to `/api/60/kestrel/github/webhook`.

**Result: 27/27**, with no unexpected ERROR lines in the server log.

| Step | Result |
|---|---|
| `ping`; bad signature; repository outside `KESTREL_GITHUB_REPOS` | 200; 401; 403 |
| First push: full sync of 3 files into 2 projects | 200. The cron job is scheduled with a next fire time. **Push → job live in 5.4 s** |
| Same push delivered again | 202 `already synced`. Nothing written |
| PR with a bad crontab, a job without a uuid, and invalid YAML | Check run `Kestrel` = failure, one annotation per problem on its line (`broken.yaml:2`, `hello.yaml:1`, `new.yaml:1`). The job in Rundeck is unchanged |
| PR that updates one job and removes a file | Check run = success. The summary names the removed file whose job the merge deletes |
| Merge (main moves to the PR head) | Incremental sync: 1 file upserted, the removed file's job deleted. **Merge → job live in 1.8 s** |
| A second repository claims a synced uuid | 422 `job … is synced from acme/jobs-…`. The job is untouched |
| Run the synced job | Succeeded with the merged version's command |

## Found and fixed during the check

- **A pull-request check reported a job without a uuid twice.** Both the file parser and the
  Rundeck import report it. They now use the same message, and the check drops duplicate
  annotations.
- **A last synced commit that GitHub no longer has made every push fail with 502.** This happens
  after a repository is recreated or after force-pushed history is garbage-collected. A compare
  that returns 404 now falls back to a full sync. There is a unit test for it, and the
  kestrel-github spec passes 15/15.
- **`local-run.sh` could create the sessions table before DynamoDB Local was listening.** The
  error was hidden, and every authenticated request then failed with 500. The script now waits
  for the port.

## Not covered here

- **The M3 exit test on EKS needs a real GitHub App**, created by the owner (permissions below).
  It would run a PR, then its check run, then the merge, and measure job live in under 60 s
  through CloudFront.
  - **Repository permissions:** Contents: read; Checks: read & write; Pull requests: read;
    Metadata: read.
  - **Events:** Push, Pull request, Check run, Check suite.
  - **Webhook URL:** `https://<host>/api/60/kestrel/github/webhook`, with a webhook secret.
- **On EKS the cron dialect check is the Kubernetes one.** Locally, Quartz validated the crontab.
