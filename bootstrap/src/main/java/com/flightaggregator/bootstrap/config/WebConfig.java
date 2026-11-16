package com.flightaggregator.bootstrap.config;

import com.flightaggregator.adapter.web.AdminController;
import com.flightaggregator.adapter.web.ProblemDetailSchema;
import com.flightaggregator.adapter.web.ProblemResponses;
import com.flightaggregator.adapter.web.RepriceController;
import com.flightaggregator.adapter.web.SearchController;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The HTTP adapter: its controllers, over the inbound ports, the problem responses of their errors,
 * and the contract's name and problem schema; the rate limit in front of them is {@link
 * RateLimitConfig}'s. The adapter's package is not scanned, so each class is named here, as the
 * stores are in {@code PersistenceConfig}.
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
}
