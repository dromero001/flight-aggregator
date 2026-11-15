package com.flightaggregator.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.flightaggregator.adapter.persistence.JdbcItineraryReader;
import com.flightaggregator.application.port.out.ItineraryReader;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/** The stores PersistenceConfig declares, over a data source that is never connected to. */
class PersistenceConfigTest {

  private static final DataSource NEVER_CONNECTED = new DriverManagerDataSource();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(PersistenceConfig.class)
          .withBean(
              DataSourceTransactionManager.class,
              () -> new DataSourceTransactionManager(NEVER_CONNECTED))
          .withBean(JdbcClient.class, () -> JdbcClient.create(NEVER_CONNECTED))
          .withBean(JsonMapper.class, () -> JsonMapper.builder().build());

  @Test
  void declaresTheItineraryReader() {
    runner.run(
        context ->
            assertThat(context)
                .getBean(ItineraryReader.class)
                .isInstanceOf(JdbcItineraryReader.class));
  }
}
