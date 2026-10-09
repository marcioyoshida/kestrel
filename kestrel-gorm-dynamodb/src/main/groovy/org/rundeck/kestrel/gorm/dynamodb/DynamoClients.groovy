package org.rundeck.kestrel.gorm.dynamodb

import groovy.transform.CompileStatic
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * DynamoDB client for the datastore: JDK URLConnection transport and the default credential
 * chain (EKS Pod Identity). An endpoint override (DynamoDB Local) uses dummy credentials.
 */
@CompileStatic
class DynamoClients {
    /**
     * @param region   AWS region
     * @param endpoint endpoint override (e.g. http://localhost:8000), or null for AWS
     * @return client
     */
    static DynamoDbClient create(String region, String endpoint) {
        def b = DynamoDbClient.builder().region(Region.of(region ?: 'us-east-1'))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
        if (endpoint) {
            b.endpointOverride(URI.create(endpoint))
             .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create('local', 'local')))
        }
        b.build()
    }
}
