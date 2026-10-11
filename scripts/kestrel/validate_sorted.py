#!/usr/bin/env python3
"""M2d sorted indexes at scale: execution and history pages read only what they return.

Imports a project archive holding one job and N executions (default 1200) with their reports,
some sharing a completion time, then checks that:
  - project execution pages (first, middle, last) come back in Rundeck's order, with the total;
  - a job's execution page and the project history come back newest first;
  - page queries read only offset + max rows (plus ties) through a sorted index. The datastore logs
    every query reading over 500 rows with its plan, offset and max; totals (max -1) count every matching row, and with N > 500 they must log,
    through an index rather than a table scan.

  validate_sorted.py          local Kestrel (local-run.sh)
  validate_sorted.py --eks    the reference environment (deploy.sh)
"""
import io
import json
import os
import re
import sys
import time
import uuid
import zipfile
from datetime import datetime, timedelta, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import local_validate_m2c as m  # noqa: E402  (sign-in, API calls, server log access)

N = int(os.environ.get("KESTREL_SORTED_N", "1200"))
RESULTS = []
READ_LINE = re.compile(r"Kestrel DynamoDB: (\w+) query read (\d+) rows \(plan ([^,]+), (\d+) matched, offset (-?\d+), max (-?\d+)\)")


def check(name, ok, detail=""):
    RESULTS.append(ok)
    m.check(name, ok, detail)


def iso(t):
    return t.strftime("%Y-%m-%dT%H:%M:%SZ")


def archive(project, job_uuid):
    """One job, N executions (completion times shared by threes: ties), N reports, in Rundeck's export layout."""
    base = datetime(2026, 1, 1, tzinfo=timezone.utc)
    root = f"rundeck-{project}/"
    buf = io.BytesIO()
    execs = []
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nRundeck-Archive-Format-Version: 1.0\n"
                   f"Rundeck-Archive-Project-Name: {project}\n")
        z.writestr(root + f"jobs/job-{job_uuid}.xml", f"""<joblist><job><id>{job_uuid}</id><uuid>{job_uuid}</uuid>
<name>scale</name><group></group><description></description><loglevel>INFO</loglevel>
<sequence keepgoing='false' strategy='node-first'><command><exec>echo hi</exec></command></sequence></job></joblist>""")
        for i in range(1, N + 1):
            started = base + timedelta(minutes=i)
            completed = base + timedelta(minutes=3 * (i // 3), seconds=30)
            status = "failed" if i % 5 == 0 else "succeeded"
            eu = str(uuid.uuid4())
            execs.append({"i": i, "uuid": eu, "completed": completed, "status": status})
            z.writestr(root + f"executions/execution-{i}.xml", f"""<executions><execution id='{i}'>
<jobId>{job_uuid}</jobId><dateStarted>{iso(started)}</dateStarted><dateCompleted>{iso(completed)}</dateCompleted>
<status>{status}</status><outputfilepath>output-{i}.rdlog</outputfilepath><failedNodeList /><succeededNodeList>localhost</succeededNodeList>
<abortedby /><cancelled>false</cancelled><argString /><loglevel>INFO</loglevel><uuid>{eu}</uuid><doNodedispatch>false</doNodedispatch>
<executionType>user</executionType><project>{project}</project><user>admin</user>
<workflow keepgoing='false' strategy='node-first'><command><exec>echo hi</exec></command></workflow></execution></executions>""")
            z.writestr(root + f"executions/output-{i}.rdlog", "")
            z.writestr(root + f"reports/report-{i}.xml", f"""<report><node>1/0/1</node><title>scale</title>
<status>{'fail' if status == 'failed' else 'succeed'}</status><actionType>{'fail' if status == 'failed' else 'succeed'}</actionType>
<project>{project}</project><reportId>scale</reportId><tags /><author>admin</author><message>Job status {status}</message>
<dateStarted>{iso(started)}</dateStarted><dateCompleted>{iso(completed)}</dateCompleted><executionId>{i}</executionId>
<jobId>{i}</jobId><adhocExecution>false</adhocExecution><adhocScript /><abortedByUser /><succeededNodeList>localhost</succeededNodeList>
<failedNodeList /><filterApplied /><jobUuid>{job_uuid}</jobUuid><executionUuid>{eu}</executionUuid></report>""")
    return buf.getvalue(), execs


def server_lines(since):
    if m.EKS:
        import soak
        pods = [p for p in soak.kubectl("get", "pods", "-o", "jsonpath={range .items[*]}{.metadata.name} {end}").split()
                if p.startswith("kestrel-web-")]
        secs = int(time.time() - since) + 30
        return "\n".join(soak.kubectl("logs", p, "-c", "rundeck", f"--since={secs}s", check=False) for p in pods).splitlines()
    with open(m.LOG, errors="replace") as fh:
        fh.seek(since)
        return fh.readlines()


def main():
    since = time.time() if m.EKS else os.path.getsize(m.LOG)
    ui, user, cleanup = m.sign_in()
    try:
        run(ui, user, since)
    finally:
        cleanup()
    print(f"\n{sum(RESULTS)}/{len(RESULTS)} checks passed")
    sys.exit(0 if all(RESULTS) else 1)


def run(ui, user, since):
    A = m.API
    s, body = m.call(ui, f"/api/{A}/tokens", "POST", json.dumps({"user": user, "roles": "admin", "duration": "2h"}))
    T = {"X-Rundeck-Auth-Token": json.loads(body)["token"]}
    project = f"sorted-{int(time.time())}"
    s, _ = m.call(ui, f"/api/{A}/projects", "POST", json.dumps({"name": project}), headers=T)
    check("setup: project created", s == 201, f"HTTP {s}")
    data, execs = archive(project, str(uuid.uuid4()))

    t0 = time.time()
    import urllib.error
    import urllib.request
    req = urllib.request.Request(
        m.BASE + f"/api/{A}/project/{project}/import?importExecutions=true&jobUuidOption=remove&importConfig=false&importACL=false",
        data=data, method="PUT", headers=dict(T, Accept="application/json", **{"Content-Type": "application/zip"}))
    try:
        with ui.open(req, timeout=1800) as r:
            result = json.loads(r.read())
    except urllib.error.HTTPError as e:
        if e.code != 504:
            raise
        # CloudFront gives up on the origin after 60 s; the import goes on. Wait for all of it.
        result = {"import_status": "successful", "note": "CloudFront 504; polled until complete"}
        for _ in range(360):
            s1, b1 = m.call(ui, f"/api/{A}/project/{project}/executions?max=1", headers=T)
            s2, b2 = m.call(ui, f"/api/{A}/project/{project}/history?max=1", headers=T)
            n1 = json.loads(b1).get("paging", {}).get("total") if s1 == 200 else None
            n2 = json.loads(b2).get("paging", {}).get("total") if s2 == 200 else None
            if n1 == N and n2 == N:
                break
            time.sleep(10)
        else:
            result = {"import_status": "incomplete", "executions": n1, "reports": n2}
    check(f"setup: {N} executions imported", result.get("import_status") == "successful",
          f"{time.time() - t0:.0f}s, {json.dumps(result)[:200]}")

    # Rundeck's order: dateCompleted desc, then id desc. The API has no execution uuid, so a page
    # must carry exactly the expected completion times, ties in descending id order.
    expected = [int(e["completed"].timestamp() * 1000) for e in sorted(execs, key=lambda e: e["completed"], reverse=True)]
    measure = time.time() if m.EKS else os.path.getsize(m.LOG)
    lat = []
    seen = set()
    for off in (0, 20, N // 2, N - 20):
        t = time.time()
        s, body = m.call(ui, f"/api/{A}/project/{project}/executions?max=20&offset={off}", headers=T)
        lat.append(time.time() - t)
        got = json.loads(body) if s == 200 else {}
        rows = [(e["date-ended"]["unixtime"], int(e["id"])) for e in got.get("executions", [])]
        ties_ok = all(a[1] > b[1] for a, b in zip(rows, rows[1:]) if a[0] == b[0])
        fresh = not (seen & {r[1] for r in rows})
        seen |= {r[1] for r in rows}
        check(f"executions page offset {off}: Rundeck's order, total {N}",
              [r[0] for r in rows] == expected[off:off + 20] and ties_ok and fresh and got.get("paging", {}).get("total") == N,
              f"HTTP {s}, total {got.get('paging', {}).get('total')}, first {rows[:2]}")

    s, body = m.call(ui, f"/api/{A}/project/{project}/jobs", headers=T)
    jid = json.loads(body)[0]["id"] if s == 200 else None
    t = time.time()
    s, body = m.call(ui, f"/api/{A}/job/{jid}/executions?max=10", headers=T)
    lat.append(time.time() - t)
    got = json.loads(body) if s == 200 else {}
    ends = [e.get("date-ended", {}).get("unixtime") for e in got.get("executions", [])]
    check("job executions page: newest first, total, under 3 s", len(ends) == 10 and ends == sorted(ends, reverse=True)
          and got.get("paging", {}).get("total") == N and lat[-1] < 3,
          f"HTTP {s}, total {got.get('paging', {}).get('total')}, {lat[-1] * 1000:.0f} ms")

    t = time.time()
    s, body = m.call(ui, f"/api/{A}/project/{project}/history?max=20", headers=T)
    lat.append(time.time() - t)
    got = json.loads(body) if s == 200 else {}
    ends = [e.get("endtime") for e in got.get("events", [])]  # history events: date-ended is a string here
    newest = int(max(e["completed"] for e in execs).timestamp() * 1000)
    check("history page: newest first", len(ends) == 20 and ends == sorted(ends, reverse=True) and ends[0] == newest,
          f"HTTP {s}, {len(ends)} events, first {ends[:1]}, expected {newest}")

    time.sleep(3 if not m.EKS else 10)
    reads = [READ_LINE.search(ln) for ln in server_lines(measure)]
    reads = [r for r in reads if r and r.group(1) in ("Execution", "ExecReport", "BaseReport")]
    # a page at offset k has to read k + max rows (plus rows tied on the sort value); never more
    pages = [r for r in reads if r.group(6) != "-1"]
    counts = [r.group(0) for r in reads if r.group(6) == "-1"]
    over = [r.group(0) for r in pages if int(r.group(2)) > max(0, int(r.group(5))) + int(r.group(6)) + 10
            or not r.group(3).startswith("sorted:")]
    check("page queries read only offset + max rows, through a sorted index", not over,
          "; ".join(over[:3]) or f"{len(pages)} deep page(s) logged, e.g. " + "; ".join(r.group(0) for r in pages[:2]))
    if N > 500:  # only reads over 500 rows are logged
        check("totals count through an index, never a table scan",
              counts and not any("plan scan" in c for c in counts), "; ".join(counts[:3]))
    print(f"      page latencies: {', '.join(f'{x * 1000:.0f} ms' for x in lat)}")

    s, _ = m.call(ui, f"/api/{A}/project/{project}", "DELETE", headers=T)
    check("cleanup: project deleted", s == 204, f"HTTP {s}")


if __name__ == "__main__":
    main()
