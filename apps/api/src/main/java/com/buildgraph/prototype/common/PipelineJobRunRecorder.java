package com.buildgraph.prototype.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 스케줄 파이프라인 잡의 실행 이력을 pipeline_job_runs에 남기는 공용 기록기.
 *
 * 스케줄러가 결과 맵을 로그로만 남겨 실패가 관리자에게 보이지 않던 것(감사 O4)을,
 * 실행마다 상태·결과 요약·소요시간을 DB에 남겨 관리자 UI에서 조회 가능하게 한다.
 * 기록 실패가 잡 자체를 실패시키지 않도록 기록 오류는 삼키고 로그만 남긴다.
 */
@Component
public class PipelineJobRunRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger(PipelineJobRunRecorder.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public PipelineJobRunRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 잡 본문을 감싸 성공/실패/소요시간을 기록한다. 예외는 기록 후 전파하지 않고 로그로 종결(스케줄 잡 관례 유지).
     *
     * Postgres advisory lock으로 잡 이름 단위 상호배제를 건다(감사 O7): API를 다중 인스턴스로
     * 늘려도 같은 크론이 중복 실행되지 않는다(외부 사이트 2배 두드림·중복 수집 방지).
     * 세션 락은 같은 커넥션에서 해제해야 하므로 ConnectionCallback 안에서 잡 전체를 수행한다.
     * (주의: 잡이 도는 동안 커넥션 1개를 점유한다 — 장시간 잡 5종 동시여도 풀 여유 내)
     */
    public void run(String jobName, Supplier<Map<String, Object>> body) {
        run(jobName, "SCHEDULED", body);
    }

    /** triggerType으로 실행 계기를 구분한다(예: 'SCHEDULED' 크론, 'DRIFT_TRIGGERED' 드리프트발 재훈련). */
    public void run(String jobName, String triggerType, Supplier<Map<String, Object>> body) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        long startNanos = System.nanoTime();
        long lockKey = advisoryLockKey(jobName);
        jdbcTemplate.execute((java.sql.Connection connection) -> {
            boolean locked = false;
            try (java.sql.PreparedStatement tryLock = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                tryLock.setLong(1, lockKey);
                try (java.sql.ResultSet resultSet = tryLock.executeQuery()) {
                    locked = resultSet.next() && resultSet.getBoolean(1);
                }
            }
            if (!locked) {
                insert(jobName, triggerType, "SKIPPED_LOCKED", null, "다른 인스턴스가 같은 잡을 실행 중이라 건너뛰었습니다.", startedAt, elapsedMs(startNanos));
                LOGGER.info("Pipeline job {} skipped: advisory lock held by another instance", jobName);
                return null;
            }
            try {
                Map<String, Object> result = body.get();
                insert(jobName, triggerType, "SUCCEEDED", result, null, startedAt, elapsedMs(startNanos));
            } catch (RuntimeException exception) {
                insert(jobName, triggerType, "FAILED", null, limited(exception.getMessage()), startedAt, elapsedMs(startNanos));
                LOGGER.warn("Pipeline job {} failed: {}", jobName, exception.getMessage());
            } finally {
                try (java.sql.PreparedStatement unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, lockKey);
                    unlock.execute();
                }
            }
            return null;
        });
    }

    // 잡 이름 → 64bit advisory lock 키. 고정 네임스페이스(상위 32bit)로 다른 용도의 advisory lock과 충돌을 피한다.
    private static long advisoryLockKey(String jobName) {
        return (0x42474A4CL << 32) | (jobName.hashCode() & 0xFFFFFFFFL); // "BGJL"
    }

    /** 데모 동결 등으로 실행을 건너뛴 사실도 이력에 남긴다(침묵 스킵 방지). */
    public void recordSkippedFrozen(String jobName) {
        recordSkippedFrozen(jobName, "SCHEDULED");
    }

    public void recordSkippedFrozen(String jobName, String triggerType) {
        OffsetDateTime now = OffsetDateTime.now();
        insert(jobName, triggerType, "SKIPPED_FROZEN", null, "데모 동결(DEMO_FREEZE_MUTATIONS)로 실행을 건너뛰었습니다.", now, 0L);
    }

    public Map<String, Object> listRecent(Integer limit) {
        int safeLimit = Math.min(Math.max(limit == null ? 30 : limit, 1), 100);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT public_id::text AS id,
                       job_name,
                       trigger_type,
                       status,
                       result_summary,
                       error_summary,
                       started_at,
                       finished_at,
                       duration_ms
                FROM pipeline_job_runs
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, safeLimit);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(MockData.map(
                    "id", DbValueMapper.string(row, "id"),
                    "jobName", DbValueMapper.string(row, "job_name"),
                    "triggerType", DbValueMapper.string(row, "trigger_type"),
                    "status", DbValueMapper.string(row, "status"),
                    "resultSummary", DbValueMapper.json(row, "result_summary", null),
                    "errorSummary", DbValueMapper.string(row, "error_summary"),
                    "startedAt", DbValueMapper.timestamp(row, "started_at"),
                    "finishedAt", DbValueMapper.timestamp(row, "finished_at"),
                    "durationMs", row.get("duration_ms")
            ));
        }
        return MockData.map("items", items, "total", items.size());
    }

    private void insert(String jobName, String triggerType, String status, Map<String, Object> result, String errorSummary, OffsetDateTime startedAt, long durationMs) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO pipeline_job_runs (
                      job_name, trigger_type, status, result_summary, error_summary,
                      started_at, finished_at, duration_ms
                    )
                    VALUES (?, ?, ?, ?::jsonb, ?, ?, now(), ?)
                    """,
                    jobName,
                    triggerType == null ? "SCHEDULED" : triggerType,
                    status,
                    result == null ? null : OBJECT_MAPPER.writeValueAsString(result),
                    errorSummary,
                    startedAt,
                    durationMs
            );
        } catch (Exception recordError) {
            LOGGER.warn("Pipeline job run 기록 실패 (job={}): {}", jobName, recordError.getMessage());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String limited(String value) {
        if (value == null) {
            return "unknown error";
        }
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }
}
