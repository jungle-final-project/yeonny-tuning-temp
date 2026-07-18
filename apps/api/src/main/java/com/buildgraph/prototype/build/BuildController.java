package com.buildgraph.prototype.build;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequestMapping("/api")
public class BuildController {
    private final BuildQueryService buildQueryService;
    private final BuildChatService buildChatService;
    private final CurrentUserService currentUserService;
    private final AiChatAsyncExecutor aiChatAsyncExecutor;

    public BuildController(
            BuildQueryService buildQueryService,
            BuildChatService buildChatService,
            CurrentUserService currentUserService,
            AiChatAsyncExecutor aiChatAsyncExecutor
    ) {
        this.buildQueryService = buildQueryService;
        this.buildChatService = buildChatService;
        this.currentUserService = currentUserService;
        this.aiChatAsyncExecutor = aiChatAsyncExecutor;
    }

    @PostMapping("/requirements/parse")
    Map<String, Object> parse(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return buildQueryService.parse(request, user);
    }

    @PostMapping("/builds/recommend")
    Map<String, Object> recommend(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return buildQueryService.recommendations(request == null ? Map.of() : request, user);
    }

    @GetMapping("/builds/{id}")
    Map<String, Object> build(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return buildQueryService.buildDetail(id, user);
    }

    @GetMapping("/builds/history")
    Map<String, Object> history(@RequestHeader(value = "Authorization", required = false) String authorization) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return Map.of("items", buildQueryService.builds(user));
    }

    @PostMapping("/builds/from-chat")
    Map<String, Object> saveFromChat(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return buildQueryService.saveFromChat(request == null ? Map.of() : request, user);
    }

    @PostMapping("/builds/{id}/change-part")
    Map<String, Object> changePart(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return buildQueryService.changePart(id, request == null ? Map.of() : request, user);
    }

    @PatchMapping("/builds/{id}")
    Map<String, Object> renameBuild(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return buildQueryService.renameBuild(id, request == null ? Map.of() : request, user);
    }

    @PostMapping("/builds/{id}/duplicate")
    Map<String, Object> duplicateBuild(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return buildQueryService.duplicateBuild(id, user);
    }

    @DeleteMapping("/builds/{id}")
    Map<String, Object> deleteBuild(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        buildQueryService.deleteBuild(id, user);
        return Map.of("id", id, "deleted", true);
    }

    @PostMapping("/ai/build-chat")
    DeferredResult<Object> buildChat(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-BuildGraph-AI-Profile", required = false) String aiProfile,
            @RequestHeader(value = "X-BuildGraph-AI-Mode", required = false) String aiMode,
            @RequestHeader(value = "X-BuildGraph-Test-Key", required = false) String testKey
    ) {
        // 인증은 요청(Tomcat) 스레드에서 동기로 처리한다 — 401은 빠르게 반환하고 채팅 풀을 낭비하지 않는다.
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        Map<String, Object> body = request == null ? Map.of() : request;
        boolean withProfile = aiProfile != null && !aiProfile.isBlank();
        boolean withTestMode = (aiMode != null && !aiMode.isBlank()) || (testKey != null && !testKey.isBlank());
        // 채팅 처리는 전용 풀에서 비동기로 — Tomcat 스레드는 즉시 반납돼 로그인 등 빠른 요청과 분리된다.
        return aiChatAsyncExecutor.submit(() -> {
            if (withTestMode) {
                return buildChatService.chat(body, aiProfile, user, aiMode, testKey);
            }
            return withProfile ? buildChatService.chat(body, aiProfile, user) : buildChatService.chat(body, user);
        });
    }

    @GetMapping("/recommendations/home-builds")
    Map<String, Object> homeRecommendedBuilds(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return buildChatService.homeRecommendedBuilds();
    }
}
