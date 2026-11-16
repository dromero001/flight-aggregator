package com.flightaggregator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.support.ValkeyImage;
import com.flightaggregator.application.port.out.ItineraryRepository;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Itinerary;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import com.redis.testcontainers.RedisContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.utility.DockerImageName;

/**
 * §6.2's evidence that Valkey is not needed for the system to work, only to make it faster: with
 * Valkey paused (it accepts connections and never answers, the worst case for a client), search
 * answers from PostgreSQL, a client past its rate limit is admitted because nothing can count it,
 * re-price answers, and health is DEGRADED with 200, so the orchestrator leaves the API running.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "providers.alpha.base-url=http://localhost:1/",
      "providers.alpha.api-key=alpha-key",
      "providers.beta.base-url=http://localhost:1/",
      "providers.beta.api-key=beta-key",
      "providers.gamma.base-url=http://localhost:1/",
      "providers.gamma.api-key=gamma-key",
      "ingestion.schedule.enabled=false"
    })
@ImportTestcontainers({PostgresContainers.class, SearchWithoutValkeyTest.Valkey.class})
class SearchWithoutValkeyTest {

  /** One more than the 60 searches a minute an address may make (Q9). */
  private static final int PAST_THE_SEARCH_LIMIT = 61;

  /** One more than the 10 re-prices a minute an address may make (Q9). */
  private static final int PAST_THE_REPRICE_LIMIT = 11;

  interface Valkey {

    @ServiceConnection(name = "redis")
    RedisContainer VALKEY = new RedisContainer(DockerImageName.parse(ValkeyImage.NAME));
  }

  @Autowired private JdbcClient jdbc;
  @Autowired private ItineraryRepository itineraries;
  @LocalServerPort private int port;

  /** One trip on sale, Madrid to London in two days, published as the active generation. */
  @BeforeEach
  void publishOneItinerary() {
    PostgresContainers.emptyTheInventory(jdbc);
    OffsetDateTime departure =
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.HOURS);
    Leg leg =
        new Leg(
            new Carrier("IB"),
            3170,
            new Airport("MAD"),
            new Airport("LHR"),
            departure,
            departure.plusHours(2),
            Money.euros("129.90"),
            Provider.ALPHA,
            Instant.now());
    itineraries.replaceInventory(Instant.now(), sink -> sink.accept(new Itinerary(List.of(leg))));
  }

  @Test
  void searchesAndRepricesWhileValkeyIsDown() throws Exception {
    assertThat(get("/health").body()).contains("\"status\":\"UP\"");

    pauseValkey();
    try {
      for (int search = 1; search <= PAST_THE_SEARCH_LIMIT; search++) {
        HttpResponse<String> response = get("/api/itineraries?origin=MAD");
        assertThat(response.statusCode()).as("search %d", search).isEqualTo(200);
        assertThat(response.body()).as("search %d", search).contains("\"origin\":\"MAD\"");
      }
      for (int reprice = 1; reprice <= PAST_THE_REPRICE_LIMIT; reprice++) {
        assertThat(post("/api/itineraries/" + UUID.randomUUID() + "/reprice").statusCode())
            .as("re-price %d of an id the inventory does not hold", reprice)
            .isEqualTo(404);
      }
      HttpResponse<String> health = get("/health");
      assertThat(health.statusCode()).isEqualTo(200);
      assertThat(health.body()).contains("\"status\":\"DEGRADED\"");
    } finally {
      unpauseValkey();
    }
  }

  private static void pauseValkey() {
    DockerClientFactory.instance()
        .client()
        .pauseContainerCmd(Valkey.VALKEY.getContainerId())
        .exec();
  }

  private static void unpauseValkey() {
    DockerClientFactory.instance()
        .client()
        .unpauseContainerCmd(Valkey.VALKEY.getContainerId())
        .exec();
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(HttpRequest.newBuilder(uri(path)).build());
  }

  private HttpResponse<String> post(String path) throws Exception {
    return send(
        HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()).build());
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }

  private static HttpResponse<String> send(HttpRequest request) throws Exception {
    try (HttpClient http = HttpClient.newHttpClient()) {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }
}
