package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.web.AdminController;
import com.flightaggregator.adapter.web.ProblemDetailSchema;
import com.flightaggregator.adapter.web.ProblemResponses;
import com.flightaggregator.adapter.web.RateLimitInterceptor;
import com.flightaggregator.adapter.web.RateLimitResponses;
import com.flightaggregator.adapter.web.RepriceController;
import com.flightaggregator.adapter.web.SearchController;
import com.flightaggregator.application.port.in.AdmitRequest;
import com.flightaggregator.application.port.in.RateLimit;
import com.flightaggregator.application.port.in.RateLimitedAction;
import com.flightaggregator.application.port.out.RequestCounter;
import com.flightaggregator.application.service.RateLimitService;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The HTTP adapter: its controllers, over the inbound ports, the problem responses of their errors,
 * and the rate limit in front of them, over Valkey's request counters (§6.6), with its 429 in the
 * contract. The adapter's package is not scanned, so each class is named here, as the stores are in
 * {@code PersistenceConfig}.
 */
@Configuration(proxyBeanMethods = false)
@Import({
  SearchController.class,
  RepriceController.class,
  AdminController.class,
  ProblemResponses.class
})
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

  /** The 429 of every rate-limited endpoint, documented from its {@code @RateLimited}. */
  @Bean
  OperationCustomizer rateLimitResponses() {
    return new RateLimitResponses();
  }

  /**
   * The contract's name, version and description, which springdoc merges with what it reads from
   * the controllers. Its one server is relative, the host that serves the contract, so Swagger UI
   * and any client try the API where they found it, not on the address it was generated on.
   */
  @Bean
  OpenAPI flightAggregatorApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Flight aggregator API")
                .version("1.0.0")
                .description(
                    "Searches the flights aggregated from every provider and re-prices a chosen"
                        + " itinerary live, both public and rate-limited per client address; with"
                        + " a token holding the ADMIN role, reads the inventory's and each"
                        + " provider's status and starts an ingestion."))
        .servers(List.of(new Server().url("/").description("The host serving this contract")));
  }

  /** The problem schema every error response refers to, as the API writes it. */
  @Bean
  OpenApiCustomizer problemDetailSchema() {
    return new ProblemDetailSchema();
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
