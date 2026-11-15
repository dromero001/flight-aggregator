package com.flightaggregator.adapter.persistence.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock a test moves by hand, so that a wait is passed at once instead of slept through. */
public final class SettableClock extends Clock {

  private volatile Instant now;

  public SettableClock(Instant start) {
    this.now = start;
  }

  public void advance(Duration by) {
    now = now.plus(by);
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("a settable clock is in UTC");
  }
}
