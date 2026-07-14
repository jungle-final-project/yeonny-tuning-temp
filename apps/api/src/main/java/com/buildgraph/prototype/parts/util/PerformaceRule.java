package com.buildgraph.prototype.parts.util;

import static com.buildgraph.prototype.parts.util.RuleValueReader.decimalValue;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.parts.benchmark.BenchmarkQueryCached;
import com.buildgraph.prototype.parts.tool.ToolBuildPart;

import lombok.RequiredArgsConstructor;

/* 퍼포먼스 산출하는 util 함수들 */
@Component
@RequiredArgsConstructor
public class PerformaceRule {

    private final BenchmarkQueryCached benchmarkQuery;
 
    /* 최신 벤치마크 가져오기: DB 접근 */
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
        /* Query 호출 */
        return benchmarkQuery.latestBenchmarkInfos(partIds);
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
