// Aggregated coverage gate (spec 6.11): unit and integration tests of every backend module together.
// architecture-tests is deliberately not aggregated: its fixtures are test code, not production code.
plugins {
    base
    `jacoco-report-aggregation`
}

repositories {
    mavenCentral()
}

dependencies {
    jacocoAggregation(project(":mock-gds"))
    jacocoAggregation(project(":domain"))
    jacocoAggregation(project(":application"))
    jacocoAggregation(project(":adapters:providers"))
    jacocoAggregation(project(":adapters:persistence"))
    jacocoAggregation(project(":adapters:web"))
    jacocoAggregation(project(":bootstrap"))
}

reporting {
    reports {
        register<JacocoCoverageReport>("testCodeCoverageReport") {
            testSuiteName = "test"
        }
    }
}

// Only the Spring Boot entry point, configuration classes and generated code are excluded.
val coverageExclusions = listOf("**/*Application.class", "**/config/**", "**/generated/**")

val minimumLines = providers.gradleProperty("coverage.minimum.lines").orElse("0.90").map(::BigDecimal)
val minimumBranches = providers.gradleProperty("coverage.minimum.branches").orElse("0.80").map(::BigDecimal)

val report = tasks.named<JacocoReport>("testCodeCoverageReport") {
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
