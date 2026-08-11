package com.eventledger.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Bootstraps the public Event Gateway service. */
@SpringBootApplication
public class EventGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(EventGatewayApplication.class, args);
    }
}
