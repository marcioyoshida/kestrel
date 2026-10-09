#!/usr/bin/env python3
"""Live validation of a Kestrel deployment (run after deploy.sh). Stdlib only.

Proves, through CloudFront, that:
  edge      /monitoring is blocked; unauthenticated users are sent to the IdP
  spoofing  forged X-Forwarded-Email/Groups headers grant nothing, on the UI and on /api/,
            and Rundeck is unreachable except through the oauth2-proxy sidecar
  sign-in   a Cognito user in group `admin` signs in through the hosted UI and gets Rundeck
  workflow  a project and a two-step job are created over the API, the job runs and succeeds,
            its log lands in the S3 log bucket, and a cron-scheduled job fires on its own

Exits non-zero if any check fails. Creates a throwaway Cognito user and project, then removes them.
"""
import html
import http.cookiejar
import json
import os
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = 60
PROJECT = "kestrel-proof"
ENV = dict(os.environ, AWS_PROFILE=os.environ.get("AWS_PROFILE", "my2027"),
           AWS_REGION=os.environ.get("AWS_REGION", "us-east-1"))
RESULTS = []


def sh(*cmd, check=True):
    p = subprocess.run(cmd, env=ENV, capture_output=True, text=True)
    if check and p.returncode:
        raise RuntimeError(f"{' '.join(cmd[:3])}…: {p.stderr.strip()[:300]}")
    return p.stdout.strip()


def out(stack, key):
    return sh("aws", "cloudformation", "describe-stacks", "--stack-name", stack, "--query",
              f"Stacks[0].Outputs[?OutputKey=='{key}'].OutputValue", "--output", "text")


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  — {detail}" if detail else ""), flush=True)
    return ok


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def client(jar=None, follow=False):
    handlers = [urllib.request.HTTPCookieProcessor(jar if jar is not None else http.cookiejar.CookieJar())]
    if not follow:
        handlers.append(_NoRedirect())
    return urllib.request.build_opener(*handlers)


def call(opener, url, method="GET", headers=None, body=None):
    """Returns (status, headers, text, final_url) without raising on HTTP errors."""
    data = body.encode() if isinstance(body, str) else body
    r = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with opener.open(r, timeout=60) as resp:
            return resp.status, resp.headers, resp.read().decode("utf-8", "replace"), resp.url
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read().decode("utf-8", "replace"), url


def sign_in(base, email, password):
    """Hosted-UI code flow driven like a browser: proxy -> Cognito form -> callback -> Rundeck."""
    jar = http.cookiejar.CookieJar()
    browser = client(jar, follow=True)
    status, _, page, login_url = call(browser, base + "/")
    if "cognito" not in urllib.parse.urlparse(login_url).netloc.lower() and "amazoncognito" not in login_url:
        raise RuntimeError(f"expected the Cognito login page, got {status} {login_url}")
    form = re.search(r'<form[^>]*name="cognitoSignInForm"[^>]*action="([^"]+)"', page)
    csrf = re.search(r'name="_csrf"\s+value="([^"]+)"', page)
    if not (form and csrf):
        raise RuntimeError("Cognito login form not found (managed login v2 is not scriptable this way)")
    action = urllib.parse.urljoin(login_url, html.unescape(form.group(1)))
    body = urllib.parse.urlencode({"_csrf": csrf.group(1), "username": email, "password": password,
                                   "signInSubmitButton": "Sign in"})
    status, _, page, final = call(browser, action, "POST",
                                  {"Content-Type": "application/x-www-form-urlencoded"}, body)
    return jar, status, page, final


def cognito_user(pool, group):
    email = f"kestrel-validate-{secrets.token_hex(4)}@example.com"
    password = "Kv-" + secrets.token_urlsafe(18) + "a9!"
    sh("aws", "cognito-idp", "admin-create-user", "--user-pool-id", pool, "--username", email,
       "--user-attributes", f"Name=email,Value={email}", "Name=email_verified,Value=true",
       "--message-action", "SUPPRESS")
    sh("aws", "cognito-idp", "admin-set-user-password", "--user-pool-id", pool, "--username", email,
       "--password", password, "--permanent")
    sh("aws", "cognito-idp", "admin-add-user-to-group", "--user-pool-id", pool, "--username", email,
       "--group-name", group)
    return email, password


JOBS_YAML = """
- name: proof-run
  group: kestrel
  description: Kestrel end-to-end proof (two steps, server node)
  loglevel: INFO
  executionEnabled: true
  sequence:
    keepgoing: false
    strategy: node-first
    commands:
    - exec: echo "kestrel-proof marker=MARKER"
    - script: |-
        #!/bin/bash
        echo "step 2 on $(uname -sm) as $(id -un)"
- name: proof-schedule
  group: kestrel
  description: fires every minute; removed with the project
  loglevel: INFO
  executionEnabled: true
  scheduleEnabled: true
  schedule:
    crontab: '0 0/1 * ? * * *'
  sequence:
    keepgoing: false
    strategy: node-first
    commands:
    - exec: echo "kestrel-proof scheduled run"
"""


def main():
    base = out("KestrelRef", "PublicUrl").rstrip("/")
    pool = out("KestrelRef", "UserPoolId")
    bucket = out("KestrelRef", "LogBucket")
    print(f"Validating {base}\n")
    anon = client()

    # ---------------------------------------------------------------- edge
    for path in ("/monitoring/threaddump", "/monitoring/health", "/monitoring/metrics"):
        s, *_ = call(anon, base + path)
        check(f"edge: {path} blocked", s == 404, f"HTTP {s}")
    s, h, *_ = call(anon, base + "/")
    loc = h.get("Location") or ""
    check("edge: anonymous / redirects to sign-in", s == 302 and ("oauth2" in loc or "cognito" in loc),
          f"HTTP {s} -> {loc[:80]}")

    # ---------------------------------------------------------------- spoofing
    forged = {"X-Forwarded-Email": "attacker@example.com", "X-Forwarded-User": "attacker",
              "X-Forwarded-Groups": "admin", "X-Forwarded-Preferred-Username": "admin",
              "X-Forwarded-Uuid": "admin", "X-Forwarded-Roles": "admin", "Accept": "application/json"}
    s, h, *_ = call(anon, base + "/menu/home", headers=forged)
    check("spoof: forged identity headers on UI do not sign in", s == 302, f"HTTP {s}")
    for path in (f"/api/{API}/system/info", f"/api/{API}/projects"):
        s, _, body, _ = call(anon, base + path, headers=forged)
        check(f"spoof: forged headers on {path} rejected", s in (401, 403), f"HTTP {s} {body[:80]}")

    # Direct pod access from inside the cluster: Rundeck must not listen on the pod IP.
    pod_ip = sh("kubectl", "--context", "kestrel-ref", "-n", "kestrel", "get", "pod", "-l",
                "app.kubernetes.io/component=web", "-o", "jsonpath={.items[0].status.podIP}")
    probe = (f"curl -s -o /dev/null -w 'direct=%{{http_code}} ' --max-time 5 http://{pod_ip}:4440/ ; "
             f"curl -s -o /dev/null -w 'proxy=%{{http_code}}' --max-time 10 "
             f"-H 'X-Forwarded-Email: attacker@example.com' -H 'X-Forwarded-Groups: admin' "
             f"-H 'Accept: application/json' http://{pod_ip}:4180/api/{API}/system/info")
    res = sh("kubectl", "--context", "kestrel-ref", "-n", "kestrel", "run", f"probe-{secrets.token_hex(3)}",
             "--rm", "-i", "--restart=Never", "--quiet", "--image=curlimages/curl:8.16.0",
             "--overrides", '{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":100}}}',
             "--command", "--", "sh", "-c", probe, check=False)
    check("spoof: pod IP :4440 refuses connections", "direct=000" in res, res)
    check("spoof: pod IP :4180 + forged headers rejected", re.search(r"proxy=(401|403)", res), res)

    # ---------------------------------------------------------------- sign-in
    email, password = cognito_user(pool, "admin")
    token = None
    try:
        jar, s, page, final = sign_in(base, email, password)
        signed_in = s == 200 and final.startswith(base) and email in page
        check("sign-in: Cognito admin reaches Rundeck", signed_in, f"HTTP {s} at {final[:80]}")
        session = client(jar)
        s, _, body, _ = call(session, f"{base}/api/{API}/tokens", "POST",
                             {"Content-Type": "application/json", "Accept": "application/json"},
                             json.dumps({"user": email, "roles": "admin", "duration": "1h"}))
        if s in (200, 201):
            token = json.loads(body).get("token")
        check("sign-in: session can mint an API token", token, f"HTTP {s} {body[:120]}")
        if not token:
            return
        api = client()
        hdr = {"X-Rundeck-Auth-Token": token, "Accept": "application/json"}

        # ------------------------------------------------------------ workflow
        call(api, f"{base}/api/{API}/project/{PROJECT}", "DELETE", hdr)
        s, _, body, _ = call(api, f"{base}/api/{API}/projects", "POST",
                             dict(hdr, **{"Content-Type": "application/json"}),
                             json.dumps({"name": PROJECT, "config": {"resources.source.1.type": "local"}}))
        check("workflow: project created", s == 201, f"HTTP {s} {body[:120]}")
        marker = secrets.token_hex(6)
        s, _, body, _ = call(api, f"{base}/api/{API}/project/{PROJECT}/jobs/import?fileformat=yaml&dupeOption=update",
                             "POST", dict(hdr, **{"Content-Type": "application/yaml"}),
                             JOBS_YAML.replace("MARKER", marker))
        jobs = {j["name"]: j["id"] for j in json.loads(body).get("succeeded", [])} if s == 200 else {}
        check("workflow: jobs imported", len(jobs) == 2, f"HTTP {s} {sorted(jobs)}")

        s, _, body, _ = call(api, f"{base}/api/{API}/job/{jobs['proof-run']}/run", "POST",
                             dict(hdr, **{"Content-Type": "application/json"}), "{}")
        eid = json.loads(body)["id"]
        for _ in range(60):
            s, _, body, _ = call(api, f"{base}/api/{API}/execution/{eid}", headers=hdr)
            state = json.loads(body).get("status")
            if state != "running":
                break
            time.sleep(3)
        check("workflow: execution succeeded", state == "succeeded", f"execution {eid}: {state}")
        s, _, body, _ = call(api, f"{base}/api/{API}/execution/{eid}/output?format=json", headers=hdr)
        lines = [e.get("log", "") for e in json.loads(body).get("entries", [])]
        check("workflow: both steps logged", any(f"marker={marker}" in l for l in lines)
              and any(l.startswith("step 2 on") for l in lines), " | ".join(lines)[:160])

        key = None
        for _ in range(30):
            keys = sh("aws", "s3api", "list-objects-v2", "--bucket", bucket, "--prefix", f"project/{PROJECT}/",
                      "--query", "Contents[].Key", "--output", "text", check=False)
            key = next((k for k in keys.split() if re.search(rf"/{eid}\.rdlog$", k)), None)
            if key:
                break
            time.sleep(5)
        check("workflow: execution log stored in S3", key, f"s3://{bucket}/{key}" if key else "not found")

        fired = []
        for _ in range(30):
            s, _, body, _ = call(api, f"{base}/api/{API}/job/{jobs['proof-schedule']}/executions", headers=hdr)
            fired = [e for e in json.loads(body).get("executions", []) if e.get("status") == "succeeded"]
            if fired:
                break
            time.sleep(10)
        check("workflow: cron-scheduled job fired on its own", fired,
              f"{len(fired)} run(s), first at {fired[0]['date-started']['date']}" if fired else "none in 5 min")
    finally:
        if token:
            call(client(), f"{base}/api/{API}/project/{PROJECT}", "DELETE",
                 {"X-Rundeck-Auth-Token": token, "Accept": "application/json"})
        sh("aws", "cognito-idp", "admin-delete-user", "--user-pool-id", pool, "--username", email, check=False)


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # report, then fail the run
        check("validation aborted", False, repr(e))
    failed = [n for n, ok, _ in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} checks passed")
    sys.exit(1 if failed else 0)
