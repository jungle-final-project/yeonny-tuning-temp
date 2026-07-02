package com.buildgraph.prototype.part;

import com.buildgraph.prototype.part.util.PerformaceRule;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ToolCheckService extends ToolService {
    public ToolCheckService(JdbcTemplate jdbcTemplate, ToolRepository toolRepository, PerformaceRule performaceRule) {
        super(jdbcTemplate, toolRepository, performaceRule);
    }
}
