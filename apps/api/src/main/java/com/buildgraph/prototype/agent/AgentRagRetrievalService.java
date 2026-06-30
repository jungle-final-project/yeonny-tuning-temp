package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AgentRagRetrievalService {
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^0-9A-Za-z가-힣]+");
    private static final int DEFAULT_EVIDENCE_LIMIT = 3;
    private final JdbcTemplate jdbcTemplate;

    public AgentRagRetrievalService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AgentRagEvidenceDraft retrieveEvidence(AgentSessionRoot root, AgentRunProfile profile) {
        return retrieveEvidenceSet(root, profile, 1).stream()
                .findFirst()
                .orElseGet(() -> AgentRunTraceDrafts.ragEvidence(root, profile));
    }

    public List<AgentRagEvidenceDraft> retrieveEvidenceSet(AgentSessionRoot root, AgentRunProfile profile, int limit) {
        return retrieveEvidenceSet(root, profile, "", limit);
    }

    public List<AgentRagEvidenceDraft> retrieveEvidenceSet(AgentSessionRoot root, AgentRunProfile profile, String extraQuery, int limit) {
        RootContext context = withExtraQuery(rootContext(root), extraQuery);
        List<String> queryTokens = tokens(context.queryText());
        List<RetrievalCandidate> ranked = reusableEvidenceRows().stream()
                .map(row -> candidate(row, root, profile, context, queryTokens))
                .filter(candidate -> candidate.allowed())
                .sorted(Comparator.comparingDouble(RetrievalCandidate::rank).reversed())
                .toList();
        List<AgentRagEvidenceDraft> selected = diversify(ranked, safeLimit(limit)).stream()
                .map(RetrievalCandidate::draft)
                .toList();
        return selected.isEmpty() ? List.of(AgentRunTraceDrafts.ragEvidence(root, profile)) : selected;
    }

    public List<AgentRagEvidenceDraft> retrieveEvidenceSet(AgentSessionRoot root, AgentRunProfile profile) {
        return retrieveEvidenceSet(root, profile, DEFAULT_EVIDENCE_LIMIT);
    }

    private static RootContext withExtraQuery(RootContext context, String extraQuery) {
        String extra = safe(extraQuery);
        if (extra.isBlank()) {
            return context;
        }
        return new RootContext(String.join(" ", safe(context.queryText()), extra));
    }

    private List<Map<String, Object>> reusableEvidenceRows() {
        return jdbcTemplate.queryForList("""
                SELECT public_id::text AS id,
                       source_id,
                       chunk_text,
                       summary,
                       score,
                       metadata
                FROM rag_evidence
                WHERE agent_session_id IS NULL
                ORDER BY score DESC NULLS LAST, id
                """);
    }

    private RetrievalCandidate candidate(
            Map<String, Object> row,
            AgentSessionRoot root,
            AgentRunProfile profile,
            RootContext context,
            List<String> queryTokens
    ) {
        Map<String, Object> sourceMetadata = metadata(row);
        String sourceType = stringValue(sourceMetadata.get("sourceType"));
        String purpose = stringValue(sourceMetadata.get("purpose"));
        boolean sourceTypeAllowed = sourceType != null && profile.ragSourceTypes().contains(sourceType);
        boolean purposeMatched = purpose == null || purpose.equals(profile.purpose().name());
        boolean allowed = sourceTypeAllowed && purposeMatched;

        String searchableText = String.join(" ",
                safe(DbValueMapper.string(row, "source_id")),
                safe(DbValueMapper.string(row, "summary")),
                safe(DbValueMapper.string(row, "chunk_text")),
                sourceMetadata.toString()
        ).toLowerCase(Locale.ROOT);
        int matchedTokens = 0;
        for (String token : queryTokens) {
            if (searchableText.contains(token.toLowerCase(Locale.ROOT))) {
                matchedTokens++;
            }
        }
        String metadataText = sourceMetadata.toString().toLowerCase(Locale.ROOT);
        int matchedMetadataTokens = 0;
        for (String token : queryTokens) {
            if (metadataText.contains(token.toLowerCase(Locale.ROOT))) {
                matchedMetadataTokens++;
            }
        }
        double tokenScore = queryTokens.isEmpty() ? 0.0 : matchedTokens / (double) queryTokens.size();
        double metadataScore = queryTokens.isEmpty() ? 0.0 : matchedMetadataTokens / (double) queryTokens.size();
        double baseScore = score(row);
        double rank = (baseScore * 0.50)
                + (purpose != null && purpose.equals(profile.purpose().name()) ? 0.25 : 0.0)
                + (sourceTypeAllowed ? 0.10 : 0.0)
                + tokenScore
                + (metadataScore * 0.35);

        Map<String, Object> metadata = new LinkedHashMap<>(sourceMetadata);
        metadata.put("sourceEvidenceId", DbValueMapper.string(row, "id"));
        metadata.put("sourceTypes", profile.ragSourceTypes());
        metadata.put("purpose", profile.purpose().name());
        metadata.put("rootType", root.type().name());
        metadata.put("rootId", root.publicId());
        metadata.put("retrievalQuery", context.queryText());
        metadata.put("matchedTokenCount", matchedTokens);
        metadata.put("matchedMetadataTokenCount", matchedMetadataTokens);
        metadata.put("queryTokenCount", queryTokens.size());
        metadata.put("retrievalRank", rounded(rank));
        metadata.put("retrievedAt", MockData.now());

        AgentRagEvidenceDraft draft = new AgentRagEvidenceDraft(
                DbValueMapper.string(row, "source_id"),
                DbValueMapper.string(row, "chunk_text"),
                DbValueMapper.string(row, "summary"),
                BigDecimal.valueOf(Math.min(0.99000, Math.max(0.00000, rank)))
                        .setScale(5, RoundingMode.HALF_UP),
                metadata
        );
        return new RetrievalCandidate(allowed, rank, sourceType, draft);
    }

    private static List<RetrievalCandidate> diversify(List<RetrievalCandidate> ranked, int limit) {
        List<RetrievalCandidate> result = new ArrayList<>();
        Set<String> selectedSourceTypes = new LinkedHashSet<>();
        Set<String> selectedSourceIds = new LinkedHashSet<>();
        for (RetrievalCandidate candidate : ranked) {
            if (result.size() >= limit) {
                break;
            }
            String sourceType = safe(candidate.sourceType());
            String sourceId = candidate.draft().sourceId();
            if (!sourceId.isBlank() && !selectedSourceIds.contains(sourceId) && selectedSourceTypes.add(sourceType)) {
                result.add(candidate);
                selectedSourceIds.add(sourceId);
            }
        }
        for (RetrievalCandidate candidate : ranked) {
            if (result.size() >= limit) {
                break;
            }
            String sourceId = candidate.draft().sourceId();
            if (!sourceId.isBlank() && selectedSourceIds.add(sourceId)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static int safeLimit(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, 10);
    }

    private RootContext rootContext(AgentSessionRoot root) {
        return switch (root.type()) {
            case REQUIREMENT -> requirementContext(root.publicId());
            case BUILD -> buildContext(root.publicId());
            case AS_TICKET -> asTicketContext(root.publicId());
        };
    }

    private RootContext requirementContext(String publicId) {
        return jdbcTemplate.queryForList("""
                        SELECT raw_message,
                               coalesce(array_to_string(usage_tags, ' '), '') AS usage_tags,
                               coalesce(parsed_context::text, '') AS parsed_context
                        FROM requirements
                        WHERE public_id = ?::uuid
                        """, publicId)
                .stream()
                .findFirst()
                .map(row -> new RootContext(String.join(" ",
                        safe(DbValueMapper.string(row, "raw_message")),
                        safe(DbValueMapper.string(row, "usage_tags")),
                        safe(DbValueMapper.string(row, "parsed_context"))
                )))
                .orElseGet(() -> new RootContext(publicId));
    }

    private RootContext buildContext(String publicId) {
        return jdbcTemplate.queryForList("""
                        SELECT b.name,
                               b.total_price::text AS total_price,
                               coalesce(b.warnings::text, '') AS warnings,
                               r.raw_message,
                               coalesce(string_agg(
                                 concat_ws(' ', p.category, p.name, p.manufacturer, p.attributes->>'shortSpec'),
                                 ' '
                                 ORDER BY bi.category
                               ), '') AS parts_text
                        FROM builds b
                        JOIN requirements r ON r.id = b.requirement_id
                        LEFT JOIN build_items bi ON bi.build_id = b.id
                        LEFT JOIN parts p ON p.id = bi.part_id
                        WHERE b.public_id = ?::uuid
                        GROUP BY b.id, r.id
                        """, publicId)
                .stream()
                .findFirst()
                .map(row -> new RootContext(String.join(" ",
                        safe(DbValueMapper.string(row, "name")),
                        safe(DbValueMapper.string(row, "total_price")),
                        safe(DbValueMapper.string(row, "warnings")),
                        safe(DbValueMapper.string(row, "raw_message")),
                        safe(DbValueMapper.string(row, "parts_text"))
                )))
                .orElseGet(() -> new RootContext(publicId));
    }

    private RootContext asTicketContext(String publicId) {
        return jdbcTemplate.queryForList("""
                        SELECT t.symptom,
                               coalesce(t.cause_candidates::text, '') AS cause_candidates,
                               coalesce(t.upgrade_candidates::text, '') AS upgrade_candidates,
                               coalesce(l.summary, '') AS log_summary
                        FROM as_tickets t
                        LEFT JOIN agent_log_uploads l ON l.id = t.log_upload_id
                        WHERE t.public_id = ?::uuid
                          AND t.deleted_at IS NULL
                        """, publicId)
                .stream()
                .findFirst()
                .map(row -> new RootContext(String.join(" ",
                        safe(DbValueMapper.string(row, "symptom")),
                        safe(DbValueMapper.string(row, "cause_candidates")),
                        safe(DbValueMapper.string(row, "upgrade_candidates")),
                        safe(DbValueMapper.string(row, "log_summary"))
                )))
                .orElseGet(() -> new RootContext(publicId));
    }

    private static List<String> tokens(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String token : TOKEN_SPLIT.split(value)) {
            if (token.length() >= 2) {
                String normalized = token.toLowerCase(Locale.ROOT);
                result.add(normalized);
                result.addAll(expandedTokens(normalized));
            }
        }
        return new ArrayList<>(result);
    }

    private static List<String> expandedTokens(String token) {
        if (containsAny(token, "온도", "발열", "열", "뜨거", "스로틀")) {
            return List.of("thermal", "throttling", "gpu", "fan", "airflow", "먼지", "팬");
        }
        if (containsAny(token, "프레임", "렉", "끊김", "드랍", "급락", "frametime", "fps")) {
            return List.of("frame", "drop", "spike", "프레임", "급락", "튐");
        }
        if (containsAny(token, "드라이버", "블루스크린", "멈춤", "튕김", "nvlddmkm", "crash")) {
            return List.of("driver", "event", "log", "crash", "display", "오류");
        }
        if (containsAny(token, "램", "ram", "메모리", "크롬", "ide", "렌더링")) {
            return List.of("memory", "ram", "pressure", "storage", "디스크");
        }
        if (containsAny(token, "ssd", "디스크", "로딩", "저장장치", "100퍼")) {
            return List.of("storage", "disk", "queue", "loading", "ssd");
        }
        if (containsAny(token, "재부팅", "전원", "꺼짐", "파워", "psu", "다운")) {
            return List.of("power", "psu", "connector", "transient", "전력");
        }
        return List.of();
    }

    private static boolean containsAny(String token, String... needles) {
        for (String needle : needles) {
            if (token.contains(needle)) {
                return true;
            }
        }
        return false;
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

    private static double score(Map<String, Object> row) {
        Object score = row.get("score");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        if (score == null) {
            return 0.5;
        }
        return Double.parseDouble(score.toString());
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static double rounded(double value) {
        return BigDecimal.valueOf(value).setScale(5, RoundingMode.HALF_UP).doubleValue();
    }

    private record RootContext(String queryText) {
    }

    private record RetrievalCandidate(boolean allowed, double rank, String sourceType, AgentRagEvidenceDraft draft) {
    }
}
