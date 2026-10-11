#!/usr/bin/env python3
"""CDK app for Kestrel (account via --profile my2027, us-east-1).

- KestrelCi: ECR + GitHub OIDC push role. Persistent, near-zero cost.
- KestrelRef: the reference environment. Deploy with `-c ephemeral=true` for validation runs so
  `cdk destroy` removes everything (log bucket, user pool, database without a final snapshot).
  No RDS unless `-c rdbms=true` (storage.mode=rdbms; the default dynamodb mode needs none, M2d).
"""
import aws_cdk as cdk

from ci_stack import KestrelCiStack
from kestrel_stack import KestrelStack

app = cdk.App()
env = cdk.Environment(account="668449743071", region="us-east-1")
KestrelCiStack(app, "KestrelCi", env=env)
KestrelStack(
    app, "KestrelRef",
    env=env,
    admin_principal_arn=app.node.try_get_context("adminPrincipalArn")
    or "arn:aws:iam::668449743071:user/user_console",
    ephemeral=str(app.node.try_get_context("ephemeral")).lower() == "true",
    rdbms=str(app.node.try_get_context("rdbms")).lower() == "true",
)
app.synth()
