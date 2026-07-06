package com.buildgraph.prototype.agent.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SupportFinalContractMigrationTest {
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V99__support_final_contracts.sql");

    @Test
    void migrationExtendsSupportDecisionConstraint() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("DROP CONSTRAINT IF EXISTS chk_as_tickets_support_decision")
                .contains("'SELF_SOLVABLE'")
                .contains("'REMOTE_POSSIBLE'")
                .contains("'VISIT_REQUIRED'")
                .contains("'REPAIR_OR_REPLACE'")
                .contains("'NEEDS_MORE_INFO'")
                .contains("'MONITOR_ONLY'")
                .contains("'UNSUPPORTED'");
    }

    @Test
    void migrationAddsFinalScenarioJsonContracts() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("ADD COLUMN IF NOT EXISTS incident_window JSONB")
                .contains("ADD COLUMN IF NOT EXISTS log_summary JSONB")
                .contains("ADD COLUMN IF NOT EXISTS support_routing JSONB");
    }

    private static String normalizedSql() throws Exception {
        return Files.readString(MIGRATION)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
