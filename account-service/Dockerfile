FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml pom.xml
COPY account-service/pom.xml account-service/pom.xml
COPY event-gateway/pom.xml event-gateway/pom.xml
COPY integration-tests/pom.xml integration-tests/pom.xml
COPY account-service/src account-service/src
RUN mvn -pl account-service -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 appuser && mkdir -p /data && chown appuser:appuser /data
COPY --from=build /workspace/account-service/target/account-service-*.jar app.jar
USER appuser
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
