package com.flightaggregator.adapter.persistence.support;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container per JVM, migrated once with the production migrations. Each test class
 * truncates the tables it uses.
 */
public final class PostgresTestDatabase {

  @SuppressWarnings("resource") // Stopped by Testcontainers' Ryuk when the JVM ends.
  private static final PostgreSQLContainer CONTAINER =
      new PostgreSQLContainer(
          DockerImageName.parse(PostgresImage.NAME).asCompatibleSubstituteFor("postgres"));

  private static final DataSource DATA_SOURCE = start();

  private PostgresTestDatabase() {}

  public static DataSource dataSource() {
    return DATA_SOURCE;
  }

  private static DataSource start() {
    CONTAINER.start();
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(CONTAINER.getJdbcUrl());
    dataSource.setUser(CONTAINER.getUsername());
    dataSource.setPassword(CONTAINER.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    return dataSource;
  }
}
