package com.eventledger.gateway.api;

import com.eventledger.gateway.client.AccountServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reports Gateway, database, and downstream dependency health. */
@RestController
public class HealthController {
    private final JdbcTemplate jdbcTemplate;
    private final AccountServiceClient accountServiceClient;
    private final String serviceName;

    public HealthController(JdbcTemplate jdbcTemplate,
                            AccountServiceClient accountServiceClient,
                            @Value("${spring.application.name}") String serviceName) {
        this.jdbcTemplate = jdbcTemplate;
        this.accountServiceClient = accountServiceClient;
        this.serviceName = serviceName;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean databaseUp = databaseUp();
        boolean accountServiceUp = accountServiceClient.isHealthy();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", databaseUp ? "UP" : "DOWN");
        body.put("service", serviceName);
        body.put("timestamp", Instant.now());
        body.put("database", Map.of("status", databaseUp ? "UP" : "DOWN"));
        body.put("dependencies", Map.of("accountService",
                Map.of("status", accountServiceUp ? "UP" : "DOWN")));
        return ResponseEntity.status(databaseUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean databaseUp() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }
}
