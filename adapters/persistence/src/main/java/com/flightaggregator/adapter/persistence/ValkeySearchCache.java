package com.flightaggregator.adapter.persistence;

import com.flightaggregator.application.port.in.SearchPage;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.SearchCache;
import com.flightaggregator.application.port.out.SearchKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.json.JsonFactory;

/**
 * The search cache in Valkey: each page a JSON string under its key's value, for the cache's time
 * to live. Each lookup counts once in {@code search.cache}, tagged {@code result=hit|miss|error},
 * so the hit ratio can be read (§6.2, §6.10). A page this version cannot read, such as one another
 * version wrote, counts as an error and is a {@code WARN}: Valkey is a cache, so nothing it holds
 * may fail a search. Many at once, as after a change of format, are one {@code WARN} per time to
 * live, the longest such a page can still be found.
 */
public final class ValkeySearchCache implements SearchCache {

  private static final Logger LOG = LoggerFactory.getLogger(ValkeySearchCache.class);

  private final StringRedisTemplate valkey;
  private final JsonFactory json;
  private final Duration ttl;
  private final ValkeyFailures failures;
  private final Counter hits;
  private final Counter misses;
  private final Counter errors;
  private final EpisodeWarning unreadablePages;

  public ValkeySearchCache(
      StringRedisTemplate valkey,
      JsonFactory json,
      Duration ttl,
      ValkeyFailures failures,
      MeterRegistry meters,
      Clock clock) {
    this.valkey = valkey;
    this.json = json;
    this.ttl = ttl;
    this.failures = failures;
    this.hits = lookups(meters, "hit");
    this.misses = lookups(meters, "miss");
    this.errors = lookups(meters, "error");
    this.unreadablePages =
        new EpisodeWarning(
            LOG, "A cached search page cannot be read, so the search runs without it", ttl, clock);
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
    if (document == null) {
      misses.increment();
      return Optional.empty();
    }
    SearchPage page;
    try {
      page = SearchPageJson.read(document, json);
    } catch (IllegalStateException unreadable) {
      // Neither the page nor the failure, which can quote it, is logged.
      unreadablePages.warn();
      errors.increment();
      return Optional.empty();
    }
    hits.increment();
    return Optional.of(page);
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
