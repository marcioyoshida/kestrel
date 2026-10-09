package org.rundeck.kestrel.scheduler

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Settable clock for tests. */
class TestClock extends Clock {
    Instant now

    TestClock(Instant now) { this.now = now }

    ZoneId getZone() { ZoneOffset.UTC }

    Clock withZone(ZoneId zone) { this }

    Instant instant() { now }
}
