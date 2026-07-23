package com.buildgraph.prototype.aichat.chat.schema;

import java.util.List;
import java.util.Map;

import com.buildgraph.prototype.common.MockData;

public final class AiChatOutputSchema {

    private AiChatOutputSchema() {
    }

    public static Map<String, Object> schema() {
        return MockData.map(
                "type", "object",
                "additionalProperties", false,
                "properties", MockData.map(
                        "conversationMode", MockData.map("type", "boolean"),
                        "replyMessage", MockData.map("type", "string"),
                        "action", actionSchema(),
                        "contextPatch", contextPatchSchema()
                ),
                "required", List.of(
                        "conversationMode",
                        "replyMessage",
                        "action",
                        "contextPatch"
                )
        );
    }

    private static Map<String, Object> actionSchema() {
        return MockData.map(
                "type", List.of("object", "null"),
                "additionalProperties", false,
                "properties", MockData.map(
                        "type", actionTypeSchema(),
                        "selectedCategory", categorySchema(),
                        "ragQuery", ragQuerySchema()
                ),
                "required", List.of(
                        "type",
                        "selectedCategory",
                        "ragQuery"
                )
        );
    }

    private static Map<String, Object> actionTypeSchema() {
        return MockData.map(
                "type", "string",
                "enum", List.of(
                        "FULL_BUILD_RECOMMEND",
                        "PART_RECOMMEND",
                        "BUILD_MODIFY"
                )
        );
    }

    private static Map<String, Object> categorySchema() {
        return MockData.map(
                "type", List.of("string", "null"),
                "enum", java.util.Arrays.asList(
                        "CPU", "MOTHERBOARD", "RAM", "GPU",
                        "STORAGE", "PSU", "CASE", "COOLER",
                        null
                )
        );
    }

    private static Map<String, Object> ragQuerySchema() {
        return MockData.map(
                "type", "object",
                "additionalProperties", false,
                "properties", MockData.map(
                        "performance", scoreSchema(),
                        "value", scoreSchema()
                ),
                "required", List.of("performance", "value")
        );
    }

    private static Map<String, Object> scoreSchema() {
        return MockData.map(
                "type", "number",
                "minimum", 0,
                "maximum", 1
        );
    }

    private static Map<String, Object> contextPatchSchema() {
        return MockData.map(
                "type", "object",
                "additionalProperties", false,
                "properties", MockData.map(
                        "budget", MockData.map(
                                "type", List.of("integer", "null")
                        ),
                        "usageTags", MockData.map(
                                "type", "array",
                                "items", MockData.map("type", "string")
                        )
                ),
                "required", List.of("budget", "usageTags")
        );
    }
}
