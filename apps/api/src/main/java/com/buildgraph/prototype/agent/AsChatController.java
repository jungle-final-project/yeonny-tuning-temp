package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.user.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class AsChatController {
    private final AsChatService asChatService;
    private final CurrentUserService currentUserService;

    public AsChatController(AsChatService asChatService, CurrentUserService currentUserService) {
        this.asChatService = asChatService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/ai/as-chat")
    Map<String, Object> history(
            @RequestParam String asTicketId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return asChatService.history(asTicketId, user);
    }

    @PostMapping("/ai/as-chat")
    Map<String, Object> send(
            @Valid @RequestBody AsChatRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-BuildGraph-AI-Profile", required = false) String aiProfile
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return asChatService.send(request.asTicketId(), request.message(), user, aiProfile);
    }

    @PostMapping(value = "/ai/as-chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(
            @Valid @RequestBody AsChatRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-BuildGraph-AI-Profile", required = false) String aiProfile
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        SseEmitter emitter = new SseEmitter(180_000L);
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> response = asChatService.send(
                        request.asTicketId(),
                        request.message(),
                        user,
                        aiProfile,
                        (eventName, payload) -> sendEvent(emitter, eventName, payload)
                );
                sendEvent(emitter, "DONE", response);
                emitter.complete();
            } catch (Exception error) {
                sendEvent(emitter, "ERROR", Map.of(
                        "message", errorMessage(error),
                        "type", error.getClass().getSimpleName()
                ));
                emitter.complete();
            }
        });
        return emitter;
    }

    private static void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (Exception ignored) {
            // A closed browser stream must not hide the original AS Chat processing result.
        }
    }

    private static String errorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    record AsChatRequest(@NotBlank String asTicketId, @NotBlank String message) {
    }
}
