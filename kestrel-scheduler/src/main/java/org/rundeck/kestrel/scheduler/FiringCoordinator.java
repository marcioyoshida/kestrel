package org.rundeck.kestrel.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Turns one bucket firing into at most one execution per job (ADR 0001 §2).
 *
 * <p>For each job currently in the bucket: claim {@code job@minute} in the {@link FireLedger},
 * hand the job to the local engine, and record the execution id. A claim left in
 * {@code CLAIMED} by a runner that died is taken over once it is older than
 * {@code staleAfter}, unless that runner had already created the execution (then the firing
 * happened, and the dead runner's restart marks it incomplete). The message is acknowledged
 * only when every job is settled, so a crash anywhere before that leads to redelivery, not to
 * a missed firing.
 */
public class FiringCoordinator implements SqsFireConsumer.Handler {
    private static final Logger LOG = LoggerFactory.getLogger(FiringCoordinator.class);

    /** A scheduled job as seen at firing time. */
    public static final class ScheduledJob {
        public final String uuid;
        public final String name;
        /** Null if the job may run now; otherwise why it is skipped (disabled, project off…). */
        public final String notRunnableReason;

        /**
         * @param uuid              job UUID
         * @param name              display name for logs
         * @param notRunnableReason null if runnable
         */
        public ScheduledJob(String uuid, String name, String notRunnableReason) {
            this.uuid = uuid;
            this.name = name;
            this.notRunnableReason = notRunnableReason;
        }
    }

    /** Job lookups backed by the Rundeck database. */
    public interface JobCatalog {
        /**
         * @param bucket bucket name
         * @return jobs whose current schedule maps to this bucket
         */
        List<ScheduledJob> jobsInBucket(String bucket) throws Exception;

        /**
         * @return true if {@code serverUuid} created a scheduled execution of the job at or after {@code since}
         */
        boolean scheduledExecutionExists(String jobUuid, String serverUuid, Instant since) throws Exception;
    }

    /** Starts a scheduled job on this runner. */
    public interface Launcher {
        /**
         * Hands the job to the engine and waits until its execution exists or the engine
         * decided not to run it.
         *
         * @return the outcome, or null if neither happened within the hand-off timeout
         */
        Outcome launch(ScheduledJob job, String fireKey, Instant scheduledAt) throws Exception;
    }

    /** What the engine did with a launch. */
    public static final class Outcome {
        public final Long executionId;
        public final String skipReason;

        private Outcome(Long executionId, String skipReason) {
            this.executionId = executionId;
            this.skipReason = skipReason;
        }

        /** @return an execution was created */
        public static Outcome started(long executionId) {
            return new Outcome(executionId, null);
        }

        /** @return the engine declined to run it */
        public static Outcome skipped(String reason) {
            return new Outcome(null, reason);
        }
    }

    private final JobCatalog catalog;
    private final FireLedger ledger;
    private final Launcher launcher;
    private final String serverUuid;
    private final Duration staleAfter;
    private final Duration maxLateness;
    private final Clock clock;

    /**
     * @param catalog     job lookups
     * @param ledger      fire ledger
     * @param launcher    local engine
     * @param serverUuid  this runner's server UUID
     * @param staleAfter  age after which a CLAIMED entry of another runner is considered dead;
     *                    must exceed the launcher's hand-off timeout
     * @param maxLateness firings older than this are skipped instead of run (outage catch-up)
     * @param clock       time source
     */
    public FiringCoordinator(JobCatalog catalog, FireLedger ledger, Launcher launcher, String serverUuid,
                             Duration staleAfter, Duration maxLateness, Clock clock)
    {
        this.catalog = catalog;
        this.ledger = ledger;
        this.launcher = launcher;
        this.serverUuid = serverUuid;
        this.staleAfter = staleAfter;
        this.maxLateness = maxLateness;
        this.clock = clock;
    }

    @Override
    public boolean handle(FireMessage fire) throws Exception {
        boolean allSettled = true;
        Instant now = clock.instant();
        boolean late = Duration.between(fire.getScheduledAt(), now).compareTo(maxLateness) > 0;
        for (ScheduledJob job : catalog.jobsInBucket(fire.getBucket())) {
            if (job.notRunnableReason != null) {
                LOG.debug("{} {}: not run ({})", fire, job.name, job.notRunnableReason);
                continue;
            }
            String key = fire.fireKey(job.uuid);
            try {
                allSettled &= fireJob(fire, job, key, late);
            } catch (Exception e) {
                LOG.error("{} {}: {}", fire, job.name, e.toString(), e);
                allSettled = false;
            }
        }
        return allSettled;
    }

    private boolean fireJob(FireMessage fire, ScheduledJob job, String key, boolean late) throws Exception {
        Instant now = clock.instant();
        FireLedger.Claim claim = ledger.claim(key, serverUuid, now);
        if (!claim.won) {
            if (claim.state != FireLedger.State.CLAIMED) {
                return true;  // already started or skipped by someone
            }
            if (Duration.between(claim.claimedAt, now).compareTo(staleAfter) < 0) {
                return false;  // another hand-off is in progress; look again on redelivery
            }
            boolean ran = catalog.scheduledExecutionExists(job.uuid, claim.owner, claim.claimedAt.minusSeconds(5));
            if (!ledger.takeOver(claim, serverUuid, now)) {
                return false;  // someone else took it over first
            }
            if (ran) {
                ledger.settle(key, serverUuid, FireLedger.State.STARTED, null,
                    "execution was created by " + claim.owner + " before it stopped");
                return true;
            }
            LOG.warn("{} {}: took over a stale claim from {}", fire, job.name, claim.owner);
        }
        if (late) {
            String note = "fired " + Duration.between(fire.getScheduledAt(), now).toMinutes()
                + " min late (limit " + maxLateness.toMinutes() + " min); skipped";
            LOG.warn("{} {}: {}", fire, job.name, note);
            ledger.settle(key, serverUuid, FireLedger.State.SKIPPED, null, note);
            return true;
        }
        Outcome outcome = launcher.launch(job, key, fire.getScheduledAt());
        if (outcome == null) {
            LOG.warn("{} {}: hand-off timed out; leaving the claim for redelivery", fire, job.name);
            return false;
        }
        if (outcome.executionId != null) {
            ledger.settle(key, serverUuid, FireLedger.State.STARTED, outcome.executionId, null);
            LOG.info("{} {}: execution {}", fire, job.name, outcome.executionId);
        } else {
            ledger.settle(key, serverUuid, FireLedger.State.SKIPPED, null, outcome.skipReason);
            LOG.info("{} {}: skipped by the engine ({})", fire, job.name, outcome.skipReason);
        }
        return true;
    }
}
