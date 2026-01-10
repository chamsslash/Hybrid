# Multi-stage build for MessegerParody
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /workspace
COPY pom.xml .
COPY Common/pom.xml Common/pom.xml
COPY MessegerParody/pom.xml MessegerParody/pom.xml

# Download dependencies
RUN mvn -pl MessegerParody -am dependency:go-offline -B

COPY Common/src Common/src
COPY MessegerParody/src MessegerParody/src

# Build the application
RUN mvn -pl MessegerParody -am clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Install necessary packages
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy the built JAR file
COPY --from=builder /workspace/MessegerParody/target/*.jar app.jar

# Create non-root user
RUN groupadd -r appuser && useradd -r -g appuser appuser
USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8082/actuator/health || exit 1

# Expose port
EXPOSE 8082

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
