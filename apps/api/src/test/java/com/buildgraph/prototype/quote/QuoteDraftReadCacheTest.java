package com.buildgraph.prototype.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class QuoteDraftReadCacheTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @Test
    void signatureSortsItemsByInternalPartIdAndDistinguishesEmptyStates() {
        QuoteDraftReadCache cache = new QuoteDraftReadCache(jdbcTemplate);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(
                        lightRow(7L, 302L, "gpu-uuid", 1),
                        lightRow(7L, 201L, "cpu-uuid", 2)
                ))
                .thenReturn(List.of(lightRowWithoutItem(9L)))
                .thenReturn(List.of());

        assertThat(cache.signature(1004L)).isEqualTo("7:201x2;302x1;");
        // draft는 있으나 항목이 없는 상태(part NULL 행)와 draft 자체가 없는 상태를 구분한다.
        assertThat(cache.signature(1004L)).isEqualTo("9:");
        assertThat(cache.signature(1004L)).isEqualTo("no-draft");
    }

    @Test
    void cachesLightDraftUntilInvalidatedWhenTtlEnabled() {
        QuoteDraftReadCache cache = new QuoteDraftReadCache(jdbcTemplate, null, 60L);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(lightRow(7L, 201L, "cpu-uuid", 1)));

        assertThat(cache.signature(1004L)).isEqualTo("7:201x1;");
        assertThat(cache.signature(1004L)).isEqualTo("7:201x1;");
        verify(jdbcTemplate, times(1)).queryForList(anyString(), any(Object[].class));

        cache.invalidate(1004L);
        assertThat(cache.signature(1004L)).isEqualTo("7:201x1;");
        verify(jdbcTemplate, times(2)).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void responseCacheReusesLoaderResultAndInvalidateClearsIt() {
        QuoteDraftReadCache cache = new QuoteDraftReadCache(jdbcTemplate, null, 60L);
        int[] loads = {0};

        Map<String, Object> first = cache.response(1004L, () -> {
            loads[0] += 1;
            return MockData.map("status", "ACTIVE", "load", loads[0]);
        });
        Map<String, Object> second = cache.response(1004L, () -> {
            loads[0] += 1;
            return MockData.map("status", "ACTIVE", "load", loads[0]);
        });
        assertThat(first).isSameAs(second);
        assertThat(loads[0]).isEqualTo(1);

        cache.invalidate(1004L);
        Map<String, Object> third = cache.response(1004L, () -> {
            loads[0] += 1;
            return MockData.map("status", "ACTIVE", "load", loads[0]);
        });
        assertThat(third.get("load")).isEqualTo(2);
    }

    private static Map<String, Object> lightRow(long draftId, long partInternalId, String partPublicId, int quantity) {
        return MockData.map(
                "draft_internal_id", draftId,
                "part_internal_id", partInternalId,
                "part_public_id", partPublicId,
                "quantity", quantity
        );
    }

    private static Map<String, Object> lightRowWithoutItem(long draftId) {
        return MockData.map(
                "draft_internal_id", draftId,
                "part_internal_id", null,
                "part_public_id", null,
                "quantity", null
        );
    }
}
