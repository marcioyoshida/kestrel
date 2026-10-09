package org.rundeck.kestrel.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Leader election on a {@code coordination.k8s.io/v1} Lease, the same protocol client-go uses:
 * the holder renews {@code renewTime}; anyone may take the Lease once
 * {@code renewTime + leaseDurationSeconds} has passed. Every write carries the observed
 * resourceVersion, so two candidates can never both win.
 *
 * <p>Call {@link #tick()} every {@code renewInterval}. {@link #isLeader()} turns false as soon as
 * a renewal has not succeeded within the lease duration, even if the API server is unreachable,
 * so a partitioned replica stops acting before another one can take over.
 */
public class LeaseLeaderElector {
    private static final Logger LOG = LoggerFactory.getLogger(LeaseLeaderElector.class);
    private static final DateTimeFormatter MICRO =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private final KubeApi api;
    private final String path;
    private final String collection;
    private final String name;
    private final String identity;
    private final Duration leaseDuration;
    private final Clock clock;
    private volatile Instant leaderUntil = Instant.EPOCH;

    /**
     * @param api           Kubernetes API
     * @param namespace     namespace of the Lease
     * @param name          Lease name
     * @param identity      this candidate (the pod name)
     * @param leaseDuration how long a lease stays valid without renewal
     * @param clock         time source
     */
    public LeaseLeaderElector(KubeApi api, String namespace, String name, String identity, Duration leaseDuration, Clock clock) {
        this.api = api;
        this.collection = "/apis/coordination.k8s.io/v1/namespaces/" + namespace + "/leases";
        this.path = collection + "/" + name;
        this.name = name;
        this.identity = identity;
        this.leaseDuration = leaseDuration;
        this.clock = clock;
    }

    /**
     * @return true while this candidate holds a lease renewed within the lease duration
     */
    public boolean isLeader() {
        return clock.instant().isBefore(leaderUntil);
    }

    /** @return this candidate's identity */
    public String getIdentity() {
        return identity;
    }

    /**
     * Acquires or renews the lease once.
     *
     * @return whether this candidate holds the lease after the attempt
     */
    public boolean tick() {
        Instant now = clock.instant();
        try {
            ObjectNode lease = api.get(path);
            if (lease == null) {
                api.create(collection, newLease(now));
                won(now, "created");
                return true;
            }
            JsonNode spec = lease.path("spec");
            String holder = spec.path("holderIdentity").asText("");
            Instant renewed = parse(spec.path("renewTime").asText(null));
            long seconds = spec.path("leaseDurationSeconds").asLong(leaseDuration.getSeconds());
            boolean expired = renewed == null || !now.isBefore(renewed.plusSeconds(seconds));
            if (!identity.equals(holder) && !expired) {
                lost("held by " + holder);
                return false;
            }
            ObjectNode s = (ObjectNode) lease.get("spec");
            if (!identity.equals(holder)) {
                s.put("holderIdentity", identity);
                s.put("acquireTime", MICRO.format(now));
                s.put("leaseTransitions", spec.path("leaseTransitions").asInt(0) + 1);
            }
            s.put("renewTime", MICRO.format(now));
            s.put("leaseDurationSeconds", leaseDuration.getSeconds());
            api.replace(path, lease);
            won(now, identity.equals(holder) ? null : "took over from " + (holder.isEmpty() ? "nobody" : holder));
            return true;
        } catch (KubeApi.ConflictException e) {
            lost("lost a concurrent update");
            return false;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Lease {}: {}", name, e.getMessage());
            return isLeader();  // keep acting only until the current lease would expire
        }
    }

    private ObjectNode newLease(Instant now) {
        ObjectNode lease = JsonNodeFactory.instance.objectNode();
        lease.put("apiVersion", "coordination.k8s.io/v1");
        lease.put("kind", "Lease");
        lease.putObject("metadata").put("name", name);
        ObjectNode spec = lease.putObject("spec");
        spec.put("holderIdentity", identity);
        spec.put("leaseDurationSeconds", leaseDuration.getSeconds());
        spec.put("acquireTime", MICRO.format(now));
        spec.put("renewTime", MICRO.format(now));
        spec.put("leaseTransitions", 0);
        return lease;
    }

    private void won(Instant at, String how) {
        boolean was = isLeader();
        // Stop acting a little before the lease expires so clocks a few seconds apart stay safe.
        leaderUntil = at.plus(leaseDuration.multipliedBy(2).dividedBy(3));
        if (!was || how != null) {
            LOG.info("Lease {}: {} is leader{}", name, identity, how == null ? "" : " (" + how + ")");
        }
    }

    private void lost(String why) {
        if (isLeader()) {
            LOG.info("Lease {}: {} is no longer leader ({})", name, identity, why);
        }
        leaderUntil = Instant.EPOCH;
    }

    private static Instant parse(String t) {
        try {
            return t == null || t.isEmpty() ? null : Instant.parse(t);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
