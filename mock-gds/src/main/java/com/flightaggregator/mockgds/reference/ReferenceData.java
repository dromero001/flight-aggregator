package com.flightaggregator.mockgds.reference;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Airports and airlines the mock providers fly, with lookups by code. */
public final class ReferenceData {

    private final List<Airport> airports;
    private final List<Airline> airlines;
    private final Map<String, Airport> airportsByIata;

    public ReferenceData(List<Airport> airports, List<Airline> airlines) {
        this.airports = List.copyOf(airports);
        this.airlines = List.copyOf(airlines);
        this.airportsByIata = airports.stream().collect(Collectors.toUnmodifiableMap(Airport::iata, Function.identity()));
    }

    /** The catalogue used by the mock providers. */
    public static ReferenceData standard() {
        return new ReferenceData(StandardCatalogue.AIRPORTS, StandardCatalogue.AIRLINES);
    }

    public List<Airport> airports() {
        return airports;
    }

    public List<Airline> airlines() {
        return airlines;
    }

    public Airport airport(String iata) {
        var airport = airportsByIata.get(iata);
        if (airport == null) {
            throw new NoSuchElementException("Unknown airport: " + iata);
        }
        return airport;
    }

    public Airline airline(String code) {
        return airlines.stream()
                .filter(airline -> airline.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Unknown airline: " + code));
    }

    public List<Airline> carriersBasedAt(Airport hub) {
        return airlines.stream()
                .filter(airline -> airline.homeHub().filter(hub.iata()::equals).isPresent())
                .toList();
    }
}
