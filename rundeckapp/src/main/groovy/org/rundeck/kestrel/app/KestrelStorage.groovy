package org.rundeck.kestrel.app

import groovy.transform.CompileStatic

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
    ].asImmutable()

    /** @return true when migrated classes live in DynamoDB */
    static boolean isDynamo() {
        MODE == 'dynamodb'
    }

    /** @return the {@code mapWith} value for a class Kestrel can store in DynamoDB */
    static String mapWith() {
        isDynamo() ? 'dynamodb' : 'hibernate'
    }
}
