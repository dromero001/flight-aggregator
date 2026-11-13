package com.flightaggregator.mockgds.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flightaggregator.mockgds.model.LegKey;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.reference.ReferenceData;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PriceModelTest {

    private static final Duration BUCKET = Duration.ofMinutes(15);
    private static final Instant BUCKET_START = Instant.parse("2026-11-13T12:00:00Z");
    private static final LegKey MAD_LHR = new LegKey("IB", 3170, "MAD", "LHR", Instant.parse("2026-11-20T07:15:00Z"));
    private static final LegKey MAD_BCN = new LegKey("IB", 1004, "MAD", "BCN", Instant.parse("2026-11-20T07:15:00Z"));
    private static final LegKey MAD_JFK = new LegKey("IB", 1101, "MAD", "JFK", Instant.parse("2026-11-20T10:00:00Z"));

    private final PriceModel model = new PriceModel(42, BUCKET, ReferenceData.standard());

    @Test
    void thePriceIsStableWithinATimeBucket() {
        assertThat(model.price(MAD_LHR, Provider.ALPHA, BUCKET_START))
                .isEqualTo(model.price(MAD_LHR, Provider.ALPHA, BUCKET_START.plus(BUCKET).minusSeconds(1)));
    }

    @Test
    void thePriceDriftsAcrossTimeBuckets() {
        var pricesOverTenBuckets = IntStream.range(0, 10)
                .mapToObj(bucket -> model.price(MAD_LHR, Provider.ALPHA, BUCKET_START.plus(BUCKET.multipliedBy(bucket))))
                .distinct()
                .count();

        assertThat(pricesOverTenBuckets).isGreaterThan(5);
    }

    @Test
    void eachProviderQuotesItsOwnPriceForTheSameLeg() {
        assertThat(model.price(MAD_LHR, Provider.ALPHA, BUCKET_START))
                .isNotEqualTo(model.price(MAD_LHR, Provider.BETA, BUCKET_START));
    }

    @Test
    void theSeedChangesThePrices() {
        var otherSeed = new PriceModel(43, BUCKET, ReferenceData.standard());

        assertThat(otherSeed.price(MAD_LHR, Provider.ALPHA, BUCKET_START))
                .isNotEqualTo(model.price(MAD_LHR, Provider.ALPHA, BUCKET_START));
    }

    @Test
    void longerFlightsCostMore() {
        assertThat(model.price(MAD_JFK, Provider.ALPHA, BUCKET_START))
                .isGreaterThan(model.price(MAD_BCN, Provider.ALPHA, BUCKET_START));
    }

    @Test
    void pricesArePositiveEurosWithTwoDecimals() {
        IntStream.range(0, 200).forEach(bucket -> {
            BigDecimal price = model.price(MAD_BCN, Provider.GAMMA, BUCKET_START.plus(BUCKET.multipliedBy(bucket)));
            assertThat(price.scale()).isEqualTo(2);
            assertThat(price).isPositive();
        });
    }

    @Test
    void rejectsABucketShorterThanOneSecond() {
        assertThatThrownBy(() -> new PriceModel(42, Duration.ZERO, ReferenceData.standard()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PriceModel(42, Duration.ofMillis(500), ReferenceData.standard()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
