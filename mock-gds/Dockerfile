# Build context: the repository root (docker build -f mock-gds/Dockerfile .).
# Both base images are pinned by tag and digest (the tags are 14+ days old, spec 6.1).
FROM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:a9c5ddd386f629518bd500ecfabf7d1e5d32d7ffed735d5c3b1604ae92c830f4 AS build
WORKDIR /workspace
# settings.gradle.kts includes every module, and Gradle refuses an included project whose directory
# is missing. The other modules stay out of the image: empty directories are enough.
COPY gradlew settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY mock-gds mock-gds
RUN mkdir -p domain application adapters/providers adapters/persistence adapters/web bootstrap architecture-tests coverage
# Tests run in CI, not here.
RUN ./gradlew :mock-gds:bootJar --no-daemon

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:ce8543ddd3070ccbeaac2106a74c9387ddeb8d47a1c43348345715c1ef245c7f
RUN useradd --system --uid 10001 --no-create-home mockgds
WORKDIR /app
COPY --from=build /workspace/mock-gds/build/libs/mock-gds.jar app.jar
USER mockgds
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
