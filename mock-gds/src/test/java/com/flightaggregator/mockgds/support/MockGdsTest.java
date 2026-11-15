package com.flightaggregator.mockgds.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A Spring test of the mock GDS over HTTP: a random port, the keys of {@link ProviderKeys}, and no
 * latency or injected errors.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "mockgds.providers.alpha.api-key=test-alpha-key",
      "mockgds.providers.beta.api-key=test-beta-key",
      "mockgds.providers.gamma.api-key=test-gamma-key",
      "mockgds.providers.alpha.fast-latency-min=0",
      "mockgds.providers.alpha.fast-latency-max=0",
      "mockgds.providers.alpha.slow-latency-min=0",
      "mockgds.providers.alpha.slow-latency-max=0",
      "mockgds.providers.beta.fast-latency-min=0",
      "mockgds.providers.beta.fast-latency-max=0",
      "mockgds.providers.beta.slow-latency-min=0",
      "mockgds.providers.beta.slow-latency-max=0",
      "mockgds.providers.gamma.fast-latency-min=0",
      "mockgds.providers.gamma.fast-latency-max=0",
      "mockgds.providers.gamma.slow-latency-min=0",
      "mockgds.providers.gamma.slow-latency-max=0",
      "mockgds.providers.alpha.error-rate=0",
      "mockgds.providers.beta.error-rate=0",
      "mockgds.providers.gamma.error-rate=0"
    })
@AutoConfigureTestRestTemplate
public @interface MockGdsTest {}
