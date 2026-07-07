package com.buildgraph.prototype.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * /api/** CORS 매핑이 앱이 실제로 쓰는 전 HTTP 메서드를 허용하는지 회귀 검증.
 *
 * <p>PUT 누락 시 담기(PUT /api/quote-drafts/current/apply-ai-build, /items/{partId})가
 * cross-origin 배포에서 403 "Invalid CORS request"로 막힌다(로컬은 vite 프록시가 Origin을 떼어
 * CORS 필터가 안 걸려 드러나지 않음).
 */
class CorsConfigMethodsTest {
    @Test
    @SuppressWarnings("unchecked")
    void apiMappingAllowsEveryMethodTheAppUses() throws Exception {
        WebMvcConfigurer configurer = new CorsConfig(new BuildGraphCorsProperties(null)).corsConfigurer();
        CorsRegistry registry = new CorsRegistry();
        configurer.addCorsMappings(registry);

        Method getConfigurations = CorsRegistry.class.getDeclaredMethod("getCorsConfigurations");
        getConfigurations.setAccessible(true);
        Map<String, CorsConfiguration> configurations = (Map<String, CorsConfiguration>) getConfigurations.invoke(registry);

        CorsConfiguration api = configurations.get("/api/**");
        assertThat(api).as("/api/** CORS 매핑이 등록되어야 한다").isNotNull();
        assertThat(api.getAllowedMethods())
                .as("담기(PUT) 포함 앱이 쓰는 전 메서드가 허용돼야 한다")
                .contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }
}
