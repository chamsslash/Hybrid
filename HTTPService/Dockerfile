FROM maven:3.9.6-eclipse-temurin-21 as builder
WORKDIR /workspace
COPY pom.xml .
COPY Common/pom.xml Common/pom.xml
COPY HTTPService/pom.xml HTTPService/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -pl HTTPService -am dependency:go-offline
COPY Common/src Common/src
COPY HTTPService/src HTTPService/src
RUN --mount=type=cache,target=/root/.m2 mvn -pl HTTPService -am package -DskipTests

FROM openjdk:21-jre-slim
WORKDIR /app
COPY --from=builder /workspace/HTTPService/target/*.jar ./app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
