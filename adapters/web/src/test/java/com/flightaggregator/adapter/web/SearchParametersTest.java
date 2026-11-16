package com.flightaggregator.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.application.port.in.PageCursor;
import com.flightaggregator.application.port.in.SearchCriteria;
import com.flightaggregator.application.port.in.SearchQuery;
import com.flightaggregator.application.port.in.SortOrder;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Money;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SearchParametersTest {

  /** Codes in any case, an amount with or without cents; the defaults of Q9 and §5.4. */
  @Test
  void normalisesCodesAndAmounts() {
    SearchQuery query = new SearchParameters("mad", "Jfk", "ib", "100", null, null, null).toQuery();

    assertThat(query)
        .isEqualTo(
            new SearchQuery(
                new SearchCriteria(
                    Optional.of(new Airport("MAD")),
                    Optional.of(new Airport("JFK")),
                    Optional.of(Money.euros("100.00")),
                    Optional.of(new Carrier("IB")),
                    SortOrder.PRICE),
                20,
                Optional.empty()));
    assertThat(new SearchParameters(null, null, null, "100.00", null, null, null).toQuery())
        .isEqualTo(new SearchParameters(null, null, null, "100", null, null, null).toQuery());
  }

  /** The page after a cursor, in each sort order, at the largest page Q9 allows. */
  @ParameterizedTest
  @CsvSource({"price, PRICE", "departure, DEPARTURE", "duration, DURATION"})
  void readsTheSortTheLimitAndTheCursor(String sort, SortOrder order) {
    PageCursor cursor = Cursors.cursor(7, order);

    SearchQuery query =
        new SearchParameters(null, null, null, null, sort, "100", CursorCodec.encode(cursor))
            .toQuery();

    assertThat(query)
        .isEqualTo(
            new SearchQuery(
                new SearchCriteria(
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), order),
                100,
                Optional.of(cursor)));
  }

  /** Each bad value is refused with a message that names its parameter. */
  @ParameterizedTest(name = "{0}={1}")
  @CsvSource({
    "origin, MADR",
    "origin, M4D",
    "origin, ''",
    // Upper-cased, the dotless i would turn into IST.
    "origin, ıst",
    "destination, MADR",
    "maxPrice, 1e3",
    "maxPrice, -1",
    "maxPrice, 0",
    "maxPrice, 0.00",
    "maxPrice, 100.999",
    "maxPrice, 1234567890",
    "carrier, IBX",
    "carrier, I",
    "sort, best",
    "sort, PRICE",
    "limit, 0",
    "limit, 101",
    "limit, -1",
    "limit, twenty"
  })
  void rejectsEachInvalidParameter(String parameter, String value) {
    SearchParameters parameters = only(parameter, value);

    assertThatThrownBy(parameters::toQuery)
        .isInstanceOf(InvalidParameterException.class)
        .hasMessageStartingWith(parameter + " ");
  }

  private static SearchParameters only(String parameter, String value) {
    return switch (parameter) {
      case "origin" -> new SearchParameters(value, null, null, null, null, null, null);
      case "destination" -> new SearchParameters(null, value, null, null, null, null, null);
      case "maxPrice" -> new SearchParameters(null, null, null, value, null, null, null);
      case "carrier" -> new SearchParameters(null, null, value, null, null, null, null);
      case "sort" -> new SearchParameters(null, null, null, null, value, null, null);
      case "limit" -> new SearchParameters(null, null, null, null, null, value, null);
      default -> throw new IllegalArgumentException(parameter);
    };
  }
}
