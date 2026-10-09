package org.rundeck.kestrel.gorm.dynamodb

import groovy.transform.CompileStatic
import org.grails.datastore.mapping.core.AbstractSession
import org.grails.datastore.mapping.engine.Persister
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.transactions.Transaction
import org.springframework.context.ApplicationEventPublisher
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * GORM session on DynamoDB. Outside a datastore transaction every save/delete is written through
 * immediately (Rundeck code often saves without flush on background threads, where no
 * session-close flush would happen); inside one, writes are buffered and committed on flush.
 */
@CompileStatic
class DynamoSession extends AbstractSession<DynamoDbClient> {
    final DynamoDatastore dynamoDatastore
    private WriteBatch batch
    private DynamoTransaction tx

    /**
     * @param datastore      datastore
     * @param mappingContext mapping context
     * @param publisher      event publisher
     */
    DynamoSession(DynamoDatastore datastore, MappingContext mappingContext, ApplicationEventPublisher publisher) {
        super(datastore, mappingContext, publisher)
        this.dynamoDatastore = datastore
    }

    @Override
    protected Persister createPersister(Class cls, MappingContext mappingContext) {
        PersistentEntity entity = mappingContext.getPersistentEntity(cls.name)
        entity == null ? null : new DynamoEntityPersister(mappingContext, entity, this, dynamoDatastore, publisher)
    }

    @Override
    DynamoDbClient getNativeInterface() {
        dynamoDatastore.client
    }

    @Override
    boolean isPendingAlready(Object obj) {
        false
    }

    /** @return true while a datastore transaction is open on this session */
    boolean inTransaction() {
        tx != null && tx.isActive()
    }

    /**
     * The batch writes join: the running flush's, or a one-off batch the caller executes.
     *
     * @param work receives the batch
     */
    void withBatch(Closure work) {
        if (batch != null) {
            work.call(batch)
        } else {
            def single = new WriteBatch(dynamoDatastore.client)
            work.call(single)
            single.execute()
        }
    }

    @Override
    void flush() {
        if (batch != null) {
            super.flush()  // nested flush joins the outer batch
            return
        }
        batch = new WriteBatch(dynamoDatastore.client)
        try {
            super.flush()
            batch.execute()
        } finally {
            batch = null
        }
    }

    @Override
    Serializable persist(Object o) {
        Serializable key = super.persist(o)
        if (!inTransaction()) {
            flush()
        }
        key
    }

    @Override
    List<Serializable> persist(Iterable objects) {
        List<Serializable> keys = super.persist(objects)
        if (!inTransaction()) {
            flush()
        }
        keys
    }

    @Override
    Serializable insert(Object o) {
        Serializable key = super.insert(o)
        if (!inTransaction()) {
            flush()
        }
        key
    }

    @Override
    void delete(Object obj) {
        super.delete(obj)
        if (!inTransaction()) {
            flush()
        }
    }

    @Override
    void delete(Iterable objects) {
        super.delete(objects)
        if (!inTransaction()) {
            flush()
        }
    }

    @Override
    protected Transaction beginTransactionInternal() {
        tx = new DynamoTransaction()
        tx
    }

    /** Buffers writes until the transaction manager flushes the session before commit. */
    static class DynamoTransaction implements Transaction<Object> {
        private boolean active = true

        @Override
        void commit() {
            active = false
        }

        @Override
        void rollback() {
            active = false  // the transaction manager clears the session's pending writes
        }

        @Override
        Object getNativeTransaction() {
            this
        }

        @Override
        boolean isActive() {
            active
        }

        @Override
        void setTimeout(int timeout) {
        }
    }
}
