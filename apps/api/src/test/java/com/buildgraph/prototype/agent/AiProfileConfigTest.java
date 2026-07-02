package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiProfileConfigTest {
    @Test
    void defaultAsChatProfileUsesMeasuredGpt54MiniProfile() {
        AiProfileConfig config = config("AS_CHAT_54_MINI_FAST", "BUILD_CHAT_54_MINI_FAST");

        AiProfileDefinition profile = config.defaultAsChatProfile();

        assertThat(profile.profile()).isEqualTo(AiProfile.AS_CHAT_54_MINI_FAST);
        assertThat(profile.provider()).isEqualTo(LlmProvider.OPENAI);
        assertThat(profile.model()).isEqualTo("gpt-5.4-mini");
        assertThat(profile.reasoningEffort()).isEqualTo("low");
        assertThat(profile.ragTopK()).isEqualTo(2);
        assertThat(profile.promptVersion()).isEqualTo("as-chat-v5-gpt54-mini-fast-compact");
        assertThat(profile.maxOutputTokens()).isEqualTo(850);
        assertThat(profile.recentMessageLimit()).isEqualTo(3);
        assertThat(profile.includeEvidenceChunkText()).isFalse();
        assertThat(profile.includeToolResultPayload()).isFalse();
        assertThat(profile.useCompactPrompt()).isTrue();
    }

    @Test
    void explicitHeaderProfileCanSelectGpt54MiniAsProfile() {
        AiProfileConfig config = config("AS_CHAT_FAST", "BUILD_CHAT_FAST");

        AiProfileDefinition profile = config.asChatProfile("AS_CHAT_54_MINI_FAST");

        assertThat(profile.profile()).isEqualTo(AiProfile.AS_CHAT_54_MINI_FAST);
        assertThat(profile.model()).isEqualTo("gpt-5.4-mini");
        assertThat(profile.reasoningEffort()).isEqualTo("low");
        assertThat(profile.ragTopK()).isEqualTo(2);
        assertThat(profile.promptVersion()).isEqualTo("as-chat-v5-gpt54-mini-fast-compact");
        assertThat(profile.maxOutputTokens()).isEqualTo(850);
        assertThat(profile.recentMessageLimit()).isEqualTo(3);
    }

    @Test
    void explicitHeaderProfileCanSelectNanoProfile() {
        AiProfileConfig config = config("AS_CHAT_FAST", "BUILD_CHAT_FAST");

        AiProfileDefinition profile = config.asChatProfile("AS_CHAT_NANO_FAST");

        assertThat(profile.profile()).isEqualTo(AiProfile.AS_CHAT_NANO_FAST);
        assertThat(profile.provider()).isEqualTo(LlmProvider.OPENAI);
        assertThat(profile.model()).isEqualTo("gpt-5.4-nano");
        assertThat(profile.promptVersion()).isEqualTo("as-chat-v4-nano-fast-compact");
        assertThat(profile.maxOutputTokens()).isEqualTo(700);
        assertThat(profile.recentMessageLimit()).isEqualTo(2);
    }

    @Test
    void buildChatProfileFamilyIsSeparatedFromAsChatProfile() {
        AiProfileConfig config = config("AS_CHAT_FAST", "BUILD_CHAT_54_FAST");

        AiProfileDefinition profile = config.buildChatProfile(null);

        assertThat(profile.profile()).isEqualTo(AiProfile.BUILD_CHAT_54_FAST);
        assertThat(profile.model()).isEqualTo("gpt-5.4");
        assertThat(profile.promptVersion()).isEqualTo("build-chat-v1-gpt54-fast");
        assertThat(profile.ragTopK()).isEqualTo(3);
        assertThatThrownBy(() -> config.asChatProfile("BUILD_CHAT_FAST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AS Chat profile이 아닙니다");
    }

    @Test
    void defaultBuildChatProfileUsesMeasuredGpt54MiniProfile() {
        AiProfileConfig config = config("AS_CHAT_54_MINI_FAST", "BUILD_CHAT_54_MINI_FAST");

        AiProfileDefinition profile = config.buildChatProfile(null);

        assertThat(profile.profile()).isEqualTo(AiProfile.BUILD_CHAT_54_MINI_FAST);
        assertThat(profile.provider()).isEqualTo(LlmProvider.OPENAI);
        assertThat(profile.model()).isEqualTo("gpt-5.4-mini");
        assertThat(profile.reasoningEffort()).isEqualTo("low");
        assertThat(profile.ragTopK()).isEqualTo(3);
        assertThat(profile.promptVersion()).isEqualTo("build-chat-v1-gpt54-mini-fast");
        assertThat(profile.maxOutputTokens()).isEqualTo(850);
        assertThat(profile.recentMessageLimit()).isEqualTo(0);
    }

    @Test
    void unknownDefaultProfileFailsFast() {
        assertThatThrownBy(() -> config("UNKNOWN", "BUILD_CHAT_FAST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 AI profile");
    }

    static AiProfileConfig config(String defaultAsProfile, String defaultBuildProfile) {
        return new AiProfileConfig(
                defaultAsProfile,
                defaultBuildProfile,
                "gpt-5.5",
                "low",
                2,
                900,
                3,
                false,
                false,
                true,
                "gpt-5.4",
                "low",
                2,
                900,
                3,
                false,
                false,
                true,
                "gpt-5.4-mini",
                "low",
                2,
                850,
                3,
                false,
                false,
                true,
                "gpt-5.4-nano",
                "low",
                2,
                700,
                2,
                false,
                false,
                true,
                "gpt-5.5",
                "low",
                3,
                1100,
                4,
                false,
                false,
                true,
                "gpt-5.5",
                "medium",
                5,
                2600,
                5,
                true,
                false,
                true,
                "gpt-5.5",
                "low",
                3,
                900,
                0,
                "gpt-5.4",
                "low",
                3,
                900,
                0,
                "gpt-5.4-mini",
                "low",
                3,
                850,
                0
        );
    }
}
