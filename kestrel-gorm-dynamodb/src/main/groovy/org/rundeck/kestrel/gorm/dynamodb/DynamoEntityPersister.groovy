package org.rundeck.kestrel.gorm.dynamodb

import groovy.util.logging.Slf4j
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.engine.AssociationIndexer
import org.grails.datastore.mapping.engine.EntityAccess
import org.grails.datastore.mapping.engine.EntityPersister
import org.grails.datastore.mapping.engine.PropertyValueIndexer
import org.grails.datastore.mapping.keyvalue.engine.AbstractKeyValueEntityPersister
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.Association
import org.grails.datastore.mapping.model.types.ManyToMany
import org.grails.datastore.mapping.model.types.ToOne
import org.grails.datastore.mapping.query.Query
import org.springframework.context.ApplicationEventPublisher
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

/**
 * Stores one entity hierarchy in one DynamoDB table (`id` S key, attributes = GORM native entry,
 * `discriminator` for subclasses). Property and association indexes live in the shared index
 * table: `k = <root>|<property>|<value>` or `<root>#<association>#<owner id>`, `id = entity id`.
 * Every write of a flush joins the session's batch, so an item and its index entries commit
 * together.
 */
@Slf4j
class DynamoEntityPersister extends AbstractKeyValueEntityPersister<Map, Object> {
    static final String DISCRIMINATOR = 'discriminator'
    static final String VERSION = 'version'

    final DynamoDatastore datastore
    final String rootFamily
    final String table

    DynamoEntityPersister(MappingContext context, PersistentEntity entity, Session session,
                          DynamoDatastore datastore, ApplicationEventPublisher publisher) {
        super(context, entity, session, publisher)
        this.datastore = datastore
        def root = entity.rootEntity
        this.rootFamily = getFamily(root, root.mapping)
        this.table = datastore.tables.entityTable(rootFamily)
    }

    DynamoSession getDynamoSession() {
        (DynamoSession) session
    }

    /**
     * GORM's cascades call a persister with an associated entity (deleting a job deletes its
     * options through the job's persister). Tables, indexes and decoding are per entity, so such
     * calls go to that entity's own persister.
     */
    private DynamoEntityPersister ownerOf(PersistentEntity e) {
        e == null || e.rootEntity == persistentEntity.rootEntity ? this : (DynamoEntityPersister) session.getPersister(e.javaClass)
    }

    @Override
    Query createQuery() {
        new DynamoQuery(dynamoSession, persistentEntity, this)
    }

    // ---------------------------------------------------------------- reads

    @Override
    protected Map retrieveEntry(PersistentEntity persistentEntity, String family, Serializable key) {
        def owner = ownerOf(persistentEntity)
        if (!owner.is(this)) return owner.retrieveEntry(persistentEntity, family, key)
        def r = datastore.client.getItem { it.tableName(table).key([id: AttributeValue.fromS(ValueCodec.keyString(key))]).consistentRead(true) }
        r.hasItem() && r.item() ? decodeItem(r.item()) : null
    }

    /** Loads many entries by key (missing keys are skipped), in request order. */
    List<Map> retrieveEntries(Collection keys) {
        List<String> ids = keys.collect { ValueCodec.keyString(it) }.unique()
        Map<String, Map> found = [:]
        for (List<String> chunk : ids.collate(100)) {
            Map<String, software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes> req =
                [(table): software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes.builder()
                    .keys(chunk.collect { [id: AttributeValue.fromS(it)] }).consistentRead(true).build()]
            while (req) {
                def r = datastore.client.batchGetItem { it.requestItems(req) }
                r.responses().get(table)?.each { item -> found[item.id.s()] = decodeItem(item) }
                req = r.unprocessedKeys()?.isEmpty() ? null : r.unprocessedKeys()
            }
        }
        ids.collect { found[it] }.findAll { it != null }
    }

    /** Scans the whole table (paginated); for small tables or queries with no usable index. */
    List<Map> scanAll() {
        List<Map> out = []
        def pages = datastore.client.scanPaginator { it.tableName(table).consistentRead(true) }
        pages.items().each { out << decodeItem(it) }
        out
    }

    /** Decodes an item into a native entry typed by the (possibly subclass) entity's properties. */
    Map decodeItem(Map<String, AttributeValue> item) {
        PersistentEntity target = persistentEntity.rootEntity
        def disc = item.get(DISCRIMINATOR)?.s()
        if (disc) {
            target = mappingContext.getChildEntityByDiscriminator(target, disc) ?: target
        }
        Map entry = new LinkedHashMap()
        item.each { String k, AttributeValue v ->
            entry.put(k, ValueCodec.decode(v, typeOf(target, k)))
        }
        entry
    }

    private Class typeOf(PersistentEntity e, String attr) {
        if (attr == 'id') return e.identity?.type ?: Long
        if (attr == VERSION) return Long
        if (attr == DISCRIMINATOR) return String
        PersistentProperty p = e.getPropertyByName(attr)
        if (p == null) return null
        if (p instanceof ToOne) {
            return ((ToOne) p).associatedEntity?.identity?.type ?: Long
        }
        if (p instanceof Association) return null
        p.type
    }

    // ---------------------------------------------------------------- writes

    @Override
    protected Object generateIdentifier(PersistentEntity persistentEntity, Map entry) {
        Class type = persistentEntity.rootEntity.identity.type
        if (UUID.isAssignableFrom(type)) {
            return UUID.randomUUID()
        }
        long next = datastore.ids.next(rootFamily)
        if (type == String) return Long.toString(next)
        if (type == Integer || type == int) return (int) next
        next
    }

    @Override
    protected Object storeEntry(PersistentEntity persistentEntity, EntityAccess entityAccess, Object storeId, Map entry) {
        def owner = ownerOf(persistentEntity)
        if (!owner.is(this)) return owner.storeEntry(persistentEntity, entityAccess, storeId, entry)
        if (!persistentEntity.root) {
            entry[DISCRIMINATOR] = persistentEntity.discriminator
        }
        if (isVersioned(entityAccess) && entityAccess.getProperty(VERSION) == null) {
            entityAccess.setProperty(VERSION, 0L)
        }
        if (isVersioned(entityAccess)) {
            entry[VERSION] = entityAccess.getProperty(VERSION)
        }
        Map<String, AttributeValue> item = encodeEntry(entry)
        def key = [id: AttributeValue.fromS(ValueCodec.keyString(storeId))]
        dynamoSession.withBatch { WriteBatch b -> b.put(table, key, item, persistentEntity, storeId) }
        // GORM skips null values when indexing an insert; seed the null-only indexes here.
        nullOnlyProperties(persistentEntity).each { PersistentProperty p ->
            if (entry[getPropertyKey(p)] == null) getPropertyIndexer(p).index(null, storeId)
        }
        storeId
    }

    @Override
    protected void updateEntry(PersistentEntity persistentEntity, EntityAccess entityAccess, Object key, Map entry) {
        def owner = ownerOf(persistentEntity)
        if (!owner.is(this)) {
            owner.updateEntry(persistentEntity, entityAccess, key, entry)
            return
        }
        AttributeValue expected = null
        if (!persistentEntity.root) {
            entry[DISCRIMINATOR] = persistentEntity.discriminator
        }
        if (isVersioned(entityAccess)) {
            def current = entityAccess.getProperty(VERSION)
            if (current != null) {
                expected = AttributeValue.fromN(current.toString())
                incrementVersion(entityAccess)
            } else {
                entityAccess.setProperty(VERSION, 0L)
            }
            entry[VERSION] = entityAccess.getProperty(VERSION)
        }
        Map<String, AttributeValue> set = [:]
        Set<String> remove = new LinkedHashSet<>()
        entry.each { k, v ->
            String name = k.toString()
            if (name == 'id') return
            def av = ValueCodec.encode(v)
            if (av == null) remove << name else set[name] = av
        }
        def itemKey = [id: AttributeValue.fromS(ValueCodec.keyString(key))]
        dynamoSession.withBatch { WriteBatch b ->
            b.update(table, itemKey, set, remove, expected != null ? VERSION : null, expected, persistentEntity, key)
        }
    }

    /** Properties of this entity (or its ancestors) configured as `Entity.property:null`. */
    List<PersistentProperty> nullOnlyProperties(PersistentEntity e) {
        if (datastore.nullOnlyIndexes.isEmpty()) return []
        e.persistentProperties.findAll { PersistentProperty p ->
            datastore.nullOnlyIndexes.contains(p.owner.javaClass.simpleName + '.' + p.name)
        }
    }

    @Override
    Object createObjectFromNativeEntry(PersistentEntity persistentEntity, Serializable nativeKey, Map nativeEntry) {
        def obj = super.createObjectFromNativeEntry(persistentEntity, nativeKey, nativeEntry)
        dynamoSession.snapshot(obj)  // the loaded state, for flush-time dirty checking
        obj
    }

    @Override
    protected void deleteEntity(PersistentEntity persistentEntity, Object obj) {
        def owner = ownerOf(persistentEntity)
        if (!owner.is(this)) {
            owner.deleteEntity(persistentEntity, obj)
            return
        }
        dynamoSession.forget(obj)
        super.deleteEntity(persistentEntity, obj)
    }

    @Override
    protected void deleteEntry(String family, Object key, Object entry) {
        if (entry instanceof Map) {
            nullOnlyProperties(persistentEntity).each { PersistentProperty p ->
                if (((Map) entry)[getPropertyKey(p)] == null) getPropertyIndexer(p).deindex(null, key)
            }
        }
        def itemKey = [id: AttributeValue.fromS(ValueCodec.keyString(key))]
        dynamoSession.withBatch { WriteBatch b -> b.delete(table, itemKey, persistentEntity, key) }
    }

    @Override
    protected void deleteEntries(String family, List<Object> keys) {
        keys?.each { deleteEntry(family, it, null) }
    }

    static Map<String, AttributeValue> encodeEntry(Map entry) {
        Map<String, AttributeValue> item = [:]
        entry.each { k, v ->
            def av = ValueCodec.encode(v)
            if (av != null) item.put(k.toString(), av)
        }
        item
    }

    // ---------------------------------------------------------------- native entry access

    @Override
    protected PersistentEntity discriminatePersistentEntity(PersistentEntity persistentEntity, Map nativeEntry) {
        def disc = nativeEntry?.get(DISCRIMINATOR)
        if (disc) {
            def child = mappingContext.getChildEntityByDiscriminator(persistentEntity.rootEntity, disc.toString())
            if (child) return child
        }
        persistentEntity
    }

    @Override
    protected Map createNewEntry(String family) {
        new LinkedHashMap()
    }

    @Override
    protected Object getEntryValue(Map nativeEntry, String property) {
        nativeEntry[property]
    }

    @Override
    protected void setEntryValue(Map nativeEntry, String key, Object value) {
        if (value != null && mappingContext.isPersistentEntity(value)) {
            EntityPersister persister = (EntityPersister) session.getPersister(value)
            value = persister.getObjectIdentifier(value)
        }
        nativeEntry[key] = value
    }

    protected void setEmbedded(Map nativeEntry, String key, Map values) {
        nativeEntry[key] = values
    }

    protected Map getEmbedded(Map nativeEntry, String key) {
        (Map) nativeEntry[key]
    }

    @Override
    protected void setManyToMany(PersistentEntity persistentEntity, Object obj, Map nativeEntry, ManyToMany manyToMany,
                                 Collection associatedObjects, Map<Association, List<Serializable>> toManyKeys) {
        def identifiers = manyToMany.isOwningSide() ?
            session.persist(associatedObjects) :
            associatedObjects.collect { ((EntityPersister) session.getPersister(it)).getObjectIdentifier(it) }
        toManyKeys.put(manyToMany, identifiers as List<Serializable>)
    }

    @Override
    protected Collection getManyToManyKeys(PersistentEntity persistentEntity, Object obj, Serializable nativeKey,
                                           Map nativeEntry, ManyToMany manyToMany) {
        getAssociationIndexer(nativeEntry, manyToMany).query(getObjectIdentifier(obj))
    }

    // ---------------------------------------------------------------- indexes

    @Override
    PropertyValueIndexer getPropertyIndexer(PersistentProperty property) {
        new IndexTableValueIndexer(this, property)
    }

    @Override
    AssociationIndexer getAssociationIndexer(Map nativeEntry, Association association) {
        association?.associatedEntity == null ? null : new IndexTableAssociationIndexer(this, association)
    }

    /** Index keys under one partition `k`, paginated. */
    List<String> queryIndex(String k) {
        List<String> ids = []
        def pages = datastore.client.queryPaginator {
            it.tableName(datastore.tables.indexTable()).keyConditionExpression('k = :k')
              .expressionAttributeValues([':k': AttributeValue.fromS(k)]).consistentRead(true)
        }
        pages.items().each { ids << it.id.s() }
        ids
    }

    void putIndex(String k, Object id) {
        def key = [k: AttributeValue.fromS(k), id: AttributeValue.fromS(ValueCodec.keyString(id))]
        dynamoSession.withBatch { WriteBatch b -> b.put(datastore.tables.indexTable(), key, [:], null, null) }
    }

    void deleteIndex(String k, Object id) {
        def key = [k: AttributeValue.fromS(k), id: AttributeValue.fromS(ValueCodec.keyString(id))]
        dynamoSession.withBatch { WriteBatch b -> b.delete(datastore.tables.indexTable(), key, null, null) }
    }

    /** Converts stored id strings to the entity's identity type. */
    Object convertId(PersistentEntity e, String id) {
        Class t = e.rootEntity.identity.type
        if (t == Long || t == long) return Long.valueOf(id)
        if (t == Integer || t == int) return Integer.valueOf(id)
        if (t == UUID) return UUID.fromString(id)
        id
    }

    /** Property-value index: k = `<root>|<property>|<value>`. */
    static class IndexTableValueIndexer implements PropertyValueIndexer {
        final DynamoEntityPersister persister
        final PersistentProperty property

        IndexTableValueIndexer(DynamoEntityPersister persister, PersistentProperty property) {
            this.persister = persister
            this.property = property
        }

        String getIndexRoot() {
            "${persister.rootFamily}|${property.name}"
        }

        static final String NULL_KEY = '\u2400null'

        /** True when only null values are indexed (`Entity.property:null`). */
        boolean isNullOnly() {
            persister.datastore.nullOnlyIndexes.contains(property.owner.javaClass.simpleName + '.' + property.name)
        }

        String keyFor(Object value) {
            "${indexRoot}|${value == null ? NULL_KEY : ValueCodec.keyString(value)}"
        }

        void index(Object value, Object primaryKey) {
            if (nullOnly ? value == null : value != null) persister.putIndex(keyFor(value), primaryKey)
        }

        void deindex(Object value, Object primaryKey) {
            if (nullOnly ? value == null : value != null) persister.deleteIndex(keyFor(value), primaryKey)
        }

        List query(Object value) {
            query(value, 0, -1)
        }

        List query(Object value, int offset, int max) {
            if (nullOnly ? value != null : value == null) return []
            def ids = persister.queryIndex(keyFor(value)).collect { persister.convertId(persister.persistentEntity, it) }
            int to = max < 0 ? ids.size() : Math.min(ids.size(), offset + max)
            offset >= ids.size() ? [] : ids.subList(offset, to)
        }

        String getIndexName(Object value) {
            keyFor(value)
        }
    }

    /**
     * One-to-many / many-to-many index: k = `<owner root>#<association>#<owner id>`,
     * id = `<8-digit position>#<child id>`. Positions keep List associations (Rundeck's
     * Workflow.commands) in order; indexing a full collection replaces what was stored, so
     * reordered or removed children do not linger.
     */
    static class IndexTableAssociationIndexer implements AssociationIndexer {
        final DynamoEntityPersister persister
        final Association association

        IndexTableAssociationIndexer(DynamoEntityPersister persister, Association association) {
            this.persister = persister
            this.association = association
        }

        private String keyFor(Object owner) {
            "${persister.rootFamily}#${association.name}#${ValueCodec.keyString(owner)}"
        }

        boolean doesReturnKeys() {
            true
        }

        void preIndex(Object primaryKey, List foreignKeys) {
        }

        void index(Object primaryKey, List foreignKeys) {
            String k = keyFor(primaryKey)
            persister.queryIndex(k).each { persister.deleteIndex(k, it) }
            foreignKeys?.findAll { it != null }?.eachWithIndex { fk, int i ->
                persister.putIndex(k, String.format('%08d#%s', i, ValueCodec.keyString(fk)))
            }
        }

        void index(Object primaryKey, Object foreignKey) {
            if (foreignKey == null) return
            String k = keyFor(primaryKey)
            List<String> entries = persister.queryIndex(k)
            String fk = ValueCodec.keyString(foreignKey)
            if (entries.any { childOf(it) == fk }) return
            int next = entries ? (entries.collect { positionOf(it) }.max() + 1) : 0
            persister.putIndex(k, String.format('%08d#%s', next, fk))
        }

        List query(Object primaryKey) {
            def target = association.associatedEntity
            persister.queryIndex(keyFor(primaryKey)).collect { childOf(it) }.unique().collect { persister.convertId(target, it) }
        }

        PersistentEntity getIndexedEntity() {
            association.associatedEntity
        }

        private static String childOf(String entry) {
            int i = entry.indexOf('#')
            i < 0 ? entry : entry.substring(i + 1)
        }

        private static int positionOf(String entry) {
            int i = entry.indexOf('#')
            i < 0 ? 0 : Integer.parseInt(entry.substring(0, i))
        }
    }
}
