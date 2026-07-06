package com.buildgraph.prototype.ticket.contract;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SupportRoutingDto(
        @NotNull SupportDecision recommendedDecision,
        @NotNull SupportConfidence confidence,
        @NotNull List<SupportReasonCode> reasonCodes,
        @NotNull List<RemoteAction> remoteActions,
        @NotNull List<VisitReason> visitReasons,
        @NotNull List<BlockingFactor> blockingFactors,
        SafetyAdviceLevel safetyAdviceLevel,
        List<SafetyNoticeDto> safetyNotices,
        Boolean allowAutoResponse,
        boolean requiresAdminApproval
) {
}
