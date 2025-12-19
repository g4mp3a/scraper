# --- STAGE 1: Build the JAR ---
# Official Eclipse Temurin image with JDK 21 and Gradle
FROM gradle:8.5.0-jdk21-alpine AS builder

WORKDIR /app

# Copy the Gradle wrapper and build configuration
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Copy source code
COPY src src

# Compiling Kotlin and package the fat JAR
# The '--no-daemon' flag for CI/Docker builds
RUN ./gradlew bootJar --no-daemon

# --- STAGE 2: Create the Final Production Image ---
# Alpine Linux for minimal footprint
FROM eclipse-temurin:21-jre-alpine

# Set the environment variable for the Spring JAR file location
ENV JAR_FILE=app/build/libs/api-service-0.0.1-SNAPSHOT.jar

# Create a non-root user for security best practices
RUN adduser -D springuser
USER springuser

# Copy the fat JAR from the builder stage
COPY --from=builder /app/build/libs/*.jar $JAR_FILE

# Cloud Run/Spring Boot listens on port 8080 by default
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "$JAR_FILE"]
