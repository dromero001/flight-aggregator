package com.flightaggregator.adapter.provider;

import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Predicate;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
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
 * size, behind a circuit breaker. It is built from the application's {@link RestClient.Builder}, so
 * each request is observed and carries the caller's trace as W3C {@code traceparent} (§6.10).
 */
public final class ProviderHttp {

  private static final CircuitBreakerConfig BREAKER =
      CircuitBreakerConfig.custom()
          .slidingWindow(10, 10, SlidingWindowType.COUNT_BASED)
          .failureRateThreshold(50)
          .waitDurationInOpenState(Duration.ofSeconds(60))
          .permittedNumberOfCallsInHalfOpenState(3)
          .recordException(ProviderHttp::isTransient)
          .ignoreException(Predicate.not(ProviderHttp::isTransient))
          .build();

  /** One page's body, the provider that sent it and the instant its response arrived. */
  record FetchedPage(String body, Provider provider, Instant receivedAt) {}

  private final RestClient client;
  private final Provider provider;
  private final CircuitBreaker circuitBreaker;
  private final RetryTemplate retry;
  private final Duration requestTimeout;
  private final int maxBodyBytes;
  private final Clock clock;

  /**
   * Its breaker is the registry's one named after the endpoint's provider; {@code retries} hears of
   * each retry, not of the first attempt. {@code http} is cloned, so the shared builder is left as
   * it was.
   */
  ProviderHttp(
      ProviderEndpoint endpoint,
      ResilienceSettings settings,
      CircuitBreakerRegistry circuitBreakers,
      RetryListener retries,
      Clock clock,
      RestClient.Builder http) {
    JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory();
    requests.setReadTimeout(settings.requestTimeout());
    this.client =
        http.clone()
            .requestFactory(requests)
            .baseUrl(endpoint.baseUrl().toString())
            .defaultHeader("X-Api-Key", endpoint.apiKey())
            .build();
    this.provider = endpoint.provider();
    this.circuitBreaker = circuitBreakers.circuitBreaker(provider.name());
    this.retry =
        new RetryTemplate(
            RetryPolicy.builder()
                .maxRetries(settings.maxAttempts() - 1L)
                .delay(settings.firstBackoff())
                .multiplier(2)
                .predicate(ProviderHttp::isTransient)
                .build());
    this.retry.setRetryListener(retries);
    this.requestTimeout = settings.requestTimeout();
    this.maxBodyBytes = settings.maxBodyBytes();
    this.clock = clock;
  }

  /**
   * The configuration of every provider's breaker (plan 4): over its last 10 calls, it opens at 50%
   * failures, stays open 60 s, then lets 3 trial calls through. It counts only the transient
   * failures, the ones the retry repeats, and ignores every other failure.
   */
  public static CircuitBreakerConfig circuitBreakerConfig() {
    return BREAKER;
  }

  /**
   * Whether a failed attempt is transient, so another attempt may succeed: a server error, a
   * timeout or an I/O error. The retry repeats only these, and each provider's breaker counts only
   * these. A refused key, another client error, a redirect or a body over the limit comes back the
   * same until the configuration or the provider changes, so the breaker ignores it: opening on it
   * would only hide the cause, and counting it as a success would hide an outage.
   */
  private static boolean isTransient(Throwable failure) {
    return failure instanceof AttemptFailed attempt && attempt.retryable();
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
    return new FetchedPage(body, provider, clock.now().truncatedTo(ChronoUnit.MICROS));
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
      throw AttemptFailed.retryable(
          timedOut ? FailureReason.TIMEOUT : FailureReason.HTTP_ERROR,
          "No response: " + e.getMessage(),
          e);
    }
  }

  /** Reads at most {@code maxBodyBytes + 1} bytes, so a larger body is never held in memory. */
  private String read(HttpRequest request, ClientHttpResponse response) throws IOException {
    HttpStatusCode status = response.getStatusCode();
    if (status.is5xxServerError()) {
      throw AttemptFailed.retryable(FailureReason.HTTP_ERROR, "Server error " + status, null);
    }
    if (status.value() == 401 || status.value() == 403) {
      throw AttemptFailed.permanent(FailureReason.UNAUTHORIZED, "Refused with " + status);
    }
    if (!status.is2xxSuccessful()) {
      throw AttemptFailed.permanent(FailureReason.HTTP_ERROR, "Unexpected " + status);
    }
    byte[] body = response.getBody().readNBytes(maxBodyBytes + 1);
    if (body.length > maxBodyBytes) {
      throw AttemptFailed.permanent(
          FailureReason.MALFORMED_PAGE, "The body is over " + maxBodyBytes + " bytes");
    }
    return new String(body, StandardCharsets.UTF_8);
  }

  /** How one attempt failed, and whether another attempt may help. */
  private static final class AttemptFailed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureReason reason;
    private final boolean retryable;

    private AttemptFailed(
        FailureReason reason, boolean retryable, String message, Throwable cause) {
      super(message, cause);
      this.reason = reason;
      this.retryable = retryable;
    }

    /** Another attempt may succeed. */
    static AttemptFailed retryable(FailureReason reason, String message, Throwable cause) {
      return new AttemptFailed(reason, true, message, cause);
    }

    /** Another attempt would fail the same way. */
    static AttemptFailed permanent(FailureReason reason, String message) {
      return new AttemptFailed(reason, false, message, null);
    }

    boolean retryable() {
      return retryable;
    }
  }
}
