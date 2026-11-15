plugins {
    id("flightaggregator.java-conventions")
    // The pinned PostgreSQL and Valkey images, shared with the bootstrap tests.
    `java-test-fixtures`
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":application"))
    implementation(libs.spring.jdbc)
    implementation(libs.postgresql)
    implementation(libs.jackson.core)
    // The Valkey stores (§6.2) and the search cache's counter (§6.10).
    implementation(libs.spring.data.redis)
    implementation(libs.micrometer.core)

    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.database.postgresql)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.redis)
    // Spring Data Redis leaves the client to the application; the tests connect as it does.
    testImplementation(libs.lettuce.core)
    // The Valkey failures' log lines, read as Logback events.
    testImplementation(libs.logback.classic)
}
