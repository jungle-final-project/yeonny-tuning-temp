package com.buildgraph.prototype.rag;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RagQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final RagEmbeddingService ragEmbeddingService;
    private final RagVectorPolicy ragVectorPolicy;

    public RagQueryService(
            JdbcTemplate jdbcTemplate,
            RagEmbeddingService ragEmbeddingService,
            RagVectorPolicy ragVectorPolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.ragEmbeddingService = ragEmbeddingService;
        this.ragVectorPolicy = ragVectorPolicy;
    }

    public Map<String, Object> search(String query) {
        return search(query, null, null);
    }

    public Map<String, Object> search(String query, Integer page, Integer size) {
        return search(query, null, null, page, size);
    }

    public Map<String, Object> search(String query, String purpose, String sourceType, Integer page, Integer size) {
        String normalizedQuery = blankToNull(query);
        String normalizedPurpose = blankToNull(purpose);
        String normalizedSourceType = blankToNull(sourceType);
        int safePage = validatePage(page);
        int safeSize = validateSize(size);
        if (normalizedQuery != null
                && ragEmbeddingService.canVectorSearch()
                && ragVectorPolicy.publicSearchEnabledFor(normalizedPurpose)) {
            try {
                return vectorSearch(normalizedQuery, normalizedPurpose, normalizedSourceType, safePage, safeSize);
            } catch (RuntimeException ignored) {
                // Keep the public search endpoint available even when live embedding lookup fails.
            }
        }
        return keywordSearch(normalizedQuery, normalizedPurpose, normalizedSourceType, safePage, safeSize);
    }

    private Map<String, Object> vectorSearch(
            String normalizedQuery,
            String normalizedPurpose,
            String normalizedSourceType,
            int safePage,
            int safeSize
    ) {
        int offset = safePage * safeSize;
        String vector = RagEmbeddingService.vectorLiteral(ragEmbeddingService.embedQuery(normalizedQuery));
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                        WITH input AS (
                          SELECT ?::vector AS query_embedding,
                                 ?::text AS query_text,
                                 ?::text AS purpose,
                                 ?::text AS source_type
                        ),
                        ranked AS (
                        SELECT re.public_id::text AS id,
                               s.public_id::text AS agent_session_id,
                               re.source_id,
                               re.chunk_text,
                               re.summary,
                               (1 - (re.embedding <=> input.query_embedding))::double precision AS vector_score,
                               (
                                 CASE WHEN lower(re.source_id) LIKE lower(concat('%', input.query_text, '%')) THEN 0.30 ELSE 0 END +
                                 CASE WHEN lower(re.summary) LIKE lower(concat('%', input.query_text, '%')) THEN 0.20 ELSE 0 END +
                                 CASE WHEN lower(re.chunk_text) LIKE lower(concat('%', input.query_text, '%')) THEN 0.30 ELSE 0 END +
                                 CASE WHEN lower(coalesce(re.metadata->>'title', '')) LIKE lower(concat('%', input.query_text, '%')) THEN 0.20 ELSE 0 END
                               )::double precision AS keyword_score,
                               re.score AS stored_score,
                               coalesce(re.metadata, '{}'::jsonb) || jsonb_build_object(
                                 'retrievalMode', 'VECTOR',
                                 'vectorScore', (1 - (re.embedding <=> input.query_embedding)),
                                 'keywordScore',
                                 (
                                   CASE WHEN lower(re.source_id) LIKE lower(concat('%', input.query_text, '%')) THEN 0.30 ELSE 0 END +
                                   CASE WHEN lower(re.summary) LIKE lower(concat('%', input.query_text, '%')) THEN 0.20 ELSE 0 END +
                                   CASE WHEN lower(re.chunk_text) LIKE lower(concat('%', input.query_text, '%')) THEN 0.30 ELSE 0 END +
                                   CASE WHEN lower(coalesce(re.metadata->>'title', '')) LIKE lower(concat('%', input.query_text, '%')) THEN 0.20 ELSE 0 END
                                 )
                               ) AS metadata
                        FROM rag_evidence re
                        CROSS JOIN input
                        LEFT JOIN agent_sessions s ON s.id = re.agent_session_id
                        WHERE re.agent_session_id IS NULL
                          AND re.embedding IS NOT NULL
                          AND (input.purpose IS NULL OR re.metadata->>'purpose' = input.purpose)
                          AND (input.source_type IS NULL OR re.metadata->>'sourceType' = input.source_type)
                        )
                        SELECT id,
                               agent_session_id,
                               source_id,
                               chunk_text,
                               summary,
                               (vector_score + keyword_score)::double precision AS score,
                               metadata
                        FROM ranked
                        ORDER BY (vector_score + keyword_score) DESC,
                                 vector_score DESC,
                                 stored_score DESC NULLS LAST,
                                 id
                        LIMIT ?
                        OFFSET ?
                        """,
                        vector,
                        normalizedQuery,
                        normalizedPurpose,
                        normalizedSourceType,
                        safeSize,
                        offset)
                .stream()
                .map(this::publicEvidenceMap)
                .toList();
        Integer total = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM rag_evidence re
                WHERE re.agent_session_id IS NULL
                  AND re.embedding IS NOT NULL
                  AND (?::text IS NULL OR re.metadata->>'purpose' = ?::text)
                  AND (?::text IS NULL OR re.metadata->>'sourceType' = ?::text)
                """, Integer.class, normalizedPurpose, normalizedPurpose, normalizedSourceType, normalizedSourceType);
        return MockData.map("items", items, "page", safePage, "size", safeSize, "total", total == null ? 0 : total);
    }

    private Map<String, Object> keywordSearch(
            String normalizedQuery,
            String normalizedPurpose,
            String normalizedSourceType,
            int safePage,
            int safeSize
    ) {
        int offset = safePage * safeSize;
        List<Object> params = new ArrayList<>();
        params.add(normalizedQuery);
        params.add(normalizedQuery);
        params.add(normalizedQuery);
        params.add(normalizedQuery);
        params.add(normalizedPurpose);
        params.add(normalizedPurpose);
        params.add(normalizedSourceType);
        params.add(normalizedSourceType);
        params.add(safeSize);
        params.add(offset);
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                        SELECT re.public_id::text AS id,
                               s.public_id::text AS agent_session_id,
                               re.source_id,
                               re.chunk_text,
                               re.summary,
                               re.score,
                               re.metadata
                        FROM rag_evidence re
                        LEFT JOIN agent_sessions s ON s.id = re.agent_session_id
                        WHERE (
                          ?::text IS NULL
                          OR lower(re.source_id) LIKE lower(concat('%', ?, '%'))
                          OR lower(re.summary) LIKE lower(concat('%', ?, '%'))
                          OR lower(re.chunk_text) LIKE lower(concat('%', ?, '%'))
                        )
                        AND (?::text IS NULL OR re.metadata->>'purpose' = ?::text)
                        AND (?::text IS NULL OR re.metadata->>'sourceType' = ?::text)
                        ORDER BY CASE WHEN re.agent_session_id IS NULL THEN 0 ELSE 1 END,
                                 re.score DESC NULLS LAST,
                                 re.id
                        LIMIT ?
                        OFFSET ?
                        """, params.toArray())
                .stream()
                .map(this::publicEvidenceMap)
                .toList();
        Integer total = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM rag_evidence re
                WHERE (
                  ?::text IS NULL
                  OR lower(re.source_id) LIKE lower(concat('%', ?, '%'))
                  OR lower(re.summary) LIKE lower(concat('%', ?, '%'))
                  OR lower(re.chunk_text) LIKE lower(concat('%', ?, '%'))
                )
                AND (?::text IS NULL OR re.metadata->>'purpose' = ?::text)
                AND (?::text IS NULL OR re.metadata->>'sourceType' = ?::text)
                """,
                Integer.class,
                normalizedQuery,
                normalizedQuery,
                normalizedQuery,
                normalizedQuery,
                normalizedPurpose,
                normalizedPurpose,
                normalizedSourceType,
                normalizedSourceType);
        return MockData.map("items", items, "page", safePage, "size", safeSize, "total", total == null ? 0 : total);
    }

    public Map<String, Object> evidence(String id) {
        return evidenceRow(id).map(this::publicEvidenceMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RAG 근거를 찾을 수 없습니다."));
    }

    public Map<String, Object> adminEvidence(String id) {
        return evidenceRow(id).map(this::adminEvidenceMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RAG 근거를 찾을 수 없습니다."));
    }

    public List<Map<String, Object>> evidenceBySession(String sessionId) {
        return jdbcTemplate.queryForList(baseEvidenceSql() + """
                        WHERE s.public_id = ?::uuid
                        ORDER BY re.id
                        """, sessionId)
                .stream()
                .map(this::publicEvidenceMap)
                .toList();
    }

    private java.util.Optional<Map<String, Object>> evidenceRow(String id) {
        return jdbcTemplate.queryForList(baseEvidenceSql() + """
                        WHERE re.public_id = ?::uuid
                        """, id)
                .stream()
                .findFirst();
    }

    private String baseEvidenceSql() {
        return """
                SELECT re.public_id::text AS id,
                       s.public_id::text AS agent_session_id,
                       re.source_id,
                       re.chunk_text,
                       re.summary,
                       re.score,
                       re.metadata
                FROM rag_evidence re
                LEFT JOIN agent_sessions s ON s.id = re.agent_session_id
                """;
    }

    private Map<String, Object> publicEvidenceMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "sourceId", DbValueMapper.string(row, "source_id"),
                "summary", DbValueMapper.string(row, "summary"),
                "score", row.get("score"),
                "metadata", DbValueMapper.json(row, "metadata", Map.of())
        );
    }

    private Map<String, Object> adminEvidenceMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "agentSessionId", DbValueMapper.string(row, "agent_session_id"),
                "sourceId", DbValueMapper.string(row, "source_id"),
                "chunkText", DbValueMapper.string(row, "chunk_text"),
                "summary", DbValueMapper.string(row, "summary"),
                "score", row.get("score"),
                "metadata", DbValueMapper.json(row, "metadata", Map.of())
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int validatePage(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page는 0 이상이어야 합니다.");
        }
        return page;
    }

    private static int validateSize(Integer size) {
        if (size == null) {
            return 20;
        }
        if (size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size는 1 이상 100 이하이어야 합니다.");
        }
        return size;
    }
}
