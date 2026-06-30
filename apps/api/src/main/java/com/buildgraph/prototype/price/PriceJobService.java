package com.buildgraph.prototype.price;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PriceJobService {
    private final JdbcTemplate jdbcTemplate;
    private final PriceJobWorker priceJobWorker;

    public PriceJobService(JdbcTemplate jdbcTemplate, PriceJobWorker priceJobWorker) {
        this.jdbcTemplate = jdbcTemplate;
        this.priceJobWorker = priceJobWorker;
    }

    /** Creates and immediately processes one admin price refresh job. */
    public Map<String, Object> runPriceJob() {
        Map<String, Object> job = createQueuedJob();
        priceJobWorker.runJob(numberLong(job.get("internal_id")));
        return priceJob(numberLong(job.get("internal_id")));
    }

    /** Creates a queued job while enforcing the single active job contract. */
    @Transactional
    protected Map<String, Object> createQueuedJob() {
        if (hasActiveJob()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 실행 중인 가격 Job이 있습니다.");
        }
        return jdbcTemplate.queryForMap("""
                INSERT INTO price_jobs (requested_by, status)
                VALUES ((SELECT id FROM users WHERE email = 'admin@example.com'), 'QUEUED')
                RETURNING id AS internal_id,
                          public_id::text AS id,
                          status,
                          (SELECT public_id::text FROM users WHERE email = 'admin@example.com') AS requested_by,
                          started_at,
                          finished_at,
                          error_summary,
                          created_at
                """);
    }

    /** Checks if another queued or running price job exists. */
    private boolean hasActiveJob() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM price_jobs
                WHERE status IN ('QUEUED', 'RUNNING')
                  AND deleted_at IS NULL
                """, Integer.class);
        return count != null && count > 0;
    }

    /** Loads a job DTO by internal id after worker processing. */
    private Map<String, Object> priceJob(long internalId) {
        return jdbcTemplate.queryForList("""
                        SELECT pj.public_id::text AS id,
                               pj.status,
                               u.public_id::text AS requested_by,
                               pj.started_at,
                               pj.finished_at,
                               pj.error_summary,
                               pj.created_at
                        FROM price_jobs pj
                        JOIN users u ON u.id = pj.requested_by
                        WHERE pj.id = ?
                        """, internalId)
                .stream()
                .findFirst()
                .map(PriceJobService::priceJobMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "가격 Job을 찾을 수 없습니다."));
    }

    /** Converts a price_jobs row into the admin DTO shape. */
    public static Map<String, Object> priceJobMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "status", DbValueMapper.string(row, "status"),
                "requestedBy", DbValueMapper.string(row, "requested_by"),
                "startedAt", DbValueMapper.timestamp(row, "started_at"),
                "finishedAt", DbValueMapper.timestamp(row, "finished_at"),
                "errorSummary", DbValueMapper.string(row, "error_summary"),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    /** Parses a long-like DB value. */
    private static Long numberLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }
}
