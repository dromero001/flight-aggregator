package com.flightaggregator.adapter.provider.gamma;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.adapter.provider.PagedDownload;
import com.flightaggregator.adapter.provider.ProviderHttp;
import com.flightaggregator.adapter.provider.ProviderHttp.FetchedPage;
import com.flightaggregator.adapter.provider.ResilienceSettings;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.domain.Provider;

/** Gamma: CSV with a header row, paged by offset and limit, ending on a short page. */
public final class GammaProvider implements FlightProvider {

  private final ProviderHttp http;
  private final PagedDownload paging;
  private final ResilienceSettings settings;

  public GammaProvider(ProviderHttp http, PagedDownload paging, ResilienceSettings settings) {
    this.http = requireNonNull(http, "http");
    this.paging = requireNonNull(paging, "paging");
    this.settings = requireNonNull(settings, "settings");
  }

  @Override
  public Provider provider() {
    return Provider.GAMMA;
  }

  @Override
  public ProviderDownload download() {
    return paging.download(
        position -> {
          int offset = position.map(Integer::parseInt).orElse(0);
          int limit = settings.pageSize();
          FetchedPage fetched =
              http.get("/gamma/legs.csv?offset={offset}&limit={limit}", offset, limit);
          return GammaPageParser.parse(fetched.body(), fetched.receivedAt(), offset, limit);
        });
  }
}
