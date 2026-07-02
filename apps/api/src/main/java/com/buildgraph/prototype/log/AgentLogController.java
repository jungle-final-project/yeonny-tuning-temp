package com.buildgraph.prototype.log;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class AgentLogController {
    private final AgentLogQueryService agentLogQueryService;
    private final CurrentUserService currentUserService;

    public AgentLogController(AgentLogQueryService agentLogQueryService, CurrentUserService currentUserService) {
        this.agentLogQueryService = agentLogQueryService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/agent-logs/upload")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> upload(
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) Integer rangeMinutes,
            @RequestParam(required = false) Boolean consentAccepted,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return agentLogQueryService.upload(file, rangeMinutes, consentAccepted, user);
    }

    @GetMapping("/agent-logs/{id}")
    Map<String, Object> log(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return agentLogQueryService.detail(id, user);
    }
}
