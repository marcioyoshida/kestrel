package org.rundeck.kestrel.gorm.dynamodb

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.datastore.mapping.core.OptimisticLockingException
import org.grails.datastore.mapping.model.PersistentEntity
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.CancellationReason
import software.amazon.awssdk.services.dynamodb.model.Delete
import software.amazon.awssdk.services.dynamodb.model.Put
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException
import software.amazon.awssdk.services.dynamodb.model.Update

/**
 * The writes of one session flush, merged per item (DynamoDB rejects two operations on one item
 * in a transaction, and GORM may insert then update an entity in the same flush):
 * put+update -> put with the update applied; update+update -> one update; anything+delete ->
 * delete; delete+update -> delete (an entity deleted in this flush is not written back);
 * delete/update+put -> put. Updates only apply to an existing item: one deleted meanwhile fails
 * like a version conflict instead of being recreated. Up to 100 items commit atomically in one TransactWriteItems;
 * larger flushes commit in chunks of 100 (atomic per chunk only, logged).
 */
@Slf4j
@CompileStatic
class WriteBatch {
    static final int MAX_TRANSACT_ITEMS = 100

    enum Kind { PUT, UPDATE, DELETE }

    /** One pending write to one item. */
    static class Op {
        String table
        Map<String, AttributeValue> key
        Kind kind
        Map<String, AttributeValue> item = [:]      // PUT: full item
        Map<String, AttributeValue> set = [:]       // UPDATE: attributes to set
        Set<String> remove = new LinkedHashSet<>()  // UPDATE: attributes to remove
        String versionAttribute                     // UPDATE: optimistic lock on this attribute...
        AttributeValue expectedVersion              // ...expected to equal this (or be absent)
        PersistentEntity entity
        Object entityKey
    }

    private final DynamoDbClient ddb
    private final Map<String, Op> ops = new LinkedHashMap<>()

    /** @param ddb client */
    WriteBatch(DynamoDbClient ddb) {
        this.ddb = ddb
    }

    /** Queues a full-item put. */
    void put(String table, Map<String, AttributeValue> key, Map<String, AttributeValue> item, PersistentEntity entity, Object entityKey) {
        Op op = new Op(table: table, key: key, kind: Kind.PUT, item: new LinkedHashMap<>(item), entity: entity, entityKey: entityKey)
        op.item.putAll(key)
        merge(op)
    }

    /** Queues a partial update, optionally guarded by a version attribute. */
    void update(String table, Map<String, AttributeValue> key, Map<String, AttributeValue> set, Set<String> remove,
                String versionAttribute, AttributeValue expectedVersion, PersistentEntity entity, Object entityKey) {
        merge(new Op(table: table, key: key, kind: Kind.UPDATE, set: new LinkedHashMap<>(set), remove: new LinkedHashSet<>(remove),
            versionAttribute: versionAttribute, expectedVersion: expectedVersion, entity: entity, entityKey: entityKey))
    }

    /** Queues a delete. */
    void delete(String table, Map<String, AttributeValue> key, PersistentEntity entity, Object entityKey) {
        merge(new Op(table: table, key: key, kind: Kind.DELETE, entity: entity, entityKey: entityKey))
    }

    /** @return number of distinct items to write */
    int size() {
        ops.size()
    }

    private void merge(Op next) {
        String id = next.table + '\u0000' + next.key.toString()
        Op prev = ops.get(id)
        if (prev == null || next.kind != Kind.UPDATE) {
            ops.remove(id)  // keep insertion order of the latest write
            ops.put(id, next)
            return
        }
        switch (prev.kind) {
            case Kind.PUT:
                next.set.each { k, v -> prev.item.put(k, v) }
                next.remove.each { prev.item.remove(it) }
                break
            case Kind.UPDATE:
                next.set.each { k, v -> prev.set.put(k, v); prev.remove.remove(k) }
                next.remove.each { prev.set.remove(it); prev.remove.add(it) }
                break  // the first update's version check is the one against stored state
            case Kind.DELETE:
                break  // deleted in this flush: drop the update
        }
    }

    /** Commits all queued writes. */
    void execute() {
        if (ops.isEmpty()) {
            return
        }
        List<Op> list = ops.values().findAll { it.kind != Kind.UPDATE || it.set || it.remove } as List<Op>
        ops.clear()
        if (list.isEmpty()) {
            return
        }
        if (list.size() > MAX_TRANSACT_ITEMS) {
            log.warn("Flush of ${list.size()} items exceeds one DynamoDB transaction; committing in chunks of ${MAX_TRANSACT_ITEMS}")
        }
        for (int from = 0; from < list.size(); from += MAX_TRANSACT_ITEMS) {
            List<Op> chunk = list.subList(from, Math.min(list.size(), from + MAX_TRANSACT_ITEMS))
            try {
                ddb.transactWriteItems { it.transactItems(chunk.collect { toItem(it) }) }
            } catch (TransactionCanceledException e) {
                List<CancellationReason> reasons = e.cancellationReasons() ?: []
                int i = reasons.findIndexOf { it.code() == 'ConditionalCheckFailed' }
                if (i >= 0) {
                    Op failed = chunk[i]
                    log.warn("Write conflict: ${failed.kind} ${failed.table} ${failed.entityKey} " +
                        (failed.kind == Kind.UPDATE ? "(item deleted, or version not ${failed.expectedVersion?.n()})" : ''))
                    throw new OptimisticLockingException(chunk[i].entity, chunk[i].entityKey)
                }
                throw e
            }
        }
    }

    private static TransactWriteItem toItem(Op op) {
        switch (op.kind) {
            case Kind.PUT:
                return TransactWriteItem.builder().put(Put.builder().tableName(op.table).item(op.item).build()).build()
            case Kind.DELETE:
                return TransactWriteItem.builder().delete(Delete.builder().tableName(op.table).key(op.key).build()).build()
            default:
                Map<String, String> names = [:]
                Map<String, AttributeValue> values = [:]
                List<String> sets = []
                List<String> removes = []
                int i = 0
                op.set.each { k, v ->
                    names['#a' + i] = k
                    values[':a' + i] = v
                    sets << ('#a' + i + ' = :a' + i)
                    i++
                }
                op.remove.each { k ->
                    names['#a' + i] = k
                    removes << ('#a' + i)
                    i++
                }
                String expr = ((sets ? 'SET ' + sets.join(', ') : '') + (removes ? ' REMOVE ' + removes.join(', ') : '')).trim()
                def u = Update.builder().tableName(op.table).key(op.key).updateExpression(expr)
                // UpdateItem would create a missing item; never recreate one deleted by another session or pod
                names['#key'] = op.key.keySet().first()
                String condition = 'attribute_exists(#key)'
                if (op.versionAttribute != null && op.expectedVersion != null) {
                    names['#ver'] = op.versionAttribute
                    values[':ver'] = op.expectedVersion
                    condition += ' AND (attribute_not_exists(#ver) OR #ver = :ver)'
                }
                u.conditionExpression(condition)
                // The SDK copies these maps, so set them only once they are complete.
                u.expressionAttributeNames(names)
                if (values) {
                    u.expressionAttributeValues(values)
                }
                return TransactWriteItem.builder().update(u.build()).build()
        }
    }
}
