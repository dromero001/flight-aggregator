package com.flightaggregator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.support.ValkeyImage;
import com.redis.testcontainers.RedisContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.utility.DockerImageName;

/**
 * Review Focus 5: the rate limit counts a client under the address Tomcat resolves, on a real
 * server, so that a client cannot pick its own counter with {@code X-Forwarded-For}; only a
 * connection from an internal proxy may say whom it forwards. The test connects from loopback, and
 * reads which counter Valkey holds after one search.
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
@ImportTestcontainers({PostgresContainers.class, ClientAddressTest.Valkey.class})
class ClientAddressTest {

  /** The address a client forwards, as a forging client or a proxy would. */
  private static final String FORWARDED = "203.0.113.9";

  interface Valkey {

    @ServiceConnection(name = "redis")
    RedisContainer VALKEY = new RedisContainer(DockerImageName.parse(ValkeyImage.NAME));
  }

  @Autowired private StringRedisTemplate valkey;
  @LocalServerPort private int port;

  @BeforeEach
  void forgetEveryCount() {
    valkey.execute(
        connection -> {
          connection.serverCommands().flushAll();
          return null;
        },
        true);
  }

  /** Loopback is not an internal proxy here: a client on it forwards an address in vain. */
  @Nested
  @TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=10[.]9[.]9[.]9")
  class FromAClientThatIsNoProxy {

    @Test
    void countsTheSocketAddressOfANonProxyClient() throws Exception {
      searchForwardedFor(FORWARDED);

      assertThat(valkey.keys("rate:*")).containsExactly("rate:search:127.0.0.1");
    }
  }

  /**
   * Tomcat's own list of internal proxies, private ranges and loopback, as the dashboard's nginx
   * reaches the application in compose (§6.8): the client it forwards is counted.
   */
  @Nested
  class FromAnInternalProxy {

    @Test
    void honoursForwardedForFromAnInternalProxy() throws Exception {
      searchForwardedFor(FORWARDED);

      assertThat(valkey.keys("rate:*")).containsExactly("rate:search:" + FORWARDED);
    }
  }

  private void searchForwardedFor(String address) throws Exception {
    try (HttpClient http = HttpClient.newHttpClient()) {
      HttpResponse<Void> response =
          http.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/itineraries"))
                  .header("X-Forwarded-For", address)
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      assertThat(response.statusCode()).isEqualTo(200);
    }
  }
}
