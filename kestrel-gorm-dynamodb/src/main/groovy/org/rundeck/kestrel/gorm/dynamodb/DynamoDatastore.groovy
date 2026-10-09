package org.rundeck.kestrel.gorm.dynamodb

import groovy.transform.CompileStatic
import org.grails.datastore.mapping.config.Settings
import org.grails.datastore.mapping.core.Session
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
 * {@code indexed} as {@code ClassSimpleName.property}.
 */
@CompileStatic
class DynamoDatastore extends SimpleMapDatastore {
    final DynamoDbClient client
    final DynamoTables tables
    final IdAllocator ids
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
        for (PersistentEntity entity : mappingContext.persistentEntities) {
            for (PersistentProperty p : entity.persistentProperties) {
                boolean wanted = p instanceof ToOne || indexed.contains(entity.javaClass.simpleName + '.' + p.name)
                def form = p.mapping?.mappedForm
                if (wanted && form instanceof KeyValue) {
                    ((KeyValue) form).setIndex(true)
                }
            }
        }
    }

    /**
     * Like Hibernate's datastore (and unlike GORM's generic one), open a session when none is
     * bound: Rundeck calls GORM from event threads and requests without a session binding.
     * Writes are written through, so an unbound session loses nothing when discarded.
     */
    @Override
    Session getCurrentSession() {
        DatastoreUtils.doGetSession(this, true)
    }

    @Override
    protected Session createSession(PropertyResolver connectionDetails) {
        new DynamoSession(this, mappingContext, publisher)
    }
}
