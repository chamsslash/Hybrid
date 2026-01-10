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

# Copy the built JAR file
COPY --from=builder /workspace/MessegerParody/target/*.jar app.jar

# Create non-root user
RUN groupadd -r appuser && useradd -r -g appuser appuser
USER appuser

# Expose port
EXPOSE 8082

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
