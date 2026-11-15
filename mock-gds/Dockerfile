# Build context: the repository root (docker build -f mock-gds/Dockerfile .).
# Both base images are pinned by tag and digest (the tags are 14+ days old, spec 6.1).
FROM eclipse-temurin:26.0.2.1_1-jdk-noble@sha256:9c0e8eddecccc0d5679b317b0c90fa7103ad25cae33df1c00280675eb681c7f8 AS build
WORKDIR /workspace
# settings.gradle.kts includes every module, and Gradle refuses an included project whose directory
# is missing. The other modules stay out of the image: empty directories, derived from the settings,
# are enough (the mock-gds copy below overwrites its own).
COPY gradlew settings.gradle.kts gradle.properties ./
COPY gradle gradle
COPY buildSrc buildSrc
RUN sed -n 's/^include("\(.*\)")/\1/p' settings.gradle.kts | tr ':' '/' | xargs mkdir -p
COPY mock-gds mock-gds
# Tests run in CI, not here.
RUN --mount=type=cache,target=/root/.gradle ./gradlew :mock-gds:bootJar --no-daemon

FROM eclipse-temurin:26.0.2.1_1-jre-noble@sha256:adfd44c114916dc79fa9a24087fbac34252096edb9892d1c2d9d82db3f8947b3
RUN useradd --system --uid 10001 --no-create-home mockgds
WORKDIR /app
COPY --from=build /workspace/mock-gds/build/libs/mock-gds.jar app.jar
USER mockgds
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
