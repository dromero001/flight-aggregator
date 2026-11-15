package com.flightaggregator.application.port.in;

import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Carrier;
import com.flightaggregator.domain.Money;
import java.util.Optional;

/**
 * What a search asks for: its filters, each optional and combinable (§4), and its sort order. Max
 * Price is inclusive; Carrier matches a trip whose every leg that carrier operates.
 */
public record SearchCriteria(
    Optional<Airport> origin,
    Optional<Airport> destination,
    Optional<Money> maxPrice,
    Optional<Carrier> carrier,
    SortOrder sort) {}
