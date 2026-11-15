package com.flightaggregator.adapter.persistence.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.redis.testcontainers.RedisContainer;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.utility.DockerImageName;

/**
 * One Valkey container per JVM, reached with the client settings the application gives Lettuce (Q9:
 * 250 ms per command, 1 s to connect, commands rejected while disconnected; {@code
 * ValkeyConfigTest} pins the application's). Each test class empties it before each test, and a
 * test can take it down for one call, to see a store fail open.
 */
public final class ValkeyTestServer {

  @SuppressWarnings("resource") // Stopped by Testcontainers' Ryuk when the JVM ends.
  private static final RedisContainer CONTAINER =
      new RedisContainer(DockerImageName.parse(ValkeyImage.NAME));

  private static final StringRedisTemplate VALKEY = start();

  private ValkeyTestServer() {}

  public static StringRedisTemplate valkey() {
    return VALKEY;
  }

  /** Deletes every key. */
  public static void empty() {
    VALKEY.execute(
        (RedisCallback<Void>)
            connection -> {
              connection.serverCommands().flushAll();
              return null;
            });
  }

  /**
   * The call's result with the server frozen, as when Valkey hangs: connections stay open and no
   * command is answered. The test fails unless the call returns within 500 ms, the bound a Valkey
   * outage may add (Review Focus 3). The server resumes before this returns.
   */
  public static <T> T whileDown(Supplier<T> call) {
    DockerClientFactory.instance().client().pauseContainerCmd(CONTAINER.getContainerId()).exec();
    try {
      long started = System.nanoTime();
      T result = call.get();
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
      return result;
    } finally {
      DockerClientFactory.instance()
          .client()
          .unpauseContainerCmd(CONTAINER.getContainerId())
          .exec();
    }
  }

  /** {@link #whileDown(Supplier)} for a call that returns nothing. */
  public static void whileDown(Runnable call) {
    whileDown(
        () -> {
          call.run();
          return null;
        });
  }

  private static StringRedisTemplate start() {
    CONTAINER.start();
    LettuceClientConfiguration client =
        LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(250))
            .clientOptions(
                ClientOptions.builder()
                    .socketOptions(
                        SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build())
                    .timeoutOptions(TimeoutOptions.enabled())
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .build())
            .build();
    LettuceConnectionFactory connections =
        new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(CONTAINER.getRedisHost(), CONTAINER.getRedisPort()),
            client);
    connections.afterPropertiesSet();
    connections.start();
    return new StringRedisTemplate(connections);
  }
}
