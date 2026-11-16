package com.flightaggregator.bootstrap;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.core.annotation.AliasFor;

/**
 * A Spring test of the whole application, on its own configuration: providers where nothing
 * listens, so that a provider call fails at once, no scheduled ingestion, and the containers it
 * names, the PostgreSQL of {@link PostgresContainers} unless it names others.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(
    properties = {
      AggregatorTest.ALPHA_URL,
      AggregatorTest.ALPHA_KEY,
      AggregatorTest.BETA_URL,
      AggregatorTest.BETA_KEY,
      AggregatorTest.GAMMA_URL,
      AggregatorTest.GAMMA_KEY,
      "ingestion.schedule.enabled=false"
    })
@ImportTestcontainers
public @interface AggregatorTest {

  // Nothing listens on port 1. FlightAggregatorApplicationTest checks that no key is ever printed.
  String ALPHA_URL = "providers.alpha.base-url=http://localhost:1/";
  String ALPHA_KEY = "providers.alpha.api-key=test-alpha-key";
  String BETA_URL = "providers.beta.base-url=http://localhost:1/";
  String BETA_KEY = "providers.beta.api-key=test-beta-key";
  String GAMMA_URL = "providers.gamma.base-url=http://localhost:1/";
  String GAMMA_KEY = "providers.gamma.api-key=test-gamma-key";

  /** A mock servlet environment, unless the test needs a real server. */
  @AliasFor(annotation = SpringBootTest.class)
  SpringBootTest.WebEnvironment webEnvironment() default SpringBootTest.WebEnvironment.MOCK;

  /** The containers Boot starts for the test, and whose property sources it runs. */
  @AliasFor(annotation = ImportTestcontainers.class, attribute = "value")
  Class<?>[] containers() default PostgresContainers.class;
}
