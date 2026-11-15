package com.flightaggregator.adapter.provider;

import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
  private final Duration requestTimeout;
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
                .multiplier(2)
                .predicate(
                    failure -> failure instanceof AttemptFailed attempt && attempt.retryable())
                .build());
    this.requestTimeout = settings.requestTimeout();
    this.maxBodyBytes = settings.maxBodyBytes();
    this.clock = clock;
  }

  /**
   * Gets a page. The path is a URI template and each variable is encoded into it, so a cursor with
   * {@code +}, {@code &} or {@code #} reaches the provider as one value.
   */
  FetchedPage get(String pathTemplate, Object... variables) {
    String body;
    try {
      body =
          retry.execute(
              () -> circuitBreaker.executeSupplier(() -> download(pathTemplate, variables)));
    } catch (RetryException e) {
      throw unavailable(e);
    }
    return new FetchedPage(body, clock.now().truncatedTo(ChronoUnit.MICROS));
  }

  /**
   * Classifies the end of the retries. The {@link RetryException} is the cause, so the earlier
   * attempts stay in it as suppressed exceptions. A failure of any other kind propagates.
   */
  private ProviderUnavailableException unavailable(RetryException exhausted) {
    return switch (exhausted.getCause()) {
      case AttemptFailed failed ->
          new ProviderUnavailableException(provider, failed.reason, exhausted);
      case CallNotPermittedException open ->
          new ProviderUnavailableException(provider, FailureReason.CIRCUIT_OPEN, exhausted);
      case RuntimeException other -> throw other;
      case Error other -> throw other;
      default -> throw new IllegalStateException(exhausted);
    };
  }

  /**
   * One attempt. Each failure is classified here, where it happens, as an {@link AttemptFailed}.
   */
  private String download(String pathTemplate, Object... variables) {
    long started = System.nanoTime();
    try {
      return client.get().uri(pathTemplate, variables).exchange(this::read);
    } catch (ResourceAccessException e) {
      // The JDK client reports a timeout as an I/O error, and so does a body cut mid-stream.
      boolean timedOut = System.nanoTime() - started >= requestTimeout.toNanos();
      throw new AttemptFailed(
          timedOut ? FailureReason.TIMEOUT : FailureReason.HTTP_ERROR,
          true,
          "No response: " + e.getMessage(),
          e);
    }
  }

  /** Reads at most {@code maxBodyBytes + 1} bytes, so a larger body is never held in memory. */
  private String read(HttpRequest request, ClientHttpResponse response) throws IOException {
    HttpStatusCode status = response.getStatusCode();
    if (status.is5xxServerError()) {
      throw new AttemptFailed(FailureReason.HTTP_ERROR, true, "Server error " + status, null);
    }
    if (status.value() == 401 || status.value() == 403) {
      throw new AttemptFailed(FailureReason.UNAUTHORIZED, false, "Refused with " + status, null);
    }
    if (!status.is2xxSuccessful()) {
      throw new AttemptFailed(FailureReason.HTTP_ERROR, false, "Unexpected " + status, null);
    }
    byte[] body = response.getBody().readNBytes(maxBodyBytes + 1);
    if (body.length > maxBodyBytes) {
      throw new AttemptFailed(
          FailureReason.MALFORMED_PAGE, false, "The body is over " + maxBodyBytes + " bytes", null);
    }
    return new String(body, StandardCharsets.UTF_8);
  }

  /** How one attempt failed, and whether another attempt may help. */
  private static final class AttemptFailed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureReason reason;
    private final boolean retryable;

    AttemptFailed(FailureReason reason, boolean retryable, String message, Throwable cause) {
      super(message, cause);
      this.reason = reason;
      this.retryable = retryable;
    }

    boolean retryable() {
      return retryable;
    }
  }
}
