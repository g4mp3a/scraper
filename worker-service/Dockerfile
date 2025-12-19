# --- STAGE 1: Build the JAR ---
FROM gradle:8.5.0-jdk21-alpine AS builder

WORKDIR /app

# Copy build configuration
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

# Copy source code
COPY src src

# Build the fat JAR
RUN ./gradlew bootJar --no-daemon

# --- STAGE 2: Create the Final Production Image ---
FROM eclipse-temurin:21-jre-alpine

ENV JAR_FILE=app/build/libs/worker-service-0.0.1-SNAPSHOT.jar

# Security setup
RUN adduser -D springuser
USER springuser

# Copy the fat JAR from the builder stage
COPY --from=builder /app/build/libs/*.jar $JAR_FILE

# Worker listens on port 8080 for the Pub/Sub push controller
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "$JAR_FILE"]
