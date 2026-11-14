plugins {
    id("flightaggregator.java-conventions")
}

dependencies {
    testImplementation(project(":domain"))
    testImplementation(project(":application"))
    testImplementation(project(":adapters:providers"))
    testImplementation(project(":adapters:persistence"))
    testImplementation(project(":adapters:web"))
    testImplementation(project(":bootstrap"))
    testImplementation(libs.archunit.junit5)
    // Only so the fixtures can use a Spring annotation; the version comes from the Spring Boot BOM.
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.context)
}
