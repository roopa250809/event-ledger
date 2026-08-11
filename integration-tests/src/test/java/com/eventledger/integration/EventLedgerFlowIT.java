package com.eventledger.integration;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
/** Exercises full Gateway, Account Service, and Kafka container flows. */
class EventLedgerFlowIT {
    private static final String JWT_SECRET =
            "ZXZlbnQtbGVkZ2VyLWRldmVsb3BtZW50LXNlY3JldC1jaGFuZ2UtbWU=";
    private static final String ACCESS_TOKEN = createAccessToken();
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
        submit("evt-e2e-later", "acct-e2e", "DEBIT", 40, "2026-05-15T14:05:00Z", 201);
        submit("evt-e2e-earlier", "acct-e2e", "CREDIT", 100, "2026-05-15T14:00:00Z", 201);
        submit("evt-e2e-later", "acct-e2e", "DEBIT", 40, "2026-05-15T14:05:00Z", 200);

        RestAssured.given()
                .auth().oauth2(ACCESS_TOKEN)
                .queryParam("account", "acct-e2e")
                .when().get("/events")
                .then().statusCode(200)
                .body("", hasSize(2))
                .body("[0].eventId", equalTo("evt-e2e-earlier"))
                .body("[1].eventId", equalTo("evt-e2e-later"));

        RestAssured.given().auth().oauth2(ACCESS_TOKEN)
                .when().get("/accounts/acct-e2e/balance")
                .then().statusCode(200)
                .body("balance", equalTo(60.0f))
                .body("currency", equalTo("USD"));
    }

    @Test
    void kafkaFallbackAppliesQueuedEventAfterAccountServiceRecovers() throws InterruptedException {
        String accountContainerId = ENVIRONMENT.getContainerByServiceName("account-service-1")
                .orElseThrow().getContainerId();
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(accountContainerId).exec();
        try {
            submit("evt-e2e-kafka", "acct-e2e-kafka", "CREDIT", 75,
                    "2026-05-15T14:10:00Z", 202);
        } finally {
            docker.unpauseContainerCmd(accountContainerId).exec();
        }

        String status = null;
        for (int attempt = 0; attempt < 45; attempt++) {
            status = RestAssured.given().auth().oauth2(ACCESS_TOKEN)
                    .when().get("/events/evt-e2e-kafka")
                    .then().statusCode(200).extract().path("status");
            if ("APPLIED".equals(status)) {
                break;
            }
            Thread.sleep(1_000);
        }
        assertEquals("APPLIED", status, "Kafka consumer did not apply the queued event");

        RestAssured.given().auth().oauth2(ACCESS_TOKEN)
                .when().get("/accounts/acct-e2e-kafka/balance")
                .then().statusCode(200)
                .body("balance", equalTo(75.0f));
    }

    private void submit(String eventId, String accountId, String type, int amount,
                        String timestamp, int expectedStatus) {
        RestAssured.given()
                .auth().oauth2(ACCESS_TOKEN)
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "eventId": "%s",
                          "accountId": "%s",
                          "type": "%s",
                          "amount": %d,
                          "currency": "USD",
                          "eventTimestamp": "%s",
                          "metadata": {"source": "integration-test"}
                        }
                        """.formatted(eventId, accountId, type, amount, timestamp))
                .when().post("/events")
                .then().statusCode(expectedStatus);
    }

    private static String createAccessToken() {
        try {
            long now = Instant.now().getEpochSecond();
            String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            String payload = base64Url("""
                    {"iss":"event-ledger-local","aud":["event-ledger-api"],
                    "sub":"integration-test","scope":"events.read events.write accounts.read",
                    "iat":%d,"exp":%d}
                    """.formatted(now, now + 600).replace("\n", ""));
            String unsigned = header + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(JWT_SECRET), "HmacSHA256"));
            return unsigned + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create integration-test JWT", exception);
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
