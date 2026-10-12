"""Kestrel reference-environment reaper: deletes KestrelRef once its lease runs out.

A backstop for `scripts/kestrel/teardown.sh`: a forgotten environment costs about $11 a day.
EventBridge runs this every 15 minutes; each run does the next step, so no run waits long:

1. Lease still valid (SSM `/kestrel/ref/lease-until`, written by deploy.sh; else stack creation +
   8 h): nothing.
2. Expired, cluster up: delete Kestrel's CronJobs and Jobs, the `kestrel` namespace and the
   `kestrel` NodePool, then wait (next runs) until the nodes are gone, so their ENIs don't block
   the VPC. After 45 minutes of waiting it carries on regardless.
3. Delete the stack (CloudFormation uses the stack's own CDK execution role).
4. Stack gone: delete the `kestrel-ref-*` tables the app created (not stack resources), and
   report any instance still tagged for the cluster.

Event `{"dryRun": true}` reports what it would do without changing anything.
"""
import base64
import datetime as dt
import json
import logging
import os
import ssl
import tempfile
import urllib.error
import urllib.request

import boto3
from botocore.signers import RequestSigner

STACK = os.environ.get("STACK_NAME", "KestrelRef")
CLUSTER = os.environ.get("CLUSTER_NAME", "kestrel-ref")
NAMESPACE = os.environ.get("NAMESPACE", "kestrel")
NODE_POOL = os.environ.get("NODE_POOL", "kestrel")
TABLE_PREFIX = os.environ.get("TABLE_PREFIX", "kestrel-ref-")
LEASE_PARAM = os.environ.get("LEASE_PARAM", "/kestrel/ref/lease-until")
DEFAULT_TTL = dt.timedelta(hours=float(os.environ.get("DEFAULT_TTL_HOURS", "8")))
DRAIN_GRACE = dt.timedelta(minutes=45)

log = logging.getLogger()
log.setLevel(logging.INFO)
cfn = boto3.client("cloudformation")
eks = boto3.client("eks")
ssm = boto3.client("ssm")
ddb = boto3.client("dynamodb")
ec2 = boto3.client("ec2")


def now():
    return dt.datetime.now(dt.timezone.utc)


def stack():
    try:
        return cfn.describe_stacks(StackName=STACK)["Stacks"][0]
    except cfn.exceptions.ClientError as e:
        if "does not exist" in str(e):
            return None
        raise


def lease_until(st):
    """The later of the lease parameter (if written for this stack) and creation + default TTL."""
    created = st["CreationTime"]
    until = created + DEFAULT_TTL
    try:
        v = ssm.get_parameter(Name=LEASE_PARAM)["Parameter"]["Value"]
        lease = dt.datetime.fromisoformat(v.replace("Z", "+00:00"))
        if lease > created:
            until = lease  # an explicit lease may also be shorter than the default
    except ssm.exceptions.ParameterNotFound:
        pass
    return until


# ------------------------------------------------------------------ Kubernetes API (no kubectl)
class Kube:
    def __init__(self, cluster):
        self.endpoint = cluster["endpoint"]
        ca = tempfile.NamedTemporaryFile(delete=False, suffix=".crt")
        ca.write(base64.b64decode(cluster["certificateAuthority"]["data"]))
        ca.close()
        self.ctx = ssl.create_default_context(cafile=ca.name)
        self.token = self._token(cluster["name"])

    @staticmethod
    def _token(name):
        session = boto3.session.Session()
        sts = session.client("sts")
        region = session.region_name
        signer = RequestSigner(sts.meta.service_model.service_id, region, "sts", "v4",
                               session.get_credentials(), session.events)
        url = signer.generate_presigned_url(
            {"method": "GET", "url": f"https://sts.{region}.amazonaws.com/?Action=GetCallerIdentity&Version=2011-06-15",
             "body": {}, "headers": {"x-k8s-aws-id": name}, "context": {}},
            region_name=region, expires_in=60, operation_name="")
        return "k8s-aws-v1." + base64.urlsafe_b64encode(url.encode()).decode().rstrip("=")

    def call(self, method, path, body=None):
        req = urllib.request.Request(self.endpoint + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None)
        req.add_header("Authorization", f"Bearer {self.token}")
        req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, context=self.ctx, timeout=20) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            return e.code, {}


def kube_cleanup(kube, dry):
    """Starts deleting everything that keeps nodes alive; returns the number of nodes left."""
    bg = {"kind": "DeleteOptions", "apiVersion": "v1", "propagationPolicy": "Background"}
    steps = [
        ("DELETE", f"/apis/batch/v1/namespaces/{NAMESPACE}/cronjobs?labelSelector=kestrel.io%2Fmanaged-by%3Dkestrel"),
        ("DELETE", f"/apis/batch/v1/namespaces/{NAMESPACE}/jobs"),
        ("DELETE", f"/api/v1/namespaces/{NAMESPACE}"),
        ("DELETE", f"/apis/karpenter.sh/v1/nodepools/{NODE_POOL}"),
    ]
    for method, path in steps:
        if dry:
            log.info("dry run: would %s %s", method, path)
            continue
        code, _ = kube.call(method, path, bg)
        log.info("%s %s -> %s", method, path, code)
    code, nodes = kube.call("GET", "/api/v1/nodes")
    if code != 200:
        log.warning("listing nodes failed (%s); treating the cluster as drained", code)
        return 0
    return len(nodes.get("items", []))


def delete_tables(dry):
    names, start = [], None
    while True:
        page = ddb.list_tables(**({"ExclusiveStartTableName": start} if start else {}))
        names += [t for t in page["TableNames"] if t.startswith(TABLE_PREFIX)]
        start = page.get("LastEvaluatedTableName")
        if not start:
            break
    for t in names:
        if dry:
            log.info("dry run: would delete table %s", t)
            continue
        try:
            ddb.delete_table(TableName=t)
            log.info("deleted table %s", t)
        except ddb.exceptions.ResourceInUseException:
            log.info("table %s busy; next run", t)
    return names


def leftover_instances():
    r = ec2.describe_instances(Filters=[
        {"Name": "tag:eks:eks-cluster-name", "Values": [CLUSTER]},
        {"Name": "instance-state-name", "Values": ["pending", "running", "stopping", "stopped"]}])
    return [i["InstanceId"] for res in r["Reservations"] for i in res["Instances"]]


def handler(event, _context):
    dry = bool((event or {}).get("dryRun"))
    st = stack()
    if st is None:
        tables = delete_tables(dry)
        left = leftover_instances()
        if left:
            log.error("KestrelRef is gone but instances tagged for %s remain: %s", CLUSTER, left)
        return {"state": "absent", "tables": tables, "leftoverInstances": left}

    status = st["StackStatus"]
    until = lease_until(st)
    if status.endswith("_IN_PROGRESS"):
        return {"state": "busy", "status": status, "leaseUntil": until.isoformat()}
    if now() < until and status != "DELETE_FAILED":
        return {"state": "leased", "leaseUntil": until.isoformat()}

    log.info("KestrelRef lease ended %s (status %s)", until.isoformat(), status)
    try:
        cluster = eks.describe_cluster(name=CLUSTER)["cluster"]
    except eks.exceptions.ResourceNotFoundException:
        cluster = None
    if cluster and cluster["status"] == "ACTIVE":
        nodes = kube_cleanup(Kube(cluster), dry)
        if nodes and now() < until + DRAIN_GRACE:
            log.info("%d node(s) still draining; stack delete on a later run", nodes)
            return {"state": "draining", "nodes": nodes, "leaseUntil": until.isoformat()}
    if dry:
        return {"state": "would-delete", "status": status, "leaseUntil": until.isoformat()}
    cfn.delete_stack(StackName=STACK)
    log.warning("deleting stack %s (lease ended %s)", STACK, until.isoformat())
    return {"state": "deleting", "leaseUntil": until.isoformat()}
