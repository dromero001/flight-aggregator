package com.flightaggregator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAPI contract the application serves is the one committed in {@code
 * docs/architecture/openapi.json}, from which the dashboard's types are generated (§6.8): CI fails
 * when a change to the API leaves it behind (§6.11). It is asked for anonymously, through the
 * security filters, as the dashboard's build would.
 */
@SpringBootTest(
    properties = {
      "providers.alpha.base-url=http://localhost:1/",
      "providers.alpha.api-key=alpha-key",
      "providers.beta.base-url=http://localhost:1/",
      "providers.beta.api-key=beta-key",
      "providers.gamma.base-url=http://localhost:1/",
      "providers.gamma.api-key=gamma-key",
      "ingestion.schedule.enabled=false"
    })
@ImportTestcontainers(PostgresContainers.class)
@AutoConfigureMockMvc
class OpenApiContractTest {

  /** The tests run in the module's directory; the contract is committed under the root's docs. */
  private static final Path COMMITTED = Path.of("..", "docs", "architecture", "openapi.json");

  private static final String REGENERATE =
      "the committed contract differs from the served one: regenerate it with"
          + " ./gradlew :bootstrap:test --tests '*OpenApiContractTest*' -Dopenapi.write=true";

  /** Two-space indentation and LF line ends on every platform, as the repository keeps text. */
  private static final DefaultPrettyPrinter PRINTER =
      new DefaultPrettyPrinter()
          .withObjectIndenter(new DefaultIndenter("  ", "\n"))
          .withArrayIndenter(new DefaultIndenter("  ", "\n"));

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The schemas of the search's answer: the page, its itineraries, their legs and prices. */
  static final List<String> SEARCH_SCHEMAS =
      List.of("SearchResponse", "ItineraryResponse", "Leg", "Price");

  @Autowired private MockMvc mvc;

  @Test
  void servedContractMatchesTheCommittedOne() throws Exception {
    String served = served();

    if (Boolean.getBoolean("openapi.write")) {
      Files.writeString(COMMITTED, served);
    }

    assertThat(COMMITTED).as(REGENERATE).exists();
    assertThat(Files.readString(COMMITTED)).as(REGENERATE).isEqualTo(served);
  }

  /**
   * The dashboard's types (§6.8): a field the search always writes is required, so no client has to
   * guess whether it is there.
   */
  @ParameterizedTest
  @FieldSource("SEARCH_SCHEMAS")
  void marksEveryFieldOfTheSearchRequired(String name) throws Exception {
    JsonNode schema = contract().at("/components/schemas/" + name);

    assertThat(schema.path("required").valueStream().map(JsonNode::asString))
        .containsExactlyInAnyOrderElementsOf(schema.path("properties").propertyNames());
  }

  /**
   * The last page has no next cursor (§5.4); every other field of the search always has a value.
   */
  @Test
  void marksOnlyTheNextCursorNullable() throws Exception {
    JsonNode schemas = contract().at("/components/schemas");

    assertThat(
            SEARCH_SCHEMAS.stream()
                .flatMap(
                    name ->
                        schemas.path(name).path("properties").properties().stream()
                            .filter(property -> isNullable(property.getValue()))
                            .map(property -> name + "." + property.getKey())))
        .containsExactly("SearchResponse.nextCursor");
  }

  /**
   * Q4-A: a re-price answers one body, its outcome and the itinerary always; the previous price and
   * the notice come only with the outcome that has them, so they are optional, and nothing is null
   * (springdoc cannot write a nullable reference to {@code Price} that OpenAPI 3.1 reads as one).
   */
  @Test
  void marksTheOutcomeAndTheItineraryOfARepriceRequiredAndNothingNullable() throws Exception {
    JsonNode contract = contract();
    JsonNode schema = contract.at("/components/schemas/RepriceResponse");

    assertThat(
            contract
                .at("/paths/~1api~1itineraries~1{id}~1reprice/post/responses/200/content")
                .path("application/json")
                .at("/schema/$ref")
                .asString())
        .isEqualTo("#/components/schemas/RepriceResponse");
    assertThat(schema.path("required").valueStream().map(JsonNode::asString))
        .containsExactlyInAnyOrder("outcome", "itinerary");
    assertThat(schema.path("properties").propertyNames())
        .containsExactlyInAnyOrder("outcome", "itinerary", "previousPrice", "notice");
    assertThat(
            schema.path("properties").properties().stream()
                .filter(property -> isNullable(property.getValue()))
                .map(Map.Entry::getKey))
        .isEmpty();
  }

  /** A bad parameter or cursor is an RFC 9457 problem (global constraints), and says so. */
  @Test
  void documentsTheProblemOfABadRequest() throws Exception {
    JsonNode contract = contract();

    assertThat(
            contract
                .at("/paths/~1api~1itineraries/get/responses/400/content")
                .path("application/problem+json")
                .at("/schema/$ref")
                .asString())
        .isEqualTo("#/components/schemas/ProblemDetail");
    assertThat(contract.at("/components/schemas/ProblemDetail/properties").propertyNames())
        .contains("type", "title", "status", "detail", "instance");
  }

  /**
   * Q4-A, Q9 and §6.6: each problem a search or a re-price may answer is documented on its own
   * operation as an RFC 9457 problem, so the dashboard's types know every failure it must show.
   */
  @ParameterizedTest(name = "{1} {0} answers {2}")
  @CsvSource({
    "/api/itineraries, get, 429",
    "/api/itineraries/{id}/reprice, post, 400",
    "/api/itineraries/{id}/reprice, post, 404",
    "/api/itineraries/{id}/reprice, post, 429",
    "/api/itineraries/{id}/reprice, post, 503"
  })
  void documentsEachProblemOnItsOperation(String path, String method, String status)
      throws Exception {
    JsonNode content =
        contract().path("paths").path(path).path(method).path("responses").path(status);

    assertThat(content.at("/content/application~1problem+json/schema/$ref").asString())
        .isEqualTo("#/components/schemas/ProblemDetail");
  }

  /** §6.5, §6.6 and Q9: a problem to retry says when, in whole seconds. */
  @ParameterizedTest(name = "{1} {0} answers {2}")
  @CsvSource({
    "/api/itineraries, get, 429",
    "/api/itineraries/{id}/reprice, post, 429",
    "/api/itineraries/{id}/reprice, post, 503"
  })
  void documentsRetryAfterOnEachProblemToRetry(String path, String method, String status)
      throws Exception {
    JsonNode retryAfter =
        contract()
            .path("paths")
            .path(path)
            .path(method)
            .path("responses")
            .path(status)
            .at("/headers/Retry-After");

    assertThat(retryAfter.path("required").asBoolean()).isTrue();
    assertThat(retryAfter.at("/schema/type").asString()).isEqualTo("integer");
  }

  /**
   * Each parameter as the search reads it (§4, §5.4, Q9), so the dashboard's types and checks
   * follow the API: all optional, the codes and the amount by their patterns, the sort orders and
   * the page size with their defaults, and the cursor's bound.
   */
  @Test
  void documentsEachParameterAsTheSearchReadsIt() throws Exception {
    Map<String, JsonNode> parameters = new LinkedHashMap<>();
    contract()
        .at("/paths/~1api~1itineraries/get/parameters")
        .forEach(parameter -> parameters.put(parameter.path("name").asString(), parameter));

    assertThat(parameters)
        .containsOnlyKeys(
            "origin", "destination", "maxPrice", "carrier", "sort", "limit", "cursor");
    assertThat(parameters.values())
        .allSatisfy(parameter -> assertThat(parameter.path("required").asBoolean()).isFalse());
    assertThat(schema(parameters, "origin", "pattern")).isEqualTo("^[A-Za-z]{3}$");
    assertThat(schema(parameters, "destination", "pattern")).isEqualTo("^[A-Za-z]{3}$");
    assertThat(schema(parameters, "carrier", "pattern")).isEqualTo("^[A-Za-z0-9]{2}$");
    assertThat(schema(parameters, "maxPrice", "pattern")).isEqualTo("^[0-9]{1,9}(\\.[0-9]{1,2})?$");
    assertThat(parameters.get("sort").at("/schema/enum").valueStream().map(JsonNode::asString))
        .containsExactly("price", "departure", "duration");
    assertThat(schema(parameters, "sort", "default")).isEqualTo("price");
    assertThat(schema(parameters, "limit", "type")).isEqualTo("integer");
    assertThat(schema(parameters, "limit", "minimum")).isEqualTo("1");
    assertThat(schema(parameters, "limit", "maximum")).isEqualTo("100");
    assertThat(schema(parameters, "limit", "default")).isEqualTo("20");
    assertThat(schema(parameters, "cursor", "maxLength")).isEqualTo("200");
  }

  /** The re-price's id as the endpoint reads it: an itinerary id in its canonical form. */
  @Test
  void documentsTheIdAsTheRepriceReadsIt() throws Exception {
    JsonNode parameters = contract().at("/paths/~1api~1itineraries~1{id}~1reprice/post/parameters");

    assertThat(parameters.valueStream().map(parameter -> parameter.path("name").asString()))
        .containsExactly("id");
    assertThat(parameters.at("/0/schema/format").asString()).isEqualTo("uuid");
    assertThat(parameters.at("/0/schema/pattern").asString())
        .isEqualTo("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  }

  private static String schema(Map<String, JsonNode> parameters, String name, String keyword) {
    return parameters.get(name).path("schema").path(keyword).asString();
  }

  /** OpenAPI 3.1 writes a nullable value as a type list that includes {@code null}. */
  private static boolean isNullable(JsonNode property) {
    return property.path("type").valueStream().anyMatch(type -> type.asString().equals("null"));
  }

  /** The contract as {@code /v3/api-docs} serves it, indented, with a final line end. */
  private String served() throws Exception {
    return JSON.writer().with(PRINTER).writeValueAsString(contract()) + "\n";
  }

  private JsonNode contract() throws Exception {
    return JSON.readTree(
        mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }
}
