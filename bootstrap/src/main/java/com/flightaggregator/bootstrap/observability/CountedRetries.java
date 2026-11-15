package com.flightaggregator.bootstrap.observability;

import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.Retryable;

/**
 * Counts each retry of one provider's requests, not the first attempts, as {@code
 * ingestion.provider.retries{provider}} (§6.10). The counter exists from the start, at zero.
 */
public final class CountedRetries implements RetryListener {

  private final Counter retries;

  public CountedRetries(MeterRegistry meters, Provider provider) {
    this.retries =
        Counter.builder("ingestion.provider.retries")
            .tag("provider", provider.name())
            .register(meters);
  }

  @Override
  public void beforeRetry(RetryPolicy retryPolicy, Retryable<?> retryable) {
    retries.increment();
  }
}
