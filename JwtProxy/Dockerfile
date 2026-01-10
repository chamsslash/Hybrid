# Multi-stage build for JwtProxy
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /workspace
COPY pom.xml .
COPY Common/pom.xml Common/pom.xml
COPY JwtProxy/pom.xml JwtProxy/pom.xml

# Download dependencies
RUN mvn -pl JwtProxy -am dependency:go-offline -B

COPY Common/src Common/src
COPY JwtProxy/src JwtProxy/src

# Build the application
RUN mvn -pl JwtProxy -am clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the built JAR file
COPY --from=builder /workspace/JwtProxy/target/*.jar app.jar

# Create non-root user
RUN groupadd -r appuser && useradd -r -g appuser appuser
USER appuser

# Expose port
EXPOSE 8084

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
