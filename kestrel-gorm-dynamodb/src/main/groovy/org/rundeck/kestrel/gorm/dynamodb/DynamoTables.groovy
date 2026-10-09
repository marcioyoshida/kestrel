package org.rundeck.kestrel.gorm.dynamodb

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.TableStatus

import java.util.concurrent.ConcurrentHashMap

/**
 * Table naming and lazy creation. Each root entity gets `<prefix>-<name>` (PK `id`, S); the
 * datastore also uses `<prefix>--index` (PK `k`, SK `id`) for property and association indexes
 * and `<prefix>--ids` (PK `k`) for numeric id blocks. All on-demand billing.
 */
@Slf4j
@CompileStatic
class DynamoTables {
    final DynamoDbClient ddb
    final String prefix
    final boolean create
    private final Set<String> ready = ConcurrentHashMap.newKeySet()

    /**
     * @param ddb    client
     * @param prefix table name prefix, e.g. `kestrel-ref`
     * @param create create missing tables (dev/test and first boot); otherwise they must exist
     */
    DynamoTables(DynamoDbClient ddb, String prefix, boolean create) {
        this.ddb = ddb
        this.prefix = prefix
        this.create = create
    }

    /** @return table name for an entity family (class name), e.g. rundeck.Execution -> <prefix>-execution */
    String entityTable(String family) {
        String simple = family.contains('.') ? family.substring(family.lastIndexOf('.') + 1) : family
        String name = prefix + '-' + simple.replaceAll(/([a-z0-9])([A-Z])/, '$1_$2').toLowerCase()
        ensure(name, 'id', null)
        name
    }

    /** @return the index table */
    String indexTable() {
        String name = prefix + '--index'
        ensure(name, 'k', 'id')
        name
    }

    /** @return the id-block table */
    String idsTable() {
        String name = prefix + '--ids'
        ensure(name, 'k', null)
        name
    }

    private void ensure(String name, String hash, String range) {
        if (ready.contains(name)) {
            return
        }
        synchronized (this) {
            if (ready.contains(name)) {
                return
            }
            try {
                def status = ddb.describeTable { it.tableName(name) }.table().tableStatus()
                if (status != TableStatus.ACTIVE) {
                    waitActive(name)
                }
            } catch (ResourceNotFoundException e) {
                if (!create) {
                    throw new IllegalStateException("DynamoDB table ${name} does not exist (table creation is disabled)", e)
                }
                List<AttributeDefinition> defs = [AttributeDefinition.builder().attributeName(hash).attributeType(ScalarAttributeType.S).build()]
                List<KeySchemaElement> keys = [KeySchemaElement.builder().attributeName(hash).keyType(KeyType.HASH).build()]
                if (range) {
                    defs << AttributeDefinition.builder().attributeName(range).attributeType(ScalarAttributeType.S).build()
                    keys << KeySchemaElement.builder().attributeName(range).keyType(KeyType.RANGE).build()
                }
                try {
                    ddb.createTable { b -> b.tableName(name).attributeDefinitions(defs).keySchema(keys).billingMode(BillingMode.PAY_PER_REQUEST) }
                    log.info("Created DynamoDB table ${name}")
                } catch (ResourceInUseException ignored) {
                    // another replica created it concurrently
                }
                waitActive(name)
            }
            ready << name
        }
    }

    private void waitActive(String name) {
        ddb.waiter().waitUntilTableExists { it.tableName(name) }
    }
}
