package com.flightaggregator.bootstrap.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ContextThreadsTest {

  private final ObservationRegistry observations = ObservationRegistry.create();

  {
    // A registry with no handler would only ever hand out no-op observations.
    observations.observationConfig().observationHandler(context -> true);
  }

  /**
   * The thread is created on the caller's thread, as a thread-per-task executor does on submit, and
   * runs its task inside the observation that was current there (§6.10: one run, one trace).
   */
  @Test
  void runsTheTaskInTheContextOfTheThreadThatCreatedIt() throws InterruptedException {
    ThreadFactory threads = ContextThreads.virtual(ContextSnapshotFactory.builder().build());
    Observation run = Observation.start("run", observations);
    AtomicReference<Observation> current = new AtomicReference<>();
    Thread thread =
        run.scoped(
            () -> threads.newThread(() -> current.set(observations.getCurrentObservation())));
    thread.start();
    thread.join();
    run.stop();

    assertThat(current.get()).isSameAs(run);
  }
}
