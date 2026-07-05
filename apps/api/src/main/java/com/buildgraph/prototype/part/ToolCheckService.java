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
        // 그래프 GPU 노드에 "권장 파워"로 표시되는 값(requiredSystemPowerW)을 담은 PSU가 충족하면,
        // 내부 추정 기준(estimatedWattage+120)에 못 미쳐도 빨간 FAIL이 아니라 WARN으로 둔다.
        // 화면엔 "권장 750W"라 해놓고 750W PSU를 담았을 때 FAIL이 뜨는 모순을 막기 위함이다.
        boolean meetsVendorRecommendation = vendorRecommendedPsu > 0 && psuCapacity >= vendorRecommendedPsu;
        boolean pass = psuCapacity >= requiredRatedCapacity && loadPercent <= 85;
        boolean warn = psuCapacity >= estimatedWattage && (headroom >= 80 || meetsVendorRecommendation);
        return tool("power",
                pass ? "PASS" : warn ? "WARN" : "FAIL",
                headroom >= 180 && loadPercent <= 80 ? "HIGH" : "MEDIUM",
                pass
                        ? "PSU 정격 출력이 예상 지속 부하와 GPU 권장 정격 파워를 충족합니다."
                        : warn
                                ? "PSU 정격 출력이 GPU 권장 파워는 충족하지만 지속 부하 대비 여유가 넉넉하지 않아 상위 용량을 검토하면 좋습니다."
                                : "PSU 정격 출력이 예상 부하와 GPU 권장 파워에 못 미쳐 상위 용량이 필요합니다.",
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
        boolean gpuKnown = gpuLength > 0 && maxGpuLength > 0;
        boolean coolerKnown = coolerHeight > 0 && maxCoolerHeight > 0;
        boolean gpuExceeded = gpuKnown && gpuLength > maxGpuLength;
        boolean coolerExceeded = coolerKnown && coolerHeight > maxCoolerHeight;
        boolean fail = gpuExceeded || coolerExceeded;
        int gpuHeadroom = gpuKnown ? maxGpuLength - gpuLength : 0;
        int coolerHeadroom = coolerKnown ? maxCoolerHeight - coolerHeight : 0;
        boolean warn = !fail && (
                !gpuKnown
                        || !coolerKnown
                        || gpuHeadroom < 20
                        || coolerHeadroom < 5
        );
        return tool("size",
                fail ? "FAIL" : warn ? "WARN" : "PASS",
                fail ? "HIGH" : "MEDIUM",
                fail ? "케이스 장착 한계를 초과해 해당 조합은 장착할 수 없습니다."
                        : warn ? "케이스 장착 여유가 낮거나 일부 치수 근거가 부족해 추가 확인이 필요합니다."
                        : "GPU 길이와 쿨러 높이가 케이스 제약 안에 있습니다.",
                MockData.map(
                        "gpuLengthMm", gpuLength,
                        "maxGpuLengthMm", maxGpuLength,
                        "gpuHeadroomMm", gpuHeadroom,
                        "coolerHeightMm", coolerHeight,
                        "maxCpuCoolerHeightMm", maxCoolerHeight,
                        "coolerHeadroomMm", coolerHeadroom
                ));
    }

    /** Evaluates coarse workload fit without promising exact FPS. */
    private Map<String, Object> performance(Map<String, ToolBuildPart> byCategory, Map<String, Object> context) {
        ToolBuildPart cpu = byCategory.get("CPU");
        ToolBuildPart gpu = byCategory.get("GPU");
        Map<Long, Map<String, Object>> benchmarkRows = latestBenchmarks(new ArrayList<>(byCategory.values()));
        Double cpuScore = benchmarkScore(benchmarkRows, cpu);
        Double gpuScore = benchmarkScore(benchmarkRows, gpu);
        int vramGb = intAttr(gpu, "vramGb", 0);
        boolean benchmarkBacked = cpuScore != null || gpuScore != null;
        boolean pass = benchmarkBacked
                ? (gpuScore == null || gpuScore >= 70.0) && (cpuScore == null || cpuScore >= 60.0)
                : vramGb >= 12;
        List<Map<String, Object>> gameFpsEvidence = gameFpsEvidence(cpu, gpu, context);
        String gameFpsEvidenceStatus = gameFpsEvidenceStatus(context, gameFpsEvidence);
        Map<String, Object> details = MockData.map(
                "gpu", name(gpu),
                "vramGb", vramGb,
                "gpuBenchmarkScore", gpuScore,
                "gpuBenchmarkSummary", benchmarkSummary(benchmarkRows, gpu),
                "cpu", name(cpu),
                "cpuBenchmarkScore", cpuScore,
                "cpuBenchmarkSummary", benchmarkSummary(benchmarkRows, cpu),
                "usageTags", context.getOrDefault("usageTags", List.of()),
                "gameFpsEvidence", gameFpsEvidence,
                "benchmarkSource", benchmarkBacked ? "benchmark_summaries" : "attributes_fallback",
                "guaranteePolicy", "NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE"
        );
        if (!gameFpsEvidence.isEmpty()) {
            details.put("gameFpsEvidenceStatus", gameFpsEvidenceStatus);
        }
        return tool("performance",
                pass ? "PASS" : "WARN",
                benchmarkBacked ? "HIGH" : "MEDIUM",
                pass
                        ? "공개 벤치마크/공식 스펙 기반 적합도 점수상 요구 작업에 무리가 적은 조합입니다. 점수는 참고용이며 실제 성능을 보장하지 않습니다."
                        : "성능 또는 작업 적합도 여유가 낮아 상위 부품을 검토해야 합니다. 점수는 참고용이며 실제 성능을 보장하지 않습니다.",
                details);
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

    /** Loads the latest category-local benchmark/fit score for selected parts. */
    private Map<Long, Map<String, Object>> latestBenchmarks(List<ToolBuildPart> parts) {
        List<Long> partIds = parts.stream()
                .filter(part -> part != null && part.internalId() != null)
                .map(ToolBuildPart::internalId)
                .distinct()
                .toList();
        if (partIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(partIds.size(), "?"));
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        jdbcTemplate.queryForList("""
                        SELECT DISTINCT ON (part_id)
                               part_id,
                               summary,
                               score
                        FROM benchmark_summaries
                        WHERE part_id IN (
                        """ + placeholders + """
                        )
                          AND deleted_at IS NULL
                        ORDER BY part_id, created_at DESC, id DESC
                        """, partIds.toArray())
                .forEach(row -> result.put(numberLong(row.get("part_id")), row));
        return result;
    }

    /** Loads game-specific public FPS evidence for selected CPU/GPU context. */
    private List<Map<String, Object>> gameFpsEvidence(ToolBuildPart cpu, ToolBuildPart gpu, Map<String, Object> context) {
        if (gpu == null || gpu.internalId() == null) {
            return List.of();
        }
        String gameKey = gameKey(context);
        String resolution = resolution(context);
        String gpuClass = hardwareClass(gpu);
        String cpuClass = hardwareClass(cpu);
        if (gameKey == null && !gamingContext(context)) {
            return List.of();
        }

        List<Object> params = new ArrayList<>();
        params.add(gpu.internalId());
        params.add(gpuClass);
        params.add(cpu == null ? -1L : cpu.internalId());
        params.add(cpu == null ? -1L : cpu.internalId());
        params.add(cpuClass);
        params.add(resolution);
        String gameFilter = "";
        if (gameKey != null) {
            gameFilter = " AND game_key = ?\n";
            params.add(gameKey);
        }

        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id,
                               game_title,
                               game_key,
                               resolution,
                               graphics_preset,
                               avg_fps,
                               one_percent_low_fps,
                               source_name,
                               source_url,
                               source_checked_at,
                               confidence,
                               metadata,
                               CASE
                                 WHEN gpu_part_id = ? THEN 0
                                 WHEN metadata->>'gpuClass' = ? THEN 1
                                 ELSE 2
                               END AS gpu_match_rank,
                               CASE
                                 WHEN cpu_part_id = ? THEN 0
                                 WHEN ? = -1 THEN 2
                                 WHEN metadata->>'cpuClass' = ? THEN 1
                                 ELSE 2
                               END AS cpu_match_rank,
                               CASE
                                 WHEN ? IS NOT NULL AND resolution = ? THEN 0
                                 WHEN ? IS NULL THEN 0
                                 ELSE 1
                               END AS resolution_rank
                        FROM game_fps_benchmarks
                        WHERE deleted_at IS NULL
                          AND (gpu_part_id = ? OR metadata->>'gpuClass' = ?)
                        """ + gameFilter + """
                        ORDER BY gpu_match_rank,
                                 cpu_match_rank,
                                 resolution_rank,
                                 CASE confidence WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END,
                                 source_checked_at DESC,
                                 id DESC
                        LIMIT 3
                        """, fpsParams(params, gpu.internalId(), gpuClass, gameKey).toArray())
                .stream()
                .map(row -> gameFpsEvidenceMap(row, gpuClass, cpuClass, gameKey, resolution))
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

    /** Expands FPS query parameters while keeping optional game filter readable. */
    private static List<Object> fpsParams(List<Object> base, Long gpuId, String gpuClass, String gameKey) {
        List<Object> result = new ArrayList<>();
        result.add(base.get(0));
        result.add(base.get(1));
        result.add(base.get(2));
        result.add(base.get(3));
        result.add(base.get(4));
        result.add(base.get(5));
        result.add(base.get(5));
        result.add(base.get(5));
        result.add(gpuId);
        result.add(gpuClass);
        if (gameKey != null) {
            result.add(gameKey);
        }
        return result;
    }

    /** Reads the first active compatibility rule for a Tool seed fallback. */
    private Map<String, Object> ruleFor(String toolName) {
        String category = categoryForTool(toolName);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT result_status AS status,
                       message AS summary
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
        return MockData.map("tool", tool, "status", status, "confidence", confidence, "summary", summary, "details", details);
    }

    /** Reads the numeric benchmark score for a nullable part. */
    private static Double benchmarkScore(Map<Long, Map<String, Object>> benchmarkRows, ToolBuildPart part) {
        if (part == null || part.internalId() == null) {
            return null;
        }
        Map<String, Object> row = benchmarkRows.get(part.internalId());
        return row == null ? null : decimalValue(row.get("score"));
    }

    /** Reads the benchmark summary for a nullable part. */
    private static String benchmarkSummary(Map<Long, Map<String, Object>> benchmarkRows, ToolBuildPart part) {
        if (part == null || part.internalId() == null) {
            return null;
        }
        Map<String, Object> row = benchmarkRows.get(part.internalId());
        return row == null ? null : DbValueMapper.string(row, "summary");
    }

    /** Converts a public FPS benchmark row into Tool response details. */
    private static Map<String, Object> gameFpsEvidenceMap(
            Map<String, Object> row,
            String selectedGpuClass,
            String selectedCpuClass,
            String requestedGameKey,
            String requestedResolution
    ) {
        Map<String, Object> metadata = objectMap(DbValueMapper.json(row, "metadata", Map.of()));
        String sourceGameKey = DbValueMapper.string(row, "game_key");
        String sourceResolution = DbValueMapper.string(row, "resolution");
        Object sourceCpuClass = metadata.get("cpuClass");
        Object sourceGpuClass = metadata.get("gpuClass");
        boolean gameMatched = requestedGameKey == null || requestedGameKey.equals(sourceGameKey);
        boolean resolutionMatched = requestedResolution == null || requestedResolution.equals(sourceResolution);
        boolean cpuClassMatched = selectedCpuClass != null && selectedCpuClass.equals(String.valueOf(sourceCpuClass));
        boolean gpuClassMatched = selectedGpuClass != null && selectedGpuClass.equals(String.valueOf(sourceGpuClass));
        boolean exactCpuPartMatched = intRank(row.get("cpu_match_rank")) == 0;
        boolean exactGpuPartMatched = intRank(row.get("gpu_match_rank")) == 0;
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "gameTitle", DbValueMapper.string(row, "game_title"),
                "gameKey", sourceGameKey,
                "resolution", sourceResolution,
                "graphicsPreset", DbValueMapper.string(row, "graphics_preset"),
                "avgFps", decimalValue(row.get("avg_fps")),
                "onePercentLowFps", decimalValue(row.get("one_percent_low_fps")),
                "sourceName", DbValueMapper.string(row, "source_name"),
                "sourceUrl", DbValueMapper.string(row, "source_url"),
                "sourceCheckedAt", DbValueMapper.string(row, "source_checked_at"),
                "confidence", DbValueMapper.string(row, "confidence"),
                "match", MockData.map(
                        "requestedGameKey", requestedGameKey,
                        "requestedResolution", requestedResolution,
                        "selectedCpuClass", selectedCpuClass,
                        "selectedGpuClass", selectedGpuClass,
                        "sourceCpuClass", sourceCpuClass,
                        "sourceGpuClass", sourceGpuClass,
                        "hardwareScope", metadata.get("hardwareScope"),
                        "gameMatched", gameMatched,
                        "resolutionMatched", resolutionMatched,
                        "cpuClassMatched", cpuClassMatched,
                        "gpuClassMatched", gpuClassMatched,
                        "exactCpuPartMatched", exactCpuPartMatched,
                        "exactGpuPartMatched", exactGpuPartMatched,
                        "evidenceExactness", evidenceExactness(gameMatched, resolutionMatched, cpuClassMatched, gpuClassMatched, exactCpuPartMatched, exactGpuPartMatched)
                ),
                "sourceContext", MockData.map(
                        "sourceCpuName", metadata.get("sourceCpuName"),
                        "sourceGpuName", metadata.get("sourceGpuName"),
                        "sourceResolutionText", metadata.get("sourceResolutionText"),
                        "sourcePresetText", metadata.get("sourcePresetText"),
                        "gameVersion", metadata.get("gameVersion"),
                        "driverVersion", metadata.get("driverVersion"),
                        "upscaling", metadata.get("upscaling"),
                        "frameGeneration", metadata.get("frameGeneration"),
                        "rayTracing", metadata.get("rayTracing")
                ),
                "notes", metadata.get("notes"),
                "guaranteePolicy", metadata.getOrDefault("guaranteePolicy", "NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE")
        );
    }

    /** Indexes selected parts by category. */
    private static Map<String, ToolBuildPart> byCategory(List<ToolBuildPart> parts) {
        Map<String, ToolBuildPart> result = new LinkedHashMap<>();
        for (ToolBuildPart part : parts) {
            result.put(part.category(), part);
        }
        return result;
    }

    /** Determines whether a request is gaming-related enough to attach public FPS evidence. */
    private static boolean gamingContext(Map<String, Object> context) {
        String text = contextText(context);
        return containsAny(text, "game", "gaming", "게임", "qhd", "fhd", "4k", "144hz", "fps");
    }

    /** Extracts a canonical game key from Tool request context. */
    private static String gameKey(Map<String, Object> context) {
        String text = contextText(context);
        if (containsAny(text, "배그", "pubg", "battleground", "playerunknown")) return "pubg";
        if (containsAny(text, "로아", "로스트아크", "lost ark")) return "lost-ark";
        if (containsAny(text, "발로", "발로란트", "valorant")) return "valorant";
        if (containsAny(text, "오버워치", "overwatch")) return "overwatch-2";
        if (containsAny(text, "사이버펑크", "사펑", "cyberpunk")) return "cyberpunk-2077";
        return null;
    }

    /** Extracts canonical resolution labels used by game_fps_benchmarks. */
    private static String resolution(Map<String, Object> context) {
        String text = contextText(context);
        if (containsAny(text, "4k", "uhd", "2160")) return "4K";
        if (containsAny(text, "qhd", "1440", "2560")) return "QHD";
        if (containsAny(text, "fhd", "1080", "1920")) return "FHD";
        return null;
    }

    /** Builds a compact searchable context string from request fields. */
    private static String contextText(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        List<String> keys = List.of("gameTitle", "game", "targetGame", "gameName", "resolution", "rawMessage", "message", "requirementsText", "usageTags");
        StringBuilder builder = new StringBuilder();
        for (String key : keys) {
            Object value = context.get(key);
            if (value != null) {
                builder.append(' ').append(value);
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    /** Maps selected internal part names to the benchmark class labels stored in metadata. */
    private static String hardwareClass(ToolBuildPart part) {
        if (part == null) {
            return null;
        }
        String attrClass = firstText(stringAttr(part, "hardwareClass"), stringAttr(part, "gpuClass"));
        attrClass = firstText(attrClass, stringAttr(part, "cpuClass"));
        if (attrClass != null && !attrClass.isBlank()) {
            return attrClass;
        }
        String name = firstText(name(part), "").toLowerCase(Locale.ROOT);
        if ("GPU".equals(part.category())) {
            if (name.contains("5090")) return "RTX_5090";
            if (name.contains("5080")) return "RTX_5080";
            if (name.matches(".*5070\\s*ti.*") || name.contains("5070ti")) return "RTX_5070_TI";
            if (name.contains("5070")) return "RTX_5070";
            if (name.matches(".*5060\\s*ti.*") || name.contains("5060ti")) return "RTX_5060_TI";
            if (name.contains("5060")) return "RTX_5060";
        }
        if ("CPU".equals(part.category())) {
            if (name.contains("9950x3d")) return "RYZEN_9_9950X3D";
            if (name.contains("9950x")) return "RYZEN_9_9950X";
            if (name.contains("9900x3d")) return "RYZEN_9_9900X3D";
            if (name.contains("9800x3d")) return "RYZEN_7_9800X3D";
            if (name.contains("9700x")) return "RYZEN_7_9700X";
            if (name.contains("9600x")) return "RYZEN_5_9600X";
            if (name.contains("285k")) return "INTEL_CORE_ULTRA_9_285K";
            if (name.contains("265k")) return "INTEL_CORE_ULTRA_7_265K";
            if (name.contains("245k")) return "INTEL_CORE_ULTRA_5_245K";
        }
        return null;
    }

    /** Classifies the FPS evidence set so AI callers do not overstate fallback data. */
    private static String gameFpsEvidenceStatus(Map<String, Object> context, List<Map<String, Object>> evidence) {
        if (!gamingContext(context)) {
            return "NOT_GAMING_CONTEXT";
        }
        if (evidence == null || evidence.isEmpty()) {
            return "NO_MATCH";
        }
        String requestedGame = gameKey(context);
        String requestedResolution = resolution(context);
        if (requestedGame == null) {
            return "GENERAL_GAME_REFERENCE";
        }
        boolean hasExactResolution = requestedResolution == null || evidence.stream()
                .map(item -> objectMap(item.get("match")))
                .anyMatch(match -> Boolean.TRUE.equals(match.get("resolutionMatched")));
        return hasExactResolution ? "MATCHED" : "RESOLUTION_FALLBACK";
    }

    /** Grades whether an FPS row is exact evidence or only a nearby public reference. */
    private static String evidenceExactness(
            boolean gameMatched,
            boolean resolutionMatched,
            boolean cpuClassMatched,
            boolean gpuClassMatched,
            boolean exactCpuPartMatched,
            boolean exactGpuPartMatched
    ) {
        if (gameMatched && resolutionMatched && exactCpuPartMatched && exactGpuPartMatched) {
            return "EXACT_PART_AND_RESOLUTION";
        }
        if (gameMatched && resolutionMatched && cpuClassMatched && gpuClassMatched) {
            return "SAME_CLASS_AND_RESOLUTION";
        }
        if (gameMatched && gpuClassMatched) {
            return resolutionMatched ? "GPU_CLASS_REFERENCE" : "GPU_CLASS_RESOLUTION_FALLBACK";
        }
        if (gameMatched) {
            return resolutionMatched ? "GAME_RESOLUTION_REFERENCE" : "GAME_REFERENCE_FALLBACK";
        }
        return "GENERAL_REFERENCE";
    }

    /** Case-insensitive substring matcher for compact context extraction. */
    private static boolean containsAny(String value, String... needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
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

    /** Parses decimal-like DB values for benchmark scores. */
    private static Double decimalValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        return Double.valueOf(text.replace(",", ""));
    }

    /** Reads match rank integers computed by SQL CASE expressions. */
    private static int intRank(Object value) {
        Integer parsed = numberValue(value);
        return parsed == null ? 99 : parsed;
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
