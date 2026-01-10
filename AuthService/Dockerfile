# Multi-stage build for AuthService
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /workspace
COPY pom.xml .
COPY Common/pom.xml Common/pom.xml
COPY AuthService/pom.xml AuthService/pom.xml

# Download dependencies
RUN mvn -pl AuthService -am dependency:go-offline -B

COPY Common/src Common/src
COPY AuthService/src AuthService/src

# Build the application
RUN mvn -pl AuthService -am clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Install necessary packages
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy the built JAR file
COPY --from=builder /workspace/AuthService/target/*.jar app.jar

# Create non-root user
RUN groupadd -r appuser && useradd -r -g appuser appuser
USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8081/actuator/health || exit 1

# Expose port
EXPOSE 8081

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
