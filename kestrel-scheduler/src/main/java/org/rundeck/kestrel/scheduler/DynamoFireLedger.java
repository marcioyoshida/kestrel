package org.rundeck.kestrel.scheduler;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link FireLedger} on a DynamoDB table with string partition key {@code pk} and TTL attribute
 * {@code expiresAt}. Items expire after {@code retention}, well past any SQS redelivery.
 */
public class DynamoFireLedger implements FireLedger {
    private final DynamoDbClient ddb;
    private final String table;
    private final Duration retention;

    /**
     * @param ddb       client
     * @param table     table name
     * @param retention how long claims are kept
     */
    public DynamoFireLedger(DynamoDbClient ddb, String table, Duration retention) {
        this.ddb = ddb;
        this.table = table;
        this.retention = retention;
    }

    @Override
    public Claim claim(String fireKey, String owner, Instant now) throws IOException {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", s(fireKey));
        item.put("owner", s(owner));
        item.put("claimedAt", n(now.toEpochMilli()));
        item.put("state", s(State.CLAIMED.name()));
        item.put("expiresAt", n(now.plus(retention).getEpochSecond()));
        try {
            ddb.putItem(b -> b.tableName(table).item(item).conditionExpression("attribute_not_exists(pk)"));
            return new Claim(fireKey, owner, now, State.CLAIMED, null, true);
        } catch (ConditionalCheckFailedException e) {
            GetItemResponse r = ddb.getItem(b -> b.tableName(table).key(Map.of("pk", s(fireKey))).consistentRead(true));
            if (!r.hasItem()) {
                throw new IOException("claim " + fireKey + " vanished");
            }
            Map<String, AttributeValue> it = r.item();
            return new Claim(fireKey, it.get("owner").s(), Instant.ofEpochMilli(Long.parseLong(it.get("claimedAt").n())),
                State.valueOf(it.get("state").s()),
                it.containsKey("executionId") ? Long.valueOf(it.get("executionId").n()) : null, false);
        } catch (RuntimeException e) {
            throw new IOException("claim " + fireKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean takeOver(Claim stale, String owner, Instant now) throws IOException {
        try {
            ddb.updateItem(b -> b.tableName(table).key(Map.of("pk", s(stale.fireKey)))
                .updateExpression("SET #o = :o, claimedAt = :n")
                .conditionExpression("#o = :eo AND claimedAt = :ec AND #s = :claimed")
                .expressionAttributeNames(Map.of("#o", "owner", "#s", "state"))
                .expressionAttributeValues(Map.of(
                    ":o", s(owner), ":n", n(now.toEpochMilli()),
                    ":eo", s(stale.owner), ":ec", n(stale.claimedAt.toEpochMilli()),
                    ":claimed", s(State.CLAIMED.name()))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        } catch (RuntimeException e) {
            throw new IOException("take over " + stale.fireKey + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean settle(String fireKey, String owner, State state, Long executionId, String note) throws IOException {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":s", s(state.name()));
        values.put(":o", s(owner));
        StringBuilder set = new StringBuilder("SET #s = :s");
        if (executionId != null) {
            values.put(":e", n(executionId));
            set.append(", executionId = :e");
        }
        if (note != null) {
            values.put(":note", s(note.length() > 500 ? note.substring(0, 500) : note));
            set.append(", note = :note");
        }
        try {
            ddb.updateItem(b -> b.tableName(table).key(Map.of("pk", s(fireKey)))
                .updateExpression(set.toString())
                .conditionExpression("#o = :o")
                .expressionAttributeNames(Map.of("#o", "owner", "#s", "state"))
                .expressionAttributeValues(values));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        } catch (RuntimeException e) {
            throw new IOException("settle " + fireKey + ": " + e.getMessage(), e);
        }
    }

    private static AttributeValue s(String v) {
        return AttributeValue.builder().s(v).build();
    }

    private static AttributeValue n(long v) {
        return AttributeValue.builder().n(Long.toString(v)).build();
    }
}
