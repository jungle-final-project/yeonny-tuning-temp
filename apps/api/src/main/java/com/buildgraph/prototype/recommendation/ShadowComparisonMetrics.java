package com.buildgraph.prototype.recommendation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * M4 Shadow 비교 관측(설계 docs/mlops-maturity-design.md §5)의 순수 계산 로직.
 *
 * shadow_scores에 쌓인 실모델 점수를, 같은 행의 features JSONB로 재구성한 baseline(deterministic)
 * 순위와 비교해 "모델이 순위를 얼마나 바꾸는가"를 정량화한다. rank_position은 원 순위가 아니므로
 * (ORDER BY 없는 페치 순서) 절대 쓰지 않고, features로 baseline 점수를 재계산해 순위를 복원한다.
 *
 * 모든 메서드는 순수 함수라 DB 없이 단위 테스트 가능하다.
 */
final class ShadowComparisonMetrics {

    private ShadowComparisonMetrics() {
    }

    /** 한 스코어링 회차(그룹) 안의 후보 1건: 그룹 키 + 모델 점수 + features JSONB(Map). */
    record ShadowRow(String groupKey, double modelScore, Map<String, Object> features) {
    }

    /**
     * HomePartRecommendationService.deterministicScore를 features JSONB의 part_* 계약 키로 재현한다.
     * (deterministicScore는 DB row 키를 읽지만 features JSONB는 언더스코어 계약 키로 저장되므로 별도 재현.)
     */
    static double baselineScore(Map<String, Object> features) {
        double score = 0.0;
        score += num(features.get("part_benchmark_score"));
        score += truthy(features.get("part_has_fps_coverage")) ? 8.0 : 0.0;
        score += truthy(features.get("part_has_image")) ? 10.0 : 0.0;
        score += truthy(features.get("part_has_offer")) ? 5.0 : 0.0;
        score += truthy(features.get("part_tool_ready")) ? 8.0 : 0.0;
        Double priceAgeDays = decimalOrNull(features.get("part_price_age_days"));
        if (priceAgeDays != null) {
            // 결측은 features에 999.0으로 저장됨 → min(999,30)/3=10 → +0.0 (deterministicScore의 null-skip과 동일).
            score += Math.max(0.0, 10.0 - Math.min(priceAgeDays, 30.0) / 3.0);
        }
        Integer price = intOrNull(features.get("part_price"));
        if (price != null && price > 0) {
            score -= Math.min(price / 2_000_000.0, 4.0);
        }
        score += Math.max(0, 8 - categoryRank(str(features.get("category")))) * 0.01;
        return score;
    }

    /**
     * 순위 역전율 = baseline 순위와 model 순위 사이의 불일치(discordant) 쌍 비율(Kendall-τ 기반). 0=완전
     * 보존, 1=완전 역전. 동점(tie) 쌍은 계산에서 제외한다.
     */
    static double inversionRate(double[] baseline, double[] model) {
        int n = baseline.length;
        long discordant = 0;
        long total = 0;
        for (int i = 0; i < n; i += 1) {
            for (int j = i + 1; j < n; j += 1) {
                int baselineOrder = Double.compare(baseline[i], baseline[j]);
                int modelOrder = Double.compare(model[i], model[j]);
                if (baselineOrder == 0 || modelOrder == 0) {
                    continue;
                }
                total += 1;
                if ((long) baselineOrder * modelOrder < 0) {
                    discordant += 1;
                }
            }
        }
        return total == 0 ? 0.0 : (double) discordant / total;
    }

    /** top-4 교체율 = baseline 상위 4 중 model 상위 4에서 빠진 비율. */
    static double top4ReplacementRate(double[] baseline, double[] model) {
        int k = Math.min(4, baseline.length);
        if (k == 0) {
            return 0.0;
        }
        List<Integer> baselineTop = topKIndices(baseline, k);
        List<Integer> modelTop = topKIndices(model, k);
        int kept = 0;
        for (int index : baselineTop) {
            if (modelTop.contains(index)) {
                kept += 1;
            }
        }
        return (double) (k - kept) / k;
    }

    /**
     * 그룹별로 baseline 순위 대비 모델 순위의 역전율·top4 교체율을 계산해 평균낸다.
     * @param rows 오염 필터(HOME/PART, baseline-shadow 제외)를 통과한 shadow 후보들
     * @param minGroupSize 유효 그룹으로 볼 최소 후보 수(기본 2)
     */
    static Map<String, Object> summarize(List<ShadowRow> rows, int minGroupSize) {
        Map<String, List<ShadowRow>> groups = new LinkedHashMap<>();
        for (ShadowRow row : rows) {
            groups.computeIfAbsent(row.groupKey(), key -> new ArrayList<>()).add(row);
        }
        double inversionSum = 0.0;
        double replacementSum = 0.0;
        int scoredGroups = 0;
        int scoredCandidates = 0;
        for (List<ShadowRow> group : groups.values()) {
            if (group.size() < Math.max(2, minGroupSize)) {
                continue;
            }
            double[] model = new double[group.size()];
            double[] baseline = new double[group.size()];
            for (int i = 0; i < group.size(); i += 1) {
                model[i] = group.get(i).modelScore();
                baseline[i] = baselineScore(group.get(i).features());
            }
            inversionSum += inversionRate(baseline, model);
            replacementSum += top4ReplacementRate(baseline, model);
            scoredGroups += 1;
            scoredCandidates += group.size();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scoredGroups", scoredGroups);
        summary.put("scoredCandidates", scoredCandidates);
        summary.put("totalGroups", groups.size());
        summary.put("avgInversionRate", scoredGroups == 0 ? null : round4(inversionSum / scoredGroups));
        summary.put("avgTop4ReplacementRate", scoredGroups == 0 ? null : round4(replacementSum / scoredGroups));
        return summary;
    }

    private static List<Integer> topKIndices(double[] scores, int k) {
        // 점수 내림차순, 동점은 인덱스 오름차순으로 상위 k개 인덱스.
        TreeSet<Integer> order = new TreeSet<>((a, b) -> {
            int byScore = Double.compare(scores[b], scores[a]);
            return byScore != 0 ? byScore : Integer.compare(a, b);
        });
        for (int i = 0; i < scores.length; i += 1) {
            order.add(i);
        }
        List<Integer> top = new ArrayList<>(k);
        for (int index : order) {
            if (top.size() >= k) {
                break;
            }
            top.add(index);
        }
        return top;
    }

    private static int categoryRank(String category) {
        if (category == null) {
            return 99;
        }
        return switch (category) {
            case "CPU" -> 1;
            case "MOTHERBOARD" -> 2;
            case "RAM" -> 3;
            case "GPU" -> 4;
            case "STORAGE" -> 5;
            case "PSU" -> 6;
            case "CASE" -> 7;
            case "COOLER" -> 8;
            default -> 99;
        };
    }

    private static double num(Object value) {
        Double parsed = decimalOrNull(value);
        return parsed == null ? 0.0 : parsed;
    }

    private static Double decimalOrNull(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer intOrNull(Object value) {
        Double parsed = decimalOrNull(value);
        return parsed == null ? null : parsed.intValue();
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0;
        }
        return value != null && "true".equalsIgnoreCase(value.toString().trim());
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static Double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
