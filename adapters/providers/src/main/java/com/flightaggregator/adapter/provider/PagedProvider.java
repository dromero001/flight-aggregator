package com.flightaggregator.adapter.provider;

import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * A provider downloaded page by page (§6.1, §6.7): each page is requested in the provider's own
 * format, fetched by its HTTP client and parsed, under the bounds of {@link PagedDownload}. Every
 * part is built from the endpoint, so the provider is named once: by its endpoint.
 */
public final class PagedProvider implements FlightProvider {

  private final Provider provider;
  private final ProviderHttp http;
  private final PagedDownload paging;
  private final PageFormat format;
  private final int pageSize;

  /** The endpoint's provider, its breaker the registry's one named after it. */
  public PagedProvider(
      ProviderEndpoint endpoint,
      ResilienceSettings settings,
      CircuitBreakerRegistry circuitBreakers,
      Clock clock) {
    this.provider = endpoint.provider();
    this.http = new ProviderHttp(endpoint, settings, circuitBreakers, clock);
    this.paging = new PagedDownload(provider, settings);
    this.format = formatOf(provider);
    this.pageSize = settings.pageSize();
  }

  /** Alpha pages by cursor, Beta by page number, Gamma by offset. */
  private static PageFormat formatOf(Provider provider) {
    return switch (provider) {
      case ALPHA -> AlphaPage::at;
      case BETA -> BetaPage::at;
      case GAMMA -> GammaPage::at;
    };
  }

  @Override
  public Provider provider() {
    return provider;
  }

  @Override
  public ProviderDownload download() {
    return paging.download(
        position -> {
          PageRequest request = format.at(position, pageSize);
          return request.parse(http.get(request.pathTemplate(), request.variables()));
        });
  }
}
