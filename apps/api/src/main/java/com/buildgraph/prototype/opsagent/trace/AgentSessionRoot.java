package com.buildgraph.prototype.opsagent.trace;

import com.buildgraph.prototype.quoteagent.chat.*;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.quoteagent.tools.*;
import com.buildgraph.prototype.opsagent.as.*;
import com.buildgraph.prototype.opsagent.profile.*;
import com.buildgraph.prototype.opsagent.trace.*;
import com.buildgraph.prototype.opsagent.runner.*;

import com.buildgraph.prototype.quoteagent.chat.*;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.quoteagent.tools.*;

public record AgentSessionRoot(
        AgentSessionRootType type,
        String publicId
) {
    public static AgentSessionRoot from(AgentSessionCreateRequest request) {
        String requirementId = stringOrNull(request == null ? null : request.requirementId());
        String buildId = stringOrNull(request == null ? null : request.buildId());
        String asTicketId = stringOrNull(request == null ? null : request.asTicketId());
        int rootCount = (requirementId == null ? 0 : 1) + (buildId == null ? 0 : 1) + (asTicketId == null ? 0 : 1);
        if (rootCount != 1) {
            throw new IllegalArgumentException("requirementId, buildId, asTicketId 중 정확히 하나만 보내야 합니다.");
        }
        if (requirementId != null) {
            return new AgentSessionRoot(AgentSessionRootType.REQUIREMENT, requirementId);
        }
        if (buildId != null) {
            return new AgentSessionRoot(AgentSessionRootType.BUILD, buildId);
        }
        return new AgentSessionRoot(AgentSessionRootType.AS_TICKET, asTicketId);
    }

    public AgentPurpose purpose() {
        return switch (type) {
            case REQUIREMENT -> AgentPurpose.BUILD_RECOMMEND;
            case BUILD -> AgentPurpose.BUILD_EXPLAIN;
            case AS_TICKET -> AgentPurpose.AS_ANALYZE;
        };
    }

    public String requirementId() {
        return type == AgentSessionRootType.REQUIREMENT ? publicId : null;
    }

    public String buildId() {
        return type == AgentSessionRootType.BUILD ? publicId : null;
    }

    public String asTicketId() {
        return type == AgentSessionRootType.AS_TICKET ? publicId : null;
    }

    private static String stringOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
