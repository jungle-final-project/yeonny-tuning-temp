package com.buildgraph.prototype.user;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final PasswordService passwordService;
    private final JwtTokenService jwtTokenService;
    private final CurrentUserService currentUserService;
    private final RefreshTokenService refreshTokenService;
    private final GoogleOAuthRuntimeStore googleOAuthRuntimeStore;

    public UserQueryService(
            JdbcTemplate jdbcTemplate,
            PasswordService passwordService,
            JwtTokenService jwtTokenService,
            CurrentUserService currentUserService,
            RefreshTokenService refreshTokenService,
            GoogleOAuthRuntimeStore googleOAuthRuntimeStore
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordService = passwordService;
        this.jwtTokenService = jwtTokenService;
        this.currentUserService = currentUserService;
        this.refreshTokenService = refreshTokenService;
        this.googleOAuthRuntimeStore = googleOAuthRuntimeStore;
    }

    public Map<String, Object> login(String email, String password) {
        Map<String, Object> user = findByEmail(normalizeEmail(email));
        String passwordHash = DbValueMapper.string(user, "password_hash");
        if (!passwordService.matches(password, passwordHash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        Map<String, Object> userDto = userMap(user);
        return issueAuthResponse(user, userDto);
    }

    public Map<String, Object> signup(
            String name,
            String email,
            String password,
            Boolean termsAccepted,
            Boolean marketingAccepted
    ) {
        if (!Boolean.TRUE.equals(termsAccepted)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "약관 동의가 필요합니다.");
        }
        String normalizedEmail = normalizeEmail(email);
        List<Map<String, Object>> existing = findRowsByEmail(normalizedEmail);
        if (!existing.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", "이미 가입된 이메일입니다.");
        }
        String passwordHash = passwordService.hash(password);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO users (email, password_hash, name, role, terms_accepted_at, marketing_accepted_at)
                VALUES (?, ?, ?, 'USER', now(), CASE WHEN ? THEN now() ELSE NULL END)
                RETURNING public_id::text AS id, email, name, role, created_at
                """, normalizedEmail, passwordHash, name, Boolean.TRUE.equals(marketingAccepted));
        return userMap(row);
    }

    public Map<String, Object> exchangeGoogleLogin(String code, Boolean termsAccepted, Boolean marketingAccepted) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google login session has expired.");
        }
        GoogleOAuthPendingLogin pendingLogin = googleOAuthRuntimeStore.getPendingLogin(code);
        if (pendingLogin == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google login session has expired.");
        }
        if (!pendingLogin.emailVerified()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google email is not verified.");
        }

        String normalizedEmail = normalizeEmail(pendingLogin.email());
        Map<String, Object> linkedUser = findByGoogleProvider(pendingLogin.providerUserId());
        Map<String, Object> emailUser = linkedUser == null ? findOptionalByEmail(normalizedEmail) : null;
        if (linkedUser == null && emailUser == null && !Boolean.TRUE.equals(termsAccepted)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "Google 회원가입을 완료하려면 약관 동의가 필요합니다.",
                    Map.of("reason", "TERMS_REQUIRED", "email", normalizedEmail, "name", safeName(pendingLogin.name(), normalizedEmail))
            );
        }

        GoogleOAuthPendingLogin consumedLogin = googleOAuthRuntimeStore.consumePendingLogin(code);
        if (consumedLogin == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google login session has expired.");
        }
        return completeGoogleLogin(consumedLogin, marketingAccepted);
    }

    public Map<String, Object> me(String authorization) {
        return currentUserService.requireUser(authorization).toUserMap();
    }

    public Map<String, Object> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token.");
        }

        Map<String, Object> tokenRow = findActiveRefreshToken(refreshTokenService.hash(refreshToken));
        Map<String, Object> user = findByInternalId(longValue(tokenRow, "user_id"));
        Map<String, Object> userDto = userMap(user);

        revokeRefreshToken(longValue(tokenRow, "id"));
        RefreshTokenService.IssuedRefreshToken nextRefreshToken = refreshTokenService.issue();
        storeRefreshToken(user, nextRefreshToken);

        return MockData.map(
                "accessToken", jwtTokenService.issueAccessToken(userDto),
                "refreshToken", nextRefreshToken.token()
        );
    }

    public void logout(String authorization, String refreshToken) {
        currentUserService.requireUser(authorization);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token.");
        }

        Map<String, Object> tokenRow = findActiveRefreshToken(refreshTokenService.hash(refreshToken));
        revokeRefreshToken(longValue(tokenRow, "id"));
    }

    private Map<String, Object> findByEmail(String email) {
        return findRowsByEmail(email)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "등록된 사용자를 찾을 수 없습니다."));
    }

    private Map<String, Object> findOptionalByEmail(String email) {
        return findRowsByEmail(email)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> findByGoogleProvider(String providerUserId) {
        return jdbcTemplate.queryForList("""
                SELECT u.id AS internal_id, u.public_id::text AS id, u.email, u.password_hash, u.name, u.role, u.created_at
                FROM user_auth_providers p
                JOIN users u ON u.id = p.user_id
                WHERE p.provider = 'GOOGLE'
                  AND p.provider_user_id = ?
                  AND u.deleted_at IS NULL
                """, providerUserId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> findActiveRefreshToken(String tokenHash) {
        return jdbcTemplate.queryForList("""
                SELECT id, user_id, token_hash, expires_at, revoked_at
                FROM refresh_tokens
                WHERE token_hash = ?
                  AND revoked_at IS NULL
                  AND expires_at > now()
                """, tokenHash)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token."));
    }

    private void revokeRefreshToken(Long refreshTokenId) {
        jdbcTemplate.update("""
                UPDATE refresh_tokens
                SET revoked_at = now()
                WHERE id = ?
                """, refreshTokenId);
    }

    private void storeRefreshToken(Map<String, Object> user, RefreshTokenService.IssuedRefreshToken refreshToken) {
        jdbcTemplate.update("""
                INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                """,
                longValue(user, "internal_id"),
                refreshToken.tokenHash(),
                Timestamp.from(refreshToken.expiresAt())
        );
    }

    private Map<String, Object> completeGoogleLogin(GoogleOAuthPendingLogin pendingLogin, Boolean marketingAccepted) {
        String normalizedEmail = normalizeEmail(pendingLogin.email());
        Map<String, Object> user = findByGoogleProvider(pendingLogin.providerUserId());
        if (user == null) {
            Map<String, Object> emailUser = findOptionalByEmail(normalizedEmail);
            if (emailUser != null) {
                linkGoogleProvider(emailUser, pendingLogin, normalizedEmail);
                user = findByInternalId(longValue(emailUser, "internal_id"));
            } else {
                user = createGoogleUser(pendingLogin, normalizedEmail, marketingAccepted);
            }
        }
        Map<String, Object> userDto = userMap(user);
        return issueAuthResponse(user, userDto);
    }

    private Map<String, Object> createGoogleUser(GoogleOAuthPendingLogin pendingLogin, String normalizedEmail, Boolean marketingAccepted) {
        Map<String, Object> user = jdbcTemplate.queryForMap("""
                INSERT INTO users (email, password_hash, name, role, terms_accepted_at, marketing_accepted_at)
                VALUES (?, NULL, ?, 'USER', now(), CASE WHEN ? THEN now() ELSE NULL END)
                RETURNING id AS internal_id, public_id::text AS id, email, password_hash, name, role, created_at
                """, normalizedEmail, safeName(pendingLogin.name(), normalizedEmail), Boolean.TRUE.equals(marketingAccepted));
        linkGoogleProvider(user, pendingLogin, normalizedEmail);
        return user;
    }

    private void linkGoogleProvider(Map<String, Object> user, GoogleOAuthPendingLogin pendingLogin, String normalizedEmail) {
        jdbcTemplate.update("""
                INSERT INTO user_auth_providers (user_id, provider, provider_user_id, provider_email, email_verified)
                VALUES (?, 'GOOGLE', ?, ?, ?)
                ON CONFLICT (provider, provider_user_id) DO NOTHING
                """,
                longValue(user, "internal_id"),
                pendingLogin.providerUserId(),
                normalizedEmail,
                pendingLogin.emailVerified()
        );
    }

    private Map<String, Object> issueAuthResponse(Map<String, Object> user, Map<String, Object> userDto) {
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue();
        storeRefreshToken(user, refreshToken);
        return MockData.map(
                "accessToken", jwtTokenService.issueAccessToken(userDto),
                "refreshToken", refreshToken.token(),
                "user", userDto
        );
    }

    private Map<String, Object> findByInternalId(Long userId) {
        return jdbcTemplate.queryForList("""
                SELECT id AS internal_id, public_id::text AS id, email, password_hash, name, role, created_at
                FROM users
                WHERE id = ?
                  AND deleted_at IS NULL
                """, userId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token."));
    }

    private List<Map<String, Object>> findRowsByEmail(String email) {
        return jdbcTemplate.queryForList("""
                SELECT id AS internal_id, public_id::text AS id, email, password_hash, name, role, created_at
                FROM users
                WHERE email = ?
                  AND deleted_at IS NULL
                """, email);
    }

    private Map<String, Object> userMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "email", DbValueMapper.string(row, "email"),
                "name", DbValueMapper.string(row, "name"),
                "role", DbValueMapper.string(row, "role"),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private String safeName(String rawName, String email) {
        if (rawName != null && !rawName.isBlank()) {
            return rawName.trim();
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }
}
