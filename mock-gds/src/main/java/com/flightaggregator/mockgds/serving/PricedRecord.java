package com.flightaggregator.mockgds.serving;

import com.flightaggregator.mockgds.dataset.ProviderRecord;
import java.math.BigDecimal;

/** A provider record with the price its leg has at the request instant. */
public record PricedRecord(ProviderRecord record, BigDecimal price) {}
