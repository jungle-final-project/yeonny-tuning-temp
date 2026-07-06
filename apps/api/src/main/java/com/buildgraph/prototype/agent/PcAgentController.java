package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import java.util.Map;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/agent")
public class PcAgentController {
    private final PcAgentAsService pcAgentAsService;

    public PcAgentController(PcAgentAsService pcAgentAsService) {
        this.pcAgentAsService = pcAgentAsService;
    }

    @PostMapping("/devices/register")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> register(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        if (authorization != null && !authorization.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Register uses activation token only.");
        }
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
            @RequestParam(required = false) String incidentId,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) String symptomType,
            @RequestParam(required = false) String detectedAt,
            @RequestParam(required = false) String incidentStartedAt,
            @RequestParam(required = false) String incidentEndedAt,
            @RequestParam(required = false) String lastNormalBootAt,
            @RequestParam(required = false) String startedAt,
            @RequestParam(required = false) String endedAt,
            @RequestParam(required = false) Boolean selectedByUser,
            @RequestParam(required = false) String consentId,
            @RequestParam(required = false) String symptom,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return pcAgentAsService.uploadLogs(
                principal,
                file,
                MockData.map(
                        "rangeMinutes", rangeMinutes,
                        "rangeStartedAt", rangeStartedAt,
                        "rangeEndedAt", rangeEndedAt,
                        "schemaVersion", schemaVersion,
                        "incidentId", incidentId,
                        "triggerType", triggerType,
                        "symptomType", symptomType,
                        "detectedAt", detectedAt,
                        "startedAt", startedAt,
                        "endedAt", endedAt,
                        "incidentStartedAt", incidentStartedAt,
                        "incidentEndedAt", incidentEndedAt,
                        "lastNormalBootAt", lastNormalBootAt,
                        "selectedByUser", selectedByUser,
                        "consentId", consentId,
                        "symptom", symptom
                ),
                requireIdempotencyKey(idempotencyKey)
        );
    }

    @PostMapping(value = "/log-uploads/as-rag-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> previewAsRag(
            @AuthenticationPrincipal AgentPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Integer rangeMinutes,
            @RequestParam(required = false) String rangeStartedAt,
            @RequestParam(required = false) String rangeEndedAt,
            @RequestParam(required = false) Integer schemaVersion,
            @RequestParam(required = false) String incidentId,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) String symptomType,
            @RequestParam(required = false) String detectedAt,
            @RequestParam(required = false) String incidentStartedAt,
            @RequestParam(required = false) String incidentEndedAt,
            @RequestParam(required = false) String lastNormalBootAt,
            @RequestParam(required = false) String startedAt,
            @RequestParam(required = false) String endedAt,
            @RequestParam(required = false) Boolean selectedByUser,
            @RequestParam(required = false) String consentId,
            @RequestParam(required = false) String symptom,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return pcAgentAsService.previewAsRag(
                principal,
                file,
                MockData.map(
                        "rangeMinutes", rangeMinutes,
                        "rangeStartedAt", rangeStartedAt,
                        "rangeEndedAt", rangeEndedAt,
                        "schemaVersion", schemaVersion,
                        "incidentId", incidentId,
                        "triggerType", triggerType,
                        "symptomType", symptomType,
                        "detectedAt", detectedAt,
                        "startedAt", startedAt,
                        "endedAt", endedAt,
                        "incidentStartedAt", incidentStartedAt,
                        "incidentEndedAt", incidentEndedAt,
                        "lastNormalBootAt", lastNormalBootAt,
                        "selectedByUser", selectedByUser,
                        "consentId", consentId,
                        "symptom", symptom
                ),
                requireIdempotencyKey(idempotencyKey)
        );
    }

    @PostMapping(value = "/as-drafts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createAsDraft(
            @AuthenticationPrincipal AgentPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Integer rangeMinutes,
            @RequestParam(required = false) String rangeStartedAt,
            @RequestParam(required = false) String rangeEndedAt,
            @RequestParam(required = false) Integer schemaVersion,
            @RequestParam(required = false) String incidentId,
            @RequestParam(required = false) String triggerType,
            @RequestParam(required = false) String symptomType,
            @RequestParam(required = false) String detectedAt,
            @RequestParam(required = false) String incidentStartedAt,
            @RequestParam(required = false) String incidentEndedAt,
            @RequestParam(required = false) String lastNormalBootAt,
            @RequestParam(required = false) String startedAt,
            @RequestParam(required = false) String endedAt,
            @RequestParam(required = false) Boolean selectedByUser,
            @RequestParam(required = false) String consentId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String detailDescription,
            @RequestParam(required = false) String supportRequestKind,
            @RequestParam(required = false) String symptom,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return pcAgentAsService.createAsDraft(
                principal,
                file,
                MockData.map(
                        "rangeMinutes", rangeMinutes,
                        "rangeStartedAt", rangeStartedAt,
                        "rangeEndedAt", rangeEndedAt,
                        "schemaVersion", schemaVersion,
                        "incidentId", incidentId,
                        "triggerType", triggerType,
                        "symptomType", symptomType,
                        "detectedAt", detectedAt,
                        "startedAt", startedAt,
                        "endedAt", endedAt,
                        "incidentStartedAt", incidentStartedAt,
                        "incidentEndedAt", incidentEndedAt,
                        "lastNormalBootAt", lastNormalBootAt,
                        "selectedByUser", selectedByUser,
                        "consentId", consentId,
                        "title", title,
                        "detailDescription", detailDescription,
                        "supportRequestKind", supportRequestKind,
                        "symptom", symptom
                ),
                requireIdempotencyKey(idempotencyKey)
        );
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "Idempotency-Key header is required.",
                    Map.of("field", "Idempotency-Key")
            );
        }
        return idempotencyKey;
    }
}
