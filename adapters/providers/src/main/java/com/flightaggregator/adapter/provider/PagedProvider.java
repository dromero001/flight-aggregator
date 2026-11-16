package com.flightaggregator.adapter.provider;

import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import org.springframework.core.retry.RetryListener;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.ConcurrencyLimit.ThrottlePolicy;
import org.springframework.util.ConcurrencyThrottleSupport;
import org.springframework.web.client.RestClient;

/**
 * A provider downloaded page by page (§6.1, §6.7): each page is requested in the provider's own
 * format, fetched by its HTTP client and parsed, under the bounds of {@link PagedDownload}. Every
 * part is built from the endpoint, so the provider is named once: by its endpoint.
 *
 * <p>Its origin downloads and quotes share one limit, {@code providers.concurrency-limit} at once
 * (Q9), applied when the bean is proxied ({@code @EnableResilientMethods}); a run's whole download
 * is outside it (I2). A call over it is rejected at once with an {@link
 * org.springframework.resilience.InvocationRejectedException}, a {@link
 * java.util.concurrent.RejectedExecutionException}. It is not {@code final}: Boot proxies it by
 * subclassing, and an interface proxy would not see the exemptions of {@link #download()}, {@link
 * #provider()} and {@link #toString()}.
 */
@ConcurrencyLimit(limitString = "${providers.concurrency-limit}", policy = ThrottlePolicy.REJECT)
public class PagedProvider implements FlightProvider {

  private final Provider provider;
  private final ProviderHttp http;
  private final PagedDownload paging;
  private final PageFormat format;
  private final int pageSize;

  /**
   * The endpoint's provider: its breaker is the registry's one named after it, and {@code retries}
   * hears of each retry of its requests. Its requests are built from {@code http}, and a download's
   * page loop runs on a thread of {@code pages}.
   */
  public PagedProvider(
      ProviderEndpoint endpoint,
      ResilienceSettings settings,
      CircuitBreakerRegistry circuitBreakers,
      RetryListener retries,
      Clock clock,
      RestClient.Builder http,
      ThreadFactory pages) {
    this.provider = endpoint.provider();
    this.http = new ProviderHttp(endpoint, settings, circuitBreakers, retries, clock, http);
    this.paging = new PagedDownload(provider, settings, pages);
    this.format = formatOf(provider);
    this.pageSize = settings.pageSize();
  }

  /** Alpha pages by cursor, Beta by page number, Gamma by offset. */
  private static PageFormat formatOf(Provider provider) {
    return switch (provider) {
      case ALPHA -> AlphaPage.FORMAT;
      case BETA -> BetaPage.FORMAT;
      case GAMMA -> GammaPage.FORMAT;
    };
  }

  /** Never limited: a caller finds the provider by it, so a rejection must never fail it. */
  @Override
  @ConcurrencyLimit(ConcurrencyThrottleSupport.UNBOUNDED_CONCURRENCY)
  public Provider provider() {
    return provider;
  }

  /** Never limited, as {@link #provider()}: a log line may print the provider at any time. */
  @Override
  @ConcurrencyLimit(ConcurrencyThrottleSupport.UNBOUNDED_CONCURRENCY)
  public String toString() {
    return "PagedProvider[" + provider + "]";
  }

  /**
   * Never limited (plan 5's end-of-plan review, I2): a run downloads each provider once, under its
   * own lock, and refreshes and re-prices taking every call of the limit must never make it keep a
   * healthy provider's old offers.
   */
  @Override
  @ConcurrencyLimit(ConcurrencyThrottleSupport.UNBOUNDED_CONCURRENCY)
  public ProviderDownload download() {
    return downloadOf(Optional.empty());
  }

  @Override
  public ProviderDownload downloadFrom(Airport origin) {
    return downloadOf(Optional.of(origin));
  }

  /**
   * One request, under the HTTP client's breaker, retries and request timeout; the answer's arrival
   * is its legs' {@code quotedAt}. A page that is not of the provider's format, or is not of the
   * flight asked for, fails it with {@code MALFORMED_PAGE}, as a download's page does.
   */
  @Override
  public Optional<Leg> quote(LegKey flight) {
    try {
      return quoted(flight, fetch(format.quoting(flight)));
    } catch (PageMalformed e) {
      throw new ProviderUnavailableException(provider, FailureReason.MALFORMED_PAGE, e);
    }
  }

  /**
   * The page's record of the flight, none when it no longer sells it. The quote's page holds one
   * record at most ({@link PageFormat#quoting}), so a second one has already failed it.
   */
  private static Optional<Leg> quoted(LegKey flight, ParsedPage page) {
    if (!page.discarded().isEmpty()) {
      throw new PageMalformed(
          "Asked for the quote of "
              + flight
              + ", got a record that is not valid: "
              + page.discarded().getFirst().reason());
    }
    Optional<Leg> leg = page.legs().stream().findFirst();
    if (leg.isPresent() && !leg.get().key().equals(flight)) {
      throw new PageMalformed("Asked for the quote of " + flight + ", got " + leg.get().key());
    }
    return leg;
  }

  /** One origin's legs, or every leg, through the same bounds of {@link PagedDownload}. */
  private ProviderDownload downloadOf(Optional<Airport> origin) {
    return paging.download(position -> fetch(format.at(origin, position, pageSize)));
  }

  private ParsedPage fetch(PageRequest request) {
    return request.parse(http.get(request.pathTemplate(), request.variables()));
  }
}
