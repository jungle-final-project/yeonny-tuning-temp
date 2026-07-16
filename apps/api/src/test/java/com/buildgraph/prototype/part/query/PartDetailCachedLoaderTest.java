package com.buildgraph.prototype.part.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;

class PartDetailCachedLoaderTest {

    private static final String CPU_ID = "10000000-0000-4000-8000-000000000001";
    private static final String GPU_ID = "10000000-0000-4000-8000-000000000002";

    @Test
    void cacheMissesUseOneBatchQueryAndHitsRestoreRequestedOrder() {
        PartQuery partQuery = mock(PartQuery.class);
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder().maximumSize(100));
        PartDetailCachedLoader loader = new PartDetailCachedLoader(cacheManager, partQuery);

        when(partQuery.findAllByPublicIds(anyList())).thenReturn(List.of(
                detail(2L, GPU_ID, "GPU"),
                detail(1L, CPU_ID, "CPU")
        ));

        List<PartDetailDto> first = loader.detailsByPublicIds(List.of(CPU_ID, GPU_ID));
        List<PartDetailDto> second = loader.detailsByPublicIds(List.of(GPU_ID, CPU_ID));

        assertThat(first).extracting(detail -> detail.part().publicId()).containsExactly(CPU_ID, GPU_ID);
        assertThat(second).extracting(detail -> detail.part().publicId()).containsExactly(GPU_ID, CPU_ID);
        assertThat(loader.dbQueryCount()).isEqualTo(1);
        verify(partQuery, times(1)).findAllByPublicIds(anyList());
    }

    private static PartDetailDto detail(long internalId, String publicId, String category) {
        return new PartDetailDto(
                new ToolBuildPart(
                        internalId,
                        publicId,
                        category,
                        category + " part",
                        "BuildGraph",
                        100_000,
                        Map.of(),
                        1
                ),
                "ACTIVE",
                Map.of("summary", category + " benchmark", "score", 80),
                null,
                null
        );
    }
}