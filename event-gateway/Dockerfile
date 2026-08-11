FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml pom.xml
COPY account-service/pom.xml account-service/pom.xml
COPY event-gateway/pom.xml event-gateway/pom.xml
COPY integration-tests/pom.xml integration-tests/pom.xml
COPY event-gateway/src event-gateway/src
RUN mvn -pl event-gateway -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 appuser && mkdir -p /data && chown appuser:appuser /data
COPY --from=build /workspace/event-gateway/target/event-gateway-*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
