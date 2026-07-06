package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/support/chat-sessions")
public class AdminSupportChatController {
    private final SupportChatService supportChatService;
    private final CurrentUserService currentUserService;
    private final SupportChatWebSocketHandler supportChatWebSocketHandler;
    private final SupportChatWebSocketTicketService supportChatWebSocketTicketService;

    public AdminSupportChatController(
            SupportChatService supportChatService,
            CurrentUserService currentUserService,
            SupportChatWebSocketHandler supportChatWebSocketHandler,
            SupportChatWebSocketTicketService supportChatWebSocketTicketService
    ) {
        this.supportChatService = supportChatService;
        this.currentUserService = currentUserService;
        this.supportChatWebSocketHandler = supportChatWebSocketHandler;
        this.supportChatWebSocketTicketService = supportChatWebSocketTicketService;
    }

    @GetMapping
    Map<String, Object> sessions(@RequestHeader(value = "Authorization", required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return supportChatService.adminList();
    }

    @GetMapping("/{id}")
    Map<String, Object> detail(
            @PathVariable String id,
            @org.springframework.web.bind.annotation.RequestParam(value = "markRead", defaultValue = "true") boolean markRead,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return supportChatService.adminDetail(id, admin, markRead);
    }

    @PostMapping("/{id}/messages")
    Map<String, Object> postMessage(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        Map<String, Object> detail = supportChatService.postAdminMessage(id, request == null ? Map.of() : request, admin);
        supportChatWebSocketHandler.broadcastRoomUpdate(id);
        return detail;
    }

    @PostMapping("/{id}/ws-ticket")
    Map<String, Object> issueWebSocketTicket(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return supportChatWebSocketTicketService.issueAdminTicket(id, admin);
    }
}
