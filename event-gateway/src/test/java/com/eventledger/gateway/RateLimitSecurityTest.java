package com.eventledger.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "event-ledger.security.rate-limit.requests-per-minute=1")
@AutoConfigureMockMvc
class RateLimitSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void limitsRequestsPerAuthenticatedClient() throws Exception {
        mockMvc.perform(get("/events/does-not-exist").with(jwt()
                        .jwt(token -> token.subject("rate-limited-client"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_events.read"))))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-RateLimit-Remaining", "0"));

        mockMvc.perform(get("/events/does-not-exist").with(jwt()
                        .jwt(token -> token.subject("rate-limited-client"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_events.read"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }
}
