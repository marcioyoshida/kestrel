package org.rundeck.kestrel.scheduler;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS clients for the scheduler: JDK URLConnection transport (no Netty/Apache on the web
 * classpath) and the default credential chain, which picks up EKS Pod Identity.
 */
public final class AwsClients {
    private AwsClients() {
    }

    /**
     * @param region AWS region
     * @return SQS client; long polls of 20 s fit the default socket timeout
     */
    public static SqsClient sqs(String region) {
        return SqsClient.builder().region(Region.of(region))
            .httpClientBuilder(UrlConnectionHttpClient.builder()
                .socketTimeout(java.time.Duration.ofSeconds(30)))
            .build();
    }

    /**
     * @param region AWS region
     * @return DynamoDB client
     */
    public static DynamoDbClient dynamo(String region) {
        return DynamoDbClient.builder().region(Region.of(region))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();
    }
}
