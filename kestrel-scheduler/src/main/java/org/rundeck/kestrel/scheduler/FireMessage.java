package org.rundeck.kestrel.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * One firing of a schedule bucket, as sent by the trigger container to the SQS FIFO queue.
 * {@code scheduledAt} is the minute the CronJob controller scheduled, not when the pod ran,
 * so a late start or a retried trigger names the same firing.
 */
public final class FireMessage {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String bucket;
    private final String schedule;
    private final String timeZone;
    private final Instant scheduledAt;

    /**
     * @param bucket      CronJob / bucket name
     * @param schedule    Kubernetes schedule of the bucket when it fired
     * @param timeZone    time zone of the bucket
     * @param scheduledAt scheduled minute
     */
    public FireMessage(String bucket, String schedule, String timeZone, Instant scheduledAt) {
        this.bucket = bucket;
        this.schedule = schedule;
        this.timeZone = timeZone;
        this.scheduledAt = scheduledAt.truncatedTo(ChronoUnit.MINUTES);
    }

    /**
     * @param body message body
     * @return the message
     * @throws IOException if the body is not a version-1 fire message
     */
    public static FireMessage parse(String body) throws IOException {
        JsonNode n = JSON.readTree(body);
        if (n.path("v").asInt() != 1 || !n.hasNonNull("bucket") || !n.hasNonNull("scheduledAt")) {
            throw new IOException("not a Kestrel fire message: " + body);
        }
        return new FireMessage(n.get("bucket").asText(), n.path("schedule").asText(""),
            n.path("timeZone").asText(""), Instant.parse(n.get("scheduledAt").asText()));
    }

    /** @return JSON body, as the trigger writes it */
    public String toJson() {
        ObjectNode n = JSON.createObjectNode();
        n.put("v", 1);
        n.put("bucket", bucket);
        n.put("schedule", schedule);
        n.put("timeZone", timeZone);
        n.put("scheduledAt", scheduledAt.toString());
        return n.toString();
    }

    /**
     * @param jobUuid job
     * @return the idempotency key of this firing for one job
     */
    public String fireKey(String jobUuid) {
        return jobUuid + "@" + scheduledAt.getEpochSecond() / 60;
    }

    /** @return bucket name */
    public String getBucket() {
        return bucket;
    }

    /** @return schedule when fired */
    public String getSchedule() {
        return schedule;
    }

    /** @return time zone when fired */
    public String getTimeZone() {
        return timeZone;
    }

    /** @return scheduled minute */
    public Instant getScheduledAt() {
        return scheduledAt;
    }

    @Override
    public String toString() {
        return bucket + "@" + scheduledAt;
    }
}
