package com.buildgraph.prototype.quoteagent.query;

import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class AiChatSessionStore {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /* sessionId 기반 찾기 => 없으면 생성 후 반환 */
    public AiChatSessionState findOrCreate(String sessionId){

        /* 애초에 session_id가 없으면 생성해야 함 */
        if (sessionId == null || sessionId.isBlank()) {
            return create();
        }
        
        List<AiChatSessionState> rows = jdbcTemplate.query("""
                SELECT session_id::text AS session_id,
                       context
                FROM ai_chat_sessions
                WHERE session_id = ?::uuid
                """,
                (rs, rowNum) -> new AiChatSessionState(
                        rs.getString("session_id"),
                        parseContext(rs.getString("context"))
                ),
                sessionId
        );

        return rows.isEmpty() ? create() : rows.get(0);
    }

    /* 새 session 생성하기 */
    private AiChatSessionState create(){
        return jdbcTemplate.queryForObject("""
                INSERT INTO ai_chat_sessions (context)
                VALUES ('{}'::jsonb)
                RETURNING session_id::text AS session_id, context
                """,
                /* 반환값 어디에서 가져올지 식 작성 */
                (rs, rowNum) -> new AiChatSessionState(
                        rs.getString("session_id"),
                        parseContext(rs.getString("context"))
                )
        );
    }

    /* 기존 session 업데이트 */
    public void updateSession(String sessionId, String jsonContext){
        jdbcTemplate.update("""
                UPDATE ai_chat_sessions
                SET context = ?::jsonb
                WHERE session_id = ?::uuid
                """,
                jsonContext, sessionId);
    }

    /* helper 함수들 */
    private Map<String, Object> parseContext(String contextJson) {
    try {
        if (contextJson == null || contextJson.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(
                contextJson,
                new TypeReference<Map<String, Object>>() {}
        );
    } catch (Exception e) {
        throw new IllegalArgumentException("context 파싱 실패", e);
    }
}
}
