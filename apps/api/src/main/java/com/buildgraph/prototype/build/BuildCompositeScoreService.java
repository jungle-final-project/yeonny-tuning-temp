package com.buildgraph.prototype.build;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.ToolBuildPart;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class BuildCompositeScoreService {
    private static final String POLICY_VERSION = "build-composite-score-v1";
    private static final List<String> REQUIRED_CATEGORIES = List.of("CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER");

    public Map<String, Object> score(List<ToolBuildPart> parts, List<Map<String, Object>> toolResults, int budgetWon, int totalPrice) {
        List<ToolBuildPart> normalizedParts = parts == null ? List.of() : parts;
        Map<String, List<ToolBuildPart>> byCategory = normalizedParts.stream()
                .filter(part -> part.category() != null)
                .collect(Collectors.groupingBy(part -> normalize(part.category()), LinkedHashMap::new, Collectors.toList()));
        Map<String, Map<String, Object>> toolByName = toolResults == null ? Map.of() : toolResults.stream()
                .filter(result -> result.get("tool") != null)
                .collect(Collectors.toMap(result -> normalize(text(result.get("tool"))), result -> result, (left, right) -> left, LinkedHashMap::new));

        Component performance = performanceComponent(byCategory, toolByName);
        Component compatibility = compatibilityComponent(toolByName);
        Component balance = balanceComponent(byCategory, toolByName);
        Component upgrade = upgradeComponent(byCategory, toolByName);
        Component evidence = evidenceComponent(byCategory, toolByName);
        List<Component> components = List.of(performance, compatibility, balance, upgrade, evidence);

        int rawScore = components.stream().mapToInt(Component::score).sum();
        List<Map<String, Object>> caps = new ArrayList<>();
        int cappedScore = applyCaps(rawScore, byCategory, toolByName, caps);
        String grade = grade(cappedScore);
        String label = label(cappedScore);
        return MockData.map(
                "policyVersion", POLICY_VERSION,
                "score", cappedScore,
                "rawScore", rawScore,
                "maxScore", 1000,
                "grade", grade,
                "label", label,
                "summary", summary(cappedScore, label, caps),
                "components", components.stream().map(Component::toMap).toList(),
                "caps", caps,
                "requestFit", requestFit(budgetWon, totalPrice),
                "curve", curve(cappedScore),
                "missingCategories", missingCategories(byCategory)
        );
    }

    private Component performanceComponent(Map<String, List<ToolBuildPart>> byCategory, Map<String, Map<String, Object>> toolByName) {
        Map<String, Object> perfDetails = details(toolByName, "PERFORMANCE");
        CpuScoreProfile cpuProfile = cpuScoreProfile(first(byCategory, "CPU"), number(perfDetails.get("cpuBenchmarkScore")));
        double cpuRaw = cpuProfile.blendedRaw();
        double gpuRaw = firstPositive(number(perfDetails.get("gpuBenchmarkScore")), gpuFallback(first(byCategory, "GPU")));
        double cpu = performanceCurve(cpuRaw, 3.0);
        double gpu = performanceCurve(gpuRaw, 3.4);
        double ram = performanceCurve(ramScore(byCategory.getOrDefault("RAM", List.of())), 1.35);
        double storage = performanceCurve(storageScore(byCategory.getOrDefault("STORAGE", List.of())), 1.65);
        double cooler = performanceCurve(coolerScore(first(byCategory, "CPU"), first(byCategory, "COOLER")), 1.25);
        double normalized = weightedAverage(
                List.of(gpu, cpu, ram, storage, cooler),
                List.of(0.39, 0.28, 0.14, 0.12, 0.07)
        );
        if (gpuRaw >= 99.5 && cpuProfile.endgameCapable() && ram >= 85.0 && storage >= 85.0 && cooler >= 85.0) {
            normalized = 100.0;
        }
        return component(
                "performance",
                "성능",
                Math.round(normalized * 4.3),
                430,
                "CPU/GPU 벤치마크와 RAM/저장장치/쿨링 스펙 기반. X3D CPU는 게임/작업 점수를 분리해 과대평가를 막습니다."
        );
    }

    private Component compatibilityComponent(Map<String, Map<String, Object>> toolByName) {
        if (hasBlockingFail(toolByName)) {
            return component("compatibility", "호환·장착 안전성", 0, 220, "호환, 전력, 장착성 중 하나라도 실패하면 완성 조합으로 보지 않습니다.");
        }
        boolean sizeWarn = "WARN".equals(status(toolByName, "SIZE"));
        double clearance = clearanceScore(details(toolByName, "SIZE"));
        int score = sizeWarn ? (int) Math.round(180.0 + clearance * 0.40) : 220;
        return component("compatibility", "호환·장착 안전성", score, 220, "호환 통과 후 케이스 장착 여유만 감점합니다.");
    }

    private Component balanceComponent(Map<String, List<ToolBuildPart>> byCategory, Map<String, Map<String, Object>> toolByName) {
        Map<String, Object> perfDetails = details(toolByName, "PERFORMANCE");
        double cpu = cpuScoreProfile(first(byCategory, "CPU"), number(perfDetails.get("cpuBenchmarkScore"))).blendedRaw();
        double gpu = firstPositive(number(perfDetails.get("gpuBenchmarkScore")), gpuFallback(first(byCategory, "GPU")));
        double cpuGpuBalance = 100.0 - Math.min(45.0, Math.max(0.0, Math.abs(cpu - gpu) - 18.0) * 1.35);

        Map<String, Object> powerDetails = details(toolByName, "POWER");
        int headroom = integer(powerDetails.get("ratedHeadroomW"), 0);
        int loadPercent = integer(powerDetails.get("ratedLoadPercent"), 100);
        double powerBalance = headroom >= 280 && loadPercent <= 70 ? 100.0
                : headroom >= 220 && loadPercent <= 75 ? 97.0
                : headroom >= 160 && loadPercent <= 80 ? 90.0
                : headroom >= 120 && loadPercent <= 85 ? 62.0
                : headroom >= 80 && loadPercent <= 88 ? 30.0
                : headroom >= 60 ? 18.0
                : 8.0;

        Map<String, Object> sizeDetails = details(toolByName, "SIZE");
        double fitBalance = clearanceScore(sizeDetails);
        double memoryBalance = memoryBalanceScore(byCategory.getOrDefault("RAM", List.of()));
        double storageBalance = storageBalanceScore(byCategory.getOrDefault("STORAGE", List.of()));
        double thermalBalance = thermalBalanceScore(first(byCategory, "CPU"), first(byCategory, "COOLER"));
        double normalized = weightedAverage(
                List.of(cpuGpuBalance, powerBalance, fitBalance, memoryBalance, storageBalance, thermalBalance),
                List.of(0.27, 0.33, 0.13, 0.08, 0.11, 0.08)
        );
        return component("balance", "병목·여유 균형", Math.round(normalized * 1.6), 160, "CPU/GPU 급 차이, 파워 헤드룸, 장착 여유 기반");
    }

    private Component upgradeComponent(Map<String, List<ToolBuildPart>> byCategory, Map<String, Map<String, Object>> toolByName) {
        double psu = psuUpgradeScore(first(byCategory, "PSU"), details(toolByName, "POWER"));
        double board = motherboardUpgradeScore(first(byCategory, "MOTHERBOARD"));
        double pcCase = caseUpgradeScore(first(byCategory, "CASE"));
        double storage = storageExpansionScore(byCategory.getOrDefault("STORAGE", List.of()));
        double normalized = weightedAverage(List.of(psu, board, pcCase, storage), List.of(0.37, 0.28, 0.18, 0.17));
        return component("upgrade", "확장·운영 여유", Math.round(normalized * 1.1), 110, "파워 규격, 보드 확장성, 케이스 여유, 저장장치 구성 기반");
    }

    private Component evidenceComponent(Map<String, List<ToolBuildPart>> byCategory, Map<String, Map<String, Object>> toolByName) {
        Map<String, Object> perfDetails = details(toolByName, "PERFORMANCE");
        int benchmarkCoverage = 0;
        if (number(perfDetails.get("cpuBenchmarkScore")) != null) benchmarkCoverage += 18;
        if (number(perfDetails.get("gpuBenchmarkScore")) != null) benchmarkCoverage += 18;
        if (!list(perfDetails.get("gameFpsEvidence")).isEmpty()) benchmarkCoverage += 10;
        int specCoverage = REQUIRED_CATEGORIES.stream()
                .map(category -> first(byCategory, category))
                .mapToInt(part -> part == null || part.attributes() == null || part.attributes().isEmpty() ? 0 : 3)
                .sum();
        long toolKnown = toolByName.values().stream().filter(tool -> tool.get("status") != null).count();
        int toolCoverage = (int) Math.min(10, toolKnown * 2);
        int score = clampInt(benchmarkCoverage + specCoverage + toolCoverage, 0, 80);
        return component("evidence", "근거 신뢰도", score, 80, "벤치마크, FPS seed, Tool 판정, 스펙 데이터 보유율");
    }

    private int applyCaps(
            int rawScore,
            Map<String, List<ToolBuildPart>> byCategory,
            Map<String, Map<String, Object>> toolByName,
            List<Map<String, Object>> caps
    ) {
        int cap = 1000;
        if ("FAIL".equals(status(toolByName, "COMPATIBILITY"))) {
            caps.add(cap("COMPATIBILITY_FAIL", 0, "소켓/메모리/쿨러 호환 실패 조합은 완성 PC 점수를 0점으로 처리합니다."));
            return 0;
        }
        if ("FAIL".equals(status(toolByName, "POWER"))) {
            caps.add(cap("POWER_FAIL", 0, "파워 부족 조합은 완성 PC 점수를 0점으로 처리합니다."));
            return 0;
        }
        if ("FAIL".equals(status(toolByName, "SIZE"))) {
            caps.add(cap("SIZE_FAIL", 0, "케이스 장착 불가 조합은 완성 PC 점수를 0점으로 처리합니다."));
            return 0;
        }
        Map<String, Object> powerDetails = details(toolByName, "POWER");
        boolean hasPowerMetrics = number(powerDetails.get("ratedHeadroomW")) != null || number(powerDetails.get("ratedLoadPercent")) != null;
        if ("PASS".equals(status(toolByName, "POWER")) && hasPowerMetrics) {
            int powerCap = powerHeadroomCap(
                    integer(powerDetails.get("ratedHeadroomW"), 0),
                    integer(powerDetails.get("ratedLoadPercent"), 100)
            );
            if (powerCap < rawScore) {
                cap = Math.min(cap, powerCap);
                caps.add(cap("LOW_POWER_HEADROOM", powerCap, "파워 용량은 통과했지만 고성능 조합 대비 여유가 낮아 최고 점수를 제한합니다."));
            }
        }
        int memoryCap = memoryQualityCap(byCategory.getOrDefault("RAM", List.of()));
        if (memoryCap < rawScore) {
            cap = Math.min(cap, memoryCap);
            caps.add(cap("LOW_MEMORY_TIER", memoryCap, "RAM 용량이나 채널 구성이 고성능 조합 기준보다 낮아 최고 점수를 제한합니다."));
        }
        int storageCap = storageQualityCap(byCategory.getOrDefault("STORAGE", List.of()));
        if (storageCap < rawScore) {
            cap = Math.min(cap, storageCap);
            caps.add(cap("LOW_STORAGE_TIER", storageCap, "저장장치 용량이나 PCIe 세대가 고성능 조합 기준보다 낮아 최고 점수를 제한합니다."));
        }
        int coolerCap = coolerQualityCap(first(byCategory, "CPU"), first(byCategory, "COOLER"));
        if (coolerCap < rawScore) {
            cap = Math.min(cap, coolerCap);
            caps.add(cap("LOW_COOLING_HEADROOM", coolerCap, "CPU 발열 대비 쿨러 여유가 낮아 최고 점수를 제한합니다."));
        }
        int motherboardCap = motherboardQualityCap(
                first(byCategory, "CPU"),
                first(byCategory, "GPU"),
                first(byCategory, "MOTHERBOARD"),
                details(toolByName, "PERFORMANCE")
        );
        if (motherboardCap < rawScore) {
            cap = Math.min(cap, motherboardCap);
            caps.add(cap("LOW_MOTHERBOARD_TIER", motherboardCap, "고성능 CPU/GPU 조합 대비 메인보드 플랫폼 체급이 낮아 최고 점수를 제한합니다."));
        }
        int caseCap = caseQualityCap(
                first(byCategory, "CPU"),
                first(byCategory, "GPU"),
                first(byCategory, "COOLER"),
                first(byCategory, "PSU"),
                first(byCategory, "CASE"),
                details(toolByName, "SIZE"),
                details(toolByName, "PERFORMANCE")
        );
        if (caseCap < rawScore) {
            cap = Math.min(cap, caseCap);
            caps.add(cap("LOW_CASE_CLEARANCE", caseCap, "장착은 가능하지만 케이스 여유나 airflow 근거가 낮아 최고 점수를 제한합니다."));
        }
        List<String> missing = missingCategories(byCategory);
        if (!missing.isEmpty()) {
            int missingCap = missing.size() >= 3 ? 600 : missing.size() == 2 ? 680 : 760;
            cap = Math.min(cap, missingCap);
            caps.add(cap("MISSING_CORE_PART", missingCap, "핵심 부품이 빠진 조합은 완성 견적 점수를 제한합니다."));
        }
        return Math.min(rawScore, cap);
    }

    private static int powerHeadroomCap(int headroomW, int loadPercent) {
        if (headroomW < 80 || loadPercent >= 88) return 880;
        if (headroomW < 120 || loadPercent >= 84) return 920;
        if (headroomW < 160 || loadPercent >= 80) return 950;
        return 1000;
    }

    private static int memoryQualityCap(List<ToolBuildPart> ramParts) {
        if (ramParts == null || ramParts.isEmpty()) return 1000;
        int capacity = totalRamCapacityGb(ramParts);
        int moduleCount = totalRamModuleCount(ramParts);
        if (capacity > 0 && capacity < 8) return 620;
        if (capacity > 0 && capacity < 16) return 720;
        if (capacity > 0 && capacity < 32) return moduleCount >= 2 ? 880 : 860;
        if (capacity > 0 && capacity < 64 && moduleCount < 2) return 920;
        return 1000;
    }

    private static int storageQualityCap(List<ToolBuildPart> storageParts) {
        if (storageParts == null || storageParts.isEmpty()) return 1000;
        int totalCapacity = storageParts.stream()
                .mapToInt(part -> integer(attrs(part).get("capacityGb"), 0) * part.effectiveQuantity())
                .sum();
        int bestGeneration = storageParts.stream().mapToInt(part -> generation(attrs(part).get("generation"))).max().orElse(0);
        int cap = 1000;
        if (totalCapacity > 0 && totalCapacity < 1000) {
            cap = Math.min(cap, 900);
        } else if (totalCapacity > 0 && totalCapacity < 2000) {
            cap = Math.min(cap, 929);
        }
        if (bestGeneration > 0 && bestGeneration <= 3) {
            cap = Math.min(cap, 930);
        }
        return cap;
    }

    private static int coolerQualityCap(ToolBuildPart cpu, ToolBuildPart cooler) {
        if (cpu == null || cooler == null) return 1000;
        int cpuTdp = integer(attrs(cpu).get("tdpW"), 0);
        int coolerTdp = integer(attrs(cooler).get("tdpW"), 0);
        if (cpuTdp <= 0 || coolerTdp <= 0) return 1000;
        double ratio = coolerTdp / (double) cpuTdp;
        if (ratio < 1.05) return 820;
        if (ratio < 1.20) return 880;
        if (ratio < 1.35) return 930;
        return 1000;
    }

    private static int motherboardQualityCap(ToolBuildPart cpu, ToolBuildPart gpu, ToolBuildPart board, Map<String, Object> perfDetails) {
        if (board == null) return 1000;
        CpuScoreProfile cpuProfile = cpuScoreProfile(cpu, number(perfDetails.get("cpuBenchmarkScore")));
        double gpuRaw = firstPositive(number(perfDetails.get("gpuBenchmarkScore")), gpuFallback(gpu));
        BoardProfile boardProfile = boardProfile(board);
        boolean extremeBuild = gpuRaw >= 99.5 && cpuProfile.endgameCapable();
        boolean highEndBuild = gpuRaw >= 94.0 || cpuProfile.blendedRaw() >= 94.0;
        if (extremeBuild) {
            if (boardProfile.premium()) return 1000;
            if (boardProfile.modernMainstream()) return 965;
            if (boardProfile.mainstream()) return 929;
            if (boardProfile.entry()) return 870;
            return 900;
        }
        if (highEndBuild) {
            if (boardProfile.premium()) return 1000;
            if (boardProfile.modernMainstream()) return 980;
            if (boardProfile.mainstream()) return 960;
            if (boardProfile.entry()) return 900;
            return 930;
        }
        return boardProfile.entry() ? 930 : 1000;
    }

    private static BoardProfile boardProfile(ToolBuildPart board) {
        Map<String, Object> attrs = attrs(board);
        String chipset = text(attrs.get("chipset")).toUpperCase(Locale.ROOT);
        int pcie = generation(attrs.get("pcieGeneration"));
        boolean wifi = bool(attrs.get("hasWifi")) || text(attrs.get("wifi")).toUpperCase(Locale.ROOT).contains("WI");
        boolean premium = chipset.startsWith("X") || chipset.startsWith("Z");
        boolean mainstream = chipset.startsWith("B");
        boolean modernMainstream = mainstream && (chipset.contains("850") || chipset.contains("860") || (pcie >= 5 && wifi));
        boolean entry = chipset.startsWith("A") || chipset.startsWith("H");
        return new BoardProfile(premium, modernMainstream, mainstream, entry);
    }

    private static int caseQualityCap(
            ToolBuildPart cpu,
            ToolBuildPart gpu,
            ToolBuildPart cooler,
            ToolBuildPart psu,
            ToolBuildPart pcCase,
            Map<String, Object> sizeDetails,
            Map<String, Object> perfDetails
    ) {
        if (pcCase == null) return 1000;
        int cap = 1000;
        cap = Math.min(cap, clearanceCap(
                firstPositiveInt(integer(sizeDetails.get("gpuLengthMm"), 0), integer(attrs(gpu).get("lengthMm"), 0)),
                firstPositiveInt(integer(sizeDetails.get("maxGpuLengthMm"), 0), integer(attrs(pcCase).get("maxGpuLengthMm"), 0)),
                5,
                20,
                40
        ));
        cap = Math.min(cap, clearanceCap(
                firstPositiveInt(integer(sizeDetails.get("coolerHeightMm"), 0), integer(attrs(cooler).get("heightMm"), 0)),
                firstPositiveInt(integer(sizeDetails.get("maxCpuCoolerHeightMm"), 0), integer(attrs(pcCase).get("maxCpuCoolerHeightMm"), 0)),
                3,
                10,
                20
        ));
        cap = Math.min(cap, clearanceCap(
                firstPositiveInt(integer(sizeDetails.get("psuDepthMm"), 0), integer(attrs(psu).get("depthMm"), 0)),
                firstPositiveInt(integer(sizeDetails.get("maxPsuLengthMm"), 0), integer(attrs(pcCase).get("maxPsuLengthMm"), 0)),
                5,
                15,
                40
        ));

        CpuScoreProfile cpuProfile = cpuScoreProfile(cpu, number(perfDetails.get("cpuBenchmarkScore")));
        double gpuRaw = firstPositive(number(perfDetails.get("gpuBenchmarkScore")), gpuFallback(gpu));
        Boolean airflowStrong = caseAirflowStrong(pcCase);
        boolean extremeBuild = gpuRaw >= 99.5 && cpuProfile.endgameCapable();
        boolean highEndBuild = gpuRaw >= 94.0 || cpuProfile.blendedRaw() >= 94.0;
        if (extremeBuild && Boolean.FALSE.equals(airflowStrong)) {
            cap = Math.min(cap, 929);
        } else if (highEndBuild && Boolean.FALSE.equals(airflowStrong)) {
            cap = Math.min(cap, 960);
        }
        return cap;
    }

    private static int clearanceCap(int actualMm, int limitMm, int narrowMm, int normalMm, int comfortableMm) {
        if (actualMm <= 0 || limitMm <= 0) return 1000;
        int headroom = limitMm - actualMm;
        if (headroom < 0) return 0;
        if (headroom < narrowMm) return 880;
        if (headroom < normalMm) return 920;
        if (headroom < comfortableMm) return 960;
        return 1000;
    }

    private static Boolean caseAirflowStrong(ToolBuildPart pcCase) {
        Map<String, Object> attrs = attrs(pcCase);
        if (!attrs.containsKey("frontMesh") && !attrs.containsKey("airflowFocus") && !attrs.containsKey("airflow")) {
            return null;
        }
        return bool(attrs.get("frontMesh"))
                || bool(attrs.get("airflowFocus"))
                || text(attrs.get("airflow")).toUpperCase(Locale.ROOT).contains("HIGH");
    }

    private static Map<String, Object> cap(String code, int maxScore, String reason) {
        return MockData.map("code", code, "maxScore", maxScore, "reason", reason);
    }

    private static Map<String, Object> requestFit(int budgetWon, int totalPrice) {
        if (budgetWon <= 0 || totalPrice <= 0) {
            return MockData.map(
                    "status", "UNSPECIFIED",
                    "score", null,
                    "budgetWon", budgetWon > 0 ? budgetWon : null,
                    "totalPrice", totalPrice > 0 ? totalPrice : null,
                    "priceDiff", null,
                    "summary", "명시 예산이 없어 견적 자체 점수만 계산했습니다."
            );
        }
        int diff = totalPrice - budgetWon;
        double ratio = totalPrice / (double) budgetWon;
        String status = ratio <= 1.0 ? "PASS" : ratio <= 1.125 ? "WARN" : "OVER_BUDGET";
        int score = ratio <= 1.0
                ? 100
                : ratio <= 1.125
                        ? (int) Math.round(100.0 - (ratio - 1.0) / 0.125 * 20.0)
                        : ratio <= 1.30
                                ? (int) Math.round(70.0 - (ratio - 1.125) / 0.175 * 30.0)
                                : 25;
        return MockData.map(
                "status", status,
                "score", clampInt(score, 0, 100),
                "budgetWon", budgetWon,
                "totalPrice", totalPrice,
                "priceDiff", diff,
                "ratio", Math.round(ratio * 1000.0) / 1000.0,
                "summary", diff <= 0
                        ? "명시 예산 안에 들어오는 조합입니다."
                        : "명시 예산보다 " + diff + "원 높습니다. 견적 비교 점수와 별도로 해석해야 합니다."
        );
    }

    private static Component component(String key, String label, long score, int maxScore, String summary) {
        return new Component(key, label, clampInt((int) score, 0, maxScore), maxScore, summary);
    }

    private static Map<String, Object> curve(int score) {
        return MockData.map(
                "position", score / 1000.0,
                "markers", List.of(
                        MockData.map("score", 400, "label", "검토 필요"),
                        MockData.map("score", 600, "label", "기본형"),
                        MockData.map("score", 750, "label", "균형형"),
                        MockData.map("score", 850, "label", "고성능"),
                        MockData.map("score", 930, "label", "끝판왕")
                )
        );
    }

    private static String summary(int score, String label, List<Map<String, Object>> caps) {
        if (!caps.isEmpty()) {
            return label + " (" + score + "/1000). 다만 " + DbValueMapper.string(caps.get(0), "reason");
        }
        return label + " (" + score + "/1000). 성능, 호환성, 균형, 확장성, 근거 신뢰도를 합산한 참고 점수입니다.";
    }

    private static String grade(int score) {
        if (score >= 930) return "S";
        if (score >= 850) return "A";
        if (score >= 750) return "B";
        if (score >= 600) return "C";
        if (score >= 400) return "D";
        return "F";
    }

    private static String label(int score) {
        if (score >= 930) return "끝판왕";
        if (score >= 850) return "고성능";
        if (score >= 750) return "균형형";
        if (score >= 600) return "기본형";
        if (score >= 400) return "검토 필요";
        return "구성 재검토";
    }

    private static List<String> missingCategories(Map<String, List<ToolBuildPart>> byCategory) {
        return REQUIRED_CATEGORIES.stream()
                .filter(category -> !byCategory.containsKey(category) || byCategory.get(category).isEmpty())
                .toList();
    }

    private static double statusScore(Map<String, Map<String, Object>> toolByName, String tool) {
        return switch (status(toolByName, tool)) {
            case "PASS" -> 100.0;
            case "WARN" -> 72.0;
            case "FAIL" -> 0.0;
            default -> 55.0;
        };
    }

    private static boolean hasBlockingFail(Map<String, Map<String, Object>> toolByName) {
        return "FAIL".equals(status(toolByName, "COMPATIBILITY"))
                || "FAIL".equals(status(toolByName, "POWER"))
                || "FAIL".equals(status(toolByName, "SIZE"));
    }

    private static String status(Map<String, Map<String, Object>> toolByName, String tool) {
        return normalize(text(toolByName.getOrDefault(tool, Map.of()).get("status")));
    }

    private static Map<String, Object> details(Map<String, Map<String, Object>> toolByName, String tool) {
        Object value = toolByName.getOrDefault(tool, Map.of()).get("details");
        return objectMap(value);
    }

    private static double performanceCurve(double score, double exponent) {
        double normalized = clamp(score, 0.0, 100.0) / 100.0;
        return Math.pow(normalized, exponent) * 100.0;
    }

    private static CpuScoreProfile cpuScoreProfile(ToolBuildPart cpu, Double benchmarkScore) {
        if (cpu == null) {
            return new CpuScoreProfile(0.0, 0.0, 0.0, false, 0);
        }
        boolean x3d = isX3dCpu(cpu);
        double generalRaw = firstPositive(benchmarkScore, cpuProductivityFallback(cpu));
        double gamingRaw = clamp(Math.max(generalRaw, cpuGamingFallback(cpu)) + (x3d ? x3dGamingBonus(cpu) : 0.0), 35.0, 100.0);
        double productivityRaw = x3d
                ? clamp(generalRaw * 0.55 + cpuProductivityFallback(cpu) * 0.45 - x3dProductivityPenalty(cpu), 35.0, 100.0)
                : clamp(generalRaw, 35.0, 100.0);
        double blendedRaw = weightedAverage(
                List.of(gamingRaw, productivityRaw, generalRaw),
                List.of(0.45, 0.45, 0.10)
        );
        return new CpuScoreProfile(gamingRaw, productivityRaw, blendedRaw, x3d, integer(attrs(cpu).get("coreCount"), 0));
    }

    private static double cpuGamingFallback(ToolBuildPart cpu) {
        if (cpu == null) return 0.0;
        Map<String, Object> attrs = attrs(cpu);
        double cores = Math.min(24.0, integer(attrs.get("coreCount"), 6)) / 24.0 * 38.0;
        double threads = Math.min(32.0, integer(attrs.get("threadCount"), 12)) / 32.0 * 14.0;
        String model = (cpu.name() + " " + text(attrs.get("cpuClass"))).toUpperCase(Locale.ROOT);
        double tier = model.contains("9950") || model.contains("RYZEN 9") || model.contains("I9") || model.contains("ULTRA 9") ? 38.0
                : model.contains("9700") || model.contains("RYZEN 7") || model.contains("I7") || model.contains("ULTRA 7") ? 30.0
                : model.contains("RYZEN 5") || model.contains("I5") ? 22.0
                : 16.0;
        double cacheBonus = model.contains("X3D") ? 8.0 : 0.0;
        return clamp(cores + threads + tier + cacheBonus, 35.0, 100.0);
    }

    private static double cpuProductivityFallback(ToolBuildPart cpu) {
        if (cpu == null) return 0.0;
        Map<String, Object> attrs = attrs(cpu);
        double cores = Math.min(24.0, integer(attrs.get("coreCount"), 6)) / 24.0 * 44.0;
        double threads = Math.min(32.0, integer(attrs.get("threadCount"), 12)) / 32.0 * 20.0;
        String model = (cpu.name() + " " + text(attrs.get("cpuClass"))).toUpperCase(Locale.ROOT);
        double tier = model.contains("9950") || model.contains("7950") || model.contains("RYZEN 9") || model.contains("I9") || model.contains("ULTRA 9") ? 34.0
                : model.contains("9900") || model.contains("7900") || model.contains("RYZEN 7") || model.contains("I7") || model.contains("ULTRA 7") ? 27.0
                : model.contains("RYZEN 5") || model.contains("I5") ? 20.0
                : 14.0;
        return clamp(cores + threads + tier, 35.0, 100.0);
    }

    private static boolean isX3dCpu(ToolBuildPart cpu) {
        if (cpu == null) return false;
        Map<String, Object> attrs = attrs(cpu);
        String model = (cpu.name() + " " + text(attrs.get("cpuClass"))).toUpperCase(Locale.ROOT);
        return model.contains("X3D");
    }

    private static double x3dGamingBonus(ToolBuildPart cpu) {
        int cores = integer(attrs(cpu).get("coreCount"), 0);
        return cores >= 16 ? 3.0 : cores >= 12 ? 5.0 : 7.0;
    }

    private static double x3dProductivityPenalty(ToolBuildPart cpu) {
        int cores = integer(attrs(cpu).get("coreCount"), 0);
        return cores >= 16 ? 2.0 : cores >= 12 ? 5.0 : 9.0;
    }

    private static double gpuFallback(ToolBuildPart gpu) {
        if (gpu == null) return 0.0;
        Map<String, Object> attrs = attrs(gpu);
        String text = (gpu.name() + " " + text(attrs.get("gpuClass"))).toUpperCase(Locale.ROOT).replace(" ", "_");
        double tier = text.contains("5090") ? 100.0
                : text.contains("5080") ? 94.0
                : text.contains("5070_TI") || text.contains("5070TI") ? 84.0
                : text.contains("5070") ? 76.0
                : text.contains("5060_TI") || text.contains("5060TI") ? 64.0
                : text.contains("5060") ? 54.0
                : 48.0;
        int vram = integer(attrs.get("vramGb"), 0);
        if (vram >= 24) tier += 3.0;
        else if (vram > 0 && vram < 12) tier -= 5.0;
        return clamp(tier, 30.0, 100.0);
    }

    private static double ramScore(List<ToolBuildPart> ramParts) {
        if (ramParts.isEmpty()) return 0.0;
        int capacity = totalRamCapacityGb(ramParts);
        int maxSpeed = ramParts.stream().mapToInt(part -> integer(attrs(part).get("speedMhz"), 0)).max().orElse(0);
        boolean ddr5 = ramParts.stream().anyMatch(part -> "DDR5".equalsIgnoreCase(text(attrs(part).get("memoryType"))));
        int moduleCount = totalRamModuleCount(ramParts);
        double capacityScore = ramCapacityScore(capacity);
        double channelScore = moduleCount >= 2 ? 96 : 62;
        double generationScore = ddr5 ? 90.0 : 55.0;
        double speedScore = ramSpeedScore(maxSpeed, ddr5);
        return weightedAverage(List.of(capacityScore, channelScore, generationScore, speedScore), List.of(0.65, 0.15, 0.10, 0.10));
    }

    private static double storageScore(List<ToolBuildPart> storageParts) {
        if (storageParts.isEmpty()) return 0.0;
        ToolBuildPart best = storageParts.stream()
                .max(Comparator.comparingDouble(BuildCompositeScoreService::singleStorageScore))
                .orElse(storageParts.get(0));
        double bestScore = singleStorageScore(best);
        int totalCapacity = storageParts.stream()
                .mapToInt(part -> integer(attrs(part).get("capacityGb"), 0) * part.effectiveQuantity())
                .sum();
        double capacityBonus = totalCapacity >= 4000 ? 3.0 : totalCapacity >= 2000 ? 2.0 : totalCapacity >= 1000 ? 0.5 : 0.0;
        return clamp(bestScore + capacityBonus, 0.0, 100.0);
    }

    private static double singleStorageScore(ToolBuildPart storage) {
        Map<String, Object> attrs = attrs(storage);
        int capacity = integer(attrs.get("capacityGb"), 0);
        int generation = generation(attrs.get("generation"));
        int read = integer(attrs.get("readMbps"), 0);
        int write = integer(attrs.get("writeMbps"), 0);
        double capacityScore = capacity >= 4000 ? 100 : capacity >= 2000 ? 90 : capacity >= 1000 ? 76 : capacity >= 500 ? 60 : 45;
        double genScore = generation >= 5 ? 100 : generation == 4 ? 76 : generation == 3 ? 42 : 30;
        double readScore = read >= 12000 ? 100 : read >= 7000 ? 84 : read >= 5000 ? 72 : read >= 3500 ? 52 : read >= 2500 ? 42 : 30;
        double writeScore = write >= 10000 ? 100 : write >= 6000 ? 84 : write >= 4500 ? 72 : write >= 3000 ? 52 : write >= 2000 ? 42 : 30;
        double speedScore = read <= 0 && write <= 0 ? genScore : weightedAverage(List.of(readScore, writeScore), List.of(0.55, 0.45));
        return weightedAverage(List.of(capacityScore, genScore, speedScore), List.of(0.12, 0.48, 0.40));
    }

    private static double coolerScore(ToolBuildPart cpu, ToolBuildPart cooler) {
        if (cooler == null) return 45.0;
        Map<String, Object> attrs = attrs(cooler);
        int tdp = integer(attrs.get("tdpW"), 0);
        int radiator = firstPositiveInt(integer(attrs.get("radiatorLengthMm"), 0), integer(attrs.get("radiatorSizeMm"), 0));
        String type = text(attrs.get("coolerType")).toUpperCase(Locale.ROOT);
        int heatpipes = integer(attrs.get("heatpipeCount"), 0);
        int cpuTdp = integer(attrs(cpu).get("tdpW"), 0);
        double ratioScore = coolingRatioScore(cpuTdp, tdp);
        double classScore = coolerClassScore(type, radiator, heatpipes, tdp);
        double priceTierScore = coolerPriceTierScore(cooler.price());
        return weightedAverage(List.of(ratioScore, classScore, priceTierScore), List.of(0.65, 0.25, 0.10));
    }

    private static double psuUpgradeScore(ToolBuildPart psu, Map<String, Object> powerDetails) {
        if (psu == null) return 40.0;
        Map<String, Object> attrs = attrs(psu);
        int capacity = integer(attrs.get("capacityW"), integer(attrs.get("wattage"), 0));
        String efficiency = text(attrs.get("efficiency")).toUpperCase(Locale.ROOT);
        String atx = text(attrs.get("atxSpec")).toUpperCase(Locale.ROOT);
        String pcie = text(attrs.get("pcieSpec")).toUpperCase(Locale.ROOT);
        double capacityScore = psuCapacityFitScore(capacity, powerDetails);
        double specScore = atx.contains("3") || pcie.contains("5") || pcie.contains("12V") ? 92 : 65;
        double effScore = efficiency.contains("TITANIUM") || efficiency.contains("PLATINUM") ? 96 : efficiency.contains("GOLD") ? 82 : efficiency.contains("BRONZE") ? 60 : 55;
        return weightedAverage(List.of(capacityScore, specScore, effScore), List.of(0.62, 0.22, 0.16));
    }

    private static double psuCapacityFitScore(int capacity, Map<String, Object> powerDetails) {
        Double headroomValue = number(powerDetails.get("ratedHeadroomW"));
        Double loadValue = number(powerDetails.get("ratedLoadPercent"));
        if (headroomValue != null || loadValue != null) {
            int headroom = headroomValue == null ? 0 : headroomValue.intValue();
            int loadPercent = loadValue == null ? 100 : loadValue.intValue();
            return headroom >= 280 && loadPercent <= 70 ? 100.0
                    : headroom >= 220 && loadPercent <= 75 ? 97.0
                    : headroom >= 160 && loadPercent <= 80 ? 92.0
                    : headroom >= 120 && loadPercent <= 85 ? 68.0
                    : headroom >= 80 && loadPercent <= 88 ? 36.0
                    : headroom >= 60 ? 22.0
                    : 8.0;
        }
        return capacity >= 1200 ? 100 : capacity >= 1000 ? 94 : capacity >= 850 ? 72 : capacity >= 750 ? 58 : 42;
    }

    private static double motherboardUpgradeScore(ToolBuildPart board) {
        if (board == null) return 40.0;
        Map<String, Object> attrs = attrs(board);
        String chipset = text(attrs.get("chipset")).toUpperCase(Locale.ROOT);
        double chipsetScore = chipset.startsWith("X") || chipset.startsWith("Z") ? 94 : chipset.startsWith("B") ? 76 : chipset.startsWith("H") ? 55 : 62;
        double memory = "DDR5".equalsIgnoreCase(text(attrs.get("memoryType"))) ? 88 : 55;
        double wifi = bool(attrs.get("hasWifi")) || text(attrs.get("wifi")).toUpperCase(Locale.ROOT).contains("WI") ? 86 : 62;
        int pcie = generation(attrs.get("pcieGeneration"));
        double pcieScore = pcie >= 5 ? 95 : pcie == 4 ? 78 : 58;
        return weightedAverage(List.of(chipsetScore, memory, wifi, pcieScore), List.of(0.35, 0.25, 0.15, 0.25));
    }

    private static double caseUpgradeScore(ToolBuildPart pcCase) {
        if (pcCase == null) return 40.0;
        Map<String, Object> attrs = attrs(pcCase);
        int gpuClearance = integer(attrs.get("maxGpuLengthMm"), 0);
        int coolerClearance = integer(attrs.get("maxCpuCoolerHeightMm"), 0);
        Boolean airflowStrong = caseAirflowStrong(pcCase);
        double gpu = gpuClearance >= 420 ? 100 : gpuClearance >= 380 ? 88 : gpuClearance >= 340 ? 72 : gpuClearance >= 300 ? 56 : 38;
        double cooler = coolerClearance >= 180 ? 94 : coolerClearance >= 165 ? 80 : coolerClearance >= 155 ? 66 : 45;
        double airflow = airflowStrong == null ? 70 : airflowStrong ? 90 : 62;
        return weightedAverage(List.of(gpu, cooler, airflow), List.of(0.45, 0.30, 0.25));
    }

    private static double storageExpansionScore(List<ToolBuildPart> storageParts) {
        if (storageParts.isEmpty()) return 45.0;
        int totalCapacity = storageParts.stream()
                .mapToInt(part -> integer(attrs(part).get("capacityGb"), 0) * part.effectiveQuantity())
                .sum();
        return totalCapacity >= 4000 ? 96 : totalCapacity >= 2000 ? 84 : totalCapacity >= 1000 ? 70 : 52;
    }

    private static double clearanceScore(Map<String, Object> sizeDetails) {
        List<Double> scores = new ArrayList<>();
        addClearanceScore(scores, integer(sizeDetails.get("gpuLengthMm"), 0), integer(sizeDetails.get("maxGpuLengthMm"), 0), 20, 60);
        addClearanceScore(scores, integer(sizeDetails.get("coolerHeightMm"), 0), integer(sizeDetails.get("maxCpuCoolerHeightMm"), 0), 5, 20);
        addClearanceScore(scores, integer(sizeDetails.get("psuDepthMm"), 0), integer(sizeDetails.get("maxPsuLengthMm"), 0), 10, 40);
        if (scores.isEmpty()) return 70.0;
        return scores.stream().mapToDouble(Double::doubleValue).average().orElse(70.0);
    }

    private static void addClearanceScore(List<Double> scores, int actual, int limit, int warningMm, int goodMm) {
        if (actual <= 0 || limit <= 0) return;
        int headroom = limit - actual;
        if (headroom < 0) scores.add(0.0);
        else if (headroom < warningMm) scores.add(58.0);
        else if (headroom >= goodMm) scores.add(100.0);
        else scores.add(76.0);
    }

    private static double memoryBalanceScore(List<ToolBuildPart> ramParts) {
        if (ramParts.isEmpty()) return 0.0;
        int capacity = totalRamCapacityGb(ramParts);
        int moduleCount = totalRamModuleCount(ramParts);
        int speed = ramParts.stream().mapToInt(part -> integer(attrs(part).get("speedMhz"), 0)).max().orElse(0);
        boolean ddr5 = ramParts.stream().anyMatch(part -> "DDR5".equalsIgnoreCase(text(attrs(part).get("memoryType"))));
        double capacityScore = ramCapacityScore(capacity);
        double channelScore = moduleCount >= 2 ? 100.0 : 58.0;
        double speedScore = ramSpeedScore(speed, ddr5);
        double generationScore = ddr5 ? 90.0 : 55.0;
        return weightedAverage(List.of(capacityScore, channelScore, speedScore, generationScore), List.of(0.70, 0.18, 0.07, 0.05));
    }

    private static double storageBalanceScore(List<ToolBuildPart> storageParts) {
        if (storageParts.isEmpty()) return 0.0;
        int totalCapacity = storageParts.stream()
                .mapToInt(part -> integer(attrs(part).get("capacityGb"), 0) * part.effectiveQuantity())
                .sum();
        int bestGeneration = storageParts.stream().mapToInt(part -> generation(attrs(part).get("generation"))).max().orElse(0);
        double capacityScore = totalCapacity >= 4000 ? 100 : totalCapacity >= 2000 ? 92 : totalCapacity >= 1000 ? 78 : totalCapacity >= 500 ? 56 : 36;
        double generationBonus = bestGeneration >= 5 ? 8.0 : bestGeneration == 4 ? 0.0 : bestGeneration == 3 ? -18.0 : -24.0;
        return clamp(capacityScore + generationBonus, 20.0, 100.0);
    }

    private static double ramCapacityScore(int capacityGb) {
        if (capacityGb >= 128) return 100.0;
        if (capacityGb >= 64) return 94.0;
        if (capacityGb >= 32) return 78.0;
        if (capacityGb >= 16) return 52.0;
        if (capacityGb >= 8) return 32.0;
        if (capacityGb >= 4) return 18.0;
        return 10.0;
    }

    private static double ramSpeedScore(int speedMhz, boolean ddr5) {
        if (speedMhz <= 0) return ddr5 ? 72.0 : 55.0;
        if (speedMhz >= 7200) return 100.0;
        if (speedMhz >= 6400) return 90.0;
        if (speedMhz >= 6000) return 82.0;
        if (speedMhz >= 5600) return 72.0;
        if (speedMhz >= 4800) return 62.0;
        return 50.0;
    }

    private static int totalRamCapacityGb(List<ToolBuildPart> ramParts) {
        return ramParts.stream()
                .mapToInt(part -> integer(attrs(part).get("capacityGb"), 0) * part.effectiveQuantity())
                .sum();
    }

    private static int totalRamModuleCount(List<ToolBuildPart> ramParts) {
        return ramParts.stream()
                .mapToInt(part -> Math.max(1, integer(attrs(part).get("moduleCount"), 1)) * part.effectiveQuantity())
                .sum();
    }

    private static double thermalBalanceScore(ToolBuildPart cpu, ToolBuildPart cooler) {
        if (cooler == null) return 40.0;
        int cpuTdp = integer(attrs(cpu).get("tdpW"), 0);
        int coolerTdp = integer(attrs(cooler).get("tdpW"), 0);
        if (cpuTdp <= 0 || coolerTdp <= 0) {
            return coolerScore(cpu, cooler);
        }
        double ratio = coolerTdp / (double) cpuTdp;
        if (ratio >= 1.65) return 100.0;
        if (ratio >= 1.45) return 92.0;
        if (ratio >= 1.30) return 78.0;
        if (ratio >= 1.15) return 55.0;
        if (ratio >= 1.05) return 35.0;
        return 10.0;
    }

    private static double coolingRatioScore(int cpuTdp, int coolerTdp) {
        if (cpuTdp <= 0 || coolerTdp <= 0) {
            return coolerTdp >= 280 ? 96.0 : coolerTdp >= 240 ? 84.0 : coolerTdp >= 180 ? 62.0 : 45.0;
        }
        double ratio = coolerTdp / (double) cpuTdp;
        if (ratio >= 1.80) return 100.0;
        if (ratio >= 1.60) return 94.0;
        if (ratio >= 1.40) return 82.0;
        if (ratio >= 1.25) return 65.0;
        if (ratio >= 1.10) return 40.0;
        return 18.0;
    }

    private static double coolerClassScore(String type, int radiator, int heatpipes, int tdp) {
        if (type.contains("LIQUID")) {
            return radiator >= 360 ? 98.0 : radiator >= 280 ? 90.0 : radiator >= 240 ? 82.0 : 70.0;
        }
        if (heatpipes >= 7 || tdp >= 260) return 88.0;
        if (heatpipes >= 6 || tdp >= 220) return 76.0;
        if (tdp >= 180) return 58.0;
        return 42.0;
    }

    private static double coolerPriceTierScore(Integer price) {
        int won = price == null ? 0 : price;
        if (won <= 0) return 70.0;
        if (won < 90_000) return 55.0;
        if (won < 200_000) return 76.0;
        if (won < 400_000) return 88.0;
        return 84.0;
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

    private static ToolBuildPart first(Map<String, List<ToolBuildPart>> byCategory, String category) {
        List<ToolBuildPart> parts = byCategory.get(category);
        return parts == null || parts.isEmpty() ? null : parts.get(0);
    }

    private static Map<String, Object> attrs(ToolBuildPart part) {
        return part == null || part.attributes() == null ? Map.of() : part.attributes();
    }

    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
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

    private static boolean bool(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return false;
        return Boolean.parseBoolean(value.toString());
    }

    private static double firstPositive(Double primary, double fallback) {
        return primary != null && primary > 0 ? primary : fallback;
    }

    private static int firstPositiveInt(int primary, int fallback) {
        return primary > 0 ? primary : fallback;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double weightedAverage(List<Double> values, List<Double> weights) {
        double sum = 0.0;
        double weightSum = 0.0;
        for (int i = 0; i < values.size(); i++) {
            double value = values.get(i);
            double weight = weights.get(i);
            sum += clamp(value, 0.0, 100.0) * weight;
            weightSum += weight;
        }
        return weightSum <= 0 ? 0.0 : sum / weightSum;
    }

    private record Component(String key, String label, int score, int maxScore, String summary) {
        double normalized() {
            return maxScore <= 0 ? 0.0 : score * 100.0 / maxScore;
        }

        Map<String, Object> toMap() {
            return MockData.map(
                    "key", key,
                    "label", label,
                    "score", score,
                    "maxScore", maxScore,
                    "percent", Math.round(normalized()),
                    "summary", summary
            );
        }
    }

    private record CpuScoreProfile(double gamingRaw, double productivityRaw, double blendedRaw, boolean x3d, int coreCount) {
        boolean endgameCapable() {
            return blendedRaw >= 98.5 || (x3d && coreCount >= 16 && gamingRaw >= 99.5 && productivityRaw >= 90.0);
        }
    }

    private record BoardProfile(boolean premium, boolean modernMainstream, boolean mainstream, boolean entry) {
    }
}
