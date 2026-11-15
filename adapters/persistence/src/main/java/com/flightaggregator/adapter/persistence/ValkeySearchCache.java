package com.flightaggregator.adapter.persistence;

import com.flightaggregator.application.port.in.SearchPage;
import com.flightaggregator.application.port.out.SearchCache;
import com.flightaggregator.application.port.out.SearchKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.json.JsonFactory;

/**
 * The search cache in Valkey: each page a JSON string under its key's value, for the cache's time
 * to live. Each lookup counts once in {@code search.cache}, tagged {@code result=hit|miss|error},
 * so the hit ratio can be read (§6.2, §6.10).
 */
public final class ValkeySearchCache implements SearchCache {

  private final StringRedisTemplate valkey;
  private final JsonFactory json;
  private final Duration ttl;
  private final ValkeyFailures failures;
  private final Counter hits;
  private final Counter misses;
  private final Counter errors;

  public ValkeySearchCache(
      StringRedisTemplate valkey,
      JsonFactory json,
      Duration ttl,
      ValkeyFailures failures,
      MeterRegistry meters) {
    this.valkey = valkey;
    this.json = json;
    this.ttl = ttl;
    this.failures = failures;
    this.hits = lookups(meters, "hit");
    this.misses = lookups(meters, "miss");
    this.errors = lookups(meters, "error");
  }

  /** Nothing, counted as an error, when Valkey fails the lookup. */
  @Override
  public Optional<SearchPage> get(SearchKey key) {
    return failures.call(() -> lookUp(key), this::failedLookup);
  }

  /** Keeps nothing when Valkey fails the write. */
  @Override
  public void put(SearchKey key, SearchPage page) {
    String document = SearchPageJson.write(page, json);
    failures.run(() -> valkey.opsForValue().set(key.value(), document, ttl));
  }

  private Optional<SearchPage> lookUp(SearchKey key) {
    String document = valkey.opsForValue().get(key.value());
    (document == null ? misses : hits).increment();
    return Optional.ofNullable(document).map(found -> SearchPageJson.read(found, json));
  }

  private Optional<SearchPage> failedLookup() {
    errors.increment();
    return Optional.empty();
  }

  private static Counter lookups(MeterRegistry meters, String result) {
    return Counter.builder("search.cache")
        .description("Search cache lookups, by result")
        .tag("result", result)
        .register(meters);
  }
}
