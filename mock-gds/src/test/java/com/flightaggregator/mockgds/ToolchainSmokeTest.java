package com.flightaggregator.mockgds;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolchainSmokeTest {

    @Test
    void runsOnJava25() {
        assertThat(Runtime.version().feature()).isEqualTo(25);
    }
}
