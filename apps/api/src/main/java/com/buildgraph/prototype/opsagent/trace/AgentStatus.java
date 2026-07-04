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

public enum AgentStatus {
    QUEUED,
    RUNNING,
    RAG_SEARCHED,
    TOOLS_CALLED,
    SUMMARY_READY,
    FALLBACK_READY,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
