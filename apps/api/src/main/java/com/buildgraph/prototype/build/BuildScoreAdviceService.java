package com.buildgraph.prototype.build;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.ToolBuildPart;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class BuildScoreAdviceService {
    private static final Set<String> CATEGORIES = Set.of(
            "CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER"
    );
    private static final Set<String> TOOLS = Set.of("compatibility", "power", "size", "performance", "price");

    public Map<String, Object> assess(
            List<ToolBuildPart> parts,
            List<Map<String, Object>> toolResults,
            Map<String, Object> compositeScore,
            String focusCategory,
            String focusTool
    ) {
        List<ToolBuildPart> safeParts = parts == null ? List.of() : parts;
        List<Map<String, Object>> safeToolResults = toolResults == null ? List.of() : toolResults;
        Map<String, Object> safeScore = compositeScore == null ? Map.of() : compositeScore;
        String safeFocusCategory = normalizeCategory(focusCategory);
        String safeFocusTool = normalizeTool(focusTool);
        Map<String, Map<String, Object>> toolByName = toolByName(safeToolResults);
        List<AdviceItem> cautions = new ArrayList<>();

        addCapCautions(cautions, safeScore, toolByName);
        addCpuGpuBalanceCaution(cautions, toolByName);
        addPowerHeadroomCaution(cautions, toolByName);
        addMemoryCaution(cautions, safeParts);
        addStorageCaution(cautions, safeParts);
        addCoolingCaution(cautions, safeParts);
        addToolCautions(cautions, safeToolResults);
        addMissingPartCaution(cautions, safeScore);
        addEvidenceCaution(cautions, safeScore, toolByName);

        List<AdviceItem> prioritized = distinct(cautions).stream()
                .sorted(adviceComparator(safeFocusCategory, safeFocusTool))
                .limit(3)
                .toList();
        List<Map<String, Object>> strengths = strengths(safeParts, toolByName, safeScore).stream()
                .limit(3)
                .map(AdviceItem::toPublicMap)
                .toList();
        List<Map<String, Object>> recommendations = recommendations(prioritized);
        int score = integer(safeScore.get("score"), 0);
        int maxScore = integer(safeScore.get("maxScore"), 1000);
        String summary = assessmentSummary(score, prioritized, safeScore);

        return MockData.map(
                "type", "COMPOSITE_SCORE_EXPLANATION",
                "score", score,
                "maxScore", maxScore,
                "grade", text(safeScore.get("grade")),
                "label", text(safeScore.get("label")),
                "summary", summary,
                "strengths", strengths,
                "cautions", prioritized.stream().map(AdviceItem::toPublicMap).toList(),
                "recommendations", recommendations,
                "evaluatedAt", OffsetDateTime.now().toString()
        );
    }

    private static void addCapCautions(
            List<AdviceItem> cautions,
            Map<String, Object> compositeScore,
            Map<String, Map<String, Object>> toolByName
    ) {
        for (Map<String, Object> cap : objectMaps(compositeScore.get("caps"))) {
            String code = text(cap.get("code")).toUpperCase(Locale.ROOT);
            String reason = text(cap.get("reason"));
            switch (code) {
                case "COMPATIBILITY_FAIL" -> cautions.add(new AdviceItem(
                        code, "FAIL", "호환성 문제", toolSummary(toolByName, "compatibility", reason),
                        List.of("CPU", "MOTHERBOARD", "RAM", "COOLER"), "compatibility", 0
                ));
                case "POWER_FAIL" -> cautions.add(new AdviceItem(
                        code, "FAIL", "파워 용량 부족", toolSummary(toolByName, "power", reason),
                        List.of("GPU", "PSU"), "power", 0
                ));
                case "SIZE_FAIL" -> cautions.add(new AdviceItem(
                        code, "FAIL", "장착 공간 부족", toolSummary(toolByName, "size", reason),
                        List.of("GPU", "CASE", "COOLER", "PSU"), "size", 0
                ));
                case "LOW_POWER_HEADROOM" -> cautions.add(new AdviceItem(
                        code, "WARN", "파워 여유 확인", reason, List.of("PSU"), "power", 10
                ));
                case "LOW_MEMORY_TIER" -> cautions.add(new AdviceItem(
                        code, "WARN", "RAM 구성 확인", reason, List.of("RAM"), "performance", 11
                ));
                case "LOW_STORAGE_TIER" -> cautions.add(new AdviceItem(
                        code, "WARN", "저장장치 구성 확인", reason, List.of("STORAGE"), "performance", 12
                ));
                case "LOW_COOLING_HEADROOM" -> cautions.add(new AdviceItem(
                        code, "WARN", "쿨링 여유 확인", reason, List.of("COOLER"), "compatibility", 13
                ));
                case "LOW_MOTHERBOARD_TIER" -> cautions.add(new AdviceItem(
                        code, "WARN", "메인보드 체급 확인", reason, List.of("MOTHERBOARD"), "compatibility", 14
                ));
                case "LOW_CASE_CLEARANCE" -> cautions.add(new AdviceItem(
                        code, "WARN", "케이스 장착 여유 확인", reason, List.of("CASE"), "size", 15
                ));
                case "LOW_CASE_AIRFLOW" -> cautions.add(new AdviceItem(
                        code, "WARN", "케이스 통풍 구성 확인", reason, List.of("CASE"), "size", 16
                ));
                case "MISSING_CORE_PART" -> cautions.add(new AdviceItem(
                        code, "WARN", "견적 완성 필요", reason, stringList(compositeScore.get("missingCategories")), null, 40
                ));
                default -> {
                    if (!code.isBlank()) {
                        cautions.add(new AdviceItem(code, "WARN", "점수 제한 항목", reason, List.of(), null, 20));
                    }
                }
            }
        }
    }

    private static void addCpuGpuBalanceCaution(
            List<AdviceItem> cautions,
            Map<String, Map<String, Object>> toolByName
    ) {
        Map<String, Object> details = details(toolByName.get("performance"));
        Double cpuScore = number(details.get("cpuBenchmarkScore"));
        Double gpuScore = number(details.get("gpuBenchmarkScore"));
        if (cpuScore == null || gpuScore == null || Math.abs(cpuScore - gpuScore) <= 18.0) {
            return;
        }
        boolean gpuIsWeaker = gpuScore < cpuScore;
        String description = gpuIsWeaker
                ? "CPU는 상위 체급이지만 GPU가 상대적으로 낮아 게임 성능 균형을 제한합니다."
                : "GPU는 상위 체급이지만 CPU가 상대적으로 낮아 전체 성능 균형을 제한합니다.";
        cautions.add(new AdviceItem(
                "CPU_GPU_IMBALANCE",
                "WARN",
                "CPU와 GPU 성능 균형",
                description,
                List.of("CPU", "GPU"),
                "performance",
                16,
                gpuIsWeaker ? "GPU" : "CPU"
        ));
    }

    private static void addPowerHeadroomCaution(
            List<AdviceItem> cautions,
            Map<String, Map<String, Object>> toolByName
    ) {
        if (containsCode(cautions, "LOW_POWER_HEADROOM") || containsCode(cautions, "POWER_FAIL")) {
            return;
        }
        Map<String, Object> power = toolByName.get("power");
        Map<String, Object> details = details(power);
        Double headroom = number(details.get("ratedHeadroomW"));
        Double load = number(details.get("ratedLoadPercent"));
        if (headroom == null && load == null) {
            return;
        }
        boolean low = (headroom != null && headroom < 160) || (load != null && load >= 80);
        if (!low) {
            return;
        }
        String description = headroom != null
                ? "현재 조합의 파워 정격 여유는 약 " + Math.round(headroom) + "W로, 부하 변동과 향후 업그레이드 여유가 크지 않습니다."
                : "현재 조합은 파워 사용률이 높아 부하 변동과 향후 업그레이드 여유가 크지 않습니다.";
        cautions.add(new AdviceItem(
                "LOW_POWER_HEADROOM", "WARN", "파워 여유 확인", description,
                List.of("PSU"), "power", 17
        ));
    }

    private static void addMemoryCaution(List<AdviceItem> cautions, List<ToolBuildPart> parts) {
        int modules = parts.stream()
                .filter(part -> "RAM".equals(normalizeCategory(part.category())))
                .mapToInt(part -> Math.max(1, integer(attributes(part).get("moduleCount"), 1)) * part.effectiveQuantity())
                .sum();
        if (modules == 1 && !containsCode(cautions, "LOW_MEMORY_TIER")) {
            cautions.add(new AdviceItem(
                    "SINGLE_CHANNEL_MEMORY", "WARN", "RAM 채널 구성",
                    "RAM이 한 모듈 구성이라 메모리 대역폭을 충분히 활용하기 어렵습니다.",
                    List.of("RAM"), "performance", 18
            ));
        }
    }

    private static void addStorageCaution(List<AdviceItem> cautions, List<ToolBuildPart> parts) {
        int bestGeneration = parts.stream()
                .filter(part -> "STORAGE".equals(normalizeCategory(part.category())))
                .mapToInt(part -> generation(attributes(part).get("generation")))
                .max()
                .orElse(0);
        if (bestGeneration > 0 && bestGeneration <= 3 && !containsCode(cautions, "LOW_STORAGE_TIER")) {
            cautions.add(new AdviceItem(
                    "LOW_STORAGE_GENERATION", "WARN", "저장장치 인터페이스",
                    "현재 SSD는 PCIe 3.0 세대라 최신 저장장치보다 순차 읽기·쓰기 성능 여유가 낮습니다.",
                    List.of("STORAGE"), "performance", 19
            ));
        }
    }

    private static void addCoolingCaution(List<AdviceItem> cautions, List<ToolBuildPart> parts) {
        ToolBuildPart cpu = first(parts, "CPU");
        ToolBuildPart cooler = first(parts, "COOLER");
        if (cpu == null || cooler == null || containsCode(cautions, "LOW_COOLING_HEADROOM")) {
            return;
        }
        int cpuTdp = integer(attributes(cpu).get("tdpW"), 0);
        int coolerTdp = integer(attributes(cooler).get("tdpW"), 0);
        if (cpuTdp > 0 && coolerTdp > 0 && coolerTdp / (double) cpuTdp < 1.35) {
            cautions.add(new AdviceItem(
                    "LOW_COOLING_HEADROOM", "WARN", "쿨링 여유 확인",
                    "CPU 발열 기준으로 쿨러 대응 여유가 낮아 장시간 부하에서 온도와 소음을 확인해야 합니다.",
                    List.of("COOLER"), "compatibility", 20
            ));
        }
    }

    private static void addToolCautions(List<AdviceItem> cautions, List<Map<String, Object>> toolResults) {
        for (Map<String, Object> result : toolResults) {
            String status = text(result.get("status")).toUpperCase(Locale.ROOT);
            if (!"WARN".equals(status) && !"FAIL".equals(status)) {
                continue;
            }
            String tool = normalizeTool(text(result.get("tool")));
            if (tool == null || alreadyRepresented(cautions, tool)) {
                continue;
            }
            cautions.add(new AdviceItem(
                    "TOOL_" + tool.toUpperCase(Locale.ROOT) + "_" + status,
                    status,
                    toolTitle(tool),
                    text(result.get("summary")),
                    toolCategories(tool),
                    tool,
                    "FAIL".equals(status) ? 1 : 30
            ));
        }
    }

    private static void addMissingPartCaution(List<AdviceItem> cautions, Map<String, Object> compositeScore) {
        List<String> missing = stringList(compositeScore.get("missingCategories"));
        if (!missing.isEmpty() && !containsCode(cautions, "MISSING_CORE_PART")) {
            cautions.add(new AdviceItem(
                    "MISSING_CORE_PART", "WARN", "견적 완성 필요",
                    "아직 담기지 않은 핵심 부품이 있어 완성 견적 기준 평가는 제한됩니다.",
                    missing, null, 40
            ));
        }
    }

    private static void addEvidenceCaution(
            List<AdviceItem> cautions,
            Map<String, Object> compositeScore,
            Map<String, Map<String, Object>> toolByName
    ) {
        int evidencePercent = objectMaps(compositeScore.get("components")).stream()
                .filter(component -> "evidence".equals(text(component.get("key"))))
                .mapToInt(component -> integer(component.get("percent"), 100))
                .findFirst()
                .orElse(100);
        Map<String, Object> perfDetails = details(toolByName.get("performance"));
        boolean benchmarkMissing = perfDetails.get("cpuBenchmarkScore") == null || perfDetails.get("gpuBenchmarkScore") == null;
        if (evidencePercent < 70 || benchmarkMissing) {
            cautions.add(new AdviceItem(
                    "EVIDENCE_INSUFFICIENT", "WARN", "평가 근거 부족",
                    "일부 부품의 벤치마크 또는 공식 스펙 근거가 부족해 확인 가능한 항목만 평가했습니다.",
                    List.of(), null, 50
            ));
        }
    }

    private static List<AdviceItem> strengths(
            List<ToolBuildPart> parts,
            Map<String, Map<String, Object>> toolByName,
            Map<String, Object> compositeScore
    ) {
        List<AdviceItem> result = new ArrayList<>();
        if (integer(compositeScore.get("score"), 0) > 0
                && List.of("compatibility", "power", "size").stream()
                        .allMatch(tool -> "PASS".equalsIgnoreCase(text(toolByName.getOrDefault(tool, Map.of()).get("status"))))) {
            result.add(new AdviceItem(
                    "CORE_CHECKS_PASSED", "PASS", "핵심 검증 통과",
                    "호환성, 파워 용량, 장착 규격 검증을 통과했습니다.",
                    List.of(), null, 0
            ));
        }
        Map<String, Object> perfDetails = details(toolByName.get("performance"));
        Double cpuScore = number(perfDetails.get("cpuBenchmarkScore"));
        Double gpuScore = number(perfDetails.get("gpuBenchmarkScore"));
        if (cpuScore != null && cpuScore >= 85) {
            result.add(new AdviceItem(
                    "HIGH_CPU_TIER", "PASS", "CPU 성능 여유",
                    "현재 CPU는 상위권 성능 근거를 가진 구성입니다.",
                    List.of("CPU"), "performance", 1
            ));
        }
        if (gpuScore != null && gpuScore >= 85) {
            result.add(new AdviceItem(
                    "HIGH_GPU_TIER", "PASS", "GPU 성능 여유",
                    "현재 GPU는 상위권 성능 근거를 가진 구성입니다.",
                    List.of("GPU"), "performance", 2
            ));
        }
        Map<String, Object> powerDetails = details(toolByName.get("power"));
        Double headroom = number(powerDetails.get("ratedHeadroomW"));
        if (headroom != null && headroom >= 160) {
            result.add(new AdviceItem(
                    "POWER_HEADROOM_READY", "PASS", "파워 여유 확보",
                    "현재 구성은 정격 출력 기준으로 안정적인 파워 여유를 확보했습니다.",
                    List.of("PSU"), "power", 3
            ));
        }
        if (result.isEmpty() && !parts.isEmpty()) {
            result.add(new AdviceItem(
                    "BUILD_EVALUATED", "PASS", "현재 구성 평가 완료",
                    "현재 담긴 부품과 확인 가능한 근거를 기준으로 평가했습니다.",
                    List.of(), null, 4
            ));
        }
        return result;
    }

    private static List<Map<String, Object>> recommendations(List<AdviceItem> cautions) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int priority = 1;
        for (AdviceItem caution : cautions) {
            Recommendation recommendation = recommendation(caution);
            if (recommendation == null || !seen.add(recommendation.category())) {
                continue;
            }
            result.add(MockData.map(
                    "priority", priority++,
                    "category", recommendation.category(),
                    "title", recommendation.title(),
                    "reason", recommendation.reason(),
                    "prompt", recommendation.prompt()
            ));
            if (result.size() == 3) {
                break;
            }
        }
        return result;
    }

    private static Recommendation recommendation(AdviceItem caution) {
        String category = caution.recommendationCategory();
        if (category == null) {
            category = switch (caution.code()) {
                case "POWER_FAIL", "LOW_POWER_HEADROOM" -> "PSU";
                case "SIZE_FAIL", "LOW_CASE_CLEARANCE", "LOW_CASE_AIRFLOW" -> "CASE";
                case "COMPATIBILITY_FAIL", "LOW_MOTHERBOARD_TIER" -> "MOTHERBOARD";
                case "LOW_MEMORY_TIER", "SINGLE_CHANNEL_MEMORY" -> "RAM";
                case "LOW_STORAGE_TIER", "LOW_STORAGE_GENERATION" -> "STORAGE";
                case "LOW_COOLING_HEADROOM" -> "COOLER";
                default -> caution.categories().size() == 1 ? caution.categories().get(0) : null;
            };
        }
        if (category == null || !CATEGORIES.contains(category)) {
            return null;
        }
        String label = categoryLabel(category);
        String title = category + " 상향 우선";
        String prompt = "현재 견적에 맞는 상위 " + label + " 추천해줘";
        if ("MISSING_CORE_PART".equals(caution.code())) {
            title = label + " 추가 우선";
            prompt = "현재 견적에 맞는 " + label + " 추천해줘";
        } else if ("SINGLE_CHANNEL_MEMORY".equals(caution.code())) {
            title = "RAM 채널 구성 보완";
            prompt = "현재 견적에 맞는 듀얼 채널 RAM 추천해줘";
        } else if ("SIZE_FAIL".equals(caution.code()) || "LOW_CASE_CLEARANCE".equals(caution.code())) {
            title = "장착 여유가 큰 케이스 검토";
            prompt = "현재 부품이 여유 있게 들어가는 케이스 추천해줘";
        } else if ("LOW_CASE_AIRFLOW".equals(caution.code())) {
            title = "통풍형 케이스 검토";
            prompt = "현재 견적과 호환되는 통풍형 케이스 추천해줘";
        }
        return new Recommendation(category, title, caution.description(), prompt);
    }

    private static String assessmentSummary(int score, List<AdviceItem> cautions, Map<String, Object> compositeScore) {
        if (score <= 0) {
            return cautions.stream()
                    .filter(caution -> "FAIL".equals(caution.severity()))
                    .findFirst()
                    .map(caution -> "현재 구성은 " + caution.description())
                    .orElse("현재 구성은 호환 또는 장착 조건을 통과하지 못해 종합 점수가 0점입니다.");
        }
        if (!cautions.isEmpty()) {
            return cautions.get(0).description();
        }
        return firstText(text(compositeScore.get("summary")), "현재 구성은 긴급하게 조정할 항목 없이 균형이 잘 맞습니다.");
    }

    private static Comparator<AdviceItem> adviceComparator(String focusCategory, String focusTool) {
        return Comparator
                .comparingInt((AdviceItem item) -> "FAIL".equals(item.severity()) ? 0 : 1)
                .thenComparingInt(item -> matchesFocus(item, focusCategory, focusTool) ? 0 : 1)
                .thenComparingInt(AdviceItem::priority);
    }

    private static boolean matchesFocus(AdviceItem item, String category, String tool) {
        return (category != null && item.categories().contains(category))
                || (tool != null && tool.equals(item.tool()));
    }

    private static List<AdviceItem> distinct(List<AdviceItem> items) {
        Map<String, AdviceItem> result = new LinkedHashMap<>();
        for (AdviceItem item : items) {
            result.putIfAbsent(item.code(), item);
        }
        return new ArrayList<>(result.values());
    }

    private static boolean containsCode(List<AdviceItem> cautions, String code) {
        return cautions.stream().anyMatch(item -> code.equals(item.code()));
    }

    private static boolean alreadyRepresented(List<AdviceItem> cautions, String tool) {
        return cautions.stream().anyMatch(item -> tool.equals(item.tool()));
    }

    private static Map<String, Map<String, Object>> toolByName(List<Map<String, Object>> toolResults) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> tool : toolResults) {
            String name = normalizeTool(text(tool.get("tool")));
            if (name != null) {
                result.putIfAbsent(name, tool);
            }
        }
        return result;
    }

    private static String toolSummary(Map<String, Map<String, Object>> tools, String tool, String fallback) {
        return firstText(text(tools.getOrDefault(tool, Map.of()).get("summary")), fallback);
    }

    private static Map<String, Object> details(Map<String, Object> tool) {
        return objectMap(tool == null ? null : tool.get("details"));
    }

    private static String toolTitle(String tool) {
        return switch (tool) {
            case "compatibility" -> "호환성 확인";
            case "power" -> "파워 여유 확인";
            case "size" -> "장착 규격 확인";
            case "performance" -> "성능 균형 확인";
            case "price" -> "요청 예산 확인";
            default -> "확인 필요";
        };
    }

    private static List<String> toolCategories(String tool) {
        return switch (tool) {
            case "compatibility" -> List.of("CPU", "MOTHERBOARD", "RAM", "COOLER");
            case "power" -> List.of("GPU", "PSU");
            case "size" -> List.of("GPU", "CASE", "COOLER", "PSU");
            case "performance" -> List.of("CPU", "GPU");
            default -> List.of();
        };
    }

    private static String categoryLabel(String category) {
        return switch (category) {
            case "MOTHERBOARD" -> "메인보드";
            case "STORAGE" -> "SSD";
            case "PSU" -> "파워";
            case "CASE" -> "케이스";
            case "COOLER" -> "쿨러";
            default -> category;
        };
    }

    private static ToolBuildPart first(List<ToolBuildPart> parts, String category) {
        return parts.stream().filter(part -> category.equals(normalizeCategory(part.category()))).findFirst().orElse(null);
    }

    private static Map<String, Object> attributes(ToolBuildPart part) {
        return part == null || part.attributes() == null ? Map.of() : part.attributes();
    }

    private static int generation(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value).toUpperCase(Locale.ROOT);
        if (text.contains("5")) return 5;
        if (text.contains("4")) return 4;
        if (text.contains("3")) return 3;
        return 0;
    }

    private static String normalizeCategory(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return CATEGORIES.contains(normalized) ? normalized : null;
    }

    private static String normalizeTool(String value) {
        if (value == null) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return TOOLS.contains(normalized) ? normalized : null;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private static List<Map<String, Object>> objectMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(BuildScoreAdviceService::objectMap).filter(map -> !map.isEmpty()).toList();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .map(BuildScoreAdviceService::text)
                .map(String::toUpperCase)
                .filter(CATEGORIES::contains)
                .distinct()
                .toList();
    }

    private static Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return null;
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int integer(Object value, int fallback) {
        Double number = number(value);
        return number == null ? fallback : number.intValue();
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record AdviceItem(
            String code,
            String severity,
            String title,
            String description,
            List<String> categories,
            String tool,
            int priority,
            String recommendationCategory
    ) {
        AdviceItem(
                String code,
                String severity,
                String title,
                String description,
                List<String> categories,
                String tool,
                int priority
        ) {
            this(code, severity, title, description, categories, tool, priority, null);
        }

        Map<String, Object> toPublicMap() {
            return MockData.map(
                    "code", code,
                    "severity", severity,
                    "title", title,
                    "description", description,
                    "relatedCategories", categories
            );
        }
    }

    private record Recommendation(String category, String title, String reason, String prompt) {
    }
}
