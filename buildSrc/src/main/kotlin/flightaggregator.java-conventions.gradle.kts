// Shared setup for every Java module: Java 25 toolchain, strict compilation, JUnit Platform with
// JUnit and AssertJ, google-java-format through Spotless, and the JaCoCo agent. Repositories are
// declared once, in settings.gradle.kts.
plugins {
    java
    jacoco
    id("com.diffplug.spotless")
}

val libs = versionCatalogs.named("libs")
fun library(alias: String) = libs.findLibrary(alias).get()
fun versionOf(alias: String) = library(alias).get().versionConstraint.requiredVersion

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat(versionOf("google-java-format"))
    }
}

// Workaround for https://github.com/diffplug/spotless/issues/3067: Spotless adds a `doLast` to every
// project's `clean` that closes the formatter class loaders shared by all modules, so a `clean` in
// one module breaks a Spotless task still running in another (`NoClassDefFoundError` in
// `./gradlew clean build`). Every Spotless task therefore runs after every `clean`. The paths are
// strings, not `tasks.matching { ... }`, to stay compatible with the configuration cache. Only the
// projects with a build file have a `clean`. Remove this once a Spotless release that fixes the
// issue is at least 14 days old (spec 6.1).
val cleanTaskPaths =
    gradle.rootProject.allprojects.filter { it.buildFile.exists() }.map { "${it.path.trimEnd(':')}:clean" }
tasks.withType<com.diffplug.gradle.spotless.SpotlessTask>().configureEach {
    mustRunAfter(cleanTaskPaths)
}

dependencies {
    testImplementation(platform(library("junit-bom")))
    testImplementation(library("junit-jupiter"))
    testImplementation(library("assertj-core"))
    testRuntimeOnly(library("junit-platform-launcher"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

jacoco {
    toolVersion = versionOf("jacoco-agent")
}
