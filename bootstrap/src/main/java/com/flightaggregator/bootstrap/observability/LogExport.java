package com.flightaggregator.bootstrap.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;

/**
 * Hands Boot's OpenTelemetry SDK to the Logback appender that {@code logback-spring.xml} declares,
 * so each log event becomes a log record, with the trace context it was written in, that Boot
 * exports over OTLP (§6.10; plan 7, Q2-A). Boot builds the SDK and its log exporter, but leaves the
 * appender to the application.
 */
public final class LogExport implements InitializingBean {

  private final OpenTelemetry openTelemetry;

  public LogExport(OpenTelemetry openTelemetry) {
    this.openTelemetry = openTelemetry;
  }

  @Override
  public void afterPropertiesSet() {
    OpenTelemetryAppender.install(openTelemetry);
  }
}
