package com.buildgraph.prototype.user;

import com.buildgraph.prototype.agent.PcAgentAsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class UserController {
    private final UserQueryService userQueryService;
    private final CurrentUserService currentUserService;
    private final PcAgentAsService pcAgentAsService;
    private final GoogleOAuthService googleOAuthService;
    private final LoginRateLimiter loginRateLimiter;

    public UserController(
            UserQueryService userQueryService,
            CurrentUserService currentUserService,
            PcAgentAsService pcAgentAsService,
            GoogleOAuthService googleOAuthService,
            LoginRateLimiter loginRateLimiter
    ) {
        this.userQueryService = userQueryService;
        this.currentUserService = currentUserService;
        this.pcAgentAsService = pcAgentAsService;
        this.googleOAuthService = googleOAuthService;
        this.loginRateLimiter = loginRateLimiter;
    }

    @PostMapping("/auth/login")
    Map<String, Object> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        loginRateLimiter.checkAllowed(request.email(), servletRequest);
        try {
            Map<String, Object> response = userQueryService.login(request.email(), request.password());
            loginRateLimiter.recordSuccess(request.email(), servletRequest);
            return response;
        } catch (ResponseStatusException error) {
            if (error.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                loginRateLimiter.recordFailure(request.email(), servletRequest);
            }
            throw error;
        }
    }

    @PostMapping("/auth/refresh")
    Map<String, Object> refresh(@Valid @RequestBody RefreshRequest request) {
        return userQueryService.refresh(request.refreshToken());
    }

    @GetMapping("/auth/google/start")
    ResponseEntity<Void> googleStart(@RequestParam(value = "redirect", required = false) String redirect) {
        URI location = googleOAuthService.start(redirect);
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    @GetMapping("/auth/google/callback")
    ResponseEntity<Void> googleCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error
    ) {
        URI location = googleOAuthService.callback(code, state, error);
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    @PostMapping("/auth/exchange")
    Map<String, Object> exchange(@Valid @RequestBody AuthExchangeRequest request) {
        return userQueryService.exchangeGoogleLogin(
                request.code(),
                request.termsAccepted(),
                request.marketingAccepted(),
                request.phoneNumber(),
                request.postalCode(),
                request.addressLine1(),
                request.addressLine2()
        );
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
                request.phoneNumber(),
                request.postalCode(),
                request.addressLine1(),
                request.addressLine2(),
                request.termsAccepted(),
                request.marketingAccepted()
        );
    }

    @GetMapping("/auth/me")
    Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return userQueryService.me(authorization);
    }

    @PatchMapping("/users/me")
    Map<String, Object> updateMe(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ProfileUpdateRequest request
    ) {
        return userQueryService.updateMe(
                authorization,
                request.currentPassword(),
                request.googleVerificationToken(),
                request.name(),
                request.phoneNumber(),
                request.postalCode(),
                request.addressLine1(),
                request.addressLine2()
        );
    }

    @PostMapping("/users/me/password-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void verifyProfilePassword(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody PasswordVerificationRequest request
    ) {
        userQueryService.verifyProfilePassword(authorization, request.password());
    }

    @PostMapping("/users/me/agent-activation-token")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> issueAgentActivationToken(@RequestHeader(value = "Authorization", required = false) String authorization) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return pcAgentAsService.issueActivationTokenForUser(user);
    }

    record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    record RefreshRequest(@NotBlank String refreshToken) {
    }

    record AuthExchangeRequest(
            @NotBlank String code,
            Boolean termsAccepted,
            Boolean marketingAccepted,
            String phoneNumber,
            String postalCode,
            String addressLine1,
            String addressLine2
    ) {
    }

    record SignupRequest(
            @NotBlank String name,
            @NotBlank @Email String email,
            @NotBlank String password,
            @NotBlank String phoneNumber,
            @NotBlank String postalCode,
            @NotBlank String addressLine1,
            @NotBlank String addressLine2,
            @NotNull Boolean termsAccepted,
            Boolean marketingAccepted
    ) {
    }

    record ProfileUpdateRequest(
            String currentPassword,
            String googleVerificationToken,
            @NotBlank String name,
            @NotBlank String phoneNumber,
            @NotBlank String postalCode,
            @NotBlank String addressLine1,
            @NotBlank String addressLine2
    ) {
    }

    record PasswordVerificationRequest(@NotBlank String password) {
    }
}
