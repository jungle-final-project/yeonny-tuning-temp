package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_update_policies")
public class AgentUpdatePolicyEntity extends PublicIdEntity {
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false)
    private AgentUpdateChannel channel;

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Column(name = "minimum_supported_version", nullable = false)
    private String minimumSupportedVersion;

    @Column(name = "latest_version", nullable = false)
    private String latestVersion;

    @Column(name = "installer_url")
    private String installerUrl;

    @Column(name = "sha256")
    private String sha256;

    @Column(name = "manifest_signature")
    private String manifestSignature;

    @Column(name = "kill_switch", nullable = false)
    private Boolean killSwitch;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected AgentUpdatePolicyEntity() {
    }
}
