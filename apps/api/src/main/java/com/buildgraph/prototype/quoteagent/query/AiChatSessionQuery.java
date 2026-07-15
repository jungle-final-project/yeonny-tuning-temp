package com.buildgraph.prototype.quoteagent.query;

import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class AiChatSessionQuery {
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
    public void updateSession(
        String sessionId, 
        Map<String, Object> newContext){
        try{
            /* 객체들 생성 및 불러오기 */
            Map<String, Object> updatedContext = new LinkedHashMap<>();
            Map<String, Object> oldContext = getOldContext(sessionId);
            Set<String> usageTags = new LinkedHashSet<>();

            /* 예산안 업데이트 진행 */
            Object oldBudget = oldContext.get("budget");
            Object newBudget = newContext.get("budget");
            updatedContext.put("budget", newBudget != null ? newBudget : oldBudget);

            /* usageTags 업데이트 진행 */
            usageTags.addAll(stringList(oldContext.get("usageTags")));
            usageTags.addAll(stringList(newContext.get("usageTags")));
            updatedContext.put("usageTags", List.copyOf(usageTags));

            /* json화 */
            String jsonContext = objectMapper.writeValueAsString(updatedContext);     
            jdbcTemplate.update("""
                    UPDATE ai_chat_sessions
                    SET context = ?::jsonb
                    WHERE session_id = ?::uuid
                    """,
                    jsonContext, sessionId);

        }catch(JsonProcessingException e){
            throw new IllegalArgumentException("contextPatch JSON 변환 오류: ", e);
        }
    }

    /* 보조 함수: 기존 sessionId 기반 객체 조회 */
    public Map<String, Object> getOldContext(String sessionId){
        return jdbcTemplate.query("""
                SELECT context::text AS context
                FROM ai_chat_sessions
                WHERE session_id = ?::uuid
                FOR UPDATE
                """,
                rs -> {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("해당 세션이 존재하지 않습니다: " + sessionId);
                    }
                    return parseContext(rs.getString("context"));
                },
                sessionId
        );      
    }


    /* helper 함수 .1 */
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

    /* helper 함수 .2 */
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }

        return values.stream()
            .filter(Objects::nonNull) 
            .map(item -> Objects.toString(item, "")) 
            .filter(s -> !s.isBlank())
            .toList();
    }   
}
