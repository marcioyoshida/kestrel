package org.rundeck.kestrel.gorm.dynamodb

import groovy.transform.CompileStatic
import org.grails.datastore.mapping.core.connections.ConnectionSourceSettings
import org.grails.datastore.mapping.keyvalue.mapping.config.KeyValueMappingContext

/**
 * Key-value mapping context that accepts classes declaring {@code static mapWith = "dynamodb"}
 * (the stock one only accepts its own name, "keyvalue") as well as classes without mapWith.
 */
@CompileStatic
class DynamoMappingContext extends KeyValueMappingContext {
    static final String MAPPING_STRATEGY = 'dynamodb'

    /**
     * @param keyspace keyspace name (unused by DynamoDB; tables are per entity)
     * @param settings connection source settings
     */
    DynamoMappingContext(String keyspace, ConnectionSourceSettings settings) {
        super(keyspace, settings)
    }

    @Override
    protected boolean isValidMappingStrategy(Class javaClass, Object mappingStrategy) {
        mappingStrategy == null || MAPPING_STRATEGY.equalsIgnoreCase(mappingStrategy.toString())
    }
}
