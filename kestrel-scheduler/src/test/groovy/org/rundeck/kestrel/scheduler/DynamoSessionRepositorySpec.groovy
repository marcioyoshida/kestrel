package org.rundeck.kestrel.scheduler

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification

import java.time.Duration
import java.time.Instant

@Requires({ DynamoClusterBusSpec.endpointUp() })
class DynamoSessionRepositorySpec extends Specification {
    @Shared DynamoDbClient ddb
    @Shared String table = 'session-spec-' + System.nanoTime()
    TestClock clock = new TestClock(Instant.parse('2027-01-01T00:00:00Z'))

    def setupSpec() {
        ddb = DynamoDbClient.builder().endpointOverride(URI.create(DynamoClusterBusSpec.endpoint())).region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create('x', 'x')))
            .httpClientBuilder(UrlConnectionHttpClient.builder()).build()
        ddb.createTable { it.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(AttributeDefinition.builder().attributeName('pk').attributeType(ScalarAttributeType.S).build())
            .keySchema(KeySchemaElement.builder().attributeName('pk').keyType(KeyType.HASH).build()) }
    }

    DynamoSessionRepository repo() {
        new DynamoSessionRepository(ddb, table, Duration.ofMinutes(60), Duration.ofSeconds(60), clock)
    }

    def "a session saved by one pod is found, with its attributes, by another"() {
        given:
        def web0 = repo()
        def web1 = repo()
        def s = web0.createSession()
        s.setAttribute('user', 'admin')
        s.setAttribute('roles', ['admin', 'user'] as ArrayList)
        web0.save(s)

        when:
        def found = web1.findById(s.id)

        then:
        found.getAttribute('user') == 'admin'
        found.getAttribute('roles') == ['admin', 'user']
    }

    def "an unchanged session is not rewritten on every request, but its expiry still moves"() {
        given:
        def r = repo()
        def s = r.createSession()
        s.setAttribute('k', 'v')
        r.save(s)
        def accessed = { ddb.getItem { it.tableName(table).key([pk: AttributeValue.fromS('session#' + s.id)]) }.item().accessed.n() }
        def first = accessed()

        when: 'a request 10 s later changes nothing'
        clock.now = clock.now.plusSeconds(10)
        s.lastAccessedTime = clock.now
        r.save(s)

        then:
        accessed() == first

        when: 'two minutes later'
        clock.now = clock.now.plusSeconds(120)
        s.lastAccessedTime = clock.now
        r.save(s)

        then:
        accessed() != first
    }

    def "login changes the id: the old id is gone, the new one carries the attributes"() {
        given:
        def r = repo()
        def s = r.createSession()
        s.setAttribute('csrf', 'token-1')
        r.save(s)
        String old = s.id

        when:
        def loaded = r.findById(old)
        loaded.changeSessionId()
        r.save(loaded)

        then:
        r.findById(old) == null
        repo().findById(loaded.id).getAttribute('csrf') == 'token-1'
    }

    def "a non-serializable attribute is skipped, not fatal; expired sessions are not returned"() {
        given:
        def r = repo()
        def s = r.createSession()
        s.setAttribute('ok', 'yes')
        s.setAttribute('thread', new Object())

        when:
        r.save(s)
        def found = repo().findById(s.id)

        then:
        found.getAttribute('ok') == 'yes'
        found.getAttribute('thread') == null

        when:
        clock.now = clock.now.plus(Duration.ofMinutes(61))

        then:
        repo().findById(s.id) == null
    }
}
