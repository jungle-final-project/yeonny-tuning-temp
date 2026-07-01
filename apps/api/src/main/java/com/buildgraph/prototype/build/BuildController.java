package com.buildgraph.prototype.build;

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
@RequestMapping("/api")
public class BuildController {
    private final BuildQueryService buildQueryService;
    private final BuildChatService buildChatService;
    private final CurrentUserService currentUserService;

    public BuildController(BuildQueryService buildQueryService, BuildChatService buildChatService, CurrentUserService currentUserService) {
        this.buildQueryService = buildQueryService;
        this.buildChatService = buildChatService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/requirements/parse")
    Map<String, Object> parse(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return buildQueryService.parse(request);
    }

    @PostMapping("/builds/recommend")
    Map<String, Object> recommend(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return buildQueryService.recommendations(request == null ? Map.of() : request);
    }

    @GetMapping("/builds/{id}")
    Map<String, Object> build(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return buildQueryService.buildDetail(id);
    }

    @GetMapping("/builds/history")
    Map<String, Object> history(@RequestHeader(value = "Authorization", required = false) String authorization) {
        currentUserService.requireUser(authorization);
        return Map.of("items", buildQueryService.builds());
    }

    @PostMapping("/builds/{id}/change-part")
    Map<String, Object> changePart(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return buildQueryService.changePart(id, request == null ? Map.of() : request);
    }

    @PostMapping("/ai/build-chat")
    Map<String, Object> buildChat(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-BuildGraph-AI-Profile", required = false) String aiProfile
    ) {
        currentUserService.requireUser(authorization);
        Map<String, Object> body = request == null ? Map.of() : request;
        if (aiProfile == null || aiProfile.isBlank()) {
            return buildChatService.chat(body);
        }
        return buildChatService.chat(body, aiProfile);
    }
}
