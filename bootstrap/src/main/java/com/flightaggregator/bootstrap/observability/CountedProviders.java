package com.flightaggregator.bootstrap.observability;

import com.flightaggregator.application.port.out.FlightProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Wraps each {@link FlightProvider} bean in a {@link CountedFlightProvider}. It is not {@code
 * Ordered}, so Spring runs it after every ordered post-processor, the one that proxies the provider
 * for its {@code @ConcurrencyLimit} included: the count wraps the limit and sees what a caller
 * sees, a rejected call too. The meter registry is looked up at the first provider, so declaring
 * this post-processor does not create it early.
 */
public final class CountedProviders implements BeanPostProcessor {

  private final ObjectProvider<MeterRegistry> meters;

  public CountedProviders(ObjectProvider<MeterRegistry> meters) {
    this.meters = meters;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    return bean instanceof FlightProvider provider
        ? new CountedFlightProvider(provider, meters.getObject())
        : bean;
  }
}
