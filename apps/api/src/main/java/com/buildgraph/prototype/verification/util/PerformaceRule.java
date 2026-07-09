package com.buildgraph.prototype.verification.util;

import static com.buildgraph.prototype.verification.util.RuleValueReader.decimalValue;
import static com.buildgraph.prototype.verification.util.RuleValueReader.numberLong;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.verification.tool.ToolBuildPart;

import lombok.RequiredArgsConstructor;

/* 퍼포먼스 산출하는 util 함수들 */
@Component
@RequiredArgsConstructor
public class PerformaceRule {

    private final JdbcTemplate jdbcTemplate;
 
    /* 최신 벤치마크 가져오기 */
    public Map<Long, Map<String, Object>> latestBenchmarks(List<ToolBuildPart> parts) {
        @SuppressWarnings("null")
        List<Long> partIds = parts.stream()
                .filter(part -> part != null && part.internalId() != null)
                .map(ToolBuildPart::internalId)
                .distinct()
                .toList();
        if (partIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(partIds.size(), "?"));
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        jdbcTemplate.queryForList("""
                        SELECT DISTINCT ON (part_id)
                               part_id,
                               summary,
                               score
                        FROM benchmark_summaries
                        WHERE part_id IN (
                        """ + placeholders + """
                        )
                          AND deleted_at IS NULL
                        ORDER BY part_id, created_at DESC, id DESC
                        """, partIds.toArray())
                .forEach(row -> result.put(numberLong(row.get("part_id")), row));
        return result;
    }

    public Double benchmarkScore(Map<Long, Map<String, Object>> benchmarkRows, ToolBuildPart part) {
        if (part == null || part.internalId() == null) {
            return null;
        }
        Map<String, Object> row = benchmarkRows.get(part.internalId());
        return row == null ? null : decimalValue(row.get("score"));
    }

    /* 추후 이해 필요 */
    public String benchmarkSummary(Map<Long, Map<String, Object>> benchmarkRows, ToolBuildPart part) {
        if (part == null || part.internalId() == null) {
            return null;
        }
        Map<String, Object> row = benchmarkRows.get(part.internalId());
        return row == null ? null : DbValueMapper.string(row, "summary");
    }
}
