package com.buildgraph.prototype.config.security;

import java.util.Optional;

public record AgentTokenAuthenticationResult(
        Status status,
        Optional<AgentPrincipal> principal,
        String message
) {
    public static AgentTokenAuthenticationResult authenticated(AgentPrincipal principal) {
        return new AgentTokenAuthenticationResult(Status.AUTHENTICATED, Optional.of(principal), null);
    }

    public static AgentTokenAuthenticationResult invalid() {
        return new AgentTokenAuthenticationResult(Status.INVALID, Optional.empty(), "Agent token is invalid.");
    }

    public static AgentTokenAuthenticationResult forbidden(String message) {
        return new AgentTokenAuthenticationResult(Status.FORBIDDEN, Optional.empty(), message);
    }

    public enum Status {
        AUTHENTICATED,
        INVALID,
        FORBIDDEN
    }
}
