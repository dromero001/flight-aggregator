plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "flight-aggregator"

// The only place that declares repositories: a module that declares its own fails the build.
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// The mock providers, the hexagonal modules from the design (§6), and the architecture and coverage
// gates. The frontend is added when its first task starts.
include("mock-gds")
include("domain")
include("application")
include("adapters:providers")
include("adapters:persistence")
include("adapters:web")
include("bootstrap")
include("architecture-tests")
include("coverage")
