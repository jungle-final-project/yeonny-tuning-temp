package com.buildgraph.prototype.opsagent.trace;

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

import com.buildgraph.prototype.common.MockData;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class AgentRunTraceDrafts {
    private AgentRunTraceDrafts() {
    }

    public static AgentRagEvidenceDraft ragEvidence(AgentSessionRoot root, AgentRunProfile profile) {
        return switch (profile.purpose()) {
            case REQUIREMENT_PARSE -> evidence(
                    "guide-requirement-parse-seed",
                    "Requirement parsing should extract budget, workload, resolution, vendor preference, noise sensitivity, upgrade intent, and unanswered questions before any build recommendation.",
                    "Requirement parse guide used by Agent runner.",
                    BigDecimal.valueOf(0.90),
                    root,
                    profile
            );
            case BUILD_RECOMMEND -> evidence(
                    "internal-rule-qhd-gaming-seed",
                    "QHD gaming recommendations prioritize GPU class, CPU balance, power margin, and current price.",
                    "QHD gaming build recommendation rule used by Agent runner.",
                    BigDecimal.valueOf(0.92),
                    root,
                    profile
            );
            case BUILD_EXPLAIN -> evidence(
                    "benchmark-build-explain-seed",
                    "Build explanations compare changed parts by expected bottleneck, price delta, and workload fit.",
                    "Benchmark and price reasoning used for build explanation.",
                    BigDecimal.valueOf(0.88),
                    root,
                    profile
            );
            case AS_ANALYZE -> evidence(
                    "support-guide-gpu-thermal-seed",
                    "Sustained GPU temperature spikes with frame time drops can indicate throttling or driver instability.",
                    "Troubleshooting evidence used for AS analysis.",
                    BigDecimal.valueOf(0.86),
                    root,
                    profile
            );
        };
    }

    public static List<AgentToolInvocationDraft> toolInvocations(AgentSessionRoot root, AgentRunProfile profile) {
        return profile.toolNames().stream()
                .map(toolName -> toolInvocation(toolName, root, profile))
                .toList();
    }

    /** Converts real ToolCheckService results into Agent trace drafts. */
    public static List<AgentToolInvocationDraft> toolInvocationsFromResults(
            AgentSessionRoot root,
            AgentRunProfile profile,
            List<Map<String, Object>> results
    ) {
        return results.stream()
                .map(result -> toolInvocationFromResult(root, profile, result))
                .toList();
    }

    public static String deterministicSummary(AgentRunProfile profile) {
        return switch (profile.purpose()) {
            case REQUIREMENT_PARSE -> "Agent completed a requirement parsing trace with RAG evidence.";
            case BUILD_RECOMMEND -> "Agent completed a build recommendation trace with RAG evidence and Tool checks.";
            case BUILD_EXPLAIN -> "Agent completed a build explanation trace with benchmark and price evidence.";
            case AS_ANALYZE -> "Agent completed an AS analysis trace with troubleshooting evidence and Tool checks.";
        };
    }

    public static String deterministicSummary(AgentRunProfile profile, AgentRagEvidenceDraft evidence) {
        String evidenceSummary = evidence == null ? "no RAG evidence" : evidence.summary();
        return switch (profile.purpose()) {
            case REQUIREMENT_PARSE -> "Agent completed a requirement parsing trace using retrieved RAG evidence: " + evidenceSummary;
            case BUILD_RECOMMEND -> "Agent completed a build recommendation trace using retrieved RAG evidence: " + evidenceSummary;
            case BUILD_EXPLAIN -> "Agent completed a build explanation trace using retrieved RAG evidence: " + evidenceSummary;
            case AS_ANALYZE -> "Agent completed an AS analysis trace using retrieved RAG evidence: " + evidenceSummary;
        };
    }

    private static AgentRagEvidenceDraft evidence(
            String sourceId,
            String chunkText,
            String summary,
            BigDecimal score,
            AgentSessionRoot root,
            AgentRunProfile profile
    ) {
        return new AgentRagEvidenceDraft(
                sourceId,
                chunkText,
                summary,
                score,
                MockData.map(
                        "sourceTypes", profile.ragSourceTypes(),
                        "purpose", profile.purpose().name(),
                        "rootType", root.type().name(),
                        "rootId", root.publicId(),
                        "retrievedAt", MockData.now()
                )
        );
    }

    private static AgentToolInvocationDraft toolInvocation(String toolName, AgentSessionRoot root, AgentRunProfile profile) {
        ToolStatus status = toolStatus(toolName, profile.purpose());
        ConfidenceLevel confidence = toolConfidence(toolName, profile.purpose());
        return new AgentToolInvocationDraft(
                toolName,
                status,
                confidence,
                toolSummary(toolName, status, profile.purpose()),
                MockData.map(
                        "toolName", toolName,
                        "rootType", root.type().name(),
                        "rootId", root.publicId(),
                        "purpose", profile.purpose().name(),
                        "context", MockData.map("summaryTarget", profile.summaryTarget())
                ),
                MockData.map(
                        "status", status.name(),
                        "confidence", confidence.name(),
                        "summary", toolSummary(toolName, status, profile.purpose()),
                        "details", MockData.map(
                                "deterministic", true,
                                "checkedAt", MockData.now(),
                                "evidenceSourceTypes", profile.ragSourceTypes()
                        )
                ),
                latencyMs(toolName)
        );
    }

    /** Builds one Agent Tool draft from a real Tool result map. */
    private static AgentToolInvocationDraft toolInvocationFromResult(
            AgentSessionRoot root,
            AgentRunProfile profile,
            Map<String, Object> result
    ) {
        String toolName = stringValue(result.get("tool"), "unknown");
        ToolStatus status = enumValue(ToolStatus.class, result.get("status"), ToolStatus.WARN);
        ConfidenceLevel confidence = enumValue(ConfidenceLevel.class, result.get("confidence"), ConfidenceLevel.MEDIUM);
        String summary = stringValue(result.get("summary"), "Tool check completed.");
        return new AgentToolInvocationDraft(
                toolName,
                status,
                confidence,
                summary,
                MockData.map(
                        "toolName", toolName,
                        "rootType", root.type().name(),
                        "rootId", root.publicId(),
                        "purpose", profile.purpose().name(),
                        "context", MockData.map("summaryTarget", profile.summaryTarget())
                ),
                result,
                latencyMs(toolName)
        );
    }

    private static ToolStatus toolStatus(String toolName, AgentPurpose purpose) {
        if (purpose == AgentPurpose.AS_ANALYZE && "performance".equals(toolName)) {
            return ToolStatus.WARN;
        }
        if (purpose == AgentPurpose.BUILD_RECOMMEND && "price".equals(toolName)) {
            return ToolStatus.WARN;
        }
        return ToolStatus.PASS;
    }

    private static ConfidenceLevel toolConfidence(String toolName, AgentPurpose purpose) {
        if (purpose == AgentPurpose.AS_ANALYZE || "price".equals(toolName)) {
            return ConfidenceLevel.MEDIUM;
        }
        return ConfidenceLevel.HIGH;
    }

    private static String toolSummary(String toolName, ToolStatus status, AgentPurpose purpose) {
        return switch (purpose) {
            case REQUIREMENT_PARSE -> "Seed " + toolName + " check for requirement parsing returned " + status + ".";
            case BUILD_RECOMMEND -> "Seed " + toolName + " check for build recommendation returned " + status + ".";
            case BUILD_EXPLAIN -> "Seed " + toolName + " check for build explanation returned " + status + ".";
            case AS_ANALYZE -> "Seed " + toolName + " check for AS analysis returned " + status + ".";
        };
    }

    private static Integer latencyMs(String toolName) {
        List<String> order = List.of("compatibility", "power", "size", "performance", "price");
        int index = order.indexOf(toolName);
        return index < 0 ? 60 : 40 + (index * 11);
    }

    /** Reads a string fallback from arbitrary Tool result values. */
    private static String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    /** Reads enum values defensively from arbitrary Tool result values. */
    private static <T extends Enum<T>> T enumValue(Class<T> enumType, Object value, T fallback) {
        try {
            return Enum.valueOf(enumType, stringValue(value, fallback.name()));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
