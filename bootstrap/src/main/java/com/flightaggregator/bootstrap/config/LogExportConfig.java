package com.flightaggregator.bootstrap.config;

import com.flightaggregator.bootstrap.observability.LogExport;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Each log line is exported with the trace context it was written in (§6.10; plan 7, Q2-A). */
@Configuration(proxyBeanMethods = false)
class LogExportConfig {

  @Bean
  LogExport logExport(OpenTelemetry openTelemetry) {
    return new LogExport(openTelemetry);
  }
}
