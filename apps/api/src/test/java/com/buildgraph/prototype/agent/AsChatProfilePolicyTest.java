package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AsChatProfilePolicyTest {
    @Test
    void explicitProfileBypassesHighRiskEscalation() {
        AsChatProfilePolicy policy = new AsChatProfilePolicy(config("AS_CHAT_FAST"));

        AiProfileDefinition profile = policy.resolve(
                "AS_CHAT_NANO_FAST",
                "전원이 꺼지고 재부팅됩니다.",
                "",
                "파워 문제인가요?"
        );

        assertThat(profile.profile()).isEqualTo(AiProfile.AS_CHAT_NANO_FAST);
    }

    @Test
    void nanoDefaultEscalatesHighRiskCaseToBalanced() {
        AsChatProfilePolicy policy = new AsChatProfilePolicy(config("AS_CHAT_NANO_FAST"));

        AiProfileDefinition profile = policy.resolve(
                null,
                "고사양 게임에서 전원이 꺼집니다.",
                "",
                "파워와 전원 문제를 확인하고 싶습니다."
        );

        assertThat(profile.profile()).isEqualTo(AiProfile.AS_CHAT_BALANCED);
    }

    @Test
    void nanoDefaultStaysNanoForLowRiskCase() {
        AsChatProfilePolicy policy = new AsChatProfilePolicy(config("AS_CHAT_NANO_FAST"));

        AiProfileDefinition profile = policy.resolve(
                null,
                "IDE와 브라우저를 많이 열면 RAM 사용률이 높습니다.",
                "",
                "메모리 업그레이드가 필요할까요?"
        );

        assertThat(profile.profile()).isEqualTo(AiProfile.AS_CHAT_NANO_FAST);
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
