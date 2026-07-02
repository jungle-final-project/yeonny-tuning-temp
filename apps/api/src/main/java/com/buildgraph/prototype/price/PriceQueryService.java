package com.buildgraph.prototype.price;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PriceQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final PriceJobPublisher priceJobPublisher;

    public PriceQueryService(JdbcTemplate jdbcTemplate, PriceJobPublisher priceJobPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.priceJobPublisher = priceJobPublisher;
    }

    public Map<String, Object> alerts(CurrentUserService.CurrentUser user) {
        List<Map<String, Object>> items = alertRows(user.internalId());
        return MockData.map("items", items, "page", 0, "size", 20, "total", items.size());
    }

    public Map<String, Object> createAlert(Map<String, Object> request, CurrentUserService.CurrentUser user) {
        String partId = request == null ? defaultGpuPartId() : String.valueOf(request.getOrDefault("partId", defaultGpuPartId()));
        Integer targetPrice = numberValue(request == null ? null : request.get("targetPrice"), 850_000);
        requireActivePart(partId);
        List<Map<String, Object>> existing = alertRows(user.internalId(), partId, targetPrice);
        if (!existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 같은 목표가 알림이 등록되어 있습니다.");
        }
        jdbcTemplate.update("""
                INSERT INTO price_alerts (user_id, part_id, target_price, status)
                VALUES (
                  ?,
                  (SELECT id FROM parts WHERE public_id = ?::uuid),
                  ?,
                  'ACTIVE'
                )
                """, user.internalId(), partId, targetPrice);
        return alertRows(user.internalId(), partId, targetPrice).get(0);
    }

    public Map<String, Object> priceJobs() {
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                        SELECT pj.public_id::text AS id,
                               pj.status,
                               u.public_id::text AS requested_by,
                               pj.started_at,
                               pj.finished_at,
                               pj.error_summary,
                               pj.created_at
                        FROM price_jobs pj
                        JOIN users u ON u.id = pj.requested_by
                        WHERE pj.deleted_at IS NULL
                        ORDER BY pj.created_at DESC, pj.id DESC
                        """)
                .stream()
                .map(this::priceJobMap)
                .toList();
        return MockData.map("items", items, "page", 0, "size", 20, "total", items.size());
    }

    public Map<String, Object> runPriceJob(CurrentUserService.CurrentUser admin) {
        List<Map<String, Object>> active = jdbcTemplate.queryForList("""
                SELECT pj.public_id::text AS id,
                       pj.status,
                       u.public_id::text AS requested_by,
                       pj.started_at,
                       pj.finished_at,
                       pj.error_summary,
                       pj.created_at
                FROM price_jobs pj
                JOIN users u ON u.id = pj.requested_by
                WHERE pj.status IN ('QUEUED', 'RUNNING')
                  AND pj.deleted_at IS NULL
                ORDER BY pj.created_at DESC
                LIMIT 1
                """);
        if (!active.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 실행 중인 가격 Job이 있습니다.");
        }
        Map<String, Object> row = priceJobMap(jdbcTemplate.queryForMap("""
                INSERT INTO price_jobs (requested_by, status)
                VALUES (?, 'QUEUED')
                RETURNING public_id::text AS id,
                          status,
                          (SELECT public_id::text FROM users WHERE id = ?) AS requested_by,
                          started_at,
                          finished_at,
                          error_summary,
                          created_at
                """, admin.internalId(), admin.internalId()));
        priceJobPublisher.publishRefresh(DbValueMapper.string(row, "id"));
        return row;
    }

    public void startPriceJob(String priceJobId) {
        int updated = jdbcTemplate.update("""
                UPDATE price_jobs
                SET status = 'RUNNING',
                    started_at = COALESCE(started_at, now()),
                    error_summary = NULL
                WHERE public_id = ?::uuid
                  AND status = 'QUEUED'
                  AND deleted_at IS NULL
                """, priceJobId);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "QUEUED 상태의 가격 Job만 실행할 수 있습니다.");
        }
    }

    public void completePriceJob(String priceJobId) {
        int updated = jdbcTemplate.update("""
                UPDATE price_jobs
                SET status = 'SUCCEEDED',
                    finished_at = now(),
                    error_summary = NULL
                WHERE public_id = ?::uuid
                  AND status = 'RUNNING'
                  AND deleted_at IS NULL
                """, priceJobId);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RUNNING 상태의 가격 Job만 완료할 수 있습니다.");
        }
    }

    public void failPriceJob(String priceJobId, String errorSummary) {
        jdbcTemplate.update("""
                UPDATE price_jobs
                SET status = 'FAILED',
                    finished_at = now(),
                    error_summary = ?
                WHERE public_id = ?::uuid
                  AND status IN ('QUEUED', 'RUNNING')
                  AND deleted_at IS NULL
                """, errorSummary, priceJobId);
    }

    private List<Map<String, Object>> alertRows(Long userId) {
        return jdbcTemplate.queryForList("""
                        SELECT pa.part_id,
                               p.public_id::text AS part_public_id,
                               p.name AS part_name,
                               pa.target_price,
                               p.price AS current_price,
                               pa.status,
                               pa.created_at
                        FROM price_alerts pa
                        JOIN parts p ON p.id = pa.part_id
                        WHERE pa.deleted_at IS NULL
                          AND pa.user_id = ?
                        ORDER BY pa.created_at DESC, pa.id DESC
                        """, userId)
                .stream()
                .map(this::alertMap)
                .toList();
    }

    private List<Map<String, Object>> alertRows(Long userId, String partId, Integer targetPrice) {
        return jdbcTemplate.queryForList("""
                        SELECT pa.part_id,
                               p.public_id::text AS part_public_id,
                               p.name AS part_name,
                               pa.target_price,
                               p.price AS current_price,
                               pa.status,
                               pa.created_at
                        FROM price_alerts pa
                        JOIN parts p ON p.id = pa.part_id
                        WHERE pa.deleted_at IS NULL
                          AND pa.user_id = ?
                          AND pa.status = 'ACTIVE'
                          AND p.public_id = ?::uuid
                          AND pa.target_price = ?
                        ORDER BY pa.created_at DESC, pa.id DESC
                        """, userId, partId, targetPrice)
                .stream()
                .map(this::alertMap)
                .toList();
    }

    private Map<String, Object> alertMap(Map<String, Object> row) {
        return MockData.map(
                "partId", DbValueMapper.string(row, "part_public_id"),
                "partName", DbValueMapper.string(row, "part_name"),
                "targetPrice", DbValueMapper.integer(row, "target_price"),
                "currentPrice", DbValueMapper.integer(row, "current_price"),
                "status", DbValueMapper.string(row, "status"),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    private Map<String, Object> priceJobMap(Map<String, Object> row) {
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

    private String defaultGpuPartId() {
        return jdbcTemplate.queryForObject("""
                SELECT public_id::text
                FROM parts
                WHERE category = 'GPU'
                  AND deleted_at IS NULL
                ORDER BY id
                LIMIT 1
                """, String.class);
    }

    private void requireActivePart(String partId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM parts
                WHERE public_id = ?::uuid
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                """, Integer.class, partId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "가격 알림 대상 부품을 찾을 수 없습니다.");
        }
    }

    private static Integer numberValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        return Integer.valueOf(value.toString());
    }
}
