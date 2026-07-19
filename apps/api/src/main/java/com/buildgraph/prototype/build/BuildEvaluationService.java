package com.buildgraph.prototype.build;

import com.buildgraph.prototype.part.query.PartQuery;
import com.buildgraph.prototype.part.query.PartQueryCachedLoader;
import com.buildgraph.prototype.part.tool.ToolApplicabilityPolicy;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.part.tool.ToolCheckService;
import com.buildgraph.prototype.quote.QuoteDraftReadCache;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BuildEvaluationService {
    private final PartQuery partQuery;
    private final ToolCheckService toolCheckService;
    private final BuildCompositeScoreService buildCompositeScoreService;
    private final BuildScoreAdviceService buildScoreAdviceService;
    // 현재 draft 파츠의 단일 출처(쓰기 즉시 무효화) — resolve 경로의 draft 재조회를 캐시로 대체한다.
    private final QuoteDraftReadCache draftReadCache;

    @Autowired
    public BuildEvaluationService(
            PartQuery partQuery,
            ToolCheckService toolCheckService,
            BuildCompositeScoreService buildCompositeScoreService,
            BuildScoreAdviceService buildScoreAdviceService,
            QuoteDraftReadCache draftReadCache
    ) {
        this.partQuery = partQuery;
        this.toolCheckService = toolCheckService;
        this.buildCompositeScoreService = buildCompositeScoreService;
        this.buildScoreAdviceService = buildScoreAdviceService;
        this.draftReadCache = draftReadCache;
    }

    // 기존 테스트/내부 편의 생성자 — draft 캐시 없이 기존 PartQuery 경로를 그대로 쓴다(테스트의 jdbc 목 유지).
    public BuildEvaluationService(
            JdbcTemplate jdbcTemplate,
            ToolCheckService toolCheckService,
            BuildCompositeScoreService buildCompositeScoreService,
            BuildScoreAdviceService buildScoreAdviceService
    ) {
        this(
                new PartQuery(jdbcTemplate, new PartQueryCachedLoader(new NoOpCacheManager(), jdbcTemplate)),
                toolCheckService,
                buildCompositeScoreService,
                buildScoreAdviceService,
                null
        );
    }

    public BuildEvaluation evaluate(
            List<ToolBuildPart> parts,
            Integer requestedBudget,
            String focusCategory,
            String focusTool
    ) {
        List<ToolBuildPart> safeParts = parts == null ? List.of() : parts;
        int requestedBudgetWon = requestedBudget != null && requestedBudget > 0
                ? requestedBudget
                : total(safeParts);
        List<Map<String, Object>> rawToolResults = safeParts.isEmpty()
                ? List.of()
                : toolCheckService.checkBuild(safeParts, requestedBudgetWon);
        return evaluateSnapshot(safeParts, rawToolResults, requestedBudget, focusCategory, focusTool);
    }

    /**
     * 이미 Tool 검증을 마친 추천 카드도 그래프/현재 견적과 동일한 점수·조언 정책으로 평가한다.
     * Tool을 다시 호출하지 않아 추천 응답 지연을 늘리지 않는다.
     */
    public BuildEvaluation evaluateSnapshot(
            List<ToolBuildPart> parts,
            List<Map<String, Object>> rawToolResults,
            Integer requestedBudget,
            String focusCategory,
            String focusTool
    ) {
        List<ToolBuildPart> safeParts = parts == null ? List.of() : parts;
        int totalPrice = total(safeParts);
        int budgetWon = requestedBudget != null && requestedBudget > 0 ? requestedBudget : totalPrice;
        List<Map<String, Object>> toolResults = safeParts.isEmpty()
                ? List.of()
                : ToolApplicabilityPolicy.applicableToolResults(
                        rawToolResults == null ? List.of() : rawToolResults,
                        safeParts
                );
        Map<String, Object> compositeScore = buildCompositeScoreService.score(
                safeParts,
                toolResults,
                budgetWon,
                totalPrice
        );
        Map<String, Object> buildAssessment = buildScoreAdviceService.assess(
                safeParts,
                toolResults,
                compositeScore,
                focusCategory,
                focusTool
        );
        return new BuildEvaluation(safeParts, totalPrice, budgetWon, toolResults, compositeScore, buildAssessment);
    }

    public BuildEvaluation evaluateCurrentDraft(
            long userInternalId,
            Integer requestedBudget,
            String focusCategory,
            String focusTool
    ) {
        // 운영 빈은 draft read 캐시(쓰기 즉시 무효화)를 타고, 캐시 없는 편의 생성자(테스트)는 기존 SQL 경로 유지.
        List<ToolBuildPart> parts = draftReadCache != null
                ? draftReadCache.toolParts(userInternalId)
                : partQuery.partsByActiveDraftUserId(userInternalId);
        return evaluate(parts, requestedBudget, focusCategory, focusTool);
    }

    private static int total(List<ToolBuildPart> parts) {
        return parts.stream()
                .mapToInt(part -> Math.max(0, part.price() == null ? 0 : part.price()) * part.effectiveQuantity())
                .sum();
    }

    public record BuildEvaluation(
            List<ToolBuildPart> parts,
            int totalPrice,
            int budgetWon,
            List<Map<String, Object>> toolResults,
            Map<String, Object> compositeScore,
            Map<String, Object> buildAssessment
    ) {
    }
}
