package com.flightaggregator.application.port.in;

/** Downloads every provider and rebuilds the itineraries. */
public interface IngestInventory {

  IngestionReport ingest();
}
