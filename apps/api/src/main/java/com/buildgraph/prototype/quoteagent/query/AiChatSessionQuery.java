package com.buildgraph.prototype.quoteagent.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class AiChatSessionQuery {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AiChatSessionQuery(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public AiChatSessionState findOrCreate(String sessionId, Long userInternalId) {
        if (userInternalId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            return create(userInternalId);
        }

        UUID parsedSessionId = parseSessionId(sessionId);
        List<AiChatSessionState> rows = jdbcTemplate.query("""
                SELECT session_id::text AS session_id, context
                FROM ai_chat_sessions
                WHERE session_id = ?
                  AND user_id = ?
                """,
                (rs, rowNum) -> new AiChatSessionState(
                        rs.getString("session_id"),
                        parseContext(rs.getString("context"))
                ),
                parsedSessionId,
                userInternalId
        );

        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "대화 세션을 찾을 수 없습니다.");
        }
        return rows.get(0);
    }

    private AiChatSessionState create(Long userInternalId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO ai_chat_sessions (user_id, context)
                VALUES (?, '{}'::jsonb)
                RETURNING session_id::text AS session_id, context
                """,
                (rs, rowNum) -> new AiChatSessionState(
                        rs.getString("session_id"),
                        parseContext(rs.getString("context"))
                ),
                userInternalId
        );
    }

    public void updateSession(
            String sessionId,
            Long userInternalId,
            Map<String, Object> newContext
    ) {
        try {
            Map<String, Object> updatedContext = new LinkedHashMap<>();
            Map<String, Object> oldContext = getOldContext(sessionId, userInternalId);
            Set<String> usageTags = new LinkedHashSet<>();

            Object oldBudget = oldContext.get("budget");
            Object newBudget = newContext.get("budget");
            updatedContext.put("budget", newBudget != null ? newBudget : oldBudget);

            usageTags.addAll(stringList(oldContext.get("usageTags")));
            usageTags.addAll(stringList(newContext.get("usageTags")));
            updatedContext.put("usageTags", List.copyOf(usageTags));

            String jsonContext = objectMapper.writeValueAsString(updatedContext);
            int updated = jdbcTemplate.update("""
                    UPDATE ai_chat_sessions
                    SET context = ?::jsonb
                    WHERE session_id = ?
                      AND user_id = ?
                    """,
                    jsonContext,
                    parseSessionId(sessionId),
                    userInternalId
            );
            if (updated == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "대화 세션을 찾을 수 없습니다.");
            }
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("contextPatch JSON 변환 오류", error);
        }
    }

    private Map<String, Object> getOldContext(String sessionId, Long userInternalId) {
        return jdbcTemplate.query("""
                SELECT context::text AS context
                FROM ai_chat_sessions
                WHERE session_id = ?
                  AND user_id = ?
                FOR UPDATE
                """,
                rs -> {
                    if (!rs.next()) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "대화 세션을 찾을 수 없습니다.");
                    }
                    return parseContext(rs.getString("context"));
                },
                parseSessionId(sessionId),
                userInternalId
        );
    }

    private static UUID parseSessionId(String sessionId) {
        try {
            return UUID.fromString(sessionId);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId 형식이 올바르지 않습니다.", error);
        }
    }

    private Map<String, Object> parseContext(String contextJson) {
        try {
            if (contextJson == null || contextJson.isBlank()) {
                return Map.of();
            }
            return objectMapper.readValue(
                    contextJson,
                    new TypeReference<Map<String, Object>>() {
                    }
            );
        } catch (Exception error) {
            throw new IllegalArgumentException("context 파싱 실패", error);
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(item -> Objects.toString(item, ""))
                .filter(item -> !item.isBlank())
                .toList();
    }
}
