"""Kestrel reference environment (M0) — ADR 0001.

One stack: VPC, EKS Auto Mode cluster, RDS PostgreSQL (until M2 moves to DynamoDB), S3 log
bucket, internal ALB + target group (bound to pods by a TargetGroupBinding in the Helm chart),
CloudFront with a VPC origin, and a Cognito user pool as the default OIDC provider.

The ALB is owned here rather than by a Kubernetes Ingress so that CloudFront can reference it
at synth time and `helm uninstall` can never delete the origin out from under the distribution.
"""
from aws_cdk import (
    CfnOutput,
    Duration,
    RemovalPolicy,
    Stack,
    Tags,
    aws_cloudfront as cf,
    aws_cloudfront_origins as origins,
    aws_cognito as cognito,
    aws_ec2 as ec2,
    aws_eks as eks,
    aws_elasticloadbalancingv2 as elbv2,
    aws_iam as iam,
    aws_rds as rds,
    aws_s3 as s3,
)
from constructs import Construct

CLUSTER_NAME = "kestrel-ref"
K8S_VERSION = "1.36"
NAMESPACE = "kestrel"
SERVICE_ACCOUNT = "kestrel"
PROXY_PORT = 4180  # oauth2-proxy sidecar; Rundeck itself listens on 127.0.0.1:4440 only
# CloudFront VPC origins are unsupported in use1-az3, which is us-east-1e in this account.
AZS = ["us-east-1a", "us-east-1b"]
CLOUDFRONT_ORIGIN_FACING_PL = "pl-3b927c52"  # com.amazonaws.global.cloudfront.origin-facing


class KestrelStack(Stack):
    def __init__(self, scope: Construct, cid: str, *, admin_principal_arn: str, ephemeral: bool = False,
                 **kw) -> None:
        super().__init__(scope, cid, **kw)
        Tags.of(self).add("project", "kestrel")
        # ephemeral: validation runs; `cdk destroy` leaves nothing behind (no bucket, pool or snapshot).
        keep = RemovalPolicy.DESTROY if ephemeral else RemovalPolicy.RETAIN

        # ---------------------------------------------------------------- network
        vpc = ec2.Vpc(
            self, "Vpc",
            ip_addresses=ec2.IpAddresses.cidr("10.40.0.0/16"),
            availability_zones=AZS,
            nat_gateways=1,  # reference env; one per AZ in prod (ADR 0001 §7)
            subnet_configuration=[
                ec2.SubnetConfiguration(name="public", subnet_type=ec2.SubnetType.PUBLIC, cidr_mask=24),
                ec2.SubnetConfiguration(name="private", subnet_type=ec2.SubnetType.PRIVATE_WITH_EGRESS, cidr_mask=19),
                ec2.SubnetConfiguration(name="data", subnet_type=ec2.SubnetType.PRIVATE_ISOLATED, cidr_mask=26),
            ],
            gateway_endpoints={  # free; keeps S3/DynamoDB traffic off the NAT gateway
                "S3": ec2.GatewayVpcEndpointOptions(service=ec2.GatewayVpcEndpointAwsService.S3),
                "DynamoDB": ec2.GatewayVpcEndpointOptions(service=ec2.GatewayVpcEndpointAwsService.DYNAMODB),
            },
        )
        for sn in vpc.public_subnets:
            Tags.of(sn).add("kubernetes.io/role/elb", "1")
        for sn in vpc.private_subnets:
            Tags.of(sn).add("kubernetes.io/role/internal-elb", "1")

        # ---------------------------------------------------------------- EKS Auto Mode
        cluster_role = iam.Role(
            self, "ClusterRole",
            assumed_by=iam.ServicePrincipal("eks.amazonaws.com"),
            managed_policies=[
                iam.ManagedPolicy.from_aws_managed_policy_name(p)
                for p in (
                    "AmazonEKSClusterPolicy",
                    "AmazonEKSComputePolicy",
                    "AmazonEKSBlockStoragePolicy",
                    "AmazonEKSLoadBalancingPolicy",
                    "AmazonEKSNetworkingPolicy",
                )
            ],
        )
        cluster_role.assume_role_policy.add_statements(iam.PolicyStatement(
            actions=["sts:TagSession"], principals=[iam.ServicePrincipal("eks.amazonaws.com")]))
        node_role = iam.Role(
            self, "NodeRole",
            assumed_by=iam.ServicePrincipal("ec2.amazonaws.com"),
            managed_policies=[
                iam.ManagedPolicy.from_aws_managed_policy_name("AmazonEKSWorkerNodeMinimalPolicy"),
                iam.ManagedPolicy.from_aws_managed_policy_name("AmazonEC2ContainerRegistryPullOnly"),
            ],
        )

        cluster = eks.CfnCluster(
            self, "Cluster",
            name=CLUSTER_NAME,
            version=K8S_VERSION,
            role_arn=cluster_role.role_arn,
            resources_vpc_config=eks.CfnCluster.ResourcesVpcConfigProperty(
                subnet_ids=[s.subnet_id for s in vpc.private_subnets],
                endpoint_private_access=True,
                endpoint_public_access=True,  # IAM-authenticated; narrow publicAccessCidrs for prod
            ),
            access_config=eks.CfnCluster.AccessConfigProperty(
                authentication_mode="API",
                bootstrap_cluster_creator_admin_permissions=False,
            ),
            bootstrap_self_managed_addons=False,  # required by Auto Mode
            compute_config=eks.CfnCluster.ComputeConfigProperty(
                enabled=True, node_pools=["general-purpose", "system"], node_role_arn=node_role.role_arn),
            kubernetes_network_config=eks.CfnCluster.KubernetesNetworkConfigProperty(
                elastic_load_balancing=eks.CfnCluster.ElasticLoadBalancingProperty(enabled=True)),
            storage_config=eks.CfnCluster.StorageConfigProperty(
                block_storage=eks.CfnCluster.BlockStorageProperty(enabled=True)),
            # Extended support bills $0.60/h instead of $0.10/h: never fall into it silently.
            upgrade_policy=eks.CfnCluster.UpgradePolicyProperty(support_type="STANDARD"),
        )
        cluster_sg_id = cluster.attr_cluster_security_group_id

        eks.CfnAccessEntry(
            self, "AdminAccess",
            cluster_name=cluster.ref,
            principal_arn=admin_principal_arn,
            access_policies=[eks.CfnAccessEntry.AccessPolicyProperty(
                policy_arn="arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy",
                access_scope=eks.CfnAccessEntry.AccessScopeProperty(type="cluster"),
            )],
        )

        # ---------------------------------------------------------------- execution logs
        log_bucket = s3.Bucket(
            self, "Logs",
            block_public_access=s3.BlockPublicAccess.BLOCK_ALL,
            encryption=s3.BucketEncryption.S3_MANAGED,
            enforce_ssl=True,
            lifecycle_rules=[s3.LifecycleRule(expiration=Duration.days(90),
                                              abort_incomplete_multipart_upload_after=Duration.days(1))],
            removal_policy=keep,
            auto_delete_objects=ephemeral,
        )

        web_role = iam.Role(self, "WebPodRole", assumed_by=iam.ServicePrincipal("pods.eks.amazonaws.com"))
        web_role.assume_role_policy.add_statements(iam.PolicyStatement(
            actions=["sts:TagSession"], principals=[iam.ServicePrincipal("pods.eks.amazonaws.com")]))
        log_bucket.grant_read_write(web_role)
        log_bucket.grant_delete(web_role)
        eks.CfnPodIdentityAssociation(
            self, "WebPodIdentity",
            cluster_name=cluster.ref, namespace=NAMESPACE, service_account=SERVICE_ACCOUNT,
            role_arn=web_role.role_arn,
        )

        # ---------------------------------------------------------------- database (M0–M1 only)
        db_sg = ec2.SecurityGroup(self, "DbSg", vpc=vpc, description="Kestrel RDS", allow_all_outbound=False)
        ec2.CfnSecurityGroupIngress(
            self, "DbFromCluster", group_id=db_sg.security_group_id, ip_protocol="tcp",
            from_port=5432, to_port=5432, source_security_group_id=cluster_sg_id,
            description="EKS Auto Mode nodes/pods")
        db = rds.DatabaseInstance(
            self, "Db",
            engine=rds.DatabaseInstanceEngine.postgres(version=rds.PostgresEngineVersion.VER_17),
            instance_type=ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.MICRO),
            vpc=vpc,
            vpc_subnets=ec2.SubnetSelection(subnet_type=ec2.SubnetType.PRIVATE_ISOLATED),
            security_groups=[db_sg],
            database_name="kestrel",
            credentials=rds.Credentials.from_generated_secret("kestrel"),
            allocated_storage=20,
            storage_type=rds.StorageType.GP3,
            storage_encrypted=True,
            multi_az=False,
            backup_retention=Duration.days(1),
            removal_policy=RemovalPolicy.DESTROY if ephemeral else RemovalPolicy.SNAPSHOT,
        )

        # ---------------------------------------------------------------- internal ALB
        alb_sg = ec2.SecurityGroup(self, "AlbSg", vpc=vpc, description="Kestrel internal ALB")
        alb_sg.add_ingress_rule(ec2.Peer.prefix_list(CLOUDFRONT_ORIGIN_FACING_PL), ec2.Port.tcp(80),
                                "CloudFront VPC origin")
        ec2.CfnSecurityGroupIngress(
            self, "ProxyFromAlb", group_id=cluster_sg_id, ip_protocol="tcp",
            from_port=PROXY_PORT, to_port=PROXY_PORT, source_security_group_id=alb_sg.security_group_id,
            description="ALB -> oauth2-proxy sidecar")
        alb = elbv2.ApplicationLoadBalancer(
            self, "Alb", vpc=vpc, internet_facing=False, security_group=alb_sg,
            vpc_subnets=ec2.SubnetSelection(subnet_type=ec2.SubnetType.PRIVATE_WITH_EGRESS),
        )
        tg = elbv2.ApplicationTargetGroup(
            self, "WebTg", vpc=vpc, port=PROXY_PORT, protocol=elbv2.ApplicationProtocol.HTTP,
            target_type=elbv2.TargetType.IP,
            health_check=elbv2.HealthCheck(path="/monitoring/health/readiness", healthy_http_codes="200",
                                           interval=Duration.seconds(15)),
            deregistration_delay=Duration.seconds(30),
            stickiness_cookie_duration=Duration.hours(1),  # in-memory sessions until M1
        )
        # Lets the Auto Mode controller (AmazonEKSLoadBalancingPolicy) register pod IPs.
        Tags.of(tg).add("eks:eks-cluster-name", CLUSTER_NAME)
        listener = alb.add_listener("Http", port=80, open=False, default_target_groups=[tg])
        # Upstream serves /monitoring/** with permitAll (thread dumps, metrics).
        listener.add_action("BlockMonitoring", priority=10,
                            conditions=[elbv2.ListenerCondition.path_patterns(["/monitoring", "/monitoring/*"])],
                            action=elbv2.ListenerAction.fixed_response(404, content_type="text/plain",
                                                                       message_body="Not Found"))

        # ---------------------------------------------------------------- CloudFront
        block_fn = cf.Function(
            self, "BlockMonitoringFn",
            runtime=cf.FunctionRuntime.JS_2_0,
            code=cf.FunctionCode.from_inline(
                "function handler(event){var u=event.request.uri;"
                "if(u==='/monitoring'||u.indexOf('/monitoring/')===0){"
                "return {statusCode:404,statusDescription:'Not Found'};}"
                "return event.request;}"),
        )
        origin = origins.VpcOrigin.with_application_load_balancer(
            alb, protocol_policy=cf.OriginProtocolPolicy.HTTP_ONLY,
            read_timeout=Duration.seconds(60), keepalive_timeout=Duration.seconds(30))
        static = cf.BehaviorOptions(
            origin=origin,
            viewer_protocol_policy=cf.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
            cache_policy=cf.CachePolicy.CACHING_OPTIMIZED,
            compress=True,
        )
        dist = cf.Distribution(
            self, "Cdn",
            comment="Kestrel reference",
            price_class=cf.PriceClass.PRICE_CLASS_100,
            http_version=cf.HttpVersion.HTTP2_AND_3,
            default_behavior=cf.BehaviorOptions(
                origin=origin,
                viewer_protocol_policy=cf.ViewerProtocolPolicy.REDIRECT_TO_HTTPS,
                allowed_methods=cf.AllowedMethods.ALLOW_ALL,
                cache_policy=cf.CachePolicy.CACHING_DISABLED,
                origin_request_policy=cf.OriginRequestPolicy.ALL_VIEWER,
                compress=True,
                function_associations=[cf.FunctionAssociation(
                    function=block_fn, event_type=cf.FunctionEventType.VIEWER_REQUEST)],
            ),
            additional_behaviors={"/assets/*": static, "/static/*": static},
        )
        public_url = f"https://{dist.distribution_domain_name}"

        # ---------------------------------------------------------------- Cognito (default IdP)
        pool = cognito.UserPool(
            self, "Users",
            user_pool_name="kestrel-users",
            self_sign_up_enabled=False,
            sign_in_aliases=cognito.SignInAliases(email=True),
            auto_verify=cognito.AutoVerifiedAttrs(email=True),
            password_policy=cognito.PasswordPolicy(min_length=12),
            mfa=cognito.Mfa.OPTIONAL,
            mfa_second_factor=cognito.MfaSecondFactor(sms=False, otp=True),
            account_recovery=cognito.AccountRecovery.EMAIL_ONLY,
            removal_policy=keep,
        )
        # Group names become Rundeck roles via the cognito:groups claim; `admin` matches the
        # image's default admin.aclpolicy.
        for g in ("admin", "user"):
            cognito.CfnUserPoolGroup(self, f"Group{g}", user_pool_id=pool.user_pool_id, group_name=g)
        domain = pool.add_domain("Domain", cognito_domain=cognito.CognitoDomainOptions(
            domain_prefix=f"kestrel-{self.account}"))
        client = pool.add_client(
            "Web",
            generate_secret=True,
            o_auth=cognito.OAuthSettings(
                flows=cognito.OAuthFlows(authorization_code_grant=True),
                scopes=[cognito.OAuthScope.OPENID, cognito.OAuthScope.EMAIL, cognito.OAuthScope.PROFILE],
                callback_urls=[f"{public_url}/oauth2/callback"],
                logout_urls=[f"{public_url}/"],
            ),
            supported_identity_providers=[cognito.UserPoolClientIdentityProvider.COGNITO],
            prevent_user_existence_errors=True,
        )

        # ---------------------------------------------------------------- outputs (read by deploy.sh)
        for k, v in {
            "ClusterName": cluster.ref,
            "PublicUrl": public_url,
            "DistributionId": dist.distribution_id,
            "TargetGroupArn": tg.target_group_arn,
            "LogBucket": log_bucket.bucket_name,
            "DbEndpoint": db.db_instance_endpoint_address,
            "DbSecretArn": db.secret.secret_arn,
            "OidcIssuer": f"https://cognito-idp.{self.region}.amazonaws.com/{pool.user_pool_id}",
            "UserPoolId": pool.user_pool_id,
            "ClientId": client.user_pool_client_id,
            "CognitoDomain": domain.base_url(),
        }.items():
            CfnOutput(self, k, value=v)
