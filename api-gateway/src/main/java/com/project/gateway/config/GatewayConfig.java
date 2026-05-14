package com.project.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Configuration
public class GatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfig.class);
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "unknown"
        );
    }

    @Bean
    public GlobalFilter correlationIdFilter() {
        return new GlobalFilter() {
            @Override
            public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
                ServerHttpRequest request = exchange.getRequest();
                String correlationId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
                if (correlationId == null || correlationId.isBlank()) {
                    correlationId = UUID.randomUUID().toString();
                }
                final String finalCorrelationId = correlationId;

                ServerHttpRequest mutated = request.mutate()
                        .header(CORRELATION_ID_HEADER, finalCorrelationId)
                        .build();

                exchange.getResponse().getHeaders().add(CORRELATION_ID_HEADER, finalCorrelationId);

                log.info("Request {} {} [correlationId={}]",
                        request.getMethod(), request.getURI().getPath(), finalCorrelationId);

                return chain.filter(exchange.mutate().request(mutated).build());
            }
        };
    }
}
