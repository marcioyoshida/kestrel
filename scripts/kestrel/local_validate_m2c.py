#!/usr/bin/env python3
"""M2c check against a local Kestrel in DynamoDB storage mode (scripts/kestrel/local-run.sh):
jobs, executions, workflows, references and reports all live in DynamoDB.

Covers the job lifecycle the API and UI use: import a job (options, notification, ordered
steps, error handler, a job-reference step), export and compare it, reorder its steps, run it
with options, read output, list and count executions (plain, job-scoped, running, with job
references), execution metrics, activity history, the job/execution/activity UI pages, delete.

  local_validate_m2c.py          local Kestrel (local-run.sh): form login, DynamoDB Local, /tmp/kestrel-local/rundeck.log
  local_validate_m2c.py --eks    the reference environment (deploy.sh): Cognito sign-in through CloudFront, the
                                 kestrel-ref-* tables, and the web/runner pod logs for the run's time window
"""
import http.cookiejar
import json
import re
import os
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

EKS = "--eks" in sys.argv
BASE = os.environ.get("KESTREL_URL", "http://127.0.0.1:4440")
API = 60
DDB = ["aws", "dynamodb", "--output", "json"] if EKS else \
    ["aws", "dynamodb", "--endpoint-url", "http://localhost:8000", "--region", "us-east-1", "--output", "json"]
ENV = dict(os.environ) if EKS else dict(os.environ, AWS_ACCESS_KEY_ID="local", AWS_SECRET_ACCESS_KEY="local")
PREFIX = "kestrel-ref" if EKS else "local"
LOG = os.environ.get("KESTREL_LOG", "/tmp/kestrel-local/rundeck.log")
# expected: the job's notification points at a closed port; the aborted run logs its interruption
EXPECTED_ERRORS = ("Connection refused", "Execution interrupted", "Cancellation while running", "Step 1 failed", "Error executing node step", "Execution failed:")
RESULTS = []
# first key of each exported step (YAML sorts keys, so a script step with an error handler starts with it)
STEP_START = ("- exec:", "- script:", "- jobref:", "- errorhandler:")
STACK = re.compile(r"\b(?:java|groovy|org\.hibernate|org\.grails|grails)\.[\w.]+(?:Exception|Error)\b")


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok)))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  — {detail}" if detail else ""), flush=True)


def call(opener, path, method="GET", body=None, headers=None, ctype="application/json", accept="application/json"):
    data = body.encode() if isinstance(body, str) else body
    h = {"Accept": accept}
    if body is not None:
        h["Content-Type"] = ctype
    h.update(headers or {})
    try:
        with opener.open(urllib.request.Request(BASE + path, data=data, method=method, headers=h), timeout=60) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def wait_done(api, T, eid, limit=90):
    status = None
    for _ in range(limit):
        s, body = call(api, f"/api/{API}/execution/{eid}", headers=T)
        status = json.loads(body).get("status") if s == 200 else f"HTTP {s}"
        if status not in ("running", "scheduled", "queued"):
            return status
        time.sleep(1)
    return status


def ddb_scan(table):
    p = subprocess.run(DDB + ["scan", "--table-name", table], env=ENV, capture_output=True, text=True)
    return json.loads(p.stdout)["Items"] if p.returncode == 0 else []


def sign_in():
    """Returns (ui opener with a signed-in session, user name, cleanup)."""
    global BASE
    if not EKS:
        ui = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        call(ui, "/user/login")
        call(ui, "/j_security_check", "POST", urllib.parse.urlencode({"j_username": "admin", "j_password": "admin"}),
             ctype="application/x-www-form-urlencoded")
        return ui, "admin", lambda: None
    import validate as v
    BASE = v.out("KestrelRef", "PublicUrl").rstrip("/")
    pool = v.out("KestrelRef", "UserPoolId")
    email, password = v.cognito_user(pool, "admin")
    jar, *_ = v.sign_in(BASE, email, password)
    cleanup = lambda: v.sh("aws", "cognito-idp", "admin-delete-user", "--user-pool-id", pool, "--username", email, check=False)
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar)), email, cleanup


def server_errors(since):
    """ERROR lines logged since the run started that are not expected ones."""
    if EKS:
        import soak
        pods = [p for p in soak.kubectl("get", "pods", "-o", "jsonpath={range .items[*]}{.metadata.name} {end}").split()
                if p.startswith(("kestrel-web-", "kestrel-runner-"))]
        secs = int(time.time() - since) + 30
        text = "\n".join(soak.kubectl("logs", p, "-c", "rundeck", f"--since={secs}s", check=False) for p in pods)
        lines = text.splitlines()
    else:
        with open(LOG, errors="replace") as fh:
            fh.seek(since)
            lines = fh.readlines()
    return [ln.strip()[:200] for ln in lines if " ERROR " in ln and not any(x in ln for x in EXPECTED_ERRORS)]


def main():
    log_start = time.time() if EKS else (os.path.getsize(LOG) if os.path.exists(LOG) else None)
    ui, user, cleanup = sign_in()
    try:
        run(ui, user, log_start)
    finally:
        cleanup()


def run(ui, user, log_start):
    s, body = call(ui, f"/api/{API}/tokens", "POST", json.dumps({"user": user, "roles": "admin", "duration": "2h"}))
    token = json.loads(body).get("token") if s in (200, 201) else None
    check("setup: token", token, f"HTTP {s}")
    if not token:
        return
    api = urllib.request.build_opener()
    T = {"X-Rundeck-Auth-Token": token}
    p = f"m2c-{secrets.token_hex(3)}"
    s, _ = call(api, f"/api/{API}/projects", "POST", json.dumps({"name": p, "config": {"resources.source.1.type": "local"}}), headers=T)
    check("setup: project", s == 201, f"HTTP {s}")

    child = f"""
- name: child
  group: m2c
  uuid: {p}-child
  loglevel: INFO
  sequence: {{ commands: [ {{ exec: 'echo child-ran' }} ] }}
"""
    s, body = call(api, f"/api/{API}/project/{p}/jobs/import?fileformat=yaml", "POST", child, headers=T, ctype="application/yaml")
    check("jobs: import child job", s == 200 and json.loads(body).get("succeeded"), f"HTTP {s} {body[:120]}")

    parent = f"""
- name: parent
  group: m2c
  uuid: {p}-parent
  description: workflow with options, notification, error handler and a job reference
  loglevel: INFO
  options:
  - name: greeting
    required: true
    value: hello
  - name: target
    values: [alpha, beta]
  notification:
    onsuccess:
      urls: http://127.0.0.1:9/hook
  sequence:
    keepgoing: false
    strategy: node-first
    commands:
    - exec: echo "step1 ${{option.greeting}} ${{option.target}}"
    - script: |-
        #!/bin/bash
        echo step2
      errorhandler:
        exec: echo recovered
    - jobref:
        name: child
        group: m2c
    - exec: echo step4
"""
    s, body = call(api, f"/api/{API}/project/{p}/jobs/import?fileformat=yaml", "POST", parent, headers=T, ctype="application/yaml")
    ok = s == 200 and json.loads(body).get("succeeded")
    check("jobs: import parent (options, notification, 4 ordered steps, error handler, job ref)", ok, f"HTTP {s} {body[:160]}")
    if not ok:
        return
    pid = f"{p}-parent"

    s, body = call(api, f"/api/{API}/job/{pid}?format=yaml", headers=T, accept="application/yaml")
    order = [ln.strip() for ln in body.splitlines() if ln.strip().startswith(STEP_START)]
    check("jobs: export keeps step order and options", s == 200 and order[0].startswith("- exec: echo \"step1")
          and order[-1].startswith("- exec: echo step4") and "greeting" in body and "errorhandler" in body,
          f"HTTP {s} {order}")

    reordered = parent.replace('    - exec: echo step4\n', '').replace('    commands:\n', '    commands:\n    - exec: echo step4\n')
    s, body = call(api, f"/api/{API}/project/{p}/jobs/import?fileformat=yaml&dupeOption=update", "POST", reordered, headers=T, ctype="application/yaml")
    s2, body2 = call(api, f"/api/{API}/job/{pid}?format=yaml", headers=T, accept="application/yaml")
    order2 = [ln.strip() for ln in body2.splitlines() if ln.strip().startswith(STEP_START)]
    check("jobs: update reorders steps (ordered list association)", order2 and order2[0] == "- exec: echo step4" and len(order2) == 4,
          f"{order2}")

    s, body = call(api, f"/api/{API}/job/{pid}/run", "POST", json.dumps({"options": {"greeting": "hi", "target": "beta"}}), headers=T)
    eid = json.loads(body).get("id") if s == 200 else None
    status = wait_done(api, T, eid) if eid else f"HTTP {s} {body[:120]}"
    check("executions: run with options succeeds (job reference ran the child)", status == "succeeded", f"execution {eid}: {status}")
    lines = []
    for _ in range(30):  # as the UI does: on another web pod the log is pending until it is fetched from S3
        s, body = call(api, f"/api/{API}/execution/{eid}/output?format=json", headers=T)
        out = json.loads(body) if s == 200 else {}
        lines = [e.get("log", "") for e in out.get("entries", [])]
        if out.get("completed"):
            break
        time.sleep(1)
    check("executions: output in order, option values applied", "step1 hi beta" in lines and "child-ran" in lines
          and lines.index("step4") < lines.index("step1 hi beta"), " | ".join(lines)[:160])

    for _ in range(2):
        s, body = call(api, f"/api/{API}/job/{pid}/run", "POST", json.dumps({"options": {"greeting": "again"}}), headers=T)
        wait_done(api, T, json.loads(body)["id"])
    s, body = call(api, f"/api/{API}/job/{pid}/executions", headers=T)
    d = json.loads(body) if s == 200 else {}
    check("executions: job execution list and total", d.get("paging", {}).get("total") == 3 and len(d.get("executions", [])) == 3,
          f"HTTP {s} paging={d.get('paging')}")
    s, body = call(api, f"/api/{API}/project/{p}/executions?jobIdListFilter={pid}&max=2", headers=T)
    d = json.loads(body) if s == 200 else {}
    check("executions: project query with paging and count", d.get("paging", {}).get("total") == 3 and len(d.get("executions", [])) == 2,
          f"HTTP {s} paging={d.get('paging')}")
    s, body = call(api, f"/api/{API}/project/{p}/executions?jobIdListFilter={p}-child&includeJobRef=true", headers=T)
    d = json.loads(body) if s == 200 else {}
    check("executions: child job query including job references", s == 200 and d.get("paging", {}).get("total", 0) >= 1,
          f"HTTP {s} paging={d.get('paging')}")

    s, body = call(api, f"/api/{API}/project/{p}/run/command", "POST", json.dumps({"exec": "sleep 20", "project": p}), headers=T)
    rid = json.loads(body).get("execution", {}).get("id") if s == 200 else None
    time.sleep(3)
    s, body = call(api, f"/api/{API}/project/{p}/executions/running", headers=T)
    running = [e["id"] for e in json.loads(body).get("executions", [])] if s == 200 else []
    check("executions: running list (sparse dateCompleted index)", rid in running, f"HTTP {s} running={running}")
    call(api, f"/api/{API}/execution/{rid}/abort", "POST", "{}", headers=T)
    check("executions: abort a local run", wait_done(api, T, rid) == "aborted", f"execution {rid}")

    s, body = call(api, f"/api/{API}/project/{p}/executions/metrics?jobIdListFilter={pid}", headers=T)
    m = json.loads(body) if s == 200 else {}
    check("executions: metrics (portable projection)", m.get("total") == 3 and m.get("succeeded") == 3, f"HTTP {s} {body[:160]}")

    s, body = call(api, f"/api/{API}/job/{pid}/info", headers=T)
    check("jobs: info (stats)", s == 200 and json.loads(body).get("id") == pid, f"HTTP {s} {body[:120]}")
    s, body = call(api, f"/api/{API}/project/{p}/history?max=20", headers=T)
    events = json.loads(body).get("events", []) if s == 200 else []
    check("activity: history (ExecReport)", len(events) >= 4, f"HTTP {s} {len(events)} events")

    pages = {f"/project/{p}/jobs": "parent", f"/project/{p}/job/show/{pid}": pid,
             f"/project/{p}/activity": p, f"/project/{p}/execution/show/{eid}": pid}
    for path, marker in pages.items():
        s, body = call(ui, path, accept="text/html")
        trace = STACK.search(body)
        check(f"ui: {path.split('/')[3]} page renders", s == 200 and marker in body and not trace,
              f"HTTP {s} marker={marker in body} trace={trace.group(0) if trace else None}")

    tables = json.loads(subprocess.run(DDB + ["list-tables"], env=ENV, capture_output=True, text=True).stdout)["TableNames"]
    want = [f"{PREFIX}-{t}" for t in ("scheduled_execution", "execution", "workflow", "workflow_step", "option",
                                       "notification", "base_report", "referenced_execution")]
    check("dynamodb: job/execution/report tables exist", all(t in tables for t in want), f"missing {[t for t in want if t not in tables]}")

    s, _ = call(api, f"/api/{API}/execution/{eid}", "DELETE", headers=T)
    check("executions: delete", s == 204, f"HTTP {s}")
    s, _ = call(api, f"/api/{API}/job/{pid}", "DELETE", headers=T)
    check("jobs: delete", s == 204, f"HTTP {s}")
    s, _ = call(api, f"/api/{API}/project/{p}", "DELETE", headers=T)
    left = [i["id"]["S"] for i in ddb_scan(f"{PREFIX}-execution") if i.get("project", {}).get("S") == p]
    check("projects: delete removes the project's executions (child job, references, aborted run)", s == 204 and not left,
          f"HTTP {s}, executions left {left}")

    if log_start is not None:
        errors = server_errors(log_start)
        check("server log: no unexpected errors during the run", not errors, " | ".join(errors[:3]))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        check("run aborted", False, repr(e))
    failed = [n for n, ok in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} checks passed")
    sys.exit(1 if failed else 0)
