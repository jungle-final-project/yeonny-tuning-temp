package com.buildgraph.prototype.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GoogleOAuthProperties {
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String webCallbackUrl;
    private final long stateTtlSeconds;
    private final long codeTtlSeconds;

    public GoogleOAuthProperties(
            @Value("${buildgraph.auth.google.client-id:${GOOGLE_OAUTH_CLIENT_ID:}}") String clientId,
            @Value("${buildgraph.auth.google.client-secret:${GOOGLE_OAUTH_CLIENT_SECRET:}}") String clientSecret,
            @Value("${buildgraph.auth.google.redirect-uri:${GOOGLE_OAUTH_REDIRECT_URI:http://localhost:8080/api/auth/google/callback}}") String redirectUri,
            @Value("${buildgraph.auth.google.web-callback-url:${GOOGLE_OAUTH_WEB_CALLBACK_URL:http://localhost:5173/auth/callback}}") String webCallbackUrl,
            @Value("${buildgraph.auth.google.state-ttl-seconds:300}") long stateTtlSeconds,
            @Value("${buildgraph.auth.google.code-ttl-seconds:300}") long codeTtlSeconds
    ) {
        this.clientId = trim(clientId);
        this.clientSecret = trim(clientSecret);
        this.redirectUri = trim(redirectUri);
        this.webCallbackUrl = trim(webCallbackUrl);
        this.stateTtlSeconds = stateTtlSeconds;
        this.codeTtlSeconds = codeTtlSeconds;
    }

    public String clientId() {
        return clientId;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public String redirectUri() {
        return redirectUri;
    }

    public String webCallbackUrl() {
        return webCallbackUrl;
    }

    public long stateTtlSeconds() {
        return stateTtlSeconds;
    }

    public long codeTtlSeconds() {
        return codeTtlSeconds;
    }

    public boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !redirectUri.isBlank() && !webCallbackUrl.isBlank();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
