#!/usr/bin/env python3
"""Rewrite upstream's functional-test compose file for Kestrel's DynamoDB storage (M2d exit check).

Usage: api_suite_compose.py <docker-compose.yml> dynamodb|rdbms

rdbms leaves the file as upstream wrote it (MySQL), the baseline. dynamodb replaces the MySQL
service with DynamoDB Local and points Kestrel at it: no JDBC settings reach the container, so
the app runs with no RDBMS at all, as on EKS. Run in CI only; it rewrites the file in place.
"""
import sys

import yaml

DYNAMODB_LOCAL = "amazon/dynamodb-local:3.3.1"  # same image as the datastore spec job

path, mode = sys.argv[1], sys.argv[2]
if mode == "rdbms":
    sys.exit(0)
if mode != "dynamodb":
    sys.exit(f"unknown storage mode {mode!r}")

with open(path) as f:
    compose = yaml.safe_load(f)
services = compose["services"]
env = services["rundeck"]["environment"]
dropped = sorted(k for k in env if k.startswith(("RUNDECK_DATABASE_", "SPRING_DATASOURCE_")))
for k in dropped:
    del env[k]
env.update({
    "KESTREL_STORAGE": "dynamodb",
    "KESTREL_DYNAMODB_ENDPOINT": "http://rundeck-db:8000",
    "KESTREL_DYNAMODB_PREFIX": "apitest",
    "AWS_REGION": "us-east-1",
    "AWS_ACCESS_KEY_ID": "local",
    "AWS_SECRET_ACCESS_KEY": "local",
})
# Same service name, so nothing else in the file changes.
services["rundeck-db"] = {
    "image": DYNAMODB_LOCAL,
    "command": ["-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb", "-port", "8000"],
}
with open(path, "w") as f:
    yaml.safe_dump(compose, f, sort_keys=False)
print(f"{path}: DynamoDB Local as rundeck-db; dropped {', '.join(dropped)}")
