plugins {
    id("flightaggregator.java-conventions")
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":application"))
    implementation(libs.spring.web)
    implementation(libs.jackson.databind)
    implementation(libs.resilience4j.circuitbreaker)

    testImplementation(libs.wiremock.standalone)
}
