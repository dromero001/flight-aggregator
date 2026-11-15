package com.flightaggregator.mockgds.gate;

import com.flightaggregator.mockgds.model.Provider;
import java.util.Locale;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts one {@link ProviderGate} in front of each provider's paths, and nothing else. */
@Configuration
@EnableConfigurationProperties(ProviderSettings.class)
class WebConfig implements WebMvcConfigurer {

  private final ProviderSettings settings;
  private final RandomGenerator random;
  private final Sleeper sleeper;

  WebConfig(ProviderSettings settings, RandomGenerator random, Sleeper sleeper) {
    this.settings = settings;
    this.random = random;
    this.sleeper = sleeper;
  }

  /** Thread-safe, unlike the default L32X64MixRandom: requests run concurrently. */
  @Bean
  static RandomGenerator randomGenerator() {
    return new Random();
  }

  @Bean
  static Sleeper sleeper() {
    return Thread::sleep;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    for (var provider : Provider.values()) {
      var gate = new ProviderGate(settings.forProvider(provider), random, sleeper);
      registry
          .addInterceptor(gate)
          .addPathPatterns("/" + provider.name().toLowerCase(Locale.ROOT) + "/**");
    }
  }
}
