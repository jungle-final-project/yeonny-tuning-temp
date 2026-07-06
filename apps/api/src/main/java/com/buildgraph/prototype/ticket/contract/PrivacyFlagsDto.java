package com.buildgraph.prototype.ticket.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;

public record PrivacyFlagsDto(
        boolean masked,
        boolean containsRawPath
) {
    @AssertTrue(message = "rawSamples must not contain raw file paths.")
    @JsonIgnore
    public boolean isRawPathMasked() {
        return !containsRawPath;
    }
}
