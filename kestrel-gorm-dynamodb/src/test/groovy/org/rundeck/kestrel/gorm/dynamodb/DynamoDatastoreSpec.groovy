package org.rundeck.kestrel.gorm.dynamodb

import org.grails.datastore.mapping.core.OptimisticLockingException
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import spock.lang.Requires
import spock.lang.Shared
import spock.lang.Specification

/**
 * The GORM API Rundeck relies on, against DynamoDB Local (KESTREL_DYNAMODB_ENDPOINT).
 */
@Requires({ DynamoDatastoreSpec.endpointUp() })
class DynamoDatastoreSpec extends Specification {
    @Shared DynamoDbClient client
    @Shared DynamoDatastore datastore

    static String endpoint() {
        System.getenv('KESTREL_DYNAMODB_ENDPOINT') ?: 'http://localhost:8000'
    }

    static boolean endpointUp() {
        try {
            def u = new URL(endpoint())
            new Socket(u.host, u.port).close()
            true
        } catch (Exception ignored) {
            false
        }
    }

    def setupSpec() {
        client = DynamoDbClient.builder().endpointOverride(URI.create(endpoint())).region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create('x', 'x')))
            .httpClientBuilder(UrlConnectionHttpClient.builder()).build()
        datastore = new DynamoDatastore(client, 'spec' + System.nanoTime(), true, ['TExec.status', 'TExec.project', 'TJob.uuid', 'TJob.project', 'TToken.token'],
            org.grails.datastore.mapping.core.DatastoreUtils.createPropertyResolver([:]),
            new org.grails.datastore.gorm.events.DefaultApplicationEventPublisher(),
            TJob, TOption, TExec, TStep, TCmdStep, TJobStep, TToken)
    }

    def cleanupSpec() {
        datastore?.close()
    }

    def "save, get, numeric ids, dynamic finders"() {
        when:
        def a = TJob.withNewSession { new TJob(uuid: 'a', project: 'p1', jobName: 'nightly', lastRun: new Date(1000)).save(failOnError: true) }
        def b = TJob.withNewSession { new TJob(uuid: 'b', project: 'p1', jobName: 'hourly', groupPath: 'ops/db').save(failOnError: true) }

        then:
        a.id instanceof Long && b.id instanceof Long && a.id != b.id
        TJob.withNewSession { TJob.get(a.id).jobName } == 'nightly'
        TJob.withNewSession { TJob.get(a.id).lastRun } == new Date(1000)
        TJob.withNewSession { TJob.get(a.id).dateCreated } != null  // GORM auto-timestamp
        TJob.withNewSession { TJob.findByUuid('b').groupPath } == 'ops/db'
        DynamoQuery.LAST_PLAN.get() == 'index:uuid'
        TJob.withNewSession { TJob.findAllByProject('p1')*.uuid.sort() } == ['a', 'b']
        TJob.withNewSession { TJob.countByProject('p1') } == 2
        TJob.withNewSession { TJob.findByJobName('hourly').uuid } == 'b'
        DynamoQuery.LAST_PLAN.get() == 'scan'
        TJob.withNewSession { TJob.get(a.id).state } == TStatus.ACTIVE
    }

    def "updates are partial, nulls remove attributes, and versions guard concurrent edits"() {
        given:
        def id = TJob.withNewSession { new TJob(uuid: 'v', project: 'p2', jobName: 'x', groupPath: 'g').save(failOnError: true).id }

        when: 'a normal update'
        TJob.withNewSession { def j = TJob.get(id); j.jobName = 'y'; j.groupPath = null; j.save(failOnError: true) }
        def reloaded = TJob.withNewSession { TJob.get(id) }

        then:
        reloaded.jobName == 'y'
        reloaded.groupPath == null
        reloaded.version == 1

        when: 'two sessions edit the same version'
        def s1 = TJob.withNewSession { TJob.get(id) }
        TJob.withNewSession { def j = TJob.get(id); j.jobName = 'first'; j.save(failOnError: true) }
        TJob.withNewSession { s1.attach(); s1.jobName = 'second'; s1.save(failOnError: true) }

        then:
        thrown(OptimisticLockingException)
        TJob.withNewSession { TJob.get(id).jobName } == 'first'
    }

    def "criteria: ranges, ilike, in, disjunction, ordering, paging, projections, group by"() {
        given:
        TExec.withNewSession {
            (1..6).each { i ->
                new TExec(project: i <= 4 ? 'web' : 'ops', status: i % 2 ? 'succeeded' : 'failed', user: "user$i",
                    dateStarted: new Date(i * 1000L), dateCompleted: i == 6 ? null : new Date(i * 1000L + 500)).save(failOnError: true)
            }
        }

        expect:
        TExec.withNewSession {
            TExec.createCriteria().list {
                eq('project', 'web')
                between('dateStarted', new Date(2000), new Date(4000))
                order('dateStarted', 'desc')
            }*.user
        } == ['user4', 'user3', 'user2']
        DynamoQuery.LAST_PLAN.get() == 'index:project'

        TExec.withNewSession { TExec.createCriteria().list { ilike('user', 'USER%'); maxResults(2); firstResult(1); order('user') }*.user } == ['user2', 'user3']
        TExec.withNewSession { TExec.createCriteria().list { 'in'('status', ['failed']); order('user') }*.user } == ['user2', 'user4', 'user6']
        TExec.withNewSession { TExec.createCriteria().list { or { eq('user', 'user1'); isNull('dateCompleted') }; order('user') }*.user } == ['user1', 'user6']
        TExec.withNewSession { TExec.createCriteria().get { projections { count() }; eq('status', 'succeeded') } } == 3
        TExec.withNewSession { TExec.createCriteria().get { projections { max('dateStarted') } } } == new Date(6000)
        TExec.withNewSession {
            TExec.createCriteria().list { projections { groupProperty('project'); count() }; order('project') }
        }.collectEntries { [(it[0]): it[1]] } == [ops: 2, web: 4]
        TExec.withNewSession { TExec.createCriteria().list { projections { distinct('project') } }.sort() } == ['ops', 'web']
        TExec.withNewSession { TExec.where { project == 'ops' && status != 'failed' }.list()*.user } == ['user5']
    }

    def "associations: to-one queries, one-to-many collections, association criteria"() {
        given:
        def jobId = TJob.withNewSession {
            def j = new TJob(uuid: 'assoc', project: 'p3', jobName: 'with-options')
            j.addToOptions(new TOption(name: 'env', required: true))
            j.addToOptions(new TOption(name: 'region'))
            j.save(failOnError: true)
            new TExec(project: 'p3', status: 'running', user: 'u', dateStarted: new Date(), job: j).save(failOnError: true)
            new TExec(project: 'p3', status: 'succeeded', user: 'u', dateStarted: new Date(), job: j).save(failOnError: true)
            j.id
        }

        expect:
        TJob.withNewSession { TJob.get(jobId).options*.name.sort() } == ['env', 'region']
        TExec.withNewSession { TExec.findAllByJob(TJob.get(jobId))*.status.sort() } == ['running', 'succeeded']
        DynamoQuery.LAST_PLAN.get() == 'index:job'
        TExec.withNewSession { TExec.createCriteria().list { job { eq('uuid', 'assoc') }; eq('status', 'running') }.size() } == 1
        TExec.withNewSession { TExec.get(TExec.findByStatus('running').id).job.uuid } == 'assoc'
        TOption.withNewSession { TOption.findByName('env').job.uuid } == 'assoc'
    }

    def "inheritance: one table, subclass queries see only their subclass"() {
        when:
        TStep.withNewSession {
            new TCmdStep(command: 'ls', description: 'list').save(failOnError: true)
            new TJobStep(jobRef: 'other', description: 'call').save(failOnError: true)
        }

        then:
        TStep.withNewSession { TStep.list()*.class*.simpleName.sort() } == ['TCmdStep', 'TJobStep']
        TStep.withNewSession { TCmdStep.list()*.command } == ['ls']
        TStep.withNewSession { TJobStep.findByJobRef('other').description } == 'call'
        TStep.withNewSession { TStep.findByDescription('list') instanceof TCmdStep }
    }

    def "transactions buffer writes; rollback discards them"() {
        when:
        TJob.withTransaction { status ->
            new TJob(uuid: 'tx-rolled-back', project: 'tx', jobName: 'n').save(failOnError: true)
            status.setRollbackOnly()
        }
        TJob.withTransaction {
            new TJob(uuid: 'tx-1', project: 'tx', jobName: 'n').save(failOnError: true)
            new TJob(uuid: 'tx-2', project: 'tx', jobName: 'n').save(failOnError: true)
        }

        then:
        TJob.withNewSession { TJob.findAllByProject('tx')*.uuid.sort() } == ['tx-1', 'tx-2']
    }

    def "bulk delete and update through where-queries; delete removes from indexes"() {
        given:
        TExec.withNewSession {
            3.times { new TExec(project: 'bulk', status: 'queued', user: 'b', dateStarted: new Date()).save(failOnError: true) }
        }

        when:
        def updated = TExec.withNewSession { TExec.where { project == 'bulk' }.updateAll(status: 'done') }
        def done = TExec.withNewSession { TExec.findAllByProjectAndStatus('bulk', 'done').size() }
        def deleted = TExec.withNewSession { TExec.where { project == 'bulk' }.deleteAll() }

        then:
        updated == 3
        done == 3
        deleted == 3
        TExec.withNewSession { TExec.countByProject('bulk') } == 0
    }

    def "classes with mapWith = 'dynamodb' are mapped; an indexed lookup inside or-branches avoids a scan"() {
        given:
        TToken.withNewSession {
            new TToken(token: 'hash-1', mode: 'SECURED', creator: 'a').save(failOnError: true)
            new TToken(token: 'legacy-2', mode: 'LEGACY', creator: 'b').save(failOnError: true)
        }

        when: 'the shape of GormTokenDataProvider.tokenLookup'
        def found = TToken.withNewSession {
            TToken.createCriteria().get {
                or {
                    and { eq('mode', 'SECURED'); eq('token', 'hash-1') }
                    and { eq('mode', 'LEGACY'); eq('token', 'xx') }
                }
            }
        }

        then:
        found.creator == 'a'
        DynamoQuery.LAST_PLAN.get() == 'index:token'
        datastore.mappingContext.getPersistentEntity(TToken.name) != null
    }

    def "GORM calls work without a bound session (event threads, background jobs)"() {
        when:
        def t = Thread.start { new TJob(uuid: 'nosession', project: 'bg', jobName: 'b').save(failOnError: true) }
        t.join()
        def found = null
        Thread.start { found = TJob.findByUuid('nosession')?.project }.join()

        then:
        found == 'bg'
    }

    def "unsupported criteria fail loudly instead of returning wrong rows"() {
        when:
        TExec.withNewSession { TExec.createCriteria().list { sqlRestriction('1=1') } }

        then:
        thrown(Exception)
    }
}
