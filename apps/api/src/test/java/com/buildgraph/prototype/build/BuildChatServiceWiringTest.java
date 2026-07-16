package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.buildgraph.prototype.agent.AiChatEngine;
import com.buildgraph.prototype.agent.AiProfileConfig;
import com.buildgraph.prototype.agent.PartReplacementRanker;
import com.buildgraph.prototype.agent.PartRouteResolver;
import com.buildgraph.prototype.part.catalog.PartAliasReviewService;
import com.buildgraph.prototype.part.query.PartQuery;
import com.buildgraph.prototype.part.tool.ToolCheckService;
import com.buildgraph.prototype.recommendation.CandidateReranker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class BuildChatServiceWiringTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "ai.build-chat.cache.enabled=true",
                    "ai.build-chat.cache.ttl-seconds=600"
            )
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(ToolCheckService.class, () -> mock(ToolCheckService.class))
            .withBean(PartQuery.class, () -> mock(PartQuery.class))
            .withBean(AiChatEngine.class, () -> mock(AiChatEngine.class))
            .withBean(AiProfileConfig.class, () -> mock(AiProfileConfig.class))
            .withBean(PartAliasReviewService.class, () -> mock(PartAliasReviewService.class))
            .withBean(PartReplacementRanker.class)
            .withBean(PartRouteResolver.class)
            .withBean(CandidateReranker.class, () -> mock(CandidateReranker.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(BuildChatCacheService.class)
            .withBean(BuildChatIntentRouter.class)
            .withBean(BuildChatSemanticCacheService.class, BuildChatSemanticCacheService::disabled)
            .withBean(BuildCompositeScoreService.class)
            .withBean(BuildScoreAdviceService.class)
            .withBean(BuildEvaluationService.class)
            .withBean(BuildChatService.class);

    @Test
    void springRuntimeWiresBuildChatCacheServiceIntoBuildChatService() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(BuildChatService.class);
            assertThat(context).hasSingleBean(BuildChatCacheService.class);

            BuildChatService buildChatService = context.getBean(BuildChatService.class);
            BuildChatCacheService cacheService = context.getBean(BuildChatCacheService.class);

            assertThat(ReflectionTestUtils.getField(buildChatService, "buildChatCacheService"))
                    .isSameAs(cacheService);
            assertThat(ReflectionTestUtils.getField(buildChatService, "buildEvaluationService"))
                    .isSameAs(context.getBean(BuildEvaluationService.class));
        });
    }
}
