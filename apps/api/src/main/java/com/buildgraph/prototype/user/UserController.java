package com.buildgraph.prototype.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class UserController {
    private final UserQueryService userQueryService;

    public UserController(UserQueryService userQueryService) {
        this.userQueryService = userQueryService;
    }

    @PostMapping("/auth/login")
    Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        return userQueryService.login(request.email(), request.password());
    }

    @PostMapping("/auth/refresh")
    Map<String, Object> refresh(@Valid @RequestBody RefreshRequest request) {
        return userQueryService.refresh(request.refreshToken());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody RefreshRequest request
    ) {
        userQueryService.logout(authorization, request.refreshToken());
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> signup(@Valid @RequestBody SignupRequest request) {
        return userQueryService.signup(
                request.name(),
                request.email(),
                request.password(),
                request.termsAccepted(),
                request.marketingAccepted()
        );
    }

    @GetMapping("/auth/me")
    Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return userQueryService.me(authorization);
    }

    record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    record RefreshRequest(@NotBlank String refreshToken) {
    }

    record SignupRequest(
            @NotBlank String name,
            @NotBlank @Email String email,
            @NotBlank String password,
            @NotNull Boolean termsAccepted,
            Boolean marketingAccepted
    ) {
    }
}
