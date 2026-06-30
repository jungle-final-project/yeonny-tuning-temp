package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ToolCheckService {
    private static final List<String> TOOL_ORDER = List.of("compatibility", "power", "size", "performance", "price");
    private final JdbcTemplate jdbcTemplate;

    public ToolCheckService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Checks every MVP Tool against the same build candidate. */
    public List<Map<String, Object>> checkBuild(List<ToolBuildPart> parts, int budget) {
        return TOOL_ORDER.stream()
                .map(tool -> checkResolvedTool(tool, parts, budget, total(parts), Map.of()))
                .toList();
    }

    /** Runs one Tool API request through the shared validation engine. */
    public Map<String, Object> checkTool(String toolName, Map<String, Object> request) {
        String tool = normalizeToolName(toolName);
        Map<String, Object> body = request == null ? Map.of() : request;
        List<ToolBuildPart> parts = resolveParts(body);
        if (parts.isEmpty()) {
            return seedBackedToolResult(tool);
        }
        Map<String, Object> context = objectMap(body.get("context"));
        int currentTotalPrice = firstNumber(context.get("currentTotalPrice"), total(parts));
        int budget = firstNumber(context.get("budget"), currentTotalPrice);
        return checkResolvedTool(tool, parts, budget, currentTotalPrice, context);
    }

    /** Runs Agent-requested Tool checks from the Agent root resource. */
    public List<Map<String, Object>> checkAgentTools(String rootType, String rootId, List<String> toolNames) {
        List<String> tools = toolNames == null || toolNames.isEmpty() ? TOOL_ORDER : toolNames.stream().map(ToolCheckService::normalizeToolName).toList();
        AgentRootParts rootParts = resolveAgentRootParts(rootType, rootId);
        if (rootParts.parts().isEmpty()) {
            return tools.stream().map(this::seedBackedToolResult).toList();
        }
        int total = total(rootParts.parts());
        int budget = rootParts.budget() == null || rootParts.budget() <= 0 ? total : rootParts.budget();
        Map<String, Object> context = MockData.map("rootType", rootType, "rootId", rootId);
        return tools.stream()
                .map(tool -> checkResolvedTool(tool, rootParts.parts(), budget, total, context))
                .toList();
    }

    /** Dispatches a normalized Tool name to its rule implementation. */
    private Map<String, Object> checkResolvedTool(
            String tool,
            List<ToolBuildPart> parts,
            int budget,
            int currentTotalPrice,
            Map<String, Object> context
    ) {
        Map<String, ToolBuildPart> byCategory = byCategory(parts);
        return switch (tool) {
            case "compatibility" -> compatibility(byCategory);
            case "power" -> power(byCategory);
            case "size" -> size(byCategory);
            case "performance" -> performance(byCategory, context);
            case "price" -> price(parts, budget, currentTotalPrice);
            default -> throw new IllegalArgumentException("지원하지 않는 Tool입니다: " + tool);
        };
    }

    /** Evaluates socket, memory, and cooler support compatibility. */
    private Map<String, Object> compatibility(Map<String, ToolBuildPart> byCategory) {
        ToolBuildPart cpu = byCategory.get("CPU");
        ToolBuildPart motherboard = byCategory.get("MOTHERBOARD");
        ToolBuildPart ram = byCategory.get("RAM");
        ToolBuildPart cooler = byCategory.get("COOLER");
        boolean socketMatched = same(stringAttr(cpu, "socket"), stringAttr(motherboard, "socket"));
        boolean memoryMatched = same(firstText(stringAttr(ram, "memoryType"), "DDR5"), firstText(stringAttr(motherboard, "memoryType"), "DDR5"));
        boolean coolerMatched = socketSupported(cooler, stringAttr(cpu, "socket"));
        boolean pass = socketMatched && memoryMatched && coolerMatched;
        return tool("compatibility",
                pass ? "PASS" : "FAIL",
                socketMatched && memoryMatched ? "HIGH" : "MEDIUM",
                pass ? "CPU, 메인보드, RAM, 쿨러 기본 호환성이 맞습니다." : "소켓 또는 메모리 호환성 확인이 필요합니다.",
                MockData.map("socketMatched", socketMatched, "memoryTypeMatched", memoryMatched, "coolerSocketMatched", coolerMatched));
    }

    /** Evaluates PSU rated capacity against estimated build load. */
    private Map<String, Object> power(Map<String, ToolBuildPart> byCategory) {
        ToolBuildPart gpu = byCategory.get("GPU");
        ToolBuildPart psu = byCategory.get("PSU");
        int estimatedWattage = estimatedWattage(new ArrayList<>(byCategory.values()));
        int psuCapacity = intAttr(psu, "capacityW", 0);
        int vendorRecommendedPsu = intAttr(gpu, "requiredSystemPowerW", 0);
        int requiredRatedCapacity = Math.max(vendorRecommendedPsu, estimatedWattage + 120);
        int headroom = psuCapacity - estimatedWattage;
        int loadPercent = psuCapacity <= 0 ? 100 : (int) Math.round((estimatedWattage * 100.0) / psuCapacity);
        boolean pass = psuCapacity >= requiredRatedCapacity && loadPercent <= 85;
        boolean warn = psuCapacity >= estimatedWattage && headroom >= 80;
        return tool("power",
                pass ? "PASS" : warn ? "WARN" : "FAIL",
                headroom >= 180 && loadPercent <= 80 ? "HIGH" : "MEDIUM",
                pass ? "PSU 정격 출력이 예상 지속 부하와 GPU 권장 정격 파워를 충족합니다." : "PSU 정격 출력 여유가 낮아 상위 용량을 검토해야 합니다.",
                MockData.map(
                        "estimatedContinuousLoadW", estimatedWattage,
                        "psuRatedCapacityW", psuCapacity,
                        "vendorRecommendedPsuW", vendorRecommendedPsu,
                        "requiredRatedCapacityW", requiredRatedCapacity,
                        "ratedHeadroomW", headroom,
                        "ratedLoadPercent", loadPercent,
                        "note", "capacityW는 정격 출력이며 PSU 자체 소비전력이나 피크 부하로 합산하지 않습니다."
                ));
    }

    /** Evaluates GPU length and CPU cooler height against case limits. */
    private Map<String, Object> size(Map<String, ToolBuildPart> byCategory) {
        ToolBuildPart gpu = byCategory.get("GPU");
        ToolBuildPart pcCase = byCategory.get("CASE");
        ToolBuildPart cooler = byCategory.get("COOLER");
        int gpuLength = intAttr(gpu, "lengthMm", 0);
        int maxGpuLength = intAttr(pcCase, "maxGpuLengthMm", 0);
        int coolerHeight = intAttr(cooler, "heightMm", intAttr(cooler, "coolerHeightMm", 0));
        int maxCoolerHeight = intAttr(pcCase, "maxCpuCoolerHeightMm", 190);
        boolean pass = gpuLength <= maxGpuLength && coolerHeight <= maxCoolerHeight;
        return tool("size",
                pass ? "PASS" : "WARN",
                "MEDIUM",
                pass ? "GPU 길이와 쿨러 높이가 케이스 제약 안에 있습니다." : "케이스 장착 제약을 추가 확인해야 합니다.",
                MockData.map("gpuLengthMm", gpuLength, "maxGpuLengthMm", maxGpuLength, "coolerHeightMm", coolerHeight, "maxCpuCoolerHeightMm", maxCoolerHeight));
    }

    /** Evaluates coarse workload fit without promising exact FPS. */
    private Map<String, Object> performance(Map<String, ToolBuildPart> byCategory, Map<String, Object> context) {
        ToolBuildPart cpu = byCategory.get("CPU");
        ToolBuildPart gpu = byCategory.get("GPU");
        int vramGb = intAttr(gpu, "vramGb", 0);
        boolean pass = vramGb >= 12;
        return tool("performance",
                pass ? "PASS" : "WARN",
                "MEDIUM",
                pass ? "QHD 게임과 개발 병행에 적합한 GPU 등급입니다." : "VRAM 여유가 작아 고해상도 작업에서 한계가 있을 수 있습니다.",
                MockData.map("gpu", name(gpu), "vramGb", vramGb, "cpu", name(cpu), "usageTags", context.getOrDefault("usageTags", List.of())));
    }

    /** Evaluates saved current prices against the selected budget. */
    private Map<String, Object> price(List<ToolBuildPart> parts, int budget, int currentTotalPrice) {
        int total = currentTotalPrice > 0 ? currentTotalPrice : total(parts);
        return tool("price",
                total <= budget ? "PASS" : total <= Math.round(budget * 1.08) ? "WARN" : "FAIL",
                "HIGH",
                total <= budget ? "저장된 현재가 기준 예산 안에 들어옵니다." : "저장된 현재가 기준 예산을 초과합니다.",
                MockData.map("budget", budget, "totalPrice", total, "priceDiff", total - budget));
    }

    /** Resolves buildId or partIds from a Tool API request. */
    private List<ToolBuildPart> resolveParts(Map<String, Object> request) {
        String buildId = text(request.get("buildId"));
        if (buildId != null) {
            return partsByBuildId(buildId);
        }
        List<String> partIds = stringList(request.get("partIds"));
        return partIds.isEmpty() ? List.of() : partsByPublicIds(partIds);
    }

    /** Resolves the concrete build parts that an Agent root can validate. */
    private AgentRootParts resolveAgentRootParts(String rootType, String rootId) {
        String normalizedType = firstText(text(rootType), "").toUpperCase(Locale.ROOT);
        String id = text(rootId);
        if (id == null) {
            return new AgentRootParts(List.of(), null);
        }
        return switch (normalizedType) {
            case "BUILD" -> new AgentRootParts(partsByBuildId(id), budgetByBuildId(id));
            case "REQUIREMENT" -> partsByRequirementId(id);
            default -> new AgentRootParts(List.of(), null);
        };
    }

    /** Loads build items as Tool-ready part DTOs. */
    private List<ToolBuildPart> partsByBuildId(String buildId) {
        return jdbcTemplate.queryForList("""
                        SELECT p.id AS internal_id,
                               p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               bi.price,
                               p.attributes
                        FROM build_items bi
                        JOIN builds b ON b.id = bi.build_id
                        JOIN parts p ON p.id = bi.part_id
                        WHERE b.public_id = ?::uuid
                        ORDER BY bi.id
                        """, buildId)
                .stream()
                .map(this::part)
                .toList();
    }

    /** Loads the newest build parts for a requirement-root Agent run. */
    private AgentRootParts partsByRequirementId(String requirementId) {
        List<String> buildIds = jdbcTemplate.queryForList("""
                SELECT b.public_id::text
                FROM builds b
                JOIN requirements r ON r.id = b.requirement_id
                WHERE r.public_id = ?::uuid
                ORDER BY b.created_at DESC, b.id DESC
                LIMIT 1
                """, String.class, requirementId);
        if (buildIds.isEmpty()) {
            return new AgentRootParts(List.of(), budgetByRequirementId(requirementId));
        }
        return new AgentRootParts(partsByBuildId(buildIds.get(0)), budgetByRequirementId(requirementId));
    }

    /** Reads the user budget connected to a build. */
    private Integer budgetByBuildId(String buildId) {
        List<Integer> rows = jdbcTemplate.queryForList("""
                SELECT r.budget
                FROM builds b
                JOIN requirements r ON r.id = b.requirement_id
                WHERE b.public_id = ?::uuid
                """, Integer.class, buildId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Reads the user budget connected to a requirement. */
    private Integer budgetByRequirementId(String requirementId) {
        List<Integer> rows = jdbcTemplate.queryForList("""
                SELECT budget
                FROM requirements
                WHERE public_id = ?::uuid
                """, Integer.class, requirementId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Loads explicit partIds as Tool-ready part DTOs. */
    private List<ToolBuildPart> partsByPublicIds(List<String> partIds) {
        String placeholders = String.join(", ", Collections.nCopies(partIds.size(), "?"));
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               category,
                               name,
                               manufacturer,
                               price,
                               attributes
                        FROM parts
                        WHERE public_id::text IN (
                        """ + placeholders + """
                        )
                          AND deleted_at IS NULL
                        ORDER BY category, id
                        """, partIds.toArray())
                .stream()
                .map(this::part)
                .toList();
    }

    /** Keeps legacy direct Tool calls useful when no concrete parts are supplied. */
    private Map<String, Object> seedBackedToolResult(String tool) {
        Map<String, Object> rule = ruleFor(tool);
        String category = categoryForTool(tool);
        String status = rule == null ? defaultStatus(tool) : DbValueMapper.string(rule, "status");
        String summary = rule == null ? "DB seed result for " + tool : DbValueMapper.string(rule, "summary");
        return tool(tool, status, "MEDIUM", summary, MockData.map(
                "checkedPartIds", toolReadyPartIds(category, 3),
                "candidateCategory", category,
                "source", "db-seed",
                "toolName", tool
        ));
    }

    /** Reads the first active compatibility rule for a Tool seed fallback. */
    private Map<String, Object> ruleFor(String toolName) {
        String category = categoryForTool(toolName);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT status, summary
                FROM compatibility_rules
                WHERE category = ?
                  AND deleted_at IS NULL
                ORDER BY id
                LIMIT 1
                """, category);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Finds representative Tool-ready part ids for seed fallback details. */
    private List<String> toolReadyPartIds(String category, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT public_id::text
                FROM parts
                WHERE category = ?
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                  AND coalesce((attributes->>'toolReady')::boolean, false) = true
                ORDER BY price ASC, id ASC
                LIMIT ?
                """, String.class, category, limit);
    }

    /** Converts a DB row into the shared Tool part DTO. */
    private ToolBuildPart part(Map<String, Object> row) {
        return new ToolBuildPart(
                numberLong(row.get("internal_id")),
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "category"),
                DbValueMapper.string(row, "name"),
                DbValueMapper.string(row, "manufacturer"),
                DbValueMapper.integer(row, "price"),
                objectMap(DbValueMapper.json(row, "attributes", Map.of()))
        );
    }

    /** Builds the common Tool response shape. */
    private static Map<String, Object> tool(String tool, String status, String confidence, String summary, Map<String, Object> details) {
        return MockData.map(
                "tool", tool,
                "status", status,
                "score", score(status),
                "confidence", confidence,
                "summary", summary,
                "warnings", warnings(status, summary),
                "evidence", evidence(tool, summary),
                "details", details
        );
    }

    /** Scores the normalized Tool status for sorting and admin display. */
    private static double score(String status) {
        return switch (String.valueOf(status)) {
            case "PASS" -> 1.0;
            case "WARN" -> 0.65;
            case "FAIL" -> 0.2;
            default -> 0.0;
        };
    }

    /** Converts non-pass summaries into user-facing warning strings. */
    private static List<String> warnings(String status, String summary) {
        return "PASS".equals(status) ? List.of() : List.of(firstText(summary, "Tool 검증 경고가 있습니다."));
    }

    /** Adds source evidence metadata to every Tool response. */
    private static List<Map<String, Object>> evidence(String tool, String summary) {
        return List.of(MockData.map(
                "source_id", tool + "-rule-v1",
                "summary", summary
        ));
    }

    /** Indexes selected parts by category. */
    private static Map<String, ToolBuildPart> byCategory(List<ToolBuildPart> parts) {
        Map<String, ToolBuildPart> result = new LinkedHashMap<>();
        for (ToolBuildPart part : parts) {
            result.put(part.category(), part);
        }
        return result;
    }

    /** Sums current selected part prices. */
    private static int total(List<ToolBuildPart> parts) {
        return parts.stream().mapToInt(part -> part.price() == null ? 0 : part.price()).sum();
    }

    /** Estimates continuous system draw for power checks. */
    private static int estimatedWattage(List<ToolBuildPart> parts) {
        return parts.stream()
                .mapToInt(ToolCheckService::estimatedPartPowerDraw)
                .sum() + 60;
    }

    /** Estimates per-part draw using spec attributes. */
    private static int estimatedPartPowerDraw(ToolBuildPart part) {
        if (part == null) {
            return 0;
        }
        return switch (part.category()) {
            case "CPU" -> Math.max(intAttr(part, "wattage", 0), intAttr(part, "tdpW", 65));
            case "GPU" -> intAttr(part, "wattage", 0);
            case "MOTHERBOARD" -> intAttr(part, "wattage", 50);
            case "RAM" -> intAttr(part, "wattage", 10);
            case "STORAGE" -> intAttr(part, "wattage", 8);
            case "COOLER" -> firstPositive(intAttr(part, "electricalW", 0), intAttr(part, "pumpW", 0), intAttr(part, "fanW", 0), 8);
            case "CASE" -> firstPositive(intAttr(part, "fanW", 0), intAttr(part, "wattage", 0), 10);
            case "PSU" -> 0;
            default -> intAttr(part, "wattage", 0);
        };
    }

    /** Returns the first positive numeric candidate. */
    private static int firstPositive(int... values) {
        for (int value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    /** Checks cooler socket support arrays. */
    private static boolean socketSupported(ToolBuildPart cooler, String socket) {
        if (socket == null || cooler == null) {
            return true;
        }
        Object support = cooler.attributes().get("socketSupport");
        if (support instanceof List<?> list) {
            return list.stream().anyMatch(item -> socket.equalsIgnoreCase(String.valueOf(item)));
        }
        return true;
    }

    /** Compares nullable attribute strings permissively. */
    private static boolean same(String left, String right) {
        if (left == null || right == null) {
            return true;
        }
        return left.equalsIgnoreCase(right);
    }

    /** Reads a string attribute from a part. */
    private static String stringAttr(ToolBuildPart part, String key) {
        if (part == null) {
            return null;
        }
        Object value = part.attributes().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** Reads an integer attribute from a part. */
    private static int intAttr(ToolBuildPart part, String key, int fallback) {
        if (part == null) {
            return fallback;
        }
        Object value = part.attributes().get(key);
        Integer parsed = numberValue(value);
        return parsed == null ? fallback : parsed;
    }

    /** Resolves category ownership for seed compatibility rules. */
    private static String categoryForTool(String toolName) {
        return switch (toolName) {
            case "compatibility" -> "MOTHERBOARD";
            case "power" -> "PSU";
            case "size" -> "CASE";
            case "performance" -> "GPU";
            case "price" -> "GPU";
            default -> "GPU";
        };
    }

    /** Supplies legacy fallback statuses for seed Tool calls. */
    private static String defaultStatus(String toolName) {
        return "compatibility".equals(toolName) || "size".equals(toolName) ? "PASS" : "WARN";
    }

    /** Normalizes supported Tool names from route input. */
    private static String normalizeToolName(String value) {
        String tool = text(value);
        if (tool == null) {
            throw new IllegalArgumentException("Tool 이름이 필요합니다.");
        }
        tool = tool.toLowerCase(Locale.ROOT);
        if (!TOOL_ORDER.contains(tool)) {
            throw new IllegalArgumentException("지원하지 않는 Tool입니다: " + value);
        }
        return tool;
    }

    /** Converts arbitrary map values into a string-keyed map. */
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return new LinkedHashMap<>();
    }

    /** Converts arbitrary values into trimmed string lists. */
    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).toList();
        }
        String text = text(value);
        if (text == null) {
            return List.of();
        }
        return List.of(text.split(",")).stream().map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    /** Reads text values while treating blanks and null text as absent. */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    /** Picks the first nonblank text value. */
    private static String firstText(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    /** Picks a parsed number or a fallback. */
    private static int firstNumber(Object value, int fallback) {
        Integer parsed = numberValue(value);
        return parsed == null ? fallback : parsed;
    }

    /** Parses an integer-like value. */
    private static Integer numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        return Integer.valueOf(text.replace(",", ""));
    }

    /** Parses a long-like value. */
    private static Long numberLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    /** Reads the display name of a nullable part. */
    private static String name(ToolBuildPart part) {
        return part == null ? null : part.name();
    }

    private record AgentRootParts(List<ToolBuildPart> parts, Integer budget) {
    }
}
