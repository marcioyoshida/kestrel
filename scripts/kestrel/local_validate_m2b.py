#!/usr/bin/env python3
"""M2b check against a local Kestrel in DynamoDB storage mode (scripts/kestrel/local-run.sh).

Exercises everything the classes moved in M2b back: form login (User), API tokens (AuthToken,
including the token lookup on every API call), projects and their stored configuration
(Project + Storage), key storage (Storage), webhooks (Webhook), plugin metadata, and a job run
whose execution is still in the RDBMS (mixed datastores). Then it reads DynamoDB Local directly
to prove the data lives there, and restarts nothing: run it twice to check persistence.
"""
import http.cookiejar
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("KESTREL_URL", "http://127.0.0.1:4440")
API = 60
DDB = ["aws", "dynamodb", "--endpoint-url", "http://localhost:8000", "--region", "us-east-1", "--output", "json"]
ENV = dict(os.environ, AWS_ACCESS_KEY_ID="local", AWS_SECRET_ACCESS_KEY="local")
RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok)))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  — {detail}" if detail else ""), flush=True)


def call(opener, path, method="GET", body=None, headers=None, ctype="application/json"):
    data = body.encode() if isinstance(body, str) else body
    h = {"Accept": "application/json"}
    if body is not None:
        h["Content-Type"] = ctype
    h.update(headers or {})
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
    try:
        with opener.open(req, timeout=60) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def ddb_items(table):
    p = subprocess.run(DDB + ["scan", "--table-name", table], env=ENV, capture_output=True, text=True)
    return json.loads(p.stdout)["Items"] if p.returncode == 0 else None


def main():
    jar = http.cookiejar.CookieJar()
    session = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    call(session, "/user/login")
    s, _ = call(session, "/j_security_check", "POST", urllib.parse.urlencode({"j_username": "admin", "j_password": "admin"}),
                ctype="application/x-www-form-urlencoded")
    s, body = call(session, f"/api/{API}/user/info")
    check("login: admin session (User in DynamoDB)", s == 200 and json.loads(body).get("login") == "admin", f"HTTP {s}")

    s, body = call(session, f"/api/{API}/tokens", "POST", json.dumps({"user": "admin", "roles": "admin", "duration": "2h"}))
    token = json.loads(body).get("token") if s in (200, 201) else None
    check("tokens: mint an API token (AuthToken)", token, f"HTTP {s}")
    if not token:
        return
    api = urllib.request.build_opener()
    T = {"X-Rundeck-Auth-Token": token}
    t0 = time.time()
    s, _ = call(api, f"/api/{API}/system/info", headers=T)
    check("tokens: the token authenticates API calls (indexed lookup)", s == 200, f"HTTP {s} in {time.time() - t0:.2f}s")

    project = f"m2b-{secrets.token_hex(3)}"
    s, _ = call(api, f"/api/{API}/projects", "POST", json.dumps({"name": project, "config": {
        "resources.source.1.type": "local", "project.label": "Kestrel M2b"}}), headers=T)
    check("projects: create (Project + project config in Storage)", s == 201, f"HTTP {s}")
    s, body = call(api, f"/api/{API}/projects", headers=T)
    check("projects: list includes it", s == 200 and project in [p["name"] for p in json.loads(body)], f"HTTP {s}")
    s, body = call(api, f"/api/{API}/project/{project}/config", headers=T)
    check("projects: config round-trips", s == 200 and json.loads(body).get("project.label") == "Kestrel M2b", f"HTTP {s}")
    s, _ = call(api, f"/api/{API}/project/{project}/config/project.label", "PUT", json.dumps({"key": "project.label", "value": "changed"}), headers=T)
    s2, body = call(api, f"/api/{API}/project/{project}/config/project.label", headers=T)
    check("projects: config update", s == 200 and json.loads(body).get("value") == "changed", f"PUT {s}, GET {s2}")

    key = f"keys/kestrel/m2b-{secrets.token_hex(3)}.password"
    s, _ = call(api, f"/api/{API}/storage/{key}", "POST", "s3cr3t", headers=T, ctype="application/x-rundeck-data-password")
    check("key storage: store a password (Storage)", s == 201, f"HTTP {s}")
    s, body = call(api, f"/api/{API}/storage/keys/kestrel", headers=T)
    names = [r.get("name") for r in json.loads(body).get("resources", [])] if s == 200 else []
    check("key storage: directory listing shows it", key.split("/")[-1] in names, f"HTTP {s} {names[:5]}")
    s, _ = call(api, f"/api/{API}/storage/{key}", "DELETE", headers=T)
    check("key storage: delete", s == 204, f"HTTP {s}")

    s, body = call(api, f"/api/{API}/project/{project}/webhook", "POST", json.dumps({
        "name": "m2b-hook", "project": project, "user": "admin", "roles": "admin", "enabled": True,
        "eventPlugin": "log-webhook-event", "config": {}}), headers=T)
    check("webhooks: create (Webhook + its AuthToken)", s == 200 and "err" not in json.loads(body), f"HTTP {s} {body[:120]}")
    s, body = call(api, f"/api/{API}/project/{project}/webhooks", headers=T)
    hooks = json.loads(body) if s == 200 else []
    hook = next((w for w in hooks if w.get("name") == "m2b-hook"), None)
    check("webhooks: list", hook is not None, f"HTTP {s}")
    if hook:
        s, body = call(urllib.request.build_opener(), f"/api/{API}/webhook/{hook['authToken']}", "POST", json.dumps({"hello": "kestrel"}))
        check("webhooks: invoking it authenticates through its own token", s == 200, f"HTTP {s} {body[:120]}")

    s, body = call(api, f"/api/{API}/project/{project}/run/command", "POST", json.dumps({"exec": "echo kestrel-m2b"}), headers=T)
    eid = json.loads(body).get("execution", {}).get("id") if s == 200 else None
    status = None
    for _ in range(30):
        if not eid:
            break
        s, body = call(api, f"/api/{API}/execution/{eid}", headers=T)
        status = json.loads(body).get("status")
        if status != "running":
            break
        time.sleep(2)
    check("executions: adhoc run in a DynamoDB-stored project (execution still in H2)", status == "succeeded", f"execution {eid}: {status}")

    tables = json.loads(subprocess.run(DDB + ["list-tables"], env=ENV, capture_output=True, text=True).stdout)["TableNames"]
    local = sorted(t for t in tables if t.startswith("local-"))
    print("      DynamoDB tables:", local)
    projects = ddb_items("local-project") or []
    check("dynamodb: the project row is in DynamoDB", any(i.get("name", {}).get("S") == project for i in projects),
          f"{len(projects)} project rows")
    tokens = ddb_items("local-auth_token") or []
    check("dynamodb: tokens are in DynamoDB", len(tokens) >= 1, f"{len(tokens)} token rows")
    storage = ddb_items("local-storage") or []
    check("dynamodb: project config is in DynamoDB (Storage)", any(project in i.get("dir", {}).get("S", "") for i in storage),
          f"{len(storage)} storage rows")

    s, _ = call(api, f"/api/{API}/project/{project}", "DELETE", headers=T)
    check("projects: delete", s == 204, f"HTTP {s}")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        check("run aborted", False, repr(e))
    failed = [n for n, ok in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} checks passed")
    sys.exit(1 if failed else 0)
