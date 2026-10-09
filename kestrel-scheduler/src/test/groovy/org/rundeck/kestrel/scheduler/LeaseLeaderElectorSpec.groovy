package org.rundeck.kestrel.scheduler

import spock.lang.Specification

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class LeaseLeaderElectorSpec extends Specification {
    def api = new FakeKubeApi()
    TestClock clock = new TestClock(Instant.parse('2027-01-01T00:00:00Z'))

    LeaseLeaderElector candidate(String id) {
        new LeaseLeaderElector(api, 'kestrel', 'kestrel-scheduler', id, Duration.ofSeconds(15), clock)
    }

    def "first candidate creates the lease and leads; the second does not"() {
        given:
        def a = candidate('web-0')
        def b = candidate('web-1')

        expect:
        a.tick()
        a.isLeader()
        !b.tick()
        !b.isLeader()
    }

    def "the holder renews; nobody else can take a fresh lease"() {
        given:
        def a = candidate('web-0')
        def b = candidate('web-1')
        a.tick()

        when:
        clock.now = clock.now.plusSeconds(10)

        then:
        a.tick()
        !b.tick()
    }

    def "an expired lease is taken over, and the old holder stops leading on its own"() {
        given:
        def a = candidate('web-0')
        def b = candidate('web-1')
        a.tick()

        when: 'web-0 stops renewing (killed or partitioned)'
        clock.now = clock.now.plusSeconds(16)

        then: 'web-0 already considers itself not leader without any API call'
        !a.isLeader()
        b.tick()
        b.isLeader()
        api.objects.values().first().path('spec').path('leaseTransitions').asInt() == 1

        and: 'web-0 cannot take it back while web-1 renews'
        !a.tick()
    }

    def "a leader stops acting before its lease expires if renewals fail"() {
        given:
        def a = candidate('web-0')
        a.tick()

        when:
        clock.now = clock.now.plusSeconds(11)  // past 2/3 of the 15 s lease

        then:
        !a.isLeader()
    }

    def "simultaneous takeover: exactly one of two candidates wins"() {
        given:
        def a = candidate('web-0')
        a.tick()
        clock.now = clock.now.plusSeconds(20)
        def b = candidate('web-1')
        def c = candidate('web-2')
        def path = api.objects.keySet().first()
        // b and c both read the expired lease before either writes
        def stale = api.get(path)
        api.metaClass.get = { String p -> stale.deepCopy() }

        expect:
        [b.tick(), c.tick()].count { it } == 1
    }
}
