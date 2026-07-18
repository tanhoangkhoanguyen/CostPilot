# build stage: gradle wrapper + JDK 21. Dependency resolution is its own layer so
# code-only changes don't re-download the world.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# runtime stage: JRE only, non-root
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S costpilot && adduser -S costpilot -G costpilot
USER costpilot
COPY --from=build /workspace/build/libs/*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
