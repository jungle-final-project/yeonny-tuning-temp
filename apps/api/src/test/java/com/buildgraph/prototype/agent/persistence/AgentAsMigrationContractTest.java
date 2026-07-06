package com.buildgraph.prototype.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentAsMigrationContractTest {
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V56__pc_agent_gold_mode_contract.sql");
    private static final Path LOG_SUMMARY_MIGRATION = Path.of("src/main/resources/db/migration/V100__pc_agent_log_summary_routing.sql");
    private static final Path FINAL_SCENARIO_MIGRATION = Path.of("src/main/resources/db/migration/V101__final_support_scenario_additive_contract.sql");

    @Test
    void migrationCreatesGoldModeAgentTablesInParentChildOrder() throws Exception {
        String sql = normalizedSql();
        List<String> orderedFragments = List.of(
                "CREATE TABLE IF NOT EXISTS agent_activation_tokens",
                "CREATE TABLE IF NOT EXISTS agent_devices",
                "CREATE TABLE IF NOT EXISTS agent_consents",
                "CREATE TABLE IF NOT EXISTS agent_heartbeats",
                "CREATE TABLE IF NOT EXISTS agent_update_policies",
                "CREATE TABLE IF NOT EXISTS agent_update_rollouts",
                "CREATE TABLE IF NOT EXISTS agent_upload_jobs",
                "CREATE TABLE IF NOT EXISTS agent_log_bundles",
                "ALTER TABLE agent_log_uploads",
                "ALTER TABLE as_tickets",
                "CREATE TABLE IF NOT EXISTS remote_support_sessions",
                "CREATE TABLE IF NOT EXISTS visit_support_reservations"
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
                .contains("ADD COLUMN IF NOT EXISTS device_id BIGINT REFERENCES agent_devices(id)")
                .contains("ADD COLUMN IF NOT EXISTS upload_job_id BIGINT REFERENCES agent_upload_jobs(id)")
                .contains("ADD COLUMN IF NOT EXISTS analysis_status VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED'")
                .contains("ADD COLUMN IF NOT EXISTS review_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED'")
                .contains("ADD COLUMN IF NOT EXISTS support_decision VARCHAR(50)")
                .contains("ADD COLUMN IF NOT EXISTS risk_level VARCHAR(30)")
                .contains("ADD COLUMN IF NOT EXISTS auto_response_allowed BOOLEAN NOT NULL DEFAULT false");
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

    @Test
    void logSummaryMigrationAddsLogSummaryRoutingAndExceptionApprovalFields() throws Exception {
        String sql = Files.readString(LOG_SUMMARY_MIGRATION)
                .replaceAll("\\s+", " ")
                .trim();

        assertThat(sql)
                .contains("ADD COLUMN IF NOT EXISTS incident_window JSONB")
                .contains("ADD COLUMN IF NOT EXISTS range_started_at TIMESTAMPTZ")
                .contains("ADD COLUMN IF NOT EXISTS range_ended_at TIMESTAMPTZ")
                .contains("ADD COLUMN IF NOT EXISTS ai_diagnosis_request JSONB")
                .contains("exception_approval_reason")
                .contains("exception_responsibility_scope")
                .contains("exception_user_message");
    }

    @Test
    void finalScenarioMigrationAddsConsentRemoteRequestAndSafetyFieldsAdditively() throws Exception {
        String sql = Files.readString(FINAL_SCENARIO_MIGRATION)
                .replaceAll("\\s+", " ")
                .trim();

        assertThat(sql)
                .contains("REMOTE_CONNECTION")
                .contains("REMOTE_FULL_CONTROL")
                .contains("HIGH_RISK_REMOTE_ACTION")
                .contains("ADD COLUMN IF NOT EXISTS requested_by_user_id BIGINT REFERENCES users(id)")
                .contains("ADD COLUMN IF NOT EXISTS request_reason TEXT")
                .contains("ADD COLUMN IF NOT EXISTS contact_phone_snapshot VARCHAR(50)")
                .contains("ADD COLUMN IF NOT EXISTS safety_advice_level VARCHAR(40)")
                .contains("ADD COLUMN IF NOT EXISTS safety_notices JSONB")
                .contains("ADD COLUMN IF NOT EXISTS feedback_rating INTEGER")
                .contains("ADD COLUMN IF NOT EXISTS diagnostic_accuracy VARCHAR(40)")
                .contains("chk_as_tickets_feedback_rating")
                .contains("chk_as_tickets_diagnostic_accuracy");
    }

    private static String normalizedSql() throws Exception {
        return Files.readString(MIGRATION)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
