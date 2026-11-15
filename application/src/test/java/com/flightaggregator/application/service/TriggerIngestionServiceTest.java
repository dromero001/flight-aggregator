package com.flightaggregator.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.application.port.in.TriggerIngestion.TriggerOutcome;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TriggerIngestionServiceTest {

  /** A run starts unless another one holds the ingestion's lock. */
  @ParameterizedTest
  @CsvSource({"true, STARTED", "false, ALREADY_RUNNING"})
  void startsARunUnlessOneIsRunning(boolean launched, TriggerOutcome outcome) {
    assertThat(new TriggerIngestionService(() -> launched).trigger()).isEqualTo(outcome);
  }
}
