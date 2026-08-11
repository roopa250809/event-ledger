package com.eventledger.account.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** Reports Account Service and database health. */
@RestController
public class HealthController {
    private final JdbcTemplate jdbcTemplate;
    private final String serviceName;

    public HealthController(JdbcTemplate jdbcTemplate,
                            @Value("${spring.application.name}") String serviceName) {
        this.jdbcTemplate = jdbcTemplate;
        this.serviceName = serviceName;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(body("UP", "UP"));
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body("DOWN", "DOWN"));
        }
    }

    private Map<String, Object> body(String status, String databaseStatus) {
        return Map.of(
                "status", status,
                "service", serviceName,
                "timestamp", Instant.now(),
                "database", Map.of("status", databaseStatus));
    }
}
