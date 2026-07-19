package com.buildgraph.prototype.agent.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PcAgentDiagnosisEventRepository extends JpaRepository<PcAgentDiagnosisEventEntity, Long> {
    Optional<PcAgentDiagnosisEventEntity> findByEventId(String eventId);

    List<PcAgentDiagnosisEventEntity> findAllByDiagnosisIdOrderByOccurredAtAscIdAsc(UUID diagnosisId);

    @Modifying
    @Query(value = """
            INSERT INTO pc_agent_diagnosis_events (
              diagnosis_id, event_id, task_id, event_type, status, progress_percent,
              message, occurred_at, raw_payload
            )
            VALUES (
              :diagnosisId, :eventId, :taskId, :eventType, :status, :progressPercent,
              :message, :occurredAt, CAST(:rawPayload AS jsonb)
            )
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("diagnosisId") UUID diagnosisId,
            @Param("eventId") String eventId,
            @Param("taskId") String taskId,
            @Param("eventType") String eventType,
            @Param("status") String status,
            @Param("progressPercent") int progressPercent,
            @Param("message") String message,
            @Param("occurredAt") Instant occurredAt,
            @Param("rawPayload") String rawPayload
    );
}
