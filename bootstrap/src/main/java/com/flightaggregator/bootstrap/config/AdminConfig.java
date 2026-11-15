package com.flightaggregator.bootstrap.config;

import com.flightaggregator.application.port.in.InventoryStatus;
import com.flightaggregator.application.port.in.TriggerIngestion;
import com.flightaggregator.application.port.out.IngestionStatusStore;
import com.flightaggregator.application.port.out.ItineraryReader;
import com.flightaggregator.application.service.InventoryStatusService;
import com.flightaggregator.application.service.TriggerIngestionService;
import com.flightaggregator.bootstrap.ingestion.IngestionJob;
import com.flightaggregator.bootstrap.observability.RegistryProviderBreakers;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The admin side's use cases (§6.6, §6.8): each provider's status, from the runs' records (Q7-B)
 * and the providers' breakers (plan 4's registry), and the manual run, through the ingestion job,
 * so that it takes the scheduled runs' lock.
 */
@Configuration(proxyBeanMethods = false)
class AdminConfig {

  @Bean
  InventoryStatus inventoryStatus(
      IngestionStatusStore statuses,
      ItineraryReader inventory,
      CircuitBreakerRegistry circuitBreakers,
      @Value("${ingestion.interval}") Duration interval) {
    return new InventoryStatusService(
        statuses, inventory, new RegistryProviderBreakers(circuitBreakers), interval);
  }

  @Bean
  TriggerIngestion triggerIngestion(IngestionJob job) {
    return new TriggerIngestionService(job);
  }
}
