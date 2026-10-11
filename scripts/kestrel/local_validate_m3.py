#!/usr/bin/env python3
"""M3 check against a local Kestrel (scripts/kestrel/local-run.sh): the GitHub App webhook.

A fake GitHub (the REST endpoints Kestrel calls, plus App JWT verification) runs in this
process; the script plays GitHub's side by sending signed webhook deliveries. Covers: ping, bad
signature, a repository outside the allowlist, first push (full sync, a scheduled job live with a
next fire time), pull-request check runs (failure with per-line annotations, then success), an
incremental push (update + delete of a removed file), a second repository claiming the same uuid,
and that the synced job runs.

Start Kestrel with the App configured against the fake (port 18089):
  eval "$(scripts/kestrel/local_validate_m3.py --env)"; scripts/kestrel/local-run.sh <commit>
  scripts/kestrel/local_validate_m3.py
"""
import base64
import hashlib
import hmac
import json
import os
import secrets
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from local_validate_m2c import API, BASE, call, check, server_errors, sign_in, wait_done, RESULTS  # noqa: E402

PORT = 18089
STATE = os.environ.get("KESTREL_M3_STATE", "/tmp/kestrel-local/github-app")
SECRET_FILE, KEY_FILE = f"{STATE}/webhook-secret", f"{STATE}/app.pem"
INSTALLATION = 4242


def env():
    """Shell exports that point Kestrel's GitHub App at the fake; creates the key on first use."""
    os.makedirs(STATE, exist_ok=True)
    if not os.path.exists(KEY_FILE):
        subprocess.run(["openssl", "genrsa", "-traditional", "-out", KEY_FILE, "2048"], check=True, capture_output=True)
        with open(SECRET_FILE, "w") as f:
            f.write(secrets.token_hex(20))
    pem = open(KEY_FILE).read().strip().replace("\n", "\\n")  # the literal-\n form Kestrel accepts
    for k, v in {"APP_ID": "77", "PRIVATE_KEY": pem, "WEBHOOK_SECRET": open(SECRET_FILE).read(),
                 "REPOS": "acme/jobs,other/*", "ROLES": "admin", "API_URL": f"http://127.0.0.1:{PORT}"}.items():
        print(f"export KESTREL_GITHUB_{k}='{v}'")


class Fake:
    """An in-memory GitHub: commits are full snapshots (path -> content) in a history per branch."""

    def __init__(self):
        self.commits, self.branches, self.pulls = {}, {}, {}
        self.check_runs, self.errors, self.token_requests, self.seq = [], [], 0, 0
        self.public_key = subprocess.run(["openssl", "rsa", "-in", KEY_FILE, "-pubout"], check=True,
                                         capture_output=True).stdout

    def commit(self, repo, branch, files):
        self.seq += 1
        sha = hashlib.sha1(f"{repo}{self.seq}".encode()).hexdigest()
        self.commits[sha] = dict(files)
        self.branches.setdefault((repo, branch), []).append(sha)
        return sha

    def change(self, repo, branch, changes, base=None):
        files = dict(self.commits[self.branches[(repo, base or branch)][-1]])
        for k, v in changes.items():
            files.pop(k, None) if v is None else files.__setitem__(k, v)
        if base:
            self.branches[(repo, branch)] = list(self.branches[(repo, base)])
        return self.commit(repo, branch, files)

    @staticmethod
    def diff(a, b):
        out = []
        for p, t in b.items():
            if p not in a:
                frm = next((op for op, ot in a.items() if ot == t and op not in b), None)
                out.append({"filename": p, "status": "renamed", "previous_filename": frm} if frm
                           else {"filename": p, "status": "added"})
            elif a[p] != t:
                out.append({"filename": p, "status": "modified"})
        renamed = {f.get("previous_filename") for f in out}
        out += [{"filename": p, "status": "removed"} for p in a if p not in b and p not in renamed]
        return out

    def verify_jwt(self, jwt):
        head, payload, sig = jwt.split(".")
        with tempfile.TemporaryDirectory() as d:
            open(f"{d}/pub", "wb").write(self.public_key)
            open(f"{d}/sig", "wb").write(base64.urlsafe_b64decode(sig + "=" * (-len(sig) % 4)))
            open(f"{d}/data", "w").write(f"{head}.{payload}")
            ok = subprocess.run(["openssl", "dgst", "-sha256", "-verify", f"{d}/pub", "-signature", f"{d}/sig", f"{d}/data"],
                                capture_output=True).returncode == 0
        claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
        return ok and claims.get("iss") == "77" and claims["exp"] - claims["iat"] <= 600

    def handle(self, method, path, query, headers, body):
        if method == "POST" and path == f"/app/installations/{INSTALLATION}/access_tokens":
            auth = headers.get("Authorization", "")
            if not (auth.startswith("Bearer ") and self.verify_jwt(auth[7:])):
                return 401, {"message": "bad JWT"}
            self.token_requests += 1
            return 201, {"token": "inst-token", "expires_at": "2099-01-01T00:00:00Z"}
        if headers.get("Authorization") != "token inst-token":
            return 401, {"message": "Bad credentials"}
        parts = path.split("/")
        if len(parts) < 5 or parts[1] != "repos":
            return 404, {"message": "Not Found"}
        repo, rest = f"{parts[2]}/{parts[3]}", parts[4:]
        q = urllib.parse.parse_qs(query)
        if rest[:3] == ["git", "ref", "heads"]:
            h = self.branches.get((repo, urllib.parse.unquote("/".join(rest[3:]))))
            return (200, {"object": {"sha": h[-1]}}) if h else (404, {"message": "Not Found"})
        if rest[0] == "compare":
            base, head = rest[1].split("...")
            ahead = any(base in h and head in h and h.index(head) > h.index(base) for h in self.branches.values())
            return 200, {"status": "ahead" if ahead else "diverged", "files": self.diff(self.commits[base], self.commits[head])}
        if rest[0] == "pulls" and rest[2:] == ["files"]:
            base, head = self.pulls[int(rest[1])]
            return 200, self.diff(self.commits[base], self.commits[head]) if q["page"] == ["1"] else []
        if rest[:2] == ["git", "trees"]:
            return 200, {"truncated": False, "tree": [{"path": p, "type": "blob"} for p in self.commits[rest[2]]]}
        if rest[0] == "contents":
            text = self.commits.get(q["ref"][0], {}).get(urllib.parse.unquote("/".join(rest[1:])))
            return (200, text.encode()) if text is not None else (404, {"message": "Not Found"})
        if rest == ["check-runs"] and method == "POST":
            run = dict(json.loads(body), id=len(self.check_runs) + 1, repo=repo, annotations=[], patches=0)
            self.check_runs.append(run)
            return 201, {"id": run["id"]}
        if rest[0] == "check-runs" and method == "PATCH":
            run, patch = self.check_runs[int(rest[1]) - 1], json.loads(body)
            run["annotations"] += patch["output"].pop("annotations", [])
            run.update(patch, patches=run["patches"] + 1)
            return 200, {"id": run["id"]}
        return 404, {"message": f"no fake for {method} {path}"}

    def serve(self):
        fake = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def respond(self):
                u = urllib.parse.urlsplit(self.path)
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                try:
                    status, out = fake.handle(self.command, u.path, u.query, self.headers, body)
                except Exception as e:  # noqa: BLE001 - reported as a FAIL at the end
                    fake.errors.append(f"{self.command} {self.path}: {e!r}")
                    status, out = 500, {"message": repr(e)}
                data = out if isinstance(out, bytes) else json.dumps(out).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/vnd.github.raw" if isinstance(out, bytes) else "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_GET = do_POST = do_PATCH = respond

        srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        return srv


SECRET = None


def deliver(event, payload, secret=None, sig=None):
    body = json.dumps(payload).encode()
    sig = sig or "sha256=" + hmac.new((secret or SECRET).encode(), body, hashlib.sha256).hexdigest()
    s, out = call(urllib.request.build_opener(), f"/api/{API}/kestrel/github/webhook", "POST", body,
                  headers={"X-GitHub-Event": event, "X-GitHub-Delivery": secrets.token_hex(8), "X-Hub-Signature-256": sig})
    try:
        return s, json.loads(out)
    except ValueError:
        return s, {"raw": out[:200]}


def push(repo, branch="main"):
    return deliver("push", {"ref": f"refs/heads/{branch}", "deleted": False,
                            "repository": {"full_name": repo}, "installation": {"id": INSTALLATION}})


def pr(fake, repo, number, base, head_branch):
    head = fake.branches[(repo, head_branch)][-1]
    fake.pulls[number] = (fake.branches[(repo, base)][-1], head)
    s, out = deliver("pull_request", {"action": "opened", "number": number, "repository": {"full_name": repo},
                                      "installation": {"id": INSTALLATION},
                                      "pull_request": {"number": number, "base": {"ref": base}, "head": {"sha": head}}})
    run = next((r for r in reversed(fake.check_runs) if r["head_sha"] == head), None)
    return s, out, run


def job(uuid, name, cmd, extra=""):
    return f"""- uuid: {uuid}
  name: {name}
  group: gh
  loglevel: INFO{extra}
  sequence:
    commands:
    - exec: {cmd}
"""


def main():
    global SECRET
    if "--env" in sys.argv:
        return env()
    SECRET = open(SECRET_FILE).read()
    log_start = os.path.getsize("/tmp/kestrel-local/rundeck.log")
    ui, user, _ = sign_in()
    s, body = call(ui, f"/api/{API}/tokens", "POST", json.dumps({"user": user, "roles": "admin", "duration": "2h"}))
    T = {"X-Rundeck-Auth-Token": json.loads(body)["token"]}
    api = urllib.request.build_opener()
    tag = secrets.token_hex(3)
    p1, p2 = f"m3a-{tag}", f"m3b-{tag}"
    for p in (p1, p2):
        s, _ = call(api, f"/api/{API}/projects", "POST", json.dumps({"name": p, "config": {"resources.source.1.type": "local"}}), headers=T)
        check(f"setup: project {p}", s == 201, f"HTTP {s}")
    fake = Fake()
    srv = fake.serve()

    def get_job(uuid):
        s, out = call(api, f"/api/{API}/job/{uuid}/info", headers=T)
        return json.loads(out) if s == 200 else None

    # deliveries GitHub would send that must not sync anything
    s, out = deliver("ping", {"zen": "Keep it logically awesome.", "repository": {"full_name": "acme/jobs"}})
    check("webhook: ping", s == 200 and out.get("ok"), f"HTTP {s} {out}")
    s, _ = deliver("ping", {"zen": "x"}, sig="sha256=" + "0" * 64)
    check("webhook: bad signature -> 401", s == 401, f"HTTP {s}")
    s, _ = deliver("push", {"ref": "refs/heads/main", "repository": {"full_name": "acme/elsewhere"},
                            "installation": {"id": INSTALLATION}})
    check("webhook: repository not allowed -> 403", s == 403, f"HTTP {s}")

    # first push: full sync of two projects, one job scheduled
    u_hello, u_b, u_c = f"{tag}-hello", f"{tag}-b", f"{tag}-c"
    config = f"projects:\n- path: jobs/a\n  project: {p1}\n- path: jobs/b\n  project: {p2}\n"
    fake.commit("acme/jobs", "main", {
        "kestrel.yaml": config,
        "README.md": "not a job",
        "jobs/a/hello.yaml": job(u_hello, "hello", "echo hello-from-git",
                                 "\n  schedule:\n    crontab: '0 0/5 * ? * * *'\n  scheduleEnabled: true"),
        "jobs/b/b.yaml": job(u_b, "b", "echo b"),
        "jobs/b/c.yml": job(u_c, "c", "echo c"),
    })
    t0 = time.time()
    s, out = push("acme/jobs")
    live = time.time() - t0
    check("push 1: full sync", s == 200 and out.get("full") and len(out.get("upserted", [])) == 3 and out.get("deleted") == [],
          f"HTTP {s} {out}")
    check("push 1: JWT verified, one installation token", fake.token_requests == 1, f"{fake.token_requests}")
    hello = get_job(u_hello)
    check("push 1: job live in project", hello and hello.get("project") == p1 and hello.get("name") == "hello", f"{hello}")
    check("push 1: schedule live with a next fire time", hello and hello.get("scheduled") and hello.get("nextScheduledExecution"),
          f"scheduled={hello and hello.get('scheduled')} next={hello and hello.get('nextScheduledExecution')}")
    check("push 1: webhook -> job live < 60 s", live < 60, f"{live:.1f}s")
    check("push 1: other project", (get_job(u_b) or {}).get("project") == p2 and get_job(u_c))

    # redelivery of the same push: nothing to do
    s, out = push("acme/jobs")
    check("push 1 redelivered: no-op", s in (200, 202) and not out.get("upserted"), f"HTTP {s} {out}")

    # PR with problems: bad cron, missing uuid, invalid YAML, uuid owned by the other project's file
    fake.change("acme/jobs", "feature", {
        "jobs/a/hello.yaml": job(u_hello, "hello", "echo hello-v2", "\n  schedule:\n    crontab: 'not a cron'"),
        "jobs/a/new.yaml": "- name: no-uuid\n  sequence:\n    commands:\n    - exec: echo x\n",
        "jobs/a/broken.yaml": "- name: [unclosed\n",
    }, base="main")
    s, out, run = pr(fake, "acme/jobs", 7, "main", "feature")
    ann = sorted((a["path"], a["start_line"]) for a in (run or {}).get("annotations", []))
    check("PR 7: check run failure", s == 200 and run and run.get("conclusion") == "failure" and run.get("name") == "Kestrel",
          f"HTTP {s} {out} conclusion={run and run.get('conclusion')}")
    check("PR 7: one annotation per problem, on its file", ann == [("jobs/a/broken.yaml", 2), ("jobs/a/hello.yaml", 1), ("jobs/a/new.yaml", 1)],
          f"{ann}")
    msgs = " | ".join(a["message"] for a in (run or {}).get("annotations", []))
    check("PR 7: messages name the cause", "uuid" in msgs and "YAML" in msgs and ("cron" in msgs.lower() or "schedule" in msgs.lower()),
          msgs[:400])
    check("PR 7: nothing saved by the check", "echo hello-from-git" in export(api, T, u_hello))

    # PR that is fine: update hello, remove b.yaml (the summary names the job a merge would delete)
    fake.change("acme/jobs", "good", {
        "jobs/a/hello.yaml": job(u_hello, "hello", "echo hello-v2",
                                 "\n  schedule:\n    crontab: '0 0/5 * ? * * *'\n  scheduleEnabled: true"),
        "jobs/b/b.yaml": None,
    }, base="main")
    s, out, run = pr(fake, "acme/jobs", 8, "main", "good")
    summary = ((run or {}).get("output") or {}).get("summary", "")
    check("PR 8: check run success", run and run.get("conclusion") == "success", f"HTTP {s} {out} {summary[:200]}")
    check("PR 8: summary lists the job a merge deletes", "jobs/b/b.yaml" in summary, summary[:300])

    # merge = the main branch moves to the PR head; incremental sync
    fake.branches[("acme/jobs", "main")] = list(fake.branches[("acme/jobs", "good")])
    t0 = time.time()
    s, out = push("acme/jobs")
    live = time.time() - t0
    check("push 2: incremental update + delete", s == 200 and not out.get("full") and out.get("upserted") == ["jobs/a/hello.yaml"]
          and out.get("deleted") == [u_b], f"HTTP {s} {out}")
    check("push 2: job updated", "echo hello-v2" in export(api, T, u_hello))
    check("push 2: removed file's job deleted", get_job(u_b) is None and get_job(u_c) is not None)
    check("push 2: merge -> job live < 60 s", live < 60, f"{live:.1f}s")

    # a second repository may not take over a job synced from acme/jobs
    fake.commit("other/repo", "main", {"kestrel.yaml": f"projects:\n- path: ''\n  project: {p1}\n",
                                        "steal.yaml": job(u_hello, "hello", "echo stolen")})
    s, out = push("other/repo")
    check("other/repo: claiming a synced uuid -> 422", s == 422 and any("acme/jobs" in p for p in out.get("problems", [])),
          f"HTTP {s} {out}")
    check("other/repo: job untouched", "echo hello-v2" in export(api, T, u_hello))

    # the synced job runs
    s, body = call(api, f"/api/{API}/job/{u_hello}/run", "POST", "{}", headers=T)
    eid = json.loads(body).get("id") if s == 200 else None
    status = wait_done(api, T, eid) if eid else f"HTTP {s}"
    s, body = call(api, f"/api/{API}/execution/{eid}/output", headers=T)
    check("run: synced job succeeds with the git version", status == "succeeded" and "hello-v2" in body, f"{status}")

    srv.shutdown()
    check("fake GitHub: no unexpected requests", not fake.errors, "; ".join(fake.errors)[:300])
    errs = server_errors(log_start)
    check("server log: no unexpected ERROR lines", not errs, "; ".join(errs[:3]))
    failed = [n for n, ok in RESULTS if not ok]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} passed")
    sys.exit(1 if failed else 0)


def export(api, T, uuid):
    s, body = call(api, f"/api/{API}/job/{uuid}?format=yaml", headers=T, accept="application/yaml")
    return body if s == 200 else ""


if __name__ == "__main__":
    main()
