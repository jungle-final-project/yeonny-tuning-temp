package com.buildgraph.prototype.opsagent.persistence;

import com.buildgraph.prototype.quoteagent.chat.*;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.quoteagent.tools.*;
import com.buildgraph.prototype.opsagent.as.*;
import com.buildgraph.prototype.opsagent.profile.*;
import com.buildgraph.prototype.opsagent.trace.*;
import com.buildgraph.prototype.opsagent.runner.*;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentLogBundleRepository extends JpaRepository<AgentLogBundleEntity, Long> {
}
