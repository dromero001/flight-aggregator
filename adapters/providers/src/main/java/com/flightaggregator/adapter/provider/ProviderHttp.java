package com.flightaggregator.adapter.provider;

import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The HTTP client of one provider: its API key on every request, bounded in time, retries and body
 * size, behind a circuit breaker.
 */
final class ProviderHttp {

  /** One page's body and the instant its response arrived. */
  record FetchedPage(String body, Instant receivedAt) {}

  private final RestClient client;
  private final Provider provider;
  private final CircuitBreaker circuitBreaker;
  private final RetryTemplate retry;
  private final int maxBodyBytes;
  private final Clock clock;

  ProviderHttp(
      ProviderEndpoint endpoint,
      ResilienceSettings settings,
      CircuitBreaker circuitBreaker,
      Clock clock) {
    JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory();
    requests.setReadTimeout(settings.requestTimeout());
    this.client =
        RestClient.builder()
            .requestFactory(requests)
            .baseUrl(endpoint.baseUrl().toString())
            .defaultHeader("X-Api-Key", endpoint.apiKey())
            .build();
    this.provider = endpoint.provider();
    this.circuitBreaker = circuitBreaker;
    this.retry =
        new RetryTemplate(
            RetryPolicy.builder()
                .maxRetries(settings.maxAttempts() - 1L)
                .delay(settings.firstBackoff())
                .includes(HttpServerErrorException.class, ResourceAccessException.class)
                .build());
    this.maxBodyBytes = settings.maxBodyBytes();
    this.clock = clock;
  }

  FetchedPage get(String pathAndQuery) {
    String body;
    try {
      body = retry.execute(() -> circuitBreaker.executeSupplier(() -> download(pathAndQuery)));
    } catch (RetryException e) {
      throw new ProviderUnavailableException(provider, reasonOf(e.getCause()), e.getCause());
    }
    return new FetchedPage(body, clock.now().truncatedTo(ChronoUnit.MICROS));
  }

  /** Reads at most {@code maxBodyBytes + 1} bytes, so a larger body is never held in memory. */
  private String download(String pathAndQuery) {
    return client
        .get()
        .uri(pathAndQuery)
        .exchange(
            (request, response) -> {
              HttpStatusCode status = response.getStatusCode();
              if (status.is5xxServerError()) {
                throw HttpServerErrorException.create(
                    status, response.getStatusText(), response.getHeaders(), new byte[0], null);
              }
              if (!status.is2xxSuccessful()) {
                throw HttpClientErrorException.create(
                    status, response.getStatusText(), response.getHeaders(), new byte[0], null);
              }
              byte[] body = response.getBody().readNBytes(maxBodyBytes + 1);
              if (body.length > maxBodyBytes) {
                throw new BodyTooLargeException(maxBodyBytes);
              }
              return new String(body, StandardCharsets.UTF_8);
            });
  }

  private static FailureReason reasonOf(Throwable failure) {
    if (failure instanceof BodyTooLargeException) {
      return FailureReason.MALFORMED_PAGE;
    }
    if (failure instanceof CallNotPermittedException) {
      return FailureReason.CIRCUIT_OPEN;
    }
    if (failure instanceof HttpClientErrorException rejected
        && (rejected.getStatusCode() == HttpStatus.UNAUTHORIZED
            || rejected.getStatusCode() == HttpStatus.FORBIDDEN)) {
      return FailureReason.UNAUTHORIZED;
    }
    if (failure instanceof ResourceAccessException io
        && (io.getCause() instanceof HttpTimeoutException
            || io.getCause() instanceof SocketTimeoutException)) {
      return FailureReason.TIMEOUT;
    }
    return FailureReason.HTTP_ERROR;
  }

  private static final class BodyTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    BodyTooLargeException(int limit) {
      super("The response body is over " + limit + " bytes");
    }
  }
}
