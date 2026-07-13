package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.ToolBuildPart;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BuildScoreAdviceServiceTest {
    private final BuildScoreAdviceService service = new BuildScoreAdviceService();

    @Test
    void recommendsGpuFirstWhenTopCpuIsPairedWithLowerTierGpu() {
        Map<String, Object> assessment = service.assess(
                List.of(part("CPU"), part("GPU")),
                toolResults(97, 54),
                score(742),
                null,
                null
        );

        assertThat(codes(assessment, "cautions")).contains("CPU_GPU_IMBALANCE");
        assertThat(codes(assessment, "strengths")).contains("HIGH_CPU_TIER");
        assertThat(recommendations(assessment).get(0))
                .containsEntry("category", "GPU")
                .containsEntry("title", "GPU 상향 우선");
    }

    @Test
    void recommendsCpuFirstWhenTopGpuIsPairedWithLowerTierCpu() {
        Map<String, Object> assessment = service.assess(
                List.of(part("CPU"), part("GPU")),
                toolResults(62, 100),
                score(710),
                null,
                null
        );

        assertThat(codes(assessment, "cautions")).contains("CPU_GPU_IMBALANCE");
        assertThat(recommendations(assessment).get(0)).containsEntry("category", "CPU");
    }

    @Test
    void blockingFailureExplainsZeroBeforeGeneralUpgradeAdvice() {
        Map<String, Object> assessment = service.assess(
                List.of(part("CPU"), part("MOTHERBOARD")),
                List.of(tool("compatibility", "FAIL", "CPU 소켓과 메인보드 소켓이 일치하지 않습니다.", Map.of())),
                MockData.map(
                        "score", 0,
                        "maxScore", 1000,
                        "grade", "F",
                        "label", "장착 불가",
                        "summary", "호환성 검증 실패",
                        "caps", List.of(MockData.map(
                                "code", "COMPATIBILITY_FAIL",
                                "maxScore", 0,
                                "reason", "CPU 소켓과 메인보드 소켓이 일치하지 않습니다."
                        )),
                        "components", List.of(),
                        "missingCategories", List.of()
                ),
                null,
                null
        );

        assertThat(assessment).containsEntry("score", 0);
        assertThat(cautions(assessment).get(0))
                .containsEntry("code", "COMPATIBILITY_FAIL")
                .containsEntry("severity", "FAIL");
        assertThat(String.valueOf(assessment.get("summary"))).contains("소켓");
    }

    @Test
    void missingBenchmarkIsReportedAsEvidenceGapInsteadOfInventedImbalance() {
        Map<String, Object> assessment = service.assess(
                List.of(part("CPU"), part("GPU")),
                List.of(
                        tool("compatibility", "PASS", "호환 가능합니다.", Map.of()),
                        tool("power", "PASS", "파워 여유가 있습니다.", Map.of("ratedHeadroomW", 200)),
                        tool("size", "PASS", "장착 가능합니다.", Map.of()),
                        tool("performance", "PASS", "확인 가능한 성능 근거입니다.", Map.of())
                ),
                score(680),
                null,
                null
        );

        assertThat(codes(assessment, "cautions"))
                .contains("EVIDENCE_INSUFFICIENT")
                .doesNotContain("CPU_GPU_IMBALANCE");
    }

    @Test
    void issueFocusMovesMatchingToolAdviceAheadWithoutChangingFacts() {
        Map<String, Object> assessment = service.assess(
                List.of(part("CPU"), part("GPU"), part("PSU")),
                List.of(
                        tool("performance", "WARN", "CPU와 GPU 체급 차이가 큽니다.", Map.of("cpuBenchmarkScore", 97, "gpuBenchmarkScore", 54)),
                        tool("power", "WARN", "파워 여유가 낮습니다.", Map.of("ratedHeadroomW", 80, "ratedLoadPercent", 88))
                ),
                score(620),
                "PSU",
                "power"
        );

        assertThat(cautions(assessment).get(0)).containsEntry("code", "LOW_POWER_HEADROOM");
        assertThat(codes(assessment, "cautions")).contains("CPU_GPU_IMBALANCE");
    }

    @ParameterizedTest
    @MethodSource("scoreCapRecommendations")
    void scoreCapsPointToTheCategoryThatCanResolveThem(String capCode, String expectedCategory) {
        Map<String, Object> cappedScore = score(650);
        cappedScore.put("caps", List.of(MockData.map(
                "code", capCode,
                "maxScore", 700,
                "reason", "현재 구성에서 확인된 점수 제한 사유입니다."
        )));

        Map<String, Object> assessment = service.assess(
                completeParts(),
                passingToolResults(88, 88),
                cappedScore,
                null,
                null
        );

        assertThat(codes(assessment, "cautions")).contains(capCode);
        assertThat(recommendations(assessment).get(0)).containsEntry("category", expectedCategory);
    }

    @Test
    void balancedBuildDoesNotInventAnUrgentWeakness() {
        Map<String, Object> assessment = service.assess(
                completeParts(),
                passingToolResults(92, 91),
                score(860),
                null,
                null
        );

        assertThat(codes(assessment, "cautions"))
                .doesNotContain("CPU_GPU_IMBALANCE", "EVIDENCE_INSUFFICIENT");
        assertThat(String.valueOf(assessment.get("summary"))).isEqualTo("현재 구성 평가");
    }

    private static Map<String, Object> score(int value) {
        return MockData.map(
                "score", value,
                "maxScore", 1000,
                "grade", "C",
                "label", "기본형",
                "summary", "현재 구성 평가",
                "caps", List.of(),
                "components", List.of(MockData.map("key", "evidence", "percent", 100)),
                "missingCategories", List.of()
        );
    }

    private static List<Map<String, Object>> toolResults(double cpu, double gpu) {
        return List.of(
                tool("compatibility", "PASS", "호환 가능합니다.", Map.of()),
                tool("power", "PASS", "파워 여유가 있습니다.", Map.of("ratedHeadroomW", 200, "ratedLoadPercent", 70)),
                tool("size", "PASS", "장착 가능합니다.", Map.of()),
                tool("performance", "WARN", "CPU와 GPU 체급 차이가 큽니다.", Map.of(
                        "cpuBenchmarkScore", cpu,
                        "gpuBenchmarkScore", gpu
                ))
        );
    }

    private static List<Map<String, Object>> passingToolResults(double cpu, double gpu) {
        return List.of(
                tool("compatibility", "PASS", "호환 가능합니다.", Map.of()),
                tool("power", "PASS", "파워 여유가 있습니다.", Map.of("ratedHeadroomW", 220, "ratedLoadPercent", 70)),
                tool("size", "PASS", "장착 가능합니다.", Map.of()),
                tool("performance", "PASS", "성능 균형이 적절합니다.", Map.of(
                        "cpuBenchmarkScore", cpu,
                        "gpuBenchmarkScore", gpu
                ))
        );
    }

    private static List<ToolBuildPart> completeParts() {
        return List.of(
                part("CPU", Map.of("tdpW", 120)),
                part("MOTHERBOARD", Map.of()),
                part("RAM", Map.of("moduleCount", 2, "capacityGb", 32)),
                part("GPU", Map.of()),
                part("STORAGE", Map.of("generation", "PCIe 4.0")),
                part("PSU", Map.of()),
                part("CASE", Map.of()),
                part("COOLER", Map.of("tdpW", 250))
        );
    }

    private static Stream<Arguments> scoreCapRecommendations() {
        return Stream.of(
                Arguments.of("LOW_POWER_HEADROOM", "PSU"),
                Arguments.of("LOW_MEMORY_TIER", "RAM"),
                Arguments.of("LOW_STORAGE_TIER", "STORAGE"),
                Arguments.of("LOW_COOLING_HEADROOM", "COOLER"),
                Arguments.of("LOW_MOTHERBOARD_TIER", "MOTHERBOARD"),
                Arguments.of("LOW_CASE_CLEARANCE", "CASE"),
                Arguments.of("LOW_CASE_AIRFLOW", "CASE")
        );
    }

    private static Map<String, Object> tool(String name, String status, String summary, Map<String, Object> details) {
        return MockData.map("tool", name, "status", status, "confidence", "HIGH", "summary", summary, "details", details);
    }

    private static ToolBuildPart part(String category) {
        return part(category, Map.of());
    }

    private static ToolBuildPart part(String category, Map<String, Object> attributes) {
        return new ToolBuildPart(1L, category + "-id", category, category + " part", "BuildGraph", 100_000, attributes, 1);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cautions(Map<String, Object> assessment) {
        return (List<Map<String, Object>>) assessment.get("cautions");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> recommendations(Map<String, Object> assessment) {
        return (List<Map<String, Object>>) assessment.get("recommendations");
    }

    private static List<String> codes(Map<String, Object> assessment, String key) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) assessment.get(key);
        return items.stream().map(item -> String.valueOf(item.get("code"))).toList();
    }
}
