package com.buildgraph.prototype.quoteagent.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiChatClientTest {
    @Test
    void configuredWhenApiKeyIsPresent() {
        AiChatClient client = new AiChatClient(
                "https://api.openai.com/v1/",
                "test-api-key",
                "gpt-5.5",
                "medium"
        );

        assertThat(client.isConfigured()).isTrue();
        assertThat(client.model()).isEqualTo("gpt-5.5");
    }

    @Test
    void notConfiguredWhenApiKeyIsBlank() {
        AiChatClient client = new AiChatClient(
                "https://api.openai.com/v1",
                " ",
                "gpt-5.5",
                "medium"
        );

        assertThat(client.isConfigured()).isFalse();
    }
}
