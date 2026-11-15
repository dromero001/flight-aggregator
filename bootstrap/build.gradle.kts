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
    // A web server keeps the process alive between runs and serves /actuator/health: without one the
    // JVM exits after the first run, its remaining threads being daemons (found in Task 11).
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jackson)
    // The admin side is an OAuth2 resource server (§6.6).
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    // Valkey on Lettuce: the search cache, refresh claims and request counters (§6.2).
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.resilience4j.circuitbreaker)
    // Binds the breakers' state and calls to Micrometer (§6.1, §6.10).
    implementation(libs.resilience4j.micrometer)
    implementation(libs.shedlock.core)
    implementation(libs.shedlock.provider.jdbc.template)
    // Flyway 10+ reads PostgreSQL through this module; no code uses it.
    runtimeOnly(libs.flyway.database.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.redis)
    testImplementation(testFixtures(project(":adapters:persistence")))
    testImplementation(libs.wiremock.standalone)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.resttestclient)
    testImplementation(libs.spring.boot.restclient)
}

// The Dockerfile copies this exact name, whatever the project version.
tasks.bootJar {
    archiveFileName = "bootstrap.jar"
}

// ComposeFileTest reads compose.yaml and RealmClientCredentialsTest the Keycloak realm file: a
// change to either alone must run the tests again.
tasks.test {
    inputs.file(rootProject.layout.projectDirectory.file("compose.yaml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("deploy/keycloak/flight-aggregator-realm.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
