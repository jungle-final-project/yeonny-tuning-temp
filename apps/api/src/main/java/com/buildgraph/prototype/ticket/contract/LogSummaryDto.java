package com.buildgraph.prototype.ticket.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record LogSummaryDto(
        @NotBlank String summaryVersion,
        @NotBlank String ticketId,
        @Valid @NotNull IncidentWindowDto incidentWindow,
        @NotNull Map<String, Object> deviceProfile,
        @NotNull Map<String, Object> userSymptom,
        @NotNull Map<String, Object> baseline,
        @NotNull List<Map<String, Object>> timeline,
        @NotNull List<Map<String, Object>> anomalies,
        @NotNull List<Map<String, Object>> correlations,
        @NotNull List<Map<String, Object>> ruleSignals,
        @Valid @NotNull DataQualityDto dataQuality,
        @NotNull List<Map<String, Object>> evidenceRefs,
        @Valid @NotNull @Size(max = 20) List<RawLogSampleDto> rawSamples
) {
}
