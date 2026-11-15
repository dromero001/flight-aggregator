package com.flightaggregator.adapter.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PagedDownloadTest {

  @Test
  void failsADownloadThatRepeatsAPosition() {
    PagedDownload download = downloadOf(10, Duration.ofSeconds(5));

    assertThatThrownBy(
            () ->
                download.download(
                    position ->
                        new ParsedPage(
                            List.of(),
                            List.of(),
                            Optional.of(position.map(p -> p.equals("a") ? "b" : "a").orElse("a")))))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> {
              assertThat(e.provider()).isEqualTo(Provider.ALPHA);
              assertThat(e.reason()).isEqualTo(FailureReason.PAGINATION_LIMIT);
            });
  }

  @Test
  void failsADownloadPastTheMaximumPages() {
    PagedDownload download = downloadOf(3, Duration.ofSeconds(5));
    AtomicInteger requested = new AtomicInteger();

    assertThatThrownBy(
            () ->
                download.download(
                    position -> {
                      int number = requested.incrementAndGet();
                      return new ParsedPage(List.of(), List.of(), Optional.of("page" + number));
                    }))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(FailureReason.PAGINATION_LIMIT));
    assertThat(requested).hasValue(3);
  }

  @Test
  void acceptsADownloadOfExactlyTheMaximumPages() {
    PagedDownload download = downloadOf(3, Duration.ofSeconds(5));
    AtomicInteger requested = new AtomicInteger();

    download.download(
        position -> {
          int number = requested.incrementAndGet();
          return new ParsedPage(
              List.of(), List.of(), number < 3 ? Optional.of("page" + number) : Optional.empty());
        });

    assertThat(requested).hasValue(3);
  }

  @Test
  void failsADownloadPastItsTimeBudget() {
    PagedDownload download = downloadOf(10, Duration.ofMillis(250));
    SlowPages pages = new SlowPages(Duration.ofMillis(100), 10);

    long started = System.nanoTime();
    Throwable failure = catchThrowable(() -> download.download(pages));
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertThat(failure)
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> {
              assertThat(e.provider()).isEqualTo(Provider.ALPHA);
              assertThat(e.reason()).isEqualTo(FailureReason.TIMEOUT);
            });
    assertThat(elapsed).isLessThan(Duration.ofMillis(400));
  }

  @Test
  void requestsNoPageAfterTheBudget() throws InterruptedException {
    PagedDownload download = downloadOf(10, Duration.ofMillis(250));
    SlowPages pages = new SlowPages(Duration.ofMillis(100), 10);

    assertThatThrownBy(() -> download.download(pages))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(FailureReason.TIMEOUT));
    int atTheTimeout = pages.requested();
    Thread.sleep(300);

    assertThat(atTheTimeout).isLessThanOrEqualTo(3);
    assertThat(pages.requested()).isEqualTo(atTheTimeout);
  }

  @Test
  void keepsTheInterruptOfTheCallingThreadAndFailsTheDownload() {
    PagedDownload download = downloadOf(10, Duration.ofSeconds(5));
    SlowPages pages = new SlowPages(Duration.ofMillis(100), 10);

    Thread.currentThread().interrupt();
    Throwable failure = catchThrowable(() -> download.download(pages));
    boolean stillInterrupted = Thread.interrupted();

    assertThat(failure)
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(FailureReason.UNEXPECTED));
    assertThat(stillInterrupted).isTrue();
  }

  /** A page source that takes a fixed time per page and has a fixed number of pages. */
  private static final class SlowPages implements PagedDownload.PageSource {

    private final Duration perPage;
    private final int total;
    private final AtomicInteger requested = new AtomicInteger();

    SlowPages(Duration perPage, int total) {
      this.perPage = perPage;
      this.total = total;
    }

    @Override
    public ParsedPage page(Optional<String> position) {
      int number = requested.incrementAndGet();
      try {
        Thread.sleep(perPage);
      } catch (InterruptedException e) {
        // Like a blocking read that still completes: the page returns, the flag stays set.
        Thread.currentThread().interrupt();
      }
      return new ParsedPage(
          List.of(), List.of(), number < total ? Optional.of("page" + number) : Optional.empty());
    }

    int requested() {
      return requested.get();
    }
  }

  private static PagedDownload downloadOf(int maxPages, Duration budget) {
    ResilienceSettings settings =
        new ResilienceSettings(
            Duration.ofSeconds(1), budget, 1, Duration.ofMillis(10), 100, maxPages, 1024);
    TimeLimiter timeLimiter =
        TimeLimiter.of(
            TimeLimiterConfig.custom().timeoutDuration(budget).cancelRunningFuture(true).build());
    return new PagedDownload(Provider.ALPHA, settings, timeLimiter);
  }
}
