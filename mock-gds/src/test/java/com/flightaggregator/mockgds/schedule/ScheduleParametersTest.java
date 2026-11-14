package com.flightaggregator.mockgds.schedule;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ScheduleParametersTest {

    @Test
    void rejectsNonHubRoutesWithoutDailyFlights() {
        assertThatThrownBy(() -> new ScheduleParameters(1, 2, 0, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDailyFlightsPerNonHubRoute");
    }
}
