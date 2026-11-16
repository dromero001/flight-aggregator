plugins {
    id("flightaggregator.java-conventions")
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":application"))
    implementation(libs.spring.web)
    // The rate limit is a Spring MVC interceptor (§6.6).
    implementation(libs.spring.webmvc)
    // A re-price leaves out the fields its outcome does not have.
    implementation(libs.jackson.annotations)
    // The servlet container provides the servlet API at run time, Tomcat's own copy of it.
    compileOnly(libs.jakarta.servlet.api)
    // The OpenAPI contract, generated from the controllers (§6.8, §6.11). Outside Boot's BOM, so
    // pinned by the 14-day rule (§6.1). Bootstrap, which serves the contract, declares it too.
    implementation(libs.springdoc.openapi.starter.webmvc.api)

    testImplementation(libs.spring.boot.starter.webmvc.test)
}
