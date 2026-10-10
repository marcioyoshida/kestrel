package org.rundeck.kestrel.gorm.dynamodb

import groovy.transform.CompileStatic
import org.grails.datastore.mapping.collection.PersistentCollection
import org.grails.datastore.mapping.core.AbstractSession
import org.grails.datastore.mapping.core.impl.PendingInsert
import org.grails.datastore.mapping.core.impl.PendingUpdate
import org.grails.datastore.mapping.engine.EntityAccess
import org.grails.datastore.mapping.engine.Persister
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.Association
import org.grails.datastore.mapping.model.types.Embedded
import org.grails.datastore.mapping.model.types.ToOne
import org.grails.datastore.mapping.proxy.ProxyHandler
import org.grails.datastore.mapping.transactions.Transaction
import org.springframework.context.ApplicationEventPublisher
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * GORM session on DynamoDB. Outside a datastore transaction every save/delete is written through
 * immediately (Rundeck code often saves without flush on background threads, where no
 * session-close flush would happen); inside one, writes are buffered and committed on flush.
 *
 * <p>Like Hibernate, a flush also writes entities this session loaded and the caller changed
 * without calling save() (Rundeck relies on it, e.g. job delete detaches its executions by
 * setting {@code exec.scheduledExecution = null}). GORM's key-value engine does not: it writes
 * only what was saved. Each loaded or written entity's property state is kept and compared at
 * flush; lazy associations are compared by id or by their collection's dirty flag, so the
 * comparison never loads anything.
 */
@CompileStatic
class DynamoSession extends AbstractSession<DynamoDbClient> {
    final DynamoDatastore dynamoDatastore
    private WriteBatch batch
    private DynamoTransaction tx
    private final Map<Object, Map<String, Object>> snapshots = new IdentityHashMap<>()
    /** `root entity#id` of entities deleted through this session (by key: a cascade may delete through a proxy). */
    private final Set<String> deleted = new HashSet<>()
    private static final String CLEAN = 'clean'
    private static final String DIRTY = 'dirty'

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
            persistChanged()
            super.flush()
            batch.execute()
        } finally {
            batch = null
        }
        snapshotCached()
    }

    /** Queues an update for every cached entity whose state differs from its last loaded or written state. */
    private void persistChanged() {
        Set<String> queued = new HashSet<>()
        for (Collection<PendingInsert> ops : pendingInserts.values()) {
            for (PendingInsert op : ops) queued.add(op.entity.rootEntity.name + '#' + op.nativeKey)
        }
        for (Collection<PendingUpdate> ops : pendingUpdates.values()) {
            for (PendingUpdate op : ops) queued.add(op.entity.rootEntity.name + '#' + op.nativeKey)
        }
        List<Object> changed = []
        for (Map<Serializable, Object> cached : firstLevelCache.values()) {
            for (Object o : cached.values()) {
                Map<String, Object> before = snapshots.get(o)
                String key = keyOf(o)
                if (before != null && !deleted.contains(key) && !queued.contains(key) && before != stateOf(o)) {
                    changed.add(o)
                }
            }
        }
        for (Object o : changed) {
            super.persist(o)
        }
    }

    private String keyOf(Object o) {
        if (o == null) return null
        ProxyHandler proxies = mappingContext.proxyHandler
        if (proxies.isProxy(o)) {
            PersistentEntity pe = mappingContext.getPersistentEntity(proxies.getProxiedClass(o).name)
            return pe == null ? null : pe.rootEntity.name + '#' + proxies.getIdentifier(o)
        }
        PersistentEntity e = mappingContext.getPersistentEntity(o.getClass().name)
        e == null ? null : e.rootEntity.name + '#' + createEntityAccess(e, o).identifier
    }

    /** After a successful flush every cached entity matches the store. */
    private void snapshotCached() {
        snapshots.clear()
        for (Map<Serializable, Object> cached : firstLevelCache.values()) {
            for (Object o : cached.values()) {
                if (deleted.contains(keyOf(o))) continue
                resetCollections(o)
                snapshot(o)
            }
        }
    }

    /**
     * Records an entity's current state as stored (called on load, refresh and after flush).
     *
     * @param o entity
     */
    void snapshot(Object o) {
        Map<String, Object> state = stateOf(o)
        if (state != null) {
            snapshots.put(o, state)
        }
    }

    /**
     * Stops tracking a deleted entity, so a later flush cannot write it back.
     *
     * @param o entity
     */
    void forget(Object o) {
        snapshots.remove(o)
        String key = keyOf(o)
        if (key != null) deleted.add(key)
    }

    /**
     * @return true if this session deleted that entity (its updates are dropped, not written back)
     */
    boolean isDeleted(PersistentEntity e, Object id) {
        id != null && deleted.contains(e.rootEntity.name + '#' + id)
    }

    /**
     * @param o entity
     * @return property name to a comparable value, or null for objects that are not entities here
     */
    Map<String, Object> stateOf(Object o) {
        PersistentEntity e = o == null ? null : mappingContext.getPersistentEntity(o.getClass().name)
        if (e == null) return null
        EntityAccess access = createEntityAccess(e, o)
        String version = e.version?.name
        Map<String, Object> state = [:]
        for (PersistentProperty p : e.persistentProperties) {
            if (p.name == version) continue
            state[p.name] = comparable(p, access.getProperty(p.name))
        }
        state
    }

    private Object comparable(PersistentProperty p, Object v) {
        if (v == null) return null
        if (v instanceof PersistentCollection) {
            PersistentCollection c = (PersistentCollection) v
            return c.isInitialized() && c.isDirty() ? DIRTY : CLEAN
        }
        if (p instanceof ToOne && !(p instanceof Embedded)) return idOf(v)
        if (p instanceof Association && v instanceof Collection) {
            return ((Collection) v).collect { idOf(it) }  // a plain collection set by the caller
        }
        if (v instanceof Date) return ((Date) v).time
        if (v instanceof Set) return new HashSet((Set) v)
        if (v instanceof Collection) return new ArrayList((Collection) v)
        if (v instanceof Map) return new LinkedHashMap((Map) v)
        v
    }

    private Object idOf(Object v) {
        if (v == null) return null
        ProxyHandler proxies = mappingContext.proxyHandler
        if (proxies.isProxy(v)) return proxies.getIdentifier(v)
        PersistentEntity e = mappingContext.getPersistentEntity(v.getClass().name)
        if (e == null) return v
        def id = createEntityAccess(e, v).identifier
        id != null ? id : v
    }

    private void resetCollections(Object o) {
        PersistentEntity e = mappingContext.getPersistentEntity(o.getClass().name)
        if (e == null) return
        EntityAccess access = createEntityAccess(e, o)
        for (PersistentProperty p : e.persistentProperties) {
            if (!(p instanceof Association)) continue
            def v = access.getProperty(p.name)
            if (v instanceof PersistentCollection && ((PersistentCollection) v).isInitialized()) {
                ((PersistentCollection) v).resetDirty()
            }
        }
    }

    @Override
    void refresh(Object o) {
        super.refresh(o)
        snapshot(o)
    }

    @Override
    void clear() {
        super.clear()
        snapshots.clear()
        deleted.clear()
    }

    @Override
    void clear(Object o) {
        super.clear(o)
        snapshots.remove(o)
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
        forget(obj)
        super.delete(obj)
        if (!inTransaction()) {
            flush()
        }
    }

    @Override
    void delete(Iterable objects) {
        objects.each { forget(it) }
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
