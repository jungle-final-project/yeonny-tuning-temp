package com.buildgraph.prototype.price;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DanawaBackupCollector {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SOURCE = "DANAWA_BACKUP";
    private final JdbcTemplate jdbcTemplate;

    public DanawaBackupCollector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Replays Danawa or previous saved prices as the price job fallback source. */
    @Transactional
    public Map<String, Object> collectBackupPrices() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT p.id,
                       p.public_id::text AS public_id,
                       p.price AS current_price,
                       latest.price AS latest_price,
                       latest.source AS latest_source,
                       danawa.price AS danawa_price
                FROM parts p
                LEFT JOIN LATERAL (
                  SELECT ps.price, ps.source
                  FROM price_snapshots ps
                  WHERE ps.part_id = p.id
                  ORDER BY ps.collected_at DESC, ps.id DESC
                  LIMIT 1
                ) latest ON true
                LEFT JOIN LATERAL (
                  SELECT ps.price
                  FROM price_snapshots ps
                  WHERE ps.part_id = p.id
                    AND ps.source = ?
                  ORDER BY ps.collected_at DESC, ps.id DESC
                  LIMIT 1
                ) danawa ON true
                WHERE p.deleted_at IS NULL
                  AND p.status = 'ACTIVE'
                ORDER BY p.category, p.id
                """, SOURCE);
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        for (Map<String, Object> row : rows) {
            Integer price = firstPrice(row.get("danawa_price"), row.get("latest_price"), row.get("current_price"));
            if (price == null || price < 0) {
                skipped += 1;
                continue;
            }
            try {
                saveBackupPrice(row, price);
                updated += 1;
            } catch (RuntimeException error) {
                failed += 1;
            }
        }
        return MockData.map(
                "source", SOURCE,
                "attempted", rows.size(),
                "updated", updated,
                "skipped", skipped,
                "failed", failed
        );
    }

    /** Stores the fallback price on parts and price_snapshots. */
    private void saveBackupPrice(Map<String, Object> row, int price) {
        long partId = ((Number) row.get("id")).longValue();
        jdbcTemplate.update("""
                UPDATE parts
                SET price = ?,
                    updated_at = now()
                WHERE id = ?
                  AND deleted_at IS NULL
                """, price, partId);
        jdbcTemplate.update("""
                INSERT INTO price_snapshots (
                  part_id,
                  price,
                  source,
                  collected_at,
                  raw_payload
                )
                VALUES (?, ?, ?, now(), ?::jsonb)
                """, partId, price, SOURCE, rawPayload(row, price));
    }

    /** Builds a compact JSON payload explaining the fallback basis. */
    private static String rawPayload(Map<String, Object> row, int price) {
        String latestSource = DbValueMapper.string(row, "latest_source");
        String basis = row.get("danawa_price") == null ? "PREVIOUS_PRICE_FALLBACK" : "DANAWA_BACKUP_REPLAY";
        return json(MockData.map(
                "source", SOURCE,
                "partId", row.get("public_id"),
                "price", price,
                "basis", basis,
                "latestSource", latestSource
        ));
    }

    /** Picks the first available integer price candidate. */
    private static Integer firstPrice(Object... values) {
        for (Object value : values) {
            if (value instanceof Number number) {
                return number.intValue();
            }
        }
        return null;
    }

    /** Serializes a raw payload map for JSONB storage. */
    private static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("JSON 직렬화에 실패했습니다.", error);
        }
    }
}
