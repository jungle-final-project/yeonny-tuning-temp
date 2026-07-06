package com.buildgraph.prototype.ticket.contract;

import jakarta.validation.constraints.NotBlank;

public record SafetyNoticeDto(
        @NotBlank String code,
        @NotBlank String message
) {
}
