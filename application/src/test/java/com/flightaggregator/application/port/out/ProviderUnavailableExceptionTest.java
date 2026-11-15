package com.flightaggregator.application.port.out;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.flightaggregator.domain.Provider;
import org.junit.jupiter.api.Test;

class ProviderUnavailableExceptionTest {

  @Test
  void requiresAProvider() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ProviderUnavailableException(null, FailureReason.TIMEOUT, null))
        .withMessage("provider");
  }

  @Test
  void requiresAReason() {
    assertThatNullPointerException()
        .isThrownBy(() -> new ProviderUnavailableException(Provider.ALPHA, null, null))
        .withMessage("reason");
  }
}
