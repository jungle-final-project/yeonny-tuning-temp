package com.buildgraph.prototype.agent;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiProfileConfig {
    private final AiProfile defaultAsChatProfile;
    private final AiProfile defaultBuildChatProfile;
    private final Map<AiProfile, AiProfileDefinition> definitions;

    public AiProfileConfig(
            @Value("${ai.as-chat.default-profile:AS_CHAT_FAST}") String defaultAsChatProfile,
            @Value("${ai.build-chat.default-profile:BUILD_CHAT_FAST}") String defaultBuildChatProfile,
            @Value("${ai.as-chat.fast.model:gpt-5.5}") String fastModel,
            @Value("${ai.as-chat.fast.reasoning-effort:low}") String fastReasoningEffort,
            @Value("${ai.as-chat.fast.rag-top-k:2}") int fastRagTopK,
            @Value("${ai.as-chat.fast.max-output-tokens:900}") int fastMaxOutputTokens,
            @Value("${ai.as-chat.fast.recent-message-limit:3}") int fastRecentMessageLimit,
            @Value("${ai.as-chat.fast.include-evidence-chunk-text:false}") boolean fastIncludeEvidenceChunkText,
            @Value("${ai.as-chat.fast.include-tool-result-payload:false}") boolean fastIncludeToolResultPayload,
            @Value("${ai.as-chat.fast.use-compact-prompt:true}") boolean fastUseCompactPrompt,
            @Value("${ai.as-chat.gpt54-fast.model:gpt-5.4}") String gpt54FastModel,
            @Value("${ai.as-chat.gpt54-fast.reasoning-effort:low}") String gpt54FastReasoningEffort,
            @Value("${ai.as-chat.gpt54-fast.rag-top-k:2}") int gpt54FastRagTopK,
            @Value("${ai.as-chat.gpt54-fast.max-output-tokens:900}") int gpt54FastMaxOutputTokens,
            @Value("${ai.as-chat.gpt54-fast.recent-message-limit:3}") int gpt54FastRecentMessageLimit,
            @Value("${ai.as-chat.gpt54-fast.include-evidence-chunk-text:false}") boolean gpt54FastIncludeEvidenceChunkText,
            @Value("${ai.as-chat.gpt54-fast.include-tool-result-payload:false}") boolean gpt54FastIncludeToolResultPayload,
            @Value("${ai.as-chat.gpt54-fast.use-compact-prompt:true}") boolean gpt54FastUseCompactPrompt,
            @Value("${ai.as-chat.gpt54-mini-fast.model:gpt-5.4-mini}") String gpt54MiniFastModel,
            @Value("${ai.as-chat.gpt54-mini-fast.reasoning-effort:low}") String gpt54MiniFastReasoningEffort,
            @Value("${ai.as-chat.gpt54-mini-fast.rag-top-k:2}") int gpt54MiniFastRagTopK,
            @Value("${ai.as-chat.gpt54-mini-fast.max-output-tokens:850}") int gpt54MiniFastMaxOutputTokens,
            @Value("${ai.as-chat.gpt54-mini-fast.recent-message-limit:3}") int gpt54MiniFastRecentMessageLimit,
            @Value("${ai.as-chat.gpt54-mini-fast.include-evidence-chunk-text:false}") boolean gpt54MiniFastIncludeEvidenceChunkText,
            @Value("${ai.as-chat.gpt54-mini-fast.include-tool-result-payload:false}") boolean gpt54MiniFastIncludeToolResultPayload,
            @Value("${ai.as-chat.gpt54-mini-fast.use-compact-prompt:true}") boolean gpt54MiniFastUseCompactPrompt,
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
            @Value("${ai.as-chat.high-quality.use-compact-prompt:true}") boolean highQualityUseCompactPrompt,
            @Value("${ai.build-chat.fast.model:gpt-5.5}") String buildChatFastModel,
            @Value("${ai.build-chat.fast.reasoning-effort:low}") String buildChatFastReasoningEffort,
            @Value("${ai.build-chat.fast.rag-top-k:3}") int buildChatFastRagTopK,
            @Value("${ai.build-chat.fast.max-output-tokens:900}") int buildChatFastMaxOutputTokens,
            @Value("${ai.build-chat.fast.recent-message-limit:0}") int buildChatFastRecentMessageLimit,
            @Value("${ai.build-chat.gpt54-fast.model:gpt-5.4}") String buildChat54FastModel,
            @Value("${ai.build-chat.gpt54-fast.reasoning-effort:low}") String buildChat54FastReasoningEffort,
            @Value("${ai.build-chat.gpt54-fast.rag-top-k:3}") int buildChat54FastRagTopK,
            @Value("${ai.build-chat.gpt54-fast.max-output-tokens:900}") int buildChat54FastMaxOutputTokens,
            @Value("${ai.build-chat.gpt54-fast.recent-message-limit:0}") int buildChat54FastRecentMessageLimit,
            @Value("${ai.build-chat.gpt54-mini-fast.model:gpt-5.4-mini}") String buildChat54MiniFastModel,
            @Value("${ai.build-chat.gpt54-mini-fast.reasoning-effort:low}") String buildChat54MiniFastReasoningEffort,
            @Value("${ai.build-chat.gpt54-mini-fast.rag-top-k:3}") int buildChat54MiniFastRagTopK,
            @Value("${ai.build-chat.gpt54-mini-fast.max-output-tokens:850}") int buildChat54MiniFastMaxOutputTokens,
            @Value("${ai.build-chat.gpt54-mini-fast.recent-message-limit:0}") int buildChat54MiniFastRecentMessageLimit
    ) {
        this.defaultAsChatProfile = parseProfile(defaultAsChatProfile);
        this.defaultBuildChatProfile = parseProfile(defaultBuildChatProfile);
        this.definitions = new EnumMap<>(AiProfile.class);
        add(AiProfile.AS_CHAT_FAST, fastModel, fastReasoningEffort, fastRagTopK, "as-chat-v3-fast-compact",
                fastMaxOutputTokens, fastRecentMessageLimit, fastIncludeEvidenceChunkText, fastIncludeToolResultPayload, fastUseCompactPrompt);
        add(AiProfile.AS_CHAT_54_FAST, gpt54FastModel, gpt54FastReasoningEffort, gpt54FastRagTopK, "as-chat-v5-gpt54-fast-compact",
                gpt54FastMaxOutputTokens, gpt54FastRecentMessageLimit, gpt54FastIncludeEvidenceChunkText, gpt54FastIncludeToolResultPayload, gpt54FastUseCompactPrompt);
        add(AiProfile.AS_CHAT_54_MINI_FAST, gpt54MiniFastModel, gpt54MiniFastReasoningEffort, gpt54MiniFastRagTopK, "as-chat-v5-gpt54-mini-fast-compact",
                gpt54MiniFastMaxOutputTokens, gpt54MiniFastRecentMessageLimit, gpt54MiniFastIncludeEvidenceChunkText, gpt54MiniFastIncludeToolResultPayload, gpt54MiniFastUseCompactPrompt);
        add(AiProfile.AS_CHAT_NANO_FAST, nanoFastModel, nanoFastReasoningEffort, nanoFastRagTopK, "as-chat-v4-nano-fast-compact",
                nanoFastMaxOutputTokens, nanoFastRecentMessageLimit, nanoFastIncludeEvidenceChunkText, nanoFastIncludeToolResultPayload, nanoFastUseCompactPrompt);
        add(AiProfile.AS_CHAT_BALANCED, balancedModel, balancedReasoningEffort, balancedRagTopK, "as-chat-v3-balanced-compact",
                balancedMaxOutputTokens, balancedRecentMessageLimit, balancedIncludeEvidenceChunkText, balancedIncludeToolResultPayload, balancedUseCompactPrompt);
        add(AiProfile.AS_CHAT_HIGH_QUALITY, highQualityModel, highQualityReasoningEffort, highQualityRagTopK, "as-chat-v3-high-quality-full",
                highQualityMaxOutputTokens, highQualityRecentMessageLimit, highQualityIncludeEvidenceChunkText, highQualityIncludeToolResultPayload, highQualityUseCompactPrompt);
        add(AiProfile.BUILD_CHAT_FAST, buildChatFastModel, buildChatFastReasoningEffort, buildChatFastRagTopK, "build-chat-v1-fast",
                buildChatFastMaxOutputTokens, buildChatFastRecentMessageLimit, false, false, true);
        add(AiProfile.BUILD_CHAT_54_FAST, buildChat54FastModel, buildChat54FastReasoningEffort, buildChat54FastRagTopK, "build-chat-v1-gpt54-fast",
                buildChat54FastMaxOutputTokens, buildChat54FastRecentMessageLimit, false, false, true);
        add(AiProfile.BUILD_CHAT_54_MINI_FAST, buildChat54MiniFastModel, buildChat54MiniFastReasoningEffort, buildChat54MiniFastRagTopK, "build-chat-v1-gpt54-mini-fast",
                buildChat54MiniFastMaxOutputTokens, buildChat54MiniFastRecentMessageLimit, false, false, true);
        requireFamily(this.defaultAsChatProfile, "AS_CHAT", "AS Chat");
        requireFamily(this.defaultBuildChatProfile, "BUILD_CHAT", "Build Chat");
    }

    public AiProfileDefinition defaultAsChatProfile() {
        return definitions.get(defaultAsChatProfile);
    }

    public AiProfileDefinition defaultBuildChatProfile() {
        return definitions.get(defaultBuildChatProfile);
    }

    public AiProfileDefinition asChatProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return defaultAsChatProfile();
        }
        AiProfile profile = parseProfile(profileName);
        requireFamily(profile, "AS_CHAT", "AS Chat");
        return asChatProfile(profile);
    }

    public AiProfileDefinition asChatProfile(AiProfile profile) {
        requireFamily(profile, "AS_CHAT", "AS Chat");
        return definitions.get(profile);
    }

    public AiProfileDefinition buildChatProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return defaultBuildChatProfile();
        }
        AiProfile profile = parseProfile(profileName);
        requireFamily(profile, "BUILD_CHAT", "Build Chat");
        return definitions.get(profile);
    }

    private void add(
            AiProfile profile,
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
        definitions.put(profile, definition(
                profile,
                LlmProvider.OPENAI,
                model,
                reasoningEffort,
                ragTopK,
                promptVersion,
                maxOutputTokens,
                recentMessageLimit,
                includeEvidenceChunkText,
                includeToolResultPayload,
                useCompactPrompt
        ));
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
            throw new IllegalArgumentException("지원하지 않는 AI profile입니다: " + value, error);
        }
    }

    private static void requireFamily(AiProfile profile, String prefix, String label) {
        if (profile == null || !profile.name().startsWith(prefix + "_")) {
            throw new IllegalArgumentException(label + " profile이 아닙니다: " + profile);
        }
    }
}
