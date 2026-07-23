package com.buildgraph.prototype.aichat.chat;

import com.buildgraph.prototype.aichat.query.AiChatSessionQuery;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/build-chat/session")
public class AiChatSessionController {
    private final AiChatSessionQuery aiChatSessionQuery;
    private final CurrentUserService currentUserService;

    public AiChatSessionController(
            AiChatSessionQuery aiChatSessionQuery,
            CurrentUserService currentUserService
    ) {
        this.aiChatSessionQuery = aiChatSessionQuery;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/reset")
    Map<String, Object> reset(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        aiChatSessionQuery.resetContext(user.internalId());
        return Map.of("status", "RESET");
    }
}