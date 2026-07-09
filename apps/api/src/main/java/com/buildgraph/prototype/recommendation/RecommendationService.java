package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {
    private final JdbcTemplate jdbcTemplate;

    public RecommendationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /* 새롭게 추가됨: 홈 추천 조회 */
    public Map<String, Object> homeParts(CurrentUserService.CurrentUser user, Integer limit) {
        int safeLimit = limit == null ? 4 : Math.min(Math.max(limit, 1), 12);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.status,
                               p.attributes,
                               b.summary AS benchmark_summary,
                               b.score AS benchmark_score,
                               peo.title AS external_offer_title,
                               peo.image_url AS external_offer_image_url,
                               peo.supplier_name AS external_offer_supplier_name,
                               peo.offer_url AS external_offer_url,
                               peo.low_price AS external_offer_low_price,
                               peo.source AS external_offer_source,
                               peo.refreshed_at AS external_offer_refreshed_at
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT summary, score
                          FROM benchmark_summaries bs
                          WHERE bs.part_id = p.id
                            AND bs.deleted_at IS NULL
                          ORDER BY bs.created_at DESC, bs.id DESC
                          LIMIT 1
                        ) b ON true
                        LEFT JOIN part_external_offers peo
                          ON peo.part_id = p.id
                         AND peo.source = 'NAVER_SHOPPING_SEARCH'
                         AND peo.deleted_at IS NULL
                        WHERE p.status = 'ACTIVE'
                          AND p.deleted_at IS NULL
                        ORDER BY coalesce(b.score, 0) DESC, p.price DESC, p.id ASC
                        LIMIT ?
                        """, safeLimit);
        return MockData.map(
                "items", indexedRecommendations(rows),
                "generatedAt", Instant.now().toString(),
                "fallbackUsed", true
        );
    }

    /* 새롭게 추가됨: 추천 이벤트 수신 */
    public Map<String, Object> recordEvent(Map<String, Object> request, CurrentUserService.CurrentUser user) {
        return MockData.map(
                "accepted", true,
                "userId", user.id(),
                "eventType", text(request.get("eventType")),
                "recommendationId", text(request.get("recommendationId")),
                "recordedAt", Instant.now().toString()
        );
    }

    private List<Map<String, Object>> indexedRecommendations(List<Map<String, Object>> rows) {
        return java.util.stream.IntStream.range(0, rows.size())
                .mapToObj(index -> recommendationMap(rows.get(index), index))
                .toList();
    }

    private Map<String, Object> recommendationMap(Map<String, Object> row, int index) {
        String partId = DbValueMapper.string(row, "id");
        return MockData.map(
                "recommendationId", "home-part-" + partId,
                "rankPosition", index,
                "part", partMap(row),
                "scoreSource", "PARTS_DB",
                "modelVersion", "fallback-parts-db-v1",
                "reasonTags", reasonTags(row)
        );
    }

    private Map<String, Object> partMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "category", DbValueMapper.string(row, "category"),
                "name", DbValueMapper.string(row, "name"),
                "manufacturer", DbValueMapper.string(row, "manufacturer"),
                "price", DbValueMapper.integer(row, "price"),
                "status", DbValueMapper.string(row, "status"),
                "attributes", DbValueMapper.json(row, "attributes", Map.of()),
                "benchmarkSummary", benchmarkSummary(row),
                "externalOffer", externalOffer(row)
        );
    }

    private List<String> reasonTags(Map<String, Object> row) {
        if (row.get("benchmark_score") != null) {
            return List.of("BENCHMARK", "ACTIVE_PART");
        }
        if (row.get("external_offer_low_price") != null) {
            return List.of("PRICE_SIGNAL", "ACTIVE_PART");
        }
        return List.of("ACTIVE_PART");
    }

    private static Map<String, Object> benchmarkSummary(Map<String, Object> row) {
        String summary = DbValueMapper.string(row, "benchmark_summary");
        if (summary == null) {
            return null;
        }
        return MockData.map("summary", summary, "score", row.get("benchmark_score"));
    }

    private static Map<String, Object> externalOffer(Map<String, Object> row) {
        String source = DbValueMapper.string(row, "external_offer_source");
        if (source == null) {
            return null;
        }
        return MockData.map(
                "title", DbValueMapper.string(row, "external_offer_title"),
                "imageUrl", DbValueMapper.string(row, "external_offer_image_url"),
                "supplierName", DbValueMapper.string(row, "external_offer_supplier_name"),
                "offerUrl", DbValueMapper.string(row, "external_offer_url"),
                "lowPrice", DbValueMapper.integer(row, "external_offer_low_price"),
                "source", source,
                "refreshedAt", DbValueMapper.timestamp(row, "external_offer_refreshed_at")
        );
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
