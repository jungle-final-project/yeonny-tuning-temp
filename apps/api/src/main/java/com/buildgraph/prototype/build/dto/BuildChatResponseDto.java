package com.buildgraph.prototype.build.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BuildChatResponseDto(
        OutputType outputType,
        String message,
        Build build,
        Part part,
        String sessionId
) {
    public enum OutputType {
        MESSAGE,
        FULL_BUILD,
        PART
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Build(
            int totalPrice,
            List<Part> items
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Part(
            String partId,
            String category,
            String name,
            String manufacturer,
            int price,
            String reason
    ) {
    }
}
