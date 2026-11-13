package com.flightaggregator.mockgds.calibration;

import com.flightaggregator.mockgds.model.Leg;
import java.time.Duration;

/**
 * The connection rules of §4 of the design, restated for test-only calibration. The production rules will live in
 * the domain module; this copy only estimates how many itineraries the dataset yields.
 */
final class ConnectionRules {

    static final Duration MINIMUM_CONNECTION_TIME = Duration.ofMinutes(45);
    static final Duration MAXIMUM_LAYOVER = Duration.ofHours(24);

    private ConnectionRules() {
    }

    static Duration layover(Leg inbound, Leg outbound) {
        return Duration.between(inbound.arrival(), outbound.departure());
    }

    static boolean connects(Leg inbound, Leg outbound) {
        if (!inbound.destination().equals(outbound.origin())) {
            return false;
        }
        var layover = layover(inbound, outbound);
        return layover.compareTo(MINIMUM_CONNECTION_TIME) >= 0 && layover.compareTo(MAXIMUM_LAYOVER) <= 0;
    }
}
