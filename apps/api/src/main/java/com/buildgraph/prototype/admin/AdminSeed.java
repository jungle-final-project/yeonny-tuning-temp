package com.buildgraph.prototype.admin;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.parts.tool.ToolSeed;

import java.util.List;
import java.util.Map;

public final class AdminSeed {
    private AdminSeed() {
    }

    public static Map<String, Object> dashboard() {
        return MockData.map("agentRunning", 1, "openTickets", 3, "priceJobsRunning", 0, "degraded", false, "generatedAt", MockData.now());
    }

    public static Map<String, Object> auditLogs() {
        return MockData.map(
                "items", List.of(MockData.map(
                        "action", "AS_TICKET_UPDATED",
                        "targetType", "as_tickets",
                        "targetId", "00000000-0000-4000-8000-000000006001",
                        "metadata", MockData.map("beforeStatus", "OPEN", "afterStatus", "IN_PROGRESS"),
                        "createdAt", MockData.now()
                ))
        );
    }

    public static Map<String, Object> agentSessions() {
        return MockData.map(
                "items", List.of(MockData.map(
                        "id", "00000000-0000-4000-8000-000000003001",
                        "status", "RUNNING",
                        "userId", "00000000-0000-4000-8000-000000001004",
                        "createdAt", MockData.now()
                )),
                "page", 0,
                "size", 20,
                "total", 1
        );
    }

    public static Map<String, Object> agentSession(String id) {
        return MockData.map(
                "id", id,
                "status", "SUCCEEDED",
                "summary", "추천이 완료되었습니다.",
                "stateTimeline", List.of(),
                "toolInvocations", List.of(ToolSeed.toolResult("compatibility"), ToolSeed.toolResult("power")),
                "evidenceIds", List.of("00000000-0000-4000-8000-000000004001")
        );
    }

    public static Map<String, Object> toolInvocations() {
        return MockData.map(
                "items", List.of(toolInvocation("00000000-0000-4000-8000-000000005001"), toolInvocation("00000000-0000-4000-8000-000000005002")),
                "page", 0,
                "size", 20,
                "total", 2
        );
    }

    public static Map<String, Object> toolInvocation(String id) {
        return MockData.map(
                "id", id,
                "agentSessionId", "00000000-0000-4000-8000-000000003001",
                "toolName", "power",
                "status", "WARN",
                "confidence", "MEDIUM",
                "summary", "권장 파워 여유율 확인 필요",
                "latencyMs", 168,
                "requestPayload", MockData.map("toolName", "power", "partIds", List.of("00000000-0000-4000-8000-000000010004")),
                "resultPayload", ToolSeed.toolResult("power"),
                "createdAt", MockData.now()
        );
    }

    public static Map<String, Object> ragEvidence(String id) {
        return MockData.map(
                "id", id,
                "agentSessionId", "00000000-0000-4000-8000-000000003001",
                "sourceId", "psu-rule-001",
                "chunkText", "GPU 피크 전력과 CPU TDP 합산 후 여유율을 적용한다.",
                "summary", "GPU 피크 전력과 CPU TDP 합산 후 여유율 적용",
                "score", 0.91,
                "metadata", MockData.map("sourceType", "INTERNAL_RULE", "title", "PSU sizing guide")
        );
    }
}
