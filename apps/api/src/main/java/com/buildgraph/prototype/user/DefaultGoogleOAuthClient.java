package com.buildgraph.prototype.user;

import com.buildgraph.prototype.common.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class DefaultGoogleOAuthClient implements GoogleOAuthClient {
    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final List<String> ALLOWED_ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");

    private final GoogleOAuthProperties properties;
    private final RestClient restClient;
    private final JwtDecoder jwtDecoder;

    @Autowired
    public DefaultGoogleOAuthClient(GoogleOAuthProperties properties) {
        this(properties, RestClient.create(), NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS_URL).build());
    }

    DefaultGoogleOAuthClient(GoogleOAuthProperties properties, RestClient restClient, JwtDecoder jwtDecoder) {
        this.properties = properties;
        this.restClient = restClient;
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public GoogleOAuthPendingLogin exchangeAuthorizationCode(String authorizationCode, String redirectUri, String redirectPath) {
        Map<String, Object> tokenResponse = requestTokens(authorizationCode, redirectUri);
        Object idToken = tokenResponse.get("id_token");
        if (!(idToken instanceof String rawIdToken) || rawIdToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Google did not return an ID token.");
        }
        Jwt jwt = decodeAndValidateIdToken(rawIdToken);
        String email = requiredClaim(jwt, "email");
        Boolean emailVerified = jwt.getClaim("email_verified");
        String providerUserId = requiredClaim(jwt, "sub");
        String name = optionalClaim(jwt, "name");
        return new GoogleOAuthPendingLogin(providerUserId, email, name == null || name.isBlank() ? email : name, Boolean.TRUE.equals(emailVerified), redirectPath);
    }

    private Map<String, Object> requestTokens(String authorizationCode, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", authorizationCode);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");
        try {
            Map<String, Object> body = restClient.post()
                    .uri(GOOGLE_TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (body == null) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Google authorization code could not be exchanged.");
            }
            return body;
        } catch (RestClientException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Google authorization code could not be exchanged.");
        }
    }

    private Jwt decodeAndValidateIdToken(String rawIdToken) {
        try {
            Jwt jwt = jwtDecoder.decode(rawIdToken);
            if (!ALLOWED_ISSUERS.contains(String.valueOf(jwt.getIssuer()))) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Google ID token issuer is invalid.");
            }
            if (!jwt.getAudience().contains(properties.clientId())) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Google ID token audience is invalid.");
            }
            Instant expiresAt = jwt.getExpiresAt();
            if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Google ID token has expired.");
            }
            if (!Boolean.TRUE.equals(jwt.getClaim("email_verified"))) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Google email is not verified.");
            }
            return jwt;
        } catch (JwtException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Google ID token is invalid.");
        }
    }

    private String requiredClaim(Jwt jwt, String claim) {
        String value = optionalClaim(jwt, claim);
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Google ID token is missing required claims.");
        }
        return value;
    }

    private String optionalClaim(Jwt jwt, String claim) {
        Object value = jwt.getClaim(claim);
        return value == null ? null : value.toString();
    }
}
