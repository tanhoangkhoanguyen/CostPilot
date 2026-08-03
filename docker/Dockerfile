# Build context is the repo root, not this directory - compose and CI pass
# `-f docker/Dockerfile` with `.` as the context so the Gradle build still sees
# settings.gradle / gradle/ / src at the paths it expects.
#
# build stage: gradle wrapper + JDK 21. Dependency resolution is its own layer so
# code-only changes don't re-download the world.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true

COPY src src

# There is no .git in this stage, so gradle/versioning.gradle cannot derive the version
# on its own - it falls back to 0.0.0-SNAPSHOT. The release workflow passes the real tag
# and commit in, which keeps `docker build` alone working and a release reproducible from
# the source tree without git history.
ARG APP_VERSION=""
ARG APP_COMMIT=""
RUN set -eu; \
    args=""; \
    if [ -n "$APP_VERSION" ]; then args="$args -PreleaseVersion=$APP_VERSION"; fi; \
    if [ -n "$APP_COMMIT" ]; then args="$args -PreleaseCommit=$APP_COMMIT"; fi; \
    ./gradlew bootJar --no-daemon -x test $args

# runtime stage: JRE only, non-root
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S costpilot && adduser -S costpilot -G costpilot
USER costpilot
COPY --from=build /workspace/build/libs/*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
