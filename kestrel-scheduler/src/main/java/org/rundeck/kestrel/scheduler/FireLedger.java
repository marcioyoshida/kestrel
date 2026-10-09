package org.rundeck.kestrel.scheduler;

import java.io.IOException;
import java.time.Instant;

/**
 * Exactly-once claims on firings: the first runner to claim {@code job@minute} runs it; anyone
 * else sees the existing claim. A claim stuck in {@link State#CLAIMED} (its runner died before
 * the execution started) can be taken over with a conditional write, so two runners never both
 * take over the same stale claim.
 */
public interface FireLedger {

    /** Claim lifecycle. */
    enum State { CLAIMED, STARTED, SKIPPED }

    /**
     * Claims a firing, or returns the existing claim.
     *
     * @param fireKey {@link FireMessage#fireKey(String)}
     * @param owner   server UUID of the claiming runner
     * @param now     current time
     */
    Claim claim(String fireKey, String owner, Instant now) throws IOException;

    /**
     * Takes over a stale claim.
     *
     * @param stale the claim as last read
     * @return true if this owner now holds it
     */
    boolean takeOver(Claim stale, String owner, Instant now) throws IOException;

    /**
     * Records the outcome. Ignored unless {@code owner} still holds the claim.
     *
     * @param executionId Rundeck execution id, or null when skipped
     * @param note        reason for a skip, or null
     * @return true if recorded
     */
    boolean settle(String fireKey, String owner, State state, Long executionId, String note) throws IOException;

    /** A claim as stored. */
    final class Claim {
        public final String fireKey;
        public final String owner;
        public final Instant claimedAt;
        public final State state;
        public final Long executionId;
        /** True if this call created the claim. */
        public final boolean won;

        /**
         * @param fireKey     key
         * @param owner       owning server UUID
         * @param claimedAt   when it was (re)claimed
         * @param state       state
         * @param executionId execution id once started
         * @param won         whether the caller just created it
         */
        public Claim(String fireKey, String owner, Instant claimedAt, State state, Long executionId, boolean won) {
            this.fireKey = fireKey;
            this.owner = owner;
            this.claimedAt = claimedAt;
            this.state = state;
            this.executionId = executionId;
            this.won = won;
        }

        @Override
        public String toString() {
            return fireKey + "[" + state + " by " + owner + " at " + claimedAt + (executionId != null ? " exec " + executionId : "") + "]";
        }
    }
}
