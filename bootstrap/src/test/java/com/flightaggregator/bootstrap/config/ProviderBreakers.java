package com.flightaggregator.bootstrap.config;

import org.springframework.context.ApplicationContext;

/**
 * Closes the wired providers' circuit breakers. A test class that shares one application context
 * across its tests calls it before each one, so the failures one test provokes cannot open a
 * breaker for the next.
 */
public final class ProviderBreakers {

  private ProviderBreakers() {}

  public static void closeAll(ApplicationContext context) {
    context
        .getBeansOfType(WiredProvider.class)
        .values()
        .forEach(provider -> provider.circuitBreaker().reset());
  }
}
