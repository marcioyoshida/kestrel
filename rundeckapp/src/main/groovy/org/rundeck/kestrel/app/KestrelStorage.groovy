package org.rundeck.kestrel.app

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.grails.datastore.gorm.GormEnhancer
import org.rundeck.kestrel.gorm.dynamodb.DynamoDatastore

/**
 * Where domain classes are persisted (ADR 0004). {@code KESTREL_STORAGE=dynamodb} maps the classes
 * migrated so far to the DynamoDB GORM datastore; anything else keeps them on the RDBMS. Read
 * from the environment at class-initialization time because GORM reads {@code mapWith} then.
 * (grails-webhooks' Webhook applies the same rule inline; it cannot see this class.)
 */
@CompileStatic
class KestrelStorage {
    /** Storage mode: {@code dynamodb} or {@code rdbms}. */
    static final String MODE = (System.getenv('KESTREL_STORAGE') ?: 'rdbms').trim().toLowerCase()

    /**
     * Equality-queried properties to index in DynamoDB (to-one associations are always indexed).
     * Chosen from the finders and criteria of the data providers of each migrated class.
     */
    static final List<String> INDEXED = [
        'Project.name',
        'User.login',
        'AuthToken.token', 'AuthToken.uuid', 'AuthToken.creator',
        'Webhook.uuid', 'Webhook.project', 'Webhook.authToken', 'Webhook.name',
        'PluginMeta.key', 'PluginMeta.project',
        'StoredEvent.projectName', 'StoredEvent.topic',
        'Storage.dir', 'Storage.name', 'Storage.pathSha',
        // M2c: jobs, executions, references, files, stats, reports
        'ScheduledExecution.uuid', 'ScheduledExecution.project', 'ScheduledExecution.scheduled',
        'Execution.uuid', 'Execution.project', 'Execution.jobUuid', 'Execution.status',
        'Execution.dateCompleted:null',  // sparse: running executions only
        'ReferencedExecution.jobUuid',
        'ScheduledExecutionStats.jobUuid',
        'JobFileRecord.jobId', 'JobFileRecord.uuid', 'JobFileRecord.project',
        'ExecReport.executionId', 'ExecReport.executionUuid', 'ExecReport.jobId',
        'BaseReport.ctxProject',
    ].asImmutable()

    /** @return true when migrated classes live in DynamoDB */
    static boolean isDynamo() {
        MODE == 'dynamodb'
    }

    /** @return the {@code mapWith} value for a class Kestrel can store in DynamoDB */
    static String mapWith() {
        isDynamo() ? 'dynamodb' : 'hibernate'
    }

    /**
     * In DynamoDB mode the RDBMS is a pod-local, in-memory placeholder (M2d), so a domain class
     * left on Hibernate would keep its rows in one pod's memory. Bootstrap fails on any of these.
     * @return names of the domain classes whose GORM datastore is not DynamoDB
     */
    @CompileDynamic
    static List<String> notOnDynamo(Collection<Class> domainClasses) {
        domainClasses.findAll { Class c ->
            try {
                !(GormEnhancer.findDatastore(c) instanceof DynamoDatastore)
            } catch (IllegalStateException ignored) {
                true
            }
        }*.name.sort()
    }
}
