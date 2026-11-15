package com.flightaggregator.adapter.provider;

import static java.util.Objects.requireNonNull;

import com.flightaggregator.application.port.out.DiscardedRecord;
import com.flightaggregator.application.port.out.FailureReason;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.port.out.ProviderUnavailableException;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Provider;
import io.github.resilience4j.timelimiter.TimeLimiter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * Drives a provider's pages one after another, in order, and bounds the download: a position that
 * repeats or going past the maximum of pages fails it with {@code PAGINATION_LIMIT}, and so does
 * the time budget of the {@link TimeLimiter} with {@code TIMEOUT} (§6.1). The page loop runs on a
 * virtual thread under the limiter, which interrupts it past the budget; the page in flight is
 * bounded by its own request timeout, and no further page is requested. The limiter's {@code
 * timeoutDuration} is the provider's {@code downloadTimeout}, set where the limiter is built.
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

  public PagedDownload(Provider provider, ResilienceSettings settings, TimeLimiter timeLimiter) {
    this.provider = requireNonNull(provider, "provider");
    this.settings = requireNonNull(settings, "settings");
    this.timeLimiter = requireNonNull(timeLimiter, "timeLimiter");
  }

  /**
   * Returns the pages' legs and discarded records in page order.
   *
   * @throws ProviderUnavailableException when a page fails, or the pagination does not end
   */
  public ProviderDownload download(PageSource source) {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      return timeLimiter.executeFutureSupplier(() -> executor.submit(() -> pages(source)));
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
      executor.shutdown();
    }
  }

  /** The page loop. A cancelled download is interrupted, and requests no further page. */
  private ProviderDownload pages(PageSource source) throws InterruptedException {
    List<Leg> legs = new ArrayList<>();
    List<DiscardedRecord> discarded = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Optional<String> position = Optional.empty();
    for (int pages = 0; ; pages++) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("The download was cancelled");
      }
      ParsedPage page = source.page(position);
      legs.addAll(page.legs());
      discarded.addAll(page.discarded());
      position = page.next();
      if (position.isEmpty()) {
        break;
      }
      if (pages + 1 == settings.maxPages() || !seen.add(position.get())) {
        throw new ProviderUnavailableException(provider, FailureReason.PAGINATION_LIMIT, null);
      }
    }
    return new ProviderDownload(legs, discarded);
  }
}
