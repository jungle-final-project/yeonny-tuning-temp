package com.buildgraph.prototype.ticket.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record IncidentWindowDto(
        @NotBlank String incidentId,
        @NotNull IncidentTriggerType triggerType,
        @NotNull SymptomType symptomType,
        @NotNull Instant detectedAt,
        @NotNull Instant startedAt,
        @NotNull Instant endedAt,
        @Min(0) int preBufferSec,
        @Min(0) int postBufferSec,
        boolean selectedByUser,
        @NotBlank String consentId
) {
    @AssertTrue(message = "endedAt must be after startedAt.")
    @JsonIgnore
    public boolean isEndedAfterStarted() {
        return startedAt != null && endedAt != null && endedAt.isAfter(startedAt);
    }
}
