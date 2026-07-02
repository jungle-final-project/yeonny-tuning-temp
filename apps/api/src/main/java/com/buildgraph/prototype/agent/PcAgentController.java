package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/agent")
public class PcAgentController {
    private final PcAgentAsService pcAgentAsService;

    public PcAgentController(PcAgentAsService pcAgentAsService) {
        this.pcAgentAsService = pcAgentAsService;
    }

    @PostMapping("/devices/register")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> register(@RequestBody(required = false) Map<String, Object> request) {
        return pcAgentAsService.register(request == null ? Map.of() : request);
    }

    @PostMapping("/consents")
    Map<String, Object> consent(
            @AuthenticationPrincipal AgentPrincipal principal,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return pcAgentAsService.saveConsent(principal, request == null ? Map.of() : request, idempotencyKey);
    }

    @PostMapping("/heartbeat")
    Map<String, Object> heartbeat(
            @AuthenticationPrincipal AgentPrincipal principal,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return pcAgentAsService.heartbeat(principal, request == null ? Map.of() : request, idempotencyKey);
    }

    @PostMapping(value = "/log-uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> uploadLogs(
            @AuthenticationPrincipal AgentPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Integer rangeMinutes,
            @RequestParam(required = false) String rangeStartedAt,
            @RequestParam(required = false) String rangeEndedAt,
            @RequestParam(required = false) Integer schemaVersion,
            @RequestParam(required = false) String symptom,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return pcAgentAsService.uploadLogs(
                principal,
                file,
                MockData.map(
                        "rangeMinutes", rangeMinutes,
                        "rangeStartedAt", rangeStartedAt,
                        "rangeEndedAt", rangeEndedAt,
                        "schemaVersion", schemaVersion,
                        "symptom", symptom
                ),
                idempotencyKey
        );
    }
}
