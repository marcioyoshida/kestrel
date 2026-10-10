package org.rundeck.kestrel.scheduler;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link ClusterBus} on a DynamoDB table with string key {@code pk} and TTL {@code expiresAt}
 * (Kestrel uses the fire-ledger table). A counter item {@code bus#<channel>#seq} numbers events;
 * each event is an item {@code bus#<channel>#<n>} kept for a day. Pollers read the counter and
 * fetch the events they have not seen.
 */
public class DynamoClusterBus implements ClusterBus {
    static final int MAX_CATCH_UP = 500;

    private final DynamoDbClient ddb;
    private final String table;
    private final String prefix;
    private final String origin;
    private final Clock clock;
    private long seen = -1;

    /**
     * @param ddb     client
     * @param table   table name
     * @param channel channel, e.g. {@code acl}
     * @param origin  this pod's identity; its own events are not returned by {@link #poll()}
     * @param clock   time source (TTL)
     */
    public DynamoClusterBus(DynamoDbClient ddb, String table, String channel, String origin, Clock clock) {
        this.ddb = ddb;
        this.table = table;
        this.prefix = "bus#" + channel + "#";
        this.origin = origin;
        this.clock = clock;
    }

    @Override
    public long publish(Map<String, ?> event) throws IOException {
        try {
            long seq = Long.parseLong(ddb.updateItem(b -> b.tableName(table)
                .key(Map.of("pk", AttributeValue.fromS(prefix + "seq")))
                .updateExpression("ADD #n :one")
                .expressionAttributeNames(Map.of("#n", "n"))
                .expressionAttributeValues(Map.of(":one", AttributeValue.fromN("1")))
                .returnValues(ReturnValue.UPDATED_NEW)).attributes().get("n").n());
            Map<String, AttributeValue> data = new HashMap<>();
            event.forEach((k, v) -> data.put(String.valueOf(k), AttributeValue.fromS(v == null ? "" : String.valueOf(v))));
            ddb.putItem(b -> b.tableName(table).item(Map.of(
                "pk", AttributeValue.fromS(prefix + seq),
                "origin", AttributeValue.fromS(origin),
                "data", AttributeValue.fromM(data),
                "expiresAt", AttributeValue.fromN(Long.toString(clock.instant().getEpochSecond() + 86400)))));
            return seq;
        } catch (RuntimeException e) {
            throw new IOException("cluster bus publish: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, String>> poll() throws IOException {
        try {
            var counter = ddb.getItem(b -> b.tableName(table).key(Map.of("pk", AttributeValue.fromS(prefix + "seq"))).consistentRead(true));
            long current = counter.hasItem() && counter.item().containsKey("n") ? Long.parseLong(counter.item().get("n").n()) : 0L;
            List<Map<String, String>> out = new ArrayList<>();
            if (seen < 0) {  // first poll: start from now
                seen = current;
                return out;
            }
            if (current <= seen) {
                return out;
            }
            long from = seen + 1;
            if (current - seen > MAX_CATCH_UP) {
                out.add(Map.of("_reset", "missed " + (current - seen) + " events"));
                seen = current;
                return out;
            }
            Map<Long, Map<String, String>> bySeq = new TreeMap<>();
            for (long start = from; start <= current; start += 100) {
                List<Map<String, AttributeValue>> keys = new ArrayList<>();
                for (long n = start; n <= Math.min(current, start + 99); n++) {
                    keys.add(Map.of("pk", AttributeValue.fromS(prefix + n)));
                }
                Map<String, KeysAndAttributes> req = Map.of(table, KeysAndAttributes.builder().keys(keys).consistentRead(true).build());
                while (req != null && !req.isEmpty()) {
                    final Map<String, KeysAndAttributes> r0 = req;
                    var r = ddb.batchGetItem(b -> b.requestItems(r0));
                    for (var item : r.responses().getOrDefault(table, List.of())) {
                        if (origin.equals(item.get("origin").s())) {
                            continue;
                        }
                        long n = Long.parseLong(item.get("pk").s().substring(prefix.length()));
                        Map<String, String> e = new HashMap<>();
                        item.get("data").m().forEach((k, v) -> e.put(k, v.s()));
                        bySeq.put(n, e);
                    }
                    req = r.unprocessedKeys();
                }
            }
            out.addAll(bySeq.values());
            seen = current;
            return out;
        } catch (RuntimeException e) {
            throw new IOException("cluster bus poll: " + e.getMessage(), e);
        }
    }
}
