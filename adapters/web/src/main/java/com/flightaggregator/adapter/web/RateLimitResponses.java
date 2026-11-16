package com.flightaggregator.adapter.web;

import com.flightaggregator.application.port.in.RateLimitedAction;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.math.BigDecimal;
import java.util.Set;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.web.method.HandlerMethod;

/**
 * Documents the 429 of every endpoint {@link RateLimited} marks, from the same annotation that has
 * {@link RateLimitInterceptor} count it: a problem body, and {@code Retry-After} in whole seconds
 * (§6.6, Q9). A new limited endpoint is documented with no block to copy (plan 5 Task 10's review,
 * C-m1).
 */
public final class RateLimitResponses implements OperationCustomizer {

  @Override
  public Operation customize(Operation operation, HandlerMethod handlerMethod) {
    RateLimited limited = handlerMethod.getMethodAnnotation(RateLimited.class);
    if (limited != null) {
      operation.getResponses().addApiResponse("429", tooMany(limited.value()));
    }
    return operation;
  }

  private static ApiResponse tooMany(RateLimitedAction action) {
    return new ApiResponse()
        .description(
            "Too many " + requestsOf(action) + " from this address; try again after Retry-After")
        .addHeaderObject(
            HttpHeaders.RETRY_AFTER,
            new Header()
                .required(true)
                .description("How many seconds to wait before asking again")
                .style(Header.StyleEnum.SIMPLE)
                .schema(new Schema<Integer>().types(Set.of("integer")).minimum(BigDecimal.ZERO)))
        .content(
            new Content()
                .addMediaType(
                    org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"))));
  }

  /** The requests one action counts, as a client reads them. */
  private static String requestsOf(RateLimitedAction action) {
    return switch (action) {
      case SEARCH -> "searches";
      case REPRICE -> "re-prices";
    };
  }
}
