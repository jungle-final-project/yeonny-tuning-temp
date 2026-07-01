package com.buildgraph.prototype.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentAsMigrationContractTest {
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V53__pc_agent_gold_mode_contract.sql");

    @Test
    void migrationCreatesGoldModeAgentTablesInParentChildOrder() throws Exception {
        String sql = normalizedSql();
        List<String> orderedFragments = List.of(
                "CREATE TABLE agent_activation_tokens",
                "CREATE TABLE agent_devices",
                "CREATE TABLE agent_consents",
                "CREATE TABLE agent_heartbeats",
                "CREATE TABLE agent_update_policies",
                "CREATE TABLE agent_update_rollouts",
                "CREATE TABLE agent_upload_jobs",
                "CREATE TABLE agent_log_bundles",
                "ALTER TABLE agent_log_uploads",
                "ALTER TABLE as_tickets",
                "CREATE TABLE remote_support_sessions",
                "CREATE TABLE visit_support_reservations"
        );

        assertThat(sql).contains(orderedFragments);
        int previous = -1;
        for (String fragment : orderedFragments) {
            int current = sql.indexOf(fragment);
            assertThat(current)
                    .as("migration fragment order: %s", fragment)
                    .isGreaterThan(previous);
            previous = current;
        }
    }

    @Test
    void migrationAddsOnlyDbSchemaGoldModeColumnsToExistingTables() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("ADD COLUMN device_id BIGINT REFERENCES agent_devices(id)")
                .contains("ADD COLUMN upload_job_id BIGINT REFERENCES agent_upload_jobs(id)")
                .contains("ADD COLUMN analysis_status VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED'")
                .contains("ADD COLUMN review_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED'")
                .contains("ADD COLUMN support_decision VARCHAR(50)")
                .contains("ADD COLUMN risk_level VARCHAR(30)")
                .contains("ADD COLUMN auto_response_allowed BOOLEAN NOT NULL DEFAULT false");
    }

    @Test
    void migrationKeepsAsTicketLifecycleStatusSeparateFromAnalysisAndReviewEnums() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("chk_as_tickets_analysis_status")
                .contains("'NOT_STARTED', 'QUEUED', 'ANALYZING', 'RULE_READY', 'LLM_READY', 'FAILED'")
                .contains("chk_as_tickets_review_status")
                .contains("'NOT_REQUIRED', 'REQUIRED', 'IN_REVIEW', 'APPROVED', 'REJECTED'")
                .contains("chk_as_tickets_support_decision")
                .contains("'SELF_SOLVABLE', 'REMOTE_POSSIBLE', 'VISIT_REQUIRED', 'NEEDS_MORE_INFO'")
                .contains("chk_as_tickets_risk_level")
                .contains("'LOW', 'MEDIUM', 'HIGH'");
    }

    @Test
    void migrationDoesNotCreateOutOfScopeQuickAssistOrSecurityTables() throws Exception {
        String sql = normalizedSql().toLowerCase();

        assertThat(sql)
                .doesNotContain("quick_assist")
                .doesNotContain("agent_access_tokens")
                .doesNotContain("idempotency_keys");
    }

    private static String normalizedSql() throws Exception {
        return Files.readString(MIGRATION)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
