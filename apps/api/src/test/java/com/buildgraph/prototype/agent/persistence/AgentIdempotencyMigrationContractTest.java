package com.buildgraph.prototype.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentIdempotencyMigrationContractTest {
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V57__agent_idempotency_records.sql");

    @Test
    void migrationCreatesAgentScopedIdempotencyRecordTable() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS agent_idempotency_records")
                .contains("agent_device_id BIGINT NOT NULL REFERENCES agent_devices(id)")
                .contains("idempotency_key VARCHAR(160) NOT NULL")
                .contains("request_method VARCHAR(10) NOT NULL")
                .contains("request_path VARCHAR(512) NOT NULL")
                .contains("request_hash CHAR(64) NOT NULL")
                .contains("response_status INTEGER")
                .contains("response_body TEXT")
                .contains("status VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS'")
                .contains("created_at TIMESTAMPTZ NOT NULL DEFAULT now()")
                .contains("updated_at TIMESTAMPTZ NOT NULL DEFAULT now()")
                .contains("completed_at TIMESTAMPTZ")
                .contains("expires_at TIMESTAMPTZ NOT NULL")
                .contains("CONSTRAINT uq_agent_idempotency_scope UNIQUE (agent_device_id, request_method, request_path, idempotency_key)");
    }

    @Test
    void migrationConstrainsRecordStatusToProcessingLifecycleOnly() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("chk_agent_idempotency_status")
                .contains("'IN_PROGRESS', 'COMPLETED'");
    }

    private static String normalizedSql() throws Exception {
        return Files.readString(MIGRATION)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
