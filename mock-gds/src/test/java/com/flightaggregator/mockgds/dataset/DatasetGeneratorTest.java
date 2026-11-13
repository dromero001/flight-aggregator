package com.flightaggregator.mockgds.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.flightaggregator.mockgds.model.Leg;
import com.flightaggregator.mockgds.model.LegKey;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.pricing.PriceModel;
import com.flightaggregator.mockgds.reference.ReferenceData;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DatasetGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final GeneratorConfig CONFIG = GeneratorConfig.defaults();

    private final DatasetGenerator generator = new DatasetGenerator(CONFIG, ReferenceData.standard());
    private final Dataset dataset = generator.generate(clockAt(NOW));

    @Test
    void legsDepartInsideTheRollingWindowStartingOnTheClockDate() {
        var today = LocalDate.of(2026, 11, 13);

        assertThat(dataset.validLegs()).isNotEmpty().allSatisfy(leg -> assertThat(leg.departure().toLocalDate())
                .isBetween(today, today.plusDays(CONFIG.windowDays() - 1)));
    }

    @Test
    void includesLegsThatHaveAlreadyDepartedAtTheClockInstant() {
        assertThat(dataset.validLegs()).anyMatch(leg -> leg.departure().toInstant().isBefore(NOW));
    }

    @Test
    void theSameSeedAndClockProduceTheSameDataset() {
        assertThat(generator.generate(clockAt(NOW))).isEqualTo(dataset);
    }

    @Test
    void anotherPriceBucketOfTheSameDayKeepsTheLegsButMovesThePrices() {
        var later = generator.generate(clockAt(NOW.plus(CONFIG.priceBucket())));

        assertThat(keys(later.validLegs())).isEqualTo(keys(dataset.validLegs()));
        assertThat(later.validLegs()).isNotEqualTo(dataset.validLegs());
    }

    @Test
    void rollingTheWindowByOneDayKeepsTheLegsOfTheOverlappingDays() {
        var tomorrow = generator.generate(clockAt(NOW.plus(Duration.ofDays(1))));
        var overlapStart = LocalDate.of(2026, 11, 14);
        var overlapEnd = LocalDate.of(2026, 11, 13).plusDays(CONFIG.windowDays());

        assertThat(keysDepartingBetween(tomorrow, overlapStart, overlapEnd))
                .isNotEmpty()
                .isEqualTo(keysDepartingBetween(dataset, overlapStart, overlapEnd));
    }

    @Test
    void pricesAreTheProvidersQuotesAtTheClockInstant() {
        var prices = new PriceModel(CONFIG.seed(), CONFIG.priceBucket(), ReferenceData.standard());

        assertThat(dataset.validLegs().subList(0, 100)).allSatisfy(leg ->
                assertThat(leg.priceEur()).isEqualTo(prices.price(leg.key(), leg.provider(), NOW)));
    }

    @Test
    void someLegsAreSoldByTwoOrThreeProvidersAtDifferentPrices() {
        var providersByKey = allRecords().stream().collect(Collectors.groupingBy(
                record -> record.leg().key(), Collectors.mapping(ProviderRecord::provider, Collectors.toSet())));
        double total = providersByKey.size();

        assertThat(count(providersByKey, 2) / total).isCloseTo(CONFIG.twoProviderShare(), within(0.02));
        assertThat(count(providersByKey, 3) / total).isCloseTo(CONFIG.threeProviderShare(), within(0.02));
        var sharedOffers = dataset.validLegs().stream().collect(Collectors.groupingBy(Leg::key)).values().stream()
                .filter(offers -> offers.size() > 1)
                .toList();
        var withDifferentPrices = sharedOffers.stream()
                .filter(offers -> offers.stream().map(Leg::priceEur).distinct().count() == offers.size())
                .count();
        assertThat(sharedOffers).isNotEmpty();
        assertThat((double) withDifferentPrices / sharedOffers.size()).isGreaterThan(0.99);
    }

    @Test
    void everyProviderServesAShareOfMalformedRecordsWithEveryDefect() {
        for (var provider : Provider.values()) {
            var records = dataset.recordsFor(provider);
            var malformed = records.stream().filter(MalformedLeg.class::isInstance).map(MalformedLeg.class::cast).toList();

            assertThat((double) malformed.size() / records.size()).as(provider.name())
                    .isCloseTo(CONFIG.malformedShare(), within(fourSigma(CONFIG.malformedShare(), records.size())));
            assertThat(malformed.stream().map(MalformedLeg::defect).collect(Collectors.toSet()))
                    .containsExactlyInAnyOrder(Defect.values());
        }
    }

    @Test
    void malformedRecordsReplaceTheValidOfferOfThatProvider() {
        var validOffers = dataset.validLegs().stream()
                .map(leg -> leg.provider() + "|" + leg.key())
                .collect(Collectors.toSet());

        assertThat(allRecords()).filteredOn(MalformedLeg.class::isInstance)
                .allSatisfy(record -> assertThat(validOffers).doesNotContain(record.provider() + "|" + record.leg().key()));
    }

    @Test
    void validLegsAreTheValidRecordsOfAllProviders() {
        var validRecords = allRecords().stream()
                .filter(ValidLeg.class::isInstance)
                .map(ProviderRecord::leg)
                .toList();

        assertThat(dataset.validLegs()).containsExactlyInAnyOrderElementsOf(validRecords);
        assertThat(dataset.validLegs()).allSatisfy(leg ->
                assertThat(dataset.recordsFor(leg.provider())).contains(new ValidLeg(leg)));
    }

    private List<ProviderRecord> allRecords() {
        return Arrays.stream(Provider.values()).flatMap(provider -> dataset.recordsFor(provider).stream()).toList();
    }

    /** Four standard deviations of the share observed in {@code samples} draws with probability {@code share}. */
    private static double fourSigma(double share, int samples) {
        return 4 * Math.sqrt(share * (1 - share) / samples);
    }

    private static long count(Map<LegKey, Set<Provider>> providersByKey, int providers) {
        return providersByKey.values().stream().filter(set -> set.size() == providers).count();
    }

    private static Set<LegKey> keys(List<Leg> legs) {
        return legs.stream().map(Leg::key).collect(Collectors.toSet());
    }

    private static Set<LegKey> keysDepartingBetween(Dataset dataset, LocalDate first, LocalDate end) {
        return dataset.validLegs().stream()
                .filter(leg -> !leg.departure().toLocalDate().isBefore(first) && leg.departure().toLocalDate().isBefore(end))
                .map(Leg::key)
                .collect(Collectors.toSet());
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
