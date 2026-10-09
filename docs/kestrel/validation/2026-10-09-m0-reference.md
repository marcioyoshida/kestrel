# M0 validation run, 2026-10-09

Kestrel M0 was built from this fork in CI, deployed to a throwaway EKS environment, proven
end to end through CloudFront, and torn down. The validated environment existed from 15:38 to
16:20 UTC, and teardown took 12 minutes of that. An earlier attempt rolled back on its own
after 26 minutes (see below). Afterwards no VPC, cluster, NAT gateway, database, snapshot,
bucket, user pool, load balancer or Elastic IP remained. Only the KestrelCi stack (ECR and the
OIDC role) is kept.

## What ran

| Piece | Version |
|---|---|
| Commit | `125cafd782` (image `kestrel:125cafd7827e` in the KestrelCi ECR repo) |
| CI | `.github/workflows/kestrel.yml` run 37953884481, all green: chart checks, CDK synth + template checks, preauth unit test, war + image build (13 min), OIDC push to ECR |
| Cluster | EKS 1.36 Auto Mode; one `m5a.large` Bottlerocket node, launched about 1 minute after the pod was scheduled |
| Web pod | Rundeck 6.4.0-SNAPSHOT (this fork) + oauth2-proxy v7.15.5 sidecar; Rundeck on 127.0.0.1 only |
| Data | RDS PostgreSQL 17 `db.t4g.micro`; execution logs in S3 via `rundeck-s3-log-plugin` 3.0.6 and EKS Pod Identity |
| Edge | CloudFront → VPC origin → internal ALB → TargetGroupBinding (IP target :4180) |
| Sign-in | Cognito hosted UI, groups `admin` / `user` |

## Results: `scripts/kestrel/validate.py`, 21/21

```
PASS  edge: /monitoring/threaddump blocked  — HTTP 404
PASS  edge: /monitoring/health blocked  — HTTP 404
PASS  edge: /monitoring/metrics blocked  — HTTP 404
PASS  edge: anonymous / redirects to sign-in  — HTTP 302 -> https://kestrel-…auth.us-east-1.amazoncognito.com/oauth2/authorize?…
PASS  spoof: forged identity headers on UI do not sign in  — HTTP 302
PASS  spoof: forged headers on /api/60/system/info rejected  — HTTP 403
PASS  spoof: forged headers on /api/60/projects rejected  — HTTP 403
PASS  spoof: pod IP :4440 refuses connections  — direct=000 proxy=403
PASS  spoof: pod IP :4180 + forged headers rejected  — direct=000 proxy=403
PASS  sign-in: Cognito admin reaches Rundeck  — HTTP 200 at /menu/home
PASS  sign-in: Rundeck sees the IdP identity  — login=<cognito email>
PASS  sign-in: admin group grants admin API access  — HTTP 200
PASS  spoof: signed-in non-admin + forged headers keeps real identity  — roles=['user']
PASS  spoof: signed-in non-admin + forged admin headers denied admin API  — system/acl HTTP 403
PASS  sign-in: session can mint an API token  — HTTP 201
PASS  workflow: project created  — HTTP 201
PASS  workflow: jobs imported  — HTTP 200 ['proof-run', 'proof-schedule']
PASS  workflow: execution succeeded  — execution 44: succeeded
PASS  workflow: both steps logged  — kestrel-proof marker=… | step 2 on Linux x86_64 as rundeck
PASS  workflow: execution log stored in S3  — s3://<log bucket>/project/kestrel-proof/44.rdlog
PASS  workflow: cron-scheduled job fired on its own  — 1 run(s), first at 2026-10-09T16:04:00Z
```

An extra live check covered the fork's preauth change: a Cognito user in both groups got Rundeck
roles `["admin","user"]`. It was not repeated against the upstream image, so this run does not
show whether upstream would have returned only the first group.

## Found along the way

- **EC2 rule descriptions:** EC2 rejects `>` in security-group rule descriptions at deploy time,
  and `cdk synth` does not catch it. The first KestrelRef create rolled back after about 20
  minutes. `infra/check_templates.py` now runs in CI.
- **Private npm mirror:** upstream `package-lock.json` files resolve through PagerDuty's
  private npm mirror. CI rewrites them to registry.npmjs.org, and the lockfile integrity hashes
  still pin every package. npm's `replace-registry-host` is not enough, because it keeps the
  mirror's `/npm/` path.
- **Unpublished Gradle wrapper:** `examples/json-plugin/wrapper/gradle-wrapper.jar` matches no
  published Gradle checksum. It is never executed. CI verifies only the root wrapper, which
  matches Gradle 8.14.5.
- **GitHub OIDC subjects:** GitHub now issues immutable OIDC subjects
  (`repo:owner@id/repo@id:…`). The ECR push role trusts that form, so a renamed or re-created
  repository with the same name cannot assume it.
- **Roles on every request:** in preauth mode, Rundeck re-reads roles from the request headers
  on every request, session or not (`SetUserInterceptor`). oauth2-proxy must therefore strip
  and re-inject identity headers on skip-auth routes too. The "signed-in non-admin + forged
  headers" checks guard that.
- **`/system/info` for non-admins:** it returns 200 to any signed-in user, with every field
  null. Use `/system/acl/` to test for admin-only access.
