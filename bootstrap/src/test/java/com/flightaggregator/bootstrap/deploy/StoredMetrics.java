package com.flightaggregator.bootstrap.deploy;

import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The metric names Prometheus stores for the application, and the names a PromQL expression uses,
 * so a dashboard's queries can be checked against what is really scraped (plan 7 Task 6).
 */
final class StoredMetrics {

  /**
   * Every metric name Prometheus stored for the job {@code app} in plan 7 Task 2's run, as its
   * progress report lists them (2026-11-15, "Task 2 — evidence"), Prometheus's own included.
   */
  static final Set<String> NAMES =
      Set.of(
          """
          up scrape_duration_seconds scrape_samples_post_metric_relabeling scrape_samples_scraped
          scrape_series_added

          ingestion_consecutive_failures ingestion_partitions_days_ahead
          ingestion_partitions_default_rows ingestion_itineraries
          ingestion_provider_last_fresh_seconds ingestion_runs_total
          ingestion_duration_seconds_count ingestion_duration_seconds_sum
          ingestion_duration_seconds_max ingestion_provider_downloads_total
          ingestion_provider_retries_total ingestion_records_discarded_total provider_calls_total

          search_cache_total

          resilience4j_circuitbreaker_state resilience4j_circuitbreaker_buffered_calls
          resilience4j_circuitbreaker_calls_seconds_count resilience4j_circuitbreaker_calls_seconds_sum
          resilience4j_circuitbreaker_calls_seconds_max resilience4j_circuitbreaker_failure_rate
          resilience4j_circuitbreaker_not_permitted_calls_total
          resilience4j_circuitbreaker_slow_call_rate resilience4j_circuitbreaker_slow_calls

          valkey_circuitbreaker_state valkey_circuitbreaker_buffered_calls
          valkey_circuitbreaker_calls_seconds_count valkey_circuitbreaker_calls_seconds_sum
          valkey_circuitbreaker_calls_seconds_max valkey_circuitbreaker_failure_rate
          valkey_circuitbreaker_not_permitted_calls_total valkey_circuitbreaker_slow_call_rate
          valkey_circuitbreaker_slow_calls

          http_server_requests_seconds_count http_server_requests_seconds_sum
          http_server_requests_seconds_max http_server_requests_active_seconds_count
          http_server_requests_active_seconds_sum http_server_requests_active_seconds_max

          application_ready_time_seconds application_started_time_seconds disk_free_bytes
          disk_total_bytes
          hikaricp_connections hikaricp_connections_acquire_seconds_count
          hikaricp_connections_acquire_seconds_max hikaricp_connections_acquire_seconds_sum
          hikaricp_connections_active hikaricp_connections_creation_seconds_count
          hikaricp_connections_creation_seconds_max hikaricp_connections_creation_seconds_sum
          hikaricp_connections_idle hikaricp_connections_max hikaricp_connections_min
          hikaricp_connections_pending hikaricp_connections_timeout_total
          hikaricp_connections_usage_seconds_count hikaricp_connections_usage_seconds_max
          hikaricp_connections_usage_seconds_sum
          jdbc_connections_active jdbc_connections_idle jdbc_connections_max jdbc_connections_min
          jvm_buffer_count_buffers jvm_buffer_memory_used_bytes jvm_buffer_total_capacity_bytes
          jvm_classes_loaded_classes jvm_classes_loaded_count_classes_total
          jvm_classes_unloaded_classes_total jvm_compilation_time_ms_total
          jvm_gc_concurrent_phase_time_seconds_count jvm_gc_concurrent_phase_time_seconds_max
          jvm_gc_concurrent_phase_time_seconds_sum jvm_gc_live_data_size_bytes
          jvm_gc_max_data_size_bytes jvm_gc_memory_allocated_bytes_total
          jvm_gc_memory_promoted_bytes_total jvm_gc_overhead jvm_gc_pause_seconds_count
          jvm_gc_pause_seconds_max jvm_gc_pause_seconds_sum jvm_info jvm_memory_committed_bytes
          jvm_memory_max_bytes jvm_memory_usage_after_gc jvm_memory_used_bytes
          jvm_threads_daemon_threads jvm_threads_live_threads jvm_threads_peak_threads
          jvm_threads_started_threads_total jvm_threads_states_threads
          lettuce_active_seconds_count lettuce_active_seconds_max lettuce_active_seconds_sum
          lettuce_seconds_count lettuce_seconds_max lettuce_seconds_sum
          logback_events_total
          process_cpu_time_ns_total process_cpu_usage process_files_max_files
          process_files_open_files process_start_time_seconds process_uptime_seconds
          system_cpu_count system_cpu_usage system_load_average_1m
          tasks_scheduled_execution_seconds_count tasks_scheduled_execution_seconds_max
          tasks_scheduled_execution_seconds_sum tasks_scheduled_execution_active_seconds_count
          tasks_scheduled_execution_active_seconds_max tasks_scheduled_execution_active_seconds_sum
          tomcat_sessions_active_current_sessions tomcat_sessions_active_max_sessions
          tomcat_sessions_alive_max_seconds tomcat_sessions_created_sessions_total
          tomcat_sessions_expired_sessions_total tomcat_sessions_rejected_sessions_total

          spring_security_authorizations_seconds_count spring_security_authorizations_seconds_max
          spring_security_authorizations_seconds_sum
          spring_security_authorizations_active_seconds_count
          spring_security_authorizations_active_seconds_max
          spring_security_authorizations_active_seconds_sum
          spring_security_filterchains_seconds_count spring_security_filterchains_seconds_max
          spring_security_filterchains_seconds_sum spring_security_filterchains_active_seconds_count
          spring_security_filterchains_active_seconds_max
          spring_security_filterchains_active_seconds_sum
          spring_security_http_secured_requests_seconds_count
          spring_security_http_secured_requests_seconds_max
          spring_security_http_secured_requests_seconds_sum
          spring_security_http_secured_requests_active_seconds_count
          spring_security_http_secured_requests_active_seconds_max
          spring_security_http_secured_requests_active_seconds_sum
          spring_security_filterchains_OAuth2ProtectedResourceMetadataFilter_before_total
          spring_security_filterchains_OAuth2ProtectedResourceMetadataFilter_after_total
          spring_security_filterchains_access_exceptions_before_total
          spring_security_filterchains_access_exceptions_after_total
          spring_security_filterchains_authentication_anonymous_before_total
          spring_security_filterchains_authentication_anonymous_after_total
          spring_security_filterchains_authentication_bearer_before_total
          spring_security_filterchains_authentication_bearer_after_total
          spring_security_filterchains_authorization_before_total
          spring_security_filterchains_authorization_after_total
          spring_security_filterchains_context_async_before_total
          spring_security_filterchains_context_async_after_total
          spring_security_filterchains_context_holder_before_total
          spring_security_filterchains_context_holder_after_total
          spring_security_filterchains_context_servlet_before_total
          spring_security_filterchains_context_servlet_after_total
          spring_security_filterchains_header_before_total
          spring_security_filterchains_header_after_total
          spring_security_filterchains_logout_before_total
          spring_security_filterchains_logout_after_total
          spring_security_filterchains_requestcache_before_total
          spring_security_filterchains_requestcache_after_total
          spring_security_filterchains_session_management_before_total
          spring_security_filterchains_session_management_after_total
          spring_security_filterchains_session_urlencoding_before_total
          spring_security_filterchains_session_urlencoding_after_total
          """
              .strip()
              .split("\\s+"));

  /**
   * A word of PromQL that is not followed by an opening parenthesis (a function or an aggregation)
   * nor preceded by a digit (part of a duration, such as {@code 1h}).
   */
  private static final Pattern PROMQL_WORD =
      Pattern.compile("(?<![\\w.:])[a-zA-Z_:][\\w:]*+(?!\\s*\\()");

  /** PromQL's keywords, which the word pattern also finds. */
  private static final Set<String> PROMQL_KEYWORDS =
      Set.of(
          "by",
          "without",
          "on",
          "ignoring",
          "group_left",
          "group_right",
          "bool",
          "and",
          "or",
          "unless",
          "offset");

  /**
   * The metric names in a PromQL expression: label matchers, ranges and grouping labels are taken
   * out, then every remaining word that is not a function, a keyword or part of a duration is one.
   * The reading errs towards more names, never fewer: a word it cannot place, such as one inside a
   * quoted argument of {@code label_replace}, counts as a name and fails the check.
   */
  static Set<String> namedIn(String expression) {
    String withoutLabels =
        expression
            .replaceAll("\\{[^}]*}", "")
            .replaceAll("\\[[^\\]]*]", "")
            .replaceAll("\\b(by|without|on|ignoring)\\s*\\([^)]*\\)", "");
    return PROMQL_WORD
        .matcher(withoutLabels)
        .results()
        .map(MatchResult::group)
        .filter(word -> !PROMQL_KEYWORDS.contains(word))
        .collect(Collectors.toSet());
  }

  private StoredMetrics() {}
}
