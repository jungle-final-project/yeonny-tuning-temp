package com.buildgraph.prototype.rag;

import com.buildgraph.prototype.opsagent.profile.OpenAiEmbeddingClient;
import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RagEmbeddingService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final OpenAiEmbeddingClient embeddingClient;
    private final boolean vectorEnabled;

    public RagEmbeddingService(
            JdbcTemplate jdbcTemplate,
            OpenAiEmbeddingClient embeddingClient,
            @Value("${rag.vector.enabled:true}") boolean vectorEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
        this.vectorEnabled = vectorEnabled;
    }

    public boolean canVectorSearch() {
        return vectorEnabled && embeddingClient.isConfigured();
    }

    public List<Double> embedQuery(String queryText) {
        if (!canVectorSearch()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "RAG vector search를 위한 OpenAI embedding 설정이 필요합니다.");
        }
        return embeddingClient.embed(queryText);
    }

    public Map<String, Object> backfillReusableEmbeddings(Integer requestedLimit) {
        if (!vectorEnabled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RAG vector search가 비활성화되어 있습니다.");
        }
        if (!embeddingClient.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "OPENAI_API_KEY가 필요합니다.");
        }
        int limit = requestedLimit == null ? 200 : Math.max(1, Math.min(requestedLimit, 500));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT public_id::text AS id,
                       source_id,
                       chunk_text,
                       summary,
                       score,
                       metadata
                FROM rag_evidence
                WHERE agent_session_id IS NULL
                ORDER BY id
                LIMIT ?
                """, limit);
        int scanned = 0;
        int updated = 0;
        int skipped = 0;
        for (Map<String, Object> row : rows) {
            scanned++;
            String embeddingText = embeddingText(row);
            String hash = textHash(embeddingText);
            Map<String, Object> metadata = metadata(row);
            if (upToDate(metadata, hash)) {
                skipped++;
                continue;
            }
            List<Double> embedding = embeddingClient.embed(embeddingText);
            Map<String, Object> embeddingMetadata = new LinkedHashMap<>();
            embeddingMetadata.put("embeddingModel", embeddingClient.model());
            embeddingMetadata.put("embeddingDimensions", embeddingClient.dimensions());
            embeddingMetadata.put("embeddingTextHash", hash);
            embeddingMetadata.put("embeddingUpdatedAt", Instant.now().toString());
            jdbcTemplate.update("""
                    UPDATE rag_evidence
                    SET embedding = ?::vector,
                        metadata = coalesce(metadata, '{}'::jsonb) || ?::jsonb
                    WHERE public_id = ?::uuid
                    """, vectorLiteral(embedding), json(embeddingMetadata), DbValueMapper.string(row, "id"));
            updated++;
        }
        int embeddedTotal = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM rag_evidence
                WHERE agent_session_id IS NULL
                  AND embedding IS NOT NULL
                """, Integer.class);
        int reusableTotal = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM rag_evidence
                WHERE agent_session_id IS NULL
                """, Integer.class);
        return MockData.map(
                "scanned", scanned,
                "updated", updated,
                "skipped", skipped,
                "reusableTotal", reusableTotal,
                "embeddedTotal", embeddedTotal,
                "embeddingModel", embeddingClient.model(),
                "embeddingDimensions", embeddingClient.dimensions()
        );
    }

    public static String vectorLiteral(List<Double> embedding) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(Double.toString(embedding.get(i)));
        }
        return builder.append(']').toString();
    }

    public static String embeddingText(Map<String, Object> row) {
        Map<String, Object> metadata = metadata(row);
        return String.join("\n",
                safe(DbValueMapper.string(row, "source_id")),
                safe(DbValueMapper.string(row, "summary")),
                safe(DbValueMapper.string(row, "chunk_text")),
                safe(String.valueOf(metadata.getOrDefault("title", ""))),
                safe(String.valueOf(metadata.getOrDefault("purpose", ""))),
                safe(String.valueOf(metadata.getOrDefault("sourceType", ""))),
                safe(String.valueOf(metadata.getOrDefault("relatedCategories", ""))),
                safe(String.valueOf(metadata.getOrDefault("relatedFields", "")))
        ).trim();
    }

    public static String textHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(safe(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("RAG embedding hash 계산에 실패했습니다.", error);
        }
    }

    private boolean upToDate(Map<String, Object> metadata, String hash) {
        return embeddingClient.model().equals(metadata.get("embeddingModel"))
                && Integer.valueOf(embeddingClient.dimensions()).equals(numberValue(metadata.get("embeddingDimensions")))
                && hash.equals(metadata.get("embeddingTextHash"));
    }

    private static Map<String, Object> metadata(Map<String, Object> row) {
        Object parsed = DbValueMapper.json(row, "metadata", Map.of());
        if (parsed instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static Integer numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 변환에 실패했습니다.", e);
        }
    }

    private static String safe(String value) {
        return value == null || "null".equals(value) ? "" : value;
    }
}
