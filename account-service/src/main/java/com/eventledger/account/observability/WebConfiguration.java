package com.eventledger.account.observability;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final TraceResponseInterceptor traceResponseInterceptor;

    public WebConfiguration(TraceResponseInterceptor traceResponseInterceptor) {
        this.traceResponseInterceptor = traceResponseInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(traceResponseInterceptor);
    }
}
