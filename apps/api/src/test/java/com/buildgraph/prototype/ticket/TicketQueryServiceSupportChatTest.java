package com.buildgraph.prototype.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class TicketQueryServiceSupportChatTest {
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1004L,
            "00000000-0000-4000-8000-000000001004",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TicketQueryService service = new TicketQueryService(jdbcTemplate);

    @Test
    void createTicketEnsuresSupportChatRoomWithSystemMessage() {
        mockLockedUser();
        mockNoOpenSupportChat();
        mockTicketInsert("GPU temperature rises quickly.", 6001L, "ticket-public-id");
        mockSupportChatRoomInsert(6001L, 7001L, "room-public-id");
        mockTicketLookup("ticket-public-id", "GPU temperature rises quickly.", "room-public-id");

        Map<String, Object> result = service.create(Map.of("symptom", "GPU temperature rises quickly."), USER);

        assertThat(result).containsEntry("id", "ticket-public-id");
        assertThat(result).containsEntry("supportChatRoomId", "room-public-id");
        verify(jdbcTemplate).queryForList(contains("FOR UPDATE"), eq(USER.internalId()));
        verify(jdbcTemplate).queryForList(contains("t.status NOT IN ('CLOSED', 'CANCELLED')"), eq(USER.internalId()));
        verify(jdbcTemplate).queryForList(
                contains("INSERT INTO support_chat_rooms"),
                eq(USER.internalId()),
                eq(6001L),
                anyString(),
                eq(SupportChatService.SYSTEM_OPEN_MESSAGE)
        );
        verify(jdbcTemplate).update(
                contains("INSERT INTO support_chat_messages"),
                eq(7001L),
                eq(SupportChatService.SYSTEM_OPEN_MESSAGE)
        );
    }

    @Test
    void createTicketRejectsWhenUserAlreadyHasOpenSupportChat() {
        mockLockedUser();
        when(jdbcTemplate.queryForList(contains("t.status NOT IN ('CLOSED', 'CANCELLED')"), eq(USER.internalId())))
                .thenReturn(List.of(Map.of(
                        "as_ticket_id", "00000000-0000-4000-8000-000000006001",
                        "support_chat_room_id", "00000000-0000-4000-8000-000000009001"
                )));

        assertThatThrownBy(() -> service.create(Map.of("symptom", "new issue"), USER))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException exception = (ApiException) error;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("CONFLICT_STATE");
                    assertThat(exception.details()).containsEntry("asTicketId", "00000000-0000-4000-8000-000000006001");
                    assertThat(exception.details()).containsEntry("supportChatRoomId", "00000000-0000-4000-8000-000000009001");
                });

        verify(jdbcTemplate).queryForList(contains("FOR UPDATE"), eq(USER.internalId()));
        verify(jdbcTemplate, never()).queryForMap(
                contains("INSERT INTO as_tickets"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
        verify(jdbcTemplate, never()).queryForList(
                contains("INSERT INTO support_chat_rooms"),
                any(), any(), any(), any()
        );
        verify(jdbcTemplate, never()).update(contains("INSERT INTO support_chat_messages"), any(), any());
    }

    @Test
    void createTicketAllowsNewTicketWhenExistingSupportChatTicketIsClosedOrCancelled() {
        mockLockedUser();
        mockNoOpenSupportChat();
        mockTicketInsert("new issue after closed chat", 6002L, "new-ticket-public-id");
        mockSupportChatRoomInsert(6002L, 7002L, "new-room-public-id");
        mockTicketLookup("new-ticket-public-id", "new issue after closed chat", "new-room-public-id");

        Map<String, Object> result = service.create(Map.of("symptom", "new issue after closed chat"), USER);

        assertThat(result).containsEntry("id", "new-ticket-public-id");
        verify(jdbcTemplate).queryForList(contains("r.status = 'ACTIVE'"), eq(USER.internalId()));
        verify(jdbcTemplate).queryForList(contains("t.status NOT IN ('CLOSED', 'CANCELLED')"), eq(USER.internalId()));
        verifyTicketInsert("new issue after closed chat");
    }

    private void mockLockedUser() {
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq(USER.internalId())))
                .thenReturn(List.of(Map.of("id", USER.internalId())));
    }

    private void mockNoOpenSupportChat() {
        when(jdbcTemplate.queryForList(contains("t.status NOT IN ('CLOSED', 'CANCELLED')"), eq(USER.internalId())))
                .thenReturn(List.of());
    }

    private void mockTicketInsert(String symptom, long internalId, String publicId) {
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO as_tickets"),
                any(),
                eq(USER.internalId()),
                isNull(),
                eq(symptom),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(Map.of(
                "id", publicId,
                "internal_id", internalId
        ));
    }

    private void mockSupportChatRoomInsert(long ticketInternalId, long roomInternalId, String roomPublicId) {
        when(jdbcTemplate.queryForList(
                contains("INSERT INTO support_chat_rooms"),
                eq(USER.internalId()),
                eq(ticketInternalId),
                anyString(),
                eq(SupportChatService.SYSTEM_OPEN_MESSAGE)
        )).thenReturn(List.of(Map.of(
                "internal_id", roomInternalId,
                "id", roomPublicId
        )));
    }

    private void mockTicketLookup(String ticketPublicId, String symptom, String roomPublicId) {
        when(jdbcTemplate.queryForList(anyString(), eq(ticketPublicId), eq(USER.internalId())))
                .thenReturn(List.of(Map.of(
                        "id", ticketPublicId,
                        "user_id", USER.id(),
                        "status", "OPEN",
                        "symptom", symptom,
                        "cause_candidates", "[]",
                        "upgrade_candidates", "[]",
                        "support_chat_room_id", roomPublicId
                )));
    }

    private void verifyTicketInsert(String symptom) {
        verify(jdbcTemplate).queryForMap(
                contains("INSERT INTO as_tickets"),
                any(),
                eq(USER.internalId()),
                isNull(),
                eq(symptom),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }
}
