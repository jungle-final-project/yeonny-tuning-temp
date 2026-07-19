package com.buildgraph.prototype.agent.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PcAgentDiagnosisResultRepository extends JpaRepository<PcAgentDiagnosisResultEntity, Long> {
    Optional<PcAgentDiagnosisResultEntity> findByDiagnosisId(UUID diagnosisId);

    Optional<PcAgentDiagnosisResultEntity> findByResultId(String resultId);

    @Modifying
    @Query(value = """
            INSERT INTO pc_agent_diagnosis_results (
              diagnosis_id, result_id, diagnosis_type, severity, title, summary,
              resolution_type, can_auto_recover, evidence, findings, actions,
              data_mode, scenario_id, raw_payload
            )
            VALUES (
              :diagnosisId, :resultId, :diagnosisType, :severity, :title, :summary,
              :resolutionType, :canAutoRecover, CAST(:evidence AS jsonb),
              CAST(:findings AS jsonb), CAST(:actions AS jsonb), :dataMode,
              :scenarioId, CAST(:rawPayload AS jsonb)
            )
            ON CONFLICT (diagnosis_id) DO UPDATE SET
              result_id = EXCLUDED.result_id,
              diagnosis_type = EXCLUDED.diagnosis_type,
              severity = EXCLUDED.severity,
              title = EXCLUDED.title,
              summary = EXCLUDED.summary,
              resolution_type = EXCLUDED.resolution_type,
              can_auto_recover = EXCLUDED.can_auto_recover,
              evidence = EXCLUDED.evidence,
              findings = EXCLUDED.findings,
              actions = EXCLUDED.actions,
              data_mode = EXCLUDED.data_mode,
              scenario_id = EXCLUDED.scenario_id,
              raw_payload = EXCLUDED.raw_payload,
              updated_at = now()
            """, nativeQuery = true)
    int upsert(
            @Param("diagnosisId") UUID diagnosisId,
            @Param("resultId") String resultId,
            @Param("diagnosisType") String diagnosisType,
            @Param("severity") String severity,
            @Param("title") String title,
            @Param("summary") String summary,
            @Param("resolutionType") String resolutionType,
            @Param("canAutoRecover") boolean canAutoRecover,
            @Param("evidence") String evidence,
            @Param("findings") String findings,
            @Param("actions") String actions,
            @Param("dataMode") String dataMode,
            @Param("scenarioId") String scenarioId,
            @Param("rawPayload") String rawPayload
    );
}
