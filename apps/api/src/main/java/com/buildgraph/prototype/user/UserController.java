package com.buildgraph.prototype.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class UserController {
    @PostMapping("/auth/login")
    Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        return UserSeed.login(request.email());
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> signup(@Valid @RequestBody SignupRequest request) {
        return UserSeed.signup(request.name(), request.email());
    }

    @GetMapping("/auth/me")
    Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer demo-jwt-")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        return UserSeed.me(authorization);
    }

    record LoginRequest(@Email String email, @NotBlank String password) {
    }

    record SignupRequest(@NotBlank String name, @Email String email, @NotBlank String password) {
    }
}
