package com.buildgraph.prototype.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

public class AgentAccessTokenFilter extends OncePerRequestFilter {
    private static final String BEARER_PREFIX = "Bearer ";

    private final AgentTokenAuthenticationService authenticationService;
    private final SecurityErrorResponseWriter errorResponseWriter;

    public AgentAccessTokenFilter(
            AgentTokenAuthenticationService authenticationService,
            SecurityErrorResponseWriter errorResponseWriter
    ) {
        this.authenticationService = authenticationService;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = path(request);
        return !path.startsWith("/api/agent/") || isRegisterRequest(request, path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = bearerToken(request);
        if (token == null) {
            writeUnauthorized(response);
            return;
        }

        AgentTokenAuthenticationResult result = authenticationService.authenticate(token);
        if (result.status() == AgentTokenAuthenticationResult.Status.INVALID) {
            writeUnauthorized(response);
            return;
        }
        if (result.status() == AgentTokenAuthenticationResult.Status.FORBIDDEN) {
            SecurityContextHolder.clearContext();
            errorResponseWriter.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Agent device is not active.");
            return;
        }

        AgentPrincipal principal = result.principal().orElseThrow();
        AgentAuthenticationToken authentication = AgentAuthenticationToken.authenticated(principal);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return token.isBlank() ? null : token;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        errorResponseWriter.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Agent token is required.");
    }

    private static String path(HttpServletRequest request) {
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        return path == null ? request.getRequestURI() : path;
    }

    private static boolean isRegisterRequest(HttpServletRequest request, String path) {
        return "POST".equals(request.getMethod()) && "/api/agent/devices/register".equals(path);
    }
}
