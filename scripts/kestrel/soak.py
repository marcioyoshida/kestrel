#!/usr/bin/env python3
"""M1 exit test (ADR 0001): kill pods mid-schedule; no missed or duplicate firings. Stdlib only.

Run after deploy.sh in kubernetes mode. It:
  1. signs in through CloudFront, creates project kestrel-soak with cron jobs (two sharing one
     schedule bucket, one in another time zone), and checks that a schedule Kubernetes cannot
     express (L = last day of month) is rejected when saved;
  2. checks the leader created exactly one CronJob per distinct schedule;
  3. for --minutes, deletes a pod every --kill-every seconds, rotating through the Lease leader,
     the other web pod and the runner(s), with one forced (no grace) kill per round;
  4. aborts a scheduled execution running on a runner through the web API (cross-pod abort);
  5. accounts for every expected firing minute in the window, from two independent sources: the
     DynamoDB fire ledger (STARTED with an execution id) and Rundeck's own execution list (no
     job has more scheduled executions than expected minutes);
  6. checks the fire queue's dead-letter queue is empty and that no pod ever registered a Quartz
     cron trigger.

Exits non-zero on any missed or duplicate firing.
"""
import argparse
import datetime as dt
import json
import random
import secrets
import subprocess
import sys
import time
import urllib.parse

sys.path.insert(0, __import__("os").path.dirname(__file__))
import validate as v  # noqa: E402  (shared helpers: sign-in, HTTP, AWS CLI)

API = v.API
PROJECT = "kestrel-soak"
K = ["kubectl", "--context", "kestrel-ref", "-n", "kestrel"]

JOBS = """
- name: every-minute-a
  group: soak
  loglevel: INFO
  scheduleEnabled: true
  executionEnabled: true
  multipleExecutions: true
  schedule: { crontab: '0 * * ? * * *' }
  sequence: { keepgoing: false, strategy: node-first, commands: [ { exec: 'sleep 3; echo a' } ] }
- name: every-minute-b
  group: soak
  loglevel: INFO
  scheduleEnabled: true
  executionEnabled: true
  multipleExecutions: true
  schedule: { crontab: '0 * * ? * * *' }
  sequence: { keepgoing: false, strategy: node-first, commands: [ { exec: 'echo b' } ] }
- name: every-2-minutes-sao-paulo
  group: soak
  loglevel: INFO
  scheduleEnabled: true
  executionEnabled: true
  multipleExecutions: true
  timeZone: America/Sao_Paulo
  schedule: { crontab: '0 0/2 * ? * * *' }
  sequence: { keepgoing: false, strategy: node-first, commands: [ { exec: 'echo sp' } ] }
"""

UNSUPPORTED = """
- name: last-day-of-month
  group: soak
  scheduleEnabled: true
  executionEnabled: true
  schedule: { crontab: '0 0 12 L * ? *' }
  sequence: { commands: [ { exec: 'echo never' } ] }
"""


def kubectl(*args, check=True):
    p = subprocess.run(K + list(args), env=v.ENV, capture_output=True, text=True)
    if check and p.returncode:
        raise RuntimeError(f"kubectl {' '.join(args)}: {p.stderr.strip()[:300]}")
    return p.stdout.strip()


def api_call(token, base, path, method="GET", body=None, ctype="application/json", tries=6):
    """API call through CloudFront that rides out pod kills (502/503 while a web pod restarts)."""
    hdr = {"X-Rundeck-Auth-Token": token, "Accept": "application/json"}
    if body is not None:
        hdr["Content-Type"] = ctype
    for i in range(tries):
        s, _, text, _ = v.call(v.client(), base + path, method, hdr, body)
        if s not in (502, 503, 504):
            return s, text
        time.sleep(5 * (i + 1))
    return s, text


def expected_minutes(cron_minutes_step, start, end):
    """Minutes in [start, end) whose minute-of-hour is divisible by step (all our test crons are
    'every N minutes' with N dividing 60, so the time zone does not change which minutes fire)."""
    t = start.replace(second=0, microsecond=0) + dt.timedelta(minutes=1)
    out = []
    while t < end:
        if t.minute % cron_minutes_step == 0:
            out.append(t)
        t += dt.timedelta(minutes=1)
    return out


def ledger_items(table, job_uuid, minutes):
    keys = [{"pk": {"S": f"{job_uuid}@{int(m.timestamp()) // 60}"}} for m in minutes]
    found = {}
    for i in range(0, len(keys), 100):
        req = {table: {"Keys": keys[i:i + 100], "ConsistentRead": True}}
        out = json.loads(v.sh("aws", "dynamodb", "batch-get-item", "--request-items", json.dumps(req)))
        for it in out["Responses"].get(table, []):
            found[it["pk"]["S"]] = {
                "state": it["state"]["S"], "owner": it["owner"]["S"],
                "executionId": int(it["executionId"]["N"]) if "executionId" in it else None,
                "note": it.get("note", {}).get("S"),
            }
    return found


def leader():
    return kubectl("get", "lease", "kestrel-scheduler", "-o", "jsonpath={.spec.holderIdentity}", check=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=int, default=45, help="soak window")
    ap.add_argument("--kill-every", type=int, default=240, help="seconds between pod kills")
    args = ap.parse_args()

    base = v.out("KestrelRef", "PublicUrl").rstrip("/")
    pool = v.out("KestrelRef", "UserPoolId")
    table = v.out("KestrelRef", "FireLedgerTable")
    dlq = v.out("KestrelRef", "FireDlqUrl")
    email, password = v.cognito_user(pool, "admin")
    try:
        jar, s, *_ = v.sign_in(base, email, password)
        s, _, body, _ = v.call(v.client(jar), f"{base}/api/{API}/tokens", "POST",
                               {"Content-Type": "application/json", "Accept": "application/json"},
                               json.dumps({"user": email, "roles": "admin", "duration": "12h"}))
        token = json.loads(body)["token"]
    finally:
        v.sh("aws", "cognito-idp", "admin-delete-user", "--user-pool-id", pool, "--username", email, check=False)

    # ------------------------------------------------------------------ setup
    api_call(token, base, f"/api/{API}/project/{PROJECT}", "DELETE")
    s, text = api_call(token, base, f"/api/{API}/projects", "POST",
                       json.dumps({"name": PROJECT, "config": {"resources.source.1.type": "local"}}))
    v.check("setup: project created", s == 201, f"HTTP {s}")
    s, text = api_call(token, base, f"/api/{API}/project/{PROJECT}/jobs/import?fileformat=yaml&dupeOption=update",
                       "POST", JOBS, "application/yaml")
    jobs = {j["name"]: j["id"] for j in json.loads(text).get("succeeded", [])} if s == 200 else {}
    v.check("setup: 3 scheduled jobs imported", len(jobs) == 3, f"HTTP {s} {sorted(jobs)}")
    s, text = api_call(token, base, f"/api/{API}/project/{PROJECT}/jobs/import?fileformat=yaml",
                       "POST", UNSUPPORTED, "application/yaml")
    failed = json.loads(text).get("failed", []) if s == 200 else []
    v.check("cron: Quartz-only schedule (L) rejected at save", failed and not json.loads(text).get("succeeded"),
            (failed[0].get("error", "") if failed else text)[:160])

    time.sleep(45)  # leader reconciles on save, or within one resync
    cronjobs = json.loads(kubectl("get", "cronjobs", "-l", "kestrel.io/managed-by=kestrel", "-o", "json"))["items"]
    schedules = sorted((c["spec"]["schedule"], c["spec"].get("timeZone")) for c in cronjobs)
    v.check("cron: one CronJob per distinct schedule (2 jobs share a bucket)",
            len(cronjobs) == 2 and ("0-59/2 * * * *", "America/Sao_Paulo") in schedules, f"{schedules}")

    # ------------------------------------------------------------------ chaos window
    start = dt.datetime.now(dt.timezone.utc)
    end = start + dt.timedelta(minutes=args.minutes)
    kills = []
    rnd = random.Random(7)
    print(f"\nSoak {start:%H:%M:%S} -> {end:%H:%M:%S} UTC, a pod kill every {args.kill_every}s", flush=True)
    order = ["leader", "web-other", "runner"]
    i = 0
    abort_checked = False
    while dt.datetime.now(dt.timezone.utc) < end - dt.timedelta(minutes=2):
        time.sleep(args.kill_every)
        if dt.datetime.now(dt.timezone.utc) >= end - dt.timedelta(minutes=2):
            break
        pods = kubectl("get", "pods", "-o", "jsonpath={range .items[*]}{.metadata.name} {end}").split()
        web = sorted(p for p in pods if p.startswith("kestrel-web-"))
        runners = sorted(p for p in pods if p.startswith("kestrel-runner-"))
        lead = leader()
        kind = order[i % len(order)]
        target = {"leader": lead, "web-other": next((p for p in web if p != lead), None),
                  "runner": rnd.choice(runners) if runners else None}[kind]
        force = (i // len(order)) % 2 == 1
        if target:
            kubectl("delete", "pod", target, "--wait=false", *(["--grace-period=0", "--force"] if force else []),
                    check=False)
            kills.append(f"{dt.datetime.now(dt.timezone.utc):%H:%M:%S} {kind}={target}{' (forced)' if force else ''}")
            print("  kill", kills[-1], flush=True)
        i += 1

        if not abort_checked and i == 2:
            abort_checked = True
            check_cross_pod_abort(token, base)

    time.sleep(max(0, (end - dt.datetime.now(dt.timezone.utc)).total_seconds()) + 150)  # let late firings land

    # ------------------------------------------------------------------ accounting
    print(f"\nKills: {len(kills)}")
    window = {"every-minute-a": 1, "every-minute-b": 1, "every-2-minutes-sao-paulo": 2}
    total_expected = total_ok = 0
    for name, step in window.items():
        minutes = expected_minutes(step, start, end)
        items = ledger_items(table, jobs[name], minutes)
        started = {k: it for k, it in items.items() if it["state"] == "STARTED" and it["executionId"]}
        missing = [m.strftime("%H:%M") for m in minutes if f"{jobs[name]}@{int(m.timestamp()) // 60}" not in started]
        total_expected += len(minutes)
        total_ok += len(minutes) - len(missing)
        v.check(f"soak: {name} fired every expected minute ({len(minutes)})", not missing,
                f"missing {missing}" + (f" states {[items.get(k) for k in items if k not in started]}" if missing else ""))
        # Rundeck's own records: scheduled executions of this job started inside the window.
        s, text = api_call(token, base, f"/api/{API}/job/{jobs[name]}/executions?max=1000")
        execs = [e for e in json.loads(text).get("executions", [])
                 if e.get("executionType") == "scheduled"
                 and start.timestamp() * 1000 < e["date-started"]["unixtime"] < (end.timestamp() + 900) * 1000]
        ids = {e["id"] for e in execs}
        ledger_ids = {it["executionId"] for it in started.values()}
        extra = sorted(ids - ledger_ids)
        v.check(f"soak: {name} has no duplicate executions", not extra and len(execs) <= len(minutes),
                f"{len(execs)} scheduled executions for {len(minutes)} minutes; not in ledger: {extra[:10]}")
        statuses = {}
        for e in execs:
            statuses[e["status"]] = statuses.get(e["status"], 0) + 1
        print(f"      {name}: statuses {statuses}")

    attrs = json.loads(v.sh("aws", "sqs", "get-queue-attributes", "--queue-url", dlq, "--attribute-names",
                            "ApproximateNumberOfMessages"))["Attributes"]
    v.check("soak: dead-letter queue empty", attrs["ApproximateNumberOfMessages"] == "0", str(attrs))

    bad = []
    for pod in kubectl("get", "pods", "-o", "jsonpath={range .items[*]}{.metadata.name} {end}").split():
        if pod.startswith("kestrel-web-") or pod.startswith("kestrel-runner-"):
            log = kubectl("logs", pod, "-c", "rundeck", check=False)
            if "Quartz cron trigger(s) registered" in log:
                bad.append(pod)
    v.check("soak: no pod registered a Quartz cron trigger", not bad, f"offending pods: {bad}")
    print(f"\nFirings accounted: {total_ok}/{total_expected}; kills: {kills}")
    api_call(token, base, f"/api/{API}/project/{PROJECT}", "DELETE")


def check_cross_pod_abort(token, base):
    """Run a long job on a runner (scheduled 2 minutes ahead), abort it through the web API."""
    at = dt.datetime.now(dt.timezone.utc).replace(second=0, microsecond=0) + dt.timedelta(minutes=2)
    yaml = f"""
- name: long-abort-me
  group: soak
  scheduleEnabled: true
  executionEnabled: true
  timeZone: UTC
  schedule: {{ crontab: '0 {at.minute} {at.hour} ? * * *' }}
  sequence: {{ commands: [ {{ exec: 'sleep 600' }} ] }}
"""
    s, text = api_call(token, base, f"/api/{API}/project/{PROJECT}/jobs/import?fileformat=yaml&dupeOption=update",
                       "POST", yaml, "application/yaml")
    job = json.loads(text)["succeeded"][0]["id"]
    eid, server = None, None
    deadline = time.time() + 240
    while time.time() < deadline and eid is None:
        time.sleep(10)
        s, text = api_call(token, base, f"/api/{API}/job/{job}/executions?status=running")
        running = json.loads(text).get("executions", []) if s == 200 else []
        if running:
            eid, server = running[0]["id"], running[0].get("serverUUID")
    if not eid:
        v.check("abort: scheduled long job started on a runner", False, "no running execution in 4 min")
        return
    s, text = api_call(token, base, f"/api/{API}/execution/{eid}/abort", "POST", "{}")
    state = json.loads(text).get("abort", {}).get("status") if s == 200 else text[:120]
    final = None
    for _ in range(15):
        time.sleep(3)
        s, text = api_call(token, base, f"/api/{API}/execution/{eid}")
        final = json.loads(text).get("status")
        if final != "running":
            break
    v.check("abort: execution on a runner aborted through the web API", final == "aborted",
            f"execution {eid} on server {server}: abort {state} -> {final}")
    api_call(token, base, f"/api/{API}/job/{job}", "DELETE")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        v.check("soak aborted", False, repr(e))
    failed = [n for n, ok, _ in v.RESULTS if not ok]
    print(f"\n{len(v.RESULTS) - len(failed)}/{len(v.RESULTS)} checks passed")
    sys.exit(1 if failed else 0)
