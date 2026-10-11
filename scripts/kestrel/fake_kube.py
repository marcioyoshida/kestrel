#!/usr/bin/env python3
"""A fake Kubernetes for running Kestrel's kubernetes mode on one machine (no cluster, no Docker).

It serves the API calls Kestrel makes (Leases, CronJobs, Jobs, Pods, PodTemplates, Secrets) over
HTTPS with a throwaway CA, and plays the parts of the controllers Kestrel relies on:

  - kubelet: a Job's pod is a local Kestrel JVM (the CI war) with the pod's env, its server UUID
    derived from the Job name exactly as the chart does, and a Secret volume as a directory; at
    most max_pods run at once (more wait, Pending), since every pod is a full JVM;
  - Job controller: exit 0 -> Complete; exit 3 -> Failed (podFailurePolicy FailJob); anything
    else -> a new pod until backoffLimit, then Failed;
  - CronJob controller: isolated CronJobs (component=isolated) fire on their schedule (UTC) as
    Jobs named <cronjob>-<minutes since epoch>. Trigger CronJobs are recorded, never run (no SQS).

GET /_state returns everything, for checks. Used by local_validate_m3_isolated.py.
"""
import base64
import copy
import hashlib
import json
import os
import re
import shutil
import ssl
import subprocess
import threading
import time
import urllib.parse
import uuid as uuidlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

NS = "kestrel"


def server_uuid(namespace, name):
    """ServerIdentity.uuidFor / the chart's pod command."""
    h = hashlib.md5(f"kestrel/{namespace}/{name}".encode()).hexdigest()
    variant = (int(h[16], 16) & 3) | 8
    return f"{h[0:8]}-{h[8:12]}-3{h[13:16]}-{variant:x}{h[17:20]}-{h[20:32]}"


def cron_field_matches(field, value, lo, hi):
    for part in field.split(","):
        step = 1
        if "/" in part:
            part, step = part.split("/")
            step = int(step)
        if part == "*":
            a, b = lo, hi
        elif "-" in part:
            a, b = (int(x) for x in part.split("-"))
        else:
            a = b = int(part)
        if a <= value <= b and (value - a) % step == 0:
            return True
    return False


def cron_matches(schedule, t):
    """5-field cron against a UTC struct_time (enough for the schedules the checks use)."""
    m, h, dom, mon, dow = schedule.split()
    return (cron_field_matches(m, t.tm_min, 0, 59) and cron_field_matches(h, t.tm_hour, 0, 23)
            and cron_field_matches(dom, t.tm_mday, 1, 31) and cron_field_matches(mon, t.tm_mon, 1, 12)
            and cron_field_matches(dow, (t.tm_wday + 1) % 7, 0, 6))


class FakeKube:
    def __init__(self, base, war, java, pod_env, port=18443, pod_templates=(), max_pods=2):
        self.base = base
        self.war = war
        self.java = java
        self.pod_env = pod_env          # env every pod gets (the chart's ConfigMap, made local)
        self.port = port
        self.objects = {}               # path -> object
        self.events = []                # (time, text)
        self.pods = {}                  # job name -> list of attempts {pid, port, exit, log}
        self.procs = {}                 # job name -> Popen of the current attempt
        self.lock = threading.RLock()
        self.next_port = 4450
        self.rv = 1
        self.stopping = False
        for pt in pod_templates:
            self.objects[f"/api/v1/namespaces/{NS}/podtemplates/{pt['metadata']['name']}"] = pt
        self.sa_dir = os.path.join(base, "kube-sa")
        # a node with room for max_pods: further pods wait (Pending), as on a full node
        self.slots = threading.Semaphore(max_pods)

    # ------------------------------------------------------------------ TLS + server
    def start(self):
        os.makedirs(self.sa_dir, exist_ok=True)
        key, crt = os.path.join(self.sa_dir, "tls.key"), os.path.join(self.sa_dir, "ca.crt")
        if not os.path.exists(crt):
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", crt,
                            "-days", "30", "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1"],
                           check=True, capture_output=True)
        with open(os.path.join(self.sa_dir, "token"), "w") as f:
            f.write("fake-token")
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(crt, key)
        kube = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _send(self, code, obj):
                body = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _body(self):
                n = int(self.headers.get("Content-Length") or 0)
                return json.loads(self.rfile.read(n) or b"{}")

            def do_GET(self):
                code, obj = kube.handle("GET", self.path, None)
                self._send(code, obj)

            def do_POST(self):
                code, obj = kube.handle("POST", self.path, self._body())
                self._send(code, obj)

            def do_PUT(self):
                code, obj = kube.handle("PUT", self.path, self._body())
                self._send(code, obj)

            def do_DELETE(self):
                code, obj = kube.handle("DELETE", self.path, None)
                self._send(code, obj)

        self.httpd = ThreadingHTTPServer(("127.0.0.1", self.port), Handler)
        self.httpd.socket = ctx.wrap_socket(self.httpd.socket, server_side=True)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        threading.Thread(target=self.cron_loop, daemon=True).start()
        return self

    def client_env(self):
        """Env that points InClusterKubeApi at this server."""
        return {"KUBERNETES_SERVICE_HOST": "127.0.0.1", "KUBERNETES_SERVICE_PORT": str(self.port),
                "KESTREL_KUBE_SA_DIR": self.sa_dir}

    def stop(self):
        self.stopping = True
        with self.lock:
            procs = list(self.procs.values())
        for p in procs:
            if p.poll() is None:
                p.kill()
        self.httpd.shutdown()

    def event(self, text):
        with self.lock:
            self.events.append((time.time(), text))

    # ------------------------------------------------------------------ API
    def handle(self, method, raw, body):
        u = urllib.parse.urlparse(raw)
        path, query = u.path.rstrip("/"), urllib.parse.parse_qs(u.query)
        if path == "/_state":
            with self.lock:
                return 200, {"objects": self.objects, "events": self.events, "pods": self.pods}
        with self.lock:
            if method == "GET":
                if path in self.objects:
                    return 200, self.objects[path]
                if re.search(r"/(leases|cronjobs|jobs|pods|secrets|podtemplates)$", path):
                    return 200, {"items": self.list(path, query.get("labelSelector", [""])[0])}
                return 404, {"kind": "Status", "code": 404}
            if method == "POST":
                name = body.get("metadata", {}).get("name")
                full = f"{path}/{name}"
                if full in self.objects:
                    return 409, {"kind": "Status", "code": 409}
                obj = self.store(full, body)
                if path.endswith("/jobs"):
                    self.event(f"job created {name}")
                    threading.Thread(target=self.run_job, args=(name,), daemon=True).start()
                elif path.endswith("/secrets"):
                    self.event(f"secret created {name}")
                elif path.endswith("/cronjobs"):
                    self.event(f"cronjob created {name}")
                return 201, obj
            if method == "PUT":
                have = self.objects.get(path)
                if have is None:
                    return 404, {"kind": "Status", "code": 404}
                if body.get("metadata", {}).get("resourceVersion") != have["metadata"]["resourceVersion"]:
                    return 409, {"kind": "Status", "code": 409}
                if "/cronjobs/" in path:
                    self.event(f"cronjob updated {path.rsplit('/', 1)[1]}")
                return 200, self.store(path, body)
            if method == "DELETE":
                obj = self.objects.pop(path, None)
                if obj is not None:
                    kind = path.rsplit("/", 2)[1]
                    self.event(f"{kind[:-1]} deleted {path.rsplit('/', 1)[1]}")
                    if kind == "jobs":
                        self.delete_job(path.rsplit("/", 1)[1])
                return 200, {"kind": "Status", "status": "Success"}
        return 405, {}

    def store(self, path, body):
        obj = copy.deepcopy(body)
        meta = obj.setdefault("metadata", {})
        meta["resourceVersion"] = str(self.rv)
        self.rv += 1
        meta.setdefault("uid", str(uuidlib.uuid4()))
        meta.setdefault("namespace", NS)
        self.objects[path] = obj
        return obj

    def list(self, collection, selector):
        items = [o for p, o in self.objects.items() if p.rsplit("/", 1)[0] == collection]
        if collection.endswith("/pods"):
            # Web pod of the local Kestrel (its UUID is server_uuid(NS, "kestrel-web-0")).
            items = [{"metadata": {"name": "kestrel-web-0", "labels": {"app.kubernetes.io/component": "web"}}}]
        sel = urllib.parse.unquote(selector)
        m = re.match(r"([\w./-]+)\s+in\s+\(([^)]*)\)", sel)
        if m:
            key, values = m.group(1), [v.strip() for v in m.group(2).split(",")]
            return [o for o in items if o["metadata"].get("labels", {}).get(key) in values]
        for term in filter(None, sel.split(",")):
            key, _, value = term.partition("=")
            items = [o for o in items if o["metadata"].get("labels", {}).get(key) == value]
        return items

    # ------------------------------------------------------------------ controllers
    def cron_loop(self):
        fired = set()
        while not self.stopping:
            minute = int(time.time() // 60)
            t = time.gmtime(minute * 60)
            with self.lock:
                cronjobs = [o for p, o in self.objects.items() if "/cronjobs/" in p]
            for cj in cronjobs:
                labels = cj["metadata"].get("labels", {})
                name = cj["metadata"]["name"]
                if labels.get("app.kubernetes.io/component") != "isolated" or (name, minute) in fired:
                    continue
                if cron_matches(cj["spec"]["schedule"], t):
                    fired.add((name, minute))
                    job = {"apiVersion": "batch/v1", "kind": "Job",
                           "metadata": {"name": f"{name}-{minute}",
                                        "labels": cj["spec"]["jobTemplate"].get("metadata", {}).get("labels", {})},
                           "spec": copy.deepcopy(cj["spec"]["jobTemplate"]["spec"])}
                    self.handle("POST", f"/apis/batch/v1/namespaces/{NS}/jobs", job)
            time.sleep(1)

    def run_job(self, name):
        path = f"/apis/batch/v1/namespaces/{NS}/jobs/{name}"
        backoff = self.objects[path]["spec"].get("backoffLimit", 6)
        failures = 0
        while not self.stopping:
            with self.lock:
                if path not in self.objects:
                    return
                job = self.objects[path]
            with self.slots:
                code = self.run_pod(name, job)
            with self.lock:
                if path not in self.objects:
                    return
                status = self.objects[path].setdefault("status", {})
                fail_codes = [r["onExitCodes"]["values"] for r in job["spec"].get("podFailurePolicy", {}).get("rules", [])
                              if r.get("action") == "FailJob" and "onExitCodes" in r]
                if code == 0:
                    status["succeeded"] = 1
                    status["conditions"] = [{"type": "Complete", "status": "True"}]
                    self.event(f"job complete {name}")
                    return
                failures += 1
                if any(code in v for v in fail_codes) or failures > backoff:
                    status["failed"] = failures
                    status["conditions"] = [{"type": "Failed", "status": "True",
                                             "reason": "PodFailurePolicy" if failures <= backoff else "BackoffLimitExceeded"}]
                    self.event(f"job failed {name} (exit {code})")
                    return
                self.event(f"job pod failed {name} (exit {code}); retrying")

    def run_pod(self, name, job):
        spec = job["spec"]["template"]["spec"]
        container = next(c for c in spec["containers"] if c["name"] == "rundeck")
        env = dict(self.pod_env)
        for e in container.get("env", []):
            if "value" in e:
                env[e["name"]] = e["value"]
            elif "fieldRef" in e.get("valueFrom", {}):
                fp = e["valueFrom"]["fieldRef"]["fieldPath"]
                env[e["name"]] = NS if fp == "metadata.namespace" else name
        env["KESTREL_POD_NAME"] = name    # the job-name label, as the chart maps it
        env["KESTREL_NAMESPACE"] = NS
        with self.lock:
            attempt = len(self.pods.get(name, [])) + 1
            port = self.next_port
            self.next_port += 1
        root = os.path.join(self.base, "pods", f"{name}-{attempt}")
        os.makedirs(os.path.join(root, "rdeck", "var"), exist_ok=True)
        # Execution logs land in the web's base, as S3 would make them visible to every pod.
        logs = os.path.join(root, "rdeck", "var", "logs")
        if not os.path.lexists(logs):
            os.symlink(os.path.join(self.base, "rdeck", "var", "logs"), logs)
        for v in spec.get("volumes", []):
            if "secret" in v:
                secret = self.objects.get(f"/api/v1/namespaces/{NS}/secrets/{v['secret']['secretName']}")
                for _ in range(30):     # the kubelet waits for a non-optional Secret
                    if secret:
                        break
                    time.sleep(1)
                    secret = self.objects.get(f"/api/v1/namespaces/{NS}/secrets/{v['secret']['secretName']}")
                d = os.path.join(root, "secret")
                os.makedirs(d, exist_ok=True)
                for k, val in (secret or {}).get("data", {}).items():
                    with open(os.path.join(d, k), "wb") as f:
                        f.write(base64.b64decode(val))
                env["KESTREL_SECURE_OPTIONS_DIR"] = d
        log_path = os.path.join(root, "pod.log")
        cmd = [self.java, "-Xmx1536m", f"-Drdeck.base={root}/rdeck", f"-Dserver.http.port={port}",
               "-Dserver.address=127.0.0.1", f"-Drundeck.server.uuid={server_uuid(NS, name)}", "-jar", self.war]
        with open(log_path, "w") as out:
            p = subprocess.Popen(cmd, cwd=root, env=env, stdout=out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        with self.lock:
            self.procs[name] = p
            self.pods.setdefault(name, []).append({"pid": p.pid, "port": port, "log": log_path, "exit": None,
                                                   "started": time.time()})
        self.event(f"pod started {name} attempt {attempt} pid {p.pid}")
        code = p.wait()
        with self.lock:
            self.pods[name][-1]["exit"] = code
            self.pods[name][-1]["ended"] = time.time()
        self.event(f"pod exited {name} attempt {attempt} code {code}")
        return code

    def delete_job(self, name):
        p = self.procs.get(name)
        if p and p.poll() is None:
            p.kill()

    def kill_pod(self, name):
        """Like a node loss: the pod dies; the Job controller decides what happens next."""
        p = self.procs.get(name)
        if p and p.poll() is None:
            p.kill()
            return True
        return False


def clean(base):
    shutil.rmtree(os.path.join(base, "pods"), ignore_errors=True)
