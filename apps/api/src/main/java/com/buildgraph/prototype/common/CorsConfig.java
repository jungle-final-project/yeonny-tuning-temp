package com.buildgraph.prototype.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {
    private final BuildGraphCorsProperties corsProperties;

    public CorsConfig(BuildGraphCorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Bean
    WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NonNull CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(corsProperties.allowedOrigins())
                        // PUT 누락 시 담기(PUT /quote-drafts/current/apply-ai-build, /items/{partId})가
                        // cross-origin 배포(CloudFront가 Origin 전달)에서 403 "Invalid CORS request"로 막힌다.
                        // 앱이 쓰는 전 메서드를 명시한다.
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
