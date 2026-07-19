package com.buildgraph.prototype.user;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserQueryService {
    private static final Logger log = LoggerFactory.getLogger(UserQueryService.class);

    private final JdbcTemplate jdbcTemplate;
    private final PasswordService passwordService;
    private final JwtTokenService jwtTokenService;
    private final CurrentUserService currentUserService;
    private final RefreshTokenService refreshTokenService;
    private final GoogleOAuthRuntimeStore googleOAuthRuntimeStore;
    private final int maxActiveRefreshTokensPerUser;

    @Autowired
    public UserQueryService(
            JdbcTemplate jdbcTemplate,
            PasswordService passwordService,
            JwtTokenService jwtTokenService,
            CurrentUserService currentUserService,
            RefreshTokenService refreshTokenService,
            GoogleOAuthRuntimeStore googleOAuthRuntimeStore,
            @Value("${buildgraph.auth.refresh-token-cleanup.max-active-per-user:3}") int maxActiveRefreshTokensPerUser
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordService = passwordService;
        this.jwtTokenService = jwtTokenService;
        this.currentUserService = currentUserService;
        this.refreshTokenService = refreshTokenService;
        this.googleOAuthRuntimeStore = googleOAuthRuntimeStore;
        this.maxActiveRefreshTokensPerUser = Math.max(1, maxActiveRefreshTokensPerUser);
    }

    UserQueryService(
            JdbcTemplate jdbcTemplate,
            PasswordService passwordService,
            JwtTokenService jwtTokenService,
            CurrentUserService currentUserService,
            RefreshTokenService refreshTokenService,
            GoogleOAuthRuntimeStore googleOAuthRuntimeStore
    ) {
        this(
                jdbcTemplate,
                passwordService,
                jwtTokenService,
                currentUserService,
                refreshTokenService,
                googleOAuthRuntimeStore,
                3
        );
    }

    public Map<String, Object> login(String email, String password) {
        long startedAt = System.nanoTime();
        long lookupStartedAt = startedAt;
        Map<String, Object> user = findByEmail(normalizeEmail(email));
        long lookupFinishedAt = System.nanoTime();
        String passwordHash = DbValueMapper.string(user, "password_hash");
        long bcryptStartedAt = lookupFinishedAt;
        if (!passwordService.matches(password, passwordHash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        long bcryptFinishedAt = System.nanoTime();
        Map<String, Object> userDto = minimalUserMap(user);
        long refreshIssueStartedAt = bcryptFinishedAt;
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue();
        long refreshIssueFinishedAt = System.nanoTime();
        storeRefreshToken(user, refreshToken);
        long refreshInsertFinishedAt = System.nanoTime();
        String accessToken = jwtTokenService.issueAccessToken(userDto);
        long jwtFinishedAt = System.nanoTime();

        log.debug(
                "Auth login timing: lookupMs={} bcryptMs={} refreshIssueMs={} refreshInsertMs={} jwtMs={} totalMs={}",
                elapsedMillis(lookupStartedAt, lookupFinishedAt),
                elapsedMillis(bcryptStartedAt, bcryptFinishedAt),
                elapsedMillis(refreshIssueStartedAt, refreshIssueFinishedAt),
                elapsedMillis(refreshIssueFinishedAt, refreshInsertFinishedAt),
                elapsedMillis(refreshInsertFinishedAt, jwtFinishedAt),
                elapsedMillis(startedAt, jwtFinishedAt)
        );

        return MockData.map(
                "accessToken", accessToken,
                "refreshToken", refreshToken.token(),
                "user", userDto
        );
    }

    public Map<String, Object> signup(
            String name,
            String email,
            String password,
            String phoneNumber,
            String postalCode,
            String addressLine1,
            String addressLine2,
            Boolean termsAccepted,
            Boolean marketingAccepted
    ) {
        if (!Boolean.TRUE.equals(termsAccepted)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "약관 동의가 필요합니다.");
        }
        String normalizedPhoneNumber = normalizePhoneNumber(phoneNumber);
        String normalizedPostalCode = normalizePostalCode(postalCode);
        String normalizedAddressLine1 = normalizeAddressLine1(addressLine1);
        String normalizedAddressLine2 = normalizeAddressLine2(addressLine2);
        String normalizedEmail = normalizeEmail(email);
        List<Map<String, Object>> existing = findRowsByEmail(normalizedEmail);
        if (!existing.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", "이미 가입된 이메일입니다.");
        }
        String passwordHash = passwordService.hash(password);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO users (
                    email,
                    password_hash,
                    name,
                    phone_number,
                    postal_code,
                    address_line1,
                    address_line2,
                    role,
                    terms_accepted_at,
                    marketing_accepted_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'USER', now(), CASE WHEN ? THEN now() ELSE NULL END)
                RETURNING id AS internal_id,
                          public_id::text AS id,
                          email,
                          password_hash,
                          name,
                          role,
                          phone_number,
                          postal_code,
                          address_line1,
                          address_line2,
                          created_at,
                          '' AS auth_providers
                """,
                normalizedEmail,
                passwordHash,
                name,
                normalizedPhoneNumber,
                normalizedPostalCode,
                normalizedAddressLine1,
                normalizedAddressLine2,
                Boolean.TRUE.equals(marketingAccepted)
        );
        return userMap(row);
    }

    public Map<String, Object> exchangeGoogleLogin(
            String code,
            Boolean termsAccepted,
            Boolean marketingAccepted,
            String phoneNumber,
            String postalCode,
            String addressLine1,
            String addressLine2
    ) {
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
        boolean newGoogleUser = linkedUser == null && emailUser == null;
        if (newGoogleUser && !Boolean.TRUE.equals(termsAccepted)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "Google 회원가입을 완료하려면 약관 동의가 필요합니다.",
                    Map.of("reason", "TERMS_REQUIRED", "email", normalizedEmail, "name", safeName(pendingLogin.name(), normalizedEmail))
            );
        }
        Map<String, Object> existingUser = linkedUser != null ? linkedUser : emailUser;
        boolean hasContactInput = hasAnyText(phoneNumber, postalCode, addressLine1, addressLine2);
        if (!newGoogleUser && requiresContact(existingUser) && !hasContactInput) {
            throw contactRequired(existingUser);
        }
        ContactAddress contactAddress = newGoogleUser || hasContactInput
                ? requiredContactAddress(phoneNumber, postalCode, addressLine1, addressLine2)
                : null;

        GoogleOAuthPendingLogin consumedLogin = googleOAuthRuntimeStore.consumePendingLogin(code);
        if (consumedLogin == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google login session has expired.");
        }
        return completeGoogleLogin(consumedLogin, marketingAccepted, contactAddress);
    }

    public Map<String, Object> me(String authorization) {
        CurrentUserService.CurrentUser currentUser = currentUserService.requireUser(authorization);
        return userMap(findByInternalId(currentUser.internalId()));
    }

    public Map<String, Object> updateMe(
            String authorization,
            String currentPassword,
            String googleVerificationToken,
            String name,
            String phoneNumber,
            String postalCode,
            String addressLine1,
            String addressLine2
    ) {
        CurrentUserService.CurrentUser currentUser = currentUserService.requireUser(authorization);
        Map<String, Object> user = findByInternalId(currentUser.internalId());
        boolean usedGoogleVerification = verifyProfileEdit(user, currentPassword, googleVerificationToken);
        ContactAddress contactAddress = requiredContactAddress(phoneNumber, postalCode, addressLine1, addressLine2);
        jdbcTemplate.update("""
                UPDATE users
                SET name = ?,
                    phone_number = ?,
                    postal_code = ?,
                    address_line1 = ?,
                    address_line2 = ?,
                    updated_at = now()
                WHERE id = ?
                """,
                normalizeName(name),
                contactAddress.phoneNumber(),
                contactAddress.postalCode(),
                contactAddress.addressLine1(),
                contactAddress.addressLine2(),
                currentUser.internalId()
        );
        if (usedGoogleVerification) {
            googleOAuthRuntimeStore.consumeProfileVerificationToken(googleVerificationToken);
        }
        currentUserService.evictCachedUser(currentUser.id());
        return userMap(findByInternalId(currentUser.internalId()));
    }

    public void verifyProfilePassword(String authorization, String password) {
        CurrentUserService.CurrentUser currentUser = currentUserService.requireUser(authorization);
        verifyPasswordForProfileEdit(findByInternalId(currentUser.internalId()), password);
    }

    public Map<String, Object> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token.");
        }

        Map<String, Object> tokenRow = findActiveRefreshToken(refreshTokenService.hash(refreshToken));
        Map<String, Object> user = findAuthTokenUserByInternalId(longValue(tokenRow, "user_id"));
        Map<String, Object> userDto = minimalUserMap(user);

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
        return jdbcTemplate.queryForList("""
                SELECT id AS internal_id,
                       public_id::text AS id,
                       email,
                       password_hash,
                       name,
                       role
                FROM users
                WHERE email = ?
                  AND deleted_at IS NULL
                """, email)
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
                SELECT u.id AS internal_id,
                       u.public_id::text AS id,
                       u.email,
                       u.password_hash,
                       u.name,
                       u.role,
                       u.phone_number,
                       u.postal_code,
                       u.address_line1,
                       u.address_line2,
                       u.created_at
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
        Long userId = longValue(user, "internal_id");
        jdbcTemplate.update("""
                INSERT INTO refresh_tokens (user_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                """,
                userId,
                refreshToken.tokenHash(),
                Timestamp.from(refreshToken.expiresAt())
        );
        revokeExcessActiveRefreshTokensForUser(userId);
    }

    private int revokeExcessActiveRefreshTokensForUser(Long userId) {
        return jdbcTemplate.update("""
                WITH ranked AS (
                  SELECT id,
                         row_number() OVER (
                           ORDER BY created_at DESC, id DESC
                         ) AS active_rank
                  FROM refresh_tokens
                  WHERE user_id = ?
                    AND revoked_at IS NULL
                    AND expires_at > now()
                )
                UPDATE refresh_tokens
                SET revoked_at = now()
                WHERE id IN (
                  SELECT id
                  FROM ranked
                  WHERE active_rank > ?
                )
                """, userId, maxActiveRefreshTokensPerUser);
    }

    private Map<String, Object> completeGoogleLogin(
            GoogleOAuthPendingLogin pendingLogin,
            Boolean marketingAccepted,
            ContactAddress contactAddress
    ) {
        String normalizedEmail = normalizeEmail(pendingLogin.email());
        Map<String, Object> user = findByGoogleProvider(pendingLogin.providerUserId());
        if (user == null) {
            Map<String, Object> emailUser = findOptionalByEmail(normalizedEmail);
            if (emailUser != null) {
                linkGoogleProvider(emailUser, pendingLogin, normalizedEmail);
                user = findByInternalId(longValue(emailUser, "internal_id"));
            } else {
                user = createGoogleUser(pendingLogin, normalizedEmail, marketingAccepted, contactAddress);
            }
        }
        if (contactAddress != null && requiresContact(user)) {
            updateUserContact(user, contactAddress);
            user = findByInternalId(longValue(user, "internal_id"));
        }
        Map<String, Object> userDto = minimalUserMap(user);
        String profileVerificationToken = profileVerificationRequested(pendingLogin.redirectPath())
                ? googleOAuthRuntimeStore.createProfileVerificationToken(DbValueMapper.string(user, "id"), pendingLogin.providerUserId())
                : null;
        return issueAuthResponse(user, userDto, profileVerificationToken);
    }

    private Map<String, Object> createGoogleUser(
            GoogleOAuthPendingLogin pendingLogin,
            String normalizedEmail,
            Boolean marketingAccepted,
            ContactAddress contactAddress
    ) {
        Map<String, Object> user = new java.util.LinkedHashMap<>(jdbcTemplate.queryForMap("""
                INSERT INTO users (
                    email,
                    password_hash,
                    name,
                    phone_number,
                    postal_code,
                    address_line1,
                    address_line2,
                    role,
                    terms_accepted_at,
                    marketing_accepted_at
                )
                VALUES (?, NULL, ?, ?, ?, ?, ?, 'USER', now(), CASE WHEN ? THEN now() ELSE NULL END)
                RETURNING id AS internal_id,
                          public_id::text AS id,
                          email,
                          password_hash,
                          name,
                          role,
                          phone_number,
                          postal_code,
                          address_line1,
                          address_line2,
                          created_at,
                          '' AS auth_providers
                """,
                normalizedEmail,
                safeName(pendingLogin.name(), normalizedEmail),
                contactAddress.phoneNumber(),
                contactAddress.postalCode(),
                contactAddress.addressLine1(),
                contactAddress.addressLine2(),
                Boolean.TRUE.equals(marketingAccepted)
        ));
        linkGoogleProvider(user, pendingLogin, normalizedEmail);
        user.put("auth_providers", "GOOGLE");
        return user;
    }

    private void updateUserContact(Map<String, Object> user, ContactAddress contactAddress) {
        jdbcTemplate.update("""
                UPDATE users
                SET phone_number = ?,
                    postal_code = ?,
                    address_line1 = ?,
                    address_line2 = ?,
                    updated_at = now()
                WHERE id = ?
                """,
                contactAddress.phoneNumber(),
                contactAddress.postalCode(),
                contactAddress.addressLine1(),
                contactAddress.addressLine2(),
                longValue(user, "internal_id")
        );
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
        return issueAuthResponse(user, userDto, null);
    }

    private Map<String, Object> issueAuthResponse(Map<String, Object> user, Map<String, Object> userDto, String profileVerificationToken) {
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue();
        storeRefreshToken(user, refreshToken);
        Map<String, Object> response = MockData.map(
                "accessToken", jwtTokenService.issueAccessToken(userDto),
                "refreshToken", refreshToken.token(),
                "user", userDto
        );
        if (profileVerificationToken != null && !profileVerificationToken.isBlank()) {
            response.put("profileVerificationToken", profileVerificationToken);
        }
        return response;
    }

    private Map<String, Object> findByInternalId(Long userId) {
        return jdbcTemplate.queryForList("""
                SELECT u.id AS internal_id,
                       u.public_id::text AS id,
                       u.email,
                       u.password_hash,
                       u.name,
                       u.role,
                       u.phone_number,
                       u.postal_code,
                       u.address_line1,
                       u.address_line2,
                       u.created_at,
                       COALESCE(provider_summary.auth_providers, '') AS auth_providers
                FROM users u
                LEFT JOIN LATERAL (
                    SELECT string_agg(provider.provider, ',' ORDER BY provider.provider) AS auth_providers
                    FROM user_auth_providers provider
                    WHERE provider.user_id = u.id
                ) provider_summary ON true
                WHERE u.id = ?
                  AND u.deleted_at IS NULL
                """, userId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token."));
    }

    private Map<String, Object> findAuthTokenUserByInternalId(Long userId) {
        return jdbcTemplate.queryForList("""
                SELECT id AS internal_id,
                       public_id::text AS id,
                       email,
                       name,
                       role
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
                SELECT id AS internal_id,
                       public_id::text AS id,
                       email,
                       password_hash,
                       name,
                       role,
                       phone_number,
                       postal_code,
                       address_line1,
                       address_line2,
                       created_at
                FROM users
                WHERE email = ?
                  AND deleted_at IS NULL
                """, email);
    }

    private Map<String, Object> minimalUserMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "email", DbValueMapper.string(row, "email"),
                "name", DbValueMapper.string(row, "name"),
                "role", DbValueMapper.string(row, "role")
        );
    }

    private double elapsedMillis(long startedAt, long finishedAt) {
        double millis = (finishedAt - startedAt) / 1_000_000.0;
        return Math.round(millis * 100.0) / 100.0;
    }

    private Map<String, Object> userMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "email", DbValueMapper.string(row, "email"),
                "name", DbValueMapper.string(row, "name"),
                "role", DbValueMapper.string(row, "role"),
                "phoneNumber", DbValueMapper.string(row, "phone_number"),
                "postalCode", DbValueMapper.string(row, "postal_code"),
                "addressLine1", DbValueMapper.string(row, "address_line1"),
                "addressLine2", DbValueMapper.string(row, "address_line2"),
                "authProviders", authProviders(row),
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

    private String normalizeName(String value) {
        return normalizeSpaces(requiredText(value, 100, "이름", "name"));
    }

    private boolean verifyProfileEdit(Map<String, Object> user, String currentPassword, String googleVerificationToken) {
        if (currentPassword != null && !currentPassword.isBlank()) {
            verifyPasswordForProfileEdit(user, currentPassword);
            return false;
        }
        if (googleVerificationToken != null && !googleVerificationToken.isBlank()) {
            verifyGoogleProfileEdit(user, googleVerificationToken);
            return true;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "현재 비밀번호 확인 또는 Google 본인 확인이 필요합니다.");
    }

    private void verifyPasswordForProfileEdit(Map<String, Object> user, String password) {
        String passwordHash = DbValueMapper.string(user, "password_hash");
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "비밀번호 로그인이 없는 계정은 현재 비밀번호 확인을 사용할 수 없습니다.");
        }
        if (!passwordService.matches(password, passwordHash)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "비밀번호가 올바르지 않습니다.");
        }
    }

    private void verifyGoogleProfileEdit(Map<String, Object> user, String googleVerificationToken) {
        GoogleProfileVerification verification = googleOAuthRuntimeStore.getProfileVerificationToken(googleVerificationToken);
        if (verification == null || !DbValueMapper.string(user, "id").equals(verification.userId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Google 본인 확인이 만료되었습니다. 다시 확인해 주세요.");
        }
        if (!hasGoogleProvider(longValue(user, "internal_id"), verification.providerUserId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "현재 계정과 다른 Google 계정입니다.");
        }
    }

    private String requiredText(String value, int maxLength, String label) {
        return requiredText(value, maxLength, label, null);
    }

    private String requiredText(String value, int maxLength, String label, String field) {
        if (value == null || value.isBlank()) {
            throw validationError(field, "REQUIRED", label + "을(를) 입력해 주세요.");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw validationError(field, "TOO_LONG", label + "은(는) " + maxLength + "자 이하여야 합니다.");
        }
        return normalized;
    }

    private ContactAddress requiredContactAddress(String phoneNumber, String postalCode, String addressLine1, String addressLine2) {
        return new ContactAddress(
                normalizePhoneNumber(phoneNumber),
                normalizePostalCode(postalCode),
                normalizeAddressLine1(addressLine1),
                normalizeAddressLine2(addressLine2)
        );
    }

    private String normalizePhoneNumber(String value) {
        String raw = requiredText(value, 30, "전화번호", "phoneNumber");
        if (!raw.matches("[0-9\\s()\\-]+")) {
            throw validationError("phoneNumber", "INVALID_FORMAT", "전화번호는 숫자와 하이픈만 입력해 주세요.");
        }
        String digits = raw.replaceAll("\\D", "");
        if (!digits.startsWith("0") || (digits.length() != 10 && digits.length() != 11)) {
            throw validationError("phoneNumber", "INVALID_FORMAT", "전화번호는 지역번호를 포함해 10~11자리 숫자로 입력해 주세요.");
        }
        if (digits.length() == 11) {
            return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
        }
        if (digits.startsWith("02")) {
            return digits.substring(0, 2) + "-" + digits.substring(2, 6) + "-" + digits.substring(6);
        }
        return digits.substring(0, 3) + "-" + digits.substring(3, 6) + "-" + digits.substring(6);
    }

    private String normalizePostalCode(String value) {
        String digits = requiredText(value, 20, "우편번호", "postalCode").replaceAll("\\s", "");
        if (!digits.matches("\\d{5}")) {
            throw validationError("postalCode", "INVALID_FORMAT", "우편번호는 5자리 숫자로 입력해 주세요.");
        }
        return digits;
    }

    private String normalizeAddressLine1(String value) {
        String normalized = normalizeSpaces(requiredText(value, 255, "주소", "addressLine1"));
        if (normalized.length() < 5 || !normalized.matches(".*[가-힣].*")) {
            throw validationError("addressLine1", "INVALID_FORMAT", "주소는 시/군/구와 도로명 또는 지번을 포함해 입력해 주세요.");
        }
        return normalized;
    }

    private String normalizeAddressLine2(String value) {
        return normalizeSpaces(requiredText(value, 255, "상세주소", "addressLine2"));
    }

    private ApiException validationError(String field, String reason, String message) {
        if (field == null || field.isBlank()) {
            return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
        }
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                message,
                Map.of("field", field, "reason", reason, "message", message)
        );
    }

    private String normalizeSpaces(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    private boolean requiresContact(Map<String, Object> user) {
        if (user == null || !"USER".equals(DbValueMapper.string(user, "role"))) {
            return false;
        }
        return isBlank(user, "phone_number")
                || isBlank(user, "postal_code")
                || isBlank(user, "address_line1")
                || isBlank(user, "address_line2");
    }

    private ApiException contactRequired(Map<String, Object> user) {
        return new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Google 계정의 연락처와 주소 입력이 필요합니다.",
                Map.of(
                        "reason", "CONTACT_REQUIRED",
                        "email", DbValueMapper.string(user, "email"),
                        "name", DbValueMapper.string(user, "name")
                )
        );
    }

    private boolean isBlank(Map<String, Object> row, String key) {
        String value = DbValueMapper.string(row, key);
        return value == null || value.isBlank();
    }

    private boolean hasAnyText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private List<String> authProviders(Map<String, Object> row) {
        List<String> providers = new ArrayList<>();
        String passwordHash = DbValueMapper.string(row, "password_hash");
        if (passwordHash != null && !passwordHash.isBlank()) {
            providers.add("LOCAL");
        }
        Object preloadedProviders = row.get("auth_providers");
        if (preloadedProviders != null) {
            addProviderList(providers, preloadedProviders.toString());
            return providers;
        }
        Long internalId = longValue(row, "internal_id");
        if (internalId == null) {
            return providers;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT provider
                FROM user_auth_providers
                WHERE user_id = ?
                ORDER BY provider
                """, internalId);
        if (rows != null) {
            for (Map<String, Object> providerRow : rows) {
                String provider = DbValueMapper.string(providerRow, "provider");
                if (provider != null && !provider.isBlank() && !providers.contains(provider)) {
                    providers.add(provider);
                }
            }
        }
        return providers;
    }

    private void addProviderList(List<String> providers, String providerList) {
        if (providerList == null || providerList.isBlank()) {
            return;
        }
        String[] values = providerList.split(",");
        for (String value : values) {
            String provider = value == null ? "" : value.trim();
            if (!provider.isBlank() && !providers.contains(provider)) {
                providers.add(provider);
            }
        }
    }

    private boolean hasGoogleProvider(Long internalId, String providerUserId) {
        if (internalId == null || providerUserId == null || providerUserId.isBlank()) {
            return false;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id
                FROM user_auth_providers
                WHERE user_id = ?
                  AND provider = 'GOOGLE'
                  AND provider_user_id = ?
                """, internalId, providerUserId);
        return rows != null && !rows.isEmpty();
    }

    private boolean profileVerificationRequested(String redirectPath) {
        return redirectPath != null
                && (redirectPath.equals("/my/profile") || redirectPath.startsWith("/my/profile?"));
    }

    private Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private record ContactAddress(String phoneNumber, String postalCode, String addressLine1, String addressLine2) {
    }
}
