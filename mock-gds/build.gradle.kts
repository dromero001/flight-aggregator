plugins {
    id("flightaggregator.java-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.resttestclient)
    testImplementation(libs.spring.boot.restclient)
}

// The Dockerfile copies this exact name, whatever the project version.
tasks.bootJar {
    archiveFileName = "mock-gds.jar"
}
