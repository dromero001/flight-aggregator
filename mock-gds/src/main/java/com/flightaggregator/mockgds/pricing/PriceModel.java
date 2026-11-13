package com.flightaggregator.mockgds.pricing;

import com.flightaggregator.mockgds.model.LegKey;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.random.SeededRandom;
import com.flightaggregator.mockgds.reference.ReferenceData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Fares of the mock providers, with deterministic price drift (§6.3 of the design).
 *
 * <p>A price is a pure function of the seed, the leg, the provider and the time bucket that contains the quoting
 * instant: it is stable inside a bucket and moves from one bucket to the next. It is the product of
 * <ul>
 *   <li>a base fare that grows with the great-circle distance;</li>
 *   <li>a fare level of the leg (some flights are expensive, some cheap);</li>
 *   <li>a provider margin, so providers quote different prices for the same leg;</li>
 *   <li>a drift factor that changes with every bucket.</li>
 * </ul>
 */
public final class PriceModel {

    private static final double BASE_FARE_EUR = 30.0;
    private static final double FARE_PER_KM_EUR = 0.09;
    private static final double MIN_LEG_LEVEL = 0.7;
    private static final double MAX_LEG_LEVEL = 1.6;
    private static final double MIN_PROVIDER_MARGIN = 0.95;
    private static final double MAX_PROVIDER_MARGIN = 1.10;
    private static final double MIN_DRIFT = 0.90;
    private static final double MAX_DRIFT = 1.10;

    private final long seed;
    private final Duration bucket;
    private final ReferenceData reference;

    public PriceModel(long seed, Duration bucket, ReferenceData reference) {
        if (bucket.toSeconds() < 1) {
            throw new IllegalArgumentException("The price bucket must be at least one second: " + bucket);
        }
        this.seed = seed;
        this.bucket = bucket;
        this.reference = Objects.requireNonNull(reference);
    }

    /** The price {@code provider} quotes for the leg at instant {@code at}, in euros with two decimals. */
    public BigDecimal price(LegKey leg, Provider provider, Instant at) {
        double distanceKm = reference.airport(leg.origin()).distanceKmTo(reference.airport(leg.destination()));
        double price = (BASE_FARE_EUR + FARE_PER_KM_EUR * distanceKm)
                * factor(MIN_LEG_LEVEL, MAX_LEG_LEVEL, "level", leg.toString())
                * factor(MIN_PROVIDER_MARGIN, MAX_PROVIDER_MARGIN, "margin", leg.toString(), provider.name())
                * factor(MIN_DRIFT, MAX_DRIFT, "drift", leg.toString(), provider.name(), bucketOf(at));
        return BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP);
    }

    private double factor(double min, double max, String... keyParts) {
        RandomGenerator random = SeededRandom.derive(seed, keyParts);
        return random.nextDouble(min, max);
    }

    private String bucketOf(Instant at) {
        return String.valueOf(Math.floorDiv(at.getEpochSecond(), bucket.toSeconds()));
    }
}
