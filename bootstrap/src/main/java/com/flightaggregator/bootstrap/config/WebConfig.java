package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.web.ProblemResponses;
import com.flightaggregator.adapter.web.RateLimitInterceptor;
import com.flightaggregator.adapter.web.RepriceController;
import com.flightaggregator.adapter.web.SearchController;
import com.flightaggregator.application.port.in.AdmitRequest;
import com.flightaggregator.application.port.in.RateLimit;
import com.flightaggregator.application.port.in.RateLimitedAction;
import com.flightaggregator.application.port.out.RequestCounter;
import com.flightaggregator.application.service.RateLimitService;
import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The HTTP adapter: its controllers, over the inbound ports, the problem responses of their errors,
 * and the rate limit in front of them, over Valkey's request counters (§6.6). The adapter's package
 * is not scanned, so each class is named here, as the stores are in {@code PersistenceConfig}.
 */
@Configuration(proxyBeanMethods = false)
@Import({SearchController.class, RepriceController.class, ProblemResponses.class})
class WebConfig {

  /**
   * Q9: a person paging and changing filters stays well under 60 searches a minute; each re-price
   * calls the providers (the look-to-book cost, §6.2), so it has 10 a minute, on its own counter.
   */
  private static final Map<RateLimitedAction, RateLimit> LIMITS =
      Map.of(
          RateLimitedAction.SEARCH, new RateLimit(60, Duration.ofMinutes(1)),
          RateLimitedAction.REPRICE, new RateLimit(10, Duration.ofMinutes(1)));

  @Bean
  AdmitRequest admitRequest(RequestCounter counter) {
    return new RateLimitService(counter, LIMITS);
  }

  @Bean
  WebMvcConfigurer rateLimit(AdmitRequest admitRequest) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(admitRequest));
      }
    };
  }
}
