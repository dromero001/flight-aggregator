package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.application.port.in.RepriceItinerary;
import com.flightaggregator.application.port.out.Clock;
import com.flightaggregator.application.port.out.FlightProvider;
import com.flightaggregator.application.port.out.ProviderDownload;
import com.flightaggregator.application.service.RepriceItineraryService;
import com.flightaggregator.domain.Airport;
import com.flightaggregator.domain.Leg;
import com.flightaggregator.domain.LegKey;
import com.flightaggregator.domain.Provider;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/** The re-price use case RepriceConfig declares, over stores that are never connected to. */
class RepriceConfigTest {

  private static final DataSource NEVER_CONNECTED = new DriverManagerDataSource();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(PersistenceConfig.class, RepriceConfig.class)
          .withBean(
              DataSourceTransactionManager.class,
              () -> new DataSourceTransactionManager(NEVER_CONNECTED))
          .withBean(JdbcClient.class, () -> JdbcClient.create(NEVER_CONNECTED))
          .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
          .withBean(Clock.class, () -> Instant::now)
          .withBean(FlightProvider.class, NeverCalled::new);

  @Test
  void declaresTheRepriceUseCase() {
    runner.run(
        context ->
            assertThat(context)
                .getBean(RepriceItinerary.class)
                .isInstanceOf(RepriceItineraryService.class));
  }

  /** A provider the wiring names, and nothing calls. */
  private static final class NeverCalled implements FlightProvider {

    @Override
    public Provider provider() {
      return Provider.ALPHA;
    }

    @Override
    public ProviderDownload download() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProviderDownload downloadFrom(Airport origin) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Leg> quote(LegKey flight) {
      throw new UnsupportedOperationException();
    }
  }
}
