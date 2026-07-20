package com.buildgraph.prototype.quoteagent.chat;

import org.junit.jupiter.api.Test;

import com.buildgraph.prototype.quoteagent.chat.dto.AiChatRequestDto;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAiChatEngineEvaluationTest {
    @Test
    void aiChatRequestCarriesOnlyMessageAndSessionId() {
        AiChatRequestDto request = new AiChatRequestDto("게임용 견적 추천해줘", "session-eval");

        assertThat(request.message()).isEqualTo("게임용 견적 추천해줘");
        assertThat(request.sessionId()).isEqualTo("session-eval");
    }
}
