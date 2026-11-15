package com.flightaggregator.mockgds.serving;

import com.flightaggregator.mockgds.dataset.Dataset;
import com.flightaggregator.mockgds.dataset.ProviderRecord;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.pricing.PriceModel;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * What each provider serves: its records in dataset order, page by page, each priced at the instant
 * of the request. A malformed record keeps its defect and gets the drifted price of its leg, and
 * {@link RenderedLeg} applies the defect.
 */
public final class ProviderInventory {

  private final Dataset dataset;
  private final PriceModel prices;
  private final Clock clock;

  public ProviderInventory(Dataset dataset, PriceModel prices, Clock clock) {
    this.dataset = Objects.requireNonNull(dataset);
    this.prices = Objects.requireNonNull(prices);
    this.clock = Objects.requireNonNull(clock);
  }

  /**
   * At most {@code limit} records of the provider that the filter keeps, starting at {@code offset}
   * of the filtered list.
   */
  public InventoryPage page(Provider provider, RecordFilter filter, int offset, int limit) {
    if (offset < 0 || limit < 1) {
      throw new IllegalArgumentException(
          "Offset must not be negative and limit must be positive: " + offset + ", " + limit);
    }
    List<ProviderRecord> all =
        dataset.recordsFor(provider).stream().filter(filter::matches).toList();
    var now = clock.instant();
    int from = Math.min(offset, all.size());
    int to = (int) Math.min((long) offset + limit, all.size());
    var records =
        all.subList(from, to).stream()
            .map(
                record -> new PricedRecord(record, prices.price(record.leg().key(), provider, now)))
            .toList();
    return new InventoryPage(records, offset, all.size());
  }
}
