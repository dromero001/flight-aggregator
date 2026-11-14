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
