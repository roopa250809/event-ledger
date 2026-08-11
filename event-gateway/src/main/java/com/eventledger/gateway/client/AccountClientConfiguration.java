package com.eventledger.gateway.client;

import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AccountClientConfiguration {
    @Bean
    RestClient accountRestClient(RestClient.Builder builder,
                                 Tracer tracer,
                                 @Value("${account-service.base-url}") String baseUrl,
                                 @Value("${account-service.connect-timeout}") Duration connectTimeout,
                                 @Value("${account-service.read-timeout}") Duration readTimeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    if (tracer.currentSpan() != null) {
                        request.getHeaders().set("X-Trace-Id", tracer.currentSpan().context().traceId());
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
