package com.buildgraph.prototype.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class SupportChatServiceTest {
    private static final String ROOM_ID = "00000000-0000-4000-8000-000000009001";
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1004L,
            "00000000-0000-4000-8000-000000001004",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );
    private static final CurrentUserService.CurrentUser ADMIN = new CurrentUserService.CurrentUser(
            2L,
            "00000000-0000-4000-8000-000000000001",
            "admin@example.com",
            "BuildGraph Admin",
            "ADMIN",
            null
    );

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final SupportChatService service = new SupportChatService(jdbcTemplate);

    @Test
    void postUserMessageRejectsNonStringContent() {
        assertThatThrownBy(() -> service.postUserMessage(ROOM_ID, Map.of("content", 123), USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(jdbcTemplate, never()).update(contains("INSERT INTO support_chat_messages"), any(), any(), any(), any());
    }

    @Test
    void postUserMessageRejectsBlankNullLiteralAndTooLongContent() {
        assertBadRequest(Map.of("content", "   "));
        assertBadRequest(Map.of("content", "null"));
        assertBadRequest(Map.of("content", "x".repeat(2001)));

        verify(jdbcTemplate, never()).update(contains("INSERT INTO support_chat_messages"), any(), any(), any(), any());
    }

    @Test
    void postUserMessageRejectsTerminalTicketWithoutInsertingMessage() {
        mockRoom("ACTIVE", "CLOSED");

        assertThatThrownBy(() -> service.postUserMessage(ROOM_ID, Map.of("content", "아직 확인할 내용이 있습니다."), USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(jdbcTemplate, never()).update(contains("INSERT INTO support_chat_messages"), any(), any(), any(), any());
    }

    @Test
    void postUserMessageRejectsInactiveRoomWithoutInsertingMessage() {
        mockRoom("ARCHIVED", "OPEN");

        assertThatThrownBy(() -> service.postUserMessage(ROOM_ID, Map.of("content", "아직 확인할 내용이 있습니다."), USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(jdbcTemplate, never()).update(contains("INSERT INTO support_chat_messages"), any(), any(), any(), any());
    }

    @Test
    void userDetailSnapshotDoesNotClearUnreadCount() {
        mockRoom("ACTIVE", "OPEN", 3, 0);
        mockMessages();

        Map<String, Object> detail = service.detailSnapshot(ROOM_ID, USER);

        assertThat((Map<String, Object>) detail.get("contact")).containsEntry("userUnreadCount", 3);
        verify(jdbcTemplate, never()).update(contains("user_unread_count = 0"), eq(7001L));
    }

    @Test
    void adminDetailSnapshotDoesNotClearUnreadCount() {
        mockAdminRoom("ACTIVE", "OPEN", 0, 4);
        mockMessages();

        Map<String, Object> detail = service.adminDetailSnapshot(ROOM_ID, ADMIN);

        assertThat((Map<String, Object>) detail.get("contact")).containsEntry("adminUnreadCount", 4);
        verify(jdbcTemplate, never()).update(contains("admin_unread_count = 0"), eq(7001L));
    }

    @Test
    void closedTicketChatHistoryRemainsReadableWithoutActiveRoomFilter() {
        mockRoom("ARCHIVED", "CLOSED", 2, 0);
        mockMessages();

        Map<String, Object> detail = service.detailSnapshot(ROOM_ID, USER);

        assertThat((Map<String, Object>) detail.get("contact"))
                .containsEntry("status", "ARCHIVED")
                .containsEntry("ticketStatus", "CLOSED")
                .containsEntry("canSendMessage", false);
    }

    @Test
    void currentIgnoresArchivedRoomsSoSupportNewCanProceed() {
        when(jdbcTemplate.queryForList(contains("r.status = 'ACTIVE'"), eq(USER.internalId())))
                .thenReturn(List.of());

        Map<String, Object> detail = service.current(USER, null);

        assertThat(detail).containsEntry("contact", null);
        assertThat((List<Map<String, Object>>) detail.get("messages")).isEmpty();
    }

    @Test
    void currentDoesNotRecreateRoomForCancelledTicket() {
        when(jdbcTemplate.queryForList(
                contains("FROM as_tickets"),
                eq("00000000-0000-4000-8000-000000006001"),
                eq(USER.internalId())
        )).thenReturn(List.of(Map.of(
                "internal_id", 6001L,
                "id", "00000000-0000-4000-8000-000000006001",
                "symptom", "검은 화면",
                "status", "CANCELLED"
        )));

        Map<String, Object> detail = service.current(
                USER,
                "00000000-0000-4000-8000-000000006001"
        );

        assertThat(detail).containsEntry("contact", null);
        assertThat((List<Map<String, Object>>) detail.get("messages")).isEmpty();
        verify(jdbcTemplate, never()).update(contains("INSERT INTO support_chat_rooms"), any(), any());
    }

    @Test
    void currentSummarySkipsHeavyMessageAndVisitReservationQueries() {
        // findLatestUserRoom → 활성 방 1개
        when(jdbcTemplate.queryForList(contains("r.status = 'ACTIVE'"), eq(USER.internalId())))
                .thenReturn(List.of(Map.of("id", ROOM_ID)));
        // roomForUser → 룸 1행(배지 필드 포함)
        mockRoom("ACTIVE", "OPEN", 5, 0);

        Map<String, Object> summary = service.currentSummary(USER, null);

        // 계약: 경량 요약(summary=true), 느슨한 폴링(30s), messages 미포함
        assertThat(summary)
                .containsEntry("summary", true)
                .containsEntry("pollingIntervalMs", 30000)
                .containsEntry("messages", null);
        assertThat((Map<String, Object>) summary.get("contact"))
                .containsEntry("id", ROOM_ID)
                .containsEntry("userUnreadCount", 5)
                .containsEntry("lastMessagePreview", "최근 메시지")
                .containsEntry("canSendMessage", true);
        // 핵심: 무거운 쿼리 미호출 — messages(방ID,100)·visitReservation
        verify(jdbcTemplate, never()).queryForList(anyString(), eq(7001L), eq(100));
        verify(jdbcTemplate, never()).queryForList(contains("latest_visit_reservation"), eq(6001L));
    }

    @Test
    void currentSummaryReturnsEmptyWhenNoActiveRoom() {
        when(jdbcTemplate.queryForList(contains("r.status = 'ACTIVE'"), eq(USER.internalId())))
                .thenReturn(List.of());

        Map<String, Object> summary = service.currentSummary(USER, null);

        assertThat(summary)
                .containsEntry("contact", null)
                .containsEntry("summary", true)
                .containsEntry("pollingIntervalMs", 30000);
        verify(jdbcTemplate, never()).queryForList(anyString(), eq(7001L), eq(100));
    }

    @Test
    void detailIncludesLatestVisitReservation() {
        mockRoom("ACTIVE", "OPEN", 0, 0);
        mockMessages();
        mockVisitReservation("SCHEDULED");

        Map<String, Object> detail = service.detailSnapshot(ROOM_ID, USER);

        Map<String, Object> contact = (Map<String, Object>) detail.get("contact");
        assertThat((Map<String, Object>) contact.get("visitReservation"))
                .containsEntry("id", "00000000-0000-4000-8000-000000008001")
                .containsEntry("status", "SCHEDULED")
                .containsEntry("scheduledAt", "2099-07-10T14:30+09:00");
    }

    @Test
    void adminQueueContactSnapshotReturnsContactWhenRoomBelongsInAdminList() {
        mockAdminRoom("ACTIVE", "OPEN", 0, 4);
        mockVisitReservation("REQUESTED");

        Optional<Map<String, Object>> contact = service.adminQueueContactSnapshot(ROOM_ID);

        assertThat(contact).isPresent();
        assertThat(contact.orElseThrow())
                .containsEntry("id", ROOM_ID)
                .containsEntry("adminUnreadCount", 4)
                .containsEntry("canSendMessage", true);
        assertThat((Map<String, Object>) contact.orElseThrow().get("visitReservation"))
                .containsEntry("status", "REQUESTED");
    }

    @Test
    void adminQueueContactSnapshotIsEmptyWhenRoomIsNotInAdminList() {
        when(jdbcTemplate.queryForList(anyString(), eq(ROOM_ID))).thenReturn(List.of());

        Optional<Map<String, Object>> contact = service.adminQueueContactSnapshot(ROOM_ID);

        assertThat(contact).isEmpty();
    }

    @Test
    void deleteAdminSessionArchivesActiveRoomCancelsTicketAndWritesSystemMessage() {
        mockAdminRoomForUpdate("ACTIVE", "OPEN", 0, 4);
        mockMessages();

        Map<String, Object> detail = service.deleteAdminSession(ROOM_ID, ADMIN);

        Map<String, Object> contact = (Map<String, Object>) detail.get("contact");
        assertThat(contact)
                .containsEntry("status", "ARCHIVED")
                .containsEntry("ticketStatus", "CANCELLED")
                .containsEntry("canSendMessage", false);
        verify(jdbcTemplate).update(
                contains("INSERT INTO support_chat_messages"),
                eq(7001L),
                eq("SYSTEM"),
                eq(SupportChatService.SYSTEM_DELETE_MESSAGE),
                eq(null)
        );
        verify(jdbcTemplate).update(
                contains("UPDATE support_chat_rooms"),
                eq("ARCHIVED"),
                eq(SupportChatService.SYSTEM_DELETE_MESSAGE),
                eq(7001L)
        );
        verify(jdbcTemplate).update(
                contains("UPDATE as_tickets"),
                eq("CANCELLED"),
                eq(6001L)
        );
        verify(jdbcTemplate).update(
                contains("UPDATE remote_support_sessions"),
                eq(6001L)
        );
    }

    @Test
    void deleteAdminSessionCancelsResolvedTicketByExplicitDeleteException() {
        mockAdminRoomForUpdate("ACTIVE", "RESOLVED", 0, 4);
        mockMessages();

        Map<String, Object> detail = service.deleteAdminSession(ROOM_ID, ADMIN);

        Map<String, Object> contact = (Map<String, Object>) detail.get("contact");
        assertThat(contact)
                .containsEntry("status", "ARCHIVED")
                .containsEntry("ticketStatus", "CANCELLED");
        verify(jdbcTemplate).update(
                contains("UPDATE as_tickets"),
                eq("CANCELLED"),
                eq(6001L)
        );
        verify(jdbcTemplate).update(
                contains("UPDATE remote_support_sessions"),
                eq(6001L)
        );
    }

    @Test
    void deleteAdminSessionKeepsTerminalTicketStatusAndArchivesRoom() {
        mockAdminRoomForUpdate("ACTIVE", "CLOSED", 0, 4);
        mockMessages();

        Map<String, Object> detail = service.deleteAdminSession(ROOM_ID, ADMIN);

        Map<String, Object> contact = (Map<String, Object>) detail.get("contact");
        assertThat(contact)
                .containsEntry("status", "ARCHIVED")
                .containsEntry("ticketStatus", "CLOSED")
                .containsEntry("canSendMessage", false);
        verify(jdbcTemplate, never()).update(contains("UPDATE as_tickets"), any(), any());
    }

    @Test
    void deleteAdminSessionIsIdempotentWhenRoomAlreadyArchived() {
        mockAdminRoomForUpdate("ARCHIVED", "CANCELLED", 1, 4);
        mockMessages();

        Map<String, Object> detail = service.deleteAdminSession(ROOM_ID, ADMIN);

        Map<String, Object> contact = (Map<String, Object>) detail.get("contact");
        assertThat(contact)
                .containsEntry("status", "ARCHIVED")
                .containsEntry("ticketStatus", "CANCELLED");
        verify(jdbcTemplate, never()).update(contains("INSERT INTO support_chat_messages"), any(), any(), any(), any());
        verify(jdbcTemplate, never()).update(contains("UPDATE support_chat_rooms"), any(), any(), any());
        verify(jdbcTemplate, never()).update(contains("UPDATE as_tickets"), any(), any());
    }

    private void assertBadRequest(Map<String, Object> request) {
        assertThatThrownBy(() -> service.postUserMessage(ROOM_ID, request, USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private void mockRoom(String roomStatus, String ticketStatus) {
        mockRoom(roomStatus, ticketStatus, 0, 0);
    }

    private void mockRoom(String roomStatus, String ticketStatus, int userUnreadCount, int adminUnreadCount) {
        when(jdbcTemplate.queryForList(anyString(), eq(ROOM_ID), eq(USER.internalId())))
                .thenReturn(List.of(roomRow(roomStatus, ticketStatus, userUnreadCount, adminUnreadCount)));
    }

    private void mockAdminRoom(String roomStatus, String ticketStatus, int userUnreadCount, int adminUnreadCount) {
        when(jdbcTemplate.queryForList(anyString(), eq(ROOM_ID)))
                .thenReturn(List.of(roomRow(roomStatus, ticketStatus, userUnreadCount, adminUnreadCount)));
    }

    private void mockAdminRoomForUpdate(String roomStatus, String ticketStatus, int userUnreadCount, int adminUnreadCount) {
        when(jdbcTemplate.queryForList(contains("FOR UPDATE OF r, t"), eq(ROOM_ID)))
                .thenReturn(List.of(roomRow(roomStatus, ticketStatus, userUnreadCount, adminUnreadCount)));
    }

    private void mockMessages() {
        when(jdbcTemplate.queryForList(anyString(), eq(7001L), eq(100)))
                .thenReturn(List.of());
    }

    private void mockVisitReservation(String status) {
        when(jdbcTemplate.queryForList(contains("latest_visit_reservation"), eq(6001L)))
                .thenReturn(List.of(Map.ofEntries(
                        Map.entry("id", "00000000-0000-4000-8000-000000008001"),
                        Map.entry("status", status),
                        Map.entry("scheduled_at", java.time.OffsetDateTime.parse("2099-07-10T14:30:00+09:00")),
                        Map.entry("address_snapshot", "서울시 강남구"),
                        Map.entry("technician_note", "방문 전 연락"),
                        Map.entry("created_at", java.time.OffsetDateTime.parse("2099-07-01T00:00:00+09:00")),
                        Map.entry("updated_at", java.time.OffsetDateTime.parse("2099-07-02T00:00:00+09:00"))
                )));
    }

    private Map<String, Object> roomRow(String roomStatus, String ticketStatus, int userUnreadCount, int adminUnreadCount) {
        return Map.ofEntries(
                Map.entry("internal_id", 7001L),
                Map.entry("id", ROOM_ID),
                Map.entry("ticket_internal_id", 6001L),
                Map.entry("as_ticket_id", "00000000-0000-4000-8000-000000006001"),
                Map.entry("ticket_status", ticketStatus),
                Map.entry("ticket_symptom", "GPU 온도 상승"),
                Map.entry("status", roomStatus),
                Map.entry("title", "AS 상담방"),
                Map.entry("last_message_preview", "최근 메시지"),
                Map.entry("user_unread_count", userUnreadCount),
                Map.entry("admin_unread_count", adminUnreadCount),
                Map.entry("user_id", USER.id()),
                Map.entry("user_email", USER.email()),
                Map.entry("user_name", USER.name())
        );
    }
}
