#!/usr/bin/env python3
"""The M1 items closed after the first M1 soak, checked live on EKS (after deploy.sh, 2 web pods):

  sessions   a session created through CloudFront is recognized by every web pod directly
             (no stickiness, no identity headers): HTTP sessions live in DynamoDB
  acl bus    a system ACL policy saved through the API is invalidated on the other pods'
             caches within seconds (cluster.clearAclCache over the DynamoDB bus)
  live tail  the output of a job running on a runner pod is readable through the web API
             while it is still running (partial log checkpoints in S3)
  orphans    a run-now execution left running by a web pod that is scaled away is marked
             incomplete by the leader's sweeper

Exits non-zero on any failure. Restores the web replica count it changes.
"""
import datetime as dt
import json
import re
import secrets
import subprocess
import sys
import time

sys.path.insert(0, __import__("os").path.dirname(__file__))
import validate as v  # noqa: E402
import soak  # noqa: E402

API = v.API
K = soak.K


def pod_curl(pod, path, headers):
    args = ["exec", pod, "-c", "rundeck", "--", "curl", "-s", "-w", "\n%{http_code}", "-H", "Accept: application/json"]
    for k, val in headers.items():
        args += ["-H", f"{k}: {val}"]
    out = soak.kubectl(*args, f"http://127.0.0.1:4440{path}", check=False).rsplit("\n", 1)
    return (out[1] if len(out) > 1 else "000"), out[0]


def web_pods():
    return sorted(p for p in soak.kubectl("get", "pods", "-o", "jsonpath={range .items[*]}{.metadata.name} {end}").split()
                  if p.startswith("kestrel-web-"))


def main():
    base = v.out("KestrelRef", "PublicUrl").rstrip("/")
    pool = v.out("KestrelRef", "UserPoolId")
    email, password = v.cognito_user(pool, "admin")
    try:
        jar, s, *_ = v.sign_in(base, email, password)
        session = next((c.value for c in jar if c.name == "KESTREL_SESSION"), None)
        v.check("sessions: sign-in through CloudFront sets KESTREL_SESSION", session, f"cookies {sorted(c.name for c in jar)}")
        s, _, body, _ = v.call(v.client(jar), f"{base}/api/{API}/tokens", "POST",
                               {"Content-Type": "application/json", "Accept": "application/json"},
                               json.dumps({"user": email, "roles": "admin", "duration": "2h"}))
        token = json.loads(body)["token"]
    finally:
        v.sh("aws", "cognito-idp", "admin-delete-user", "--user-pool-id", pool, "--username", email, check=False)

    # ------------------------------------------------------------------ sessions
    for pod in web_pods():
        code, body = pod_curl(pod, f"/api/{API}/user/info", {"Cookie": f"KESTREL_SESSION={session}"})
        who = json.loads(body).get("login") if code == "200" else None
        v.check(f"sessions: {pod} recognizes the session (no headers, no stickiness)", who == email, f"HTTP {code} login={who}")

    # ------------------------------------------------------------------ ACL bus
    name = f"kestrel-bus-{secrets.token_hex(3)}.aclpolicy"
    policy = "description: kestrel bus check\ncontext:\n  application: rundeck\nfor:\n  project:\n    - allow: read\nby:\n  group: nobody\n"
    since = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    s, text = soak.api_call(token, base, f"/api/{API}/system/acl/{name}", "POST", json.dumps({"contents": policy}))
    v.check("acl bus: system policy saved through the API", s == 201, f"HTTP {s} {text[:100]}")
    time.sleep(12)
    pods = [p for p in soak.kubectl("get", "pods", "-o", "jsonpath={range .items[*]}{.metadata.name} {end}").split()
            if p.startswith("kestrel-web-") or p.startswith("kestrel-runner-")]
    seen = [p for p in pods if name in soak.kubectl("logs", p, "-c", "rundeck", f"--since-time={since}", check=False)
            and "changed on another pod" in soak.kubectl("logs", p, "-c", "rundeck", f"--since-time={since}", check=False)]
    v.check("acl bus: every other pod invalidated the policy", len(seen) == len(pods) - 1, f"{seen} of {pods}")
    soak.api_call(token, base, f"/api/{API}/system/acl/{name}", "DELETE")

    # ------------------------------------------------------------------ live tail
    project = f"m1gaps-{secrets.token_hex(3)}"
    soak.api_call(token, base, f"/api/{API}/projects", "POST", json.dumps({"name": project, "config": {"resources.source.1.type": "local"}}))
    at = dt.datetime.now(dt.timezone.utc).replace(second=0, microsecond=0) + dt.timedelta(minutes=2)
    job_yaml = f"""
- name: tail-me
  scheduleEnabled: true
  executionEnabled: true
  timeZone: UTC
  schedule: {{ crontab: '0 {at.minute} {at.hour} ? * * *' }}
  sequence: {{ commands: [ {{ exec: 'for i in $(seq 1 30); do echo tick $i; sleep 2; done' }} ] }}
"""
    s, text = soak.api_call(token, base, f"/api/{API}/project/{project}/jobs/import?fileformat=yaml", "POST", job_yaml, "application/yaml")
    job = json.loads(text)["succeeded"][0]["id"]
    eid = None
    for _ in range(30):
        time.sleep(8)
        s, text = soak.api_call(token, base, f"/api/{API}/job/{job}/executions?status=running")
        running = json.loads(text).get("executions", []) if s == 200 else []
        if running:
            eid = running[0]["id"]
            break
    seen_lines, completed_when_seen = [], None
    for _ in range(12):
        if not eid:
            break
        time.sleep(4)
        s, text = soak.api_call(token, base, f"/api/{API}/execution/{eid}/output?format=json")
        if s != 200:
            continue
        out = json.loads(text)
        seen_lines = [e.get("log", "") for e in out.get("entries", []) if e.get("log", "").startswith("tick")]
        if seen_lines:
            completed_when_seen = out.get("execCompleted")
            break
    v.check("live tail: a runner's running output is readable through the web API",
            seen_lines and completed_when_seen is False, f"execution {eid}: {seen_lines[:3]} (completed={completed_when_seen})")

    # ------------------------------------------------------------------ orphan sweeper
    target_uuid, oid = None, None
    for _ in range(8):
        s, text = soak.api_call(token, base, f"/api/{API}/project/{project}/run/command", "POST",
                                json.dumps({"exec": "sleep 900", "project": project}))
        e = json.loads(text).get("execution", {}) if s == 200 else {}
        s, text = soak.api_call(token, base, f"/api/{API}/execution/{e.get('id')}")
        info = json.loads(text) if s == 200 else {}
        if info.get("serverUUID") == soak.kubectl("exec", "kestrel-web-1", "-c", "rundeck", "--", "printenv", "RUNDECK_SERVER_UUID", check=False) \
                or info.get("serverUUID") == server_uuid("kestrel-web-1"):
            target_uuid, oid = info.get("serverUUID"), e.get("id")
            break
        soak.api_call(token, base, f"/api/{API}/execution/{e.get('id')}/abort", "POST", "{}")
    if not oid:
        v.check("orphans: got a run-now execution on kestrel-web-1", False, "8 attempts landed on web-0")
        return
    # Scale away kestrel-web-1 and kill it without grace, as a node loss would: it cannot mark its
    # own execution, so only the leader's sweeper can.
    soak.kubectl("scale", "statefulset", "kestrel-web", "--replicas=1")
    soak.kubectl("delete", "pod", "kestrel-web-1", "--grace-period=0", "--force", "--wait=false", check=False)
    try:
        status = None
        for _ in range(40):  # sweeps every 2 min; needs two sightings
            time.sleep(15)
            s, text = soak.api_call(token, base, f"/api/{API}/execution/{oid}")
            status = json.loads(text).get("status") if s == 200 else status
            if status not in (None, "running"):
                break
        v.check("orphans: execution of the scaled-away pod marked incomplete", status in ("incomplete", "failed"),
                f"execution {oid} on {target_uuid}: {status}")
    finally:
        soak.kubectl("scale", "statefulset", "kestrel-web", "--replicas=2")
    soak.api_call(token, base, f"/api/{API}/project/{project}", "DELETE")


def server_uuid(pod):
    import hashlib
    h = hashlib.md5(f"kestrel/kestrel/{pod}".encode()).hexdigest()
    return f"{h[0:8]}-{h[8:12]}-3{h[13:16]}-{(int(h[16], 16) & 3) | 8:x}{h[17:20]}-{h[20:32]}"


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        v.check("run aborted", False, repr(e))
    failed = [n for n, ok, _ in v.RESULTS if not ok]
    print(f"\n{len(v.RESULTS) - len(failed)}/{len(v.RESULTS)} checks passed")
    sys.exit(1 if failed else 0)
