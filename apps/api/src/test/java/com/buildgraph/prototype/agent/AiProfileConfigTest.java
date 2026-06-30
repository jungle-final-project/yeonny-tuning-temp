package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiProfileConfigTest {
    @Test
    void defaultProfileCanUseFastProfile() {
        AiProfileConfig config = config("AS_CHAT_FAST");

        AiProfileDefinition profile = config.defaultAsChatProfile();

        assertThat(profile.profile()).isEqualTo(AiProfile.AS_CHAT_FAST);
        assertThat(profile.provider()).isEqualTo(LlmProvider.OPENAI);
        assertThat(profile.model()).isEqualTo("gpt-5.5");
        assertThat(profile.reasoningEffort()).isEqualTo("low");
        assertThat(profile.ragTopK()).isEqualTo(2);
        assertThat(profile.promptVersion()).isEqualTo("as-chat-v3-fast-compact");
        assertThat(profile.maxOutputTokens()).isEqualTo(900);
        assertThat(profile.recentMessageLimit()).isEqualTo(3);
        assertThat(profile.includeEvidenceChunkText()).isFalse();
        assertThat(profile.includeToolResultPayload()).isFalse();
        assertThat(profile.useCompactPrompt()).isTrue();
    }

    @Test
    void explicitHeaderProfileSelectsFastProfile() {
        AiProfileConfig config = config("AS_CHAT_BALANCED");

        AiProfileDefinition profile = config.asChatProfile("AS_CHAT_FAST");

        assertThat(profile.profile()).isEqualTo(AiProfile.AS_CHAT_FAST);
        assertThat(profile.model()).isEqualTo("gpt-5.5");
        assertThat(profile.reasoningEffort()).isEqualTo("low");
        assertThat(profile.ragTopK()).isEqualTo(2);
    }

    @Test
    void explicitHeaderProfileCanSelectNanoProfile() {
        AiProfileConfig config = config("AS_CHAT_FAST");

        AiProfileDefinition profile = config.asChatProfile("AS_CHAT_NANO_FAST");

        assertThat(profile.profile()).isEqualTo(AiProfile.AS_CHAT_NANO_FAST);
        assertThat(profile.provider()).isEqualTo(LlmProvider.OPENAI);
        assertThat(profile.model()).isEqualTo("gpt-5.4-nano");
        assertThat(profile.reasoningEffort()).isEqualTo("low");
        assertThat(profile.ragTopK()).isEqualTo(2);
        assertThat(profile.promptVersion()).isEqualTo("as-chat-v4-nano-fast-compact");
        assertThat(profile.maxOutputTokens()).isEqualTo(700);
        assertThat(profile.recentMessageLimit()).isEqualTo(2);
    }

    @Test
    void unknownDefaultProfileFailsFast() {
        assertThatThrownBy(() -> config("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 AS Chat AI profile");
    }

    private static AiProfileConfig config(String defaultProfile) {
        return new AiProfileConfig(
                defaultProfile,
                "gpt-5.5",
                "low",
                2,
                900,
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
                true
        );
    }
}
