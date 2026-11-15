package com.flightaggregator.bootstrap.observability;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Each state transition of a provider's circuit breaker is one {@code WARN} line, with the provider
 * and both states as structured fields (§6.5, §6.10): a breaker that opens, probes or closes again
 * is seen in the log, not only in its gauge. The line is logged on the thread of the call that
 * changed the state, a download's.
 */
public final class LoggedBreakerTransitions {

  private static final Logger LOG = LoggerFactory.getLogger(LoggedBreakerTransitions.class);

  private LoggedBreakerTransitions() {}

  /** Logs the transitions of every breaker the registry creates, so call it on a new registry. */
  public static void of(CircuitBreakerRegistry circuitBreakers) {
    circuitBreakers
        .getEventPublisher()
        .onEntryAdded(added -> logTransitionsOf(added.getAddedEntry()));
  }

  private static void logTransitionsOf(CircuitBreaker circuitBreaker) {
    circuitBreaker.getEventPublisher().onStateTransition(LoggedBreakerTransitions::warn);
  }

  private static void warn(CircuitBreakerOnStateTransitionEvent event) {
    LOG.atWarn()
        .addKeyValue("provider", event.getCircuitBreakerName())
        .addKeyValue("from", event.getStateTransition().getFromState().name())
        .addKeyValue("to", event.getStateTransition().getToState().name())
        .log("A provider's circuit breaker changed state");
  }
}
