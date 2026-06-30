package com.buildgraph.prototype.price;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PriceQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final PriceJobService priceJobService;

    public PriceQueryService(JdbcTemplate jdbcTemplate, PriceJobService priceJobService) {
        this.jdbcTemplate = jdbcTemplate;
        this.priceJobService = priceJobService;
    }

    public Map<String, Object> alerts() {
        List<Map<String, Object>> items = alertRows();
        return MockData.map("items", items, "page", 0, "size", 20, "total", items.size());
    }

    public Map<String, Object> createAlert(Map<String, Object> request) {
        String partId = request == null ? defaultGpuPartId() : String.valueOf(request.getOrDefault("partId", defaultGpuPartId()));
        Integer targetPrice = numberValue(request == null ? null : request.get("targetPrice"), 850_000);
        List<Map<String, Object>> existing = alertRows(partId, targetPrice);
        if (!existing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "동일한 활성 가격 알림이 이미 있습니다.");
        }
        jdbcTemplate.update("""
                INSERT INTO price_alerts (user_id, part_id, target_price, status)
                VALUES (
                  (SELECT id FROM users WHERE email = 'user@example.com'),
                  (SELECT id FROM parts WHERE public_id = ?::uuid),
                  ?,
                  'ACTIVE'
                )
                """, partId, targetPrice);
        return alertRows(partId, targetPrice).get(0);
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
                .map(PriceJobService::priceJobMap)
                .toList();
        return MockData.map("items", items, "page", 0, "size", 20, "total", items.size());
    }

    public Map<String, Object> runPriceJob() {
        return priceJobService.runPriceJob();
    }

    private List<Map<String, Object>> alertRows() {
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
                        ORDER BY pa.created_at DESC, pa.id DESC
                        """)
                .stream()
                .map(this::alertMap)
                .toList();
    }

    private List<Map<String, Object>> alertRows(String partId, Integer targetPrice) {
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
                          AND pa.status = 'ACTIVE'
                          AND p.public_id = ?::uuid
                          AND pa.target_price = ?
                        ORDER BY pa.created_at DESC, pa.id DESC
                        """, partId, targetPrice)
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
