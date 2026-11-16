package com.flightaggregator.application.service;

import com.flightaggregator.application.port.in.Admission;
import com.flightaggregator.application.port.in.AdmitRequest;
import com.flightaggregator.application.port.in.RateLimit;
import com.flightaggregator.application.port.in.RateLimitedAction;
import com.flightaggregator.application.port.out.RequestCounter;
import com.flightaggregator.application.port.out.WindowCount;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Limits each public action per client address in fixed windows (§6.6, Q9), with one counter per
 * action and address, {@code <action>:<address>}, shared by every instance. A request past its
 * action's limit is rejected until the window ends. One the counter cannot count is admitted, so
 * the API keeps answering without Valkey (§6.2). Every action must have a limit, or the service is
 * not built, so a missing one stops the application at startup instead of failing a request.
 */
public final class RateLimitService implements AdmitRequest {

  private final RequestCounter counter;
  private final Map<RateLimitedAction, RateLimit> limits;

  public RateLimitService(RequestCounter counter, Map<RateLimitedAction, RateLimit> limits) {
    Set<RateLimitedAction> unlimited = EnumSet.allOf(RateLimitedAction.class);
    unlimited.removeAll(limits.keySet());
    if (!unlimited.isEmpty()) {
      throw new IllegalArgumentException(
          "Every rate-limited action needs a limit; none is set for " + unlimited);
    }
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
