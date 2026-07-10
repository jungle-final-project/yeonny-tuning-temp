package com.buildgraph.prototype.user;

import com.buildgraph.prototype.common.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GoogleOAuthService {
    private static final String GOOGLE_AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";

    private final GoogleOAuthProperties properties;
    private final GoogleOAuthRuntimeStore runtimeStore;
    private final GoogleOAuthClient googleOAuthClient;

    public GoogleOAuthService(
            GoogleOAuthProperties properties,
            GoogleOAuthRuntimeStore runtimeStore,
            GoogleOAuthClient googleOAuthClient
    ) {
        this.properties = properties;
        this.runtimeStore = runtimeStore;
        this.googleOAuthClient = googleOAuthClient;
    }

    public URI start(String redirect) {
        requireConfigured();
        String safeRedirect = safeRedirectPath(redirect);
        String state = runtimeStore.createState(safeRedirect);
        return UriComponentsBuilder.fromUriString(GOOGLE_AUTHORIZATION_ENDPOINT)
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .queryParam("prompt", "select_account")
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();
    }

    public URI callback(String code, String state, String error) {
        if (error != null && !error.isBlank()) {
            return webCallbackWithError("google_denied");
        }
        if (code == null || code.isBlank()) {
            return webCallbackWithError("missing_code");
        }
        String redirectPath = runtimeStore.consumeState(state);
        if (redirectPath == null || redirectPath.isBlank()) {
            return webCallbackWithError("invalid_state");
        }
        try {
            GoogleOAuthPendingLogin pendingLogin = googleOAuthClient.exchangeAuthorizationCode(code, properties.redirectUri(), redirectPath);
            String oneTimeCode = runtimeStore.createPendingLogin(pendingLogin);
            return webCallback(oneTimeCode, redirectPath);
        } catch (ApiException exception) {
            return webCallbackWithError("google_exchange_failed");
        }
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new ApiException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "PRECONDITION_REQUIRED",
                    "Google OAuth is not configured.",
                    Map.of("required", "GOOGLE_OAUTH_CLIENT_ID, GOOGLE_OAUTH_CLIENT_SECRET, GOOGLE_OAUTH_REDIRECT_URI, GOOGLE_OAUTH_WEB_CALLBACK_URL")
            );
        }
    }

    private URI webCallback(String code, String redirectPath) {
        return UriComponentsBuilder.fromUriString(properties.webCallbackUrl())
                .queryParam("code", code)
                .queryParam("redirect", safeRedirectPath(redirectPath))
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();
    }

    private URI webCallbackWithError(String error) {
        return UriComponentsBuilder.fromUriString(properties.webCallbackUrl())
                .queryParam("error", error)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();
    }

    static String safeRedirectPath(String raw) {
        if (raw == null || raw.isBlank() || !raw.startsWith("/") || raw.startsWith("//")) {
            return "/";
        }
        return raw;
    }
}
