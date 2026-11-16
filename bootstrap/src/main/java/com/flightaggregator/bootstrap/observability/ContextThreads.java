package com.flightaggregator.bootstrap.observability;

import io.micrometer.context.ContextSnapshotFactory;
import java.util.concurrent.ThreadFactory;

/**
 * The fan-out threads of the application layer (§6.10: one search, re-price or run is one trace).
 * The application may not use Micrometer (ArchUnit rule 2), so it fans out through a JDK {@link
 * ThreadFactory} built here, which carries the trace context onto each thread it creates.
 */
public final class ContextThreads {

  private ContextThreads() {}

  /**
   * Virtual threads that run their task inside the context, the current observation included, of
   * the thread that created them. A thread-per-task executor creates each thread when a task is
   * submitted, so each task runs in the context of the code that submitted it.
   */
  public static ThreadFactory virtual(ContextSnapshotFactory snapshots) {
    ThreadFactory virtual = Thread.ofVirtual().factory();
    return task -> virtual.newThread(snapshots.captureAll().wrap(task));
  }
}
