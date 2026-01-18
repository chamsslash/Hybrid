# Multi-stage build for MessegerParody
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app
COPY pom.xml .
COPY src ./src

# Use build cache for Maven repo to avoid refetching dependencies
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B -Dmaven.repo.local=/root/.m2
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests -Dmaven.repo.local=/root/.m2

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the built JAR file
COPY --from=builder /app/target/*.jar app.jar

# Create non-root user
RUN groupadd -r appuser && useradd -r -g appuser appuser
USER appuser

# Expose port
EXPOSE 8082

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
