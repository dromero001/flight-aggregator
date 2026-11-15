package com.flightaggregator.mockgds.serving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.mockgds.dataset.Dataset;
import com.flightaggregator.mockgds.dataset.Defect;
import com.flightaggregator.mockgds.dataset.MalformedLeg;
import com.flightaggregator.mockgds.dataset.ProviderRecord;
import com.flightaggregator.mockgds.dataset.ValidLeg;
import com.flightaggregator.mockgds.model.Leg;
import com.flightaggregator.mockgds.model.LegKey;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.pricing.PriceModel;
import com.flightaggregator.mockgds.reference.ReferenceData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ProviderInventoryTest {

  private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
  private static final PriceModel PRICES =
      new PriceModel(42, Duration.ofMinutes(15), ReferenceData.standard());
  private static final List<ProviderRecord> ALPHA_RECORDS =
      IntStream.range(0, 10).<ProviderRecord>mapToObj(ProviderInventoryTest::alphaLeg).toList();

  private final Dataset dataset = new Dataset(ALPHA_RECORDS);

  @Test
  void servesARangeOfTheProvidersRecordsInOrder() {
    var page = inventoryAt(NOW).page(Provider.ALPHA, RecordFilter.all(), 2, 3);

    assertThat(page.records())
        .extracting(PricedRecord::record)
        .isEqualTo(ALPHA_RECORDS.subList(2, 5));
    assertThat(page.offset()).isEqualTo(2);
    assertThat(page.total()).isEqualTo(10);
    assertThat(page.isLast()).isFalse();
  }

  @Test
  void servesAShortLastPage() {
    var page = inventoryAt(NOW).page(Provider.ALPHA, RecordFilter.all(), 8, 5);

    assertThat(page.records()).hasSize(2);
    assertThat(page.isLast()).isTrue();
  }

  @Test
  void servesAnEmptyPageAfterTheEnd() {
    var page = inventoryAt(NOW).page(Provider.ALPHA, RecordFilter.all(), 10, 5);

    assertThat(page.records()).isEmpty();
    assertThat(page.isLast()).isTrue();
  }

  @Test
  void pricesTheSameRecordAlikeWithinABucket() {
    var first =
        inventoryAt(NOW).page(Provider.ALPHA, RecordFilter.all(), 0, 1).records().get(0).price();
    var fourteenMinutesLater =
        inventoryAt(NOW.plus(Duration.ofMinutes(14)))
            .page(Provider.ALPHA, RecordFilter.all(), 0, 1)
            .records()
            .get(0)
            .price();

    assertThat(fourteenMinutesLater).isEqualTo(first);
  }

  @Test
  void repricesTheRecordInTheNextBucket() {
    var nextBucket = NOW.plus(Duration.ofMinutes(15));
    var record = ALPHA_RECORDS.get(0);

    var price =
        inventoryAt(nextBucket)
            .page(Provider.ALPHA, RecordFilter.all(), 0, 1)
            .records()
            .get(0)
            .price();

    assertThat(price).isEqualTo(PRICES.price(record.leg().key(), Provider.ALPHA, nextBucket));
    assertThat(price).isNotEqualTo(PRICES.price(record.leg().key(), Provider.ALPHA, NOW));
  }

  @Test
  void rejectsANegativeOffsetOrANonPositiveLimit() {
    var inventory = inventoryAt(NOW);

    assertThatThrownBy(() -> inventory.page(Provider.ALPHA, RecordFilter.all(), -1, 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> inventory.page(Provider.ALPHA, RecordFilter.all(), 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aMalformedRecordKeepsItsDefectAndGetsTheDriftedPrice() {
    var leg = ((ValidLeg) ALPHA_RECORDS.get(0)).leg();
    var malformed = new MalformedLeg(leg, Defect.NEGATIVE_PRICE);
    var inventory =
        new ProviderInventory(
            new Dataset(List.of(malformed)), PRICES, Clock.fixed(NOW, ZoneOffset.UTC));

    var served = inventory.page(Provider.ALPHA, RecordFilter.all(), 0, 1).records().get(0);

    assertThat(served.record()).isEqualTo(malformed);
    assertThat(served.price()).isEqualTo(PRICES.price(leg.key(), Provider.ALPHA, NOW));
  }

  @Test
  void filtersByOriginBeforePaging() {
    var records =
        List.<ProviderRecord>of(
            new ValidLeg(leg("BCN", "LHR", 1)),
            new MalformedLeg(leg("MAD", "LHR", 2), Defect.NEGATIVE_PRICE),
            new ValidLeg(leg("BCN", "LHR", 3)),
            new ValidLeg(leg("MAD", "LHR", 4)),
            new ValidLeg(leg("BCN", "LHR", 5)),
            new ValidLeg(leg("MAD", "LHR", 6)));
    var inventory =
        new ProviderInventory(new Dataset(records), PRICES, Clock.fixed(NOW, ZoneOffset.UTC));
    var madrid = RecordFilter.fromOrigin("MAD");

    var first = inventory.page(Provider.ALPHA, madrid, 0, 1);
    var rest = inventory.page(Provider.ALPHA, madrid, 1, 2);

    assertThat(first.records()).extracting(PricedRecord::record).containsExactly(records.get(1));
    assertThat(first.isLast()).isFalse();
    assertThat(rest.records())
        .extracting(PricedRecord::record)
        .containsExactly(records.get(3), records.get(5));
    assertThat(rest.total()).isEqualTo(3);
    assertThat(rest.isLast()).isTrue();
  }

  @Test
  void findsOneFlightByItsKey() {
    var wanted = ((ValidLeg) ALPHA_RECORDS.get(4)).leg();

    var page = inventoryAt(NOW).page(Provider.ALPHA, RecordFilter.flight(wanted.key()), 0, 10);

    assertThat(page.records())
        .extracting(PricedRecord::record)
        .containsExactly(ALPHA_RECORDS.get(4));
    assertThat(page.records().get(0).price())
        .isEqualTo(PRICES.price(wanted.key(), Provider.ALPHA, NOW));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.isLast()).isTrue();
  }

  @Test
  void findsNothingForAFlightItDoesNotSell() {
    var sold = ((ValidLeg) ALPHA_RECORDS.get(4)).leg();
    var otherDay =
        new LegKey("IB", 3004, "MAD", "LHR", sold.key().departure().plus(Duration.ofDays(1)));

    var page = inventoryAt(NOW).page(Provider.ALPHA, RecordFilter.flight(otherDay), 0, 10);

    assertThat(page.records()).isEmpty();
    assertThat(page.isLast()).isTrue();
  }

  private ProviderInventory inventoryAt(Instant instant) {
    return new ProviderInventory(dataset, PRICES, Clock.fixed(instant, ZoneOffset.UTC));
  }

  private static ProviderRecord alphaLeg(int index) {
    return new ValidLeg(leg("MAD", "LHR", index));
  }

  private static Leg leg(String origin, String destination, int index) {
    var departure = OffsetDateTime.parse("2026-11-20T08:00:00+01:00").plusMinutes(index);
    return new Leg(
        "IB",
        3000 + index,
        origin,
        destination,
        departure,
        departure.plusHours(2),
        new BigDecimal("100.00"),
        Provider.ALPHA);
  }
}
