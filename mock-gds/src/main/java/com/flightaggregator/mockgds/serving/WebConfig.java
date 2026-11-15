package com.flightaggregator.mockgds.serving;

import java.util.random.RandomGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the {@link ProviderGate} in front of the three provider paths, and nothing else. */
@Configuration
@EnableConfigurationProperties(ProviderSettings.class)
class WebConfig implements WebMvcConfigurer {

  private final ProviderGate gate;

  WebConfig(ProviderGate gate) {
    this.gate = gate;
  }

  @Bean
  static RandomGenerator randomGenerator() {
    return RandomGenerator.getDefault();
  }

  @Bean
  static Sleeper sleeper() {
    return Thread::sleep;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(gate).addPathPatterns("/alpha/**", "/beta/**", "/gamma/**");
  }
}
