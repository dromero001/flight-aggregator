package com.flightaggregator.adapter.provider.alpha;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.adapter.provider.PagedDownload;
import com.flightaggregator.adapter.provider.ProviderHttp;
import com.flightaggregator.adapter.provider.ProviderHttp.FetchedPage;
import com.flightaggregator.adapter.provider.ResilienceSettings;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.domain.Provider;

/** Alpha: a flat JSON list of legs, paged by an opaque cursor that is {@code null} at the end. */
public final class AlphaProvider implements FlightProvider {

  private final ProviderHttp http;
  private final PagedDownload paging;
  private final ResilienceSettings settings;

  public AlphaProvider(ProviderHttp http, PagedDownload paging, ResilienceSettings settings) {
    this.http = requireNonNull(http, "http");
    this.paging = requireNonNull(paging, "paging");
    this.settings = requireNonNull(settings, "settings");
  }

  @Override
  public Provider provider() {
    return Provider.ALPHA;
  }

  @Override
  public ProviderDownload download() {
    return paging.download(
        cursor -> {
          FetchedPage fetched =
              cursor.isEmpty()
                  ? http.get("/alpha/legs?limit={limit}", settings.pageSize())
                  : http.get(
                      "/alpha/legs?limit={limit}&cursor={cursor}",
                      settings.pageSize(),
                      cursor.get());
          return AlphaPageParser.parse(fetched.body(), fetched.receivedAt());
        });
  }
}
