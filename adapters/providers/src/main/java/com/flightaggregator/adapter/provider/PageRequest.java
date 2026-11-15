package com.flightaggregator.adapter.provider;

import com.flightaggregator.adapter.provider.ProviderHttp.FetchedPage;

/** One page to fetch from a provider: where it is, and how its response is read. */
interface PageRequest {

  /** A URI template, into which each of {@link #variables()} is encoded. */
  String pathTemplate();

  Object[] variables();

  /**
   * The page's valid legs and discarded records, and the position of the next page.
   *
   * @throws PageMalformed when the page is not of the provider's format
   */
  ParsedPage parse(FetchedPage page);
}
