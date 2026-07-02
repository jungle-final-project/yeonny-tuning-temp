package com.buildgraph.prototype.config.security;

public record AgentPrincipal(
        Long deviceInternalId,
        String deviceId,
        Long userInternalId,
        String status
) {
}
