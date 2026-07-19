package com.buildgraph.prototype.user;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.agent.PcAgentAsService;
import com.buildgraph.prototype.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(UserController.class)
class UserControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserQueryService userQueryService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private PcAgentAsService pcAgentAsService;

    @MockitoBean
    private GoogleOAuthService googleOAuthService;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    @Test
    void loginReturnsAuthResponse() throws Exception {
        when(userQueryService.login("admin@example.com", "passw0rd!")).thenReturn(Map.of(
                "accessToken", "jwt-access-token",
                "refreshToken", "demo-refresh-admin",
                "user", Map.of(
                        "id", "00000000-0000-4000-8000-000000000001",
                        "email", "admin@example.com",
                        "name", "Admin User",
                        "role", "ADMIN"
                )
        ));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "admin@example.com",
                                  "password": "passw0rd!"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("demo-refresh-admin"))
                .andExpect(jsonPath("$.user.id").value("00000000-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.user.email").value("admin@example.com"))
                .andExpect(jsonPath("$.user.name").value("Admin User"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"));

        verify(userQueryService).login("admin@example.com", "passw0rd!");
    }

    @Test
    void loginReturnsValidationErrorForInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "not-an-email",
                                  "password": "passw0rd!"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."));

        verifyNoInteractions(userQueryService);
    }

    @Test
    void loginReturnsValidationErrorForMalformedJson() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."));

        verifyNoInteractions(userQueryService);
    }

    @Test
    void loginRateLimitRejectsBeforeAuthService() throws Exception {
        doThrow(new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "Too many login attempts. Please try again later.",
                Map.of("scope", "ip", "retryAfterSeconds", 60)
        )).when(loginRateLimiter).checkAllowed(eq("admin@example.com"), any(HttpServletRequest.class));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "admin@example.com",
                                  "password": "passw0rd!"
                                }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.details.scope").value("ip"))
                .andExpect(jsonPath("$.details.retryAfterSeconds").value(60));

        verifyNoInteractions(userQueryService);
    }

    @Test
    void refreshReturnsNewTokens() throws Exception {
        when(userQueryService.refresh("opaque-refresh-token")).thenReturn(Map.of(
                "accessToken", "new-jwt-access-token",
                "refreshToken", "new-opaque-refresh-token"
        ));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "opaque-refresh-token"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-jwt-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("new-opaque-refresh-token"));

        verify(userQueryService).refresh("opaque-refresh-token");
    }

    @Test
    void googleStartRedirectsToProvider() throws Exception {
        when(googleOAuthService.start("/admin")).thenReturn(URI.create("https://accounts.google.com/o/oauth2/v2/auth?state=state"));

        mockMvc.perform(get("/api/auth/google/start")
                        .param("redirect", "/admin"))
                .andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "https://accounts.google.com/o/oauth2/v2/auth?state=state"));

        verify(googleOAuthService).start("/admin");
    }

    @Test
    void googleCallbackRedirectsToWebCallback() throws Exception {
        when(googleOAuthService.callback("google-code", "state", null)).thenReturn(URI.create("http://localhost:5173/auth/callback?code=one-time-code"));

        mockMvc.perform(get("/api/auth/google/callback")
                        .param("code", "google-code")
                        .param("state", "state"))
                .andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "http://localhost:5173/auth/callback?code=one-time-code"));

        verify(googleOAuthService).callback("google-code", "state", null);
    }

    @Test
    void exchangeReturnsAuthResponse() throws Exception {
        when(userQueryService.exchangeGoogleLogin(
                "one-time-code",
                true,
                false,
                "010-1234-5678",
                "06236",
                "서울시 강남구 테헤란로 1",
                "101호"
        )).thenReturn(Map.of(
                "accessToken", "jwt-access-token",
                "refreshToken", "refresh-token",
                "user", Map.of(
                        "id", "00000000-0000-4000-8000-000000001004",
                        "email", "user@example.com",
                        "name", "Demo User",
                        "role", "USER"
                )
        ));

        mockMvc.perform(post("/api/auth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "one-time-code",
                                  "termsAccepted": true,
                                  "marketingAccepted": false,
                                  "phoneNumber": "010-1234-5678",
                                  "postalCode": "06236",
                                  "addressLine1": "서울시 강남구 테헤란로 1",
                                  "addressLine2": "101호"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.user.role").value("USER"));

        verify(userQueryService).exchangeGoogleLogin(
                "one-time-code",
                true,
                false,
                "010-1234-5678",
                "06236",
                "서울시 강남구 테헤란로 1",
                "101호"
        );
    }

    @Test
    void logoutReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer jwt-access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "refreshToken": "opaque-refresh-token"
                                }
                                """))
                .andExpect(status().isNoContent());

        verify(userQueryService).logout("Bearer jwt-access-token", "opaque-refresh-token");
    }

    @Test
    void logoutReturnsValidationErrorForMissingRefreshToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer jwt-access-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("요청 값이 올바르지 않습니다."));

        verifyNoInteractions(userQueryService);
    }

    @Test
    void signupReturnsCreatedUserResponse() throws Exception {
        when(userQueryService.signup(
                "Demo User",
                "user@example.com",
                "passw0rd!",
                "010-1234-5678",
                "06236",
                "서울시 강남구 테헤란로 1",
                "101호",
                true,
                false
        )).thenReturn(Map.of(
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "name", "Demo User",
                "role", "USER"
        ));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@example.com",
                                  "password": "passw0rd!",
                                  "name": "Demo User",
                                  "phoneNumber": "010-1234-5678",
                                  "postalCode": "06236",
                                  "addressLine1": "서울시 강남구 테헤란로 1",
                                  "addressLine2": "101호",
                                  "role": "ADMIN",
                                  "termsAccepted": true,
                                  "marketingAccepted": false
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("00000000-0000-4000-8000-000000001004"))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.name").value("Demo User"))
                .andExpect(jsonPath("$.role").value("USER"));

        verify(userQueryService).signup(
                "Demo User",
                "user@example.com",
                "passw0rd!",
                "010-1234-5678",
                "06236",
                "서울시 강남구 테헤란로 1",
                "101호",
                true,
                false
        );
    }

    @Test
    void signupPassesMarketingAccepted() throws Exception {
        when(userQueryService.signup(
                "Demo User",
                "user@example.com",
                "passw0rd!",
                "010-1234-5678",
                "06236",
                "서울시 강남구 테헤란로 1",
                "101호",
                true,
                true
        )).thenReturn(Map.of(
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "name", "Demo User",
                "role", "USER"
        ));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "user@example.com",
                                  "password": "passw0rd!",
                                  "name": "Demo User",
                                  "phoneNumber": "010-1234-5678",
                                  "postalCode": "06236",
                                  "addressLine1": "서울시 강남구 테헤란로 1",
                                  "addressLine2": "101호",
                                  "termsAccepted": true,
                                  "marketingAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("00000000-0000-4000-8000-000000001004"))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.name").value("Demo User"))
                .andExpect(jsonPath("$.role").value("USER"));

        verify(userQueryService).signup(
                "Demo User",
                "user@example.com",
                "passw0rd!",
                "010-1234-5678",
                "06236",
                "서울시 강남구 테헤란로 1",
                "101호",
                true,
                true
        );
    }

    @Test
    void meReturnsCurrentUserForJwtToken() throws Exception {
        when(userQueryService.me("Bearer jwt-admin-token")).thenReturn(Map.of(
                "id", "00000000-0000-4000-8000-000000000001",
                "email", "admin@example.com",
                "name", "Admin User",
                "role", "ADMIN",
                "phoneNumber", "010-1234-5678",
                "postalCode", "06236",
                "addressLine1", "서울시 강남구 테헤란로 1",
                "addressLine2", "101호"
        ));

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer jwt-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("00000000-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.email").value("admin@example.com"))
                .andExpect(jsonPath("$.name").value("Admin User"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.phoneNumber").value("010-1234-5678"))
                .andExpect(jsonPath("$.postalCode").value("06236"))
                .andExpect(jsonPath("$.addressLine1").value("서울시 강남구 테헤란로 1"))
                .andExpect(jsonPath("$.addressLine2").value("101호"));

        verify(userQueryService).me("Bearer jwt-admin-token");
    }

    @Test
    void updateMeReturnsUpdatedProfile() throws Exception {
        when(userQueryService.updateMe(
                "Bearer jwt-user-token",
                "passw0rd!",
                null,
                "홍길동",
                "01012345678",
                "06236",
                "서울시 강남구 테헤란로 1",
                "101호"
        )).thenReturn(Map.of(
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "name", "홍길동",
                "role", "USER",
                "phoneNumber", "010-1234-5678",
                "postalCode", "06236",
                "addressLine1", "서울시 강남구 테헤란로 1",
                "addressLine2", "101호"
        ));

        mockMvc.perform(patch("/api/users/me")
                        .header("Authorization", "Bearer jwt-user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "홍길동",
                                  "currentPassword": "passw0rd!",
                                  "googleVerificationToken": null,
                                  "phoneNumber": "01012345678",
                                  "postalCode": "06236",
                                  "addressLine1": "서울시 강남구 테헤란로 1",
                                  "addressLine2": "101호"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.name").value("홍길동"))
                .andExpect(jsonPath("$.phoneNumber").value("010-1234-5678"));

        verify(userQueryService).updateMe(
                "Bearer jwt-user-token",
                "passw0rd!",
                null,
                "홍길동",
                "01012345678",
                "06236",
                "서울시 강남구 테헤란로 1",
                "101호"
        );
    }

    @Test
    void verifyProfilePasswordReturnsNoContent() throws Exception {
        mockMvc.perform(post("/api/users/me/password-verification")
                        .header("Authorization", "Bearer jwt-user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "password": "passw0rd!"
                                }
                                """))
                .andExpect(status().isNoContent());

        verify(userQueryService).verifyProfilePassword("Bearer jwt-user-token", "passw0rd!");
    }

    @Test
    void meReturnsUnauthorizedWhenTokenIsMissing() throws Exception {
        when(userQueryService.me(eq(null))).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required."));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Login required."));

        verify(userQueryService).me(null);
    }
}
