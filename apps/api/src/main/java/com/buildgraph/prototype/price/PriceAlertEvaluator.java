package com.buildgraph.prototype.price;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceAlertEvaluator {
    private final JdbcTemplate jdbcTemplate;
    private final PriceAlertMailService mailService;

    public PriceAlertEvaluator(JdbcTemplate jdbcTemplate, PriceAlertMailService mailService) {
        this.jdbcTemplate = jdbcTemplate;
        this.mailService = mailService;
    }

    /** Evaluates active alerts against saved current part prices and triggers email once. */
    @Transactional
    public Map<String, Object> evaluateActiveAlerts() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT pa.id,
                       u.email,
                       p.name AS part_name,
                       pa.target_price,
                       p.price AS current_price
                FROM price_alerts pa
                JOIN users u ON u.id = pa.user_id
                JOIN parts p ON p.id = pa.part_id
                WHERE pa.status = 'ACTIVE'
                  AND pa.deleted_at IS NULL
                  AND p.deleted_at IS NULL
                  AND p.price <= pa.target_price
                ORDER BY pa.created_at ASC, pa.id ASC
                """);
        int attempted = 0;
        int triggered = 0;
        int failed = 0;
        for (Map<String, Object> row : rows) {
            attempted += 1;
            boolean sent = mailService.sendTriggeredAlert(
                    DbValueMapper.string(row, "email"),
                    DbValueMapper.string(row, "part_name"),
                    DbValueMapper.integer(row, "target_price"),
                    DbValueMapper.integer(row, "current_price")
            );
            if (!sent) {
                failed += 1;
                continue;
            }
            int updated = jdbcTemplate.update("""
                    UPDATE price_alerts
                    SET status = 'TRIGGERED',
                        triggered_at = now(),
                        updated_at = now()
                    WHERE id = ?
                      AND status = 'ACTIVE'
                      AND deleted_at IS NULL
                    """, row.get("id"));
            if (updated == 1) {
                triggered += 1;
            }
        }
        return MockData.map(
                "attempted", attempted,
                "triggered", triggered,
                "failed", failed
        );
    }
}
