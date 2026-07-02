package com.buildgraph.prototype.rag;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        int candidateLimit = Math.max(offset + safeSize, Math.min(100, Math.max(50, safeSize * 5)));
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
                          AND (
                            input.purpose IS NULL
                            OR re.metadata->>'purpose' = input.purpose
                            OR jsonb_exists(coalesce(re.metadata->'purposes', '[]'::jsonb), input.purpose)
                          )
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
                        OFFSET 0
                        """,
                        vector,
                        normalizedQuery,
                        normalizedPurpose,
                        normalizedSourceType,
                        candidateLimit)
                .stream()
                .map(row -> rerankedRow(row, normalizedQuery, normalizedPurpose))
                .sorted(Comparator
                        .comparingDouble((Map<String, Object> row) -> number(row.get("score"))).reversed()
                        .thenComparing(row -> DbValueMapper.string(row, "source_id")))
                .skip(offset)
                .limit(safeSize)
                .map(this::publicEvidenceMap)
                .toList();
        Integer total = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM rag_evidence re
                WHERE re.agent_session_id IS NULL
                  AND re.embedding IS NOT NULL
                  AND (
                    ?::text IS NULL
                    OR re.metadata->>'purpose' = ?::text
                    OR jsonb_exists(coalesce(re.metadata->'purposes', '[]'::jsonb), ?::text)
                  )
                  AND (?::text IS NULL OR re.metadata->>'sourceType' = ?::text)
                """, Integer.class, normalizedPurpose, normalizedPurpose, normalizedPurpose, normalizedSourceType, normalizedSourceType);
        return MockData.map("items", items, "page", safePage, "size", safeSize, "total", total == null ? 0 : total);
    }

    private Map<String, Object> rerankedRow(Map<String, Object> row, String normalizedQuery, String normalizedPurpose) {
        double baseScore = number(row.get("score"));
        double bonus = rerankBonus(row, normalizedQuery, normalizedPurpose);
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("score", baseScore + bonus);
        Map<String, Object> metadata = objectMap(DbValueMapper.json(row, "metadata", Map.of()));
        Map<String, Object> enrichedMetadata = new LinkedHashMap<>(metadata);
        enrichedMetadata.put("rerankBonus", bonus);
        enrichedMetadata.put("rerankVersion", "purpose-token-v1");
        result.put("metadata", enrichedMetadata);
        return result;
    }

    private double rerankBonus(Map<String, Object> row, String normalizedQuery, String normalizedPurpose) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return 0;
        }
        String purpose = normalizedPurpose == null ? "" : normalizedPurpose;
        String query = normalizedQuery.toLowerCase(Locale.ROOT);
        String sourceId = DbValueMapper.string(row, "source_id").toLowerCase(Locale.ROOT);
        String haystack = String.join(" ",
                sourceId,
                DbValueMapper.string(row, "summary"),
                DbValueMapper.string(row, "chunk_text"),
                String.valueOf(row.get("metadata"))
        ).toLowerCase(Locale.ROOT);

        double bonus = tokenOverlapBonus(query, haystack);
        if ("REQUIREMENT_PARSE".equals(purpose)) {
            bonus += requirementParseBonus(query, sourceId);
        } else if ("BUILD_RECOMMEND".equals(purpose)) {
            bonus += buildRecommendBonus(query, sourceId);
        } else if ("AS_ANALYZE".equals(purpose)) {
            bonus += asAnalyzeBonus(query, sourceId);
        }
        return Math.min(0.90, bonus);
    }

    private double tokenOverlapBonus(String query, String haystack) {
        double bonus = 0;
        for (String token : query.split("[^0-9a-zA-Z가-힣]+")) {
            if (token.length() < 2) {
                continue;
            }
            if (haystack.contains(token)) {
                bonus += token.matches("[0-9]+|rtx|qhd|uhd|fhd|psu|gpu|cpu|ram|ssd|cuda") ? 0.08 : 0.035;
            }
        }
        return Math.min(0.30, bonus);
    }

    private double requirementParseBonus(String query, String sourceId) {
        double bonus = 0;
        boolean explicitBudget = query.matches(".*\\d+\\s*(만원|만|원|아래|이하|으로|안에서).*");
        boolean premium = containsAny(query, "끝판왕", "최고급", "하이엔드", "플래그십", "고성능", "성능만", "가격 상관", "돈 상관", "예산 무관",
                "제일 좋은", "최상급", "최대한 좋은", "가장 좋은");
        boolean openBudget = containsAny(query, "예산 무관", "가격 상관", "돈 상관", "예산 없음", "예산 없이");
        boolean explicitGpu = containsAny(query, "5090", "5080", "5070", "rtx");
        if (premium && !explicitBudget && sourceId.equals("internal-rule-requirement-parse-premium-open-budget")) {
            bonus += openBudget ? 0.55 : 0.42;
        }
        if (premium && explicitBudget && sourceId.equals("requirement-counterexample-premium-with-user-budget")) {
            bonus += 0.58;
        }
        if (explicitBudget
                && containsAny(query, "예산", "아래", "이하", "안에서", "안쪽", "으로")
                && sourceId.equals("requirement-counterexample-premium-with-user-budget")) {
            bonus += containsAny(query, "제일 좋은", "최상급", "최대한 좋은", "4k", "5090", "그래픽카드") ? 0.42 : 0.26;
        }
        if (explicitGpu && sourceId.equals("requirement-rule-explicit-gpu-class-hard-constraint")) {
            bonus += 0.35;
        }
        if (explicitGpu && !explicitBudget && sourceId.equals("internal-rule-requirement-parse-premium-open-budget")) {
            bonus += 0.30;
        }
        if (explicitGpu && explicitBudget && sourceId.equals("requirement-counterexample-explicit-gpu-with-user-budget")) {
            bonus += 0.40;
        }
        if (explicitGpu && explicitBudget && sourceId.equals("requirement-counterexample-premium-with-user-budget")) {
            bonus += 0.36;
        }
        if (containsAny(query, "예산 미정", "예산은 아직", "가격은 나중", "해상도", "qhd", "브랜드는 상관", "초보")
                && sourceId.equals("guide-requirement-parse-budget-resolution-workload")) {
            bonus += 0.42;
        }
        if (containsAny(query, "사무", "학습", "예산", "중간", "우선", "상관없", "미니타워", "작은 케이스", "램", "저장공간", "넉넉")
                && sourceId.equals("guide-requirement-parse-budget-resolution-workload")) {
            bonus += 0.40;
        }
        if (containsAny(query, "nvidia", "엔비디아", "지포스", "라데온", "asus", "브랜드")
                && sourceId.equals("requirement-example-noise-upgrade-brand")) {
            bonus += 0.45;
        }
        if (containsAny(query, "저소음", "조용", "소음", "업그레이드", "오래 쓸", "밤에")
                && !containsAny(query, "소음은 상관없")
                && (sourceId.equals("internal-rule-requirement-parse-noise-upgrade")
                || sourceId.equals("requirement-example-noise-upgrade-brand"))) {
            bonus += 0.45;
        }
        if (containsAny(query, "게임", "배그", "144", "qhd", "4k", "프레임")
                && (sourceId.equals("requirement-example-gaming-resolution-refresh")
                || sourceId.equals("benchmark-requirement-parse-gaming-development"))) {
            bonus += containsAny(query, "배그", "qhd", "4k", "144", "프레임") ? 0.42 : 0.30;
        }
        if (containsAny(query, "개발", "영상", "편집", "ai", "llm", "렌더링", "언리얼", "워크스테이션")
                && sourceId.equals("requirement-example-workload-mixed-creator-ai")) {
            bonus += 0.34;
        }
        if (sourceId.equals("requirement-example-workload-mixed-creator-ai")
                && (containsAny(query, "둘 다", "겸용", "같이", "방송", "송출")
                || (containsAny(query, "게임", "qhd", "배그") && containsAny(query, "프리미어", "개발", "ide", "영상", "편집")))) {
            bonus += 0.38;
        }
        return bonus;
    }

    private double buildRecommendBonus(String query, String sourceId) {
        double bonus = 0;
        if (containsAny(query, "케이스", "쿨링", "컴팩트", "작은", "길이", "높이", "흡기", "airflow")
                && sourceId.equals("build-rule-airflow-cooler-case-fit")) {
            bonus += 0.60;
        }
        if (containsAny(query, "가격", "스냅샷", "현재가", "저장", "외부 api", "매번", "parts.price")
                && sourceId.equals("price-guide-saved-snapshot-first")) {
            bonus += 0.72;
        }
        if (containsAny(query, "가격", "스냅샷", "현재가", "저장", "외부 api", "매번", "parts.price")
                && sourceId.equals("build-rule-saved-price-and-psu-headroom")) {
            bonus += 0.48;
        }
        if (containsAny(query, "파워", "전력", "psu", "atx", "헤드룸", "여유")
                && (sourceId.equals("internal-rule-psu-atx31-power-margin")
                || sourceId.equals("build-rule-saved-price-and-psu-headroom"))) {
            bonus += 0.42;
        }
        if (containsAny(query, "qhd", "게임", "gpu", "그래픽", "144")
                && sourceId.equals("internal-rule-build-qhd-gaming-gpu-priority")) {
            bonus += 0.34;
        }
        if (containsAny(query, "cpu", "병목", "균형", "고주사율")
                && sourceId.equals("build-rule-cpu-gpu-balance-and-bottleneck")) {
            bonus += 0.36;
        }
        if (containsAny(query, "ram", "램", "메모리", "nvme", "저장", "스토리지", "ssd")
                && sourceId.equals("build-rule-memory-storage-workload-floor")) {
            bonus += 0.36;
        }
        if (containsAny(query, "5090", "5080", "rtx 50", "rtx50", "글카")
                && (sourceId.equals("part-catalog-rtx50-tool-ready-dimensions")
                || sourceId.equals("part-spec-rtx-5090-class")
                || sourceId.equals("build-rule-hard-gpu-class-selection"))) {
            bonus += 0.34;
        }
        return bonus;
    }

    private double asAnalyzeBonus(String query, String sourceId) {
        if (containsAny(query, "먼지", "흡기", "팬", "쿨러", "케이스", "발열")
                && (sourceId.equals("support-guide-airflow-upgrade-before-gpu")
                || sourceId.equals("as-guide-gpu-thermal-frame-drop"))) {
            return 0.28;
        }
        return 0;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
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
                        AND (
                          ?::text IS NULL
                          OR re.metadata->>'purpose' = ?::text
                          OR jsonb_exists(coalesce(re.metadata->'purposes', '[]'::jsonb), ?::text)
                        )
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
                AND (
                  ?::text IS NULL
                  OR re.metadata->>'purpose' = ?::text
                  OR jsonb_exists(coalesce(re.metadata->'purposes', '[]'::jsonb), ?::text)
                )
                AND (?::text IS NULL OR re.metadata->>'sourceType' = ?::text)
                """,
                Integer.class,
                normalizedQuery,
                normalizedQuery,
                normalizedQuery,
                normalizedQuery,
                normalizedPurpose,
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

    public Map<String, Object> adminEvidenceList() {
        int page = 0;
        int size = 20;
        List<Map<String, Object>> items = jdbcTemplate.queryForList(baseEvidenceSql() + """
                        ORDER BY re.created_at DESC, re.id DESC
                        LIMIT ?
                        """, size)
                .stream()
                .map(this::adminEvidenceMap)
                .toList();
        Integer total = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM rag_evidence
                """, Integer.class);
        return MockData.map("items", items, "page", page, "size", size, "total", total == null ? 0 : total);
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
