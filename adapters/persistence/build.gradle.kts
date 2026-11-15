plugins {
    id("flightaggregator.java-conventions")
    // The pinned PostgreSQL image, shared with the bootstrap tests.
    `java-test-fixtures`
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":application"))
    implementation(libs.spring.jdbc)
    implementation(libs.postgresql)
    implementation(libs.jackson.core)

    testImplementation(libs.flyway.core)
    testImplementation(libs.flyway.database.postgresql)
    testImplementation(libs.testcontainers.postgresql)
}
