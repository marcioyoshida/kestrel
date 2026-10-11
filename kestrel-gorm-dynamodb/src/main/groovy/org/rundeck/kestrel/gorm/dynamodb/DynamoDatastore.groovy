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
import org.grails.datastore.mapping.keyvalue.mapping.config.Family
import org.grails.datastore.mapping.keyvalue.mapping.config.KeyValue
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.ToOne
import org.grails.datastore.mapping.simple.SimpleMapDatastore
import org.grails.datastore.mapping.simple.connections.SimpleMapConnectionSourceFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.env.PropertyResolver
import org.grails.datastore.gorm.GormEnhancer
import org.grails.datastore.gorm.validation.constraints.MappingContextAwareConstraintFactory
import org.grails.datastore.gorm.validation.constraints.builtin.UniqueConstraint
import org.grails.datastore.gorm.validation.constraints.registry.DefaultValidatorRegistry
import org.grails.datastore.gorm.validation.registry.support.ValidatorRegistries
import org.grails.datastore.mapping.validation.ValidatorRegistry
import org.grails.datastore.gorm.events.ConfigurableApplicationEventPublisher
import org.grails.datastore.gorm.events.DefaultApplicationEventPublisher
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.Put
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem
import org.slf4j.LoggerFactory

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
    /** Sorted indexes ({@code Entity.partition>sort} in the indexed list), see {@link SortedIndex}. */
    final List<SortedIndex> sortedIndexes = []
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
        sortedIndexes.addAll(sortedIndexesOf(indexed ?: []))
        registerUniqueConstraint()
        if (createTables) {
            tables.ensureAll(mappingContext.persistentEntities.findAll { it.root }.collect { familyOf(it) })
        }
        buildIndexes()
        leaveSingleDatastoreLookupToOthers()
        // withTransaction takes getCurrentSession(): inside a Hibernate transaction that is the
        // transaction's shared session, so code around withTransaction sees the same instances.
        ((DatastoreTransactionManager) transactionManager).datastoreManagedSession = true
    }

    /**
     * Readiness check (the datastore is the app's only database): one GetItem on the id-block
     * table. A data-plane read, so frequent probes stay cheap and within DynamoDB's API limits.
     */
    void ping() {
        client.getItem { it.tableName(tables.idsTable()).key([k: AttributeValue.fromS('--ping')]) }
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

    /**
     * {@code unique: true} (ScheduledExecution.uuid, Project.name, AuthToken.token, Storage.pathSha)
     * is a no-op unless the datastore registers it: Hibernate registers its own, the key-value base
     * does not. Without it a job imported twice with the same uuid was created twice. GORM's
     * generic constraint checks with a query (an index lookup here) at validation, as Hibernate's
     * does before its database index.
     */
    private void registerUniqueConstraint() {
        ValidatorRegistry registry = mappingContext.validatorRegistry
        if (registry == null) {
            registry = ValidatorRegistries.createValidatorRegistry(mappingContext, connectionSources.defaultConnectionSource.settings)
            mappingContext.validatorRegistry = registry
        }
        if (registry instanceof DefaultValidatorRegistry) {
            DefaultValidatorRegistry r = (DefaultValidatorRegistry) registry
            r.addConstraintFactory(new MappingContextAwareConstraintFactory(UniqueConstraint, r.messageSource, mappingContext))
        }
    }

    /** Parses the sorted-index specs; a spec naming a mapped entity but a missing property fails startup. */
    private List<SortedIndex> sortedIndexesOf(Collection<String> indexed) {
        List<SortedIndex> out = []
        for (String spec : indexed) {
            SortedIndex s = SortedIndex.parse(spec)
            if (s == null) continue
            PersistentEntity e = mappingContext.persistentEntities.find { PersistentEntity pe -> pe.javaClass.simpleName == s.entityName }
            if (e == null) continue
            if (e.getPropertyByName(s.partition) == null || e.getPropertyByName(s.sort) == null) {
                throw new IllegalArgumentException("Sorted index ${spec}: ${s.entityName} has no property ${s.partition} or ${s.sort}")
            }
            out << s
        }
        out
    }

    /**
     * Builds each index once for the rows that existed before it: plain indexes (including
     * to-one foreign keys and null-only ones) and sorted indexes. Per table, one scan covers every
     * index without a "built" marker; the markers then make later starts skip it. Idempotent, so
     * pods starting together may both build; rows written meanwhile index themselves.
     */
    private void buildIndexes() {
        String indexTable = tables.indexTable()
        for (PersistentEntity root : mappingContext.persistentEntities.findAll { PersistentEntity pe -> pe.root }) {
            String family = familyOf(root)
            List<PersistentEntity> hierarchy = mappingContext.persistentEntities.findAll { PersistentEntity pe -> pe.rootEntity == root } as List<PersistentEntity>
            Set<String> plain = new TreeSet<>()
            for (PersistentEntity e : hierarchy) {
                for (PersistentProperty p : e.persistentProperties) {
                    def form = p.mapping?.mappedForm
                    if (form instanceof KeyValue && ((KeyValue) form).isIndex()) plain << p.name
                }
            }
            List<SortedIndex> sorted = sortedIndexes.findAll { SortedIndex si -> hierarchy.any { PersistentEntity e -> si.appliesTo(e) } }
            Map<String, Object> missing = [:]  // marker key -> property name or SortedIndex
            for (String prop : plain) missing["${family}|>built|${prop}".toString()] = prop
            for (SortedIndex si : sorted) missing[si.builtKey(family)] = si
            missing = missing.findAll { String marker, Object what ->
                !client.getItem { it.tableName(indexTable).key(markerKey(marker)).consistentRead(true) }.hasItem()
            }
            if (missing.isEmpty()) continue
            List<Map<String, AttributeValue>> entries = []
            int rows = 0
            client.scanPaginator { it.tableName(tables.entityTable(family)).consistentRead(true) }.items().each { Map<String, AttributeValue> item ->
                rows++
                String id = item.get('id').s()
                String disc = item.get(DynamoEntityPersister.DISCRIMINATOR)?.s()
                PersistentEntity rowEntity = disc ? (mappingContext.getChildEntityByDiscriminator(root, disc) ?: root) : root
                for (Object what : missing.values()) {
                    if (what instanceof SortedIndex) {
                        SortedIndex si = (SortedIndex) what
                        AttributeValue pv = item.get(si.partition)
                        if (!si.appliesTo(rowEntity) || absent(pv)) continue
                        AttributeValue sv = item.get(si.sort)
                        Object sort = absent(sv) ? null : sv.n() != null ? (Object) new BigDecimal(sv.n()) : (Object) sv.s()
                        entries << [k : AttributeValue.fromS(si.partitionKey(family, ValueCodec.decode(pv, rowEntity.getPropertyByName(si.partition)?.type))),
                                    id: AttributeValue.fromS(SortedIndex.entryId(sort, id))]
                    } else {
                        String prop = (String) what
                        PersistentProperty p = rowEntity.getPropertyByName(prop)
                        if (p == null) continue
                        AttributeValue v = item.get(prop)
                        boolean nullOnly = nullOnlyIndexes.contains(p.owner.javaClass.simpleName + '.' + p.name)
                        String value = absent(v) ? null : (v.s() ?: v.n() ?: v.bool()?.toString())
                        if (nullOnly ? value != null : value == null) continue
                        entries << [k : AttributeValue.fromS("${family}|${prop}|${value == null ? DynamoEntityPersister.IndexTableValueIndexer.NULL_KEY : value}".toString()),
                                    id: AttributeValue.fromS(id)]
                    }
                }
            }
            entries.addAll(missing.keySet().collect { String marker -> markerKey(marker) })
            // Transactions of puts, as the datastore's own writes: the pod role allows PutItem, not
            // BatchWriteItem (seen on EKS; DynamoDB Local has no IAM).
            for (List<Map<String, AttributeValue>> chunk : entries.collate(100)) {
                client.transactWriteItems { b ->
                    b.transactItems(chunk.collect { Map<String, AttributeValue> it ->
                        TransactWriteItem.builder().put(Put.builder().tableName(indexTable).item(it).build()).build() })
                }
            }
            LoggerFactory.getLogger(DynamoDatastore).info(
                "Kestrel DynamoDB: built ${missing.size()} index(es) of ${family} over ${rows} existing rows: ${missing.values().join(', ')}")
        }
    }

    private static Map<String, AttributeValue> markerKey(String marker) {
        [k: AttributeValue.fromS(marker), id: AttributeValue.fromS('-')]
    }

    private static boolean absent(AttributeValue v) {
        v == null || Boolean.TRUE == v.nul()
    }

    /** The key-value family of a root entity, as the persister names it (mapped family, else class name). */
    private static String familyOf(PersistentEntity root) {
        def form = root.mapping?.mappedForm
        form instanceof Family && ((Family) form).family ? ((Family) form).family : root.javaClass.name
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
        Set<String> plain = indexed.findAll { !it.endsWith(':null') && !it.contains('>') } as Set
        nullOnlyIndexes.addAll(indexed.findAll { it.endsWith(':null') }.collect { it - ':null' })
        // A misnamed property would silently leave its queries scanning (seen: BaseReport.ctxProject).
        for (String name : plain + nullOnlyIndexes) {
            int dot = name.indexOf('.')
            PersistentEntity e = dot < 0 ? null : mappingContext.persistentEntities.find { PersistentEntity pe -> pe.javaClass.simpleName == name.substring(0, dot) }
            if (e != null && e.getPropertyByName(name.substring(dot + 1)) == null) {
                throw new IllegalArgumentException("Index ${name}: ${e.javaClass.simpleName} has no property ${name.substring(dot + 1)}")
            }
        }
        for (PersistentEntity entity : mappingContext.persistentEntities) {
            for (PersistentProperty p : entity.persistentProperties) {
                // An entry names a class; its subclasses' rows are indexed too (BaseReport.project
                // must reach ExecReport, the class reports are saved and queried as).
                boolean wanted = p instanceof ToOne
                for (PersistentEntity e = entity; e != null && !wanted; e = e.parentEntity) {
                    String name = e.javaClass.simpleName + '.' + p.name
                    wanted = plain.contains(name) || nullOnlyIndexes.contains(name)
                }
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
