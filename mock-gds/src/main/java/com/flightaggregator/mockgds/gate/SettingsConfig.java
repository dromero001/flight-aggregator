package com.flightaggregator.mockgds.gate;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Binds {@link ProviderSettings}, and stops startup on a {@code MOCKGDS_PROVIDERS_} environment
 * variable that names no setting: Boot's unknown-key check leaves the environment out, so such a
 * variable would be ignored. The failure names the variables, never their values.
 */
@Configuration
@EnableConfigurationProperties(ProviderSettings.class)
class SettingsConfig {

  private static final String PREFIX = "MOCKGDS_PROVIDERS_";

  /** The prefix, the provider and the setting, upper-cased without dashes, as Boot reads them. */
  private static final Set<String> KNOWN =
      Arrays.stream(ProviderSettings.class.getRecordComponents())
          .flatMap(
              provider ->
                  Arrays.stream(ProviderSettings.Entry.class.getRecordComponents())
                      .map(
                          setting ->
                              (PREFIX + provider.getName() + "_" + setting.getName())
                                  .toUpperCase(Locale.ROOT)))
          .collect(Collectors.toUnmodifiableSet());

  /** Checks the variables as the configuration is created, before anything is served. */
  SettingsConfig(ConfigurableEnvironment environment) {
    // The machine's variables, under the name Spring gives their source; it is always a map.
    var variables =
        (EnumerablePropertySource<?>)
            environment
                .getPropertySources()
                .get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    var unknown =
        Arrays.stream(variables.getPropertyNames())
            .filter(name -> name.startsWith(PREFIX) && !KNOWN.contains(name))
            .sorted()
            .toList();
    if (!unknown.isEmpty()) {
      throw new IllegalStateException(
          "Environment variables that name no provider setting: " + unknown);
    }
  }
}
