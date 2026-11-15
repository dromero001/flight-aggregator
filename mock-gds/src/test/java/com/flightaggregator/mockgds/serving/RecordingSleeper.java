package com.flightaggregator.mockgds.serving;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Records the sleeps instead of waiting, so tests stay fast. */
class RecordingSleeper implements Sleeper {

  private final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());

  @Override
  public void sleep(Duration duration) {
    sleeps.add(duration);
  }

  List<Duration> sleeps() {
    return List.copyOf(sleeps);
  }
}
