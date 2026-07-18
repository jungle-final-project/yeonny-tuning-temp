package com.buildgraph.prototype.agent.mock;

import com.buildgraph.prototype.agent.AiChatAction;
import com.buildgraph.prototype.agent.AiChatActionType;
import com.buildgraph.prototype.agent.AiChatEngineRequest;
import com.buildgraph.prototype.agent.AiChatEngineResponse;
import com.buildgraph.prototype.agent.AiChatIntent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component

public class MockAiChatResponse {

    /* 2차 대화: 예산 선택지를 기반으로 응답 객체 생성 */
    public AiChatEngineResponse budgetFollowUp(AiChatEngineRequest request) {
        return new AiChatEngineResponse(
                "사이버펑크 60FPS 기준으로 예산대를 선택해 주세요.",
                AiChatIntent.ASK_FOLLOW_UP,
                List.of(new AiChatAction(
                        AiChatActionType.ASK_FOLLOW_UP,
                        "예산 선택",
                        Map.of(
                                "missing", List.of("budget"),
                                "message", request.message()
                        )
                )),
                List.of(),
                List.of(),
                Map.of(
                        "rawMessage", request.message(),
                        "usageTags", List.of("GAMING", "CYBERPUNK"),
                        "missingSlots", List.of("budget")
                ),
                List.of(),
                List.of(),
                null
        );
    }
}
