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

# Build the JAR
RUN ./gradlew bootJar --no-daemon

# --- STAGE 2: Create the Final Production Image ---
FROM eclipse-temurin:21-jre-alpine

ENV JAR_FILE=app/build/libs/publisher-service-0.0.1-SNAPSHOT.jar

# Switch away from root user
RUN adduser -D springuser
USER springuser

# Copy the fat JAR from the builder stage
COPY --from=builder /app/build/libs/*.jar $JAR_FILE

# Publisher listens on port 8080 (though its primary function is CDC streaming)
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "$JAR_FILE"]
