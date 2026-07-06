package com.buildgraph.prototype.ticket.contract;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record DataQualityDto(
        @NotNull DataQualityLevel level,
        @NotNull List<String> missingSignals
) {
}
