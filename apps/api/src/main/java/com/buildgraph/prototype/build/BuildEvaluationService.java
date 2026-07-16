package com.buildgraph.prototype.build;

import com.buildgraph.prototype.part.query.PartQuery;
import com.buildgraph.prototype.part.query.PartQueryCachedLoader;
import com.buildgraph.prototype.part.tool.ToolApplicabilityPolicy;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.part.tool.ToolCheckService;
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

    @Autowired
    public BuildEvaluationService(
            PartQuery partQuery,
            ToolCheckService toolCheckService,
            BuildCompositeScoreService buildCompositeScoreService,
            BuildScoreAdviceService buildScoreAdviceService
    ) {
        this.partQuery = partQuery;
        this.toolCheckService = toolCheckService;
        this.buildCompositeScoreService = buildCompositeScoreService;
        this.buildScoreAdviceService = buildScoreAdviceService;
    }

    // 기존 테스트/내부 편의 생성자도 동일한 PartQuery 경로를 사용한다. 실제 Bean은 위 생성자에서 Caffeine CacheManager를 주입받는다.
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
                buildScoreAdviceService
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
        return evaluate(partQuery.partsByActiveDraftUserId(userInternalId), requestedBudget, focusCategory, focusTool);
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
