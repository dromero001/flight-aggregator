package com.flightaggregator.mockgds.serving;

import java.util.List;

/**
 * One page of a provider's inventory.
 *
 * @param records the records of the page, in the dataset's order
 * @param offset position of the first record of the page in the provider's inventory
 * @param total size of the provider's whole inventory
 */
public record InventoryPage(List<PricedRecord> records, int offset, int total) {

  public InventoryPage {
    records = List.copyOf(records);
  }

  public boolean isLast() {
    return offset + records.size() >= total;
  }
}
