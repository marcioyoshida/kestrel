package org.rundeck.kestrel.scheduler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Jobs that share an identical (Kubernetes schedule, time zone) pair share one CronJob, so the
 * number of CronJob objects grows with distinct schedules rather than with jobs (ADR 0001 §2).
 * The CronJob name is derived from that pair, so every replica computes the same name.
 */
public final class ScheduleBucket {
    /** Prefix of every CronJob Kestrel manages. */
    public static final String NAME_PREFIX = "kestrel-";

    private final String schedule;
    private final String timeZone;
    private final String name;

    private ScheduleBucket(String schedule, String timeZone) {
        this.schedule = schedule;
        this.timeZone = timeZone;
        this.name = NAME_PREFIX + digest(schedule + "\n" + timeZone).substring(0, 12);
    }

    /**
     * Bucket for a Rundeck job schedule.
     *
     * @param quartzCron the job's Quartz cron expression
     * @param timeZone   the job's time zone ID, or null/blank for the default
     * @param defaultZone time zone used when the job has none (the server's)
     * @return the bucket
     * @throws UnsupportedScheduleException if the schedule has no exact Kubernetes form
     */
    public static ScheduleBucket of(String quartzCron, String timeZone, ZoneId defaultZone)
        throws UnsupportedScheduleException
    {
        String zone = (timeZone == null || timeZone.isBlank()) ? defaultZone.getId() : timeZone.trim();
        try {
            zone = ZoneId.of(zone).getId();
        } catch (DateTimeException e) {
            throw new UnsupportedScheduleException(quartzCron, "unknown time zone '" + zone + "'");
        }
        return new ScheduleBucket(CronTranslator.toKubernetes(quartzCron), zone);
    }

    /** @return 5-field Kubernetes schedule */
    public String getSchedule() {
        return schedule;
    }

    /** @return IANA time zone ID used as the CronJob's {@code spec.timeZone} */
    public String getTimeZone() {
        return timeZone;
    }

    /** @return CronJob name, stable across replicas */
    public String getName() {
        return name;
    }

    static String digest(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ScheduleBucket && ((ScheduleBucket) o).name.equals(name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return name + "[" + schedule + " " + timeZone + "]";
    }
}
