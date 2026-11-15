package com.flightaggregator.adapter.persistence;

import com.flightaggregator.application.port.out.RequestCounter;
import com.flightaggregator.application.port.out.WindowCount;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Fixed-window request counters in Valkey, one key per bucket under {@code rate:<bucket>}: {@code
 * INCR}, then {@code PEXPIRE … NX}, so that only the window's first request sets its end, then
 * {@code PTTL} for the time left. The three run as one script, so a count is never left without its
 * expiry, which would count the bucket's requests for ever.
 */
public final class ValkeyRequestCounter implements RequestCounter {

  /** {@code EVAL} answers the script's two integers as a list of {@code Long}. */
  @SuppressWarnings("unchecked")
  private static final RedisScript<List<Long>> INCREMENT =
      (RedisScript<List<Long>>)
          (RedisScript<?>)
              RedisScript.of(
                  """
                  local count = redis.call('INCR', KEYS[1])
                  redis.call('PEXPIRE', KEYS[1], ARGV[1], 'NX')
                  return {count, redis.call('PTTL', KEYS[1])}
                  """,
                  List.class);

  private final StringRedisTemplate valkey;
  private final ValkeyFailures failures;

  public ValkeyRequestCounter(StringRedisTemplate valkey, ValkeyFailures failures) {
    this.valkey = valkey;
    this.failures = failures;
  }

  /** {@link WindowCount#unavailable()} when Valkey fails the count. */
  @Override
  public WindowCount increment(String bucket, Duration window) {
    return failures.call(() -> count(bucket, window), WindowCount::unavailable);
  }

  private WindowCount count(String bucket, Duration window) {
    List<Long> counted =
        valkey.execute(INCREMENT, List.of("rate:" + bucket), Long.toString(window.toMillis()));
    return new WindowCount(counted.get(0), Duration.ofMillis(counted.get(1)));
  }
}
