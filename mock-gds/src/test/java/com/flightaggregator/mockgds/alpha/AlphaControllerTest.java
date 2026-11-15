package com.flightaggregator.mockgds.alpha;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.dataset.Dataset;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.support.MockGdsTest;
import com.flightaggregator.mockgds.support.ProviderKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@MockGdsTest
class AlphaControllerTest {

  @Autowired private TestRestTemplate http;
  @Autowired private Dataset dataset;

  @Test
  void servesTheFirstPageWithoutACursor() throws Exception {
    var response = get("/alpha/legs");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    var body = new ObjectMapper().readTree(response.getBody());
    assertThat(body.get("legs")).hasSize(500);
    assertThat(body.get("nextCursor").asString()).isEqualTo(AlphaCursor.encode(500));
    assertThat(response.getBody()).doesNotContain(ProviderKeys.ALPHA);
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

    assertThat(served).isEqualTo(dataset.recordsFor(Provider.ALPHA).size());
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

  @Test
  void rejectsAnEmptyLimit() {
    assertThat(get("/alpha/legs?limit=").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  private ResponseEntity<String> get(String path) {
    return ProviderKeys.get(http, path, ProviderKeys.ALPHA);
  }
}
