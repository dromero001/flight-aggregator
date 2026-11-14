package com.flightaggregator.mockgds.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.mockgds.dataset.Dataset;
import com.flightaggregator.mockgds.dataset.DatasetGenerator;
import com.flightaggregator.mockgds.dataset.Defect;
import com.flightaggregator.mockgds.dataset.GeneratorConfig;
import com.flightaggregator.mockgds.dataset.MalformedLeg;
import com.flightaggregator.mockgds.model.Leg;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.reference.ReferenceData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Checks that the default dataset covers every case of §4 of the design and yields about one million itineraries,
 * about 40% of them on a single airline, and prints the statistics recorded in the progress report.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasetCoverageTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final long TARGET_ITINERARIES = 1_000_000;

    private Dataset dataset;
    private Duration generationTime;
    private List<Leg> sellableLegs;
    private ItineraryCount itineraries;
    private long rejectedForVisitingAnAirportTwice;
    private ConnectionCensus connections;

    @BeforeAll
    void generateAndAnalyse() {
        var generator = new DatasetGenerator(GeneratorConfig.defaults(), ReferenceData.standard());
        long start = System.nanoTime();
        dataset = generator.generate(Clock.fixed(NOW, ZoneOffset.UTC));
        generationTime = Duration.ofNanos(System.nanoTime() - start);
        sellableLegs = ItineraryEnumerator.sellableLegs(dataset.validLegs(), NOW);
        var enumerator = ItineraryEnumerator.of(dataset.validLegs(), NOW);
        itineraries = ItineraryCount.of(enumerator);
        var connectedSequences = new LongAdder();
        enumerator.forEachConnectedSequence(sequence -> connectedSequences.increment());
        rejectedForVisitingAnAirportTwice = connectedSequences.sum() - itineraries.total();
        connections = ConnectionCensus.of(sellableLegs);
        printStatistics();
    }

    @Test
    void yieldsAboutOneMillionItineraries() {
        assertThat(itineraries.total()).isBetween(TARGET_ITINERARIES * 95 / 100, TARGET_ITINERARIES * 105 / 100);
    }

    @Test
    void generatesFast() {
        assertThat(generationTime).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void containsDirectOneStopAndTwoStopItineraries() {
        assertThat(itineraries.direct()).isPositive();
        assertThat(itineraries.oneStop()).isPositive();
        assertThat(itineraries.twoStops()).isPositive();
    }

    @Test
    void containsEveryKindOfConnectionAndNonConnection() {
        assertThat(connections.valid()).isPositive();
        assertThat(connections.belowMinimumConnectionTime()).isPositive();
        assertThat(connections.layoverOverTwentyFourHours()).isPositive();
        assertThat(connections.differentAirport()).isPositive();
    }

    @Test
    void containsConnectedLegsThatTheBuilderMustRejectForVisitingAnAirportTwice() {
        assertThat(rejectedForVisitingAnAirportTwice).isPositive();
    }

    @Test
    void aboutFortyPercentOfItinerariesAreOperatedByASingleAirline() {
        assertThat(itineraries.singleAirlineShare()).isBetween(0.35, 0.45);
    }

    @Test
    void containsInterliningItineraries() {
        assertThat(itineraries.interlining()).isPositive();
    }

    @Test
    void containsRoutesReachableOnlyWithTwoStops() {
        assertThat(itineraries.pairsReachableOnlyWithTwoStops()).contains("SVQ-NCE");
    }

    @Test
    void containsLegsThatHaveAlreadyDeparted() {
        assertThat(departedLegs()).isPositive();
    }

    @Test
    void containsLegsSoldByTwoAndByThreeProviders() {
        var offersPerLeg = offersPerLeg();

        assertThat(offersPerLeg).containsKeys(1L, 2L, 3L);
    }

    @Test
    void containsMalformedRecordsOfEveryDefectForEveryProvider() {
        Arrays.stream(Provider.values()).forEach(provider -> assertThat(defectsOf(provider).keySet())
                .as(provider.name()).containsExactlyInAnyOrder(Defect.values()));
    }

    private long departedLegs() {
        return dataset.validLegs().stream().map(Leg::key).distinct()
                .filter(key -> !key.departure().isAfter(NOW))
                .count();
    }

    /** Number of distinct legs by how many providers sell them. */
    private Map<Long, Long> offersPerLeg() {
        return dataset.validLegs().stream()
                .collect(Collectors.groupingBy(Leg::key, Collectors.counting()))
                .values().stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private Map<Defect, Long> defectsOf(Provider provider) {
        return dataset.recordsFor(provider).stream()
                .filter(MalformedLeg.class::isInstance)
                .map(MalformedLeg.class::cast)
                .collect(Collectors.groupingBy(MalformedLeg::defect, Collectors.counting()));
    }

    private void printStatistics() {
        System.out.println("=== Dataset statistics (default config, clock " + NOW + ") ===");
        System.out.println("Generation time: " + generationTime.toMillis() + " ms");
        for (var provider : Provider.values()) {
            var records = dataset.recordsFor(provider);
            System.out.printf("%s: %d records, %d malformed %s%n", provider, records.size(),
                    records.stream().filter(MalformedLeg.class::isInstance).count(), defectsOf(provider));
        }
        System.out.println("Distinct legs by number of providers selling them: " + offersPerLeg());
        System.out.println("Already departed legs: " + departedLegs());
        System.out.println("Sellable legs after dedup: " + sellableLegs.size());
        System.out.println("Connections: " + connections);
        System.out.printf("Itineraries: direct=%d, oneStop=%d, twoStops=%d, total=%d%n",
                itineraries.direct(), itineraries.oneStop(), itineraries.twoStops(), itineraries.total());
        System.out.println("Interlining itineraries: " + itineraries.interlining());
        System.out.printf(Locale.ROOT, "Single-airline itineraries: %.1f%% %s%n", 100 * itineraries.singleAirlineShare(),
                itineraries.singleAirlineByCarrier());
        System.out.println("Rejected for visiting an airport twice: " + rejectedForVisitingAnAirportTwice);
        System.out.println("O-D pairs reachable only with two stops: " + itineraries.pairsReachableOnlyWithTwoStops().size());
    }
}
