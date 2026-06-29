package com.buildgraph.prototype.user;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    void signupReturnsCreatedUserResponse() throws Exception {
        when(userQueryService.signup("Demo User", "user@example.com", "passw0rd!", true, false)).thenReturn(Map.of(
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
                                  "termsAccepted": true,
                                  "marketingAccepted": false
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("00000000-0000-4000-8000-000000001004"))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.name").value("Demo User"))
                .andExpect(jsonPath("$.role").value("USER"));

        verify(userQueryService).signup("Demo User", "user@example.com", "passw0rd!", true, false);
    }

    @Test
    void signupPassesMarketingAccepted() throws Exception {
        when(userQueryService.signup("Demo User", "user@example.com", "passw0rd!", true, true)).thenReturn(Map.of(
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
                                  "termsAccepted": true,
                                  "marketingAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("00000000-0000-4000-8000-000000001004"))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.name").value("Demo User"))
                .andExpect(jsonPath("$.role").value("USER"));

        verify(userQueryService).signup("Demo User", "user@example.com", "passw0rd!", true, true);
    }

    @Test
    void meReturnsCurrentUserForDemoToken() throws Exception {
        when(userQueryService.me("Bearer demo-access-admin")).thenReturn(Map.of(
                "id", "00000000-0000-4000-8000-000000000001",
                "email", "admin@example.com",
                "name", "Admin User",
                "role", "ADMIN"
        ));

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer demo-access-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("00000000-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.email").value("admin@example.com"))
                .andExpect(jsonPath("$.name").value("Admin User"))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        verify(userQueryService).me("Bearer demo-access-admin");
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
