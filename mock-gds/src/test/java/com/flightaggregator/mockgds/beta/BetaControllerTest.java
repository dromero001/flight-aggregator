package com.flightaggregator.mockgds.beta;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.serving.ProviderInventory;
import com.flightaggregator.mockgds.support.MockGdsTest;
import com.flightaggregator.mockgds.support.ProviderKeys;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

@MockGdsTest
class BetaControllerTest {

  @Autowired private TestRestTemplate http;
  @Autowired private ProviderInventory inventory;

  @Test
  void servesPageOneByDefault() throws Exception {
    var response = get("/beta/segments");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_XML))
        .isTrue();
    var root = parse(response.getBody());
    assertThat(root.getAttribute("page")).isEqualTo("1");
    assertThat(root.getElementsByTagName("Segment").getLength()).isEqualTo(500);
    assertThat(response.getBody()).doesNotContain(ProviderKeys.BETA);
  }

  private static Element parse(String xml) throws Exception {
    return DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(new InputSource(new StringReader(xml)))
        .getDocumentElement();
  }

  private ResponseEntity<String> get(String path) {
    return ProviderKeys.get(http, path, ProviderKeys.BETA);
  }

  @Test
  void pagesToTotalPages() throws Exception {
    var total = inventory.page(Provider.BETA, 0, Integer.MAX_VALUE).total();
    var first = parse(get("/beta/segments?pageSize=300").getBody());
    var totalPages = Integer.parseInt(first.getAttribute("totalPages"));

    var served = 0;
    for (var page = 1; page <= totalPages; page++) {
      var root = parse(get("/beta/segments?pageSize=300&page=" + page).getBody());
      assertThat(root.getAttribute("page")).isEqualTo(Integer.toString(page));
      served += root.getElementsByTagName("Segment").getLength();
    }

    assertThat(totalPages).isEqualTo((total + 299) / 300);
    assertThat(served).isEqualTo(total);
  }

  @Test
  void servesAnEmptyPageBeyondTheEnd() {
    var body = get("/beta/segments?pageSize=1000&page=1000000").getBody();

    assertThat(body)
        .matches(
            "<SegmentsResponse page=\"1000000\" totalPages=\"\\d+\"><Segments/></SegmentsResponse>");
  }

  @Test
  void servesAnEmptyPageForAnEnormousPageNumber() {
    var body = get("/beta/segments?pageSize=1000&page=2147483647").getBody();

    assertThat(body).contains("<Segments/>");
  }

  @Test
  void rejectsPageZeroAndAPageSizeOutOfRange() {
    assertThat(get("/beta/segments?page=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/beta/segments?page=").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/beta/segments?page=2147483648").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/beta/segments?pageSize=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/beta/segments?pageSize=1001").getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/beta/segments?pageSize=").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }
}
