package com.buildgraph.prototype.ticket.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record AiDiagnosisRequestDto(
        @NotBlank String requestId,
        @NotBlank String ticketId,
        @NotNull Map<String, Object> userSymptom,
        @Valid @NotNull LogSummaryDto logSummary,
        @Valid @NotNull @Size(max = 20) List<RawLogSampleDto> rawSamples,
        @Valid @NotNull SupportRoutingDto supportRouting,
        @NotBlank String locale,
        @NotBlank String outputContractVersion
) {
}
