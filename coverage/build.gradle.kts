// Aggregated coverage gate (spec 6.11) over the `test` suite of every backend module together. A
// separate integration-test suite would need a report and executionData of its own.
// architecture-tests is deliberately not aggregated: its fixtures are test code, not production code.
plugins {
    base
    `jacoco-report-aggregation`
}

// The report and the gate run the same JaCoCo as the modules' agents.
jacoco {
    toolVersion = libs.jacoco.agent.get().versionConstraint.requiredVersion
}

dependencies {
    // bootstrap depends on every hexagonal module, so its graph is the single module list.
    jacocoAggregation(project(":mock-gds"))
    jacocoAggregation(project(":bootstrap"))
}

reporting {
    reports {
        register<JacocoCoverageReport>("testCodeCoverageReport") {
            // Not redundant: Gradle 9.9.1 fails on an explicitly registered report without it.
            testSuiteName = "test"
        }
    }
}

// Only the Spring Boot entry point, configuration classes and generated code are excluded.
val coverageExclusions = listOf("**/*Application.class", "**/config/**", "**/generated/**")

val minimumLines = providers.gradleProperty("coverage.minimum.lines").orElse("0.90").map(::BigDecimal)
val minimumBranches = providers.gradleProperty("coverage.minimum.branches").orElse("0.80").map(::BigDecimal)

val report = tasks.named<JacocoReport>("testCodeCoverageReport") {
    // Snapshot the directories first: filtering classDirectories in place refers to itself and
    // runs out of memory with the configuration cache.
    val allClasses = files(classDirectories.from.toList())
    classDirectories.setFrom(allClasses.asFileTree.matching { exclude(coverageExclusions) })
}

val coverageVerification = tasks.register<JacocoCoverageVerification>("coverageVerification") {
    executionData.from(report.map { it.executionData })
    classDirectories.from(report.map { it.classDirectories })
    sourceDirectories.from(report.map { it.sourceDirectories })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = minimumLines.get()
            }
            limit {
                counter = "BRANCH"
                minimum = minimumBranches.get()
            }
        }
    }
}

tasks.check {
    dependsOn(coverageVerification)
}
