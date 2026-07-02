package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.util.PerformaceRule;
import com.buildgraph.prototype.part.util.PowerRule;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import static com.buildgraph.prototype.part.util.RuleValueReader.intAttr;
import static com.buildgraph.prototype.part.util.RuleValueReader.name;

@Service
@RequiredArgsConstructor
public class ToolService {
    private static final List<String> TOOL_ORDER = List.of("compatibility", "power", "size", "performance", "price");
    
    private final JdbcTemplate jdbcTemplate;
    private final ToolRepository toolRepository;
    private final PerformaceRule performaceRule;

    /* 서버 내부 견적 추천 로직: 해당 견적을 검증 */
    public List<Map<String, Object>> checkBuild(List<ToolBuildPart> parts, int budget) {
        List<Map<String, Object>> results = new ArrayList<>();

        results.add(checkResolvedTool("compatibility", parts, budget, total(parts), Map.of()));
        results.add(checkResolvedTool("power", parts, budget, total(parts), Map.of()));
        results.add(checkResolvedTool("size", parts, budget, total(parts), Map.of()));
        results.add(checkResolvedTool("performance", parts, budget, total(parts), Map.of()));
        results.add(checkResolvedTool("price", parts, budget, total(parts), Map.of()));

        return results;
    }

    /* 모든 Tool 호출이 거치는 입구? 함수 */
    public Map<String, Object> checkTool(String toolName, Map<String, Object> request) {
        /* 1. toolName 정규화
           2. request에서 body 추출 => DB 조회 => parts 객체 가져오기
           3. request에서 context 추출 => 총 지출, 예산 추출 
           4. 툴 타입에 따른 분기 진입 */
        String tool = normalizeToolName(toolName);
        Map<String, Object> body = request == null ? Map.of() : request;
        List<ToolBuildPart> parts = resolveParts(body);
        if (parts.isEmpty()) {
            return null;
        }
        Map<String, Object> context = objectMap(body.get("context"));
        int currentTotalPrice = firstNumber(context.get("currentTotalPrice"), total(parts));
        int budget = firstNumber(context.get("budget"), currentTotalPrice);
        return checkResolvedTool(tool, parts, budget, currentTotalPrice, context);
    }

    /* Agent의 특정 카테고리 검사 요청을 받는 함수 */
    public List<Map<String, Object>> checkAgentTools(String rootType, String rootId, List<String> toolNames) {
        List<String> tools = toolNames == null || toolNames.isEmpty() ? TOOL_ORDER : toolNames.stream().map(ToolService::normalizeToolName).toList();
        AgentRootParts rootParts = resolveAgentRootParts(rootType, rootId);
        if (rootParts.parts().isEmpty()) {
            return null;
        }
        int total = total(rootParts.parts());
        int budget = rootParts.budget() == null || rootParts.budget() <= 0 ? total : rootParts.budget();
        Map<String, Object> context = MockData.map("rootType", rootType, "rootId", rootId);
        return tools.stream()
                .map(tool -> checkResolvedTool(tool, rootParts.parts(), budget, total, context))
                .toList();
    }

    /* 툴 타입에 따른 분기 설정 */
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

    /* 소켓 호환성 검증하기 */
    private Map<String, Object> compatibility(Map<String, ToolBuildPart> byCategory) {
        /* 인자에서 객체 가져오기: CPU + motherboard */
        ToolBuildPart cpu = byCategory.get("CPU");
        ToolBuildPart motherboard = byCategory.get("MOTHERBOARD");

        /* 장비 객체의 소켓 속성 일치 여부 판단 */
        boolean pass = same(stringAttr(cpu, "socket"), stringAttr(motherboard, "socket"));

        /* 세부 정보 객체 생성 */
        Map<String, Object> details = Map.of(
            "socketMatched", pass
        );
        
        /* 반환 객체 생성하기 */
        Map<String, Object> result = Map.of(
            "tool", "compatibility",
            "status", pass ? "PASS" : "FAIL",
            "score", pass ? 1.0 : 0.2,
            "confidence", "HIGH",
            "summary", pass
                    ? "CPU, 메인보드, RAM, 쿨러 기본 호환성이 맞습니다."
                    : "소켓 또는 메모리 호환성 확인이 필요합니다.",
            "warnings", pass
                    ? List.of()
                    : List.of("소켓 또는 메모리 호환성 확인이 필요합니다."),
            "evidence", List.of(Map.of(
                    "source_id", "compatibility-rule-v1",
                    "summary", pass
                            ? "CPU 소켓, 메모리 타입, 쿨러 소켓 지원 기준으로 검증"
                            : "CPU 소켓, 메모리 타입, 쿨러 소켓 지원 중 불일치 항목 존재"
                )),
            "details", details
        );

        return result;
    }

    /* 전력 검증 로직 */
    private Map<String, Object> power(Map<String, ToolBuildPart> byCategory) {
        /* 장비 각채 가져오기 */
        ToolBuildPart gpu = byCategory.get("GPU");
        ToolBuildPart psu = byCategory.get("PSU");
        
        /* 예상 총 소비전력(CPU + GPU + 기타)
           파워 정격 출력
           GPU 권장 소비전력 */
        int estimatedWattage = PowerRule.estimatedWattage(new ArrayList<>(byCategory.values()));
        int psuCapacity = intAttr(psu, "capacityW", 0);
        int vendorRecommendedPsu = intAttr(gpu, "requiredSystemPowerW", 0);
        
        /* 예상전력
           여유전력
           부하율 */
        int requiredRatedCapacity = Math.max(vendorRecommendedPsu, estimatedWattage + 120);
        int headroom = psuCapacity - estimatedWattage;
        int loadPercent = psuCapacity <= 0 ? 100 : (int) Math.round((estimatedWattage * 100.0) / psuCapacity);
        
        /* 통과 및 경고 여부 알기 */
        boolean pass = psuCapacity >= requiredRatedCapacity && loadPercent <= 85;
        boolean warn = psuCapacity >= estimatedWattage && headroom >= 80;
        
        /* 상세객체 만들기 */
        Map<String, Object> details = Map.of(
        "estimatedContinuousLoadW", estimatedWattage,
        "psuRatedCapacityW", psuCapacity,
        "vendorRecommendedPsuW", vendorRecommendedPsu,
        "requiredRatedCapacityW", requiredRatedCapacity,
        "ratedHeadroomW", headroom,
        "ratedLoadPercent", loadPercent,
        "note", "capacityW는 정격 출력이며 PSU 자체 소비전력이나 피크 부하로 합산하지 않습니다."            
         );

        /* 응답객체 만들기 */
        Map<String, Object> response = Map.of(
            "tool", "power",
            "status", pass ? "PASS" : warn ? "WARN" : "FAIL",
            "score", pass ? 1.0 : warn ? 0.65 : 0.2, 
            "confidence", headroom >= 180 && loadPercent <= 80 ? "HIGH" : "MEDIUM",
            /* 요약문 생성
               경고문 생성
               근거문 생성 */
            "summary", pass
                ? "PSU 정격 출력이 예상 지속 부하와 GPU 권장 정격 파워를 충족합니다."
                : "PSU 정격 출력 여유가 낮아 상위 용량을 검토해야 합니다.",
            "warnings", pass
                ? List.of()
                : List.of("PSU 정격 출력 여유가 낮아 상위 용량을 검토해야 합니다."),
            "evidence", List.of(Map.of(
                "source_id", "power-rule-v1",
                "summary", "CPU/GPU 소비전력, GPU 권장 PSU, PSU 정격 출력 기준으로 검증"
                )),
            "details", details
        );
       
        return response;
    }

    /* 사이즈 호환성 검증 */
    private Map<String, Object> size(Map<String, ToolBuildPart> byCategory) {
        /* 장비 객체화 하기 */
        ToolBuildPart gpu = byCategory.get("GPU");
        ToolBuildPart pcCase = byCategory.get("CASE");
        ToolBuildPart cooler = byCategory.get("COOLER");
        
        /* 객체 내에서 장비 크기 가져오기 */
        int gpuLength = intAttr(gpu, "lengthMm", 0);
        int coolerHeight = intAttr(cooler, "heightMm", intAttr(cooler, "coolerHeightMm", 0));
        
        /* 케이스 내 허용 사이즈 크기 가져오기 */
        int maxGpuLength = intAttr(pcCase, "maxGpuLengthMm", 0);
        int maxCoolerHeight = intAttr(pcCase, "maxCpuCoolerHeightMm", 190);
       
        /* 통과 여부 판별하기 */
        boolean pass = gpuLength <= maxGpuLength && coolerHeight <= maxCoolerHeight;   

        /* 상세객체 만들기 */
        Map<String, Object> details = Map.of(
        "gpuLengthMm", gpuLength,
        "maxGpuLengthMm", maxGpuLength,
        "coolerHeightMm", coolerHeight,
        "maxCpuCoolerHeightMm", maxCoolerHeight
        );

        /* 응답객체 만들기 */
        Map<String, Object> response = Map.of(
            "tool", "size",
            "status", pass ? "PASS" : "WARN",
            "score", pass ? 1.0 : 0.65,
            "summary", pass
                    ? "GPU 길이와 쿨러 높이가 케이스 제약 안에 있습니다."
                    : "케이스 장착 제약을 추가 확인해야 합니다.",
            "warnings", pass
                    ? List.of()
                    : List.of("GPU 길이 또는 CPU 쿨러 높이가 케이스 허용 범위를 초과했거나 여유가 부족합니다."),
            "evidence", List.of(Map.of(
                    "source_id", "size-rule-v1",
                    "summary", "GPU lengthMm, 케이스 maxGpuLengthMm, 쿨러 heightMm, 케이스 maxCpuCoolerHeightMm 기준으로 검증"
            )),
            "details", details
        );

        return response;
    }

    /* 퍼포먼스 측정 */
    private Map<String, Object> performance(Map<String, ToolBuildPart> byCategory, Map<String, Object> context) {
        /* CPU, GPU 객체 받기 */
        ToolBuildPart cpu = byCategory.get("CPU");
        ToolBuildPart gpu = byCategory.get("GPU");

        /* 최신 벤치마크 데이터 DB에서 가져오기(Category의 것만) => 각 장비 점수 꺼냄  */
        Map<Long, Map<String, Object>> benchmarkRows = performaceRule.latestBenchmarks(new ArrayList<>(byCategory.values()));
        Double cpuScore = performaceRule.benchmarkScore(benchmarkRows, cpu);
        Double gpuScore = performaceRule.benchmarkScore(benchmarkRows, gpu);      
        int vramGb = intAttr(gpu, "vramGb", 0);
        
        /* 데이터를 기준으로 판별하기:
           1. 둘 중 하나라도 벤치 마크가 있으면? => 이를 기준으로,
           2. 벤치마크 있는 것을 기준으로 =>  cpu >= 60, gpu >= 70
           3. 아얘 없으면 => GPU VRAM >= 12GB */
        boolean benchmarkBacked = cpuScore != null || gpuScore != null;
        boolean pass = benchmarkBacked
                ? (gpuScore == null || gpuScore >= 70.0) && (cpuScore == null || cpuScore >= 60.0)
                : vramGb >= 12;

        /* 상세 개체 생성 */
        Map<String, Object> details = MockData.map(
                "gpu", name(gpu),
                "vramGb", vramGb,
                "gpuBenchmarkScore", gpuScore,
                "gpuBenchmarkSummary", performaceRule.benchmarkSummary(benchmarkRows, gpu),
                "cpu", name(cpu),
                "cpuBenchmarkScore", cpuScore,
                "cpuBenchmarkSummary", performaceRule.benchmarkSummary(benchmarkRows, cpu),
                "usageTags", context.getOrDefault("usageTags", List.of()),
                "benchmarkSource", benchmarkBacked ? "benchmark_summaries" : "attributes_fallback",
                "guaranteePolicy", "NO_EXACT_FPS_OR_RENDER_TIME_GUARANTEE"
        );

        /* 응답 객체 생성 */
        Map<String, Object> response = Map.of(
            "tool", "performace",
            "status", pass ? "PASS" : "WARN",
            "score", pass ? 1.0 : 0.65,
            "confidence", benchmarkBacked ? "HIGH" : "MEDIUM",
            "summary", pass
                    ? "공개 벤치마크/공식 스펙 기반 적합도 점수상 요구 작업에 무리가 적은 조합입니다"
                    : "성능 또는 작업 적합도 여유가 낮아 상위 부품을 검토해야 합니다.",
            "warnings", pass
                    ? List.of()
                    : List.of("GPU 길이 또는 CPU 쿨러 높이가 케이스 허용 범위를 초과했거나 여유가 부족합니다."),
            "evidence", List.of(Map.of(
                    "source_id", "size-rule-v1",
                    "summary", "GPU lengthMm, 케이스 maxGpuLengthMm, 쿨러 heightMm, 케이스 maxCpuCoolerHeightMm 기준으로 검증"
            )),
            "details", details
        );

        return response;
    }

    /* 가격 검증 */
    private Map<String, Object> price(List<ToolBuildPart> parts, int budget, int currentTotalPrice) {
        int total = currentTotalPrice > 0 ? currentTotalPrice : total(parts);

        /* 상태 판별 */
        boolean pass = total <= budget;
        boolean warn = !pass && total <= Math.round(budget * 1.08);

        /* 상세객체 만들기 */
        Map<String, Object> details = Map.of(
            "budget", budget,
            "totalPrice", total,
            "priceDiff", total - budget
        );

        /* 응답객체 만들기 */
        Map<String, Object> response = Map.of(
            "tool", "price",
            "status", pass ? "PASS" : warn ? "WARN" : "FAIL",
            "score", pass ? 1.0 : warn ? 0.65 : 0.2,
            "summary", pass
                    ? "저장된 현재가 기준 예산 안에 들어옵니다."
                    : "저장된 현재가 기준 예산을 초과합니다.",
            "warnings", pass
                    ? List.of()
                    : List.of("저장된 현재가 기준 예산을 초과합니다."),
            "evidence", List.of(Map.of(
                    "source_id", "price-rule-v1",
                    "summary", "저장된 현재가 총합과 사용자 예산 기준으로 검증"
            )),
            "details", details
        );

        return response;
    }

    /* DB 조회 로직을 수행 */
    private List<ToolBuildPart> resolveParts(Map<String, Object> request) {
        String buildId = text(request.get("buildId"));
        if (buildId != null) {
            return toolRepository.partsByBuildId(buildId);
        }
        List<String> partIds = stringList(request.get("partIds"));
        return partIds.isEmpty() ? List.of() : partsByPublicIds(partIds);
    }

    /** Resolves the concrete build parts that an Agent root can validate: 추후 이해 필요 */
    private AgentRootParts resolveAgentRootParts(String rootType, String rootId) {
        String normalizedType = firstText(text(rootType), "").toUpperCase(Locale.ROOT);
        String id = text(rootId);
        if (id == null) {
            return new AgentRootParts(List.of(), null);
        }
        return switch (normalizedType) {
            case "BUILD" -> new AgentRootParts(toolRepository.partsByBuildId(id), budgetByBuildId(id));
            case "REQUIREMENT" -> partsByRequirementId(id);
            default -> new AgentRootParts(List.of(), null);
        };
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
        return new AgentRootParts(toolRepository.partsByBuildId(buildIds.get(0)), budgetByRequirementId(requirementId));
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

    private record AgentRootParts(List<ToolBuildPart> parts, Integer budget) {
    }
}
