package com.flightaggregator.adapter.web;

import static com.flightaggregator.adapter.web.Legs.leg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flightaggregator.application.port.in.InventoryUnavailableException;
import com.flightaggregator.application.port.in.PageCursor;
import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SearchPage;
import com.flightaggregator.application.port.in.SearchQuery;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Provider;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SearchController.class)
@Import(SearchControllerTest.Search.class)
class SearchControllerTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class Search {

    @Bean
    RecordingSearch search() {
      return new RecordingSearch();
    }
  }

  @Autowired private MockMvc mvc;
  @Autowired private RecordingSearch search;

  @BeforeEach
  void startAfresh() {
    search.reset();
  }

  /** §5.4 and Q9: by price, 20 to a page, every filter absent. */
  @Test
  void searchesWithNoFilters() throws Exception {
    mvc.perform(get("/api/itineraries"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    """
                    {"itineraries": [], "nextCursor": null, "inventoryChanged": false}
                    """,
                    JsonCompareMode.STRICT));

    assertThat(search.queries())
        .containsExactly(
            new SearchQuery(
                new SearchCriteria(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    SortOrder.PRICE),
                20,
                Optional.empty()));
  }

  /**
   * §4.2 and the global constraints: times at each airport's own offset, the final price as a
   * decimal string in EUR, and nothing a card does not show (§6.8): no provider, no leg price.
   */
  @Test
  void writesLocalTimesAndThePriceButNeverTheProvider() throws Exception {
    Itinerary madridNewYork =
        new Itinerary(
            List.of(
                leg(
                    "IB",
                    3166,
                    "MAD",
                    "BCN",
                    "2026-11-20T08:15+01:00",
                    "2026-11-20T09:30+01:00",
                    "100.00",
                    Provider.ALPHA,
                    "2026-11-15T09:00:00Z"),
                leg(
                    "AA",
                    69,
                    "BCN",
                    "JFK",
                    "2026-11-20T11:00+01:00",
                    "2026-11-20T14:10-05:00",
                    "200.00",
                    Provider.BETA,
                    "2026-11-15T09:05:00Z")));
    search.answer(new SearchPage(List.of(madridNewYork), Optional.empty(), false));

    String body =
        mvc.perform(get("/api/itineraries"))
            .andExpect(status().isOk())
            .andExpect(
                content()
                    .json(
                        """
                        {
                          "itineraries": [{
                            "id": "%s",
                            "origin": "MAD",
                            "destination": "JFK",
                            "departure": "2026-11-20T08:15:00+01:00",
                            "arrival": "2026-11-20T14:10:00-05:00",
                            "durationMinutes": 715,
                            "stops": 1,
                            "price": {"amount": "315.00", "currency": "EUR"},
                            "quotedAt": "2026-11-15T09:00:00Z",
                            "legs": [
                              {
                                "carrier": "IB",
                                "flightNumber": 3166,
                                "origin": "MAD",
                                "destination": "BCN",
                                "departure": "2026-11-20T08:15:00+01:00",
                                "arrival": "2026-11-20T09:30:00+01:00"
                              },
                              {
                                "carrier": "AA",
                                "flightNumber": 69,
                                "origin": "BCN",
                                "destination": "JFK",
                                "departure": "2026-11-20T11:00:00+01:00",
                                "arrival": "2026-11-20T14:10:00-05:00"
                              }
                            ]
                          }],
                          "nextCursor": null,
                          "inventoryChanged": false
                        }
                        """
                            .formatted(madridNewYork.id().value()),
                        JsonCompareMode.STRICT))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("provider", "alpha", "beta");
    assertThat(body).doesNotContain("100.00", "200.00");
  }

  /** Q1-A: a page read after the inventory changed says so, and still gives the next cursor. */
  @Test
  void flagsAChangedInventory() throws Exception {
    PageCursor next = Cursors.cursor(8, SortOrder.PRICE);
    search.answer(new SearchPage(List.of(), Optional.of(next), true));

    String nextCursor =
        JsonPath.read(
            mvc.perform(get("/api/itineraries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventoryChanged").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "$.nextCursor");

    assertThat(CursorCodec.decode(nextCursor)).isEqualTo(next);
  }

  /** Review Focus 2: an RFC 9457 problem naming the parameter, and no search made. */
  @Test
  void answersAProblemForABadCursor() throws Exception {
    mvc.perform(get("/api/itineraries").param("cursor", "not-a-cursor"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Bad Request"))
        .andExpect(jsonPath("$.detail").value(containsString("cursor")))
        .andExpect(jsonPath("$.instance").value("/api/itineraries"));

    assertThat(search.queries()).isEmpty();
  }

  /**
   * Plan 5's end-of-plan review (I1): a read that outruns its bound is the inventory busy, not a
   * bug, so it answers 503 with Retry-After, as re-price's other 503 does (Q9: 60 s), never a 500.
   */
  @Test
  void answers503WithRetryAfterWhenTheInventoryCannotBeReadInTime() throws Exception {
    search.fail(
        new InventoryUnavailableException(
            "a search read outran its bound", new IllegalStateException("statement timeout")));

    mvc.perform(get("/api/itineraries").param("origin", "NRT"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(503))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "The flights could not be read in time: try again after the seconds in"
                        + " Retry-After."))
        .andExpect(jsonPath("$.instance").value("/api/itineraries"));
  }

  /**
   * A misspelt parameter is refused, not ignored: {@code orign=MAD} would otherwise search every
   * origin (G1, the user's decision of 2026-11-16). The detail names every unknown one, in the
   * order sent, and the parameters the search takes, in the order the docs and the dashboard's
   * filters list them.
   */
  @Test
  void answersAProblemNamingEachUnknownParameter() throws Exception {
    mvc.perform(get("/api/itineraries?orign=MAD&destination=JFK&page=2"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "Unknown parameters: orign, page. The search takes origin, destination,"
                        + " carrier, maxPrice, sort, limit and cursor."));

    assertThat(search.queries()).isEmpty();
  }

  /** One unknown parameter is named in the singular (G1's review, Minor 1). */
  @Test
  void answersAProblemNamingTheOneUnknownParameter() throws Exception {
    mvc.perform(get("/api/itineraries?orign=MAD"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.detail")
                .value(
                    "Unknown parameter: orign. The search takes origin, destination, carrier,"
                        + " maxPrice, sort, limit and cursor."));
  }

  /** The global constraints: each bad value is a 400 whose detail names its parameter. */
  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({
    "origin, MADR",
    "destination, MADR",
    "maxPrice, 0",
    "carrier, IBX",
    "sort, best",
    "limit, 101"
  })
  void answersAProblemNamingEachBadParameter(String parameter, String value) throws Exception {
    mvc.perform(get("/api/itineraries").param(parameter, value))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value(startsWith(parameter + " ")));

    assertThat(search.queries()).isEmpty();
  }
}
