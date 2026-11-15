plugins {
    id("flightaggregator.java-conventions")
}

dependencies {
    // ArchUnit reads the production classes from the runtime classpath, and bootstrap depends on
    // every hexagonal module, so its graph is the single module list.
    testRuntimeOnly(project(":bootstrap"))
    testImplementation(libs.archunit)
    // Only so the fixtures can use a Spring annotation; the version comes from the Spring Boot BOM,
    // which the convention plugin imports for the tests.
    testImplementation(libs.spring.context)
}
