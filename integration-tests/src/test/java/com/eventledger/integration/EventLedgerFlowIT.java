package com.eventledger.integration;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.time.Duration;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@Testcontainers(disabledWithoutDocker = true)
class EventLedgerFlowIT {
    @Container
    private static final ComposeContainer ENVIRONMENT =
            new ComposeContainer(new File("../docker-compose.yml"))
                    .withExposedService("event-gateway-1", 8080,
                            Wait.forHttp("/health").forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(3)));

    @BeforeAll
    static void configureClient() {
        RestAssured.baseURI = "http://" + ENVIRONMENT.getServiceHost("event-gateway-1", 8080);
        RestAssured.port = ENVIRONMENT.getServicePort("event-gateway-1", 8080);
    }

    @Test
    void fullGatewayToAccountFlowHandlesOrderingAndDuplicates() {
        submit("evt-e2e-later", "DEBIT", 40, "2026-05-15T14:05:00Z", 201);
        submit("evt-e2e-earlier", "CREDIT", 100, "2026-05-15T14:00:00Z", 201);
        submit("evt-e2e-later", "DEBIT", 40, "2026-05-15T14:05:00Z", 200);

        RestAssured.given()
                .queryParam("account", "acct-e2e")
                .when().get("/events")
                .then().statusCode(200)
                .body("", hasSize(2))
                .body("[0].eventId", equalTo("evt-e2e-earlier"))
                .body("[1].eventId", equalTo("evt-e2e-later"));

        RestAssured.when().get("/accounts/acct-e2e/balance")
                .then().statusCode(200)
                .body("balance", equalTo(60.0f))
                .body("currency", equalTo("USD"));
    }

    private void submit(String eventId, String type, int amount, String timestamp, int expectedStatus) {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "eventId": "%s",
                          "accountId": "acct-e2e",
                          "type": "%s",
                          "amount": %d,
                          "currency": "USD",
                          "eventTimestamp": "%s",
                          "metadata": {"source": "integration-test"}
                        }
                        """.formatted(eventId, type, amount, timestamp))
                .when().post("/events")
                .then().statusCode(expectedStatus);
    }
}
