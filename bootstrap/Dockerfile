# Build context: the repository root (docker build -f bootstrap/Dockerfile .).
# Both base images are pinned by tag and digest (the tags are 14+ days old, spec 6.1), the same ones
# as mock-gds/Dockerfile.
FROM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:a9c5ddd386f629518bd500ecfabf7d1e5d32d7ffed735d5c3b1604ae92c830f4 AS build
WORKDIR /workspace
# The application needs every module but mock-gds and the test-only ones. settings.gradle.kts
# includes all of them, and Gradle refuses an included project whose directory is missing, so every
# module directory, derived from the settings, exists; the sources of the modules the jar needs are
# copied over them below.
COPY gradlew settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY buildSrc buildSrc
RUN sed -n 's/^include("\(.*\)")/\1/p' settings.gradle.kts | tr ':' '/' | xargs mkdir -p
COPY domain domain
COPY application application
COPY adapters adapters
COPY bootstrap bootstrap
# Tests run in CI, not here. No JDK download: the base image must hold the toolchain's JDK, so a
# mismatch fails the build instead of fetching an unpinned JDK. The caches hold only Gradle's
# dependencies and its wrapper, never /root/.gradle/jdks, so no JDK from an earlier build is reused.
RUN --mount=type=cache,target=/root/.gradle/caches,sharing=locked --mount=type=cache,target=/root/.gradle/wrapper,sharing=locked ./gradlew :bootstrap:bootJar --no-daemon -Porg.gradle.java.installations.auto-download=false

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:ce8543ddd3070ccbeaac2106a74c9387ddeb8d47a1c43348345715c1ef245c7f
RUN useradd --system --uid 10001 --no-create-home aggregator
WORKDIR /app
COPY --from=build /workspace/bootstrap/build/libs/bootstrap.jar app.jar
USER aggregator
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
