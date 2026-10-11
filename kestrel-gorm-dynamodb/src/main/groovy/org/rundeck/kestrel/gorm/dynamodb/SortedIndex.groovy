package org.rundeck.kestrel.gorm.dynamodb

import groovy.transform.CompileStatic
import org.grails.datastore.mapping.model.PersistentEntity

/**
 * A sorted index (M2d): the rows of one partition value in the order of one sort property, so a
 * paged, ordered list reads only the rows it returns instead of the whole partition. Configured
 * as {@code Entity.partition>sort}, e.g. {@code Execution.project>dateCompleted}.
 *
 * <p>Entries live in the index table: {@code k = <root>|<partition>|<value>|><sort>},
 * {@code id = <sort token>#<item id>}. Sort tokens order like the in-memory sort: null first,
 * numbers and dates (epoch milliseconds) as order-preserving hex, strings as themselves. An entry
 * is written with its item in the same batch. When the previous values are known the old entry is
 * deleted; when they are not, the stale entry is skipped (and removed) by the reader, which
 * always checks an entry against its item.
 */
@CompileStatic
class SortedIndex {
    final String entityName
    final String partition
    final String sort

    SortedIndex(String entityName, String partition, String sort) {
        this.entityName = entityName
        this.partition = partition
        this.sort = sort
    }

    /** @return the index for a spec like {@code Execution.project>dateCompleted}, or null if it is not one */
    static SortedIndex parse(String spec) {
        def m = spec =~ /^(\w+)\.(\w+)>(\w+)$/
        m.matches() ? new SortedIndex(m.group(1), m.group(2), m.group(3)) : null
    }

    /** @return true when rows of this entity (or a subclass of the configured one) are indexed */
    boolean appliesTo(PersistentEntity entity) {
        for (PersistentEntity e = entity; e != null; e = e.parentEntity) {
            if (e.javaClass.simpleName == entityName) return true
        }
        false
    }

    String partitionKey(String rootFamily, Object partitionValue) {
        "${rootFamily}|${partition}|${ValueCodec.keyString(partitionValue)}|>${sort}".toString()
    }

    /** Marker item (in the index table) written once the index has been built for existing rows. */
    String builtKey(String rootFamily) {
        "${rootFamily}|>built|${partition}>${sort}".toString()
    }

    static String entryId(Object sortValue, Object id) {
        token(sortValue) + '#' + ValueCodec.keyString(id)
    }

    /** @return the item id string of an entry */
    static String idOf(String entryId) {
        entryId.substring(entryId.lastIndexOf('#') + 1)
    }

    /** @return the sort token of an entry */
    static String tokenOf(String entryId) {
        entryId.substring(0, entryId.lastIndexOf('#'))
    }

    /**
     * Order-preserving token. Dates, and the epoch-millisecond longs that stand for them in
     * snapshots and stored items, give the same token.
     */
    static String token(Object v) {
        if (v == null) return '0'
        if (v instanceof Date) return '1' + hex(((Date) v).time)
        if (v instanceof Number) return '1' + hex(((Number) v).longValue())
        '1' + v.toString()
    }

    private static String hex(long v) {
        String.format('%016x', v ^ Long.MIN_VALUE)
    }

    @Override
    String toString() {
        "${entityName}.${partition}>${sort}"
    }
}
