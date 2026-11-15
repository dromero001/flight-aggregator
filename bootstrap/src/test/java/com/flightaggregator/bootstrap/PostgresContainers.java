package com.flightaggregator.bootstrap;

import com.flightaggregator.adapter.persistence.support.PostgresImage;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The PostgreSQL of the application's tests, imported with {@code @ImportTestcontainers}, which
 * starts it (no {@code @Container} is needed: that is for the {@code @Testcontainers} extension):
 * one container per JVM, so the test classes that import it share its database, and each of their
 * tests starts by emptying it ({@link #emptyTheInventory}) rather than seeing another class's runs.
 */
public interface PostgresContainers {

  /**
   * Named, because Boot otherwise deduces the name from the image, and Testcontainers' check of an
   * image name refuses one pinned by both tag and digest.
   */
  @ServiceConnection(name = "postgres")
  PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
          DockerImageName.parse(PostgresImage.NAME).asCompatibleSubstituteFor("postgres"));

  /** No stored offer, both slots empty, and generation 0 active, as on a fresh database. */
  static void emptyTheInventory(JdbcClient jdbc) {
    jdbc.sql("TRUNCATE provider_offer, itinerary_a, itinerary_b").update();
    jdbc.sql(
            "UPDATE inventory_publication"
                + " SET active_slot = 'a', generation = 0, published_at = NULL")
        .update();
  }
}
