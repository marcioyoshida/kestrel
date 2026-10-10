#!/usr/bin/env python3
"""Stateless sessions, locally (scripts/kestrel/local-run.sh starts Kestrel with KESTREL_SESSIONS=dynamodb).

Phase `login`: form login, then a few session-dependent pages; saves the session cookie.
Phase `after-restart`: with the same cookie in a brand-new JVM, the user is still signed in.
A restart is the strictest form of "another pod serves the next request".
"""
import http.cookiejar
import json
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:4440"
JAR = "/tmp/kestrel-local/session-cookies.txt"
DDB = ["aws", "dynamodb", "--endpoint-url", "http://localhost:8000", "--region", "us-east-1", "--output", "json"]


def opener():
    jar = http.cookiejar.MozillaCookieJar(JAR)
    try:
        jar.load(ignore_discard=True, ignore_expires=True)
    except FileNotFoundError:
        pass
    return jar, urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def get(o, path):
    try:
        with o.open(urllib.request.Request(BASE + path, headers={"Accept": "application/json"}), timeout=30) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def main(phase):
    ok = True
    jar, o = opener()
    if phase == "login":
        o.open(BASE + "/user/login").read()
        o.open(urllib.request.Request(BASE + "/j_security_check", method="POST",
                                      data=urllib.parse.urlencode({"j_username": "admin", "j_password": "admin"}).encode())).read()
        jar.save(ignore_discard=True, ignore_expires=True)
        names = sorted(c.name for c in jar)
        print("cookies:", names)
        ok &= "KESTREL_SESSION" in names and "JSESSIONID" not in names
        s, body = get(o, "/api/60/user/info")
        print("user/info:", s, body[:80])
        ok &= s == 200
        items = json.loads(subprocess.run(DDB + ["scan", "--table-name", "local-ledger"], capture_output=True, text=True,
                                          env={"AWS_ACCESS_KEY_ID": "l", "AWS_SECRET_ACCESS_KEY": "l", "PATH": "/usr/bin:/usr/local/bin:/bin"}).stdout)["Items"]
        sessions = [i for i in items if i["pk"]["S"].startswith("session#")]
        print("session items in DynamoDB:", len(sessions), "attributes:", sorted(sessions[0]["attrs"]["M"].keys()) if sessions else [])
        ok &= len(sessions) >= 1
    else:
        s, body = get(o, "/api/60/user/info")
        print("after restart, user/info:", s, body[:80])
        ok &= s == 200 and json.loads(body).get("login") == "admin"
        s, _ = get(o, "/menu/home")
        print("after restart, /menu/home:", s)
        ok &= s == 200
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
