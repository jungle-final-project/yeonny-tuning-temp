package com.buildgraph.prototype.build;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.verification.part.PartSeed;
import com.buildgraph.prototype.verification.tool.ToolSeed;

import java.util.List;
import java.util.Map;

public final class BuildSeed {
    private static final String BUILD_ID = "00000000-0000-4000-8000-000000002001";
    private static final String ALT_BUILD_ID = "00000000-0000-4000-8000-000000002002";
    private static final String EVIDENCE_ID = "00000000-0000-4000-8000-000000004001";
    private static final String GPU_ID = PartSeed.GPU_ID;
    private static final String PREVIOUS_GPU_ID = "00000000-0000-4000-8000-000000010104";

    private BuildSeed() {
    }

    public static Map<String, Object> parsedRequirement(Map<String, Object> request) {
        return MockData.map(
                "id", "req-1001",
                "rawMessage", request.getOrDefault("message", "QHD 게임용 PC"),
                "budget", request.getOrDefault("budget", 2000000),
                "usageTags", List.of("GAMING", "DEVELOPMENT"),
                "parsedContext", MockData.map(
                        "usageTags", List.of("GAMING", "DEVELOPMENT"),
                        "budget", request.getOrDefault("budget", 2000000),
                        "preferredVendors", List.of("NVIDIA")
                )
        );
    }

    public static Map<String, Object> recommendations() {
        return MockData.map(
                "agentSessionId", "00000000-0000-4000-8000-000000003001",
                "recommendations", builds(),
                "warnings", List.of(),
                "evidenceIds", List.of(EVIDENCE_ID)
        );
    }

    public static List<Map<String, Object>> builds() {
        return List.of(
                buildSummary(BUILD_ID, "QHD 게임 균형형", 1980000, "MEDIUM"),
                buildSummary(ALT_BUILD_ID, "개발 + 게임 혼합형", 2120000, "HIGH")
        );
    }

    public static Map<String, Object> buildDetail(String id) {
        return MockData.map(
                "id", id,
                "name", "QHD 게임 균형형",
                "totalPrice", 1980000,
                "confidence", "MEDIUM",
                "items", PartSeed.parts(),
                "warnings", List.of(warning("POWER_HEADROOM", "PSU 여유율 확인 필요")),
                "evidenceIds", List.of(EVIDENCE_ID),
                "changeableCategories", List.of("GPU", "RAM"),
                "createdAt", MockData.now(),
                "toolResults", List.of(ToolSeed.toolResult("compatibility"), ToolSeed.toolResult("power"))
        );
    }

    public static Map<String, Object> changePart(String id) {
        return MockData.map(
                "buildId", id,
                "category", "GPU",
                "previousPartId", PREVIOUS_GPU_ID,
                "selectedPartId", GPU_ID,
                "totalPrice", 1980000,
                "diff", MockData.map("price", 318000, "qhdPerformance", "+42%"),
                "warnings", List.of(warning("POWER_HEADROOM", "PSU 여유율 확인 필요"))
        );
    }

    private static Map<String, Object> buildSummary(String id, String name, int totalPrice, String confidence) {
        return MockData.map(
                "id", id,
                "name", name,
                "totalPrice", totalPrice,
                "confidence", confidence,
                "items", PartSeed.parts(),
                "warnings", List.of(warning("POWER_HEADROOM", "PSU 여유율 확인 필요")),
                "evidenceIds", List.of(EVIDENCE_ID),
                "changeableCategories", List.of("GPU", "RAM"),
                "createdAt", MockData.now()
        );
    }

    private static Map<String, Object> warning(String code, String message) {
        return MockData.map("code", code, "message", message, "severity", "WARN", "relatedPartIds", List.of(GPU_ID));
    }
}
