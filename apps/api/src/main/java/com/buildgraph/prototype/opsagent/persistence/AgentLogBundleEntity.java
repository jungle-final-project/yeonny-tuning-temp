package com.buildgraph.prototype.opsagent.persistence;

import com.buildgraph.prototype.quoteagent.chat.*;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.quoteagent.tools.*;
import com.buildgraph.prototype.opsagent.as.*;
import com.buildgraph.prototype.opsagent.profile.*;
import com.buildgraph.prototype.opsagent.trace.*;
import com.buildgraph.prototype.opsagent.runner.*;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "agent_log_bundles")
public class AgentLogBundleEntity extends PublicIdEntity {
    @Column(name = "upload_job_id", nullable = false)
    private Long uploadJobId;

    @Column(name = "log_upload_id")
    private Long logUploadId;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Column(name = "sha256", nullable = false, unique = true)
    private String sha256;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "delete_after", nullable = false)
    private Instant deleteAfter;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected AgentLogBundleEntity() {
    }
}
