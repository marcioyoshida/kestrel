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
        datastore = new DynamoDatastore(client, 'spec' + System.nanoTime(), true, ['TExec.status', 'TExec.project', 'TJob.uuid', 'TJob.project', 'TToken.token', 'TRef.jobUuid', 'TExec.dateCompleted:null'],
            org.grails.datastore.mapping.core.DatastoreUtils.createPropertyResolver([:]),
            new org.grails.datastore.gorm.events.DefaultApplicationEventPublisher(),
            TJob, TOption, TExec, TStep, TCmdStep, TJobStep, TToken, TWorkflow, TRef, TOwner, TFlow)
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

    def "GORM calls inside another transaction manager's transaction do not join it"() {
        given: 'a foreign (Hibernate-like) transaction with synchronization active'
        def tt = foreignTx(false)

        when: 'saved inside it, then the foreign transaction rolls back'
        tt.execute { status ->
            new TJob(uuid: 'foreign-tx', project: 'ftx', jobName: 'n').save(failOnError: true)
            status.setRollbackOnly()
        }

        then: 'no synchronization error, and the write-through save stands (DynamoDB is not part of that transaction)'
        notThrown(Exception)
        TJob.withNewSession { TJob.findByUuid('foreign-tx')?.project } == 'ftx'
    }

    /** A transaction of another (Hibernate-like) manager, with Spring synchronization active. */
    static org.springframework.transaction.support.TransactionTemplate foreignTx(boolean readOnly) {
        def tm = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { new Object() }
            protected void doBegin(Object t, org.springframework.transaction.TransactionDefinition d) {}
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) {}
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) {}
        }
        def tt = new org.springframework.transaction.support.TransactionTemplate(tm)
        tt.readOnly = readOnly
        tt
    }

    def "a loaded entity changed without save() is written at commit, as Hibernate does; unchanged ones are not rewritten"() {
        given:
        def ids = TJob.withNewSession {
            def j = new TJob(uuid: 'dirty-j', project: 'dirty', jobName: 'n').save(failOnError: true)
            def detach = new TExec(project: 'dirty', status: 'succeeded', user: 'u', dateStarted: new Date(), job: j).save(failOnError: true)
            def keep = new TExec(project: 'dirty', status: 'succeeded', user: 'u', dateStarted: new Date(), job: j).save(failOnError: true)
            [j.id, detach.id, keep.id]
        }
        def versionOf = { id -> TExec.withNewSession { TExec.get(id).version } }
        def keptVersion = versionOf(ids[2])

        when: 'the shape of ScheduledExecutionService.deleteScheduledExecution: unlink, no save()'
        TExec.withTransaction {
            TExec.findAllByJob(TJob.get(ids[0])).each { if (it.id == ids[1]) it.job = null }
        }

        then:
        TExec.withNewSession { TExec.get(ids[1]).job } == null
        TExec.withNewSession { TExec.get(ids[2]).job?.id } == ids[0]
        versionOf(ids[2]) == keptVersion
    }

    def "inside another manager's transaction, GORM calls share one session flushed before commit (not when read-only or rolled back)"() {
        given:
        def id = TJob.withNewSession { new TJob(uuid: 'shared-s', project: 'shared', jobName: 'a').save(failOnError: true).id }
        boolean same = false

        when:
        foreignTx(false).execute { same = TJob.get(id).is(TJob.findByUuid('shared-s')); TJob.get(id).jobName = 'b' }
        foreignTx(true).execute { TJob.get(id).jobName = 'read-only' }
        foreignTx(false).execute { status -> TJob.get(id).jobName = 'rolled-back'; status.setRollbackOnly() }

        then:
        same
        TJob.withNewSession { TJob.get(id).jobName } == 'b'
        TJob.withNewSession { TJob.get(id).version } == 1
    }

    def "a deleted entity is not written back by a later flush"() {
        given:
        def id = TJob.withNewSession { new TJob(uuid: 'ghost', project: 'ghost', jobName: 'a').save(failOnError: true).id }

        when:
        TJob.withTransaction { def j = TJob.get(id); j.delete(); j.jobName = 'ghost-edit' }
        foreignTx(false).execute { def j = TJob.findByUuid('ghost'); j?.delete(); if (j) j.jobName = 'again' }

        then:
        TJob.withNewSession { TJob.get(id) } == null
        TJob.withNewSession { TJob.countByProject('ghost') } == 0
    }

    def "deleting a job with options (hasMany + belongsTo) the way deleteScheduledExecution does: #how"() {
        given:
        def id = TJob.withNewSession {
            def j = new TJob(uuid: 'del-' + how, project: 'del', jobName: 'n')
            j.addToOptions(new TOption(name: 'a'))
            j.addToOptions(new TOption(name: 'b'))
            j.save(failOnError: true, flush: true).id
        }
        // jobs whose ids equal this job's option ids: a cascade on the wrong table would delete them
        def optionIds = TJob.withNewSession { TJob.get(id).options*.id }
        def sameIdJobs = TJob.withNewSession {
            optionIds.each { oid -> while (TJob.list()*.id.max() < oid) new TJob(uuid: 'pad', project: 'pad', jobName: 'p').save(failOnError: true) }
            optionIds.findAll { TJob.get(it) != null && it != id }
        }

        when:
        def work = {
            def j = TJob.get(id)
            TJob.withTransaction { j.delete(flush: true) }
        }
        if (how == 'in a foreign transaction') foreignTx(false).execute { work() } else work()

        then:
        TJob.withNewSession { TJob.get(id) } == null
        TJob.withNewSession { TOption.findAllByName('a').findAll { it.job?.id == id } } == []
        !sameIdJobs.isEmpty()
        sameIdJobs.every { jid -> TJob.withNewSession { TJob.get(jid) } != null }

        where:
        how << ['in a foreign transaction', 'without one']
    }

    def "the full deleteScheduledExecution shape inside a service transaction: executions detached, job and options deleted, no conflict"() {
        given:
        def ids = TJob.withNewSession {
            def j = new TJob(uuid: 'full-del', project: 'fdel', jobName: 'n')
            j.addToOptions(new TOption(name: 'x'))
            j.save(failOnError: true, flush: true)
            def e = new TExec(project: 'fdel', status: 'succeeded', user: 'u', dateStarted: new Date(), dateCompleted: new Date(), job: j).save(failOnError: true)
            [j.id, e.id]
        }

        when:
        foreignTx(false).execute {
            def se = TJob.get(ids[0])
            TExec.withTransaction {
                def running = TExec.createCriteria().list { delegate.'job' { eq('id', se.id) }; isNull('dateCompleted') }
                assert !running
                TExec.findAllByJob(se).each { it.job = null }
                se.delete(flush: true)
            }
        }

        then:
        notThrown(Exception)
        TJob.withNewSession { TJob.get(ids[0]) } == null
        TJob.withNewSession { TExec.get(ids[1]).job } == null
    }

    def "deleting an owner cascades to its lazily loaded child and the child's steps, without writing the child back"() {
        given:
        def ids = TOwner.withNewSession {
            def f = new TFlow()
            f.addToCommands(new TCmdStep(command: 'one'))
            f.addToCommands(new TCmdStep(command: 'two'))
            def o = new TOwner(name: 'owner', flow: f).save(failOnError: true, flush: true)
            [o.id, f.id, f.commands*.id]
        }

        when:
        foreignTx(false).execute {
            def o = TOwner.get(ids[0])
            TOwner.withTransaction { o.delete(flush: true) }
        }

        then:
        notThrown(Exception)
        TOwner.withNewSession { TOwner.get(ids[0]) } == null
        TOwner.withNewSession { TFlow.get(ids[1]) } == null
        TOwner.withNewSession { ids[2].collect { TStep.get(it) }.findAll() } == []
    }

    def "the datastore does not claim GORM's single-datastore lookup (kept for the primary datastore)"() {
        when:
        org.grails.datastore.gorm.GormEnhancer.findSingleDatastore()

        then: 'only this datastore exists in the spec, and it is not offered as the single one'
        def e = thrown(IllegalStateException)
        e.message.contains('No GORM implementations')
        TJob.withNewSession { TJob.count() } >= 0  // entity-bound lookups still work
    }

    def "an ordered list association keeps its order through saves, reorders and removals"() {
        given:
        def id = TWorkflow.withNewSession {
            def w = new TWorkflow()
            ['one', 'two', 'three'].each { w.addToCommands(new TCmdStep(command: it)) }
            w.save(failOnError: true).id
        }

        expect:
        TWorkflow.withNewSession { TWorkflow.get(id).commands*.command } == ['one', 'two', 'three']

        when: 'reorder and remove'
        TWorkflow.withNewSession {
            def w = TWorkflow.get(id)
            def two = w.commands[1]
            w.commands.remove(two)
            w.commands.add(0, two)
            w.removeFromCommands(w.commands.find { it.command == 'three' })
            w.save(failOnError: true)
        }

        then:
        TWorkflow.withNewSession { TWorkflow.get(id).commands*.command } == ['two', 'one']
    }

    def "correlated EXISTS (ExecutionQuery's referenced-execution filter)"() {
        given:
        def ids = TExec.withNewSession {
            def a = new TExec(project: 'corr-a', status: 'succeeded', user: 'u', dateStarted: new Date()).save(failOnError: true)
            def b = new TExec(project: 'corr-b', status: 'succeeded', user: 'u', dateStarted: new Date()).save(failOnError: true)
            def c = new TExec(project: 'corr-a', status: 'succeeded', user: 'u', dateStarted: new Date()).save(failOnError: true)
            new TRef(jobUuid: 'J1', execution: a).save(failOnError: true)
            new TRef(jobUuid: 'J1', execution: b).save(failOnError: true)
            new TRef(jobUuid: 'J2', execution: c).save(failOnError: true)
            [a.id, b.id, c.id]
        }

        when: 'executions referenced by job J1, in project corr-a or corr-b'
        def found = TExec.withNewSession {
            TExec.createCriteria().list {
                'in'('project', ['corr-a', 'corr-b'])
                exists(new grails.gorm.DetachedCriteria(TRef, 're').build {
                    eq('re.jobUuid', 'J1')
                    eqProperty('re.execution.id', 'this.id')
                    'in'('this.project', ['corr-a', 'corr-b'])
                })
            }*.id.sort()
        }
        def none = TExec.withNewSession {
            TExec.createCriteria().list {
                eq('project', 'corr-a')
                not { exists(new grails.gorm.DetachedCriteria(TRef, 're').build { eqProperty('re.execution.id', 'this.id') }) }
            }*.id
        }

        then:
        found == [ids[0], ids[1]].sort()
        none == []
    }

    def "a null-only index finds running executions without a scan, and forgets them when they finish"() {
        given:
        def runningId = TExec.withNewSession {
            new TExec(project: 'nullidx', status: 'succeeded', user: 'u', dateStarted: new Date(), dateCompleted: new Date()).save(failOnError: true)
            new TExec(project: 'nullidx', status: 'running', user: 'u', dateStarted: new Date()).save(failOnError: true).id
        }

        when:
        def running = TExec.withNewSession { TExec.createCriteria().list { isNull('dateCompleted'); eq('user', 'u') }*.id }

        then:
        runningId in running
        DynamoQuery.LAST_PLAN.get() == 'null-index:dateCompleted'

        when: 'it finishes'
        TExec.withNewSession { def e = TExec.get(runningId); e.dateCompleted = new Date(); e.status = 'succeeded'; e.save(failOnError: true) }

        then:
        !(runningId in TExec.withNewSession { TExec.createCriteria().list { isNull('dateCompleted') }*.id })
    }

    def "unsupported criteria fail loudly instead of returning wrong rows"() {
        when:
        TExec.withNewSession { TExec.createCriteria().list { sqlRestriction('1=1') } }

        then:
        thrown(Exception)
    }
}
