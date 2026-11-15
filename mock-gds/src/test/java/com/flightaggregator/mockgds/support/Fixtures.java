package com.flightaggregator.mockgds.support;

import com.flightaggregator.mockgds.dataset.Dataset;
import com.flightaggregator.mockgds.dataset.Defect;
import com.flightaggregator.mockgds.dataset.GeneratorConfig;
import com.flightaggregator.mockgds.dataset.MalformedLeg;
import com.flightaggregator.mockgds.dataset.ProviderRecord;
import com.flightaggregator.mockgds.dataset.ValidLeg;
import com.flightaggregator.mockgds.model.Leg;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.pricing.PriceModel;
import com.flightaggregator.mockgds.reference.ReferenceData;
import com.flightaggregator.mockgds.serving.InventoryPage;
import com.flightaggregator.mockgds.serving.PricedRecord;
import com.flightaggregator.mockgds.serving.ProviderInventory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** The serializers' fixture, one leg written in each provider's format, and small inventories. */
public final class Fixtures {

  private Fixtures() {}

  /** IB3170 from Madrid to London, which arrives at a UTC airport, at 129.90. */
  public static Leg ib3170(Provider provider) {
    return new Leg(
        "IB",
        3170,
        "MAD",
        "LHR",
        OffsetDateTime.parse("2026-11-20T08:15:00+01:00"),
        OffsetDateTime.parse("2026-11-20T09:30:00Z"),
        new BigDecimal("129.90"),
        provider);
  }

  /** The leg as a valid record at its own price. */
  public static PricedRecord priced(Leg leg) {
    return new PricedRecord(new ValidLeg(leg), leg.priceEur());
  }

  /** The valid leg, then the same leg once per defect, in the order of {@link Defect}. */
  public static InventoryPage pageWithEveryDefect(Provider provider) {
    var leg = ib3170(provider);
    var records = new ArrayList<PricedRecord>();
    records.add(priced(leg));
    for (var defect : Defect.values()) {
      records.add(new PricedRecord(new MalformedLeg(leg, defect), leg.priceEur()));
    }
    return new InventoryPage(records, 0, records.size());
  }

  /** An inventory that serves only these records, priced as the mock prices them. */
  public static ProviderInventory inventoryOf(List<ProviderRecord> records) {
    var config = GeneratorConfig.defaults();
    return new ProviderInventory(
        new Dataset(records),
        new PriceModel(config.seed(), config.priceBucket(), ReferenceData.standard()),
        Clock.systemUTC());
  }
}
