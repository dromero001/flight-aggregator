package com.flightaggregator.mockgds;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.serving.ProviderInventory;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "mockgds.providers.alpha.api-key=test-alpha-key",
      "mockgds.providers.beta.api-key=test-beta-key",
      "mockgds.providers.gamma.api-key=test-gamma-key"
    })
@AutoConfigureTestRestTemplate
class MockGdsApplicationTest {

  @Autowired private TestRestTemplate http;
  @Autowired private ProviderInventory inventory;

  @Test
  void startsAndReportsHealthUp() {
    var health = http.getForEntity("/actuator/health", String.class);

    assertThat(health.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(health.getBody()).contains("\"status\":\"UP\"");
    Arrays.stream(Provider.values())
        .forEach(
            provider ->
                assertThat(inventory.page(provider, 0, 10).records())
                    .as(provider.name())
                    .isNotEmpty());
  }
}
