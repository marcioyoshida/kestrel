"""Kestrel CI (persistent, ~free): ECR repository for images built from this fork, and a GitHub
Actions OIDC role that may push to it from `main` only. Kept apart from KestrelRef so the
reference environment can be destroyed after each validation without losing images.
"""
from aws_cdk import CfnOutput, Duration, RemovalPolicy, Stack, Tags, aws_ecr as ecr, aws_iam as iam
from constructs import Construct

GITHUB_REPO = "marcioyoshida/kestrel"


class KestrelCiStack(Stack):
    def __init__(self, scope: Construct, cid: str, **kw) -> None:
        super().__init__(scope, cid, **kw)
        Tags.of(self).add("project", "kestrel")

        repo = ecr.Repository(
            self, "Images",
            repository_name="kestrel",
            image_scan_on_push=True,
            image_tag_mutability=ecr.TagMutability.MUTABLE,  # `main` moves; commit tags never reused
            lifecycle_rules=[
                ecr.LifecycleRule(description="untagged", tag_status=ecr.TagStatus.UNTAGGED,
                                  max_image_age=Duration.days(7)),
                ecr.LifecycleRule(description="keep 20", tag_status=ecr.TagStatus.ANY, max_image_count=20),
            ],
            removal_policy=RemovalPolicy.RETAIN,
        )

        provider = iam.OidcProviderNative(
            self, "GitHubOidc",
            url="https://token.actions.githubusercontent.com",
            client_ids=["sts.amazonaws.com"],  # native AWS::IAM::OIDCProvider, no Lambda
        )
        role = iam.Role(
            self, "GitHubPushRole",
            role_name="kestrel-github-ecr-push",
            max_session_duration=Duration.hours(1),
            assumed_by=iam.WebIdentityPrincipal(provider.oidc_provider_arn, conditions={
                "StringEquals": {
                    "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
                    "token.actions.githubusercontent.com:sub": f"repo:{GITHUB_REPO}:ref:refs/heads/main",
                },
            }),
        )
        repo.grant_push(role)

        CfnOutput(self, "RepositoryUri", value=repo.repository_uri)
        CfnOutput(self, "PushRoleArn", value=role.role_arn)
