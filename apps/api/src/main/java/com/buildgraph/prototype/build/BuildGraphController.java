package com.buildgraph.prototype.build;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BuildGraphController {
    private final BuildGraphService buildGraphService;
    private final BuildGraphLayoutService buildGraphLayoutService;
    private final CurrentUserService currentUserService;

    public BuildGraphController(
            BuildGraphService buildGraphService,
            BuildGraphLayoutService buildGraphLayoutService,
            CurrentUserService currentUserService
    ) {
        this.buildGraphService = buildGraphService;
        this.buildGraphLayoutService = buildGraphLayoutService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/build-graphs/resolve")
    Map<String, Object> resolve(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return buildGraphService.resolve(authorization, request == null ? Map.of() : request);
    }

    @GetMapping("/build-graph-layouts/default")
    Map<String, Object> buildGraphLayoutDefault(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return buildGraphLayoutService.getDefaultLayout();
    }
}
