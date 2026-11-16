package com.flightaggregator.bootstrap.config;

import com.flightaggregator.application.port.in.RateLimit;
import com.flightaggregator.application.port.in.RateLimitedAction;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The rate limits, bound from {@code rate-limit.actions.<action>.requests} and {@code .window}
 * (§6.6, Q9), so that an operator tunes them without a build. Each limit is validated as it binds
 * ({@link RateLimit}), and {@code RateLimitService} refuses limits that leave an action out, so a
 * wrong or missing setting stops startup. A setting under {@code rate-limit} that names none of
 * these stops it too, as under {@code search}: a misspelt key would otherwise leave the limit it
 * meant to change.
 */
@ConfigurationProperties(prefix = "rate-limit", ignoreUnknownFields = false)
record RateLimitProperties(Map<RateLimitedAction, RateLimit> actions) {

  /** No setting under {@code rate-limit.actions} binds as null: no action has a limit. */
  RateLimitProperties {
    actions = actions == null ? Map.of() : Map.copyOf(actions);
  }
}
