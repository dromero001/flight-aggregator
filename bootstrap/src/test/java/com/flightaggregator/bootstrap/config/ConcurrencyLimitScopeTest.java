package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.InvocationRejectedException;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.resilience.annotation.ConcurrencyLimit.ThrottlePolicy;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Learning test (plan 5, Task 2): what Spring Framework 7.0.10's {@code @ConcurrencyLimit} counts
 * as one limit, under Boot's AOP defaults (class proxies, so the class is not final).
 */
class ConcurrencyLimitScopeTest {

  interface Operations {
    void hold();

    void other();
  }

  /** Each method carries its own annotation, as the brief puts it on the three operations. */
  static class AnnotatedMethods implements Operations {

    private final Gate gate;

    AnnotatedMethods(Gate gate) {
      this.gate = gate;
    }

    @Override
    @ConcurrencyLimit(limit = 1, policy = ThrottlePolicy.REJECT)
    public void hold() {
      gate.hold();
    }

    @Override
    @ConcurrencyLimit(limit = 1, policy = ThrottlePolicy.REJECT)
    public void other() {}
  }

  /** Holds a call until it is released, and says when one is held. */
  static final class Gate {

    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    void hold() {
      entered.countDown();
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableResilientMethods
  static class MethodsConfig {

    @Bean
    Gate gate() {
      return new Gate();
    }

    @Bean
    Operations operations(Gate gate) {
      return new AnnotatedMethods(gate);
    }
  }

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class));

  /**
   * One limit per method: while {@code hold()} uses up its limit of 1, a second {@code hold()} is
   * rejected but {@code other()} still runs, so the three operations would not share one limit.
   */
  @Test
  void countsAMethodLevelLimitPerMethod() {
    runner
        .withUserConfiguration(MethodsConfig.class)
        .run(
            context -> {
              Operations operations = context.getBean(Operations.class);
              whileHeld(
                  operations,
                  context.getBean(Gate.class),
                  () -> {
                    assertThatThrownBy(operations::hold)
                        .isInstanceOf(InvocationRejectedException.class);
                    operations.other(); // not rejected: its own limit
                  });
            });
  }

  /** One annotation on the class; {@code exempt()} opts out with an unbounded limit of its own. */
  @ConcurrencyLimit(limit = 1, policy = ThrottlePolicy.REJECT)
  static class AnnotatedClass implements Operations {

    private final Gate gate;

    AnnotatedClass(Gate gate) {
      this.gate = gate;
    }

    @Override
    public void hold() {
      gate.hold();
    }

    @Override
    public void other() {}

    @ConcurrencyLimit(-1)
    public void exempt() {}
  }

  @Configuration(proxyBeanMethods = false)
  @EnableResilientMethods
  static class ClassConfig {

    @Bean
    Gate gate() {
      return new Gate();
    }

    @Bean
    AnnotatedClass first(Gate gate) {
      return new AnnotatedClass(gate);
    }

    @Bean
    AnnotatedClass second(Gate gate) {
      return new AnnotatedClass(gate);
    }
  }

  /**
   * One limit per proxied instance, shared by every method of the class: while {@code hold()} uses
   * it up, {@code other()} and even {@code toString()} are rejected, a method annotated with {@code
   * -1} still runs, and another bean of the same class keeps its own limit.
   */
  @Test
  void sharesAClassLevelLimitAcrossTheMethodsOfOneInstance() {
    runner
        .withUserConfiguration(ClassConfig.class)
        .run(
            context -> {
              AnnotatedClass first = context.getBean("first", AnnotatedClass.class);
              AnnotatedClass second = context.getBean("second", AnnotatedClass.class);
              whileHeld(
                  first,
                  context.getBean(Gate.class),
                  () -> {
                    assertThatThrownBy(first::other)
                        .isInstanceOf(InvocationRejectedException.class);
                    assertThatThrownBy(first::toString)
                        .isInstanceOf(InvocationRejectedException.class);
                    first.exempt();
                    second.other();
                  });
            });
  }

  /** Runs the checks while another thread is inside {@code operations.hold()}, then releases it. */
  private static void whileHeld(Operations operations, Gate gate, Runnable checks)
      throws InterruptedException {
    CompletableFuture<Void> held = CompletableFuture.runAsync(operations::hold);
    try {
      assertThat(gate.entered.await(5, TimeUnit.SECONDS)).isTrue();
      checks.run();
    } finally {
      gate.release.countDown();
      held.join();
    }
  }
}
