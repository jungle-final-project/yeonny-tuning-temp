package com.buildgraph.prototype.verification.part;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import com.buildgraph.prototype.verification.tool.ToolBuildPart;
import com.buildgraph.prototype.verification.tool.ToolService;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PartCompatibleCandidateService {
    private static final Set<String> CATEGORIES = Set.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER");

    private final JdbcTemplate jdbcTemplate;
    private final ToolService toolService;

    /* 추가된 함수: 후보호환평가 */
    public List<Map<String, Object>> partRowsWithCompatibility(
            CurrentUserService.CurrentUser user,
            String source,
            String category,
            String compatibilityMode,
            String replaceTargetPartId,
            List<Map<String, Object>> rows
    ) {
        String normalizedSource = firstText(text(source), "QUOTE_DRAFT_CURRENT").toUpperCase(Locale.ROOT);
        String normalizedCategory = normalizeCategory(category);
        if (normalizedCategory == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 부품 카테고리입니다.");
        }
        List<ToolBuildPart> baseParts = switch (normalizedSource) {
            case "QUOTE_DRAFT_CURRENT" -> currentQuoteDraftParts(user);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 compatibilitySource입니다.");
        };
        String mode = normalizeCompatibilityMode(compatibilityMode);
        List<String> checkedTools = checkedTools(normalizedCategory);
        return rows.stream()
                .map(row -> {
                    CandidateEvaluation evaluation = evaluate(
                            baseParts,
                            new CandidatePart(toolPart(row), responsePart(row)),
                            normalizedCategory,
                            mode,
                            replaceTargetPartId,
                            checkedTools
                    );
                    Map<String, Object> part = new LinkedHashMap<>(evaluation.partMap());
                    part.put("compatibility", evaluation.partListCompatibility());
                    return part;
                })
                .toList();
    }

    /* 추가된 함수: 현재견적부품 */
    private List<ToolBuildPart> currentQuoteDraftParts(CurrentUserService.CurrentUser user) {
        List<Map<String, Object>> drafts = jdbcTemplate.queryForList("""
                SELECT id AS internal_id,
                       public_id::text AS id,
                       status,
                       name
                FROM quote_drafts
                WHERE user_id = ?
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """, user.internalId());
        if (drafts.isEmpty()) {
            return List.of();
        }
        Long draftId = longValue(drafts.get(0).get("internal_id"));
        return jdbcTemplate.queryForList("""
                        SELECT p.id AS internal_id,
                               p.public_id::text AS part_id,
                               p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price AS current_price,
                               p.price,
                               qdi.quantity,
                               p.attributes
                        FROM quote_draft_items qdi
                        JOIN parts p ON p.id = qdi.part_id
                        WHERE qdi.quote_draft_id = ?
                          AND qdi.deleted_at IS NULL
                          AND p.deleted_at IS NULL
                        ORDER BY qdi.created_at ASC, qdi.id ASC
                        """, draftId)
                .stream()
                .map(this::toolPart)
                .toList();
    }

    /* 추가된 함수: 후보검증실행 */
    private CandidateEvaluation evaluate(
            List<ToolBuildPart> baseParts,
            CandidatePart candidate,
            String category,
            String compatibilityMode,
            String replaceTargetPartId,
            List<String> checkedTools
    ) {
        if (checkedTools.isEmpty()) {
            return new CandidateEvaluation(candidate.partMap(), "PASS", "ACTIVE 부품 후보입니다.", checkedTools);
        }
        List<ToolBuildPart> nextParts = nextParts(baseParts, candidate.toolPart(), category, compatibilityMode, replaceTargetPartId);
        List<Map<String, Object>> toolResults = toolService.checkBuild(nextParts, total(nextParts));
        List<Map<String, Object>> relevantResults = toolResults.stream()
                .filter(result -> checkedTools.contains(text(result.get("tool"))))
                .toList();
        String status = worstStatus(relevantResults);
        String summary = summary(status, relevantResults);
        return new CandidateEvaluation(candidate.partMap(), status, summary, checkedTools);
    }

    /* 추가된 함수: 후보조합생성 */
    private List<ToolBuildPart> nextParts(
            List<ToolBuildPart> baseParts,
            ToolBuildPart candidate,
            String category,
            String compatibilityMode,
            String replaceTargetPartId
    ) {
        List<ToolBuildPart> nextParts = new ArrayList<>();
        for (ToolBuildPart part : baseParts) {
            if (replaceTargetPartId != null && replaceTargetPartId.equals(part.publicId())) {
                continue;
            }
            if ("REPLACE".equals(compatibilityMode) && replaceTargetPartId == null && category.equals(part.category())) {
                continue;
            }
            nextParts.add(part);
        }
        nextParts.add(candidate);
        return nextParts;
    }

    /* 추가된 함수: 검증요약선택 */
    private static String summary(String status, List<Map<String, Object>> toolResults) {
        if ("PASS".equals(status)) {
            return "현재 조합 기준 호환 가능합니다.";
        }
        return toolResults.stream()
                .filter(result -> status.equals(text(result.get("status"))))
                .map(result -> text(result.get("summary")))
                .filter(summary -> summary != null && !summary.isBlank())
                .findFirst()
                .orElse("현재 조합 기준 추가 확인이 필요합니다.");
    }

    /* 추가된 함수: 최악상태선택 */
    private static String worstStatus(List<Map<String, Object>> toolResults) {
        if (toolResults.stream().anyMatch(result -> "FAIL".equals(text(result.get("status"))))) {
            return "FAIL";
        }
        if (toolResults.stream().anyMatch(result -> "WARN".equals(text(result.get("status"))))) {
            return "WARN";
        }
        return "PASS";
    }

    /* 추가된 함수: 카테고리툴선택 */
    private static List<String> checkedTools(String category) {
        return switch (category) {
            case "CPU", "MOTHERBOARD", "RAM", "COOLER" -> List.of("compatibility");
            case "GPU" -> List.of("power", "size", "performance");
            case "PSU" -> List.of("power");
            case "CASE" -> List.of("size");
            default -> List.of();
        };
    }

    /* 추가된 함수: 응답부품변환 */
    private Map<String, Object> responsePart(Map<String, Object> row) {
        if (row.containsKey("externalOffer") || row.containsKey("latestPriceSource") || row.containsKey("benchmarkSummary")) {
            return new LinkedHashMap<>(row);
        }
        return partMap(row);
    }

    /* 추가된 함수: 부품응답매핑 */
    private Map<String, Object> partMap(Map<String, Object> row) {
        return MockData.map(
                "id", firstText(DbValueMapper.string(row, "id"), DbValueMapper.string(row, "part_id")),
                "category", DbValueMapper.string(row, "category"),
                "name", DbValueMapper.string(row, "name"),
                "manufacturer", DbValueMapper.string(row, "manufacturer"),
                "price", firstNumber(row.get("price"), firstNumber(row.get("current_price"), 0)),
                "status", DbValueMapper.string(row, "status"),
                "attributes", objectMap(row.get("attributes")),
                "benchmarkSummary", benchmarkSummary(row),
                "latestPriceSource", DbValueMapper.string(row, "latest_price_source"),
                "latestPriceCollectedAt", DbValueMapper.timestamp(row, "latest_price_collected_at"),
                "externalOffer", externalOffer(row)
        );
    }

    /* 추가된 함수: 툴부품매핑 */
    private ToolBuildPart toolPart(Map<String, Object> row) {
        return new ToolBuildPart(
                longValue(row.get("internal_id")),
                firstText(DbValueMapper.string(row, "id"), DbValueMapper.string(row, "part_id")),
                DbValueMapper.string(row, "category"),
                DbValueMapper.string(row, "name"),
                DbValueMapper.string(row, "manufacturer"),
                firstNumber(row.get("price"), firstNumber(row.get("current_price"), 0)),
                objectMap(row.get("attributes"))
        );
    }

    /* 추가된 함수: 벤치요약매핑 */
    private static Map<String, Object> benchmarkSummary(Map<String, Object> row) {
        String summary = DbValueMapper.string(row, "benchmark_summary");
        if (summary == null) {
            return null;
        }
        return MockData.map("summary", summary, "score", row.get("benchmark_score"));
    }

    /* 추가된 함수: 외부가격매핑 */
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

    /* 추가된 함수: 카테고리정규화 */
    private static String normalizeCategory(String category) {
        if (category == null) {
            return null;
        }
        String normalized = category.toUpperCase(Locale.ROOT);
        return CATEGORIES.contains(normalized) ? normalized : null;
    }

    /* 추가된 함수: 호환모드검증 */
    private static String normalizeCompatibilityMode(String value) {
        String normalized = text(value);
        if (normalized == null) {
            return "REPLACE";
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if ("ADD".equals(upper) || "REPLACE".equals(upper)) {
            return upper;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 compatibilityMode입니다.");
    }

    /* 추가된 함수: 총가격계산 */
    private static int total(List<ToolBuildPart> parts) {
        return parts.stream()
                .mapToInt(part -> firstNumber(part.price(), 0))
                .sum();
    }

    /* 추가된 함수: 상태라벨 */
    private static String partListStatusLabel(String status) {
        if ("FAIL".equals(status)) {
            return "안 맞음";
        }
        if ("WARN".equals(status)) {
            return "간섭 주의";
        }
        return "호환됨";
    }

    /* 추가된 함수: 숫자롱변환 */
    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    /* 추가된 함수: 숫자변환 */
    private static int firstNumber(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /* 추가된 함수: 문자열변환 */
    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /* 추가된 함수: 첫문자선택 */
    @SafeVarargs
    private static <T> T firstText(T... values) {
        for (T value : values) {
            if (value instanceof String text && !text.isBlank()) {
                return value;
            }
            if (value != null && !(value instanceof String)) {
                return value;
            }
        }
        return null;
    }

    /* 추가된 함수: 맵변환 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        if (value == null) {
            return Map.of();
        }
        Object parsed = DbValueMapper.json(Map.of("value", value), "value", Map.of());
        return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private record CandidatePart(ToolBuildPart toolPart, Map<String, Object> partMap) {
    }

    private record CandidateEvaluation(Map<String, Object> partMap, String status, String summary, List<String> checkedTools) {
        private Map<String, Object> partListCompatibility() {
            return MockData.map(
                    "status", status,
                    "statusLabel", partListStatusLabel(status),
                    "summary", summary,
                    "checkedTools", checkedTools
            );
        }
    }
}
