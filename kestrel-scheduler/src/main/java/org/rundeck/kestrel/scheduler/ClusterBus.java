package org.rundeck.kestrel.scheduler;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Broadcast of small events between Kestrel pods (cache invalidations that upstream Rundeck
 * publishes as {@code cluster.*} events and that OSS never delivers to other servers).
 * Delivery is at-least-once by polling; handlers must be idempotent.
 */
public interface ClusterBus {
    /**
     * @param event string attributes
     * @return sequence number assigned to the event
     */
    long publish(Map<String, ?> event) throws IOException;

    /**
     * @return events published by other pods since the previous poll (or since start), in order.
     *         An event with key {@code _reset} means events were missed and caches should be
     *         invalidated wholesale.
     */
    List<Map<String, String>> poll() throws IOException;
}
