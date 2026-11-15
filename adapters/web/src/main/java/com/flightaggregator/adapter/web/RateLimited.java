package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.RateLimitedAction;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint whose requests {@link RateLimitInterceptor} counts against the client's limit
 * for the action (§6.6), so that the limit stays with the endpoint whatever its path.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface RateLimited {

  RateLimitedAction value();
}
