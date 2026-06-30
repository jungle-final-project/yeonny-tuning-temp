package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.rag.RagQueryService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final RagQueryService ragQueryService;
    private final AgentTraceService agentTraceService;
    private final AgentRunner agentRunner;
    private final LlmGenerationService llmGenerationService;

    public AgentQueryService(
            JdbcTemplate jdbcTemplate,
            RagQueryService ragQueryService,
            AgentTraceService agentTraceService,
            AgentRunner agentRunner,
            LlmGenerationService llmGenerationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.ragQueryService = ragQueryService;
        this.agentTraceService = agentTraceService;
        this.agentRunner = agentRunner;
        this.llmGenerationService = llmGenerationService;
    }

    public Map<String, Object> createSession(AgentSessionCreateRequest request) {
        AgentSessionRoot root = parseRoot(request);
        String id = agentTraceService.createQueuedSession(root, "USER");
        return session(id);
    }

    public Map<String, Object> runSession(String id) {
        Map<String, Object> row = agentSessionRow(id);
        AgentSessionRoot root = rootFromRow(row);
        AgentRunProfile profile = AgentRunProfiles.forRoot(root);
        agentTraceService.advanceStatus(id, AgentStatus.RUNNING, "SYSTEM", "agent run requested for " + profile.purpose());
        Map<String, Object> startedSession = session(id);
        agentRunner.run(id, root, profile);
        return startedSession;
    }

    public Map<String, Object> session(String id) {
        Map<String, Object> row = agentSessionRow(id);
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "status", DbValueMapper.string(row, "status"),
                "stateTimeline", DbValueMapper.json(row, "state_timeline", List.of()),
                "summary", DbValueMapper.string(row, "summary"),
                "toolInvocationIds", toolInvocationIdsBySession(id),
                "evidenceIds", evidenceIdsBySession(id)
        );
    }

    public Map<String, Object> adminSession(String id) {
        Map<String, Object> row = agentSessionRow(id);
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "status", DbValueMapper.string(row, "status"),
                "summary", DbValueMapper.string(row, "summary"),
                "stateTimeline", DbValueMapper.json(row, "state_timeline", List.of()),
                "purpose", rootFromRow(row).purpose().name(),
                "toolInvocations", toolInvocationsBySession(id),
                "evidenceIds", evidenceIdsBySession(id),
                "llmGenerations", llmGenerationService.generationsBySession(id)
        );
    }

    public Map<String, Object> agentSessions() {
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                        SELECT s.public_id::text AS id,
                               s.status,
                               u.public_id::text AS user_id,
                               s.created_at
                        FROM agent_sessions s
                        JOIN users u ON u.id = s.user_id
                        ORDER BY s.created_at DESC, s.id DESC
                        """)
                .stream()
                .map(row -> MockData.map(
                        "id", DbValueMapper.string(row, "id"),
                        "status", DbValueMapper.string(row, "status"),
                        "userId", DbValueMapper.string(row, "user_id"),
                        "createdAt", DbValueMapper.timestamp(row, "created_at")
                ))
                .toList();
        return MockData.map("items", items, "page", 0, "size", 20, "total", items.size());
    }

    public Map<String, Object> toolInvocations() {
        List<Map<String, Object>> items = jdbcTemplate.queryForList(toolInvocationSql() + " ORDER BY ti.created_at DESC, ti.id DESC")
                .stream()
                .map(this::toolInvocationMap)
                .toList();
        return MockData.map("items", items, "page", 0, "size", 20, "total", items.size());
    }

    public Map<String, Object> toolInvocation(String id) {
        return jdbcTemplate.queryForList(toolInvocationSql() + " WHERE ti.public_id = ?::uuid", id)
                .stream()
                .findFirst()
                .map(this::toolInvocationMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tool invocation을 찾을 수 없습니다."));
    }

    private Map<String, Object> agentSessionRow(String id) {
        return jdbcTemplate.queryForList("""
                        SELECT s.public_id::text AS id,
                               s.status,
                               s.summary,
                               s.state_timeline,
                               s.created_at,
                               s.updated_at,
                               r.public_id::text AS requirement_id,
                               b.public_id::text AS build_id,
                               t.public_id::text AS as_ticket_id
                        FROM agent_sessions s
                        LEFT JOIN requirements r ON r.id = s.requirement_id
                        LEFT JOIN builds b ON b.id = s.build_id
                        LEFT JOIN as_tickets t ON t.id = s.as_ticket_id
                        WHERE s.public_id = ?::uuid
                        """, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent session을 찾을 수 없습니다."));
    }

    private List<Map<String, Object>> toolInvocationsBySession(String sessionId) {
        return jdbcTemplate.queryForList(toolInvocationSql() + " WHERE s.public_id = ?::uuid ORDER BY ti.id", sessionId)
                .stream()
                .map(this::toolInvocationMap)
                .toList();
    }

    private List<Object> toolInvocationIdsBySession(String sessionId) {
        return toolInvocationsBySession(sessionId).stream().map(invocation -> invocation.get("id")).toList();
    }

    private List<Object> evidenceIdsBySession(String sessionId) {
        return ragQueryService.evidenceBySession(sessionId).stream().map(evidence -> evidence.get("id")).toList();
    }

    private String toolInvocationSql() {
        return """
                SELECT ti.public_id::text AS id,
                       s.public_id::text AS agent_session_id,
                       ti.tool_name,
                       ti.status,
                       ti.confidence,
                       ti.summary,
                       ti.request_payload,
                       ti.result_payload,
                       ti.latency_ms,
                       ti.created_at
                FROM tool_invocations ti
                JOIN agent_sessions s ON s.id = ti.agent_session_id
                """;
    }

    private Map<String, Object> toolInvocationMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "agentSessionId", DbValueMapper.string(row, "agent_session_id"),
                "toolName", DbValueMapper.string(row, "tool_name"),
                "status", DbValueMapper.string(row, "status"),
                "confidence", DbValueMapper.string(row, "confidence"),
                "summary", DbValueMapper.string(row, "summary"),
                "latencyMs", DbValueMapper.integer(row, "latency_ms"),
                "requestPayload", DbValueMapper.json(row, "request_payload", Map.of()),
                "resultPayload", DbValueMapper.json(row, "result_payload", Map.of()),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    private static AgentSessionRoot rootFromRow(Map<String, Object> row) {
        String requirementId = DbValueMapper.string(row, "requirement_id");
        if (requirementId != null) {
            return new AgentSessionRoot(AgentSessionRootType.REQUIREMENT, requirementId);
        }
        String buildId = DbValueMapper.string(row, "build_id");
        if (buildId != null) {
            return new AgentSessionRoot(AgentSessionRootType.BUILD, buildId);
        }
        String asTicketId = DbValueMapper.string(row, "as_ticket_id");
        if (asTicketId != null) {
            return new AgentSessionRoot(AgentSessionRootType.AS_TICKET, asTicketId);
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Agent session root를 확인할 수 없습니다.");
    }

    private static AgentSessionRoot parseRoot(AgentSessionCreateRequest request) {
        try {
            return AgentSessionRoot.from(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

}
