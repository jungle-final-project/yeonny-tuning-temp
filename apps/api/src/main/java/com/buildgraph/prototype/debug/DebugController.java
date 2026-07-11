package com.buildgraph.prototype.debug;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.buildgraph.prototype.parts.part.PartQueryCached;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/debug")
public class DebugController {
  
    private final PartQueryCached partQueryCached;

    @GetMapping("/part-query-count")
    public Map<String, Object> partQueryCount() {
        return Map.of("count", partQueryCached.dbQueryCount());
    }

    @PostMapping("/part-query-count/reset")
    public Map<String, Object> resetPartQueryCount() {
        partQueryCached.resetDbQueryCount();
        return Map.of("count", 0);
    }
}
