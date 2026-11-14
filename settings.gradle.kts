plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "flight-aggregator"

// Hexagonal modules from the design (§6). The frontend is added when its first task starts.
include("mock-gds")
include("domain")
include("application")
include("adapters:providers")
include("adapters:persistence")
include("adapters:web")
include("bootstrap")
include("architecture-tests")
