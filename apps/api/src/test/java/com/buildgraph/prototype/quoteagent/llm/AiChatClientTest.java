package com.buildgraph.prototype.quoteagent.llm;

import com.buildgraph.prototype.quoteagent.chat.*;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.quoteagent.tools.*;
import com.buildgraph.prototype.opsagent.as.*;
import com.buildgraph.prototype.opsagent.profile.*;
import com.buildgraph.prototype.opsagent.trace.*;
import com.buildgraph.prototype.opsagent.runner.*;

import com.buildgraph.prototype.quoteagent.chat.*;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.quoteagent.tools.*;

import static org.assertj.core.api.Assertions.assertThat;

import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiChatClientTest {
    @Test
    void structuredJsonRequestUsesResponsesJsonSchemaAndReasoningForDefaultModel() {
        AiChatClient client = new AiChatClient(
                "https://api.openai.com/v1",
                "test-api-key",
                "gpt-5.5",
                "medium"
        );
        Map<String, Object> schema = MockData.map(
                "type", "object",
                "required", List.of("assistantMessage"),
                "properties", MockData.map(
                        "assistantMessage", MockData.map("type", "string")
                ),
                "additionalProperties", false
        );

        Map<String, Object> request = client.requestBody(
                "system",
                "user",
                MockData.map(
                        "text", MockData.map(
                                "format", MockData.map(
                                        "type", "json_schema",
                                        "name", "as_chat_response",
                                        "schema", schema,
                                        "strict", true
                                )
                        )
                )
        );

        assertThat(request).containsEntry("model", "gpt-5.5");
        assertThat(request).containsEntry("instructions", "system");
        assertThat(request).containsEntry("input", "user");
        assertThat(request.get("reasoning")).isEqualTo(Map.of("effort", "medium"));

        @SuppressWarnings("unchecked")
        Map<String, Object> text = (Map<String, Object>) request.get("text");
        @SuppressWarnings("unchecked")
        Map<String, Object> format = (Map<String, Object>) text.get("format");
        assertThat(format).containsEntry("type", "json_schema");
        assertThat(format).containsEntry("name", "as_chat_response");
        assertThat(format).containsEntry("schema", schema);
        assertThat(format).containsEntry("strict", true);
    }

    @Test
    void legacyNonReasoningModelDoesNotReceiveReasoningField() {
        AiChatClient client = new AiChatClient(
                "https://api.openai.com/v1",
                "test-api-key",
                "legacy-chat-model",
                "medium"
        );

        Map<String, Object> request = client.requestBody("system", "user", Map.of());

        assertThat(request).doesNotContainKey("reasoning");
    }

    @Test
    void requestBodyCanUseProfileSpecificModelAndReasoning() {
        AiChatClient client = new AiChatClient(
                "https://api.openai.com/v1",
                "test-api-key",
                "gpt-5.5",
                "medium"
        );

        Map<String, Object> request = client.requestBody(
                "system",
                "user",
                Map.of(),
                "gpt-5.5",
                "high",
                900
        );

        assertThat(request).containsEntry("model", "gpt-5.5");
        assertThat(request.get("reasoning")).isEqualTo(Map.of("effort", "high"));
        assertThat(request).containsEntry("max_output_tokens", 900);
    }
}
