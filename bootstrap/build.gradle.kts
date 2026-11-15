plugins {
    id("flightaggregator.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":application"))
    implementation(project(":adapters:providers"))
    implementation(project(":adapters:persistence"))
    implementation(project(":adapters:web"))
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jackson)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.timelimiter)
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.provider.jdbc.template)
    // Flyway 10+ reads PostgreSQL through this module; no code uses it.
    runtimeOnly(libs.flyway.database.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(testFixtures(project(":adapters:persistence")))
    testImplementation(libs.wiremock.standalone)
}
