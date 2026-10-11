#!/usr/bin/env python3
"""M3 isolated jobs (ADR 0006), end to end on one machine: Kestrel in kubernetes mode against
fake_kube.py, whose "pods" are real one-shot Kestrel JVMs. Needs JDK 17, DynamoDB Local, helm,
and the CI war of the commit under test.

    python3 scripts/kestrel/local_validate_m3_isolated.py [commit]     (default HEAD)

Checks: run-now -> queued -> its own Job -> succeeded in that pod; secure options through a
Secret the Job owns; a failure fails the Job without a retry; abort before and during the run; a
pod killed mid-run is retried and never runs the execution twice; a scheduled isolated job gets
its own CronJob and runs once per firing; pool jobs stay on the pool; run-later is refused; an
unknown runner class is rejected; switching a job back to the pool deletes its CronJob.
"""
import json
import os
import secrets
import subprocess
import sys
import time
import urllib.request
import uuid as uuidlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fake_kube  # noqa: E402
import local_validate_m2c as m2c  # noqa: E402
from local_validate_m2c import API, call, check, RESULTS  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BASE = "/tmp/kestrel-local/iso"
JAVA = os.environ.get("JAVA", os.path.expanduser("~/.local/jdk17/bin/java"))
DDB_HOME = os.environ.get("DDB_HOME", os.path.expanduser("~/.local/dynamodb-local"))
WEB_UUID = fake_kube.server_uuid("kestrel", "kestrel-web-0")


def sh(*cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw).stdout


def war_for(commit):
    sha = sh("git", "-C", ROOT, "rev-parse", commit).strip()
    war = f"/tmp/kestrel-local/rundeck-{sha[:12]}.war"
    if not os.path.exists(war):
        runs = json.loads(sh("gh", "run", "list", "-R", "marcioyoshida/kestrel", "--commit", sha,
                             "--json", "databaseId,conclusion,workflowName"))
        run = next((r["databaseId"] for r in runs if r["conclusion"] == "success" and r["workflowName"] == "Kestrel"), None)
        if not run:
            sys.exit(f"no successful Kestrel CI run for {sha}")
        tmp = f"/tmp/kestrel-local/dl-{sha[:12]}"
        sh("gh", "run", "download", str(run), "-R", "marcioyoshida/kestrel", "-n", "kestrel-war", "-D", tmp)
        os.rename(next(os.path.join(tmp, f) for f in os.listdir(tmp) if f.endswith(".war")), war)
        os.rmdir(tmp)
    return war


def port_open(port):
    import socket
    with socket.socket() as s:
        s.settimeout(1)
        return s.connect_ex(("127.0.0.1", port)) == 0


def pod_templates():
    """The chart's PodTemplates for classes default and probe, as the web pod reads them."""
    import yaml
    out = sh("helm", "template", "kestrel", os.path.join(ROOT, "deploy/helm/kestrel"), "-n", "kestrel",
             "-f", os.path.join(ROOT, "deploy/helm/kestrel/ci/ref-values.yaml"),
             "-f", os.path.join(ROOT, "deploy/helm/kestrel/ci/ref-values-kubernetes.yaml"),
             "--set", "isolated.enabled=true", "--set", "isolated.classes.probe.activeDeadlineSeconds=1800",
             "-s", "templates/isolated-podtemplates.yaml")
    return [d for d in yaml.safe_load_all(out) if d and d.get("kind") == "PodTemplate"]


def base_env():
    env = {k: v for k, v in os.environ.items() if k in ("PATH", "HOME", "LANG", "USER", "TMPDIR")}
    env.update({
        "KESTREL_STORAGE": "dynamodb", "KESTREL_DYNAMODB_ENDPOINT": "http://localhost:8000",
        "KESTREL_DYNAMODB_PREFIX": "local", "KESTREL_SESSIONS": "dynamodb", "KESTREL_SESSIONS_TABLE": "local-ledger",
        "AWS_REGION": "us-east-1", "AWS_ACCESS_KEY_ID": "local", "AWS_SECRET_ACCESS_KEY": "local",
        "KESTREL_SCHEDULER_MODE": "kubernetes", "KESTREL_REGION": "us-east-1",
        "KESTREL_QUEUE_URL": "https://sqs.us-east-1.amazonaws.com/000000000000/local.fifo",
        "KESTREL_LEDGER_TABLE": "local-ledger", "KESTREL_TRIGGER_IMAGE": "local/kestrel-trigger:none",
        "KESTREL_ISOLATED_CLASSES": "default,probe", "KESTREL_ISOLATED_POD_TEMPLATE_PREFIX": "kestrel-isolated-",
        "RUNDECK_CLUSTERMODE_ENABLED": "true",
    })
    return env


def start_dynamodb():
    if not port_open(8000):
        subprocess.Popen([JAVA, "-Djava.library.path=./DynamoDBLocal_lib", "-jar", "DynamoDBLocal.jar", "-inMemory",
                          "-sharedDb", "-port", "8000"], cwd=DDB_HOME, stdout=open(f"{BASE}/ddb.log", "w"),
                         stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
        for _ in range(30):
            if port_open(8000):
                break
            time.sleep(1)
    subprocess.run(["aws", "dynamodb", "create-table", "--endpoint-url", "http://localhost:8000", "--region",
                    "us-east-1", "--table-name", "local-ledger", "--billing-mode", "PAY_PER_REQUEST",
                    "--attribute-definitions", "AttributeName=pk,AttributeType=S",
                    "--key-schema", "AttributeName=pk,KeyType=HASH"],
                   env=dict(os.environ, AWS_ACCESS_KEY_ID="local", AWS_SECRET_ACCESS_KEY="local"), capture_output=True)


def start_web(war, kube):
    env = base_env()
    env.update(kube.client_env())
    env.update({"KESTREL_ROLE": "web", "KESTREL_NAMESPACE": "kestrel", "KESTREL_POD_NAME": "kestrel-web-0"})
    os.makedirs(f"{BASE}/rdeck/var/logs", exist_ok=True)
    log = open(f"{BASE}/web.log", "w")
    p = subprocess.Popen([JAVA, "-Xmx2g", f"-Drdeck.base={BASE}/rdeck", "-Dserver.http.port=4440",
                          "-Dserver.address=127.0.0.1", f"-Drundeck.server.uuid={WEB_UUID}", "-jar", war],
                         cwd=BASE, env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
    for _ in range(120):
        try:
            with urllib.request.urlopen("http://127.0.0.1:4440/monitoring/health/readiness", timeout=3):
                return p
        except Exception:
            if p.poll() is not None:
                sys.exit(f"web exited ({p.returncode}); see {BASE}/web.log")
            time.sleep(5)
    sys.exit(f"web not ready after 10 min; see {BASE}/web.log")


def job_yaml(name, uuid, cls=None, script="echo hi", options="", schedule=""):
    plugins = (f"  plugins:\n    ExecutionLifecycle:\n      kestrel-isolated:\n        runnerClass: {cls}\n"
               if cls else "")
    body = "\n".join("        " + ln for ln in script.splitlines())
    return (f"- name: {name}\n  uuid: {uuid}\n  description: ''\n  executionEnabled: true\n"
            f"  scheduleEnabled: true\n  loglevel: INFO\n  nodeFilterEditable: false\n{plugins}{options}{schedule}"
            f"  sequence:\n    keepgoing: false\n    strategy: node-first\n    commands:\n"
            f"    - script: |-\n        #!/bin/sh\n{body}\n")


def main():
    commit = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else "HEAD"
    os.makedirs(BASE, exist_ok=True)
    war = war_for(commit)
    fake_kube.clean(BASE)
    start_dynamodb()
    pod_env = base_env()
    kube = fake_kube.FakeKube(BASE, war, JAVA, pod_env, pod_templates=pod_templates()).start()
    web = start_web(war, kube)
    started = time.time()
    try:
        run_checks(kube)
    finally:
        kube.stop()
        web.terminate()
        try:
            web.wait(60)
        except subprocess.TimeoutExpired:
            web.kill()
    print(f"\n{sum(ok for _, ok in RESULTS)}/{len(RESULTS)} checks passed in {int(time.time() - started)} s")
    sys.exit(0 if all(ok for _, ok in RESULTS) else 1)


def state(kube):
    with kube.lock:
        return json.loads(json.dumps({"objects": kube.objects, "events": kube.events, "pods": kube.pods}))


def job_for(kube, eid):
    for p, o in state(kube)["objects"].items():
        if "/jobs/" in p and o["metadata"].get("labels", {}).get("kestrel.io/execution") == str(eid):
            return o
    return None


def wait_for(fn, limit=300, step=2):
    end = time.time() + limit
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(step)
    return fn()


def run_checks(kube):
    ui, user, _ = m2c.sign_in()
    s, body = call(ui, f"/api/{API}/tokens", "POST", json.dumps({"user": user, "roles": "admin", "duration": "4h"}))
    T = {"X-Rundeck-Auth-Token": json.loads(body)["token"]}
    api = urllib.request.build_opener()
    tag = secrets.token_hex(3)
    p = f"iso-{tag}"
    s, _ = call(api, f"/api/{API}/projects", "POST", json.dumps({"name": p, "config": {}}), headers=T)
    check("setup: project", s == 201, f"HTTP {s}")

    u = {k: str(uuidlib.uuid4()) for k in ("hello", "secure", "fails", "sleepy", "killed", "sched", "pool", "badclass")}
    jobs = "".join([
        job_yaml("hello", u["hello"], "default",
                 'echo "isolated-hello server=$RD_JOB_SERVERUUID pod=$KESTREL_POD_NAME role=$KESTREL_ROLE"'),
        job_yaml("secure", u["secure"], "default",
                 'echo "pw-sha=$(printf %s "$RD_OPTION_PW" | sha256sum | cut -c1-12)"',
                 options="  options:\n  - name: pw\n    secure: true\n    valueExposed: true\n    required: true\n"),
        job_yaml("fails", u["fails"], "default", "echo about-to-fail\nexit 7"),
        job_yaml("sleepy", u["sleepy"], "default", "echo sleepy-started\nsleep 120\necho sleepy-done"),
        job_yaml("killed", u["killed"], "default", "echo killed-started\nsleep 90\necho killed-done"),
        job_yaml("pool", u["pool"], None, 'echo "pool server=$RD_JOB_SERVERUUID"'),
    ])
    s, body = call(api, f"/api/{API}/project/{p}/jobs/import?fileformat=yaml&uuidOption=preserve", "POST", jobs,
                   headers=T, ctype="application/yaml")
    res = json.loads(body) if s == 200 else {}
    check("import: 6 jobs (5 isolated, 1 pool)", len(res.get("succeeded", [])) == 6, f"HTTP {s} {body[:300]}")

    s, body = call(api, f"/api/{API}/project/{p}/jobs/import?fileformat=yaml&uuidOption=preserve", "POST",
                   job_yaml("badclass", u["badclass"], "gpu-xl"), headers=T, ctype="application/yaml")
    res = json.loads(body) if s == 200 else {}
    check("import: an unknown runner class is rejected", len(res.get("failed", [])) == 1 and not res.get("succeeded"),
          body[:300])

    s, body = call(api, f"/api/{API}/job/{u['hello']}?format=yaml", headers=T, accept="application/yaml")
    check("export: the runner class round-trips in the job YAML", "kestrel-isolated:" in body and "runnerClass: default" in body,
          body[:200])

    def run(key, opts=None):
        s, body = call(api, f"/api/{API}/job/{u[key]}/run", "POST", json.dumps({"options": opts or {}}), headers=T)
        return json.loads(body).get("id") if s == 200 else None, s, body

    def execution(eid):
        s, body = call(api, f"/api/{API}/execution/{eid}", headers=T)
        return json.loads(body) if s == 200 else {}

    def output(eid):
        s, body = call(api, f"/api/{API}/execution/{eid}/output?format=text", headers=T, accept="text/plain")
        return body if s == 200 else ""

    # ---- run-now: queued, a Job, run in that pod
    t0 = time.time()
    e_hello, s, body = run("hello")
    first = execution(e_hello)
    check("run-now: the execution is queued until its pod starts", first.get("status") == "queued",
          f"HTTP {s} status={first.get('status')} {body[:200] if not e_hello else ''}")
    job = job_for(kube, e_hello)
    jname = job["metadata"]["name"] if job else ""
    env = {e["name"]: e.get("value") for e in (job or {}).get("spec", {}).get("template", {}).get("spec", {})
           .get("containers", [{}])[0].get("env", [])}
    check("run-now: a Kubernetes Job from the class's PodTemplate", bool(job) and env.get("KESTREL_ISOLATED_EXECUTION") == str(e_hello)
          and job["spec"]["template"]["spec"]["serviceAccountName"] == "kestrel-isolated-default"
          and job["spec"]["template"]["spec"]["restartPolicy"] == "Never", jname)
    check("run-now: the queued execution belongs to the Job's server UUID",
          first.get("serverUUID") == fake_kube.server_uuid("kestrel", jname), f"{first.get('serverUUID')} vs {jname}")

    # start the others while the first pod boots
    e_secure, *_ = run("secure", {"pw": "s3cret-" + tag})
    e_fails, *_ = run("fails")
    e_sleepy_q, *_ = run("sleepy")
    s, body = call(api, f"/api/{API}/execution/{e_sleepy_q}/abort", "POST", headers=T)
    check("abort while queued: accepted", s == 200 and json.loads(body).get("abort", {}).get("status") in ("pending", "aborted"),
          body[:200])
    e_pool, *_ = run("pool")

    status = m2c.wait_done(api, T, e_hello, limit=400)
    elapsed = time.time() - t0
    out = output(e_hello)
    check("run-now: succeeded in its own pod", status == "succeeded" and f"pod={jname}" in out
          and fake_kube.server_uuid("kestrel", jname) in out and "role=isolated" in out, f"{status}; {out.strip()[:200]}")
    after = execution(e_hello)
    pods = state(kube)["pods"].get(jname, [])
    check("run-now: the pod exited 0 and the Job is Complete",
          [x["exit"] for x in pods] == [0] and wait_for(lambda: "job complete " + jname in str(state(kube)["events"]), 60),
          str([x["exit"] for x in pods]))
    print(f"      queued -> succeeded in {elapsed:.0f} s (local JVM boot); "
          f"dateStarted moved by {(after.get('date-started', {}).get('unixtime', 0) - first.get('date-started', {}).get('unixtime', 0)) / 1000:.0f} s")

    pool_status = m2c.wait_done(api, T, e_pool, limit=120)
    check("pool job: runs on the web pod, no Job", pool_status == "succeeded" and job_for(kube, e_pool) is None
          and WEB_UUID in output(e_pool), pool_status)

    # ---- secure options via a Secret owned by the Job
    status = m2c.wait_done(api, T, e_secure, limit=400)
    sjob = job_for(kube, e_secure)
    secret = state(kube)["objects"].get(f"/api/v1/namespaces/kestrel/secrets/{sjob['metadata']['name']}") if sjob else None
    import hashlib
    want = hashlib.sha256(("s3cret-" + tag).encode()).hexdigest()[:12]
    check("secure option: reaches the pod through a Secret the Job owns",
          status == "succeeded" and f"pw-sha={want}" in output(e_secure) and secret
          and secret["metadata"]["ownerReferences"][0]["uid"] == sjob["metadata"]["uid"], f"{status}")
    check("secure option: the value is not in the Job spec", ("s3cret-" + tag) not in json.dumps(sjob))

    # ---- failure: Job fails without a retry
    status = m2c.wait_done(api, T, e_fails, limit=400)
    fjob = job_for(kube, e_fails)
    fname = fjob["metadata"]["name"] if fjob else ""
    wait_for(lambda: f"job failed {fname}" in str(state(kube)["events"]), 60)
    check("failure: execution failed, pod exit 3, Job Failed with no retry",
          status == "failed" and [x["exit"] for x in state(kube)["pods"].get(fname, [])] == [3]
          and f"job failed {fname} (exit 3)" in str(state(kube)["events"]), f"{status} {state(kube)['pods'].get(fname)}")

    # ---- abort before the pod started
    status = m2c.wait_done(api, T, e_sleepy_q, limit=400)
    check("abort while queued: the pod ends it as aborted without running it",
          status == "aborted" and "sleepy-started" not in output(e_sleepy_q), status)

    # ---- abort while running (cross-pod abort)
    e_sleepy, *_ = run("sleepy")
    wait_for(lambda: "sleepy-started" in output(e_sleepy), 400)
    t1 = time.time()
    call(api, f"/api/{API}/execution/{e_sleepy}/abort", "POST", headers=T)
    status = m2c.wait_done(api, T, e_sleepy, limit=60)
    check("abort while running: the isolated pod stops it", status == "aborted", f"{status} after {time.time() - t1:.0f} s")

    # ---- a pod killed mid-run: retried pod never runs the execution twice
    e_killed, *_ = run("killed")
    wait_for(lambda: "killed-started" in output(e_killed), 400)
    kjob = job_for(kube, e_killed)
    kname = kjob["metadata"]["name"]
    check("pod loss: killed mid-run", kube.kill_pod(kname))
    status = m2c.wait_done(api, T, e_killed, limit=400)
    wait_for(lambda: f"job failed {kname}" in str(state(kube)["events"]) or f"job complete {kname}" in str(state(kube)["events"]), 120)
    attempts = state(kube)["pods"].get(kname, [])
    check("pod loss: the retry pod ends the run instead of running it again",
          len(attempts) == 2 and status in ("aborted", "failed", "incomplete", "other")
          and output(e_killed).count("killed-started") == 1, f"{status} attempts={[x['exit'] for x in attempts]}")

    # ---- run later is refused
    s, body = call(api, f"/api/{API}/job/{u['hello']}/run", "POST",
                   json.dumps({"runAtTime": time.strftime("%Y-%m-%dT%H:%M:%S.000+0000", time.gmtime(time.time() + 600))}), headers=T)
    check("run later: refused for an isolated job", s != 200 or "not supported" in body, f"HTTP {s} {body[:200]}")

    # ---- scheduled: its own CronJob, one run per firing, in the probe class. One firing only
    # (a fixed minute, UTC), since every firing boots a JVM here.
    at = time.gmtime(time.time() + 150)
    sched_yaml = lambda cls: job_yaml("sched", u["sched"], cls, 'echo "scheduled server=$RD_JOB_SERVERUUID"',
                                      schedule=f"  timeZone: UTC\n  schedule:\n    crontab: '0 {at.tm_min} {at.tm_hour} ? * * *'\n")
    s, body = call(api, f"/api/{API}/project/{p}/jobs/import?fileformat=yaml&uuidOption=preserve", "POST",
                   sched_yaml("probe"), headers=T, ctype="application/yaml")
    check("schedule: isolated job imported", s == 200 and '"succeeded":[{' in body, body[:200])
    def cronjob():
        for path, o in state(kube)["objects"].items():
            if "/cronjobs/" in path and o["metadata"].get("labels", {}).get("kestrel.io/job") == u["sched"]:
                return o
    cj = wait_for(cronjob, 90)
    cj_name = cj["metadata"]["name"] if cj else None
    check("schedule: the isolated job gets its own CronJob (probe class)", bool(cj)
          and cj["spec"]["schedule"] == f"{at.tm_min} {at.tm_hour} * * *" and cj["spec"]["timeZone"] == "UTC"
          and cj["spec"]["jobTemplate"]["spec"]["template"]["spec"]["serviceAccountName"] == "kestrel-isolated-probe",
          cj_name)
    buckets = [o for path, o in state(kube)["objects"].items() if "/cronjobs/" in path
               and o["metadata"].get("labels", {}).get("app.kubernetes.io/component") == "trigger"]
    check("schedule: no pool bucket CronJob for it", not buckets, str([b["metadata"]["name"] for b in buckets]))

    def sched_execs():
        s, body = call(api, f"/api/{API}/job/{u['sched']}/executions?max=50", headers=T)
        return json.loads(body).get("executions", []) if s == 200 else []
    done = wait_for(lambda: [e for e in sched_execs() if e.get("status") == "succeeded"], 420, 5)
    fired_jobs = sorted({n for n in state(kube)["pods"] if cj_name and n.startswith(cj_name + "-")})
    first_ok = done[0] if done else {}
    check("schedule: a firing runs once, in the pod of that minute",
          bool(done) and fired_jobs and first_ok.get("serverUUID") in {fake_kube.server_uuid("kestrel", n) for n in fired_jobs},
          f"executions={len(sched_execs())} jobs={fired_jobs}")

    # back to the pool: the CronJob goes away
    s, body = call(api, f"/api/{API}/project/{p}/jobs/import?fileformat=yaml&dupeOption=update&uuidOption=preserve", "POST",
                   sched_yaml(None), headers=T, ctype="application/yaml")
    gone = wait_for(lambda: cronjob() is None, 90)
    check("schedule: switching the job to the pool deletes its CronJob", gone and s == 200)
    # every minute that fired ran at most once
    execs = sched_execs()
    per_pod = {}
    for e in execs:
        per_pod[e.get("serverUUID")] = per_pod.get(e.get("serverUUID"), 0) + 1
    check("schedule: exactly one execution for the firing", len(execs) == 1 and list(per_pod.values()) == [1], str(per_pod))

    # every isolated pod exited; unexpected ERROR lines in pod logs
    errors = []
    for name, attempts in state(kube)["pods"].items():
        for a in attempts:
            with open(a["log"], errors="replace") as fh:
                errors += [f"{name}: {ln.strip()[:160]}" for ln in fh if " ERROR " in ln
                           and not any(x in ln for x in m2c.EXPECTED_ERRORS + ("Stale Execution cleaned up",))]
    check("pods: no unexpected ERROR lines", not errors, "\n      ".join(errors[:8]))
    with open(f"{BASE}/web.log", errors="replace") as fh:
        web_errors = [ln.strip()[:160] for ln in fh if " ERROR " in ln and not any(x in ln for x in m2c.EXPECTED_ERRORS)]
    check("web: no unexpected ERROR lines", not web_errors, "\n      ".join(web_errors[:8]))


if __name__ == "__main__":
    main()
