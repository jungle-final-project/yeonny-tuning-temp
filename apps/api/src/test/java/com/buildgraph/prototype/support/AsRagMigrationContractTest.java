package com.buildgraph.prototype.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AsRagMigrationContractTest {
    private static final Path V102_MIGRATION = Path.of("src/main/resources/db/migration/V102__as_page_rag_separate_contract.sql");
    private static final Path V103_MIGRATION = Path.of("src/main/resources/db/migration/V103__as_rag_final_visit_policy_alignment.sql");
    private static final Path V104_MIGRATION = Path.of("src/main/resources/db/migration/V104__as_rag_remote_catalog_coverage.sql");
    private static final Path V105_MIGRATION = Path.of("src/main/resources/db/migration/V105__as_rag_real_world_signal_coverage.sql");
    private static final Path V106_MIGRATION = Path.of("src/main/resources/db/migration/V106__as_rag_expanded_realistic_signal_corpus.sql");

    @Test
    void migrationCreatesSeparateAsRagEvidenceTable() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .contains("CREATE TABLE IF NOT EXISTS as_rag_evidence")
                .contains("ADD COLUMN IF NOT EXISTS as_rag_analysis JSONB")
                .contains("as-rag-remote-driver-os")
                .contains("as-rag-visit-disk-failure")
                .contains("as-rag-diagnosis-unclear");
    }

    @Test
    void migrationDoesNotWriteToExistingChatbotRagTable() throws Exception {
        String sql = normalizedSql();

        assertThat(sql)
                .doesNotContain("INSERT INTO rag_evidence")
                .doesNotContain("ALTER TABLE rag_evidence")
                .doesNotContain("UPDATE rag_evidence");
    }

    @Test
    void v103AlignsVisitPolicyForAlreadyMigratedDatabases() throws Exception {
        String sql = normalizedSql(V103_MIGRATION);

        assertThat(sql)
                .contains("source_id = 'as-rag-visit-disk-failure'")
                .contains("support_decision = 'VISIT_REQUIRED'")
                .contains("STORAGE_REPLACEMENT_SUSPECTED")
                .contains("source_id = 'as-rag-unsupported-scope'")
                .contains("support_decision = 'NEEDS_MORE_INFO'")
                .contains("as-rag-visit-boot-remote-blocked")
                .contains("as-rag-visit-fan-thermal");
    }

    @Test
    void v104AddsMissingRemoteCatalogCoverage() throws Exception {
        String sql = normalizedSql(V104_MIGRATION);

        assertThat(sql)
                .contains("as-rag-remote-agent")
                .contains("REMOTE_AGENT")
                .contains("AGENT_INSTALL_OR_UPLOAD_FAILURE")
                .contains("as-rag-remote-startup-service")
                .contains("REMOTE_STARTUP_SERVICE")
                .contains("BACKGROUND_SERVICE_PRESSURE")
                .contains("source_id = 'as-rag-remote-driver-os'")
                .contains("display driver reset");
    }

    @Test
    void v105AddsRealWorldWindowsAndAgentSignals() throws Exception {
        String sql = normalizedSql(V105_MIGRATION);

        assertThat(sql)
                .contains("display driver stopped responding")
                .contains("wudfrd failed to load")
                .contains("faulting application")
                .contains("name resolution timed out")
                .contains("service control manager")
                .contains("whea logger")
                .contains("previous system shutdown was unexpected")
                .contains("fps drops")
                .contains("water damage");
    }

    @Test
    void v106ExpandsRealisticWindowsEventAndAgentSignalCoverage() throws Exception {
        String sql = normalizedSql(V106_MIGRATION);

        assertThat(sql)
                .contains("jsonb_build_object")
                .contains("amdwddmg")
                .contains("igdkmdn64")
                .contains("windowsupdateclient")
                .contains("application hang")
                .contains("msiinstaller")
                .contains("resource-exhaustion-detector")
                .contains("dhcp-client")
                .contains("service failed to start")
                .contains("reset to device")
                .contains("fatal hardware error")
                .contains("rebooted without cleanly shutting down")
                .contains("processor speed is being limited")
                .contains("automatic repair")
                .contains("frame time");
    }

    private static String normalizedSql() throws Exception {
        return normalizedSql(V102_MIGRATION);
    }

    private static String normalizedSql(Path migration) throws Exception {
        return Files.readString(migration)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
