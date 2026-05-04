# ---- build stage: compiles the Spring Boot jar inside Docker ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src

# Cache Gradle wrapper + dependencies in their own layer
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Build the boot jar (skip tests for image build speed)
COPY src src
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- runtime stage ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /src/build/libs/openmrs-cce-receiver-adaptor-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
