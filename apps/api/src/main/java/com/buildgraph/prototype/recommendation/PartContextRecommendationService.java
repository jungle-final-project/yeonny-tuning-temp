package com.buildgraph.prototype.recommendation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Ranks candidates that have already been materialized and checked by the Parts Tool flow.
 * This service deliberately performs no DB access and never revives WARN/FAIL candidates.
 */
@Service
public class PartContextRecommendationService {
    private static final int MAX_RECOMMENDATIONS = 3;
    private static final Map<String, Set<String>> ANCHOR_CATEGORIES = Map.of(
            "CPU", Set.of("GPU"),
            "GPU", Set.of("CPU"),
            "MOTHERBOARD", Set.of("CPU"),
            "RAM", Set.of("CPU", "MOTHERBOARD"),
            "STORAGE", Set.of("CPU", "GPU", "RAM"),
            "PSU", Set.of("CPU", "GPU"),
            "CASE", Set.of("GPU", "COOLER", "PSU", "MOTHERBOARD"),
            "COOLER", Set.of("CPU")
    );

    public List<Map<String, Object>> annotate(String category, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty() || !hasRecommendationAnchor(category, rows)) {
            return rows == null ? List.of() : rows;
        }
        List<Map<String, Object>> passRows = rows.stream()
                .filter(PartContextRecommendationService::isPass)
                .toList();
        if (passRows.isEmpty()) {
            return rows;
        }
        double medianPrice = medianPrice(passRows);
        List<ScoredCandidate> ranked = passRows.stream()
                .map(row -> score(category, row, medianPrice))
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                        .thenComparing(candidate -> text(candidate.row().get("id"))))
                .limit(MAX_RECOMMENDATIONS)
                .toList();
        Map<String, Map<String, Object>> recommendationById = new LinkedHashMap<>();
        for (int index = 0; index < ranked.size(); index += 1) {
            ScoredCandidate candidate = ranked.get(index);
            recommendationById.put(text(candidate.row().get("id")), Map.of(
                    "recommended", true,
                    "rank", index + 1,
                    "score", (int) Math.round(candidate.score()),
                    "reasons", candidate.reasons()
            ));
        }
        return rows.stream()
                .map(row -> {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    Map<String, Object> recommendation = recommendationById.get(text(row.get("id")));
                    if (recommendation != null) {
                        copy.put("recommendation", recommendation);
                    }
                    return copy;
                })
                .toList();
    }

    private static ScoredCandidate score(String category, Map<String, Object> row, double medianPrice) {
        double performance = performanceBalance(category, row);
        double price = priceFit(row, medianPrice);
        double headroom = operationalHeadroom(category, row);
        double quality = dataQuality(row);
        double total = performance * 0.45 + price * 0.30 + headroom * 0.15 + quality * 0.10;
        return new ScoredCandidate(row, total, reasons(category, performance, price, headroom, quality));
    }

    private static double performanceBalance(String category, Map<String, Object> row) {
        Map<String, Object> performance = toolResult(row, "performance");
        Map<String, Object> details = map(performance.get("details"));
        Double cpuScore = number(details.get("cpuBenchmarkScore"));
        Double gpuScore = number(details.get("gpuBenchmarkScore"));
        if (("CPU".equals(category) || "GPU".equals(category)) && cpuScore != null && gpuScore != null
                && cpuScore > 0 && gpuScore > 0) {
            return clamp(Math.min(cpuScore, gpuScore) / Math.max(cpuScore, gpuScore) * 100.0);
        }
        Map<String, Object> benchmark = map(row.get("benchmarkSummary"));
        Double benchmarkScore = number(benchmark.get("score"));
        if (benchmarkScore != null) {
            return clamp(benchmarkScore);
        }
        Map<String, Object> attributes = map(row.get("attributes"));
        return switch (category == null ? "" : category) {
            case "RAM" -> clamp(55 + Math.min(25, integer(attributes.get("capacityGb"), 0) / 2.0)
                    + Math.min(20, integer(attributes.get("speedMhz"), 0) / 400.0));
            case "STORAGE" -> clamp(50 + Math.min(25, integer(attributes.get("capacityGb"), 0) / 80.0)
                    + Math.min(25, integer(attributes.get("readMbps"), 0) / 600.0));
            case "COOLER" -> clamp(50 + Math.min(50, integer(attributes.get("tdpW"), 0) / 6.0));
            case "PSU" -> clamp(50 + Math.min(50, integer(attributes.get("capacityW"), 0) / 20.0));
            default -> 70.0;
        };
    }

    private static double priceFit(Map<String, Object> row, double medianPrice) {
        double price = integer(row.get("price"), 0);
        if (price <= 0 || medianPrice <= 0) {
            return 55.0;
        }
        return clamp(100.0 - Math.abs(price - medianPrice) / medianPrice * 80.0);
    }

    private static double operationalHeadroom(String category, Map<String, Object> row) {
        Map<String, Object> power = map(toolResult(row, "power").get("details"));
        Double loadPercent = number(power.get("ratedLoadPercent"));
        if (loadPercent != null && ("PSU".equals(category) || "GPU".equals(category) || "CPU".equals(category))) {
            return clamp(100.0 - Math.abs(loadPercent - 70.0) * 2.0);
        }
        Map<String, Object> size = map(toolResult(row, "size").get("details"));
        Double categoryHeadroom = switch (category == null ? "" : category) {
            case "GPU", "CASE" -> number(size.get("gpuHeadroomMm"));
            case "COOLER" -> number(size.get("coolerHeadroomMm"));
            case "PSU" -> number(size.get("psuHeadroomMm"));
            default -> null;
        };
        if (categoryHeadroom != null) {
            return clamp(55.0 + categoryHeadroom * 2.0);
        }
        return 70.0;
    }

    private static double dataQuality(Map<String, Object> row) {
        double score = 35.0;
        if (!map(row.get("attributes")).isEmpty()) score += 25.0;
        if (!map(row.get("benchmarkSummary")).isEmpty()) score += 20.0;
        if (row.get("latestPriceSource") != null || !map(row.get("externalOffer")).isEmpty()) score += 20.0;
        return clamp(score);
    }

    private static List<String> reasons(String category, double performance, double price, double headroom, double quality) {
        List<ReasonScore> candidates = List.of(
                new ReasonScore(performance, performanceReason(category)),
                new ReasonScore(price, "현재 후보 가격대에서 균형이 좋습니다."),
                new ReasonScore(headroom, "현재 구성의 전력·발열 여유가 안정적입니다."),
                new ReasonScore(quality, "내부 성능·가격 근거가 충분한 후보입니다.")
        );
        return candidates.stream()
                .sorted(Comparator.comparingDouble(ReasonScore::score).reversed())
                .limit(2)
                .map(ReasonScore::reason)
                .toList();
    }

    private static String performanceReason(String category) {
        return switch (category == null ? "" : category) {
            case "CPU", "GPU" -> "현재 CPU·GPU와 성능 균형이 적절합니다.";
            case "PSU" -> "현재 CPU·GPU 구성에 적절한 출력 등급입니다.";
            case "COOLER" -> "현재 CPU의 발열 수준에 적절한 냉각 등급입니다.";
            case "RAM" -> "현재 구성에 적절한 메모리 성능 등급입니다.";
            case "STORAGE" -> "현재 구성에 적절한 저장장치 성능 등급입니다.";
            default -> "현재 선택 부품과 균형이 좋은 후보입니다.";
        };
    }

    private static boolean hasRecommendationAnchor(String category, List<Map<String, Object>> rows) {
        Set<String> required = ANCHOR_CATEGORIES.getOrDefault(category, Set.of());
        return rows.stream()
                .map(row -> map(row.get("_recommendationContext")))
                .map(context -> list(context.get("selectedCategories")))
                .flatMap(List::stream)
                .map(String::valueOf)
                .anyMatch(required::contains);
    }

    private static boolean isPass(Map<String, Object> row) {
        return "PASS".equals(text(map(row.get("compatibility")).get("status")));
    }

    private static double medianPrice(List<Map<String, Object>> rows) {
        List<Integer> prices = rows.stream()
                .map(row -> integer(row.get("price"), 0))
                .filter(price -> price > 0)
                .sorted()
                .toList();
        if (prices.isEmpty()) return 0;
        int middle = prices.size() / 2;
        return prices.size() % 2 == 0
                ? (prices.get(middle - 1) + prices.get(middle)) / 2.0
                : prices.get(middle);
    }

    private static Map<String, Object> toolResult(Map<String, Object> row, String tool) {
        return list(row.get("_candidateToolResults")).stream()
                .filter(Map.class::isInstance)
                .map(value -> map(value))
                .filter(result -> tool.equals(text(result.get("tool"))))
                .findFirst()
                .orElse(Map.of());
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }

    private static int integer(Object value, int fallback) {
        Double number = number(value);
        return number == null ? fallback : number.intValue();
    }

    private static Double number(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return null;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? (Map<String, Object>) source : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> source ? (List<Object>) source : List.of();
    }

    private record ScoredCandidate(Map<String, Object> row, double score, List<String> reasons) {
    }

    private record ReasonScore(double score, String reason) {
    }
}
