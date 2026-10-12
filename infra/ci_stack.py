"""Kestrel CI (persistent, ~free): ECR repository for images built from this fork, and a GitHub
Actions OIDC role that may push to it from `main` only. Kept apart from KestrelRef so the
reference environment can be destroyed after each validation without losing images.

Also the cost guards for KestrelRef, which costs about $0.47/h while up: a monthly budget on the
`project=kestrel` cost allocation tag, and a reaper that deletes KestrelRef when its lease (set by
deploy.sh) runs out.
"""
import pathlib

from aws_cdk import (
    CfnOutput,
    Duration,
    RemovalPolicy,
    Stack,
    Tags,
    aws_budgets as budgets,
    aws_ecr as ecr,
    aws_events as events,
    aws_events_targets as targets,
    aws_iam as iam,
    aws_lambda as lambda_,
    aws_logs as logs,
)
from constructs import Construct

# GitHub issues immutable OIDC subjects (owner@id/repo@id), so a renamed or re-created repo with
# the same name can never assume this role. This repo itself was renamed from an old fork.
GITHUB_SUBJECT = "repo:marcioyoshida@18469770/kestrel@193158319"

BUDGET_USD = 30
BUDGET_EMAIL = "marcioyoshida+aws@gmail.com"  # same recipient as the account's other project budgets
REF_STACK = "KestrelRef"
REF_CLUSTER = "kestrel-ref"
REF_TABLE_PREFIX = "kestrel-ref-"  # ADR 0004 tables the app creates on demand (kestrel_stack.DATA_TABLE_PREFIX)
LEASE_PARAM = "/kestrel/ref/lease-until"
# KestrelRef grants this role cluster access by name (kestrel_stack.REAPER_ROLE_NAME).
REAPER_ROLE_NAME = "kestrel-ref-reaper"


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

        trigger_repo = ecr.Repository(
            self, "TriggerImages",
            repository_name="kestrel-trigger",
            image_scan_on_push=True,
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
                    "token.actions.githubusercontent.com:sub": f"{GITHUB_SUBJECT}:ref:refs/heads/main",
                },
            }),
        )
        repo.grant_push(role)
        trigger_repo.grant_pull_push(role)  # buildx reads back the multi-arch manifest list

        self._budget()
        reaper = self._reaper()

        CfnOutput(self, "RepositoryUri", value=repo.repository_uri)
        CfnOutput(self, "TriggerRepositoryUri", value=trigger_repo.repository_uri)
        CfnOutput(self, "PushRoleArn", value=role.role_arn)
        CfnOutput(self, "ReaperFunctionName", value=reaper.function_name)

    def _budget(self) -> None:
        """Monthly ceiling on everything tagged project=kestrel (the stacks tag their resources;
        KestrelRef's NodeClass tags the EC2 nodes, volumes and ENIs EKS Auto Mode launches)."""
        subscribers = [budgets.CfnBudget.SubscriberProperty(subscription_type="EMAIL", address=BUDGET_EMAIL)]
        budgets.CfnBudget(
            self, "Budget",
            budget=budgets.CfnBudget.BudgetDataProperty(
                budget_name="kestrel-prototype-ceiling",
                budget_type="COST",
                time_unit="MONTHLY",
                budget_limit=budgets.CfnBudget.SpendProperty(amount=BUDGET_USD, unit="USD"),
                cost_filters={"TagKeyValue": ["user:project$kestrel"]},
            ),
            notifications_with_subscribers=[
                budgets.CfnBudget.NotificationWithSubscribersProperty(
                    notification=budgets.CfnBudget.NotificationProperty(
                        notification_type=kind, comparison_operator="GREATER_THAN",
                        threshold=threshold, threshold_type="PERCENTAGE"),
                    subscribers=subscribers)
                for kind, threshold in (("ACTUAL", 50), ("ACTUAL", 80), ("FORECASTED", 100))
            ],
        )

    def _reaper(self) -> lambda_.Function:
        """Deletes KestrelRef when its lease runs out (infra/reaper/handler.py)."""
        role = iam.Role(
            self, "ReaperRole",
            role_name=REAPER_ROLE_NAME,
            assumed_by=iam.ServicePrincipal("lambda.amazonaws.com"),
            managed_policies=[iam.ManagedPolicy.from_aws_managed_policy_name(
                "service-role/AWSLambdaBasicExecutionRole")],
        )
        stack_arn = f"arn:aws:cloudformation:{self.region}:{self.account}:stack/{REF_STACK}/*"
        role.add_to_policy(iam.PolicyStatement(
            actions=["cloudformation:DescribeStacks", "cloudformation:DeleteStack"], resources=[stack_arn]))
        # DeleteStack runs as the stack's own service role (the CDK execution role it was deployed with).
        role.add_to_policy(iam.PolicyStatement(
            actions=["iam:PassRole"],
            resources=[f"arn:aws:iam::{self.account}:role/cdk-hnb659fds-cfn-exec-role-{self.account}-{self.region}"],
            conditions={"StringEquals": {"iam:PassedToService": "cloudformation.amazonaws.com"}}))
        role.add_to_policy(iam.PolicyStatement(
            actions=["eks:DescribeCluster"],
            resources=[f"arn:aws:eks:{self.region}:{self.account}:cluster/{REF_CLUSTER}"]))
        role.add_to_policy(iam.PolicyStatement(
            actions=["ssm:GetParameter"],
            resources=[f"arn:aws:ssm:{self.region}:{self.account}:parameter{LEASE_PARAM}"]))
        role.add_to_policy(iam.PolicyStatement(actions=["dynamodb:ListTables", "ec2:DescribeInstances"],
                                               resources=["*"]))
        role.add_to_policy(iam.PolicyStatement(
            actions=["dynamodb:DeleteTable"],
            resources=[f"arn:aws:dynamodb:{self.region}:{self.account}:table/{REF_TABLE_PREFIX}*"]))

        log_group = logs.LogGroup(self, "ReaperLogs", retention=logs.RetentionDays.ONE_MONTH,
                                  removal_policy=RemovalPolicy.DESTROY)
        fn = lambda_.Function(
            self, "Reaper",
            runtime=lambda_.Runtime.PYTHON_3_13,
            handler="handler.handler",
            code=lambda_.Code.from_asset(str(pathlib.Path(__file__).parent / "reaper")),
            role=role,
            timeout=Duration.minutes(2),
            memory_size=256,
            log_group=log_group,
            environment={
                "STACK_NAME": REF_STACK, "CLUSTER_NAME": REF_CLUSTER, "TABLE_PREFIX": REF_TABLE_PREFIX,
                "LEASE_PARAM": LEASE_PARAM, "DEFAULT_TTL_HOURS": "8",
            },
        )
        events.Rule(self, "ReaperSchedule", schedule=events.Schedule.rate(Duration.minutes(15)),
                    targets=[targets.LambdaFunction(fn)])
        return fn
