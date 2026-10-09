package org.rundeck.kestrel.gorm.dynamodb

import groovy.transform.CompileStatic
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ReturnValue

import java.util.concurrent.ConcurrentHashMap

/**
 * Numeric identifiers, unique across replicas: each JVM reserves a block of ids per family with
 * one atomic ADD on `<prefix>--ids`, then hands them out locally. Rundeck exposes numeric
 * execution ids, so they stay numeric; gaps between blocks are expected (as with DB sequences).
 */
@CompileStatic
class IdAllocator {
    private final DynamoTables tables
    private final long blockSize
    private final Map<String, long[]> blocks = new ConcurrentHashMap<>()  // family -> [next, end]

    /**
     * @param tables    table registry
     * @param blockSize ids reserved per round trip
     */
    IdAllocator(DynamoTables tables, long blockSize) {
        this.tables = tables
        this.blockSize = blockSize
    }

    /**
     * @param family entity family
     * @return a new id, never handed out before by any replica
     */
    long next(String family) {
        long[] b = blocks.computeIfAbsent(family) { new long[]{0L, 0L} }
        synchronized (b) {
            if (b[0] >= b[1]) {
                def r = tables.ddb.updateItem { u ->
                    u.tableName(tables.idsTable())
                     .key([k: AttributeValue.fromS(family)])
                     .updateExpression('ADD #n :b')
                     .expressionAttributeNames(['#n': 'next'])
                     .expressionAttributeValues([':b': AttributeValue.fromN(Long.toString(blockSize))])
                     .returnValues(ReturnValue.UPDATED_NEW)
                }
                long end = Long.parseLong(r.attributes().get('next').n())
                b[0] = end - blockSize + 1
                b[1] = end + 1
            }
            return b[0]++
        }
    }
}
