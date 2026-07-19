package com.buildgraph.prototype.agent.mock;

import com.buildgraph.prototype.agent.AiChatEngineRequest;
import com.buildgraph.prototype.agent.AiChatEngineResponse;
import com.buildgraph.prototype.agent.DefaultAiChatEngine;


import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MockAiChatEngine {

    /* 기존 Engine을 가져와서 Mock 형태로 */
    private final DefaultAiChatEngine delegate;
    private final MockAiChatResponse responses;
    private final long delayMs;

    public MockAiChatEngine(
            DefaultAiChatEngine delegate,
            MockAiChatResponse responses,
            @Value("${ai.build-chat.test-delay-ms:3000}") long delayMs
    ) {
        this.delegate = delegate;
        this.responses = responses;
        this.delayMs = delayMs;
    }

    public AiChatEngineResponse respond(AiChatEngineRequest request) {
        /* 의도적인 지연 */
        delay();

        String message = request.message()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");

        /* 2차 대화만 고정 Mock 응답하도록 설계*/
        if (message.contains("사이버펑크")
                && message.contains("60fps")) {
            return responses.budgetFollowUp(request);
        }

        /* if 구문에서 안걸릴 경우 기존 엔진에서 돎
           : 1, 3차 대화는 룰 기반 캐싱이라 LLM 부하 극미량 */
        return delegate.respond(request);
    }

    /* 지연 시나리오 구현 */
    private void delay() {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mock AI response interrupted", error);
        }
    }
}
