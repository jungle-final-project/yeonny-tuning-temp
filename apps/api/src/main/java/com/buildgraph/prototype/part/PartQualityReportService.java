package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PartQualityReportService {
    private static final List<String> CATEGORIES = List.of("CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER");
    private static final Map<String, List<FieldRequirement>> TOOL_CHECK_REQUIRED_FIELDS = Map.of(
            "CPU", List.of(
                    required("socket", "socket"),
                    required("powerDraw", "wattage", "tdpW")
            ),
            "MOTHERBOARD", List.of(
                    required("socket", "socket"),
                    required("memoryType", "memoryType")
            ),
            "RAM", List.of(
                    required("memoryType", "memoryType")
            ),
            "GPU", List.of(
                    required("gpuClass", "gpuClass"),
                    required("lengthMm", "lengthMm"),
                    required("wattage", "wattage"),
                    required("requiredSystemPowerW", "requiredSystemPowerW"),
                    required("vramGb", "vramGb")
            ),
            "PSU", List.of(
                    required("capacityW", "capacityW")
            ),
            "CASE", List.of(
                    required("maxGpuLengthMm", "maxGpuLengthMm"),
                    required("maxCpuCoolerHeightMm", "maxCpuCoolerHeightMm")
            ),
            "COOLER", List.of(
                    required("socketSupport", "socketSupport"),
                    required("coolerHeight", "heightMm", "coolerHeightMm")
            )
    );

    private final JdbcTemplate jdbcTemplate;

    public PartQualityReportService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> qualityReport() {
        List<Map<String, Object>> activeParts = activeParts();
        Map<String, Boolean> benchmarkCovered = benchmarkCovered();
        Map<String, Integer> aliasOpenByCategory = aliasOpenByCategory();
        int fpsGapTotal = fpsGapTotal();
        List<Map<String, Object>> categories = new ArrayList<>();
        List<Map<String, Object>> actionItems = new ArrayList<>();

        for (String category : CATEGORIES) {
            List<Map<String, Object>> categoryParts = activeParts.stream()
                    .filter(part -> category.equals(DbValueMapper.string(part, "category")))
                    .toList();
            int toolReadyMissing = 0;
            int requiredSpecMissing = 0;
            int benchmarkMissing = 0;
            for (Map<String, Object> part : categoryParts) {
                Map<String, Object> attributes = jsonMap(part, "attributes");
                List<String> missing = missingRequiredFields(category, attributes);
                boolean toolReady = Boolean.TRUE.equals(attributes.get("toolReady"));
                if (!toolReady) {
                    toolReadyMissing += 1;
                }
                if (!missing.isEmpty()) {
                    requiredSpecMissing += 1;
                    if (actionItems.size() < 80) {
                        actionItems.add(actionItem("MISSING_REQUIRED_SPEC", category, part, "필수 스펙 누락: " + String.join(", ", missing)));
                    }
                }
                String partId = DbValueMapper.string(part, "id");
                if (!benchmarkCovered.getOrDefault(partId, false)) {
                    benchmarkMissing += 1;
                    if (actionItems.size() < 80) {
                        actionItems.add(actionItem("MISSING_BENCHMARK", category, part, "benchmark_summaries row가 없습니다."));
                    }
                }
            }
            int aliasOpen = aliasOpenByCategory.getOrDefault(category, 0);
            int fpsCoverageGap = "GPU".equals(category) ? fpsGapTotal : 0;
            categories.add(MockData.map(
                    "category", category,
                    "activeParts", categoryParts.size(),
                    "toolReadyMissing", toolReadyMissing,
                    "requiredSpecMissing", requiredSpecMissing,
                    "benchmarkMissing", benchmarkMissing,
                    "fpsCoverageGap", fpsCoverageGap,
                    "aliasReviewOpen", aliasOpen
            ));
        }

        actionItems.addAll(aliasActionItems(Math.max(0, 80 - actionItems.size())));
        actionItems.addAll(fpsGapActionItems(Math.max(0, 80 - actionItems.size())));

        return MockData.map(
                "categories", categories,
                "summary", summary(categories),
                "actionItems", actionItems,
                "generatedAt", java.time.OffsetDateTime.now().toString()
        );
    }

    private List<Map<String, Object>> activeParts() {
        return jdbcTemplate.queryForList("""
                SELECT public_id::text AS id,
                       category,
                       name,
                       manufacturer,
                       attributes
                FROM parts
                WHERE status = 'ACTIVE'
                  AND deleted_at IS NULL
                ORDER BY category, name
                """);
    }

    private Map<String, Boolean> benchmarkCovered() {
        Map<String, Boolean> covered = new LinkedHashMap<>();
        jdbcTemplate.queryForList("""
                SELECT DISTINCT p.public_id::text AS part_id
                FROM parts p
                JOIN benchmark_summaries b ON b.part_id = p.id
                WHERE p.status = 'ACTIVE'
                  AND p.deleted_at IS NULL
                  AND b.deleted_at IS NULL
                """).forEach(row -> covered.put(DbValueMapper.string(row, "part_id"), true));
        return covered;
    }

    private Map<String, Integer> aliasOpenByCategory() {
        Map<String, Integer> result = new LinkedHashMap<>();
        jdbcTemplate.queryForList("""
                SELECT category, count(*) AS count
                FROM part_alias_review_items
                WHERE status = 'OPEN'
                  AND deleted_at IS NULL
                GROUP BY category
                """).forEach(row -> result.put(DbValueMapper.string(row, "category"), DbValueMapper.integer(row, "count")));
        return result;
    }

    private List<Map<String, Object>> aliasActionItems(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                SELECT public_id::text AS id,
                       category,
                       target_field,
                       alias_text,
                       raw_value,
                       message,
                       source_type,
                       created_at
                FROM part_alias_review_items
                WHERE status = 'OPEN'
                  AND deleted_at IS NULL
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, limit).stream()
                .map(row -> MockData.map(
                        "type", "ALIAS_REVIEW_OPEN",
                        "category", DbValueMapper.string(row, "category"),
                        "targetField", DbValueMapper.string(row, "target_field"),
                        "id", DbValueMapper.string(row, "id"),
                        "label", firstNonBlank(DbValueMapper.string(row, "alias_text"), DbValueMapper.string(row, "raw_value"), "-"),
                        "message", DbValueMapper.string(row, "message"),
                        "sourceType", DbValueMapper.string(row, "source_type"),
                        "createdAt", DbValueMapper.timestamp(row, "created_at")
                ))
                .toList();
    }

    private int fpsGapTotal() {
        try {
            Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM game_fps_coverage_gaps", Integer.class);
            return count == null ? 0 : count;
        } catch (BadSqlGrammarException ignored) {
            return 0;
        }
    }

    private List<Map<String, Object>> fpsGapActionItems(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        try {
            return jdbcTemplate.queryForList("""
                    SELECT target_type,
                           game_key,
                           resolution,
                           graphics_preset,
                           gpu_class,
                           cpu_class,
                           priority,
                           reason
                    FROM game_fps_coverage_gaps
                    ORDER BY CASE priority WHEN 'P0' THEN 0 WHEN 'P1' THEN 1 ELSE 2 END,
                             target_type,
                             game_key NULLS LAST,
                             resolution NULLS LAST
                    LIMIT ?
                    """, limit).stream()
                    .map(row -> MockData.map(
                            "type", "FPS_COVERAGE_GAP",
                            "category", "GPU",
                            "targetType", DbValueMapper.string(row, "target_type"),
                            "label", firstNonBlank(DbValueMapper.string(row, "game_key"), DbValueMapper.string(row, "gpu_class"), "FPS coverage"),
                            "message", DbValueMapper.string(row, "reason"),
                            "resolution", DbValueMapper.string(row, "resolution"),
                            "graphicsPreset", DbValueMapper.string(row, "graphics_preset"),
                            "gpuClass", DbValueMapper.string(row, "gpu_class"),
                            "cpuClass", DbValueMapper.string(row, "cpu_class"),
                            "priority", DbValueMapper.string(row, "priority")
                    ))
                    .toList();
        } catch (BadSqlGrammarException ignored) {
            return List.of();
        }
    }

    private Map<String, Object> actionItem(String type, String category, Map<String, Object> part, String message) {
        return MockData.map(
                "type", type,
                "category", category,
                "partId", DbValueMapper.string(part, "id"),
                "label", DbValueMapper.string(part, "name"),
                "message", message
        );
    }

    private Map<String, Object> summary(List<Map<String, Object>> categories) {
        int activeParts = 0;
        int toolReadyMissing = 0;
        int requiredSpecMissing = 0;
        int benchmarkMissing = 0;
        int fpsCoverageGap = 0;
        int aliasReviewOpen = 0;
        for (Map<String, Object> category : categories) {
            activeParts += number(category.get("activeParts"));
            toolReadyMissing += number(category.get("toolReadyMissing"));
            requiredSpecMissing += number(category.get("requiredSpecMissing"));
            benchmarkMissing += number(category.get("benchmarkMissing"));
            fpsCoverageGap += number(category.get("fpsCoverageGap"));
            aliasReviewOpen += number(category.get("aliasReviewOpen"));
        }
        return MockData.map(
                "activeParts", activeParts,
                "toolReadyMissing", toolReadyMissing,
                "requiredSpecMissing", requiredSpecMissing,
                "benchmarkMissing", benchmarkMissing,
                "fpsCoverageGap", fpsCoverageGap,
                "aliasReviewOpen", aliasReviewOpen
        );
    }

    private static List<String> missingRequiredFields(String category, Map<String, Object> attributes) {
        return TOOL_CHECK_REQUIRED_FIELDS.getOrDefault(category, List.of()).stream()
                .filter(requirement -> requirement.alternativeFields().stream().allMatch(field -> isBlankValue(attributes.get(field))))
                .map(FieldRequirement::label)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonMap(Map<String, Object> row, String key) {
        Object value = DbValueMapper.json(row, key, Map.of());
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((mapKey, mapValue) -> result.put(String.valueOf(mapKey), mapValue));
            return result;
        }
        return Map.of();
    }

    private static boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String text) {
            return text.trim().isEmpty();
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        return false;
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static FieldRequirement required(String label, String... alternativeFields) {
        return new FieldRequirement(label, List.of(alternativeFields));
    }

    private record FieldRequirement(String label, List<String> alternativeFields) {
    }
}
