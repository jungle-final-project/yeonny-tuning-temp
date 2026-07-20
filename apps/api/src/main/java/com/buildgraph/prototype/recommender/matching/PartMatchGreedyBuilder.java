package com.buildgraph.prototype.recommender.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.buildgraph.prototype.parts.tool.ToolBuildPart;
import com.buildgraph.prototype.parts.tool.ToolService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PartMatchGreedyBuilder {

        private final ToolService toolService;

        private static final List<String> CATEGORY_ORDER = List.of(
                "CPU", "MOTHERBOARD", "RAM", "GPU", "PSU", "CASE", "COOLER", "STORAGE"
        );


        public List<Map<String, Object>> greedyBuildOrchestrator(
                List<Map<String, Object>> scoredParts,
                int budget
        ) {
                /* 품목별 후보를 점수 내림차순으로 정렬
                최종적으로 선택된 부품을 반환하는 객체 */
                Map<String, List<Map<String, Object>>> candidatesByCategory = groupByCategory(scoredParts);
                Map<String, Map<String, Object>> selectedParts = new LinkedHashMap<>();

                /* 카테고리별 최저가 합계도 예산을 넘으면 DFS로 해결할 수 없으므로 즉시 종료한다 */
                int minimumBuildPrice = 0;
                for (String category : CATEGORY_ORDER) {
                        List<Map<String, Object>> candidates =
                                candidatesByCategory.getOrDefault(category, List.of());
                        if (candidates.isEmpty()) return List.of();

                        int minimumCategoryPrice = candidates.stream()
                                .mapToInt(part -> ((Number) part.get("price")).intValue())
                                .min()
                                .orElse(Integer.MAX_VALUE);
                        minimumBuildPrice += minimumCategoryPrice;
                }
                if (budget > 0 && minimumBuildPrice > budget) return List.of();
                /* DFS 방식 BackTracking 사용 */
                boolean found = searchQuote(
                        0,
                        candidatesByCategory,
                        selectedParts,
                        budget
                );

                /* 추천 불가는 서버 오류가 아니므로 빈 결과를 반환하고 상위 Chat 계층에서 안내한다 */
                if (!found) return List.of();
                
                return new ArrayList<>(selectedParts.values());
        }


        /* 카테고리별 정렬 함수 */
        private Map<String, List<Map<String, Object>>> groupByCategory(
                List<Map<String, Object>> scoredParts
        ) {
                /* 정렬한 부품을 담을 객체 */
                Map<String, List<Map<String, Object>>> candidatesByCategory = new LinkedHashMap<>();

                /* 일단 카테고리 별로 분류한다 */
                for (Map<String, Object> part : scoredParts) {
                String category = (String) part.get("category");

                candidatesByCategory
                                .computeIfAbsent(category, key -> new ArrayList<>())
                                .add(part);
                }

                /* 각 카테고리 내부를 match_score 내림차순으로 정렬 */
                for (List<Map<String, Object>> candidates : candidatesByCategory.values()) {
                candidates.sort(
                        Comparator.comparingDouble(
                                (Map<String, Object> part) ->
                                        ((Number) part.get("match_score"))
                                                .doubleValue()
                        ).reversed()
                );
                }

                return candidatesByCategory;
        }

        /* DFS Backtracking 방식으로 부품 탐색하기 */
        private boolean searchQuote(
                int categoryIndex,
                Map<String, List<Map<String, Object>>> candidatesByCategory,
                Map<String, Map<String, Object>> selectedParts,
                int budget
        ) {
                /* 탈출 조건: 모두 찾았을 경우, 이를 최종 검사 후 반환 */
                if (categoryIndex == CATEGORY_ORDER.size()) {
                        return validateQuote(
                                "FINAL",
                                new ArrayList<>(selectedParts.values()),
                                budget
                        );
                }

                /* index 기반으로 품목을 선택 => 해당 품목 list 가져오기 */
                String category = CATEGORY_ORDER.get(categoryIndex);
                List<Map<String, Object>> candidates =
                        candidatesByCategory.getOrDefault(
                                category,
                                List.of()
                        );

                /* match score 가 높은 품목 순으로 탐색.. 흐름:
                   1. 먼서 부품 선택(임시)
                   2. 부품이 포함된 견적 생성(임시)
                   3. 검증 => 통과 시, 다음 품목으로 진입
                   .. 실패할 경우, back 하여 해당 부품 취소 */
                for (Map<String, Object> candidate : candidates) {
                        selectedParts.put(category, candidate);

                        List<Map<String, Object>> trialParts =
                                new ArrayList<>(selectedParts.values());

                        /* 현재까지의 부분 견적 검증 */
                        boolean passed = validateQuote(
                                category,
                                trialParts,
                                budget
                        );

                        if (passed && searchQuote(
                                categoryIndex + 1,
                                candidatesByCategory,
                                selectedParts,
                                budget
                        )) {
                                return true;
                        }

                        selectedParts.remove(category);
                }

                return false;
        }

        @SuppressWarnings("unchecked")
        /* ToolService를 호출하여 검증을 수행한다 */
        private boolean validateQuote(
                String category,
                List<Map<String, Object>> trialParts,
                int budget
        ) {
        /* ToolBuildPart 형태의 객체로 변환 */
        List<ToolBuildPart> toolParts = trialParts.stream()
                .map(part -> new ToolBuildPart(
                        ((Number) part.get("part_id")).longValue(),
                        (String) part.get("id"),
                        (String) part.get("category"),
                        (String) part.get("name"),
                        (String) part.get("manufacturer"),
                        ((Number) part.get("price")).intValue(),
                        (Map<String, Object>) part.getOrDefault(
                                "attributes",
                                Map.of()
                        )
                ))
                .toList();

        /* 품목에 따라서 검증이 돌아가는 도구가 다름 */
        List<String> affectedTools = switch (category) {
                case "CPU" -> List.of("price");
                case "MOTHERBOARD", "RAM" -> List.of("compatibility", "price");
                case "GPU" -> List.of("compatibility", "performance", "price");
                case "COOLER" -> List.of("compatibility", "performance", "price");
                case "CASE" -> List.of("compatibility", "size", "performance", "price");
                case "PSU" -> List.of("compatibility", "power", "size", "performance", "price");
                case "STORAGE" -> List.of("compatibility", "performance", "price");
                default -> List.of("compatibility", "power", "size", "performance", "price");
        };

        /* 사용할 도구를 기준으로 삼아 검증 결과를 호출 */
        List<Map<String, Object>> results =
                toolService.checkBuild(
                        toolParts,
                        budget,
                        affectedTools
                );

        return results.stream()
                .noneMatch(result ->
                        "FAIL".equals(result.get("status"))
                );
        }
}


