package com.buildgraph.prototype.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class AgentSecurityConfig {
    @Bean
    AgentAccessTokenFilter agentAccessTokenFilter(
            AgentTokenAuthenticationService authenticationService,
            SecurityErrorResponseWriter errorResponseWriter
    ) {
        return new AgentAccessTokenFilter(authenticationService, errorResponseWriter);
    }

    @Bean
    AgentIdempotencyFilter agentIdempotencyFilter(
            AgentIdempotencyService idempotencyService,
            SecurityErrorResponseWriter errorResponseWriter
    ) {
        return new AgentIdempotencyFilter(
                new AgentIdempotencyKeyExtractor(),
                idempotencyService,
                errorResponseWriter
        );
    }

    @Bean
    @Order(1)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AgentAccessTokenFilter agentAccessTokenFilter,
            AgentIdempotencyFilter agentIdempotencyFilter
    ) throws Exception {
        return http
                .securityMatcher("/api/agent/**")
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/agent/devices/register").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(agentAccessTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(agentIdempotencyFilter, AgentAccessTokenFilter.class)
                .build();
    }
}
