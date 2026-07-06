package com.buildgraph.prototype.ticket.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.Map;

public record RawLogSampleDto(
        @NotBlank String schemaVersion,
        @NotNull Instant collectedAt,
        @NotBlank String agentId,
        @NotBlank String deviceIdHash,
        @PositiveOrZero long sequence,
        @NotBlank String kind,
        @NotNull Map<String, Object> payload,
        @Valid @NotNull PrivacyFlagsDto privacyFlags
) {
}
