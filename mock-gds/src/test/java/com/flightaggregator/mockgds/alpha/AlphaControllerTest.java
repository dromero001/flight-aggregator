package com.flightaggregator.mockgds.alpha;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.serving.ProviderInventory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "mockgds.providers.alpha.api-key=test-alpha-key",
      "mockgds.providers.beta.api-key=test-beta-key",
      "mockgds.providers.gamma.api-key=test-gamma-key",
      "mockgds.providers.alpha.latency-min=0",
      "mockgds.providers.alpha.latency-max=0",
      "mockgds.providers.beta.latency-min=0",
      "mockgds.providers.beta.latency-max=0",
      "mockgds.providers.gamma.latency-min=0",
      "mockgds.providers.gamma.latency-max=0",
      "mockgds.providers.alpha.error-rate=0",
      "mockgds.providers.beta.error-rate=0",
      "mockgds.providers.gamma.error-rate=0"
    })
@AutoConfigureTestRestTemplate
class AlphaControllerTest {

  @Autowired private TestRestTemplate http;
  @Autowired private ProviderInventory inventory;

  @Test
  void servesTheFirstPageWithoutACursor() throws Exception {
    var response = get("/alpha/legs");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    var body = new ObjectMapper().readTree(response.getBody());
    assertThat(body.get("legs")).hasSize(500);
    assertThat(body.get("nextCursor").asString()).isEqualTo(AlphaCursor.encode(500));
    assertThat(response.getBody()).doesNotContain("test-alpha-key");
  }

  @Test
  void followsTheCursorToTheLastPage() throws Exception {
    var mapper = new ObjectMapper();
    var path = "/alpha/legs?limit=700";
    var served = 0;
    var pages = 0;
    JsonNode cursor;
    do {
      var body = mapper.readTree(get(path).getBody());
      served += body.get("legs").size();
      cursor = body.get("nextCursor");
      path = "/alpha/legs?limit=700&cursor=" + cursor.asString();
    } while (!cursor.isNull() && pages++ < 50);

    assertThat(served).isEqualTo(inventory.page(Provider.ALPHA, 0, Integer.MAX_VALUE).total());
  }

  @Test
  void rejectsAnUnreadableCursor() {
    assertThat(get("/alpha/legs?cursor=not%20base64!").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void rejectsALimitOutOfRange() {
    assertThat(get("/alpha/legs?limit=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/alpha/legs?limit=1001").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private ResponseEntity<String> get(String path) {
    var headers = new HttpHeaders();
    headers.set("X-Api-Key", "test-alpha-key");
    return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
