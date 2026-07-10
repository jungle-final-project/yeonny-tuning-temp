package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.ApiException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GoogleOAuthServiceTest {
    private final GoogleOAuthRuntimeStore runtimeStore = org.mockito.Mockito.mock(GoogleOAuthRuntimeStore.class);
    private final GoogleOAuthClient googleOAuthClient = org.mockito.Mockito.mock(GoogleOAuthClient.class);
    private final GoogleOAuthProperties properties = new GoogleOAuthProperties(
            "google-client-id",
            "google-client-secret",
            "http://localhost:8080/api/auth/google/callback",
            "http://localhost:5173/auth/callback",
            300,
            300
    );
    private final GoogleOAuthService service = new GoogleOAuthService(properties, runtimeStore, googleOAuthClient);

    @Test
    void startCreatesStateAndRedirectsToGoogleWithSafeRedirect() {
        when(runtimeStore.createState("/")).thenReturn("state-token");

        URI uri = service.start("https://evil.example/admin");

        assertThat(uri.toString()).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(uri.toString()).contains("client_id=google-client-id");
        assertThat(uri.toString()).contains("redirect_uri=http://localhost:8080/api/auth/google/callback");
        assertThat(uri.toString()).contains("response_type=code");
        assertThat(uri.toString()).contains("scope=openid%20email%20profile");
        assertThat(uri.toString()).contains("state=state-token");
        verify(runtimeStore).createState("/");
    }

    @Test
    void callbackRejectsInvalidStateWithoutExchangingGoogleCode() {
        when(runtimeStore.consumeState("bad-state")).thenReturn(null);

        URI uri = service.callback("google-code", "bad-state", null);

        assertThat(uri.toString()).isEqualTo("http://localhost:5173/auth/callback?error=invalid_state");
        verify(googleOAuthClient, never()).exchangeAuthorizationCode(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void callbackStoresPendingLoginAndRedirectsToWebCallback() {
        GoogleOAuthPendingLogin pendingLogin = new GoogleOAuthPendingLogin(
                "google-sub-1",
                "admin@example.com",
                "BuildGraph Admin",
                true,
                "/admin"
        );
        when(runtimeStore.consumeState("state-token")).thenReturn("/admin");
        when(googleOAuthClient.exchangeAuthorizationCode("google-code", properties.redirectUri(), "/admin"))
                .thenReturn(pendingLogin);
        when(runtimeStore.createPendingLogin(pendingLogin)).thenReturn("one-time-code");

        URI uri = service.callback("google-code", "state-token", null);

        assertThat(uri.toString()).isEqualTo("http://localhost:5173/auth/callback?code=one-time-code&redirect=/admin");
    }

    @Test
    void startRequiresGoogleOAuthConfiguration() {
        GoogleOAuthProperties emptyProperties = new GoogleOAuthProperties(
                "",
                "",
                "http://localhost:8080/api/auth/google/callback",
                "http://localhost:5173/auth/callback",
                300,
                300
        );
        GoogleOAuthService emptyService = new GoogleOAuthService(emptyProperties, runtimeStore, googleOAuthClient);

        assertThatThrownBy(() -> emptyService.start("/"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                    assertThat(exception.code()).isEqualTo("PRECONDITION_REQUIRED");
                });
    }
}
