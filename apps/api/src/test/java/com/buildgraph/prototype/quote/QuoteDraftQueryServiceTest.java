package com.buildgraph.prototype.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

class QuoteDraftQueryServiceTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    // TransactionTemplate은 mock PTM 위에서 콜백을 그대로 실행한다(getTransaction/commit은 no-op).
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final QuoteDraftQueryService quoteDraftQueryService =
            new QuoteDraftQueryService(jdbcTemplate, currentUserService, transactionManager, new QuoteDraftReadCache(jdbcTemplate));

    @Test
    void applyAiBuildFailsBeforeDraftMutationWhenPartIdIsInvalid() {
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(currentUser());
        when(jdbcTemplate.queryForList(anyString(), eq("missing-part-id"))).thenReturn(List.of());

        assertThatThrownBy(() -> quoteDraftQueryService.applyAiBuild(USER_TOKEN, Map.of(
                "conflictPolicy", "REPLACE",
                "items", List.of(Map.of(
                        "partId", "missing-part-id",
                        "category", "GPU",
                        "quantity", 1
                ))
        )))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(jdbcTemplate, never()).queryForList(anyString(), eq(1004L));
    }

    @Test
    void applyAiBuildReplacesWholeDraftAndReturnsQuoteDraft() {
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(currentUser());
        when(jdbcTemplate.queryForList(anyString(), eq("part-cpu-ai"))).thenReturn(List.of(part("part-cpu-ai", 101L, "CPU", "Ryzen AI CPU", 420000)));
        when(jdbcTemplate.queryForList(anyString(), eq("part-gpu-ai"))).thenReturn(List.of(part("part-gpu-ai", 201L, "GPU", "RTX AI GPU", 890000)));
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()), List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftItem("draft-item-cpu", "part-cpu-ai", "CPU", "Ryzen AI CPU", 420000),
                draftItem("draft-item-gpu", "part-gpu-ai", "GPU", "RTX AI GPU", 890000)
        ));
        when(jdbcTemplate.queryForList(argThat((String sql) -> sql != null && sql.contains("RETURNING")), eq(700L)))
                .thenReturn(List.of(activeDraft()));

        Map<String, Object> draft = quoteDraftQueryService.applyAiBuild(USER_TOKEN, Map.of(
                "buildId", "ai-2000000-balanced",
                "conflictPolicy", "REPLACE",
                "items", List.of(
                        Map.of("partId", "part-cpu-ai", "category", "CPU", "quantity", 1),
                        Map.of("partId", "part-gpu-ai", "category", "GPU", "quantity", 1)
                )
        ));

        assertThat(draft.get("status")).isEqualTo("ACTIVE");
        assertThat(draft.get("totalPrice")).isEqualTo(1_310_000);
        assertThat(draft.get("itemCount")).isEqualTo(2);
        assertThat((List<?>) draft.get("items")).hasSize(2);
    }

    @Test
    void putItemUpsertsSameCategoryWithSingleAtomicStatementAndConvergesToLastPart() {
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(currentUser());
        when(jdbcTemplate.queryForList(anyString(), eq("part-cpu-a"))).thenReturn(List.of(part("part-cpu-a", 101L, "CPU", "CPU A", 400000)));
        when(jdbcTemplate.queryForList(anyString(), eq("part-cpu-b"))).thenReturn(List.of(part("part-cpu-b", 102L, "CPU", "CPU B", 450000)));
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftItem("draft-item-cpu", "part-cpu-b", "CPU", "CPU B", 450000)
        ));
        when(jdbcTemplate.queryForList(argThat((String sql) -> sql != null && sql.contains("RETURNING")), eq(700L)))
                .thenReturn(List.of(activeDraft()));

        quoteDraftQueryService.putItem(USER_TOKEN, "part-cpu-a", Map.of());
        Map<String, Object> draft = quoteDraftQueryService.putItem(USER_TOKEN, "part-cpu-b", Map.of());

        // UPDATE 0행→INSERT 수동 2단 업서트가 아니라 단일 ON CONFLICT 문이어야
        // 동시 PUT 2건이 둘 다 INSERT로 가서 23505(→500)로 터질 창이 없다.
        verify(jdbcTemplate).update(
                argThat((String sql) -> sql.contains("ON CONFLICT (quote_draft_id, category)") && sql.contains("DO UPDATE")),
                eq(700L), eq(101L), eq("CPU"), eq(1), eq(400000));
        verify(jdbcTemplate).update(
                argThat((String sql) -> sql.contains("ON CONFLICT (quote_draft_id, category)") && sql.contains("DO UPDATE")),
                eq(700L), eq(102L), eq("CPU"), eq(1), eq(450000));
        List<?> items = (List<?>) draft.get("items");
        assertThat(items).hasSize(1);
        assertThat(((Map<?, ?>) items.get(0)).get("partId")).isEqualTo("part-cpu-b");
    }

    @Test
    void putItemJoinsWinnerDraftWhenConcurrentCreateLosesInsertRace() {
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(currentUser());
        when(jdbcTemplate.queryForList(anyString(), eq("part-cpu-a"))).thenReturn(List.of(part("part-cpu-a", 101L, "CPU", "CPU A", 400000)));
        // 1) 활성 드래프트 없음 → 2) ON CONFLICT DO NOTHING이 빈 결과(경쟁 패배) → 3) 재조회로 승자 드래프트 합류
        when(jdbcTemplate.queryForList(anyString(), eq(1004L)))
                .thenReturn(List.of(), List.of(activeDraft()), List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(1004L))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftItem("draft-item-cpu", "part-cpu-a", "CPU", "CPU A", 400000)
        ));
        when(jdbcTemplate.queryForList(argThat((String sql) -> sql != null && sql.contains("RETURNING")), eq(700L)))
                .thenReturn(List.of(activeDraft()));

        Map<String, Object> draft = quoteDraftQueryService.putItem(USER_TOKEN, "part-cpu-a", Map.of());

        assertThat(draft.get("status")).isEqualTo("ACTIVE");
        // 예외 없이 승자 드래프트(700)에 업서트되어야 한다.
        verify(jdbcTemplate).update(
                argThat((String sql) -> sql.contains("ON CONFLICT (quote_draft_id, category)")),
                eq(700L), eq(101L), eq("CPU"), eq(1), eq(400000));
    }

    @Test
    void writeInvalidatesDraftReadCacheSoNextReadSeesFreshDraft() {
        // TTL을 켠 캐시로 "담기 → 즉시 조회"가 stale 응답을 주지 않는지 본다(캐시 도입의 핵심 리스크).
        QuoteDraftReadCache readCache = new QuoteDraftReadCache(jdbcTemplate, null, 60L);
        QuoteDraftQueryService service =
                new QuoteDraftQueryService(jdbcTemplate, currentUserService, transactionManager, readCache);
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(currentUser());
        when(jdbcTemplate.queryForList(anyString(), eq("part-cpu-a"))).thenReturn(List.of(part("part-cpu-a", 101L, "CPU", "CPU A", 400000)));
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(argThat((String sql) -> sql != null && sql.contains("RETURNING")), eq(700L)))
                .thenReturn(List.of(activeDraft()));
        // 담기 전 조회는 빈 견적, 담기 후 조회는 CPU 1개 — 캐시가 무효화되지 않으면 두 번째 조회가 빈 견적으로 남는다.
        when(jdbcTemplate.queryForList(anyString(), eq(700L)))
                .thenReturn(List.of())
                .thenReturn(List.of(draftItem("draft-item-cpu", "part-cpu-a", "CPU", "CPU A", 400000)));

        assertThat((List<?>) service.current(USER_TOKEN).get("items")).isEmpty();
        service.putItem(USER_TOKEN, "part-cpu-a", Map.of());

        Map<String, Object> afterWrite = service.current(USER_TOKEN);
        assertThat((List<?>) afterWrite.get("items")).hasSize(1);
        assertThat(afterWrite.get("totalPrice")).isEqualTo(400000);
    }

    private CurrentUserService.CurrentUser currentUser() {
        return new CurrentUserService.CurrentUser(
                1004L,
                "00000000-0000-4000-8000-000000001004",
                "user@example.com",
                "Demo User",
                "USER",
                "2026-06-30T00:00:00Z"
        );
    }

    private static Map<String, Object> activeDraft() {
        return Map.of(
                "internal_id", 700L,
                "id", "draft-public-id",
                "status", "ACTIVE",
                "name", "셀프 견적",
                "created_at", "2026-06-30T00:00:00Z",
                "updated_at", "2026-06-30T00:00:00Z"
        );
    }

    private static Map<String, Object> part(String publicId, long internalId, String category, String name, int price) {
        return Map.of(
                "internal_id", internalId,
                "id", publicId,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "price", price,
                "attributes", "{}"
        );
    }

    private static Map<String, Object> draftItem(String rowId, String partId, String category, String name, int price) {
        return MockData.map(
                "id", rowId,
                "part_id", partId,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "current_price", price,
                "attributes", "{}",
                "quantity", 1,
                "unit_price_at_add", price,
                "created_at", "2026-06-30T00:00:00Z",
                "updated_at", "2026-06-30T00:00:00Z"
        );
    }
}
