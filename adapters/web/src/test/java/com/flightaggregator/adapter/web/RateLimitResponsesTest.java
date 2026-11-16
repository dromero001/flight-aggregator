package com.flightaggregator.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.application.port.in.RateLimitedAction;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

/**
 * The 429 of a rate-limited endpoint is documented from the same {@link RateLimited} annotation
 * that makes the interceptor count it, so a new limited endpoint is documented without a copied
 * block (plan 5 Task 10's review, C-m1).
 */
class RateLimitResponsesTest {

  /** Two limited endpoints and one with no limit. */
  static final class Endpoints {

    @RateLimited(RateLimitedAction.SEARCH)
    void search() {}

    @RateLimited(RateLimitedAction.REPRICE)
    void reprice() {}

    void status() {}
  }

  private static Operation customized(String endpoint) throws NoSuchMethodException {
    HandlerMethod handler =
        new HandlerMethod(new Endpoints(), Endpoints.class.getDeclaredMethod(endpoint));
    return new RateLimitResponses()
        .customize(new Operation().responses(new ApiResponses()), handler);
  }

  @Test
  void documentsA429WithRetryAfterAndAProblemOnALimitedEndpoint() throws Exception {
    ApiResponse tooMany = customized("search").getResponses().get("429");

    assertThat(tooMany).isNotNull();
    assertThat(tooMany.getDescription())
        .isEqualTo("Too many searches from this address; try again after Retry-After");
    Header retryAfter = tooMany.getHeaders().get("Retry-After");
    assertThat(retryAfter.getRequired()).isTrue();
    assertThat(retryAfter.getDescription())
        .isEqualTo("How many seconds to wait before asking again");
    Schema<?> seconds = retryAfter.getSchema();
    assertThat(seconds.getTypes()).isEqualTo(Set.of("integer"));
    assertThat(seconds.getMinimum()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(tooMany.getContent().get("application/problem+json").getSchema().get$ref())
        .isEqualTo("#/components/schemas/ProblemDetail");
  }

  @Test
  void namesTheActionItCounts() throws Exception {
    assertThat(customized("reprice").getResponses().get("429").getDescription())
        .isEqualTo("Too many re-prices from this address; try again after Retry-After");
  }

  @Test
  void leavesAnEndpointWithNoLimitAsItIs() throws Exception {
    assertThat(customized("status").getResponses()).isEmpty();
  }
}
