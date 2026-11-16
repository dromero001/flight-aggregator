package com.flightaggregator.application.service.fakes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;

/** Virtual threads, each one it creates kept, so a test can tell where a task ran. */
public final class RecordingThreads implements ThreadFactory {

  private final ThreadFactory virtual = Thread.ofVirtual().factory();
  private final List<Thread> created = new CopyOnWriteArrayList<>();

  @Override
  public Thread newThread(Runnable task) {
    Thread thread = virtual.newThread(task);
    created.add(thread);
    return thread;
  }

  public List<Thread> created() {
    return List.copyOf(created);
  }
}
