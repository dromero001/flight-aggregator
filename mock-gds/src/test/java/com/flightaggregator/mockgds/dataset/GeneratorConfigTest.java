package com.flightaggregator.mockgds.dataset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class GeneratorConfigTest {

    private static final Duration BUCKET = Duration.ofMinutes(15);

    @Test
    void rejectsAnEmptyWindow() {
        assertThatThrownBy(() -> new GeneratorConfig(1, 0, 1, 1, 1.0, 0.1, 0.1, 0.01, BUCKET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSharesOutsideZeroToOne() {
        assertThatThrownBy(() -> new GeneratorConfig(1, 30, 1, 1, 1.0, -0.1, 0.1, 0.01, BUCKET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeneratorConfig(1, 30, 1, 1, 1.0, 0.1, 0.1, 1.5, BUCKET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMultiProviderSharesAddingUpToMoreThanOne() {
        assertThatThrownBy(() -> new GeneratorConfig(1, 30, 1, 1, 1.0, 0.6, 0.6, 0.01, BUCKET))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
