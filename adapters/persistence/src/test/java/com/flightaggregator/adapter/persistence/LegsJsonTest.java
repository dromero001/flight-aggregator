package com.flightaggregator.adapter.persistence;

import static com.flightaggregator.domain.Provider.ALPHA;
import static com.flightaggregator.domain.Provider.BETA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.adapter.persistence.support.PostgresTestDatabase;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.json.JsonFactory;

class LegsJsonTest {

  private static final JsonFactory JSON = new JsonFactory();

  private static final Leg MAD_LHR =
      new Leg(
          new Carrier("IB"),
          3166,
          new Airport("MAD"),
          new Airport("LHR"),
          OffsetDateTime.parse("2026-11-20T08:15:00+01:00"),
          OffsetDateTime.parse("2026-11-20T09:30:00+00:00"),
          Money.euros("0.01"),
          ALPHA,
          Instant.parse("2026-11-15T10:00:00.123456Z"));

  private static final Leg LHR_JFK =
      new Leg(
          new Carrier("BA"),
          117,
          new Airport("LHR"),
          new Airport("JFK"),
          OffsetDateTime.parse("2026-11-20T11:00:00+00:00"),
          OffsetDateTime.parse("2026-11-20T14:00:00-05:00"),
          Money.euros("450.10"),
          BETA,
          Instant.parse("2026-11-15T09:59:00Z"));

  @Test
  void roundTripsLegsExactly() {
    List<Leg> legs = List.of(MAD_LHR, LHR_JFK);

    List<Leg> read = LegsJson.read(LegsJson.write(legs, JSON), JSON);

    assertThat(read).isEqualTo(legs);
    assertThat(new Itinerary(read).id()).isEqualTo(new Itinerary(legs).id());
  }

  /** The column is jsonb, which gives the keys back in its own order, not the order written. */
  @Test
  void readsTheLegsAsPostgresReturnsThem() {
    List<Leg> legs = List.of(MAD_LHR, LHR_JFK);
    String reordered =
        JdbcClient.create(PostgresTestDatabase.dataSource())
            .sql("SELECT CAST(? AS jsonb)::text")
            .param(LegsJson.write(legs, JSON))
            .query(String.class)
            .single();

    assertThat(LegsJson.read(reordered, JSON)).isEqualTo(legs);
  }

  @Test
  void failsOnADocumentOfAnotherShape() {
    assertThatThrownBy(() -> LegsJson.read("{\"carrier\":\"IB\"}", JSON))
        .isInstanceOf(IllegalStateException.class);
  }

  /** One leg as {@code write} gives it, to be broken one property at a time. */
  private static final String LEG =
      "{\"carrier\":\"IB\",\"flightNumber\":3166,\"origin\":\"MAD\",\"destination\":\"LHR\","
          + "\"departure\":\"2026-11-20T08:15:00+01:00\",\"arrival\":\"2026-11-20T09:30:00+00:00\","
          + "\"price\":\"129.90\",\"provider\":\"ALPHA\",\"quotedAt\":\"2026-11-15T10:00:00Z\"}";

  @Test
  void failsOnAMissingProperty() {
    String withoutCarrier = "[" + LEG.replace("\"carrier\":\"IB\",", "") + "]";

    assertThatThrownBy(() -> LegsJson.read(withoutCarrier, JSON))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void failsOnAnUnknownProperty() {
    String document = "[" + LEG.replace("\"carrier\"", "\"extra\":\"x\",\"carrier\"") + "]";

    assertThatThrownBy(() -> LegsJson.read(document, JSON))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void failsOnADuplicateProperty() {
    String document =
        "[" + LEG.replace("\"carrier\":\"IB\"", "\"carrier\":\"IB\",\"carrier\":\"BA\"") + "]";

    assertThatThrownBy(() -> LegsJson.read(document, JSON))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void failsOnAValueOfTheWrongType() {
    String document = "[" + LEG.replace("3166", "\"3166\"") + "]";

    assertThatThrownBy(() -> LegsJson.read(document, JSON))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void failsOnContentAfterTheDocument() {
    String document = "[" + LEG + "] []";

    assertThatThrownBy(() -> LegsJson.read(document, JSON))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void wrapsAValueThatDoesNotParseWithItsCause() {
    String badPrice = "[" + LEG.replace("129.90", "cheap") + "]";

    assertThatThrownBy(() -> LegsJson.read(badPrice, JSON))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(NumberFormatException.class);
  }
}
