package org.rundeck.kestrel.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Spring Session repository on DynamoDB (Kestrel M1: any web pod serves any request, no load
 * balancer stickiness). One item per session ({@code session#<id>}) in a table with string key
 * {@code pk} and TTL {@code expiresAt}: attributes Java-serialized, plus times and a digest.
 *
 * <ul>
 *   <li>A request that changed no attribute writes at most once per {@code touchInterval}
 *       (to move the expiry), so most requests cost one read.</li>
 *   <li>An attribute that cannot be serialized is logged and kept only in this pod's copy of the
 *       session; the request does not fail.</li>
 *   <li>A changed session id (login) deletes the old item.</li>
 * </ul>
 */
public class DynamoSessionRepository implements SessionRepository<MapSession> {
    private static final Logger LOG = LoggerFactory.getLogger(DynamoSessionRepository.class);
    static final String PREFIX = "session#";

    private final DynamoDbClient ddb;
    private final String table;
    private final Duration maxInactive;
    private final Duration touchInterval;
    private final Clock clock;
    /** Digest and access time of what was last read or written, per session id (bounded). */
    private final Map<String, Object[]> lastStored = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
            return size() > 20_000;
        }
    });

    /**
     * @param ddb           client
     * @param table         table with string key {@code pk} and TTL attribute {@code expiresAt}
     * @param maxInactive   session timeout
     * @param touchInterval minimum time between expiry-only writes
     * @param clock         time source
     */
    public DynamoSessionRepository(DynamoDbClient ddb, String table, Duration maxInactive, Duration touchInterval, Clock clock) {
        this.ddb = ddb;
        this.table = table;
        this.maxInactive = maxInactive;
        this.touchInterval = touchInterval;
        this.clock = clock;
    }

    @Override
    public MapSession createSession() {
        MapSession s = new MapSession();
        s.setMaxInactiveInterval(maxInactive);
        s.setCreationTime(clock.instant());
        s.setLastAccessedTime(clock.instant());
        return s;
    }

    @Override
    public void save(MapSession session) {
        Map<String, AttributeValue> attrs = new TreeMap<>();
        for (String name : session.getAttributeNames()) {
            Object v = session.getAttribute(name);
            if (v == null) {
                continue;
            }
            try {
                attrs.put(name, AttributeValue.fromB(SdkBytes.fromByteArray(serialize(v))));
            } catch (NotSerializableException e) {
                LOG.warn("Session attribute '{}' ({}) is not serializable; it stays on this pod only", name, v.getClass().getName());
            } catch (IOException e) {
                LOG.warn("Session attribute '{}' could not be serialized: {}", name, e.getMessage());
            }
        }
        String digest = digest(attrs);
        String originalId = session.getOriginalId();
        boolean idChanged = originalId != null && !originalId.equals(session.getId());
        Object[] prev = lastStored.get(session.getId());
        Instant now = clock.instant();
        if (!idChanged && prev != null && digest.equals(prev[0])
            && Duration.between((Instant) prev[1], session.getLastAccessedTime()).compareTo(touchInterval) < 0) {
            return;  // nothing new to store
        }
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("pk", AttributeValue.fromS(PREFIX + session.getId()));
        item.put("attrs", AttributeValue.fromM(attrs));
        item.put("digest", AttributeValue.fromS(digest));
        item.put("created", AttributeValue.fromN(Long.toString(session.getCreationTime().toEpochMilli())));
        item.put("accessed", AttributeValue.fromN(Long.toString(session.getLastAccessedTime().toEpochMilli())));
        item.put("maxInactive", AttributeValue.fromN(Long.toString(session.getMaxInactiveInterval().getSeconds())));
        item.put("expiresAt", AttributeValue.fromN(Long.toString(
            session.getLastAccessedTime().plus(session.getMaxInactiveInterval()).getEpochSecond())));
        ddb.putItem(b -> b.tableName(table).item(item));
        lastStored.put(session.getId(), new Object[]{digest, session.getLastAccessedTime()});
        if (idChanged) {
            deleteById(originalId);
            session.setId(session.getId());  // the new id is now the original
        }
    }

    @Override
    public MapSession findById(String id) {
        var r = ddb.getItem(b -> b.tableName(table).key(Map.of("pk", AttributeValue.fromS(PREFIX + id))).consistentRead(true));
        if (!r.hasItem() || r.item().isEmpty()) {
            return null;
        }
        Map<String, AttributeValue> item = r.item();
        MapSession s = new MapSession(id);
        s.setCreationTime(Instant.ofEpochMilli(Long.parseLong(item.get("created").n())));
        s.setLastAccessedTime(Instant.ofEpochMilli(Long.parseLong(item.get("accessed").n())));
        s.setMaxInactiveInterval(Duration.ofSeconds(Long.parseLong(item.get("maxInactive").n())));
        if (!s.getMaxInactiveInterval().isNegative()
            && clock.instant().isAfter(s.getLastAccessedTime().plus(s.getMaxInactiveInterval()))) {
            deleteById(id);
            return null;
        }
        Map<String, AttributeValue> attrs = item.containsKey("attrs") ? item.get("attrs").m() : Map.of();
        for (Map.Entry<String, AttributeValue> e : attrs.entrySet()) {
            try {
                s.setAttribute(e.getKey(), deserialize(e.getValue().b().asByteArray()));
            } catch (IOException | ClassNotFoundException ex) {
                LOG.warn("Session attribute '{}' could not be read back: {}", e.getKey(), ex.toString());
            }
        }
        lastStored.put(id, new Object[]{item.containsKey("digest") ? item.get("digest").s() : "", s.getLastAccessedTime()});
        return s;
    }

    @Override
    public void deleteById(String id) {
        lastStored.remove(id);
        ddb.deleteItem(b -> b.tableName(table).key(Map.of("pk", AttributeValue.fromS(PREFIX + id))));
    }

    static byte[] serialize(Object v) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(v);
        }
        return bos.toByteArray();
    }

    /** Resolves classes with the thread context class loader (application classes, not this module's). */
    static Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
                try {
                    return Class.forName(desc.getName(), false, cl != null ? cl : DynamoSessionRepository.class.getClassLoader());
                } catch (ClassNotFoundException e) {
                    return super.resolveClass(desc);
                }
            }
        }) {
            return in.readObject();
        }
    }

    private static String digest(Map<String, AttributeValue> attrs) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, AttributeValue> e : attrs.entrySet()) {
                md.update(e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update(e.getValue().b().asByteArray());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
