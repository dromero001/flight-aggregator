package com.flightaggregator.application.service;

import com.flightaggregator.application.port.in.Admission;
import com.flightaggregator.application.port.in.AdmitRequest;
import com.flightaggregator.application.port.in.RateLimit;
import com.flightaggregator.application.port.in.RateLimitedAction;
import com.flightaggregator.application.port.out.RequestCounter;
import com.flightaggregator.application.port.out.WindowCount;
import java.util.Locale;
import java.util.Map;

/**
 * Limits each public action per client address in fixed windows (§6.6, Q9), with one counter per
 * action and address, {@code <action>:<address>}, shared by every instance. A request past its
 * action's limit is rejected until the window ends. One the counter cannot count is admitted, so
 * the API keeps answering without Valkey (§6.2).
 */
public final class RateLimitService implements AdmitRequest {

  private final RequestCounter counter;
  private final Map<RateLimitedAction, RateLimit> limits;

  public RateLimitService(RequestCounter counter, Map<RateLimitedAction, RateLimit> limits) {
    this.counter = counter;
    this.limits = Map.copyOf(limits);
  }

  @Override
  public Admission admit(String clientAddress, RateLimitedAction action) {
    RateLimit limit = limits.get(action);
    WindowCount count =
        counter.increment(
            action.name().toLowerCase(Locale.ROOT) + ":" + clientAddress, limit.window());
    return count.count() > limit.requests()
        ? new Admission.Rejected(count.resetsIn())
        : new Admission.Admitted();
  }
}
