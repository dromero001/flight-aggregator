package com.flightaggregator.bootstrap.observability;

import com.flightaggregator.application.port.out.DiscardReason;
import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.DiscardedRecords;
import com.flightaggregator.domain.Provider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Each discarded record is one {@code WARN} line, with its provider, reason and excerpt as
 * structured fields (§6.7, §6.10), and one count of {@code ingestion.records.discarded} under its
 * provider and reason. The excerpt arrives cut and with its control characters escaped ({@link
 * DiscardedRecord}), so provider text cannot forge a log line. Every provider and reason is
 * registered at zero here, so Prometheus's {@code increase} sees the startup run's discards
 * (E-T6-1).
 */
public final class LoggedDiscardedRecords implements DiscardedRecords {

  private static final Logger LOG = LoggerFactory.getLogger(LoggedDiscardedRecords.class);

  private final MeterRegistry meters;

  public LoggedDiscardedRecords(MeterRegistry meters) {
    this.meters = meters;
    for (Provider provider : Provider.values()) {
      for (DiscardReason reason : DiscardReason.values()) {
        discarded(provider, reason);
      }
    }
  }

  @Override
  public void discarded(Provider provider, DiscardedRecord record) {
    LOG.atWarn()
        .addKeyValue("provider", provider.name())
        .addKeyValue("reason", record.reason().name())
        .addKeyValue("excerpt", record.excerpt())
        .log("Discarded a provider record");
    discarded(provider, record.reason()).increment();
  }

  private Counter discarded(Provider provider, DiscardReason reason) {
    return meters.counter(
        "ingestion.records.discarded", "provider", provider.name(), "reason", reason.name());
  }
}
