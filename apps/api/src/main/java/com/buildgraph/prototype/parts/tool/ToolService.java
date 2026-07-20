package com.buildgraph.prototype.parts.tool;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.parts.part.PartQuery;
import com.buildgraph.prototype.parts.util.PerformaceRule;
import com.buildgraph.prototype.parts.util.PowerRule;

import lombok.RequiredArgsConstructor;

import static com.buildgraph.prototype.parts.util.RuleValueReader.intAttr;
import static com.buildgraph.prototype.parts.util.RuleValueReader.name;
import static com.buildgraph.prototype.parts.util.RuleValueReader.objectMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ToolService {
    private static final List<String> TOOL_ORDER = List.of("compatibility", "power", "size", "performance", "price");
    
    private final JdbcTemplate jdbcTemplate;
    private final ToolRepository toolRepository;
    private final PerformaceRule performaceRule;
    private final PartQuery partQuery;

    /* 프론트 "셀프견적"이 호출하는 검증 Tool: 해당 견적을 검증 */
    public List<Map<String, Object>> checkBuild(List<ToolBuildPart> parts, int budget) {
        return checkBuild(parts, budget, TOOL_ORDER);
    }

    /* 프론트 후보 목록에서 필요한 Tool만 검증 */
    public List<Map<String, Object>> checkBuild(List<ToolBuildPart> parts, int budget, List<String> toolNames) {
        List<String> tools = toolNames == null || toolNames.isEmpty()
                ? TOOL_ORDER
                : toolNames.stream().map(ToolService::normalizeToolName).toList();
        int currentTotalPrice = total(parts);
        List<Map<String, Object>> results = new ArrayList<>();

        for (String tool : tools) {
            results.add(checkResolvedTool(tool, parts, budget, currentTotalPrice, Map.of()));
        }

        return results;
    }

    /* 모든 Tool 호출이 거치는 입구: "셀프견적"과 다른 분기 */
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
        ToolBuildPart cpu = byCategory.get("CPU");
        ToolBuildPart motherboard = byCategory.get("MOTHERBOARD");
        ToolBuildPart ram = byCategory.get("RAM");

        /* 비교할 부품이 모두 선택된 경우에만 해당 호환성 검사를 수행한다 */
        boolean socketApplicable = cpu != null && motherboard != null;
        boolean memoryApplicable = ram != null && motherboard != null;

        String cpuSocket = stringAttr(cpu, "socket");
        String motherboardSocket = stringAttr(motherboard, "socket");
        String ramMemoryType = stringAttr(ram, "memoryType");
        String motherboardMemoryType = stringAttr(motherboard, "memoryType");

        /* 속성이 모두 있을 때만 일치/불일치를 확정한다 */
        boolean socketKnown = !socketApplicable
                || (cpuSocket != null && motherboardSocket != null);
        boolean memoryKnown = !memoryApplicable
                || (ramMemoryType != null && motherboardMemoryType != null);

        boolean socketMismatch = socketApplicable
                && socketKnown
                && !same(cpuSocket, motherboardSocket);
        boolean memoryMismatch = memoryApplicable
                && memoryKnown
                && !same(ramMemoryType, motherboardMemoryType);

        boolean failed = socketMismatch || memoryMismatch;
        boolean missing = !socketKnown || !memoryKnown;
        String status = failed ? "FAIL" : missing ? "WARN" : "PASS";

        Map<String, Object> details = MockData.map(
                "socketMatched", socketApplicable && socketKnown ? !socketMismatch : null,
                "memoryTypeMatched", memoryApplicable && memoryKnown ? !memoryMismatch : null
        );

        Map<String, Object> result = MockData.map(
                "tool", "compatibility",
                "status", status,
                "score", failed ? 0.2 : missing ? 0.65 : 1.0,
                "confidence", failed ? "HIGH" : missing ? "LOW" : "HIGH",
                "summary", failed
                        ? "확인된 소켓 또는 메모리 규격이 호환되지 않습니다."
                        : missing
                                ? "일부 속성이 없어 호환성을 완전히 확인하지 못했습니다."
                                : "확인 가능한 CPU, 메인보드, RAM 호환성이 맞습니다.",
                "warnings", failed
                        ? List.of("소켓 또는 메모리 규격이 호환되지 않습니다.")
                        : missing
                                ? List.of("소켓 또는 메모리 규격 정보가 일부 누락되었습니다.")
                                : List.of(),
                "evidence", List.of(Map.of(
                        "source_id", "compatibility-rule-v1",
                        "summary", failed
                                ? "확인된 CPU 소켓 또는 메모리 타입 불일치"
                                : missing
                                        ? "누락되지 않은 CPU 소켓과 메모리 타입만 검증"
                                        : "CPU 소켓과 메모리 타입 기준으로 검증"
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
        int gpuWattage = intAttr(gpu, "wattage", 0);

        /* 예상전력
           여유전력
           부하율 */
        int requiredRatedCapacity = Math.max(vendorRecommendedPsu, estimatedWattage + 120);
        int headroom = psuCapacity - estimatedWattage;
        int loadPercent = psuCapacity <= 0
                ? 100
                : (int) Math.round((estimatedWattage * 100.0) / psuCapacity);

        /* 통과 및 경고 여부 알기
           PSU 용량이나 GPU 전력 정보가 없으면 실패로 확정하지 않고 WARN 처리한다 */
        boolean psuCapacityKnown = psu != null && psuCapacity > 0;
        boolean gpuPowerKnown = gpu == null || gpuWattage > 0 || vendorRecommendedPsu > 0;
        boolean missing = !psuCapacityKnown || !gpuPowerKnown;

        /* 확인된 전력만으로도 PSU 용량을 초과하면 확정 불일치로 처리한다 */
        boolean failed = psuCapacityKnown
                && psuCapacity < Math.max(vendorRecommendedPsu, estimatedWattage);
        boolean pass = !missing
                && !failed
                && psuCapacity >= requiredRatedCapacity
                && loadPercent <= 85;
        String status = failed ? "FAIL" : pass ? "PASS" : "WARN";

        /* 상세객체 만들기 */
        Map<String, Object> details = MockData.map(
                "estimatedContinuousLoadW", estimatedWattage,
                "psuRatedCapacityW", psuCapacityKnown ? psuCapacity : null,
                "vendorRecommendedPsuW", vendorRecommendedPsu > 0 ? vendorRecommendedPsu : null,
                "requiredRatedCapacityW", requiredRatedCapacity,
                "ratedHeadroomW", psuCapacityKnown ? headroom : null,
                "ratedLoadPercent", psuCapacityKnown ? loadPercent : null,
                "note", "capacityW는 정격 출력이며 PSU 자체 소비전력이나 피크 부하로 합산하지 않습니다."
        );

        /* 응답객체 만들기 */
        Map<String, Object> response = MockData.map(
                "tool", "power",
                "status", status,
                "score", failed ? 0.2 : pass ? 1.0 : 0.65,
                "confidence", failed
                        ? "HIGH"
                        : missing
                                ? "LOW"
                                : headroom >= 180 && loadPercent <= 80 ? "HIGH" : "MEDIUM",
                /* 요약문 생성
                   경고문 생성
                   근거문 생성 */
                "summary", failed
                        ? "PSU 정격 출력이 확인된 시스템 전력 요구량보다 부족합니다."
                        : missing
                                ? "일부 전력 속성이 없어 전력 검사를 완전히 수행하지 못했습니다."
                                : pass
                                        ? "PSU 정격 출력이 예상 지속 부하와 GPU 권장 정격 파워를 충족합니다."
                                        : "PSU 정격 출력 여유가 낮아 상위 용량을 검토해야 합니다.",
                "warnings", failed
                        ? List.of("PSU 정격 출력이 확인된 시스템 전력 요구량보다 부족합니다.")
                        : missing
                                ? List.of("PSU 용량 또는 GPU 전력 정보가 일부 누락되었습니다.")
                                : pass
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
        int maxCoolerHeight = intAttr(pcCase, "maxCpuCoolerHeightMm", 0);

        /* 비교할 부품이 선택되고 양쪽 크기 속성이 모두 있을 때만 실패를 확정한다 */
        boolean gpuApplicable = gpu != null && pcCase != null;
        boolean coolerApplicable = cooler != null && pcCase != null;
        boolean gpuKnown = !gpuApplicable || (gpuLength > 0 && maxGpuLength > 0);
        boolean coolerKnown = !coolerApplicable || (coolerHeight > 0 && maxCoolerHeight > 0);

        /* 통과 여부 판별하기 */
        boolean gpuFailed = gpuApplicable && gpuKnown && gpuLength > maxGpuLength;
        boolean coolerFailed = coolerApplicable && coolerKnown && coolerHeight > maxCoolerHeight;
        boolean failed = gpuFailed || coolerFailed;
        boolean missing = !gpuKnown || !coolerKnown;
        String status = failed ? "FAIL" : missing ? "WARN" : "PASS";

        /* 상세객체 만들기 */
        Map<String, Object> details = MockData.map(
                "gpuLengthMm", gpuLength > 0 ? gpuLength : null,
                "maxGpuLengthMm", maxGpuLength > 0 ? maxGpuLength : null,
                "coolerHeightMm", coolerHeight > 0 ? coolerHeight : null,
                "maxCpuCoolerHeightMm", maxCoolerHeight > 0 ? maxCoolerHeight : null
        );

        /* 응답객체 만들기 */
        Map<String, Object> response = MockData.map(
                "tool", "size",
                "status", status,
                "score", failed ? 0.2 : missing ? 0.65 : 1.0,
                "confidence", failed ? "HIGH" : missing ? "LOW" : "HIGH",
                "summary", failed
                        ? "GPU 길이 또는 CPU 쿨러 높이가 케이스 허용 범위를 초과합니다."
                        : missing
                                ? "일부 크기 속성이 없어 장착 검사를 완전히 수행하지 못했습니다."
                                : "GPU 길이와 쿨러 높이가 케이스 제약 안에 있습니다.",
                "warnings", failed
                        ? List.of("GPU 길이 또는 CPU 쿨러 높이가 케이스 허용 범위를 초과합니다.")
                        : missing
                                ? List.of("부품 또는 케이스의 크기 정보가 일부 누락되었습니다.")
                                : List.of(),
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
            "tool", "performance",
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
        return partIds.isEmpty() ? List.of() : partQuery.partsByPublicIds(partIds);
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

    /** Treats missing compatibility attributes as a mismatch. */
    private static boolean same(String left, String right) {
        if (left == null || right == null) {
            return false;
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

    /** Converts arbitrary values into trimmed string lists. */
    @SuppressWarnings("null")
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

    private record AgentRootParts(List<ToolBuildPart> parts, Integer budget) {
    }
}
