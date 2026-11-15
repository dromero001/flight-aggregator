package com.flightaggregator.adapter.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderText;
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
    List<String> cursors = List.of("a", "b", "a");
    AtomicInteger requested = new AtomicInteger();

    assertThatThrownBy(
            () ->
                download.download(
                    position -> {
                      int number = requested.incrementAndGet();
                      String next =
                          number <= cursors.size() ? cursors.get(number - 1) : "new" + number;
                      return new ParsedPage(List.of(), List.of(), Optional.of(next));
                    }))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> {
              assertThat(e.provider()).isEqualTo(Provider.ALPHA);
              assertThat(e.reason()).isEqualTo(FailureReason.PAGINATION_LIMIT);
              assertThat(e.getCause()).hasMessage("The position \"a\" came back after 3 pages");
            });
    assertThat(requested).hasValue(3);
  }

  @Test
  void quotesAndCutsARepeatedPositionInTheFailureMessage() {
    PagedDownload download = downloadOf(10, Duration.ofSeconds(5));
    String hostile = "x\r\nERROR forged " + "y".repeat(500);

    assertThatThrownBy(
            () ->
                download.download(
                    position -> new ParsedPage(List.of(), List.of(), Optional.of(hostile))))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e ->
                assertThat(e.getCause().getMessage())
                    .isEqualTo(
                        "The position " + ProviderText.quoted(hostile) + " came back after 2 pages")
                    .doesNotContain("\r", "\n")
                    .hasSizeLessThan(260));
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
            e -> {
              assertThat(e.reason()).isEqualTo(FailureReason.PAGINATION_LIMIT);
              assertThat(e.getCause()).hasMessage("Page 3 points to a page past the maximum of 3");
            });
    assertThat(requested).hasValue(3);
  }

  @Test
  void failsAsSoonAsAPageAnnouncesMoreThanTheMaximumPages() {
    PagedDownload download = downloadOf(3, Duration.ofSeconds(5));
    AtomicInteger requested = new AtomicInteger();

    assertThatThrownBy(
            () ->
                download.download(
                    position -> {
                      requested.incrementAndGet();
                      return new ParsedPage(List.of(), List.of(), Optional.of("2"), Optional.of(4));
                    }))
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> {
              assertThat(e.reason()).isEqualTo(FailureReason.PAGINATION_LIMIT);
              assertThat(e.getCause())
                  .hasMessage("Page 1 announces 4 pages, past the maximum of 3");
            });
    assertThat(requested).hasValue(1);
  }

  @Test
  void acceptsAnAnnouncementOfExactlyTheMaximumPages() {
    PagedDownload download = downloadOf(3, Duration.ofSeconds(5));
    AtomicInteger requested = new AtomicInteger();

    download.download(
        position -> {
          int number = requested.incrementAndGet();
          return new ParsedPage(
              List.of(),
              List.of(),
              number < 3 ? Optional.of("page" + number) : Optional.empty(),
              Optional.of(3));
        });

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
  void keepsTheInterruptOfTheCallingThreadAndStopsPaging() throws InterruptedException {
    PagedDownload download = downloadOf(10, Duration.ofSeconds(5));
    SlowPages pages = new SlowPages(Duration.ofMillis(100), 10);

    Thread.currentThread().interrupt();
    Throwable failure = catchThrowable(() -> download.download(pages));
    boolean stillInterrupted = Thread.interrupted();
    int atTheInterrupt = pages.requested();
    Thread.sleep(300);

    assertThat(failure)
        .isInstanceOfSatisfying(
            ProviderUnavailableException.class,
            e -> assertThat(e.reason()).isEqualTo(FailureReason.UNEXPECTED));
    assertThat(stillInterrupted).isTrue();
    assertThat(pages.requested()).isEqualTo(atTheInterrupt);
  }

  @Test
  void buildsItsTimeLimiterFromTheDownloadBudget() {
    TimeLimiterConfig config =
        PagedDownload.timeLimiterFor(settings(Duration.ofSeconds(60))).getTimeLimiterConfig();

    assertThat(config.getTimeoutDuration()).isEqualTo(Duration.ofSeconds(60));
    assertThat(config.shouldCancelRunningFuture()).isTrue();
  }

  @Test
  void rejectsATimeLimiterWhoseBudgetIsNotTheDownloadTimeout() {
    ResilienceSettings settings = settings(Duration.ofSeconds(60));
    TimeLimiter other = PagedDownload.timeLimiterFor(settings(Duration.ofSeconds(30)));

    assertThatThrownBy(() -> new PagedDownload(Provider.ALPHA, settings, other))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PT30S")
        .hasMessageContaining("PT1M");
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
    ResilienceSettings settings = settings(budget, maxPages);
    return new PagedDownload(Provider.ALPHA, settings, PagedDownload.timeLimiterFor(settings));
  }

  private static ResilienceSettings settings(Duration budget) {
    return settings(budget, 10);
  }

  private static ResilienceSettings settings(Duration budget, int maxPages) {
    return new ResilienceSettings(
        Duration.ofSeconds(1), budget, 1, Duration.ofMillis(10), 100, maxPages, 1024);
  }
}
