package com.optimumpathinc.nexus.mcp.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A {@link Clock} the tests advance by hand, so TTL/backoff behaviour needs no sleeping. */
final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    void advance(Duration amount) {
        instant = instant.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(instant, newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
