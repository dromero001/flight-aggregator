plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "flight-aggregator"

// Modules from the design (§6). Each one is added when its first task starts:
// domain, application, adapters:providers, adapters:persistence, adapters:web, bootstrap, frontend.
include("mock-gds")
