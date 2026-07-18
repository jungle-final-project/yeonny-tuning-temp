package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me/agent-diagnosis-requests")
public class PcAgentDiagnosisRequestController {
    private final CurrentUserService currentUserService;
    private final PcAgentDiagnosisRequestService service;
    private final PcAgentDiagnosisQueryService queryService;

    public PcAgentDiagnosisRequestController(
            CurrentUserService currentUserService,
            PcAgentDiagnosisRequestService service,
            PcAgentDiagnosisQueryService queryService
    ) {
        this.currentUserService = currentUserService;
        this.service = service;
        this.queryService = queryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody PcAgentDiagnosisRequestService.CreateRequest body
    ) {
        return service.create(currentUserService.requireUser(authorization), body);
    }

    @GetMapping("/{diagnosisId}")
    public Map<String, Object> get(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String diagnosisId
    ) {
        return queryService.get(currentUserService.requireUser(authorization), diagnosisId);
    }

    @GetMapping("/latest")
    public Map<String, Object> latest(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return queryService.latest(currentUserService.requireUser(authorization));
    }
}
