package com.buildgraph.prototype.admin;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminQueryService {
    private final JdbcTemplate jdbcTemplate;

    public AdminQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> dashboard() {
        Integer agentRunning = jdbcTemplate.queryForObject("""
                SELECT count(*)::int
                FROM agent_sessions
                WHERE status IN ('QUEUED', 'RUNNING', 'RAG_SEARCHED', 'TOOLS_CALLED', 'SUMMARY_READY', 'FALLBACK_READY')
                """, Integer.class);
        Integer openTickets = jdbcTemplate.queryForObject("""
                SELECT count(*)::int
                FROM as_tickets
                WHERE status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS')
                  AND deleted_at IS NULL
                """, Integer.class);
        Integer priceJobsRunning = jdbcTemplate.queryForObject("""
                SELECT count(*)::int
                FROM price_jobs
                WHERE status IN ('QUEUED', 'RUNNING')
                  AND deleted_at IS NULL
                """, Integer.class);
        Map<String, Object> revenue = paidRevenue();
        return MockData.map(
                "agentRunning", agentRunning == null ? 0 : agentRunning,
                "openTickets", openTickets == null ? 0 : openTickets,
                "priceJobsRunning", priceJobsRunning == null ? 0 : priceJobsRunning,
                "todayRevenue", longValue(revenue, "today_revenue"),
                "weekRevenue", longValue(revenue, "week_revenue"),
                "previousWeekRevenue", longValue(revenue, "previous_week_revenue"),
                "revenueTrend", revenueTrend(),
                "orderStatus", orderStatus(),
                "asStatus", asStatus(),
                "degraded", false,
                "generatedAt", MockData.now()
        );
    }

    private Map<String, Object> paidRevenue() {
        return jdbcTemplate.queryForMap("""
                SELECT
                    COALESCE(SUM(
                        CASE
                            WHEN paid_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul'
                            THEN CASE WHEN paid_amount > 0 THEN paid_amount ELSE amount END
                            ELSE 0
                        END
                    ), 0)::bigint AS today_revenue,
                    COALESCE(SUM(
                        CASE
                            WHEN paid_at >= date_trunc('week', now() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul'
                            THEN CASE WHEN paid_amount > 0 THEN paid_amount ELSE amount END
                            ELSE 0
                        END
                    ), 0)::bigint AS week_revenue,
                    COALESCE(SUM(
                        CASE
                            WHEN paid_at >= (date_trunc('week', now() AT TIME ZONE 'Asia/Seoul') - interval '1 week') AT TIME ZONE 'Asia/Seoul'
                             AND paid_at < date_trunc('week', now() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul'
                            THEN CASE WHEN paid_amount > 0 THEN paid_amount ELSE amount END
                            ELSE 0
                        END
                    ), 0)::bigint AS previous_week_revenue
                FROM assembly_payments
                WHERE status = 'PAID'
                """);
    }

    private List<Map<String, Object>> revenueTrend() {
        return jdbcTemplate.queryForList("""
                        WITH days AS (
                            SELECT generate_series(
                                ((now() AT TIME ZONE 'Asia/Seoul')::date - 6),
                                (now() AT TIME ZONE 'Asia/Seoul')::date,
                                interval '1 day'
                            )::date AS day
                        )
                        SELECT days.day::text AS date,
                               to_char(days.day, 'MM/DD') AS label,
                               COALESCE(SUM(CASE WHEN ap.paid_amount > 0 THEN ap.paid_amount ELSE ap.amount END), 0)::bigint AS revenue
                        FROM days
                        LEFT JOIN assembly_payments ap
                          ON ap.status = 'PAID'
                         AND (ap.paid_at AT TIME ZONE 'Asia/Seoul')::date = days.day
                        GROUP BY days.day
                        ORDER BY days.day
                        """)
                .stream()
                .map(row -> MockData.map(
                        "date", DbValueMapper.string(row, "date"),
                        "label", DbValueMapper.string(row, "label"),
                        "revenue", longValue(row, "revenue")
                ))
                .toList();
    }

    private List<Map<String, Object>> orderStatus() {
        Map<String, Object> counts = jdbcTemplate.queryForMap("""
                SELECT
                    count(*) FILTER (WHERE status IN ('REQUESTED', 'OFFERED'))::int AS pending,
                    count(*) FILTER (WHERE status IN ('MATCHED', 'CONFIRMED', 'ASSEMBLING', 'SHIPPED'))::int AS in_progress,
                    count(*) FILTER (WHERE status = 'COMPLETED')::int AS completed,
                    count(*) FILTER (WHERE status = 'CANCELLED')::int AS cancelled
                FROM assembly_requests
                """);
        return List.of(
                MockData.map("status", "PENDING", "label", "처리대기", "count", longValue(counts, "pending")),
                MockData.map("status", "IN_PROGRESS", "label", "진행중", "count", longValue(counts, "in_progress")),
                MockData.map("status", "COMPLETED", "label", "완료", "count", longValue(counts, "completed")),
                MockData.map("status", "CANCELLED", "label", "취소", "count", longValue(counts, "cancelled"))
        );
    }

    private List<Map<String, Object>> asStatus() {
        Map<String, Object> counts = jdbcTemplate.queryForMap("""
                SELECT
                    count(*) FILTER (WHERE status = 'OPEN')::int AS pending,
                    count(*) FILTER (WHERE status IN ('ASSIGNED', 'IN_PROGRESS'))::int AS in_progress,
                    count(*) FILTER (WHERE status IN ('RESOLVED', 'CLOSED'))::int AS completed,
                    count(*) FILTER (WHERE status = 'CANCELLED')::int AS cancelled
                FROM as_tickets
                WHERE deleted_at IS NULL
                """);
        return List.of(
                MockData.map("status", "PENDING", "label", "접수 대기", "count", longValue(counts, "pending")),
                MockData.map("status", "IN_PROGRESS", "label", "처리 중", "count", longValue(counts, "in_progress")),
                MockData.map("status", "COMPLETED", "label", "해결 완료", "count", longValue(counts, "completed")),
                MockData.map("status", "CANCELLED", "label", "취소", "count", longValue(counts, "cancelled"))
        );
    }

    private static long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : value == null ? 0L : Long.parseLong(value.toString());
    }

    public Map<String, Object> auditLogs() {
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                        SELECT action, target_type, target_id, metadata, created_at
                        FROM admin_audit_logs
                        ORDER BY created_at DESC, id DESC
                        LIMIT 20
                        """)
                .stream()
                .map(row -> MockData.map(
                        "action", DbValueMapper.string(row, "action"),
                        "targetType", DbValueMapper.string(row, "target_type"),
                        "targetId", DbValueMapper.string(row, "target_id"),
                        "metadata", DbValueMapper.json(row, "metadata", Map.of()),
                        "createdAt", DbValueMapper.timestamp(row, "created_at")
                ))
                .toList();
        return MockData.map("items", items);
    }
}
