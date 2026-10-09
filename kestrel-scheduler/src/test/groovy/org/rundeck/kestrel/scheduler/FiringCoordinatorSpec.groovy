package org.rundeck.kestrel.scheduler

import spock.lang.Specification

import java.time.Clock
import java.time.Duration
import java.time.Instant

class FiringCoordinatorSpec extends Specification {
    TestClock clock = new TestClock(Instant.parse('2027-01-01T12:00:20Z'))
    def fire = new FireMessage('kestrel-abc', '0 12 * * *', 'UTC', Instant.parse('2027-01-01T12:00:00Z'))
    def ledger = new MemoryLedger()
    List<FiringCoordinator.ScheduledJob> jobs = [new FiringCoordinator.ScheduledJob('job-1', 'nightly', null)]
    Set<String> executionsByDeadRunner = [] as Set
    def catalog = [
        jobsInBucket: { String b -> jobs },
        scheduledExecutionExists: { String job, String server, Instant since -> executionsByDeadRunner.contains(server) },
    ] as FiringCoordinator.JobCatalog
    List<String> launched = []
    FiringCoordinator.Outcome nextOutcome = FiringCoordinator.Outcome.started(42)
    def launcher = { FiringCoordinator.ScheduledJob j, String key, Instant at -> launched << key; nextOutcome } as FiringCoordinator.Launcher

    FiringCoordinator runner(String uuid) {
        new FiringCoordinator(catalog, ledger, launcher, uuid, Duration.ofSeconds(180), Duration.ofMinutes(15), clock)
    }

    def "a firing runs once and is acknowledged"() {
        expect:
        runner('r0').handle(fire)
        launched == ['job-1@' + (fire.scheduledAt.epochSecond / 60 as long)]
        ledger.items['job-1@' + (fire.scheduledAt.epochSecond / 60 as long)].state == FireLedger.State.STARTED
    }

    def "a duplicate delivery (CronJob retry, SQS redelivery, second runner) does not run again"() {
        when:
        runner('r0').handle(fire)
        def again = runner('r1').handle(fire)
        def same = runner('r0').handle(fire)

        then:
        again && same
        launched.size() == 1
    }

    def "a claim in progress elsewhere is left alone and the message is not acknowledged"() {
        given:
        ledger.claim(fire.fireKey('job-1'), 'r9', clock.now.minusSeconds(30))

        expect:
        !runner('r0').handle(fire)
        launched.isEmpty()
    }

    def "a stale claim by a runner that died before creating the execution is taken over and run"() {
        given:
        ledger.claim(fire.fireKey('job-1'), 'r9', clock.now.minusSeconds(600))

        expect:
        runner('r0').handle(fire)
        launched.size() == 1
        ledger.items[fire.fireKey('job-1')].owner == 'r0'
    }

    def "a stale claim whose runner already created the execution is settled, not re-run"() {
        given:
        ledger.claim(fire.fireKey('job-1'), 'r9', clock.now.minusSeconds(600))
        executionsByDeadRunner << 'r9'

        expect:
        runner('r0').handle(fire)
        launched.isEmpty()
        ledger.items[fire.fireKey('job-1')].state == FireLedger.State.STARTED
    }

    def "disabled jobs are not claimed; very late firings are skipped, not stormed"() {
        given:
        jobs << new FiringCoordinator.ScheduledJob('job-2', 'off', 'schedule disabled')

        when:
        clock.now = clock.now.plus(Duration.ofHours(2))
        def done = runner('r0').handle(fire)

        then:
        done
        launched.isEmpty()
        ledger.items[fire.fireKey('job-1')].state == FireLedger.State.SKIPPED
        !ledger.items.containsKey(fire.fireKey('job-2'))
    }

    def "a hand-off timeout leaves the message for redelivery"() {
        given:
        nextOutcome = null

        expect:
        !runner('r0').handle(fire)
        ledger.items[fire.fireKey('job-1')].state == FireLedger.State.CLAIMED
    }

    def "fire keys are per job and per scheduled minute"() {
        expect:
        fire.fireKey('job-1') != fire.fireKey('job-2')
        new FireMessage('b', '', '', Instant.parse('2027-01-01T12:00:59Z')).fireKey('j') ==
            new FireMessage('b', '', '', Instant.parse('2027-01-01T12:00:00Z')).fireKey('j')
        FireMessage.parse(fire.toJson()).fireKey('j') == fire.fireKey('j')
    }

    /** FireLedger with DynamoDB's conditional semantics. */
    static class MemoryLedger implements FireLedger {
        Map<String, FireLedger.Claim> items = [:]

        synchronized FireLedger.Claim claim(String key, String owner, Instant now) {
            def c = items[key]
            if (c) return new FireLedger.Claim(key, c.owner, c.claimedAt, c.state, c.executionId, false)
            items[key] = new FireLedger.Claim(key, owner, now, FireLedger.State.CLAIMED, null, true)
        }

        synchronized boolean takeOver(FireLedger.Claim stale, String owner, Instant now) {
            def c = items[stale.fireKey]
            if (c.owner != stale.owner || c.claimedAt != stale.claimedAt || c.state != FireLedger.State.CLAIMED) return false
            items[stale.fireKey] = new FireLedger.Claim(stale.fireKey, owner, now, c.state, null, false)
            true
        }

        synchronized boolean settle(String key, String owner, FireLedger.State state, Long id, String note) {
            def c = items[key]
            if (c.owner != owner) return false
            items[key] = new FireLedger.Claim(key, owner, c.claimedAt, state, id, false)
            true
        }
    }
}
