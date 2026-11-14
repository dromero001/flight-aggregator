package com.flightaggregator.mockgds.dataset;

import com.flightaggregator.mockgds.model.Leg;
import com.flightaggregator.mockgds.model.LegKey;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.network.RouteNetwork;
import com.flightaggregator.mockgds.pricing.PriceModel;
import com.flightaggregator.mockgds.reference.ReferenceData;
import com.flightaggregator.mockgds.schedule.DatedFlight;
import com.flightaggregator.mockgds.schedule.FlightSchedule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.stream.Stream;

/**
 * Generates the inventory the three mock providers serve: every flight of the rolling window, sold
 * by one or more providers at their current price, with a small share of malformed records.
 *
 * <p>Deterministic: the same configuration and clock instant always give the same dataset. The
 * window starts on the clock's UTC date, so it includes flights that departed earlier that day.
 */
public final class DatasetGenerator {

  private final GeneratorConfig config;
  private final FlightSchedule schedule;
  private final PriceModel prices;
  private final OfferDecisions decisions;

  public DatasetGenerator(GeneratorConfig config, ReferenceData reference) {
    this.config = config;
    this.schedule =
        FlightSchedule.build(RouteNetwork.build(reference), config.scheduleParameters());
    this.prices = new PriceModel(config.seed(), config.priceBucket(), reference);
    this.decisions = new OfferDecisions(config);
  }

  public Dataset generate(Clock clock) {
    Instant now = clock.instant();
    LocalDate firstDay = LocalDate.ofInstant(now, ZoneOffset.UTC);
    var records =
        schedule.flightsBetween(firstDay, firstDay.plusDays(config.windowDays())).stream()
            .flatMap(flight -> recordsOf(flight, now))
            .toList();
    return new Dataset(records);
  }

  private Stream<ProviderRecord> recordsOf(DatedFlight flight, Instant now) {
    LegKey key = flight.key();
    return decisions.sellersOf(key).stream().map(provider -> recordOf(flight, key, provider, now));
  }

  private ProviderRecord recordOf(DatedFlight flight, LegKey key, Provider provider, Instant now) {
    var leg =
        new Leg(
            key.airline(),
            key.flightNumber(),
            key.origin(),
            key.destination(),
            flight.departure(),
            flight.arrival(),
            prices.price(key, provider, now),
            provider);
    return decisions
        .defectOf(key, provider)
        .<ProviderRecord>map(defect -> new MalformedLeg(leg, defect))
        .orElseGet(() -> new ValidLeg(leg));
  }
}
