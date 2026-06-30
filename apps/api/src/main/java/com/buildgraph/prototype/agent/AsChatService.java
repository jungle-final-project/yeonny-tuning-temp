package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.ToolCheckService;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AsChatService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String AS_CHAT_SCHEMA_NAME = "buildgraph_as_chat_response";
    private static final String SYSTEM_PROMPT = """
            당신은 BuildGraph AS AI 상담 챗봇입니다.
            응답은 서버가 제공한 Structured Output JSON schema를 반드시 따릅니다.
            제공된 AS 티켓 증상, 최근 대화, RAG 근거, Tool 결과만 근거로 답하십시오.
            확인되지 않은 부품명, 가격, FPS, 수리 비용, 성능 수치를 지어내지 마십시오.
            근거가 부족하면 confidence를 LOW로 낮추고 필요한 로그를 ticketDraft.recommendedLogRequest에 적으십시오.
            사용자가 직접 시도할 수 있는 안전한 확인 절차와 원격지원/기사 연결 필요 여부를 구분하십시오.
            서버가 제공한 responseLimits의 길이와 개수 제한을 지키십시오.
            사용자 증상에 드라이버, 이벤트 로그, RAM, SSD, 파워, 전원, 온도 같은 핵심 단서가 있으면 답변 또는 조치에 그 단서를 명확히 언급하십시오.
            """;
    private static final AsChatProgressSink NOOP_PROGRESS = (eventName, payload) -> {
    };

    private final JdbcTemplate jdbcTemplate;
    private final AgentTraceService agentTraceService;
    private final AgentRagRetrievalService agentRagRetrievalService;
    private final StructuredLlmClientRouter structuredLlmClientRouter;
    private final ToolCheckService toolCheckService;
    private final AiProfileConfig aiProfileConfig;
    private final AsChatProfilePolicy asChatProfilePolicy;
    private final LlmGenerationService llmGenerationService;

    public AsChatService(
            JdbcTemplate jdbcTemplate,
            AgentTraceService agentTraceService,
            AgentRagRetrievalService agentRagRetrievalService,
            StructuredLlmClientRouter structuredLlmClientRouter,
            ToolCheckService toolCheckService,
            AiProfileConfig aiProfileConfig,
            AsChatProfilePolicy asChatProfilePolicy,
            LlmGenerationService llmGenerationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.agentTraceService = agentTraceService;
        this.agentRagRetrievalService = agentRagRetrievalService;
        this.structuredLlmClientRouter = structuredLlmClientRouter;
        this.toolCheckService = toolCheckService;
        this.aiProfileConfig = aiProfileConfig;
        this.asChatProfilePolicy = asChatProfilePolicy;
        this.llmGenerationService = llmGenerationService;
    }

    public Map<String, Object> history(String asTicketId, CurrentUserService.CurrentUser user) {
        String ticketId = requireText(asTicketId, "AS 티켓 ID가 필요합니다.");
        TicketRow ticket = ticket(ticketId, user);
        ChatSessionRow session = findActiveSession(ticket.internalId(), user.internalId());
        List<Map<String, Object>> messages = session == null ? List.of() : messages(session.internalId());
        return MockData.map(
                "sessionId", session == null ? null : session.publicId(),
                "asTicketId", ticket.publicId(),
                "ticket", ticketMap(ticket),
                "model", aiProfileConfig.defaultAsChatProfile().model(),
                "messages", messages,
                "evidence", List.of(),
                "toolResults", List.of()
        );
    }

    public Map<String, Object> send(String asTicketId, String message, CurrentUserService.CurrentUser user, String requestedAiProfile) {
        return send(asTicketId, message, user, requestedAiProfile, NOOP_PROGRESS);
    }

    public Map<String, Object> send(
            String asTicketId,
            String message,
            CurrentUserService.CurrentUser user,
            String requestedAiProfile,
            AsChatProgressSink progressSink
    ) {
        AsChatProgressSink progress = progressSink == null ? NOOP_PROGRESS : progressSink;
        progress.emit("STARTED", progressPayload("STARTED", "AS 티켓과 사용자 세션을 확인하고 있습니다.", Map.of("asTicketId", safe(asTicketId))));

        String ticketId = requireText(asTicketId, "AS 티켓 ID가 필요합니다.");
        String userMessage = requireText(message, "챗봇에 보낼 메시지가 필요합니다.");
        TicketRow ticket = ticket(ticketId, user);
        AiProfileDefinition aiProfile = requireAiProfile(requestedAiProfile, ticket, userMessage);
        if (!structuredLlmClientRouter.isConfigured(aiProfile.provider())) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, structuredLlmClientRouter.missingKeyMessage(aiProfile.provider()));
        }
        ChatSessionRow chatSession = findOrCreateActiveSession(ticket, user);
        saveMessage(chatSession.internalId(), "USER", userMessage, Map.of(), null);

        AgentSessionRoot root = new AgentSessionRoot(AgentSessionRootType.AS_TICKET, ticket.publicId());
        AgentRunProfile profile = AgentRunProfiles.forRoot(root);
        String agentSessionId = agentTraceService.createQueuedSession(root, "USER", profile.purpose(), user.internalId());
        Long agentInternalId = agentInternalId(agentSessionId);
        try {
            agentTraceService.advanceStatus(agentSessionId, AgentStatus.RUNNING, "SYSTEM", "AS AI chat requested");

            List<Map<String, Object>> recentMessages = recentConversation(chatSession.internalId(), aiProfile.recentMessageLimit());
            String retrievalQuery = retrievalQuery(ticket, recentMessages, userMessage);
            List<AgentRagEvidenceDraft> evidenceDrafts = agentRagRetrievalService.retrieveEvidenceSet(root, profile, retrievalQuery, aiProfile.ragTopK());
            List<String> evidenceIds = evidenceDrafts.stream()
                    .map(draft -> agentTraceService.recordRagEvidence(agentSessionId, draft))
                    .toList();
            agentTraceService.advanceStatus(agentSessionId, AgentStatus.RAG_SEARCHED, "SYSTEM", "AS chat RAG evidence retrieved");
            progress.emit("RAG_READY", progressPayload("RAG_READY", "관련 AS 근거를 찾았습니다.", MockData.map("evidenceCount", evidenceIds.size())));

            List<AgentToolInvocationDraft> toolDrafts = toolInvocations(root, profile);
            List<String> toolInvocationIds = toolDrafts.stream()
                    .map(draft -> agentTraceService.recordToolInvocation(agentSessionId, draft))
                    .toList();
            agentTraceService.advanceStatus(agentSessionId, AgentStatus.TOOLS_CALLED, "SYSTEM", "AS chat Tool checks completed");
            progress.emit("TOOLS_READY", progressPayload("TOOLS_READY", "Tool 검증 결과를 정리했습니다.", MockData.map("toolCount", toolInvocationIds.size())));

            List<Map<String, Object>> evidence = evidenceItems(evidenceIds, evidenceDrafts);
            List<Map<String, Object>> toolResults = toolItems(toolInvocationIds, toolDrafts);
            progress.emit("LLM_RUNNING", progressPayload("LLM_RUNNING", "AI 답변을 생성하고 있습니다.", Map.of()));
            Map<String, Object> llmJson = generateAsChatJson(
                    agentInternalId,
                    aiProfile,
                    ticket,
                    chatSession,
                    recentMessages,
                    userMessage,
                    evidence,
                    toolResults,
                    evidenceIds,
                    toolInvocationIds
            );
            String assistantMessage = requireAssistantMessage(llmJson);
            agentTraceService.updateSummary(agentSessionId, assistantMessage);
            agentTraceService.advanceStatus(agentSessionId, AgentStatus.SUMMARY_READY, "SYSTEM", "AS chat LLM JSON generated by " + aiProfile.profile().name() + " / " + aiProfile.model());
            agentTraceService.advanceStatus(agentSessionId, AgentStatus.SUCCEEDED, "SYSTEM", "AS chat completed");

            saveMessage(chatSession.internalId(), "ASSISTANT", assistantMessage, llmJson, agentInternalId);
            return response(ticket, chatSession, agentSessionId, assistantMessage, llmJson, evidence, toolResults, aiProfile);
        } catch (ResponseStatusException error) {
            failAgentSession(agentSessionId, error);
            throw error;
        } catch (RuntimeException error) {
            failAgentSession(agentSessionId, error);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM 응답 JSON을 처리할 수 없습니다.", error);
        }
    }

    private TicketRow ticket(String asTicketId, CurrentUserService.CurrentUser user) {
        return jdbcTemplate.queryForList("""
                        SELECT t.id AS internal_id,
                               t.public_id::text AS id,
                               t.symptom,
                               t.status,
                               coalesce(t.cause_candidates::text, '[]') AS cause_candidates,
                               coalesce(t.upgrade_candidates::text, '[]') AS upgrade_candidates,
                               coalesce(l.summary, '') AS log_summary,
                               t.created_at
                        FROM as_tickets t
                        LEFT JOIN agent_log_uploads l ON l.id = t.log_upload_id
                        WHERE t.public_id = ?::uuid
                          AND t.user_id = ?
                          AND t.deleted_at IS NULL
                        """, asTicketId, user.internalId())
                .stream()
                .findFirst()
                .map(this::ticketRow)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    private ChatSessionRow findActiveSession(Long ticketInternalId, Long userInternalId) {
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               title,
                               status
                        FROM as_chat_sessions
                        WHERE user_id = ?
                          AND as_ticket_id = ?
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        ORDER BY id DESC
                        LIMIT 1
                        """, userInternalId, ticketInternalId)
                .stream()
                .findFirst()
                .map(this::chatSessionRow)
                .orElse(null);
    }

    private ChatSessionRow findOrCreateActiveSession(TicketRow ticket, CurrentUserService.CurrentUser user) {
        ChatSessionRow existing = findActiveSession(ticket.internalId(), user.internalId());
        if (existing != null) {
            return existing;
        }
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO as_chat_sessions (user_id, as_ticket_id, title, updated_at)
                VALUES (?, ?, ?, now())
                RETURNING id AS internal_id, public_id::text AS id, title, status
                """, user.internalId(), ticket.internalId(), titleFromSymptom(ticket.symptom()));
        return chatSessionRow(row);
    }

    private Long agentInternalId(String agentSessionId) {
        return jdbcTemplate.queryForList("""
                        SELECT id
                        FROM agent_sessions
                        WHERE public_id = ?::uuid
                        """, agentSessionId)
                .stream()
                .findFirst()
                .map(row -> longValue(row, "id"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent session을 찾을 수 없습니다."));
    }

    private void saveMessage(Long chatSessionId, String role, String content, Map<String, Object> payload, Long agentInternalId) {
        jdbcTemplate.update("""
                INSERT INTO as_chat_messages (
                  chat_session_id,
                  role,
                  content,
                  structured_payload,
                  agent_session_id
                )
                VALUES (?, ?, ?, ?::jsonb, ?)
                """, chatSessionId, role, content, AgentTraceService.json(payload == null ? Map.of() : payload), agentInternalId);
        jdbcTemplate.update("""
                UPDATE as_chat_sessions
                SET updated_at = now()
                WHERE id = ?
                """, chatSessionId);
    }

    private List<Map<String, Object>> messages(Long chatSessionId) {
        return jdbcTemplate.queryForList("""
                        SELECT m.public_id::text AS id,
                               m.role,
                               m.content,
                               m.structured_payload,
                               s.public_id::text AS agent_session_id,
                               m.created_at
                        FROM as_chat_messages m
                        LEFT JOIN agent_sessions s ON s.id = m.agent_session_id
                        WHERE m.chat_session_id = ?
                        ORDER BY m.created_at, m.id
                        """, chatSessionId)
                .stream()
                .map(this::messageMap)
                .toList();
    }

    private List<Map<String, Object>> recentConversation(Long chatSessionId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT role, content, created_at
                FROM as_chat_messages
                WHERE chat_session_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, chatSessionId, limit);
        return rows.reversed().stream()
                .map(row -> MockData.map(
                        "role", DbValueMapper.string(row, "role"),
                        "content", DbValueMapper.string(row, "content"),
                        "createdAt", DbValueMapper.timestamp(row, "created_at")
                ))
                .toList();
    }

    private List<AgentToolInvocationDraft> toolInvocations(AgentSessionRoot root, AgentRunProfile profile) {
        try {
            return AgentRunTraceDrafts.toolInvocationsFromResults(
                    root,
                    profile,
                    toolCheckService.checkAgentTools(root.type().name(), root.publicId(), profile.toolNames())
            );
        } catch (RuntimeException ignored) {
            return AgentRunTraceDrafts.toolInvocations(root, profile);
        }
    }

    private Map<String, Object> response(
            TicketRow ticket,
            ChatSessionRow chatSession,
            String agentSessionId,
            String assistantMessage,
            Map<String, Object> llmJson,
            List<Map<String, Object>> evidence,
            List<Map<String, Object>> toolResults,
            AiProfileDefinition aiProfile
    ) {
        return MockData.map(
                "sessionId", chatSession.publicId(),
                "asTicketId", ticket.publicId(),
                "ticket", ticketMap(ticket),
                "model", aiProfile.model(),
                "agentSessionId", agentSessionId,
                "messages", messages(chatSession.internalId()),
                "assistantMessage", assistantMessage,
                "causeCandidates", listValue(llmJson.get("causeCandidates")),
                "nextActions", listValue(llmJson.get("nextActions")),
                "escalation", objectValue(llmJson.get("escalation")),
                "ticketDraft", objectValue(llmJson.get("ticketDraft")),
                "evidence", evidence,
                "toolResults", toolResults
        );
    }

    private Map<String, Object> generateAsChatJson(
            Long agentInternalId,
            AiProfileDefinition aiProfile,
            TicketRow ticket,
            ChatSessionRow chatSession,
            List<Map<String, Object>> recentMessages,
            String userMessage,
            List<Map<String, Object>> evidence,
            List<Map<String, Object>> toolResults,
            List<String> evidenceIds,
            List<String> toolInvocationIds
    ) {
        long startedAt = System.nanoTime();
        LlmResponseResult llmResult = null;
        try {
            llmResult = structuredLlmClientRouter.createStructuredJsonResult(
                    aiProfile,
                    SYSTEM_PROMPT,
                    llmPrompt(ticket, recentMessages, userMessage, evidence, toolResults, aiProfile),
                    AS_CHAT_SCHEMA_NAME,
                    asChatResponseSchema(evidenceIds, toolInvocationIds)
            );
            Map<String, Object> llmJson = strictJson(llmResult.text());
            llmGenerationService.recordSuccess(agentInternalId, aiProfile, AS_CHAT_SCHEMA_NAME, llmResult);
            return llmJson;
        } catch (ResponseStatusException error) {
            boolean schemaValid = error.getStatusCode().value() != HttpStatus.BAD_GATEWAY.value();
            llmGenerationService.recordFailure(
                    agentInternalId,
                    aiProfile,
                    AS_CHAT_SCHEMA_NAME,
                    schemaValid,
                    llmResult == null ? elapsedMs(startedAt) : llmResult.latencyMs(),
                    "HTTP_" + error.getStatusCode().value(),
                    error.getReason()
            );
            throw error;
        } catch (RuntimeException error) {
            llmGenerationService.recordFailure(
                    agentInternalId,
                    aiProfile,
                    AS_CHAT_SCHEMA_NAME,
                    false,
                    elapsedMs(startedAt),
                    "LLM_RUNTIME_ERROR",
                    safeReason(error)
            );
            throw error;
        }
    }

    private static String llmPrompt(
            TicketRow ticket,
            List<Map<String, Object>> recentMessages,
            String userMessage,
            List<Map<String, Object>> evidence,
            List<Map<String, Object>> toolResults,
            AiProfileDefinition aiProfile
    ) {
        return AgentTraceService.json(MockData.map(
                "task", "AS_ANALYZE_CHAT",
                "aiProfile", aiProfile.profile().name(),
                "promptVersion", aiProfile.promptVersion(),
                "responseLimits", responseLimits(aiProfile),
                "ticket", promptTicket(ticket),
                "recentMessages", recentMessages,
                "latestUserMessage", userMessage,
                "ragEvidence", promptEvidenceItems(evidence, aiProfile),
                "toolResults", promptToolItems(toolResults, aiProfile)
        ));
    }

    private static Map<String, Object> promptTicket(TicketRow ticket) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", ticket.publicId());
        payload.put("status", ticket.status());
        payload.put("symptom", ticket.symptom());
        if (!safe(ticket.logSummary()).isBlank()) {
            payload.put("logSummary", trimText(ticket.logSummary(), 800));
        }
        if (!isEmptyJsonValue(ticket.causeCandidates())) {
            payload.put("existingCauseCandidates", ticket.causeCandidates());
        }
        if (!isEmptyJsonValue(ticket.upgradeCandidates())) {
            payload.put("existingUpgradeCandidates", ticket.upgradeCandidates());
        }
        return payload;
    }

    private static Map<String, Object> responseLimits(AiProfileDefinition aiProfile) {
        return switch (aiProfile.profile()) {
            case AS_CHAT_FAST, AS_CHAT_NANO_FAST -> MockData.map(
                    "assistantMessage", "Korean, around 220 characters",
                    "causeCandidatesMax", 1,
                    "nextActionsMax", 2
            );
            case AS_CHAT_BALANCED -> MockData.map(
                    "assistantMessage", "Korean, around 350 characters",
                    "causeCandidatesMax", 2,
                    "nextActionsMax", 3
            );
            case AS_CHAT_HIGH_QUALITY -> MockData.map(
                    "assistantMessage", "Korean, around 500 characters",
                    "causeCandidatesMax", 3,
                    "nextActionsMax", 3
            );
        };
    }

    private static List<Map<String, Object>> promptEvidenceItems(List<Map<String, Object>> evidence, AiProfileDefinition aiProfile) {
        return evidence.stream()
                .map(item -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("id", item.get("id"));
                    payload.put("sourceId", item.get("sourceId"));
                    payload.put("summary", item.get("summary"));
                    payload.put("score", item.get("score"));
                    if (aiProfile.includeEvidenceChunkText()) {
                        Object chunkText = item.get("chunkText");
                        payload.put("chunkText", trimText(chunkText == null ? "" : String.valueOf(chunkText), 1200));
                    }
                    if (!aiProfile.useCompactPrompt() && item.get("metadata") != null) {
                        payload.put("metadata", item.get("metadata"));
                    }
                    return payload;
                })
                .toList();
    }

    private static List<Map<String, Object>> promptToolItems(List<Map<String, Object>> toolResults, AiProfileDefinition aiProfile) {
        return toolResults.stream()
                .map(item -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("id", item.get("id"));
                    payload.put("toolName", item.get("toolName"));
                    payload.put("status", item.get("status"));
                    payload.put("confidence", item.get("confidence"));
                    payload.put("summary", item.get("summary"));
                    if (aiProfile.includeToolResultPayload()) {
                        payload.put("resultPayload", item.getOrDefault("resultPayload", Map.of()));
                    } else {
                        payload.put("details", compactToolDetails(item.get("resultPayload")));
                    }
                    return payload;
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Object compactToolDetails(Object resultPayload) {
        if (!(resultPayload instanceof Map<?, ?> payload)) {
            return Map.of();
        }
        Object details = payload.get("details");
        if (details instanceof Map<?, ?> detailsMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            detailsMap.forEach((key, value) -> {
                if (value instanceof Number || value instanceof Boolean || value instanceof String) {
                    result.put(String.valueOf(key), value);
                }
            });
            return result;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        payload.forEach((key, value) -> {
            if (value instanceof Number || value instanceof Boolean || value instanceof String) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private static String retrievalQuery(TicketRow ticket, List<Map<String, Object>> recentMessages, String userMessage) {
        return String.join(" ",
                safe(ticket.symptom()),
                safe(ticket.logSummary()),
                safe(AgentTraceService.json(recentMessages)),
                safe(userMessage)
        );
    }

    private static List<Map<String, Object>> evidenceItems(List<String> ids, List<AgentRagEvidenceDraft> drafts) {
        return java.util.stream.IntStream.range(0, drafts.size())
                .mapToObj(index -> {
                    AgentRagEvidenceDraft draft = drafts.get(index);
                    return MockData.map(
                            "id", ids.get(index),
                            "sourceId", draft.sourceId(),
                            "summary", draft.summary(),
                            "chunkText", draft.chunkText(),
                            "score", draft.score(),
                            "metadata", draft.metadata()
                    );
                })
                .toList();
    }

    private static List<Map<String, Object>> toolItems(List<String> ids, List<AgentToolInvocationDraft> drafts) {
        return java.util.stream.IntStream.range(0, drafts.size())
                .mapToObj(index -> {
                    AgentToolInvocationDraft draft = drafts.get(index);
                    return MockData.map(
                            "id", ids.get(index),
                            "toolName", draft.toolName(),
                            "status", draft.status().name(),
                            "confidence", draft.confidence().name(),
                            "summary", draft.summary(),
                            "resultPayload", draft.resultPayload()
                    );
                })
                .toList();
    }

    private static Map<String, Object> schemaObject(List<String> required, Map<String, Object> properties) {
        return MockData.map(
                "type", "object",
                "additionalProperties", false,
                "required", required,
                "properties", properties
        );
    }

    private static Map<String, Object> asChatResponseSchema(List<String> evidenceIds, List<String> toolInvocationIds) {
        return schemaObject(
                List.of("assistantMessage", "causeCandidates", "nextActions", "escalation", "ticketDraft"),
                MockData.map(
                        "assistantMessage", stringSchema(),
                        "causeCandidates", arraySchema(schemaObject(
                                List.of("label", "confidence", "reason", "evidenceIds", "toolInvocationIds"),
                                MockData.map(
                                        "label", stringSchema(),
                                        "confidence", enumSchema("LOW", "MEDIUM", "HIGH"),
                                        "reason", stringSchema(),
                                        "evidenceIds", idArraySchema(evidenceIds),
                                        "toolInvocationIds", idArraySchema(toolInvocationIds)
                                )
                        )),
                        "nextActions", arraySchema(schemaObject(
                                List.of("label", "priority", "instruction", "evidenceIds", "toolInvocationIds"),
                                MockData.map(
                                        "label", stringSchema(),
                                        "priority", enumSchema("LOW", "MEDIUM", "HIGH"),
                                        "instruction", stringSchema(),
                                        "evidenceIds", idArraySchema(evidenceIds),
                                        "toolInvocationIds", idArraySchema(toolInvocationIds)
                                )
                        )),
                        "escalation", schemaObject(
                                List.of("required", "reason"),
                                MockData.map(
                                        "required", MockData.map("type", "boolean"),
                                        "reason", stringSchema()
                                )
                        ),
                        "ticketDraft", schemaObject(
                                List.of("symptomSummary", "recommendedLogRequest"),
                                MockData.map(
                                        "symptomSummary", stringSchema(),
                                        "recommendedLogRequest", stringSchema()
                                )
                        )
                )
        );
    }

    private static Map<String, Object> idArraySchema(List<String> allowedIds) {
        Map<String, Object> itemSchema = allowedIds == null || allowedIds.isEmpty()
                ? stringSchema()
                : MockData.map("type", "string", "enum", allowedIds);
        return MockData.map(
                "type", "array",
                "items", itemSchema
        );
    }

    private static Map<String, Object> stringSchema() {
        return MockData.map("type", "string");
    }

    private static Map<String, Object> enumSchema(String... values) {
        return MockData.map(
                "type", "string",
                "enum", List.of(values)
        );
    }

    private static Map<String, Object> arraySchema(Map<String, Object> items) {
        return MockData.map(
                "type", "array",
                "items", items
        );
    }

    private Map<String, Object> strictJson(String raw) {
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(normalizeJsonPayload(raw), MAP_TYPE);
            requireJsonField(parsed, "assistantMessage");
            requireJsonField(parsed, "causeCandidates");
            requireJsonField(parsed, "nextActions");
            requireJsonField(parsed, "escalation");
            requireJsonField(parsed, "ticketDraft");
            return parsed;
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM이 JSON 계약을 지키지 않았습니다.", error);
        }
    }

    private static String normalizeJsonPayload(String raw) {
        String text = safe(raw).trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1).trim();
        }
        return text;
    }

    private static void requireJsonField(Map<String, Object> parsed, String key) {
        if (!parsed.containsKey(key) || parsed.get(key) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM 응답에 필수 필드가 없습니다: " + key);
        }
    }

    private static String requireAssistantMessage(Map<String, Object> parsed) {
        Object value = parsed.get("assistantMessage");
        if (!(value instanceof String message) || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LLM assistantMessage가 비어 있습니다.");
        }
        return message.trim();
    }

    private void failAgentSession(String agentSessionId, RuntimeException error) {
        try {
            agentTraceService.updateSummary(agentSessionId, "AS chat failed: " + safeReason(error));
            agentTraceService.advanceStatus(agentSessionId, AgentStatus.FAILED, "SYSTEM", "AS chat failed");
        } catch (RuntimeException ignored) {
            // Failure recording must not hide the original API error.
        }
    }

    private TicketRow ticketRow(Map<String, Object> row) {
        return new TicketRow(
                longValue(row, "internal_id"),
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "symptom"),
                DbValueMapper.string(row, "status"),
                DbValueMapper.json(row, "cause_candidates", List.of()),
                DbValueMapper.json(row, "upgrade_candidates", List.of()),
                DbValueMapper.string(row, "log_summary"),
                DbValueMapper.timestamp(row, "created_at")
        );
    }

    private ChatSessionRow chatSessionRow(Map<String, Object> row) {
        return new ChatSessionRow(
                longValue(row, "internal_id"),
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "title"),
                DbValueMapper.string(row, "status")
        );
    }

    private Map<String, Object> ticketMap(TicketRow ticket) {
        return MockData.map(
                "id", ticket.publicId(),
                "status", ticket.status(),
                "symptom", ticket.symptom(),
                "logSummary", ticket.logSummary(),
                "causeCandidates", ticket.causeCandidates(),
                "upgradeCandidates", ticket.upgradeCandidates(),
                "createdAt", ticket.createdAt()
        );
    }

    private Map<String, Object> messageMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "role", DbValueMapper.string(row, "role"),
                "content", DbValueMapper.string(row, "content"),
                "structuredPayload", DbValueMapper.json(row, "structured_payload", Map.of()),
                "agentSessionId", DbValueMapper.string(row, "agent_session_id"),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    private static String titleFromSymptom(String symptom) {
        String text = safe(symptom).replaceAll("\\s+", " ").trim();
        if (text.isBlank()) {
            return "AS AI 챗봇 상담";
        }
        return text.length() > 80 ? text.substring(0, 80) : text;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private AiProfileDefinition requireAiProfile(String requestedAiProfile, TicketRow ticket, String userMessage) {
        try {
            return asChatProfilePolicy.resolve(requestedAiProfile, ticket.symptom(), ticket.logSummary(), userMessage);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Map<String, Object> objectValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private static String safeReason(RuntimeException error) {
        if (error instanceof ResponseStatusException responseStatusException && responseStatusException.getReason() != null) {
            return responseStatusException.getReason();
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static long elapsedMs(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static Map<String, Object> progressPayload(String state, String message, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", state);
        payload.put("message", message);
        payload.put("createdAt", java.time.OffsetDateTime.now().toString());
        if (extra != null) {
            payload.putAll(extra);
        }
        return payload;
    }

    private static boolean isEmptyJsonValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return value.toString().isBlank() || "[]".equals(value.toString()) || "{}".equals(value.toString());
    }

    private static String trimText(String value, int maxLength) {
        String text = safe(value).replaceAll("\\s+", " ").trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    public interface AsChatProgressSink {
        void emit(String eventName, Map<String, Object> payload);
    }

    private record TicketRow(
            Long internalId,
            String publicId,
            String symptom,
            String status,
            Object causeCandidates,
            Object upgradeCandidates,
            String logSummary,
            Object createdAt
    ) {
    }

    private record ChatSessionRow(Long internalId, String publicId, String title, String status) {
    }
}
