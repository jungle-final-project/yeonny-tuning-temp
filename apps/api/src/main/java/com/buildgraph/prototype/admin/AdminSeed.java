package com.buildgraph.prototype.admin;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.tool.ToolSeed;
import java.util.List;
import java.util.Map;

public final class AdminSeed {
    private AdminSeed() {
    }

    public static Map<String, Object> dashboard() {
        return MockData.map(
                "agentRunning", 1,
                "openTickets", 3,
                "priceJobsRunning", 0,
                "todayRevenue", 27800L,
                "weekRevenue", 230100L,
                "previousWeekRevenue", 208000L,
                "revenueTrend", List.of(
                        MockData.map("date", "2026-07-10", "label", "07/10", "revenue", 18000L),
                        MockData.map("date", "2026-07-11", "label", "07/11", "revenue", 0L),
                        MockData.map("date", "2026-07-12", "label", "07/12", "revenue", 72000L),
                        MockData.map("date", "2026-07-13", "label", "07/13", "revenue", 26000L),
                        MockData.map("date", "2026-07-14", "label", "07/14", "revenue", 230100L),
                        MockData.map("date", "2026-07-15", "label", "07/15", "revenue", 0L),
                        MockData.map("date", "2026-07-16", "label", "07/16", "revenue", 27800L)
                ),
                "orderStatus", List.of(
                        MockData.map("status", "PENDING", "label", "처리대기", "count", 1L),
                        MockData.map("status", "IN_PROGRESS", "label", "진행중", "count", 2L),
                        MockData.map("status", "COMPLETED", "label", "완료", "count", 8L),
                        MockData.map("status", "CANCELLED", "label", "취소", "count", 0L)
                ),
                "asStatus", List.of(
                        MockData.map("status", "PENDING", "label", "접수 대기", "count", 1L),
                        MockData.map("status", "IN_PROGRESS", "label", "처리 중", "count", 2L),
                        MockData.map("status", "COMPLETED", "label", "해결 완료", "count", 5L),
                        MockData.map("status", "CANCELLED", "label", "취소", "count", 0L)
                ),
                "degraded", false,
                "generatedAt", MockData.now()
        );
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
