package com.flightaggregator.adapter.provider.beta;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.adapter.provider.PagedDownload;
import com.flightaggregator.adapter.provider.ProviderHttp;
import com.flightaggregator.adapter.provider.ProviderHttp.FetchedPage;
import com.flightaggregator.adapter.provider.ResilienceSettings;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.domain.Provider;

/** Beta: nested XML segments, paged by page number from 1, with the page count on every page. */
public final class BetaProvider implements FlightProvider {

  private final ProviderHttp http;
  private final PagedDownload paging;
  private final ResilienceSettings settings;

  public BetaProvider(ProviderHttp http, PagedDownload paging, ResilienceSettings settings) {
    this.http = requireNonNull(http, "http");
    this.paging = requireNonNull(paging, "paging");
    this.settings = requireNonNull(settings, "settings");
  }

  @Override
  public Provider provider() {
    return Provider.BETA;
  }

  @Override
  public ProviderDownload download() {
    return paging.download(
        position -> {
          FetchedPage fetched =
              http.get(
                  "/beta/segments?page={page}&pageSize={pageSize}",
                  position.orElse("1"),
                  settings.pageSize());
          return BetaPageParser.parse(fetched.body(), fetched.receivedAt());
        });
  }
}
