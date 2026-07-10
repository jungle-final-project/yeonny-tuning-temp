package com.buildgraph.prototype.user;

public record GoogleOAuthPendingLogin(
        String providerUserId,
        String email,
        String name,
        boolean emailVerified,
        String redirectPath
) {
}
