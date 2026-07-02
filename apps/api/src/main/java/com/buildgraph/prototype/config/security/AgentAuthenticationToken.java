package com.buildgraph.prototype.config.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class AgentAuthenticationToken extends AbstractAuthenticationToken {
    private final AgentPrincipal principal;

    private AgentAuthenticationToken(AgentPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
        this.principal = principal;
        setAuthenticated(true);
    }

    static AgentAuthenticationToken authenticated(AgentPrincipal principal) {
        return new AgentAuthenticationToken(principal);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public AgentPrincipal getPrincipal() {
        return principal;
    }
}
