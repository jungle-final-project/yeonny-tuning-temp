package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "remote_support_sessions")
public class RemoteSupportSessionEntity extends PublicIdEntity {
    @Column(name = "as_ticket_id", nullable = false)
    private Long asTicketId;

    @Column(name = "device_id")
    private Long deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private RemoteSupportProvider provider;

    @Column(name = "session_url")
    private String sessionUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RemoteSupportStatus status;

    @Column(name = "requested_by_admin_id")
    private Long requestedByAdminId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected RemoteSupportSessionEntity() {
    }
}
