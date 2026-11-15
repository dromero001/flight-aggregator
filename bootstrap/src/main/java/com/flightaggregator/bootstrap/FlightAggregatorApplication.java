package com.flightaggregator.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The flight aggregator: the provider adapters, the stores and the ingestion, wired together. */
@SpringBootApplication
public class FlightAggregatorApplication {

  public static void main(String[] args) {
    SpringApplication.run(FlightAggregatorApplication.class, args);
  }
}
