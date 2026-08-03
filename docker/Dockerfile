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
# APP_VERSION is the git-tag-derived version (3.1); unset falls back to the
# 0.0.1-SNAPSHOT default in build.gradle so plain `docker build` still works.
ARG APP_VERSION=""
RUN ./gradlew bootJar --no-daemon -x test \
      ${APP_VERSION:+-Pversion="$APP_VERSION"}

# runtime stage: JRE only, non-root
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S costpilot && adduser -S costpilot -G costpilot
USER costpilot
COPY --from=build /workspace/build/libs/*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
