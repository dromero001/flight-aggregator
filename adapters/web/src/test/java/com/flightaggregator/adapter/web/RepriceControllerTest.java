package com.flightaggregator.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flightaggregator.application.port.in.RepriceResult;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RepriceController.class)
@Import(RepriceControllerTest.Reprice.class)
class RepriceControllerTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class Reprice {

    @Bean
    RecordingReprice reprice() {
      return new RecordingReprice();
    }
  }

  /** Madrid to Barcelona, one leg at 100.00 euros: 105.00 with the markup. */
  private static final Itinerary MADRID_BARCELONA =
      new Itinerary(
          List.of(
              Legs.leg(
                  "IB",
                  3166,
                  "MAD",
                  "BCN",
                  "2026-11-20T08:15+01:00",
                  "2026-11-20T09:30+01:00",
                  "100.00",
                  Provider.ALPHA,
                  "2026-11-15T09:00:00Z")));

  @Autowired private MockMvc mvc;
  @Autowired private RecordingReprice reprice;

  @BeforeEach
  void startAfresh() {
    reprice.reset();
  }

  static Stream<Arguments> outcomes() {
    return Stream.of(
        argumentSet(
            "confirmed",
            new RepriceResult.Confirmed(MADRID_BARCELONA),
            """
            {"outcome": "CONFIRMED", "previousPrice": null, "notice": null}
            """),
        argumentSet(
            "price changed",
            new RepriceResult.PriceChanged(MADRID_BARCELONA, Money.euros("99.90")),
            """
            {
              "outcome": "PRICE_CHANGED",
              "previousPrice": {"amount": "99.90", "currency": "EUR"},
              "notice": null
            }
            """),
        argumentSet(
            "alternative",
            new RepriceResult.Alternative(MADRID_BARCELONA),
            """
            {
              "outcome": "ALTERNATIVE",
              "previousPrice": null,
              "notice": "We couldn't confirm this flight. This is the best alternative we found."
            }
            """));
  }

  /**
   * Q4-A: each outcome the dashboard renders is a 200 with its outcome and the itinerary, as a
   * search writes it; a changed price says what it was, and an alternative carries §6.5's notice.
   */
  @ParameterizedTest
  @MethodSource("outcomes")
  void answersEachOutcome(RepriceResult result, String expected) throws Exception {
    reprice.answer(result);

    String body =
        mvc.perform(post("/api/itineraries/{id}/reprice", MADRID_BARCELONA.id().value()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json(expected, JsonCompareMode.LENIENT))
            .andExpect(jsonPath("$.itinerary.id").value(MADRID_BARCELONA.id().value().toString()))
            .andExpect(jsonPath("$.itinerary.price.amount").value("105.00"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<Map<String, Object>>read(body, "$").keySet())
        .containsExactlyInAnyOrder("outcome", "itinerary", "previousPrice", "notice");
    assertThat(reprice.ids()).containsExactly(MADRID_BARCELONA.id());
  }

  /**
   * §6.5 and Q9: when nothing can be confirmed, a problem with §6.5's sentence, and a retry after
   * 60 s, the providers' breakers' open time.
   */
  @Test
  void answers503WithRetryAfterAndTheMessage() throws Exception {
    reprice.answer(new RepriceResult.Unconfirmable());

    mvc.perform(post("/api/itineraries/{id}/reprice", MADRID_BARCELONA.id().value()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(503))
        .andExpect(
            jsonPath("$.detail")
                .value("We can't confirm the price right now. Please try again in a few minutes."))
        .andExpect(
            jsonPath("$.instance")
                .value("/api/itineraries/" + MADRID_BARCELONA.id().value() + "/reprice"));
  }

  /** Q4-A: an id that is not in the active generation, as after a run replaced the inventory. */
  @Test
  void answers404ForAnUnknownId() throws Exception {
    reprice.answer(new RepriceResult.NotFound());

    mvc.perform(post("/api/itineraries/{id}/reprice", MADRID_BARCELONA.id().value()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.detail").value(startsWith("id ")));
  }

  /**
   * The global constraints: an id that is not one a search writes is a 400 naming it, and nothing
   * is re-priced. {@code UUID.fromString} alone would accept {@code 1-1-1-1-1}.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-an-id",
        "1-1-1-1-1",
        "3f2c8f4e6b1a3c559d270a8e4b6c1f90",
        "3f2c8f4e-6b1a-3c55-9d27-0a8e4b6c1f90a",
        "3f2c8f4e-6b1a-3c55-9d27-0a8e4b6c1f9g"
      })
  void answers400ForAMalformedId(String id) throws Exception {
    mvc.perform(post("/api/itineraries/{id}/reprice", id))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value(startsWith("id ")));

    assertThat(reprice.ids()).isEmpty();
  }
}
