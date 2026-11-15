package com.flightaggregator.application.port.out;

import com.flightaggregator.domain.Itinerary;
import java.util.function.Consumer;

/** The producer of a generation: it pushes every itinerary into the sink, one at a time. */
@FunctionalInterface
public interface ItineraryWriter {

  void writeTo(Consumer<Itinerary> sink);
}
