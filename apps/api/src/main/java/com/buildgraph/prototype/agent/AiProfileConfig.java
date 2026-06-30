package com.buildgraph.prototype.agent;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiProfileConfig {
    private final AiProfile defaultAsChatProfile;
    private final Map<AiProfile, AiProfileDefinition> definitions;

    public AiProfileConfig(
            @Value("${ai.as-chat.default-profile:AS_CHAT_FAST}") String defaultAsChatProfile,
            @Value("${ai.as-chat.fast.model:gpt-5.5}") String fastModel,
            @Value("${ai.as-chat.fast.reasoning-effort:low}") String fastReasoningEffort,
            @Value("${ai.as-chat.fast.rag-top-k:2}") int fastRagTopK,
            @Value("${ai.as-chat.fast.max-output-tokens:900}") int fastMaxOutputTokens,
            @Value("${ai.as-chat.fast.recent-message-limit:3}") int fastRecentMessageLimit,
            @Value("${ai.as-chat.fast.include-evidence-chunk-text:false}") boolean fastIncludeEvidenceChunkText,
            @Value("${ai.as-chat.fast.include-tool-result-payload:false}") boolean fastIncludeToolResultPayload,
            @Value("${ai.as-chat.fast.use-compact-prompt:true}") boolean fastUseCompactPrompt,
            @Value("${ai.as-chat.nano-fast.model:gpt-5.4-nano}") String nanoFastModel,
            @Value("${ai.as-chat.nano-fast.reasoning-effort:low}") String nanoFastReasoningEffort,
            @Value("${ai.as-chat.nano-fast.rag-top-k:2}") int nanoFastRagTopK,
            @Value("${ai.as-chat.nano-fast.max-output-tokens:700}") int nanoFastMaxOutputTokens,
            @Value("${ai.as-chat.nano-fast.recent-message-limit:2}") int nanoFastRecentMessageLimit,
            @Value("${ai.as-chat.nano-fast.include-evidence-chunk-text:false}") boolean nanoFastIncludeEvidenceChunkText,
            @Value("${ai.as-chat.nano-fast.include-tool-result-payload:false}") boolean nanoFastIncludeToolResultPayload,
            @Value("${ai.as-chat.nano-fast.use-compact-prompt:true}") boolean nanoFastUseCompactPrompt,
            @Value("${ai.as-chat.balanced.model:gpt-5.5}") String balancedModel,
            @Value("${ai.as-chat.balanced.reasoning-effort:low}") String balancedReasoningEffort,
            @Value("${ai.as-chat.balanced.rag-top-k:3}") int balancedRagTopK,
            @Value("${ai.as-chat.balanced.max-output-tokens:1100}") int balancedMaxOutputTokens,
            @Value("${ai.as-chat.balanced.recent-message-limit:4}") int balancedRecentMessageLimit,
            @Value("${ai.as-chat.balanced.include-evidence-chunk-text:false}") boolean balancedIncludeEvidenceChunkText,
            @Value("${ai.as-chat.balanced.include-tool-result-payload:false}") boolean balancedIncludeToolResultPayload,
            @Value("${ai.as-chat.balanced.use-compact-prompt:true}") boolean balancedUseCompactPrompt,
            @Value("${ai.as-chat.high-quality.model:gpt-5.5}") String highQualityModel,
            @Value("${ai.as-chat.high-quality.reasoning-effort:medium}") String highQualityReasoningEffort,
            @Value("${ai.as-chat.high-quality.rag-top-k:5}") int highQualityRagTopK,
            @Value("${ai.as-chat.high-quality.max-output-tokens:2600}") int highQualityMaxOutputTokens,
            @Value("${ai.as-chat.high-quality.recent-message-limit:5}") int highQualityRecentMessageLimit,
            @Value("${ai.as-chat.high-quality.include-evidence-chunk-text:true}") boolean highQualityIncludeEvidenceChunkText,
            @Value("${ai.as-chat.high-quality.include-tool-result-payload:false}") boolean highQualityIncludeToolResultPayload,
            @Value("${ai.as-chat.high-quality.use-compact-prompt:true}") boolean highQualityUseCompactPrompt
    ) {
        this.defaultAsChatProfile = parseProfile(defaultAsChatProfile);
        this.definitions = new EnumMap<>(AiProfile.class);
        definitions.put(AiProfile.AS_CHAT_FAST, definition(
                AiProfile.AS_CHAT_FAST,
                LlmProvider.OPENAI,
                fastModel,
                fastReasoningEffort,
                fastRagTopK,
                "as-chat-v3-fast-compact",
                fastMaxOutputTokens,
                fastRecentMessageLimit,
                fastIncludeEvidenceChunkText,
                fastIncludeToolResultPayload,
                fastUseCompactPrompt
        ));
        definitions.put(AiProfile.AS_CHAT_NANO_FAST, definition(
                AiProfile.AS_CHAT_NANO_FAST,
                LlmProvider.OPENAI,
                nanoFastModel,
                nanoFastReasoningEffort,
                nanoFastRagTopK,
                "as-chat-v4-nano-fast-compact",
                nanoFastMaxOutputTokens,
                nanoFastRecentMessageLimit,
                nanoFastIncludeEvidenceChunkText,
                nanoFastIncludeToolResultPayload,
                nanoFastUseCompactPrompt
        ));
        definitions.put(AiProfile.AS_CHAT_BALANCED, definition(
                AiProfile.AS_CHAT_BALANCED,
                LlmProvider.OPENAI,
                balancedModel,
                balancedReasoningEffort,
                balancedRagTopK,
                "as-chat-v3-balanced-compact",
                balancedMaxOutputTokens,
                balancedRecentMessageLimit,
                balancedIncludeEvidenceChunkText,
                balancedIncludeToolResultPayload,
                balancedUseCompactPrompt
        ));
        definitions.put(AiProfile.AS_CHAT_HIGH_QUALITY, definition(
                AiProfile.AS_CHAT_HIGH_QUALITY,
                LlmProvider.OPENAI,
                highQualityModel,
                highQualityReasoningEffort,
                highQualityRagTopK,
                "as-chat-v3-high-quality-full",
                highQualityMaxOutputTokens,
                highQualityRecentMessageLimit,
                highQualityIncludeEvidenceChunkText,
                highQualityIncludeToolResultPayload,
                highQualityUseCompactPrompt
        ));
    }

    public AiProfileDefinition defaultAsChatProfile() {
        return definitions.get(defaultAsChatProfile);
    }

    public AiProfileDefinition asChatProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return defaultAsChatProfile();
        }
        return asChatProfile(parseProfile(profileName));
    }

    public AiProfileDefinition asChatProfile(AiProfile profile) {
        return definitions.get(profile);
    }

    private static AiProfileDefinition definition(
            AiProfile profile,
            LlmProvider provider,
            String model,
            String reasoningEffort,
            int ragTopK,
            String promptVersion,
            int maxOutputTokens,
            int recentMessageLimit,
            boolean includeEvidenceChunkText,
            boolean includeToolResultPayload,
            boolean useCompactPrompt
    ) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException(profile + " model 설정이 필요합니다.");
        }
        if (provider == null) {
            throw new IllegalArgumentException(profile + " provider 설정이 필요합니다.");
        }
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            throw new IllegalArgumentException(profile + " reasoning effort 설정이 필요합니다.");
        }
        if (ragTopK <= 0) {
            throw new IllegalArgumentException(profile + " ragTopK는 1 이상이어야 합니다.");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException(profile + " maxOutputTokens는 1 이상이어야 합니다.");
        }
        if (recentMessageLimit < 0) {
            throw new IllegalArgumentException(profile + " recentMessageLimit은 0 이상이어야 합니다.");
        }
        return new AiProfileDefinition(
                profile,
                provider,
                model.trim(),
                reasoningEffort.trim().toLowerCase(Locale.ROOT),
                ragTopK,
                promptVersion,
                maxOutputTokens,
                recentMessageLimit,
                includeEvidenceChunkText,
                includeToolResultPayload,
                useCompactPrompt
        );
    }

    private static AiProfile parseProfile(String value) {
        try {
            return AiProfile.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception error) {
            throw new IllegalArgumentException("지원하지 않는 AS Chat AI profile입니다: " + value, error);
        }
    }
}
