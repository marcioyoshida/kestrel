#!/usr/bin/env python3
"""CDK app for the Kestrel reference environment (account via --profile my2027, us-east-1)."""
import aws_cdk as cdk

from kestrel_stack import KestrelStack

app = cdk.App()
KestrelStack(
    app, "KestrelRef",
    env=cdk.Environment(account="668449743071", region="us-east-1"),
    admin_principal_arn=app.node.try_get_context("adminPrincipalArn")
    or "arn:aws:iam::668449743071:user/user_console",
)
app.synth()
