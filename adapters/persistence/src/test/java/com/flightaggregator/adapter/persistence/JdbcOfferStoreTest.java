package com.flightaggregator.adapter.persistence;

import static com.flightaggregator.adapter.persistence.support.LockWaits.awaitAStatementBlockedBy;
import static com.flightaggregator.domain.Provider.ALPHA;
import static com.flightaggregator.domain.Provider.BETA;
import static com.flightaggregator.domain.Provider.GAMMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.adapter.persistence.support.PostgresTestDatabase;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.Money;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.AbstractList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcOfferStoreTest {

  private final DataSource dataSource = PostgresTestDatabase.dataSource();
  private final JdbcOfferStore store =
      new JdbcOfferStore(new DataSourceTransactionManager(dataSource));

  @BeforeEach
  void emptyTheTable() {
    JdbcClient.create(dataSource).sql("TRUNCATE provider_offer").update();
  }

  private static Leg leg(Provider provider, int number, String departure, String price) {
    OffsetDateTime departsAt = OffsetDateTime.parse(departure);
    return new Leg(
        new Carrier("IB"),
        number,
        new Airport("MAD"),
        new Airport("LHR"),
        departsAt,
        departsAt.plusHours(2).withOffsetSameInstant(java.time.ZoneOffset.ofHoursMinutes(5, 30)),
        Money.euros(price),
        provider,
        Instant.parse("2026-11-20T08:00:00.123456Z"));
  }

  /** The same offer of another flight: the same number to another destination. */
  private static Leg to(String destination, Leg leg) {
    return new Leg(
        leg.carrier(),
        leg.flightNumber(),
        leg.origin(),
        new Airport(destination),
        leg.departure(),
        leg.arrival(),
        leg.price(),
        leg.provider(),
        leg.quotedAt());
  }

  /** The same offer departing from another origin. */
  private static Leg from(String origin, Leg leg) {
    return new Leg(
        leg.carrier(),
        leg.flightNumber(),
        new Airport(origin),
        leg.destination(),
        leg.departure(),
        leg.arrival(),
        leg.price(),
        leg.provider(),
        leg.quotedAt());
  }

  /**
   * A refresh's fresh offers of one origin take the place of the provider's stored ones from it,
   * after its offers from other origins, which stay; another provider's offers from the origin stay
   * too.
   */
  @Test
  void replacesOnlyTheProvidersOffersFromTheOrigin() {
    Leg madrid = leg(ALPHA, 1, "2026-11-20T07:00:00Z", "50.00");
    Leg barcelona = from("BCN", leg(ALPHA, 2, "2026-11-20T08:00:00Z", "60.00"));
    Leg betaMadrid = leg(BETA, 3, "2026-11-20T09:00:00Z", "70.00");
    store.replace(ALPHA, List.of(madrid, barcelona));
    store.replace(BETA, List.of(betaMadrid));
    Leg madridNow = leg(ALPHA, 1, "2026-11-20T07:00:00Z", "80.00");
    Leg anotherMadrid = leg(ALPHA, 4, "2026-11-20T10:00:00Z", "90.00");

    store.replaceFrom(ALPHA, new Airport("MAD"), List.of(madridNow, anotherMadrid));

    assertThat(store.offers(ALPHA)).containsExactly(barcelona, madridNow, anotherMadrid);
    assertThat(store.offers(BETA)).containsExactly(betaMadrid);
  }

  /**
   * A refresh that swaps Alpha's Madrid offers while a run replaces all of Alpha's waits for the
   * run, then swaps the run's Madrid offers, not the ones the run deleted: Alpha ends with the
   * run's other offers and the refresh's Madrid ones, never the run's Madrid ones beside them. The
   * run is a second transaction that replaces Alpha's offers and commits once the refresh waits for
   * it.
   */
  @Test
  void swapsTheOffersOfARunThatReplacedThemMeanwhile() throws Exception {
    store.replace(ALPHA, List.of(leg(ALPHA, 1, "2026-11-20T07:00:00Z", "50.00")));
    Leg runMadrid = leg(ALPHA, 1, "2026-11-20T07:00:00Z", "55.00");
    Leg runBarcelona = from("BCN", leg(ALPHA, 2, "2026-11-20T08:00:00Z", "60.00"));
    Leg refreshMadrid = leg(ALPHA, 1, "2026-11-20T07:00:00Z", "80.00");
    HeldWrite run = new HeldWrite(runs -> runs.replace(ALPHA, List.of(runMadrid, runBarcelona)));
    CompletableFuture<Void> refresh =
        CompletableFuture.runAsync(
            () -> store.replaceFrom(ALPHA, new Airport("MAD"), List.of(refreshMadrid)));

    run.commitOnceAnotherWriteWaits();
    refresh.get(10, TimeUnit.SECONDS);

    assertThat(store.offers(ALPHA)).containsExactly(runBarcelona, refreshMadrid);
  }

  /**
   * A run that replaces Alpha's offers while a refresh's transaction, which swapped Alpha's Madrid
   * ones, is still open waits for the refresh, then replaces the refresh's offers too: Alpha ends
   * with the run's offers alone, and the run's positions do not collide with the refresh's.
   */
  @Test
  void letsARunReplaceTheOffersOfARefreshThatSwappedThemMeanwhile() throws Exception {
    store.replace(ALPHA, List.of(leg(ALPHA, 1, "2026-11-20T07:00:00Z", "50.00")));
    Leg refreshMadrid = leg(ALPHA, 1, "2026-11-20T07:00:00Z", "80.00");
    Leg runMadrid = leg(ALPHA, 1, "2026-11-20T07:00:00Z", "55.00");
    Leg runBarcelona = from("BCN", leg(ALPHA, 2, "2026-11-20T08:00:00Z", "60.00"));
    HeldWrite refresh =
        new HeldWrite(
            refreshes -> refreshes.replaceFrom(ALPHA, new Airport("MAD"), List.of(refreshMadrid)));
    CompletableFuture<Void> run =
        CompletableFuture.runAsync(() -> store.replace(ALPHA, List.of(runMadrid, runBarcelona)));

    refresh.commitOnceAnotherWriteWaits();
    run.get(10, TimeUnit.SECONDS);

    assertThat(store.offers(ALPHA)).containsExactly(runMadrid, runBarcelona);
  }

  /**
   * Every provider's offer of one flight, the cheapest first, read back exactly. The same number
   * departing at another time, or flying to another destination, is another flight.
   */
  @Test
  void findsEveryProvidersOfferOfAFlight() {
    Leg alpha = leg(ALPHA, 3170, "2026-11-20T07:15:00+01:00", "120.00");
    Leg beta = leg(BETA, 3170, "2026-11-20T06:15:00Z", "100.00");
    Leg gamma = leg(GAMMA, 3170, "2026-11-20T07:15:00+01:00", "110.00");
    store.replace(ALPHA, List.of(leg(ALPHA, 3171, "2026-11-20T07:15:00+01:00", "50.00"), alpha));
    store.replace(BETA, List.of(beta, leg(BETA, 3170, "2026-11-21T07:15:00+01:00", "50.00")));
    store.replace(GAMMA, List.of(to("CDG", gamma), gamma));

    List<Leg> offers = store.offersOf(alpha.key());

    assertThat(offers).containsExactly(beta, gamma, alpha);
  }

  /** V6: one flight's offers are read through an index, not by scanning every provider's. */
  @Test
  void indexesTheOffersByFlight() {
    List<String> indexes =
        JdbcClient.create(dataSource)
            .sql(
                "SELECT substring(indexdef FROM 'USING btree \\((.*)\\)') FROM pg_indexes"
                    + " WHERE tablename = 'provider_offer' AND indexname NOT LIKE '%pkey'")
            .query(String.class)
            .list();

    assertThat(indexes).containsExactly("carrier, flight_number, origin, departure");
  }

  @Test
  void roundTripsALegExactly() {
    Leg original = leg(ALPHA, 3170, "2026-11-20T07:15:00-05:00", "0.01");

    store.replace(ALPHA, List.of(original));

    List<Leg> stored = store.offers(ALPHA);
    assertThat(stored).containsExactly(original);
    assertThat(stored.getFirst().key()).isEqualTo(original.key());
  }

  @Test
  void returnsOffersInStoredOrder() {
    List<Leg> offers =
        List.of(
            leg(ALPHA, 30, "2026-11-20T09:00:00Z", "50.00"),
            leg(ALPHA, 10, "2026-11-20T07:00:00Z", "70.00"),
            leg(ALPHA, 20, "2026-11-20T08:00:00Z", "60.00"));

    store.replace(ALPHA, offers);

    assertThat(store.offers(ALPHA)).containsExactlyElementsOf(offers);
  }

  @Test
  void replacesOnlyThatProvidersOffers() {
    Leg beta = leg(BETA, 5, "2026-11-20T07:00:00Z", "80.00");
    store.replace(ALPHA, List.of(leg(ALPHA, 1, "2026-11-20T07:00:00Z", "50.00")));
    store.replace(BETA, List.of(beta));
    Leg newAlpha = leg(ALPHA, 2, "2026-11-20T08:00:00Z", "55.00");

    store.replace(ALPHA, List.of(newAlpha));

    assertThat(store.offers(ALPHA)).containsExactly(newAlpha);
    assertThat(store.offers(BETA)).containsExactly(beta);
  }

  /**
   * A statement of the replacement that hangs is cut after 5 minutes. The offers are read during
   * the COPY, so the setting is read just before the commit, on the replacement's connection.
   */
  @Test
  void boundsEachStatementOfAReplacement() {
    Leg offer = leg(ALPHA, 1, "2026-11-20T07:00:00Z", "50.00");
    AtomicReference<String> statementTimeout = new AtomicReference<>();
    List<Leg> offers =
        new AbstractList<>() {
          @Override
          public Leg get(int index) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                  @Override
                  public void beforeCommit(boolean readOnly) {
                    statementTimeout.set(
                        JdbcClient.create(dataSource)
                            .sql("SHOW statement_timeout")
                            .query(String.class)
                            .single());
                  }
                });
            return offer;
          }

          @Override
          public int size() {
            return 1;
          }
        };

    store.replace(ALPHA, offers);

    assertThat(statementTimeout).hasValue("5min");
    assertThat(store.offers(ALPHA)).containsExactly(offer);
  }

  /**
   * The store writes in the transaction of the manager it was built from, so a caller's transaction
   * on that manager rolls its writes back: no second data source can be given to it.
   */
  @Test
  void writesInTheTransactionOfItsManager() {
    DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
    JdbcOfferStore joined = new JdbcOfferStore(transactions);

    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              joined.replace(ALPHA, List.of(leg(ALPHA, 1, "2026-11-20T07:00:00Z", "50.00")));
              status.setRollbackOnly();
            });

    assertThat(joined.offers(ALPHA)).isEmpty();
  }

  @Test
  void replacesAProvidersOffersAtomically() {
    Leg old = leg(ALPHA, 1, "2026-11-20T07:00:00Z", "50.00");
    store.replace(ALPHA, List.of(old));
    Leg first = leg(ALPHA, 2, "2026-11-20T08:00:00Z", "55.00");
    List<Leg> failing =
        new AbstractList<>() {
          @Override
          public Leg get(int index) {
            if (index == 1) {
              throw new IllegalStateException("second offer failed");
            }
            return first;
          }

          @Override
          public int size() {
            return 3;
          }
        };

    assertThatThrownBy(() -> store.replace(ALPHA, failing))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("second offer failed");

    assertThat(store.offers(ALPHA)).containsExactly(old);
  }

  /**
   * A write through a store of its own, in a transaction of its own on another thread, held open
   * once written: the constructor returns then, and {@link #commitOnceAnotherWriteWaits} lets it
   * commit.
   */
  private final class HeldWrite {

    private final CompletableFuture<Integer> backend = new CompletableFuture<>();
    private final CountDownLatch released = new CountDownLatch(1);
    private final CompletableFuture<Void> committed;

    HeldWrite(Consumer<JdbcOfferStore> write) throws Exception {
      DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
      committed =
          CompletableFuture.runAsync(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            write.accept(new JdbcOfferStore(transactions));
                            backend.complete(
                                JdbcClient.create(dataSource)
                                    .sql("SELECT pg_backend_pid()")
                                    .query(Integer.class)
                                    .single());
                            try {
                              released.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(e);
                            }
                          }));
      backend.get(10, TimeUnit.SECONDS);
    }

    /** Waits until a statement of another connection waits for this write, then commits it. */
    void commitOnceAnotherWriteWaits() throws Exception {
      awaitAStatementBlockedBy(JdbcClient.create(dataSource), backend.get());
      released.countDown();
      committed.get(10, TimeUnit.SECONDS);
    }
  }
}
