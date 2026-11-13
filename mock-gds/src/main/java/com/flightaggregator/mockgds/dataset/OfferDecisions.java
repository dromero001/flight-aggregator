package com.flightaggregator.mockgds.dataset;

import com.flightaggregator.mockgds.model.LegKey;
import com.flightaggregator.mockgds.model.Provider;
import com.flightaggregator.mockgds.random.SeededRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Seeded decisions about each offer: which providers sell a leg, and whether a provider serves it malformed. */
final class OfferDecisions {

    private static final List<Provider> ALL = List.of(Provider.values());

    private final GeneratorConfig config;

    OfferDecisions(GeneratorConfig config) {
        this.config = config;
    }

    /** One, two or all three providers, in their declaration order. */
    List<Provider> sellersOf(LegKey leg) {
        var random = SeededRandom.derive(config.seed(), "sellers", leg.toString());
        double draw = random.nextDouble();
        if (draw < config.threeProviderShare()) {
            return ALL;
        }
        if (draw < config.threeProviderShare() + config.twoProviderShare()) {
            var sellers = new ArrayList<>(ALL);
            sellers.remove(random.nextInt(ALL.size()));
            return List.copyOf(sellers);
        }
        return List.of(ALL.get(random.nextInt(ALL.size())));
    }

    Optional<Defect> defectOf(LegKey leg, Provider provider) {
        var random = SeededRandom.derive(config.seed(), "defect", leg.toString(), provider.name());
        if (random.nextDouble() >= config.malformedShare()) {
            return Optional.empty();
        }
        var defects = Arrays.asList(Defect.values());
        return Optional.of(defects.get(random.nextInt(defects.size())));
    }
}
