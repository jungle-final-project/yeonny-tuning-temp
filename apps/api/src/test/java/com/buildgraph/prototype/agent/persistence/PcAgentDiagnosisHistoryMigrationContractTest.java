package com.buildgraph.prototype.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PcAgentDiagnosisHistoryMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V128__pc_agent_diagnosis_history.sql"
    );

    @Test
    void migrationPersistsRequestStateEventsAndIdempotentResults() throws Exception {
        String sql = Files.readString(MIGRATION).replaceAll("\\s+", " ").trim();

        assertThat(sql)
                .contains("ALTER TABLE pc_agent_diagnosis_requests")
                .contains("request_status VARCHAR(30) NOT NULL DEFAULT 'REQUESTED'")
                .contains("connection_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'")
                .contains("CREATE TABLE IF NOT EXISTS pc_agent_diagnosis_events")
                .contains("REFERENCES pc_agent_diagnosis_requests(diagnosis_id) ON DELETE CASCADE")
                .contains("CONSTRAINT uq_pc_agent_diagnosis_events_event_id UNIQUE (event_id)")
                .contains("raw_payload JSONB NOT NULL")
                .contains("CREATE TABLE IF NOT EXISTS pc_agent_diagnosis_results")
                .contains("CONSTRAINT uq_pc_agent_diagnosis_results_diagnosis_id UNIQUE (diagnosis_id)")
                .contains("CONSTRAINT uq_pc_agent_diagnosis_results_result_id UNIQUE (result_id)")
                .contains("data_mode VARCHAR(10) NOT NULL CHECK (data_mode IN ('LIVE', 'DEMO'))")
                .contains("data_mode <> 'DEMO' OR scenario_id IS NOT NULL");
    }
}
