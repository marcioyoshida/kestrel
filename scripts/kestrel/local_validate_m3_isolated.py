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
import re
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid as uuidlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fake_kube  # noqa: E402
import local_validate_m2c as m2c  # noqa: E402
from local_validate_m2c import API, check, RESULTS  # noqa: E402


def call(*args, **kw):
    """m2c.call that rides out client-side network errors (WSL to CloudFront resets)."""
    for i in range(5):
        try:
            return m2c.call(*args, **kw)
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            if i == 4:
                raise
            print(f"      (network error, retrying: {e})", flush=True)
            time.sleep(3)

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
                          "-Dserver.address=127.0.0.1", f"-Drundeck.server.uuid={WEB_UUID}",
                          # the chart sets it through the image's config template (RUNDECK_CLUSTERMODE_ENABLED)
                          "-Drundeck.clusterMode.enabled=true", "-jar", war],
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


def job_yaml(name, uuid, cls=None, script="echo hi", options="", schedule="", extra=""):
    plugins = (f"  plugins:\n    ExecutionLifecycle:\n      kestrel-isolated:\n        runnerClass: {cls}\n"
               if cls else "")
    body = "\n".join("        " + ln for ln in script.splitlines())
    return (f"- name: {name}\n  uuid: {uuid}\n  description: ''\n  executionEnabled: true\n"
            f"  scheduleEnabled: true\n  loglevel: INFO\n  nodeFilterEditable: false\n{plugins}{options}{schedule}{extra}"
            f"  sequence:\n    keepgoing: false\n    strategy: node-first\n    commands:\n"
            f"    - script: |-\n        #!/bin/sh\n{body}\n")


def main():
    started = time.time()
    if m2c.EKS:
        run_checks(EksCluster())
    else:
        commit = next((a for a in sys.argv[1:] if not a.startswith("-")), "HEAD")
        os.makedirs(BASE, exist_ok=True)
        war = war_for(commit)
        fake_kube.clean(BASE)
        start_dynamodb()
        kube = fake_kube.FakeKube(BASE, war, JAVA, base_env(), pod_templates=pod_templates()).start()
        web = start_web(war, kube)
        try:
            run_checks(FakeCluster(kube))
        finally:
            kube.stop()
            web.terminate()
            try:
                web.wait(60)
            except subprocess.TimeoutExpired:
                web.kill()
    print(f"\n{sum(ok for _, ok in RESULTS)}/{len(RESULTS)} checks passed in {int(time.time() - started)} s")
    sys.exit(0 if all(ok for _, ok in RESULTS) else 1)


class FakeCluster:
    """The checks' view of Kubernetes, backed by fake_kube."""
    web_uuids = {WEB_UUID}

    def __init__(self, kube):
        self.kube = kube

    def _state(self):
        with self.kube.lock:
            return json.loads(json.dumps({"objects": self.kube.objects, "events": self.kube.events,
                                          "pods": self.kube.pods}))

    def objects(self, kind):
        return [o for p, o in self._state()["objects"].items() if f"/{kind}/" in p]

    def job_for(self, eid):
        return next((o for o in self.objects("jobs")
                     if o["metadata"].get("labels", {}).get("kestrel.io/execution") == str(eid)), None)

    def job_result(self, name):
        events = str(self._state()["events"])
        return "Complete" if f"job complete {name}" in events else "Failed" if f"job failed {name}" in events else None

    def pod_exits(self, name):
        return [a["exit"] for a in self._state()["pods"].get(name, [])]

    def pod_attempts(self, name):
        return len(self.pod_exits(name))

    def secret(self, name):
        return next((o for o in self.objects("secrets") if o["metadata"]["name"] == name), None)

    def cronjob_for(self, job_uuid):
        return next((o for o in self.objects("cronjobs")
                     if o["metadata"].get("labels", {}).get("kestrel.io/job") == job_uuid), None)

    def trigger_cronjobs(self):
        return [o for o in self.objects("cronjobs")
                if o["metadata"].get("labels", {}).get("app.kubernetes.io/component") == "trigger"]

    def jobs_of(self, cronjob):
        return sorted(n for n in self._state()["pods"] if n.startswith(cronjob + "-"))

    def kill_pod(self, name):
        return self.kube.kill_pod(name)

    def remember(self, name):
        pass

    def delete_job(self, name):
        self.kube.handle("DELETE", f"/apis/batch/v1/namespaces/kestrel/jobs/{name}", None)

    def pod_errors(self):
        errors = []
        for name, attempts in self._state()["pods"].items():
            for a in attempts:
                with open(a["log"], errors="replace") as fh:
                    errors += [f"{name}: {ln.strip()[:160]}" for ln in fh if " ERROR " in ln]
        return errors

    def web_errors(self, since):
        with open(f"{BASE}/web.log", errors="replace") as fh:
            return [ln.strip()[:160] for ln in fh if " ERROR " in ln]

    def role_names(self):
        return {}

    def timeline(self, name):
        return {}


class EksCluster:
    """The checks' view of Kubernetes on the reference EKS cluster (kubectl)."""

    def __init__(self):
        import soak
        import validate as v
        self.k, self.v = soak.kubectl, v
        self.web_uuids = {fake_kube.server_uuid("kestrel", f"kestrel-web-{i}") for i in range(4)}

    def _get(self, *args):
        out = self.k("get", *args, "-o", "json", check=False)
        return json.loads(out) if out else {}

    def objects(self, kind):
        return self._get(kind).get("items", [])

    def job_for(self, eid):
        items = self._get("jobs", "-l", f"kestrel.io/execution={eid}").get("items", [])
        return items[0] if items else None

    def job_result(self, name):
        for c in self._get("job", name).get("status", {}).get("conditions", []):
            if c.get("type") in ("Complete", "Failed") and c.get("status") == "True":
                return c["type"]
        return None

    def pod_exits(self, name):
        pods = sorted(self._get("pods", "-l", f"batch.kubernetes.io/job-name={name}").get("items", []),
                      key=lambda p: p["metadata"]["creationTimestamp"])
        out = []
        for p in pods:
            st = next((c for c in p.get("status", {}).get("containerStatuses", []) if c["name"] == "rundeck"), {})
            out.append(st.get("state", {}).get("terminated", {}).get("exitCode"))
        if out:
            return out
        # Auto Mode removed the idle node and its finished pods with it; the Job keeps the outcome.
        st = self._get("job", name).get("status", {})
        for c in st.get("conditions", []):
            if c.get("status") == "True" and c.get("type") == "Complete":
                return [0] * st.get("succeeded", 1)
            if c.get("status") == "True" and c.get("type") == "Failed":
                m = re.search(r"exit code (\d+)", c.get("message", ""))
                return [None] * (st.get("failed", 1) - 1) + [int(m.group(1)) if m else None]
        return out

    def pod_attempts(self, name):
        """Pods the Job ran: a force-deleted pod is gone, but the Job counted it as failed."""
        st = self._get("job", name).get("status", {})
        return st.get("failed", 0) + st.get("succeeded", 0) + st.get("active", 0)

    def secret(self, name):
        out = self.k("get", "secret", name, "-o", "json", check=False)
        return json.loads(out) if out else None

    def cronjob_for(self, job_uuid):
        items = self._get("cronjobs", "-l", f"kestrel.io/job={job_uuid}").get("items", [])
        return items[0] if items else None

    def trigger_cronjobs(self):
        return self._get("cronjobs", "-l", "app.kubernetes.io/component=trigger").get("items", [])

    def jobs_of(self, cronjob):
        return sorted(j["metadata"]["name"] for j in self._get("jobs").get("items", [])
                      if j["metadata"]["name"].startswith(cronjob + "-"))

    def kill_pod(self, name):
        pods = self._get("pods", "-l", f"batch.kubernetes.io/job-name={name}").get("items", [])
        running = [p["metadata"]["name"] for p in pods if p.get("status", {}).get("phase") == "Running"]
        return bool(running) and self.k("delete", "pod", running[0], "--grace-period=0", "--force", check=False) is not None

    def delete_job(self, name):
        self.k("delete", "job", name, "--wait=false", check=False)

    seen = None

    def remember(self, name):
        """Keeps a finished Job's pod logs: Auto Mode may remove an idle node, and its logs with it."""
        self.seen = self.seen if self.seen is not None else {}
        for p in self._get("pods", "-l", f"batch.kubernetes.io/job-name={name}").get("items", []):
            pod = p["metadata"]["name"]
            text = self.k("logs", pod, "-c", "rundeck", check=False)
            self.seen[pod] = (bool(text), [f"{pod}: {ln.strip()[:160]}" for ln in text.splitlines() if " ERROR " in ln])

    def pod_errors(self):
        seen = self.seen or {}
        missing = [p for p, (ok, _) in seen.items() if not ok]
        print(f"      isolated pod logs read: {len(seen) - len(missing)} of {len(seen)}"
              + (f" (node already removed: {', '.join(missing)})" if missing else ""))
        return [e for _, errs in seen.values() for e in errs]

    def web_errors(self, since):
        return m2c.server_errors(since)

    def role_names(self):
        return {c: self.v.out("KestrelRef", f"Isolated{c.capitalize()}RoleName") for c in ("default", "probe")}

    def timeline(self, name):
        """Seconds from the Job's creation to its first pod being scheduled and its container starting."""
        import datetime
        ts = lambda t: datetime.datetime.strptime(t, "%Y-%m-%dT%H:%M:%SZ").timestamp()
        job = self._get("job", name)
        pods = sorted(self._get("pods", "-l", f"batch.kubernetes.io/job-name={name}").get("items", []),
                      key=lambda p: p["metadata"]["creationTimestamp"])
        if not job or not pods:
            return {}
        t0, pod = ts(job["metadata"]["creationTimestamp"]), pods[0]
        sched = next((c["lastTransitionTime"] for c in pod["status"].get("conditions", []) if c["type"] == "PodScheduled"), None)
        st = next((c for c in pod["status"].get("containerStatuses", []) if c["name"] == "rundeck"), {})
        started = (st.get("state", {}).get("running") or st.get("state", {}).get("terminated") or {}).get("startedAt")
        return {"pod scheduled": ts(sched) - t0 if sched else None,
                "container started": ts(started) - t0 if started else None,
                "node": pod["spec"].get("nodeName")}


def wait_for(fn, limit=300, step=2):
    end = time.time() + limit
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(step)
    return fn()


def sid(job_name):
    return fake_kube.server_uuid("kestrel", job_name)


AWSID = r"""c=$(curl -s -H "Authorization: $(cat "$AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE")" "$AWS_CONTAINER_CREDENTIALS_FULL_URI" | tr -d '\n ')
f() { printf '%s' "$c" | sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
curl -s --aws-sigv4 "aws:amz:us-east-1:sts" --user "$(f AccessKeyId):$(f SecretAccessKey)" \
  -H "x-amz-security-token: $(f Token)" \
  "https://sts.us-east-1.amazonaws.com/?Action=GetCallerIdentity&Version=2011-06-15" | grep -o '<Arn>[^<]*</Arn>'"""


def run_checks(cl):
    since = time.time()
    ui, user, _ = m2c.sign_in()
    s, body = call(ui, f"/api/{API}/tokens", "POST", json.dumps({"user": user, "roles": "admin", "duration": "4h"}))
    T = {"X-Rundeck-Auth-Token": json.loads(body)["token"]}
    api = urllib.request.build_opener()
    tag = secrets.token_hex(3)
    p = f"iso-{tag}"
    s, _ = call(api, f"/api/{API}/projects", "POST", json.dumps({"name": p, "config": {}}), headers=T)
    check("setup: project", s == 201, f"HTTP {s}")

    names = ("hello", "secure", "fails", "retry", "sleepy", "killed", "sched", "pool", "badclass", "swept",
             "awsid-default", "awsid-probe")
    u = {k: str(uuidlib.uuid4()) for k in names}
    jobs = [
        job_yaml("hello", u["hello"], "default",
                 'echo "isolated-hello server=$RD_JOB_SERVERUUID pod=$KESTREL_POD_NAME role=$KESTREL_ROLE"'),
        job_yaml("secure", u["secure"], "default",
                 'echo "pw-sha=$(printf %s "$RD_OPTION_PW" | sha256sum | cut -c1-12)"',
                 options="  options:\n  - name: pw\n    secure: true\n    valueExposed: true\n    required: true\n"),
        job_yaml("fails", u["fails"], "default", "echo about-to-fail\nexit 7"),
        job_yaml("retry", u["retry"], "default", "echo attempt-$RD_JOB_RETRYATTEMPT\nexit 1", extra="  retry: '1'\n"),
        job_yaml("sleepy", u["sleepy"], "default", "echo sleepy-started\nsleep 120\necho sleepy-done"),
        job_yaml("killed", u["killed"], "default", "echo killed-started\nsleep 90\necho killed-done"),
        job_yaml("swept", u["swept"], "default", "echo swept-must-not-run"),
        job_yaml("pool", u["pool"], None, 'echo "pool server=$RD_JOB_SERVERUUID"'),
    ]
    if m2c.EKS:
        jobs += [job_yaml("awsid-default", u["awsid-default"], "default", AWSID),
                 job_yaml("awsid-probe", u["awsid-probe"], "probe", AWSID)]
    s, body = call(api, f"/api/{API}/project/{p}/jobs/import?fileformat=yaml&uuidOption=preserve", "POST", "".join(jobs),
                   headers=T, ctype="application/yaml")
    res = json.loads(body) if s == 200 else {}
    check(f"import: {len(jobs)} jobs ({len(jobs) - 1} isolated, 1 pool)", len(res.get("succeeded", [])) == len(jobs),
          f"HTTP {s} {body[:300]}")

    s, body = call(api, f"/api/{API}/project/{p}/jobs/import?fileformat=yaml&uuidOption=preserve", "POST",
                   job_yaml("badclass", u["badclass"], "gpu-xl"), headers=T, ctype="application/yaml")
    res = json.loads(body) if s == 200 else {}
    check("import: an unknown runner class is rejected", len(res.get("failed", [])) == 1 and not res.get("succeeded"),
          body[:160])

    s, body = call(api, f"/api/{API}/job/{u['hello']}?format=yaml", headers=T, accept="application/yaml")
    check("export: the runner class round-trips in the job YAML",
          "kestrel-isolated:" in body and "runnerClass: default" in body, body[:120])

    def run(key, opts=None):
        s, body = call(api, f"/api/{API}/job/{u[key]}/run", "POST", json.dumps({"options": opts or {}}), headers=T)
        return json.loads(body).get("id") if s == 200 else None

    def execution(eid):
        s, body = call(api, f"/api/{API}/execution/{eid}", headers=T)
        return json.loads(body) if s == 200 else {}

    def output(eid, tries=10):
        # A finished run's log is fetched from the log store on first read (an empty answer, as
        # for any execution another pod ran); read again until it has arrived.
        for i in range(tries):
            s, body = call(api, f"/api/{API}/execution/{eid}/output?format=text", headers=T, accept="text/plain")
            if s == 200 and body.strip():
                return body
            if i + 1 < tries:
                time.sleep(2)
        return ""

    def done(eid, limit=600):
        """Final status; rides out transient HTTP errors (a web pod moving to another node)."""
        status, end = None, time.time() + limit
        while time.time() < end:
            s, body = call(api, f"/api/{API}/execution/{eid}", headers=T)
            status = json.loads(body).get("status") if s == 200 else f"HTTP {s}"
            if s == 200 and status not in ("running", "scheduled", "queued"):
                return status
            time.sleep(2)
        return status

    # ---- run-now: queued, a Job, run in that pod
    t0 = time.time()
    e_hello = run("hello")
    first = execution(e_hello)
    check("run-now: the execution is queued until its pod starts", first.get("status") == "queued",
          f"status={first.get('status')}")
    job = cl.job_for(e_hello)
    jname = job["metadata"]["name"] if job else ""
    pod = (job or {}).get("spec", {}).get("template", {}).get("spec", {})
    env = {e["name"]: e.get("value") for e in pod.get("containers", [{}])[0].get("env", [])}
    check("run-now: a Kubernetes Job from the class's PodTemplate",
          bool(job) and env.get("KESTREL_ISOLATED_EXECUTION") == str(e_hello)
          and pod.get("serviceAccountName") == "kestrel-isolated-default" and pod.get("restartPolicy") == "Never", jname)
    check("run-now: the queued execution belongs to the Job's server UUID",
          first.get("serverUUID") == sid(jname), f"{first.get('serverUUID')} vs {jname}")

    # the others queue up while the first pod starts
    e_secure = run("secure", {"pw": "s3cret-" + tag})
    e_fails = run("fails")
    e_retry = run("retry")
    e_queued_abort = run("sleepy")
    s, body = call(api, f"/api/{API}/execution/{e_queued_abort}/abort", "POST", headers=T)
    check("abort while queued: accepted", s == 200 and json.loads(body).get("abort", {}).get("status") in ("pending", "aborted"),
          body[:160])
    e_pool = run("pool")
    e_aws = {c: run(f"awsid-{c}") for c in ("default", "probe")} if m2c.EKS else {}

    status = done(e_hello)
    elapsed = time.time() - t0
    out = output(e_hello)
    after = execution(e_hello)
    check("run-now: succeeded in its own pod", status == "succeeded" and sid(jname) in out
          and after.get("serverUUID") == sid(jname), f"{status}; {out.strip()[:200]}")
    check("run-now: the pod's environment is the isolated role", f"pod={jname}" in out and "role=isolated" in out,
          out.strip()[:200])
    wait_for(lambda: cl.job_result(jname), 120)
    cl.remember(jname)
    check("run-now: the pod exited 0 and the Job is Complete",
          cl.pod_exits(jname) == [0] and cl.job_result(jname) == "Complete", f"{cl.pod_exits(jname)} {cl.job_result(jname)}")
    queued_s = (after.get("date-started", {}).get("unixtime", 0) - first.get("date-started", {}).get("unixtime", 0)) / 1000
    print(f"      queued {queued_s:.0f} s (pod scheduling + application start), run-now to done {elapsed:.0f} s")
    TIMES.append(("run-now queued (class default)", queued_s))
    for k, v in cl.timeline(jname).items():
        print(f"      Job -> {k}: {v if isinstance(v, str) or v is None else f'{v:.0f} s'}")

    check("pool job: runs on a web pod, no Job", done(e_pool, 180) == "succeeded" and cl.job_for(e_pool) is None
          and execution(e_pool).get("serverUUID") in cl.web_uuids, execution(e_pool).get("serverUUID"))

    # ---- IAM: each class's pod runs with its own role (EKS Pod Identity)
    if m2c.EKS:
        roles = cl.role_names()
        for c, eid in e_aws.items():
            st = done(eid)
            out = output(eid)
            check(f"IAM: a class-{c} pod runs as its own role", st == "succeeded" and f"assumed-role/{roles[c]}/" in out,
                  f"{st}; {out.strip()[:160]}")

    # ---- secure options via a Secret owned by the Job
    sjob = cl.job_for(e_secure)
    secret = wait_for(lambda: cl.secret(sjob["metadata"]["name"]) if sjob else None, 30)
    status = done(e_secure)
    import hashlib
    want = hashlib.sha256(("s3cret-" + tag).encode()).hexdigest()[:12]
    check("secure option: reaches the pod through a Secret the Job owns",
          status == "succeeded" and f"pw-sha={want}" in output(e_secure) and bool(secret)
          and secret["metadata"]["ownerReferences"][0]["uid"] == sjob["metadata"]["uid"], status)
    check("secure option: the value is not in the Job spec", ("s3cret-" + tag) not in json.dumps(sjob))
    wait_for(lambda: cl.job_result(sjob["metadata"]["name"]), 120)
    cl.remember(sjob["metadata"]["name"])

    # ---- failure: the Job fails without a retry
    status = done(e_fails)
    fname = cl.job_for(e_fails)["metadata"]["name"]
    wait_for(lambda: cl.job_result(fname), 120)
    cl.remember(fname)
    check("failure: execution failed, pod exit 3, Job Failed without a retry",
          status == "failed" and cl.pod_exits(fname) == [3] and cl.job_result(fname) == "Failed",
          f"{status} {cl.pod_exits(fname)} {cl.job_result(fname)}")

    # ---- a retry runs in the same pod, which waits for it
    status = done(e_retry)
    rname = cl.job_for(e_retry)["metadata"]["name"]
    retried = execution(e_retry).get("retriedExecution") or {}
    second = done(retried.get("id"), 180) if retried.get("id") else None
    wait_for(lambda: cl.job_result(rname), 180)
    cl.remember(rname)
    check("retry: the retry runs in the same pod; the pod exits 3 only after it",
          status == "failed-with-retry" and second == "failed"
          and execution(retried["id"]).get("serverUUID") == sid(rname) and cl.pod_exits(rname) == [3],
          f"{status} -> {second}; exits={cl.pod_exits(rname)}")

    # ---- aborted while queued: its pod starts, finds it ended, never runs it
    qname = cl.job_for(e_queued_abort)["metadata"]["name"]
    wait_for(lambda: cl.job_result(qname), 400)
    cl.remember(qname)
    check("abort while queued: the pod ends it aborted without running it",
          "sleepy-started" not in output(e_queued_abort, 3) and execution(e_queued_abort).get("status") == "aborted"
          and cl.pod_exits(qname) == [3], f"{execution(e_queued_abort).get('status')} exits={cl.pod_exits(qname)}")

    # ---- abort while running (cross-pod abort)
    e_sleepy = run("sleepy")
    wait_for(lambda: "sleepy-started" in output(e_sleepy, 1), 600)
    t1 = time.time()
    call(api, f"/api/{API}/execution/{e_sleepy}/abort", "POST", headers=T)
    status = done(e_sleepy, 60)
    aname = cl.job_for(e_sleepy)["metadata"]["name"]
    wait_for(lambda: cl.job_result(aname), 120)
    cl.remember(aname)
    check("abort while running: the isolated pod stops the run", status == "aborted"
          and "sleepy-done" not in output(e_sleepy) and execution(e_sleepy).get("status") == "aborted"
          and cl.pod_exits(aname) == [3], f"{execution(e_sleepy).get('status')} after {time.time() - t1:.0f} s, "
                                          f"exits={cl.pod_exits(aname)}")

    # ---- a pod lost mid-run: the retried pod never runs the execution twice
    e_killed = run("killed")
    wait_for(lambda: "killed-started" in output(e_killed, 1), 600)
    kname = cl.job_for(e_killed)["metadata"]["name"]
    check("pod loss: the pod is killed mid-run", cl.kill_pod(kname))
    status = done(e_killed, 600)
    wait_for(lambda: cl.job_result(kname), 400)
    cl.remember(kname)
    exits = cl.pod_exits(kname)
    check("pod loss: the replacement pod ends the run instead of running it again",
          cl.pod_attempts(kname) == 2 and exits and exits[-1] == 3 and status not in ("succeeded", "running", "queued")
          and output(e_killed).count("killed-started") == 1, f"{status} pods={cl.pod_attempts(kname)} exits={exits}")

    # ---- a Job deleted before its pod ran: the sweeper ends the queued execution
    e_swept = run("swept")
    sname = cl.job_for(e_swept)["metadata"]["name"]
    cl.delete_job(sname)
    t2 = time.time()
    status = done(e_swept, 420)
    check("sweeper: a queued run whose Job is gone ends incomplete", status in ("incomplete", "other")
          and "swept-must-not-run" not in output(e_swept, 3), f"{status} after {time.time() - t2:.0f} s")

    # ---- run later is refused
    s, body = call(api, f"/api/{API}/job/{u['hello']}/run", "POST",
                   json.dumps({"runAtTime": time.strftime("%Y-%m-%dT%H:%M:%S.000+0000", time.gmtime(time.time() + 600))}),
                   headers=T)
    check("run later: refused for an isolated job", s != 200 and "not supported" in body, f"HTTP {s} {body[:160]}")

    # ---- scheduled: its own CronJob, one run per firing, in the probe class; one firing (a
    # fixed minute, UTC) locally, where every firing boots a JVM
    at = time.gmtime(time.time() + 150)
    cron = "0 0/1 * ? * * *" if m2c.EKS else f"0 {at.tm_min} {at.tm_hour} ? * * *"
    sched_yaml = lambda cls: job_yaml("sched", u["sched"], cls, 'echo "scheduled server=$RD_JOB_SERVERUUID"',
                                      schedule=f"  timeZone: UTC\n  schedule:\n    crontab: '{cron}'\n")
    s, body = call(api, f"/api/{API}/project/{p}/jobs/import?fileformat=yaml&uuidOption=preserve", "POST",
                   sched_yaml("probe"), headers=T, ctype="application/yaml")
    check("schedule: isolated job imported", s == 200 and '"succeeded":[{' in body, body[:160])
    cj = wait_for(lambda: cl.cronjob_for(u["sched"]), 90)
    cj_name = cj["metadata"]["name"] if cj else ""
    want_cron = ("0-59/1 * * * *", "*/1 * * * *", "* * * * *") if m2c.EKS else (f"{at.tm_min} {at.tm_hour} * * *",)
    check("schedule: the isolated job gets its own CronJob (probe class)", bool(cj)
          and cj["spec"]["schedule"] in want_cron and cj["spec"]["timeZone"] == "UTC"
          and cj["spec"]["jobTemplate"]["spec"]["template"]["spec"]["serviceAccountName"] == "kestrel-isolated-probe",
          f"{cj_name} {cj and cj['spec']['schedule']} {cj and cj['spec']['timeZone']}")
    t_import = time.time()
    check("schedule: no pool bucket CronJob for it", not [b for b in cl.trigger_cronjobs()
                                                          if b["spec"]["schedule"] in want_cron])

    def sched_execs():
        s, body = call(api, f"/api/{API}/job/{u['sched']}/executions?max=50", headers=T)
        return json.loads(body).get("executions", []) if s == 200 else []
    want_runs = 3 if m2c.EKS else 1
    ok_runs = wait_for(lambda: len([e for e in sched_execs() if e.get("status") == "succeeded"]) >= want_runs,
                       420 + 60 * want_runs, 5)
    # The CronJob controller keeps only the last finished Job, so map each run to the Job name
    # of any minute since the import (<cronjob>-<minutes since epoch>).
    minutes = range(int(t_import // 60) - 1, int(time.time() // 60) + 1)
    firing_servers = {sid(f"{cj_name}-{m}"): m for m in minutes}
    execs = sched_execs()
    by_server = {}
    for e in execs:
        by_server[e.get("serverUUID")] = by_server.get(e.get("serverUUID"), 0) + 1
    check(f"schedule: {want_runs}+ firing(s), each run once in the pod of its minute",
          ok_runs and set(by_server) <= set(firing_servers) and all(n == 1 for n in by_server.values()),
          f"executions={len(execs)} minutes={sorted(firing_servers[x] for x in by_server if x in firing_servers)}")
    delays = [e["date-started"]["unixtime"] / 1000 - firing_servers[e["serverUUID"]] * 60
              for e in execs if e.get("date-started") and e.get("serverUUID") in firing_servers]
    if delays:
        TIMES.append(("scheduled minute to run start, mean", sum(delays) / len(delays)))
        TIMES.append(("scheduled minute to run start, max", max(delays)))

    s, body = call(api, f"/api/{API}/project/{p}/jobs/import?fileformat=yaml&dupeOption=update&uuidOption=preserve",
                   "POST", sched_yaml(None), headers=T, ctype="application/yaml")
    gone = wait_for(lambda: cl.cronjob_for(u["sched"]) is None, 90)
    check("schedule: switching the job to the pool deletes its CronJob", gone and s == 200)
    call(api, f"/api/{API}/job/{u['sched']}/schedule/disable", "POST", headers=T)

    errors = [e for e in cl.pod_errors() if not any(x in e for x in m2c.EXPECTED_ERRORS + ("Stale Execution cleaned up",)
                                                    + tuple(f"[ID#{x}:" for x in (e_swept, e_killed)))]
    check("isolated pods: no unexpected ERROR lines", not errors, "\n      ".join(errors[:8]))
    # expected on web: the sweeper ending the swept run, and upstream logging the refused run-later
    # ...and upstream's log-storage request for a run another pod held (swept, or its pod lost):
    # the log is not on this pod (the pod checkpointed it to the log store, or it never ran)
    orphaned = tuple(f"[ID#{x}:" for x in (e_swept, e_killed))
    web_errors = [e for e in cl.web_errors(since) if not any(x in e for x in m2c.EXPECTED_ERRORS + (
        "Stale Execution cleaned up", "Unable to create execution") + orphaned)]
    check("web: no unexpected ERROR lines", not web_errors, "\n      ".join(web_errors[:8]))
    for name, value in TIMES:
        print(f"      {name}: {value:.0f} s")


TIMES = []


if __name__ == "__main__":
    main()
