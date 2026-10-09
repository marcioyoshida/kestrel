package org.rundeck.kestrel.scheduler;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Builds the AWS-backed scheduler parts with the JDK URLConnection transport and the default
 * credential chain (EKS Pod Identity). Callers outside this module use only the factory methods
 * that return Kestrel types, because the SDK is relocated in the shaded jar.
 */
public final class AwsClients {
    private AwsClients() {
    }

    /**
     * @param region   AWS region
     * @param queueUrl SQS FIFO fire queue
     * @param handler  firing handler
     * @return a consumer to run on its own thread
     */
    public static SqsFireConsumer fireConsumer(String region, String queueUrl, SqsFireConsumer.Handler handler) {
        return new SqsFireConsumer(sqs(region), queueUrl, handler);
    }

    /**
     * @param region    AWS region
     * @param table     DynamoDB fire ledger table
     * @param retention how long claims are kept
     * @return the ledger
     */
    public static FireLedger fireLedger(String region, String table, java.time.Duration retention) {
        return new DynamoFireLedger(dynamo(region), table, retention);
    }

    /**
     * @param region AWS region
     * @return SQS client; long polls of 20 s fit within the 30 s socket timeout
     */
    static SqsClient sqs(String region) {
        return SqsClient.builder().region(Region.of(region))
            .httpClientBuilder(UrlConnectionHttpClient.builder()
                .socketTimeout(java.time.Duration.ofSeconds(30)))
            .build();
    }

    /**
     * @param region AWS region
     * @return DynamoDB client
     */
    static DynamoDbClient dynamo(String region) {
        return DynamoDbClient.builder().region(Region.of(region))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();
    }
}
