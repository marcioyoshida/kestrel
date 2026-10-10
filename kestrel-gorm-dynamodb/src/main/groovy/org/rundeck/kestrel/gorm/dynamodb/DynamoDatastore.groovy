package org.rundeck.kestrel.gorm.dynamodb

import groovy.transform.CompileStatic
import org.grails.datastore.mapping.config.Settings
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.transactions.DatastoreTransactionManager
import org.grails.datastore.mapping.transactions.SessionHolder
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.grails.datastore.mapping.core.connections.ConnectionSourceSettings
import org.grails.datastore.mapping.core.connections.ConnectionSources
import org.grails.datastore.mapping.core.connections.ConnectionSourcesInitializer
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.keyvalue.mapping.config.KeyValue
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.ToOne
import org.grails.datastore.mapping.simple.SimpleMapDatastore
import org.grails.datastore.mapping.simple.connections.SimpleMapConnectionSourceFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.env.PropertyResolver
import org.grails.datastore.gorm.GormEnhancer
import org.grails.datastore.gorm.events.ConfigurableApplicationEventPublisher
import org.grails.datastore.gorm.events.DefaultApplicationEventPublisher
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * GORM datastore on DynamoDB (ADR 0004). Reuses GORM's simple datastore for the GORM plumbing
 * (static API, validation, transaction manager, connection sources) and replaces its in-memory
 * storage with DynamoDB: one table per root entity, a shared index table, numeric id blocks.
 *
 * <p>Indexed properties (equality queries without a table scan): every to-one association (its
 * foreign key), every property mapped with {@code index: true}, and the names passed in
 * {@code indexed} as {@code ClassSimpleName.property}. {@code ClassSimpleName.property:null}
 * keeps an entry only while the value is null: a small, sparse index for queries such as
 * "running executions" ({@code isNull('dateCompleted')}).
 */
@CompileStatic
class DynamoDatastore extends SimpleMapDatastore {
    final DynamoDbClient client
    final DynamoTables tables
    final IdAllocator ids
    /** `Entity.property` names indexed only while null (`Entity.property:null` in the index list). */
    final Set<String> nullOnlyIndexes = new HashSet<>()
    private final ConfigurableApplicationEventPublisher publisher

    /**
     * @param client      DynamoDB client
     * @param tablePrefix table name prefix
     * @param createTables create missing tables
     * @param indexed     extra indexed properties, `Entity.property`
     * @param config      GORM configuration
     * @param publisher   event publisher
     * @param classes     domain classes mapped to this datastore
     */
    DynamoDatastore(DynamoDbClient client, String tablePrefix, boolean createTables, Collection<String> indexed,
                    PropertyResolver config, ConfigurableApplicationEventPublisher publisher, Class... classes) {
        this(client, tablePrefix, createTables, indexed,
            ConnectionSourcesInitializer.create(new SimpleMapConnectionSourceFactory(), config), publisher, classes)
    }

    private DynamoDatastore(DynamoDbClient client, String tablePrefix, boolean createTables, Collection<String> indexed,
                            ConnectionSources<Map<String, Map>, ConnectionSourceSettings> sources,
                            ConfigurableApplicationEventPublisher publisher, Class... classes) {
        super(sources, mappingContext(sources, classes), publisher)
        this.client = client
        this.tables = new DynamoTables(client, tablePrefix, createTables)
        this.ids = new IdAllocator(tables, 100L)
        this.publisher = publisher
        markIndexed(indexed ?: [])
        leaveSingleDatastoreLookupToOthers()
        // withTransaction takes getCurrentSession(): inside a Hibernate transaction that is the
        // transaction's shared session, so code around withTransaction sees the same instances.
        ((DatastoreTransactionManager) transactionManager).datastoreManagedSession = true
    }

    /**
     * GORM resolves "the" datastore for code that is not bound to an entity (classes using
     * {@code @Transactional} without being Spring beans, such as Rundeck's Quartz ExecutionJob)
     * from a by-type registry, and fails once two datastores are registered ("More than one GORM
     * implementation is configured", seen live). This datastore only serves the entities mapped
     * to it, which GORM resolves per entity, so it leaves the single-datastore lookup to the
     * primary datastore (Hibernate).
     */
    private void leaveSingleDatastoreLookupToOthers() {
        def field = GormEnhancer.getDeclaredField('DATASTORES_BY_TYPE')
        field.accessible = true
        ((Map) field.get(null)).remove(this.getClass())
    }

    /** Convenience for tests: defaults, no extra indexes. */
    DynamoDatastore(DynamoDbClient client, String tablePrefix, Class... classes) {
        this(client, tablePrefix, true, [], DatastoreUtils.createPropertyResolver([(Settings.SETTING_FAIL_ON_ERROR): false]),
            new DefaultApplicationEventPublisher(), classes)
    }

    private static DynamoMappingContext mappingContext(ConnectionSources<Map<String, Map>, ConnectionSourceSettings> sources,
                                                       Class... classes) {
        def ctx = new DynamoMappingContext('kestrel', sources.defaultConnectionSource.settings)
        ctx.addPersistentEntities(classes)
        ctx
    }

    private void markIndexed(Collection<String> indexed) {
        Set<String> plain = indexed.findAll { !it.endsWith(':null') } as Set
        nullOnlyIndexes.addAll(indexed.findAll { it.endsWith(':null') }.collect { it - ':null' })
        for (PersistentEntity entity : mappingContext.persistentEntities) {
            for (PersistentProperty p : entity.persistentProperties) {
                String name = entity.javaClass.simpleName + '.' + p.name
                boolean wanted = p instanceof ToOne || plain.contains(name) || nullOnlyIndexes.contains(name)
                def form = p.mapping?.mappedForm
                if (wanted && form instanceof KeyValue) {
                    ((KeyValue) form).setIndex(true)
                }
            }
        }
    }

    /** Resource key of the session shared by one Spring transaction; private, so GORM's own synchronization never sees it. */
    private final Object sharedSessionKey = new Object()

    /**
     * Like Hibernate's datastore (and unlike GORM's generic one), open a session when none is
     * bound: Rundeck calls GORM from event threads and requests without a session binding.
     * <ul>
     * <li>A session bound by this datastore's own withSession/withTransaction is reused.</li>
     * <li>Inside any other Spring transaction (Rundeck's @Transactional services run Hibernate
     * ones), one session is shared for that transaction, as Hibernate's is, and flushed before it
     * commits: entities changed without save() are written then, as Hibernate writes them. The
     * session is kept under a private key with a synchronization of its own, never through GORM's
     * session synchronization (seen live when this datastore's session was bound to Hibernate
     * transactions: "No value for key [DynamoDatastore] bound to thread" on rollback). Saves
     * still write through, so a rollback of that transaction does not undo them.</li>
     * <li>Otherwise a plain, unbound session: saves write through.</li>
     * </ul>
     */
    /** GORM's static API asks this before {@link #getCurrentSession}; otherwise it opens and closes a session per call. */
    @Override
    boolean hasCurrentSession() {
        TransactionSynchronizationManager.hasResource(this) || TransactionSynchronizationManager.isSynchronizationActive()
    }

    @Override
    Session getCurrentSession() {
        def holder = TransactionSynchronizationManager.getResource(this)
        if (holder instanceof SessionHolder && ((SessionHolder) holder).session != null) {
            return ((SessionHolder) holder).session
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return connect()
        }
        def shared = TransactionSynchronizationManager.getResource(sharedSessionKey)
        if (shared instanceof Session) {
            return (Session) shared
        }
        Session session = connect()
        TransactionSynchronizationManager.bindResource(sharedSessionKey, session)
        TransactionSynchronizationManager.registerSynchronization(new SharedSessionSynchronization(sharedSessionKey, session))
        session
    }

    /** Flushes a transaction's shared session before commit; unbinds it around suspension and at completion. */
    @CompileStatic
    static class SharedSessionSynchronization implements TransactionSynchronization {
        private final Object key
        private final Session session

        SharedSessionSynchronization(Object key, Session session) {
            this.key = key
            this.session = session
        }

        @Override
        void suspend() {
            TransactionSynchronizationManager.unbindResourceIfPossible(key)
        }

        @Override
        void resume() {
            TransactionSynchronizationManager.bindResource(key, session)
        }

        @Override
        void beforeCommit(boolean readOnly) {
            if (!readOnly) {
                session.flush()
            }
        }

        @Override
        void afterCompletion(int status) {
            // not disconnected: lazy proxies loaded through it may still be initialized after the transaction
            TransactionSynchronizationManager.unbindResourceIfPossible(key)
        }
    }

    @Override
    protected Session createSession(PropertyResolver connectionDetails) {
        new DynamoSession(this, mappingContext, publisher)
    }
}
