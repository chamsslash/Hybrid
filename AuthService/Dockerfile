# Multi-stage build for AuthService
FROM maven:3.9.6-eclipse-temurin-21 AS builder

WORKDIR /app
COPY pom.xml .
COPY src ./src

# Кэш Maven переживает сборку. Без него каждая пересборка тянула весь Spring
# из central заново — 18 минут против секунд, притом что HTTPService и
# MessegerParody этот mount имеют с самого начала и собираются мгновенно.
# Кэш общий: id по умолчанию берётся из target, так что три сервиса делят
# один /root/.m2 и первый же собранный прогревает остальных.
RUN --mount=type=cache,target=/root/.m2 mvn dependency:go-offline -B
RUN --mount=type=cache,target=/root/.m2 mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the built JAR file
COPY --from=builder /app/target/*.jar app.jar

# Create non-root user
RUN groupadd -r appuser && useradd -r -g appuser appuser
USER appuser

# Expose port
EXPOSE 8081

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
