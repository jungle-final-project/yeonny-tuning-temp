package com.buildgraph.prototype.agent;

import org.springframework.stereotype.Component;

@Component
public class AsChatProfilePolicy {
    private final AiProfileConfig aiProfileConfig;

    public AsChatProfilePolicy(AiProfileConfig aiProfileConfig) {
        this.aiProfileConfig = aiProfileConfig;
    }

    public AiProfileDefinition resolve(
            String requestedAiProfile,
            String ticketSymptom,
            String logSummary,
            String userMessage
    ) {
        if (requestedAiProfile != null && !requestedAiProfile.isBlank()) {
            return aiProfileConfig.asChatProfile(requestedAiProfile);
        }
        AiProfileDefinition defaultProfile = aiProfileConfig.defaultAsChatProfile();
        if (!highRiskAsCase(ticketSymptom, logSummary, userMessage)) {
            return defaultProfile;
        }
        AiProfile escalationTarget = escalationTarget(defaultProfile.profile());
        return escalationTarget == defaultProfile.profile()
                ? defaultProfile
                : aiProfileConfig.asChatProfile(escalationTarget);
    }

    private static AiProfile escalationTarget(AiProfile profile) {
        return switch (profile) {
            case AS_CHAT_FAST, AS_CHAT_NANO_FAST -> AiProfile.AS_CHAT_BALANCED;
            case AS_CHAT_BALANCED, AS_CHAT_HIGH_QUALITY -> profile;
        };
    }

    private static boolean highRiskAsCase(String ticketSymptom, String logSummary, String userMessage) {
        String text = (safe(ticketSymptom) + " " + safe(logSummary) + " " + safe(userMessage)).toLowerCase();
        return text.contains("전원")
                || text.contains("재부팅")
                || text.contains("꺼지")
                || text.contains("타는")
                || text.contains("연기")
                || text.contains("95도")
                || text.contains("100도")
                || text.contains("power")
                || text.contains("psu");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
