package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.buildgraph.prototype.part.PartAliasReviewService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PartReplacementRankerTest {
    private final PartReplacementRanker ranker = new PartReplacementRanker(mock(PartAliasReviewService.class));

    @Test
    void categoryMatrixKeepsUpgradeDowngradeAndSimilarPriceDirection() {
        for (String category : List.of("GPU", "CPU", "RAM", "STORAGE", "PSU", "MOTHERBOARD", "CASE", "COOLER")) {
            List<AiChatEngineResponse.PartRecommendation> candidates = tieredParts(category);
            Map<String, Object> currentItem = Map.of("partId", id(category, "mid"), "price", 1_000_000);

            var upgrade = ranker.select(category, currentItem, "MORE_EXPENSIVE", null, candidates, 3);
            assertThat(upgrade.parts())
                    .as(category + " upgrade must not recommend mid/low tier")
                    .extracting(AiChatEngineResponse.PartRecommendation::partId)
                    .containsExactly(id(category, "high"));

            var cheaper = ranker.select(category, currentItem, "CHEAPER", null, candidates, 3);
            assertThat(cheaper.parts())
                    .as(category + " cheaper must stay below current price")
                    .allSatisfy(part -> assertThat(part.price()).isLessThan(1_000_000));
            assertThat(cheaper.parts())
                    .as(category + " cheaper should preserve as much rank as possible")
                    .extracting(AiChatEngineResponse.PartRecommendation::partId)
                    .containsExactly(id(category, "low"));

            var similar = ranker.select(category, currentItem, "SIMILAR_PRICE", null, candidates, 3);
            assertThat(similar.parts())
                    .as(category + " similar price must not downgrade rank when a stable/higher candidate exists")
                    .extracting(AiChatEngineResponse.PartRecommendation::partId)
                    .containsExactly(id(category, "high"));
        }
    }

    @Test
    void betterGpuRequiresHigherGpuClassBeforeSameClassPremiumFallback() {
        var result = ranker.select(
                "GPU",
                Map.of("partId", "gpu-5070-ti", "price", 1_600_000),
                "MORE_EXPENSIVE",
                null,
                List.of(
                        gpu("gpu-5090", "RTX 5090", 5_000_000, "RTX_5090", 90),
                        gpu("gpu-5080", "RTX 5080", 2_200_000, "RTX_5080", 80),
                        gpu("gpu-5070-ti-premium", "RTX 5070 Ti Premium", 1_900_000, "RTX_5070_TI", 99),
                        gpu("gpu-5070-ti", "RTX 5070 Ti", 1_600_000, "RTX_5070_TI", 60),
                        gpu("gpu-5070", "RTX 5070", 1_200_000, "RTX_5070", 70)
                ),
                3
        );

        assertThat(result.parts())
                .extracting(AiChatEngineResponse.PartRecommendation::partId)
                .containsExactly("gpu-5080", "gpu-5090");
    }

    private static List<AiChatEngineResponse.PartRecommendation> tieredParts(String category) {
        return List.of(
                part(category, "high", 1_080_000),
                part(category, "mid", 1_000_000),
                part(category, "low", 700_000)
        );
    }

    private static AiChatEngineResponse.PartRecommendation part(String category, String tier, int price) {
        return new AiChatEngineResponse.PartRecommendation(
                id(category, tier),
                category,
                category + " " + tier,
                "BuildGraph",
                price,
                attributes(category, tier)
        );
    }

    private static String id(String category, String tier) {
        return category + "-" + tier;
    }

    private static Map<String, Object> attributes(String category, String tier) {
        int rank = switch (tier) {
            case "high" -> 3;
            case "mid" -> 2;
            default -> 1;
        };
        return switch (category) {
            case "GPU" -> Map.of("gpuClass", rank == 3 ? "RTX_5080" : rank == 2 ? "RTX_5070" : "RTX_5060", "vramGb", rank == 3 ? 16 : 12);
            case "CPU" -> Map.of("cpuClass", rank == 3 ? "RYZEN_9" : rank == 2 ? "RYZEN_7" : "RYZEN_5", "coreCount", rank == 3 ? 16 : rank == 2 ? 8 : 6, "threadCount", rank == 3 ? 32 : rank == 2 ? 16 : 12);
            case "RAM" -> Map.of("memoryType", "DDR5", "capacityGb", rank == 3 ? 64 : rank == 2 ? 32 : 16, "speedMhz", rank == 3 ? 7200 : rank == 2 ? 6400 : 5600, "moduleCount", 2);
            case "STORAGE" -> Map.of("capacityGb", rank == 3 ? 4000 : rank == 2 ? 2000 : 1000, "readMbps", rank == 3 ? 14000 : rank == 2 ? 7400 : 5000, "writeMbps", rank == 3 ? 12000 : rank == 2 ? 6500 : 4200, "generation", rank == 3 ? "PCIe 5.0" : "PCIe 4.0");
            case "PSU" -> Map.of("capacityW", rank == 3 ? 1000 : rank == 2 ? 850 : 650, "efficiency", rank == 3 ? "PLATINUM" : rank == 2 ? "GOLD" : "BRONZE", "atxSpec", rank == 1 ? "2.4" : "3.1", "modular", rank > 1);
            case "MOTHERBOARD" -> Map.of("chipset", rank == 3 ? "X870E" : rank == 2 ? "B850" : "A620", "memoryType", "DDR5", "pcieGeneration", rank == 3 ? "5.0" : "4.0", "hasWifi", rank > 1, "formFactor", "ATX");
            case "CASE" -> Map.of("maxGpuLengthMm", rank == 3 ? 430 : rank == 2 ? 380 : 330, "maxCpuCoolerHeightMm", rank == 3 ? 180 : rank == 2 ? 170 : 160, "maxPsuLengthMm", rank == 3 ? 250 : rank == 2 ? 220 : 180, "frontMesh", rank > 1, "airflowFocus", rank > 1);
            case "COOLER" -> Map.of("tdpW", rank == 3 ? 280 : rank == 2 ? 220 : 160, "radiatorLengthMm", rank == 3 ? 360 : rank == 2 ? 280 : 0, "heightMm", rank == 1 ? 155 : 165, "coolerType", rank == 3 ? "AIO" : "AIR");
            default -> Map.of("benchmarkScore", rank * 10);
        };
    }

    private static AiChatEngineResponse.PartRecommendation gpu(
            String id,
            String name,
            int price,
            String gpuClass,
            int benchmarkScore
    ) {
        return new AiChatEngineResponse.PartRecommendation(
                id,
                "GPU",
                name,
                "BuildGraph",
                price,
                Map.of("gpuClass", gpuClass, "vramGb", 16, "benchmarkScore", benchmarkScore)
        );
    }
}
