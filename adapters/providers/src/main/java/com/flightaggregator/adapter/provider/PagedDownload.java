package com.flightaggregator.adapter.provider;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderText;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;

/**
 * Drives a provider's pages one after another, in order, and bounds the download: a position that
 * repeats, a page count announced past the maximum, or going past the maximum of pages fails it
 * with {@code PAGINATION_LIMIT}, and the time budget fails it with {@code TIMEOUT} (§6.1). The
 * budget is the {@link TimeLimiter}'s, built by {@link #timeLimiterFor} from the provider's {@code
 * downloadTimeout}; the constructor refuses a limiter with any other budget. The page loop runs on
 * a virtual thread under the limiter, which interrupts it past the budget; the page in flight is
 * bounded by its own request timeout, and no further page is requested.
 */
public final class PagedDownload {

  /** Fetches and parses the page at a position, empty for the first page. */
  @FunctionalInterface
  public interface PageSource {
    ParsedPage page(Optional<String> position);
  }

  private final Provider provider;
  private final ResilienceSettings settings;
  private final TimeLimiter timeLimiter;

  /** The limiter for a provider's download: its budget is {@code downloadTimeout} (§6.1). */
  public static TimeLimiter timeLimiterFor(ResilienceSettings settings) {
    return TimeLimiter.of(
        TimeLimiterConfig.custom()
            .timeoutDuration(settings.downloadTimeout())
            .cancelRunningFuture(true)
            .build());
  }

  public PagedDownload(Provider provider, ResilienceSettings settings, TimeLimiter timeLimiter) {
    this.provider = requireNonNull(provider, "provider");
    this.settings = requireNonNull(settings, "settings");
    this.timeLimiter = requireNonNull(timeLimiter, "timeLimiter");
    Duration budget = timeLimiter.getTimeLimiterConfig().getTimeoutDuration();
    if (!budget.equals(settings.downloadTimeout())) {
      throw new IllegalArgumentException(
          "The time limiter's budget "
              + budget
              + " is not the download timeout "
              + settings.downloadTimeout());
    }
  }

  /**
   * Returns the pages' legs and discarded records in page order.
   *
   * @throws ProviderUnavailableException when a page fails, or the pagination does not end
   */
  public ProviderDownload download(PageSource source) {
    FutureTask<ProviderDownload> task = new FutureTask<>(() -> pages(source));
    Thread.ofVirtual().start(task);
    try {
      return timeLimiter.executeFutureSupplier(() -> task);
    } catch (TimeoutException e) {
      throw new ProviderUnavailableException(provider, FailureReason.TIMEOUT, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProviderUnavailableException(provider, FailureReason.UNEXPECTED, e);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    } finally {
      // Every exit stops the loop: the limiter cancels only on a timeout, not on an interrupt.
      task.cancel(true);
    }
  }

  private ProviderUnavailableException paginationLimit(String detail) {
    return new ProviderUnavailableException(
        provider, FailureReason.PAGINATION_LIMIT, new IllegalStateException(detail));
  }

  private void checkAnnounced(int announced, int pageNumber) {
    if (announced > settings.maxPages()) {
      throw paginationLimit(
          "Page "
              + pageNumber
              + " announces "
              + announced
              + " pages, past the maximum of "
              + settings.maxPages());
    }
  }

  /** The page loop. A cancelled download is interrupted, and requests no further page. */
  private ProviderDownload pages(PageSource source) throws InterruptedException {
    List<Leg> legs = new ArrayList<>();
    List<DiscardedRecord> discarded = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Optional<String> position = Optional.empty();
    for (int number = 1; ; number++) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("The download was cancelled");
      }
      ParsedPage page = source.page(position);
      legs.addAll(page.legs());
      discarded.addAll(page.discarded());
      if (page.announcedPages().isPresent()) {
        checkAnnounced(page.announcedPages().get(), number);
      }
      position = page.next();
      if (position.isEmpty()) {
        break;
      }
      if (!seen.add(position.get())) {
        throw paginationLimit(
            "The position "
                + ProviderText.quoted(position.get())
                + " came back after "
                + number
                + " pages");
      }
      if (number == settings.maxPages()) {
        throw paginationLimit(
            "Page " + number + " points to a page past the maximum of " + settings.maxPages());
      }
    }
    return new ProviderDownload(legs, discarded);
  }
}
