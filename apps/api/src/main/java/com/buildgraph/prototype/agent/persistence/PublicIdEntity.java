package com.buildgraph.prototype.agent.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;

@MappedSuperclass
abstract class PublicIdEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, insertable = false, updatable = false)
    private UUID publicId;

    protected PublicIdEntity() {
    }

    Long getId() {
        return id;
    }

    UUID getPublicId() {
        return publicId;
    }
}
