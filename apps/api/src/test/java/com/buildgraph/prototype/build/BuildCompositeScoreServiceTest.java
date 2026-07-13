package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.ToolBuildPart;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildCompositeScoreServiceTest {
    private final BuildCompositeScoreService service = new BuildCompositeScoreService();

    @Test
    void representativeCasesProduceConcreteScoresAndBlockInvalidBuilds() throws IOException {
        List<CaseScore> cases = List.of(
                score("끝판왕 800만원급", endgameParts(), standardTools(100, 100, "PASS", 360, 58, relaxedFit()), 8_000_000, 7_850_000),
                score("X3D 8코어 게임 특화 CPU", x3dGamingCpuParts(), standardTools(92, 100, "PASS", 340, 60, relaxedFit()), 8_000_000, 7_320_000),
                score("16코어 작업형 CPU", workstationCpuParts(), standardTools(96, 100, "PASS", 340, 60, relaxedFit()), 8_000_000, 7_650_000),
                score("RTX 5080 고성능 게이밍", highGamingParts(), standardTools(90, 95, "PASS", 240, 64, relaxedFit()), 5_000_000, 5_170_000),
                score("QHD 균형형 5070 Ti", balancedParts(), standardTools(84, 84, "PASS", 170, 72, normalFit()), 3_000_000, 2_930_000),
                score("가성비 5060 Ti", budgetParts(), standardTools(70, 64, "PASS", 120, 74, normalFit()), 1_800_000, 1_760_000),
                score("800만원 요청에 1200만원 구성", endgameParts(), standardTools(100, 100, "FAIL", 360, 58, relaxedFit()), 8_000_000, 12_000_000),
                score("소켓 불일치", incompatibleSocketParts(), blockingTools("compatibility"), 5_000_000, 4_000_000),
                score("파워 부족", powerFailParts(), blockingTools("power"), 5_000_000, 4_300_000),
                score("케이스 장착 불가", sizeFailParts(), blockingTools("size"), 5_000_000, 4_400_000),
                score("장착 가능하지만 여유 좁음", tightFitParts(), standardTools(92, 100, "PASS", 120, 79, tightFit()), 8_000_000, 7_600_000),
                score("CPU만 중급으로 낮춤", cpuDowngradeParts(), standardTools(70, 100, "PASS", 300, 62, relaxedFit()), 8_000_000, 7_010_000),
                score("RAM만 32GB 듀얼로 낮춤", ram32DualParts(), standardTools(100, 100, "PASS", 350, 59, relaxedFit()), 8_000_000, 7_650_000),
                score("RAM만 16GB 듀얼로 낮춤", ram16DualParts(), standardTools(100, 100, "PASS", 350, 59, relaxedFit()), 8_000_000, 7_610_000),
                score("RAM만 16GB 단일로 낮춤", ramDowngradeParts(), standardTools(100, 100, "PASS", 350, 59, relaxedFit()), 8_000_000, 7_600_000),
                score("SSD만 PCIe 4.0 1TB로 낮춤", storageMidrangeParts(), standardTools(100, 100, "PASS", 355, 59, relaxedFit()), 8_000_000, 7_590_000),
                score("SSD만 보급형으로 낮춤", storageDowngradeParts(), standardTools(100, 100, "PASS", 355, 59, relaxedFit()), 8_000_000, 7_520_000),
                score("PSU만 1000W로 여유 중간", psuMidrangeParts(), standardTools(100, 100, "PASS", 170, 78, relaxedFit()), 8_000_000, 7_650_000),
                score("PSU만 850W로 여유 낮춤", psuDowngradeParts(), standardTools(100, 100, "PASS", 90, 84, relaxedFit()), 8_000_000, 7_570_000),
                score("메인보드만 B650급으로 낮춤", motherboardDowngradeParts(), standardTools(100, 100, "PASS", 360, 58, relaxedFit()), 8_000_000, 7_430_000),
                score("메인보드만 B850급으로 낮춤", motherboardModernMainstreamParts(), standardTools(100, 100, "PASS", 360, 58, relaxedFit()), 8_000_000, 7_540_000),
                score("케이스만 airflow 명시 약함", caseWeakAirflowParts(), standardTools(100, 100, "PASS", 360, 58, relaxedFit()), 8_000_000, 7_560_000),
                score("케이스 airflow 근거 없음", caseUnknownAirflowParts(), standardTools(100, 100, "PASS", 360, 58, relaxedFit()), 8_000_000, 7_560_000),
                score("쿨러만 240 AIO로 낮춤", cooler240AioParts(), standardTools(100, 100, "PASS", 360, 58, relaxedFit()), 8_000_000, 7_670_000),
                score("쿨러만 상급 공랭으로 낮춤", coolerPremiumAirParts(), standardTools(100, 100, "PASS", 360, 58, relaxedFit()), 8_000_000, 7_650_000),
                score("쿨러만 기본 공랭으로 낮춤", coolerDowngradeParts(), standardTools(100, 100, "PASS", 360, 58, relaxedFit()), 8_000_000, 7_610_000)
        );

        assertThat(cases)
                .filteredOn(item -> item.name().contains("불일치") || item.name().contains("부족") || item.name().contains("불가"))
                .allSatisfy(item -> assertThat(item.score()).isZero());
        assertThat(caseByName(cases, "장착 가능하지만 여유 좁음").score()).isGreaterThan(0);
        CaseScore endgame = caseByName(cases, "끝판왕 800만원급");
        CaseScore x3dGaming = caseByName(cases, "X3D 8코어 게임 특화 CPU");
        CaseScore workstation = caseByName(cases, "16코어 작업형 CPU");
        CaseScore ram32Dual = caseByName(cases, "RAM만 32GB 듀얼로 낮춤");
        CaseScore ram16Dual = caseByName(cases, "RAM만 16GB 듀얼로 낮춤");
        CaseScore ram16Single = caseByName(cases, "RAM만 16GB 단일로 낮춤");
        CaseScore storageMidrange = caseByName(cases, "SSD만 PCIe 4.0 1TB로 낮춤");
        CaseScore storageDowngrade = caseByName(cases, "SSD만 보급형으로 낮춤");
        CaseScore psuMidrange = caseByName(cases, "PSU만 1000W로 여유 중간");
        CaseScore psuDowngrade = caseByName(cases, "PSU만 850W로 여유 낮춤");
        CaseScore motherboardB650 = caseByName(cases, "메인보드만 B650급으로 낮춤");
        CaseScore motherboardB850 = caseByName(cases, "메인보드만 B850급으로 낮춤");
        CaseScore weakAirflowCase = caseByName(cases, "케이스만 airflow 명시 약함");
        CaseScore unknownAirflowCase = caseByName(cases, "케이스 airflow 근거 없음");
        CaseScore cooler240Aio = caseByName(cases, "쿨러만 240 AIO로 낮춤");
        CaseScore coolerPremiumAir = caseByName(cases, "쿨러만 상급 공랭으로 낮춤");
        CaseScore coolerBasicAir = caseByName(cases, "쿨러만 기본 공랭으로 낮춤");
        assertThat(componentScore(x3dGaming, "performance")).isLessThan(componentScore(endgame, "performance"));
        assertThat(componentScore(workstation, "performance")).isGreaterThanOrEqualTo(componentScore(x3dGaming, "performance"));
        assertThat(componentScore(workstation, "performance") - componentScore(x3dGaming, "performance")).isGreaterThanOrEqualTo(20);
        assertThat(ram32Dual.score()).isGreaterThan(ram16Dual.score());
        assertThat(ram16Dual.score()).isGreaterThan(ram16Single.score());
        assertThat(ram16Dual.score()).isLessThanOrEqualTo(880);
        assertThat(ram16Single.score()).isLessThanOrEqualTo(860);
        assertThat(storageMidrange.score()).isLessThan(endgame.score());
        assertThat(storageDowngrade.score()).isLessThan(storageMidrange.score());
        assertThat(storageMidrange.score()).isLessThanOrEqualTo(929);
        assertThat(storageDowngrade.score()).isLessThanOrEqualTo(900);
        assertThat(psuMidrange.score()).isLessThan(endgame.score());
        assertThat(psuDowngrade.score()).isLessThan(psuMidrange.score());
        assertThat(psuDowngrade.score()).isLessThanOrEqualTo(920);
        assertThat(motherboardB650.score()).isLessThan(endgame.score());
        assertThat(motherboardB650.score()).isLessThanOrEqualTo(929);
        assertThat(motherboardB850.score()).isGreaterThan(motherboardB650.score());
        assertThat(motherboardB850.score()).isLessThanOrEqualTo(965);
        assertThat(weakAirflowCase.score()).isLessThan(endgame.score());
        assertThat(weakAirflowCase.score()).isLessThanOrEqualTo(929);
        assertThat(unknownAirflowCase.score()).isGreaterThan(weakAirflowCase.score());
        assertThat(componentScore(unknownAirflowCase, "upgrade"))
                .as("미등록 airflow는 확인 가능한 장착 여유 점수에 임의 감점을 만들지 않는다")
                .isGreaterThanOrEqualTo(componentScore(endgame, "upgrade"));
        assertThat(cooler240Aio.score()).isLessThan(endgame.score());
        assertThat(coolerPremiumAir.score()).isLessThanOrEqualTo(cooler240Aio.score());
        assertThat(coolerBasicAir.score()).isLessThan(coolerPremiumAir.score());
        assertThat(coolerBasicAir.score()).isLessThanOrEqualTo(930);
        writeReport(cases);
    }

    @Test
    void exactPsuFitProducesNumericClearanceReasonWithoutClaimingAirflowShortage() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(5, part(106, "psu-1500-depth-200", "PSU", "1500W PSU", 520_000,
                attrs("capacityW", 1500, "depthMm", 200, "efficiency", "PLATINUM", "atxSpec", "ATX 3.1")));
        parts.set(6, part(107, "case-psu-exact-fit", "CASE", "Airflow Case With Exact PSU Fit", 330_000,
                attrs("maxGpuLengthMm", 459, "maxCpuCoolerHeightMm", 185, "maxPsuLengthMm", 200,
                        "frontMesh", true, "airflowFocus", true)));
        Map<String, Object> result = service.score(
                parts,
                standardTools(100, 100, "PASS", 700, 52,
                        MockData.map("gpuLengthMm", 340, "maxGpuLengthMm", 459,
                                "psuDepthMm", 200, "maxPsuLengthMm", 200)),
                8_000_000,
                7_900_000
        );

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> caps = (List<Map<String, Object>>) result.get("caps");
        Map<String, Object> caseCap = caps.stream()
                .filter(cap -> "LOW_CASE_CLEARANCE".equals(cap.get("code")))
                .findFirst()
                .orElseThrow();
        assertThat(caseCap.get("maxScore")).isEqualTo(880);
        assertThat(caseCap.get("reason").toString())
                .contains("파워", "200mm", "0mm")
                .doesNotContain("airflow", "통풍");
    }

    private CaseScore score(String name, List<ToolBuildPart> parts, List<Map<String, Object>> tools, int budget, int total) {
        Map<String, Object> result = service.score(parts, tools, budget, total);
        return new CaseScore(name, number(result.get("score")), number(result.get("rawScore")), text(result.get("grade")), text(result.get("label")), castList(result.get("components")), castList(result.get("caps")), castMap(result.get("requestFit")));
    }

    private static List<Map<String, Object>> standardTools(double cpuScore, double gpuScore, String priceStatus, int powerHeadroom, int loadPercent, Map<String, Object> sizeDetails) {
        return List.of(
                tool("compatibility", "PASS", Map.of("socketMatched", true, "memoryTypeMatched", true, "coolerSocketMatched", true)),
                tool("power", "PASS", MockData.map("ratedHeadroomW", powerHeadroom, "ratedLoadPercent", loadPercent)),
                tool("size", text(sizeDetails.get("status")).isBlank() ? "PASS" : text(sizeDetails.get("status")), sizeDetails),
                tool("performance", "PASS", MockData.map("cpuBenchmarkScore", cpuScore, "gpuBenchmarkScore", gpuScore, "gameFpsEvidence", List.of(Map.of("gameTitle", "PUBG")))),
                tool("price", priceStatus, Map.of())
        );
    }

    private static List<Map<String, Object>> blockingTools(String failTool) {
        return List.of(
                tool("compatibility", "compatibility".equals(failTool) ? "FAIL" : "PASS", Map.of()),
                tool("power", "power".equals(failTool) ? "FAIL" : "PASS", Map.of()),
                tool("size", "size".equals(failTool) ? "FAIL" : "PASS", Map.of()),
                tool("performance", "PASS", MockData.map("cpuBenchmarkScore", 96, "gpuBenchmarkScore", 100)),
                tool("price", "PASS", Map.of())
        );
    }

    private static Map<String, Object> relaxedFit() {
        return MockData.map("gpuLengthMm", 340, "maxGpuLengthMm", 430, "coolerHeightMm", 0, "maxCpuCoolerHeightMm", 0, "psuDepthMm", 160, "maxPsuLengthMm", 230);
    }

    private static Map<String, Object> normalFit() {
        return MockData.map("gpuLengthMm", 310, "maxGpuLengthMm", 370, "coolerHeightMm", 155, "maxCpuCoolerHeightMm", 175, "psuDepthMm", 150, "maxPsuLengthMm", 210);
    }

    private static Map<String, Object> tightFit() {
        return MockData.map("status", "WARN", "gpuLengthMm", 340, "maxGpuLengthMm", 350, "coolerHeightMm", 168, "maxCpuCoolerHeightMm", 172, "psuDepthMm", 170, "maxPsuLengthMm", 182);
    }

    private static Map<String, Object> attrs(Object... pairs) {
        return MockData.map(pairs);
    }

    private static ToolBuildPart part(long id, String publicId, String category, String name, int price, Map<String, Object> attrs) {
        return new ToolBuildPart(id, publicId, category, name, "BuildGraph", price, attrs, 1);
    }

    private static List<ToolBuildPart> endgameParts() {
        return List.of(
                part(1, "cpu-9950x3d", "CPU", "AMD Ryzen 9 9950X3D", 1_050_000, attrs("socket", "AM5", "coreCount", 16, "threadCount", 32, "tdpW", 170, "cpuClass", "RYZEN_9_X3D")),
                part(2, "board-x870", "MOTHERBOARD", "X870E Wi-Fi", 700_000, attrs("socket", "AM5", "chipset", "X870E", "memoryType", "DDR5", "pcieGeneration", 5, "hasWifi", true)),
                part(3, "ram-64", "RAM", "DDR5 64GB 6400", 360_000, attrs("memoryType", "DDR5", "capacityGb", 64, "moduleCount", 2, "speedMhz", 6400)),
                part(4, "gpu-5090", "GPU", "RTX 5090", 4_200_000, attrs("gpuClass", "RTX_5090", "vramGb", 32, "wattage", 575, "requiredSystemPowerW", 1000, "lengthMm", 340)),
                part(5, "ssd-pcie5", "STORAGE", "PCIe 5.0 2TB SSD", 420_000, attrs("capacityGb", 2000, "generation", 5, "readMbps", 12000, "writeMbps", 10000)),
                part(6, "psu-1200", "PSU", "1200W Platinum ATX 3.1", 460_000, attrs("capacityW", 1200, "efficiency", "PLATINUM", "atxSpec", "ATX 3.1", "pcieSpec", "PCIe 5.1")),
                part(7, "case-large", "CASE", "Large Airflow Case", 350_000, attrs("maxGpuLengthMm", 430, "maxCpuCoolerHeightMm", 185, "maxPsuLengthMm", 230, "frontMesh", true, "airflowFocus", true)),
                part(8, "cooler-360", "COOLER", "360mm Liquid Cooler", 310_000, attrs("coolerType", "LIQUID", "tdpW", 320, "radiatorLengthMm", 360))
        );
    }

    private static List<ToolBuildPart> highGamingParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(0, part(11, "cpu-9700x", "CPU", "AMD Ryzen 7 9700X", 500_000, attrs("socket", "AM5", "coreCount", 8, "threadCount", 16, "tdpW", 65, "cpuClass", "RYZEN_7")));
        parts.set(3, part(14, "gpu-5080", "GPU", "RTX 5080", 2_050_000, attrs("gpuClass", "RTX_5080", "vramGb", 16, "wattage", 360, "requiredSystemPowerW", 850, "lengthMm", 320)));
        parts.set(5, part(16, "psu-1000", "PSU", "1000W Gold ATX 3.1", 260_000, attrs("capacityW", 1000, "efficiency", "GOLD", "atxSpec", "ATX 3.1", "pcieSpec", "PCIe 5.1")));
        return parts;
    }

    private static List<ToolBuildPart> x3dGamingCpuParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(0, part(91, "cpu-7800x3d", "CPU", "AMD Ryzen 7 7800X3D", 520_000, attrs("socket", "AM5", "coreCount", 8, "threadCount", 16, "tdpW", 120, "cpuClass", "RYZEN_7_X3D")));
        return parts;
    }

    private static List<ToolBuildPart> workstationCpuParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(0, part(92, "cpu-9950x", "CPU", "AMD Ryzen 9 9950X", 880_000, attrs("socket", "AM5", "coreCount", 16, "threadCount", 32, "tdpW", 170, "cpuClass", "RYZEN_9")));
        return parts;
    }

    private static List<ToolBuildPart> balancedParts() {
        List<ToolBuildPart> parts = new ArrayList<>(highGamingParts());
        parts.set(3, part(24, "gpu-5070ti", "GPU", "RTX 5070 Ti", 1_250_000, attrs("gpuClass", "RTX_5070_TI", "vramGb", 16, "wattage", 300, "requiredSystemPowerW", 750, "lengthMm", 310)));
        parts.set(5, part(26, "psu-850", "PSU", "850W Gold ATX 3.0", 180_000, attrs("capacityW", 850, "efficiency", "GOLD", "atxSpec", "ATX 3.0", "pcieSpec", "PCIe 5.0")));
        return parts;
    }

    private static List<ToolBuildPart> budgetParts() {
        List<ToolBuildPart> parts = new ArrayList<>(balancedParts());
        parts.set(0, part(31, "cpu-7500f", "CPU", "AMD Ryzen 5 7500F", 210_000, attrs("socket", "AM5", "coreCount", 6, "threadCount", 12, "tdpW", 65, "cpuClass", "RYZEN_5")));
        parts.set(2, part(33, "ram-32", "RAM", "DDR5 32GB 5600", 130_000, attrs("memoryType", "DDR5", "capacityGb", 32, "moduleCount", 2, "speedMhz", 5600)));
        parts.set(3, part(34, "gpu-5060ti", "GPU", "RTX 5060 Ti", 650_000, attrs("gpuClass", "RTX_5060_TI", "vramGb", 16, "wattage", 180, "requiredSystemPowerW", 650, "lengthMm", 280)));
        parts.set(5, part(36, "psu-750", "PSU", "750W Gold", 120_000, attrs("capacityW", 750, "efficiency", "GOLD")));
        return parts;
    }

    private static List<ToolBuildPart> incompatibleSocketParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(1, part(42, "board-z890", "MOTHERBOARD", "Z890 DDR5 Board", 550_000, attrs("socket", "LGA1851", "chipset", "Z890", "memoryType", "DDR5")));
        return parts;
    }

    private static List<ToolBuildPart> powerFailParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(5, part(56, "psu-650", "PSU", "650W Bronze", 90_000, attrs("capacityW", 650, "efficiency", "BRONZE")));
        return parts;
    }

    private static List<ToolBuildPart> sizeFailParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(6, part(67, "case-small", "CASE", "Small Case", 120_000, attrs("maxGpuLengthMm", 300, "maxCpuCoolerHeightMm", 150, "maxPsuLengthMm", 160)));
        return parts;
    }

    private static List<ToolBuildPart> tightFitParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(6, part(77, "case-tight", "CASE", "Tight Airflow Case", 220_000, attrs("maxGpuLengthMm", 350, "maxCpuCoolerHeightMm", 172, "maxPsuLengthMm", 182, "frontMesh", true)));
        return parts;
    }

    private static List<ToolBuildPart> caseWeakAirflowParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(6, part(78, "case-closed", "CASE", "Closed Panel Case", 300_000, attrs("maxGpuLengthMm", 430, "maxCpuCoolerHeightMm", 185, "maxPsuLengthMm", 230, "frontMesh", false, "airflowFocus", false)));
        return parts;
    }

    private static List<ToolBuildPart> caseUnknownAirflowParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(6, part(79, "case-unknown-airflow", "CASE", "Large Case", 300_000, attrs("maxGpuLengthMm", 430, "maxCpuCoolerHeightMm", 185, "maxPsuLengthMm", 230)));
        return parts;
    }

    private static List<ToolBuildPart> cpuDowngradeParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(0, part(81, "cpu-7500f-with-5090", "CPU", "AMD Ryzen 5 7500F", 210_000, attrs("socket", "AM5", "coreCount", 6, "threadCount", 12, "tdpW", 65, "cpuClass", "RYZEN_5")));
        return parts;
    }

    private static List<ToolBuildPart> ramDowngradeParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(2, part(82, "ram-16-single", "RAM", "DDR5 16GB 5200 단일", 80_000, attrs("memoryType", "DDR5", "capacityGb", 16, "moduleCount", 1, "speedMhz", 5200)));
        return parts;
    }

    private static List<ToolBuildPart> ram16DualParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(2, part(87, "ram-16-dual", "RAM", "DDR5 16GB 5600 듀얼", 95_000, attrs("memoryType", "DDR5", "capacityGb", 16, "moduleCount", 2, "speedMhz", 5600)));
        return parts;
    }

    private static List<ToolBuildPart> ram32DualParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(2, part(88, "ram-32-dual", "RAM", "DDR5 32GB 6000 듀얼", 160_000, attrs("memoryType", "DDR5", "capacityGb", 32, "moduleCount", 2, "speedMhz", 6000)));
        return parts;
    }

    private static List<ToolBuildPart> storageDowngradeParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(4, part(83, "ssd-pcie3-500", "STORAGE", "PCIe 3.0 500GB SSD", 90_000, attrs("capacityGb", 500, "generation", 3, "readMbps", 3500, "writeMbps", 3000)));
        return parts;
    }

    private static List<ToolBuildPart> storageMidrangeParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(4, part(93, "ssd-pcie4-1tb", "STORAGE", "PCIe 4.0 1TB SSD", 150_000, attrs("capacityGb", 1000, "generation", 4, "readMbps", 7000, "writeMbps", 6000)));
        return parts;
    }

    private static List<ToolBuildPart> psuDowngradeParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(5, part(84, "psu-850-with-5090", "PSU", "850W Gold ATX 3.0", 180_000, attrs("capacityW", 850, "efficiency", "GOLD", "atxSpec", "ATX 3.0", "pcieSpec", "PCIe 5.0")));
        return parts;
    }

    private static List<ToolBuildPart> psuMidrangeParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(5, part(94, "psu-1000-with-5090", "PSU", "1000W Gold ATX 3.1", 260_000, attrs("capacityW", 1000, "efficiency", "GOLD", "atxSpec", "ATX 3.1", "pcieSpec", "PCIe 5.1")));
        return parts;
    }

    private static List<ToolBuildPart> motherboardDowngradeParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(1, part(85, "board-b650", "MOTHERBOARD", "B650 DDR5 Board", 280_000, attrs("socket", "AM5", "chipset", "B650", "memoryType", "DDR5", "pcieGeneration", 4, "hasWifi", false)));
        return parts;
    }

    private static List<ToolBuildPart> motherboardModernMainstreamParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(1, part(95, "board-b850", "MOTHERBOARD", "B850 Wi-Fi Board", 390_000, attrs("socket", "AM5", "chipset", "B850", "memoryType", "DDR5", "pcieGeneration", 5, "hasWifi", true)));
        return parts;
    }

    private static List<ToolBuildPart> coolerDowngradeParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(7, part(86, "cooler-basic-air", "COOLER", "기본 타워 공랭", 70_000, attrs("coolerType", "AIR", "tdpW", 180, "heightMm", 155)));
        return parts;
    }

    private static List<ToolBuildPart> coolerPremiumAirParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(7, part(96, "cooler-premium-air", "COOLER", "상급 듀얼타워 공랭", 160_000, attrs("coolerType", "AIR", "tdpW", 250, "heightMm", 168, "heatpipeCount", 7)));
        return parts;
    }

    private static List<ToolBuildPart> cooler240AioParts() {
        List<ToolBuildPart> parts = new ArrayList<>(endgameParts());
        parts.set(7, part(97, "cooler-240-aio", "COOLER", "240mm Liquid Cooler", 180_000, attrs("coolerType", "LIQUID_AIO", "tdpW", 250, "radiatorLengthMm", 240)));
        return parts;
    }

    private static Map<String, Object> tool(String tool, String status, Map<String, Object> details) {
        return MockData.map("tool", tool, "status", status, "confidence", "HIGH", "summary", status, "details", details);
    }

    private static void writeReport(List<CaseScore> cases) throws IOException {
        Path path = Path.of("build", "reports", "composite-score-cases.md");
        Files.createDirectories(path.getParent());
        StringBuilder builder = new StringBuilder();
        builder.append("# Build composite score cases\n\n");
        builder.append("| case | score | raw | requestFit | performance | safety | balance | upgrade | evidence | grade | label | caps |\n");
        builder.append("|---|---:|---:|---|---:|---:|---:|---:|---:|---|---|---|\n");
        for (CaseScore item : cases) {
            builder.append("| ")
                    .append(item.name())
                    .append(" | ")
                    .append(item.score())
                    .append(" | ")
                    .append(item.rawScore())
                    .append(" | ")
                    .append(requestFitLabel(item.requestFit()))
                    .append(" | ")
                    .append(componentScore(item, "performance"))
                    .append(" | ")
                    .append(componentScore(item, "compatibility"))
                    .append(" | ")
                    .append(componentScore(item, "balance"))
                    .append(" | ")
                    .append(componentScore(item, "upgrade"))
                    .append(" | ")
                    .append(componentScore(item, "evidence"))
                    .append(" | ")
                    .append(item.grade())
                    .append(" | ")
                    .append(item.label())
                    .append(" | ")
                    .append(item.caps().isEmpty() ? "-" : item.caps())
                    .append(" |\n");
        }
        Files.writeString(path, builder.toString());
    }

    private static String requestFitLabel(Map<String, Object> requestFit) {
        if (requestFit == null || requestFit.isEmpty()) {
            return "-";
        }
        return text(requestFit.get("status")) + " " + text(requestFit.get("score"));
    }

    private static int componentScore(CaseScore item, String key) {
        return item.components().stream()
                .filter(component -> key.equals(text(component.get("key"))))
                .findFirst()
                .map(component -> number(component.get("score")))
                .orElse(0);
    }

    private static CaseScore caseByName(List<CaseScore> cases, String name) {
        return cases.stream()
                .filter(item -> name.equals(item.name()))
                .findFirst()
                .orElseThrow();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private record CaseScore(String name, int score, int rawScore, String grade, String label, List<Map<String, Object>> components, List<Map<String, Object>> caps, Map<String, Object> requestFit) {
    }
}
