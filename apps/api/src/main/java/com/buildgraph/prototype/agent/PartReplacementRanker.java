package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.PartAliasReviewService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PartReplacementRanker {
    static final String WARNING_NO_HIGHER_RANK_CANDIDATE = "NO_HIGHER_RANK_CANDIDATE";
    static final String WARNING_RANK_FALLBACK_USED = "RANK_FALLBACK_USED";
    static final String WARNING_ALIAS_REVIEW_REQUIRED = "ALIAS_REVIEW_REQUIRED";

    private final PartAliasReviewService aliasReviewService;

    public PartReplacementRanker(PartAliasReviewService aliasReviewService) {
        this.aliasReviewService = aliasReviewService;
    }

    public SelectionResult select(
            String category,
            Map<String, Object> currentItem,
            String priceDirection,
            Integer targetMaxPrice,
            List<AiChatEngineResponse.PartRecommendation> candidates,
            int limit
    ) {
        String normalizedCategory = normalize(category);
        List<String> warnings = new ArrayList<>();
        if (normalizedCategory == null || candidates == null || candidates.isEmpty()) {
            return new SelectionResult(List.of(), warnings);
        }
        String currentPartId = text(currentItem == null ? null : currentItem.get("partId"));
        AiChatEngineResponse.PartRecommendation currentPart = candidates.stream()
                .filter(part -> currentPartId != null && currentPartId.equals(part.partId()))
                .findFirst()
                .orElse(null);
        Integer currentPrice = firstNumber(currentItem == null ? null : currentItem.get("currentPrice"), currentItem == null ? null : currentItem.get("price"));
        if (currentPrice == null && currentPart != null) {
            currentPrice = currentPart.price();
        }
        Double currentRank = currentPart == null ? rankCurrentItem(normalizedCategory, currentItem) : rank(currentPart);
        Double currentTier = currentPart == null ? tierCurrentItem(normalizedCategory, currentItem) : tierRank(currentPart);
        if (currentRank == null) {
            warnings.add(WARNING_ALIAS_REVIEW_REQUIRED);
            queueReview(normalizedCategory, currentItem, "현재 견적 부품의 성능/스펙 rank 기준을 계산하지 못했습니다.");
        }

        List<ScoredPart> scored = candidates.stream()
                .filter(part -> currentPartId == null || !currentPartId.equals(part.partId()))
                .map(part -> new ScoredPart(part, rank(part), tierRank(part)))
                .filter(part -> part.rank() != null)
                .toList();
        if (scored.isEmpty()) {
            warnings.add(WARNING_ALIAS_REVIEW_REQUIRED);
            aliasReviewService.queueReviewItem(
                    "AI_BUILD_CHAT",
                    normalizedCategory,
                    "rank",
                    null,
                    null,
                    "교체 후보 전체에서 rank 계산 가능한 부품을 찾지 못했습니다.",
                    MockData.map("category", normalizedCategory, "priceDirection", priceDirection)
            );
            return new SelectionResult(List.of(), warnings);
        }

        String direction = normalizePriceDirection(priceDirection);
        List<ScoredPart> selected = switch (direction) {
            case "CHEAPER" -> cheaper(scored, currentRank, currentPrice, targetMaxPrice);
            case "MORE_EXPENSIVE" -> higher(scored, currentRank, currentTier, warnings);
            case "SIMILAR_PRICE" -> similar(scored, currentRank, currentPrice);
            default -> scored.stream()
                    .sorted(Comparator.comparing(ScoredPart::rank).reversed().thenComparing(part -> part.part().price()))
                    .toList();
        };
        if (selected.isEmpty() && "MORE_EXPENSIVE".equals(direction)) {
            warnings.add(WARNING_NO_HIGHER_RANK_CANDIDATE);
            warnings.add(WARNING_RANK_FALLBACK_USED);
            selected = sameRankFallback(scored, currentRank);
        }
        if (selected.isEmpty() && "CHEAPER".equals(direction)) {
            warnings.add(WARNING_RANK_FALLBACK_USED);
            return new SelectionResult(List.of(), warnings.stream().distinct().toList());
        }
        if (selected.isEmpty() && "SIMILAR_PRICE".equals(direction)) {
            warnings.add(WARNING_RANK_FALLBACK_USED);
            return new SelectionResult(List.of(), warnings.stream().distinct().toList());
        }
        if (selected.isEmpty()) {
            warnings.add(WARNING_RANK_FALLBACK_USED);
            selected = scored.stream()
                    .sorted(Comparator.comparing(ScoredPart::rank).reversed().thenComparing(part -> part.part().price()))
                    .toList();
        }
        return new SelectionResult(
                selected.stream()
                        .map(ScoredPart::part)
                        .limit(Math.max(1, limit))
                        .toList(),
                warnings.stream().distinct().toList()
        );
    }

    public Double rank(AiChatEngineResponse.PartRecommendation part) {
        if (part == null) {
            return null;
        }
        return rank(part.category(), part.attributes());
    }

    private Double tierRank(AiChatEngineResponse.PartRecommendation part) {
        if (part == null) {
            return null;
        }
        return tierRank(part.category(), part.attributes());
    }

    private List<ScoredPart> higher(List<ScoredPart> scored, Double currentRank, Double currentTier, List<String> warnings) {
        if (currentTier != null) {
            List<ScoredPart> higherTier = scored.stream()
                    .filter(part -> part.tierRank() != null && part.tierRank() > currentTier)
                    .filter(part -> currentRank == null || part.rank() > currentRank)
                    .sorted(Comparator.comparingDouble((ScoredPart part) -> part.tierRank() - currentTier)
                            .thenComparing(Comparator.comparing(ScoredPart::rank).reversed())
                            .thenComparing(part -> part.part().price()))
                    .toList();
            if (!higherTier.isEmpty()) {
                return higherTier;
            }
        }
        if (currentRank == null) {
            warnings.add(WARNING_RANK_FALLBACK_USED);
            return scored.stream()
                    .sorted(Comparator.comparing(ScoredPart::rank).reversed().thenComparing(part -> part.part().price()))
                    .toList();
        }
        return scored.stream()
                .filter(part -> part.rank() > currentRank)
                .sorted(Comparator.comparingDouble((ScoredPart part) -> part.rank() - currentRank)
                        .thenComparing(part -> part.part().price()))
                .toList();
    }

    private List<ScoredPart> cheaper(List<ScoredPart> scored, Double currentRank, Integer currentPrice, Integer targetMaxPrice) {
        int maxPrice = targetMaxPrice != null && targetMaxPrice > 0
                ? targetMaxPrice
                : currentPrice == null ? Integer.MAX_VALUE : currentPrice - 1;
        return scored.stream()
                .filter(part -> part.part().price() <= maxPrice)
                .sorted(Comparator.comparing(ScoredPart::rank).reversed()
                        .thenComparing(Comparator.comparing((ScoredPart part) -> priceGap(part.part().price(), currentPrice)).reversed()))
                .toList();
    }

    private List<ScoredPart> similar(List<ScoredPart> scored, Double currentRank, Integer currentPrice) {
        if (currentPrice == null) {
            return scored.stream()
                    .sorted(Comparator.comparing(ScoredPart::rank).reversed().thenComparing(part -> part.part().price()))
                    .toList();
        }
        if (currentRank != null) {
            List<ScoredPart> stableOrBetter = scored.stream()
                    .filter(part -> part.rank() >= currentRank)
                    .sorted(Comparator.comparing((ScoredPart part) -> Math.abs(part.part().price() - currentPrice))
                            .thenComparing(Comparator.comparing(ScoredPart::rank).reversed()))
                    .toList();
            if (!stableOrBetter.isEmpty()) {
                return stableOrBetter;
            }
        }
        return scored.stream()
                .sorted(Comparator.comparing((ScoredPart part) -> Math.abs(part.part().price() - currentPrice))
                        .thenComparing(part -> currentRank == null ? 0.0 : Math.max(0.0, currentRank - part.rank()))
                        .thenComparing(Comparator.comparing(ScoredPart::rank).reversed()))
                .toList();
    }

    private List<ScoredPart> sameRankFallback(List<ScoredPart> scored, Double currentRank) {
        if (currentRank == null) {
            return scored.stream()
                    .sorted(Comparator.comparing(ScoredPart::rank).reversed().thenComparing(part -> part.part().price()))
                    .toList();
        }
        return scored.stream()
                .filter(part -> Math.floor(part.rank() / 1000.0) >= Math.floor(currentRank / 1000.0))
                .sorted(Comparator.comparing(ScoredPart::rank).reversed().thenComparing(part -> part.part().price()))
                .toList();
    }

    private Double rankCurrentItem(String category, Map<String, Object> currentItem) {
        if (currentItem == null || currentItem.isEmpty()) {
            return null;
        }
        Object attributes = currentItem.get("attributes");
        if (attributes instanceof Map<?, ?> rawMap) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> mapped.put(String.valueOf(key), value));
            return rank(category, mapped);
        }
        return null;
    }

    private Double tierCurrentItem(String category, Map<String, Object> currentItem) {
        if (currentItem == null || currentItem.isEmpty()) {
            return null;
        }
        Object attributes = currentItem.get("attributes");
        if (attributes instanceof Map<?, ?> rawMap) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> mapped.put(String.valueOf(key), value));
            return tierRank(category, mapped);
        }
        return null;
    }

    private Double rank(String category, Map<String, Object> attributes) {
        if (attributes == null) {
            return null;
        }
        String normalizedCategory = normalize(category);
        return switch (normalizedCategory) {
            case "GPU" -> gpuRank(attributes);
            case "CPU" -> cpuRank(attributes);
            case "RAM" -> ramRank(attributes);
            case "STORAGE" -> storageRank(attributes);
            case "PSU" -> psuRank(attributes);
            case "MOTHERBOARD" -> motherboardRank(attributes);
            case "CASE" -> caseRank(attributes);
            case "COOLER" -> coolerRank(attributes);
            default -> benchmarkScore(attributes);
        };
    }

    private Double tierRank(String category, Map<String, Object> attributes) {
        if (attributes == null) {
            return null;
        }
        String normalizedCategory = normalize(category);
        return switch (normalizedCategory) {
            case "GPU" -> gpuTier(attributes);
            case "CPU" -> cpuTier(attributes);
            case "RAM" -> firstPositive(number(attributes.get("capacityGb")), number(attributes.get("speedMhz")) / 1000.0);
            case "STORAGE" -> generationRank(text(attributes.get("generation"))) * 10.0 + number(attributes.get("capacityGb")) / 1000.0;
            case "PSU" -> firstPositive(number(attributes.get("capacityW")), number(attributes.get("wattage")));
            case "MOTHERBOARD" -> chipsetRank(text(attributes.get("chipset"))) * 10.0 + generationRank(text(attributes.get("pcieGeneration")));
            case "CASE" -> firstPositive(number(attributes.get("maxGpuLengthMm")), number(attributes.get("maxCpuCoolerHeightMm")));
            case "COOLER" -> firstPositive(number(attributes.get("tdpW")), number(attributes.get("radiatorLengthMm")), number(attributes.get("heightMm")));
            default -> benchmarkScore(attributes);
        };
    }

    private Double gpuRank(Map<String, Object> attributes) {
        String gpuClass = text(attributes.get("gpuClass"));
        double classRank = gpuClassRank(gpuClass);
        if (classRank <= 0) {
            classRank = extractRtxClassRank(gpuClass);
        }
        if (classRank <= 0) {
            return null;
        }
        return classRank * 1000.0
                + number(attributes.get("vramGb")) * 8.0
                + benchmarkScore(attributes);
    }

    private Double gpuTier(Map<String, Object> attributes) {
        String gpuClass = text(attributes.get("gpuClass"));
        double classRank = gpuClassRank(gpuClass);
        if (classRank <= 0) {
            classRank = extractRtxClassRank(gpuClass);
        }
        if (classRank <= 0) {
            classRank = extractRtxClassRank(text(attributes.get("hardwareClass")));
        }
        return classRank <= 0 ? null : classRank;
    }

    private Double cpuRank(Map<String, Object> attributes) {
        double classRank = cpuClassRank(text(attributes.get("cpuClass")));
        double benchmark = benchmarkScore(attributes);
        double cores = number(attributes.get("coreCount"));
        double threads = number(attributes.get("threadCount"));
        double tdp = number(attributes.get("tdpW"));
        if (classRank <= 0 && benchmark <= 0 && cores <= 0) {
            return null;
        }
        return (classRank * 1000.0) + (benchmark * 10.0) + (cores * 20.0) + (threads * 6.0) + (tdp * 0.2);
    }

    private Double cpuTier(Map<String, Object> attributes) {
        double classRank = cpuClassRank(text(attributes.get("cpuClass")));
        if (classRank > 0) {
            return classRank;
        }
        double benchmark = benchmarkScore(attributes);
        if (benchmark > 0) {
            return Math.floor(benchmark / 10.0);
        }
        return firstPositive(number(attributes.get("coreCount")), number(attributes.get("threadCount")));
    }

    private Double ramRank(Map<String, Object> attributes) {
        double capacity = number(attributes.get("capacityGb"));
        double speed = number(attributes.get("speedMhz"));
        double modules = number(attributes.get("moduleCount"));
        if (capacity <= 0 && speed <= 0) {
            return null;
        }
        return capacity * 100.0
                + speed / 10.0
                + modules * 20.0
                + ("DDR5".equalsIgnoreCase(text(attributes.get("memoryType"))) ? 300.0 : 0.0)
                + (bool(attributes.get("xmp")) ? 30.0 : 0.0)
                + (bool(attributes.get("expo")) ? 30.0 : 0.0);
    }

    private Double storageRank(Map<String, Object> attributes) {
        double capacity = number(attributes.get("capacityGb"));
        double read = number(attributes.get("readMbps"));
        double write = number(attributes.get("writeMbps"));
        if (capacity <= 0 && read <= 0 && write <= 0) {
            return null;
        }
        return capacity / 2.0
                + read / 10.0
                + write / 15.0
                + generationRank(text(attributes.get("generation"))) * 250.0;
    }

    private Double psuRank(Map<String, Object> attributes) {
        double capacity = firstPositive(number(attributes.get("capacityW")), number(attributes.get("wattage")));
        if (capacity <= 0) {
            return null;
        }
        return capacity
                + efficiencyRank(text(attributes.get("efficiency"))) * 90.0
                + specRank(text(attributes.get("atxSpec"))) * 50.0
                + specRank(text(attributes.get("pcieSpec"))) * 50.0
                + (bool(attributes.get("modular")) ? 60.0 : 0.0);
    }

    private Double motherboardRank(Map<String, Object> attributes) {
        double chipset = chipsetRank(text(attributes.get("chipset")));
        double pcie = generationRank(text(attributes.get("pcieGeneration")));
        if (chipset <= 0 && pcie <= 0) {
            return null;
        }
        return chipset * 1000.0
                + pcie * 160.0
                + ("DDR5".equalsIgnoreCase(text(attributes.get("memoryType"))) ? 300.0 : 0.0)
                + (bool(attributes.get("hasWifi")) ? 80.0 : 0.0)
                + formFactorRank(text(attributes.get("formFactor"))) * 30.0;
    }

    private Double caseRank(Map<String, Object> attributes) {
        double gpu = number(attributes.get("maxGpuLengthMm"));
        double cooler = number(attributes.get("maxCpuCoolerHeightMm"));
        double psu = number(attributes.get("maxPsuLengthMm"));
        if (gpu <= 0 && cooler <= 0 && psu <= 0) {
            return null;
        }
        return gpu * 2.0
                + cooler * 2.5
                + psu
                + benchmarkScore(attributes) * 10.0
                + (bool(attributes.get("frontMesh")) ? 120.0 : 0.0)
                + (bool(attributes.get("airflowFocus")) ? 120.0 : 0.0);
    }

    private Double coolerRank(Map<String, Object> attributes) {
        double tdp = number(attributes.get("tdpW"));
        double radiator = number(attributes.get("radiatorLengthMm"));
        double height = number(attributes.get("heightMm"));
        if (tdp <= 0 && radiator <= 0 && height <= 0) {
            return null;
        }
        return tdp * 4.0
                + radiator * 2.0
                + height * 0.8
                + benchmarkScore(attributes) * 10.0
                + ("AIO".equalsIgnoreCase(text(attributes.get("coolerType"))) ? 120.0 : 0.0);
    }

    private void queueReview(String category, Map<String, Object> currentItem, String message) {
        aliasReviewService.queueReviewItem(
                "AI_BUILD_CHAT",
                category,
                "rank",
                text(currentItem == null ? null : currentItem.get("name")),
                text(currentItem == null ? null : currentItem.get("partId")),
                message,
                MockData.map(
                        "category", category,
                        "currentItem", currentItem == null ? Map.of() : currentItem
                )
        );
    }

    private static double gpuClassRank(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return 0.0;
        }
        return switch (normalized) {
            case "RTX_5090", "RTX 5090" -> 5090;
            case "RTX_5080", "RTX 5080" -> 5080;
            case "RTX_5070_TI", "RTX 5070 TI" -> 5075;
            case "RTX_5070", "RTX 5070" -> 5070;
            case "RTX_5060_TI", "RTX 5060 TI" -> 5065;
            case "RTX_5060", "RTX 5060" -> 5060;
            default -> 0.0;
        };
    }

    private static double extractRtxClassRank(String value) {
        String text = text(value);
        if (text == null) {
            return 0.0;
        }
        String normalized = text.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-Z]", " ");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(50[6-9]0|40[6-9]0)\\s*(TI)?").matcher(normalized);
        if (!matcher.find()) {
            return 0.0;
        }
        double base = Double.parseDouble(matcher.group(1));
        return matcher.group(2) == null ? base : base + 5.0;
    }

    private static double cpuClassRank(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return 0.0;
        }
        if (normalized.contains("RYZEN_9") || normalized.contains("RYZEN 9") || normalized.contains("I9") || normalized.contains("ULTRA_9") || normalized.contains("ULTRA 9")) {
            return 9;
        }
        if (normalized.contains("RYZEN_7") || normalized.contains("RYZEN 7") || normalized.contains("I7") || normalized.contains("ULTRA_7") || normalized.contains("ULTRA 7")) {
            return 7;
        }
        if (normalized.contains("RYZEN_5") || normalized.contains("RYZEN 5") || normalized.contains("I5") || normalized.contains("ULTRA_5") || normalized.contains("ULTRA 5")) {
            return 5;
        }
        return switch (normalized) {
            case "ENTHUSIAST" -> 9;
            case "PERFORMANCE" -> 7;
            case "STANDARD" -> 5;
            default -> 0;
        };
    }

    private static double chipsetRank(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return 0.0;
        }
        if (normalized.startsWith("X") || normalized.startsWith("Z")) {
            return 4;
        }
        if (normalized.startsWith("B")) {
            return 3;
        }
        if (normalized.startsWith("H") || normalized.startsWith("A")) {
            return 2;
        }
        return 1;
    }

    private static double efficiencyRank(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return 0.0;
        }
        if (normalized.contains("TITANIUM")) {
            return 5;
        }
        if (normalized.contains("PLATINUM")) {
            return 4;
        }
        if (normalized.contains("GOLD")) {
            return 3;
        }
        if (normalized.contains("SILVER")) {
            return 2;
        }
        if (normalized.contains("BRONZE")) {
            return 1;
        }
        return 0;
    }

    private static double generationRank(String value) {
        String text = text(value);
        if (text == null) {
            return 0.0;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)").matcher(text);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0.0;
    }

    private static double specRank(String value) {
        return generationRank(value);
    }

    private static double formFactorRank(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return 0;
        }
        if (normalized.contains("E-ATX")) {
            return 4;
        }
        if (normalized.contains("ATX")) {
            return 3;
        }
        if (normalized.contains("M-ATX") || normalized.contains("MICRO")) {
            return 2;
        }
        if (normalized.contains("ITX")) {
            return 1;
        }
        return 0;
    }

    private static double benchmarkScore(Map<String, Object> attributes) {
        return firstPositive(number(attributes.get("_benchmarkScore")), number(attributes.get("benchmarkScore")), number(attributes.get("score")));
    }

    private static int priceGap(int price, Integer currentPrice) {
        return currentPrice == null ? price : Math.abs(currentPrice - price);
    }

    private static String normalizePriceDirection(String value) {
        String normalized = normalize(value);
        return normalized == null ? "ANY" : normalized;
    }

    private static double firstPositive(double... values) {
        for (double value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0.0;
    }

    private static Integer firstNumber(Object... values) {
        for (Object value : values) {
            Integer number = integer(value);
            if (number != null) {
                return number;
            }
        }
        return null;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = text(value);
        if (text == null) {
            return 0.0;
        }
        try {
            return Double.parseDouble(text.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = text(value);
        return text != null && ("true".equalsIgnoreCase(text) || "Y".equalsIgnoreCase(text) || "YES".equalsIgnoreCase(text));
    }

    private static String normalize(String value) {
        String text = text(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public record SelectionResult(
            List<AiChatEngineResponse.PartRecommendation> parts,
            List<String> warnings
    ) {
    }

    private record ScoredPart(
            AiChatEngineResponse.PartRecommendation part,
            Double rank,
            Double tierRank
    ) {
        private ScoredPart {
            Objects.requireNonNull(part);
        }
    }
}
