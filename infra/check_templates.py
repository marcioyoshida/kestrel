#!/usr/bin/env python3
"""Pre-deploy checks `cdk synth` does not do. Usage: check_templates.py <cdk.out dir>

EC2 security-group rule descriptions accept only a-zA-Z0-9. _-:/()#,@[]+=&;{}!$* and fail at
deploy time otherwise (KestrelRef rolled back once on "ALB -> sidecar" after 20 minutes).
"""
import json
import pathlib
import re
import sys

ALLOWED = re.compile(r"^[a-zA-Z0-9. _\-:/()#,@\[\]+=&;{}!$*]{0,255}$")
SG_TYPES = {"AWS::EC2::SecurityGroup", "AWS::EC2::SecurityGroupIngress", "AWS::EC2::SecurityGroupEgress"}


def descriptions(props):
    for key in ("GroupDescription", "Description"):
        if isinstance(props.get(key), str):
            yield props[key]
    for rule in props.get("SecurityGroupIngress", []) + props.get("SecurityGroupEgress", []):
        if isinstance(rule.get("Description"), str):
            yield rule["Description"]


bad = []
for tpl in pathlib.Path(sys.argv[1]).glob("*.template.json"):
    for lid, res in json.loads(tpl.read_text())["Resources"].items():
        if res["Type"] in SG_TYPES:
            bad += [f"{tpl.stem}/{lid}: {d!r}" for d in descriptions(res.get("Properties", {})) if not ALLOWED.match(d)]
print("\n".join(bad) or "templates ok")
sys.exit(1 if bad else 0)
