package com.buildgraph.prototype.parts.tool;

import java.util.Map;

public record ToolBuildPart(
        Long internalId,
        String publicId,
        String category,
        String name,
        String manufacturer,
        Integer price,
        Map<String, Object> attributes
) {
}
