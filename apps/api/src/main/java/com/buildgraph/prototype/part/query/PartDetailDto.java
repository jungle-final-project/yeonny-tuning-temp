package com.buildgraph.prototype.part.query;

import java.util.Map;

import com.buildgraph.prototype.part.tool.ToolBuildPart;

public record PartDetailDto(
    ToolBuildPart part,
    String status,
    Map<String, Object> benchmark,
    Map<String, Object> latestPrice,
    Map<String, Object> externalOffer   
) {
    
}
