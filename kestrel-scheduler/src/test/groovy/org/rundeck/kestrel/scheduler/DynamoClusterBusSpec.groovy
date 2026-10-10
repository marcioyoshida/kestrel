package org.rundeck.kestrel.scheduler

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification

import java.time.Clock

/** Against DynamoDB Local (KESTREL_DYNAMODB_ENDPOINT, default http://localhost:8000). */
@Requires({ DynamoClusterBusSpec.endpointUp() })
class DynamoClusterBusSpec extends Specification {
    @Shared DynamoDbClient ddb
    @Shared String table = 'bus-spec-' + System.nanoTime()

    static String endpoint() { System.getenv('KESTREL_DYNAMODB_ENDPOINT') ?: 'http://localhost:8000' }

    static boolean endpointUp() {
        try { def u = new URL(endpoint()); new Socket(u.host, u.port).close(); true } catch (Exception ignored) { false }
    }

    def setupSpec() {
        ddb = DynamoDbClient.builder().endpointOverride(URI.create(endpoint())).region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create('x', 'x')))
            .httpClientBuilder(UrlConnectionHttpClient.builder()).build()
        ddb.createTable { it.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(AttributeDefinition.builder().attributeName('pk').attributeType(ScalarAttributeType.S).build())
            .keySchema(KeySchemaElement.builder().attributeName('pk').keyType(KeyType.HASH).build()) }
    }

    def "a pod sees other pods' events once, in order, and never its own"() {
        given:
        def web0 = new DynamoClusterBus(ddb, table, 'acl', 'web-0', Clock.systemUTC())
        def web1 = new DynamoClusterBus(ddb, table, 'acl', 'web-1', Clock.systemUTC())
        web0.poll(); web1.poll()  // start from now

        when:
        web0.publish([path: 'a.aclpolicy', system: 'true'])
        web0.publish([path: 'b.aclpolicy', project: 'p1', system: 'false'])
        web1.publish([path: 'c.aclpolicy', system: 'true'])

        then:
        web1.poll()*.path == ['a.aclpolicy', 'b.aclpolicy']
        web1.poll().isEmpty()
        web0.poll()*.path == ['c.aclpolicy']
    }

    def "a pod that fell far behind is told to reset"() {
        given:
        def a = new DynamoClusterBus(ddb, table, 'flood', 'a', Clock.systemUTC())
        def b = new DynamoClusterBus(ddb, table, 'flood', 'b', Clock.systemUTC())
        b.poll()
        (DynamoClusterBus.MAX_CATCH_UP + 1).times { a.publish([i: "$it"]) }

        expect:
        b.poll()*.containsKey('_reset') == [true]
    }
}
