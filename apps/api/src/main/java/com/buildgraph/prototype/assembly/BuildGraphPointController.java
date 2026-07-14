package com.buildgraph.prototype.assembly;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BuildGraphPointController {
    private final BuildGraphPointService service;

    public BuildGraphPointController(BuildGraphPointService service) {
        this.service = service;
    }

    @GetMapping("/users/me/points")
    Map<String, Object> wallet(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.wallet(authorization);
    }

    @PostMapping("/assembly-requests/{id}/payments/points/confirm")
    Map<String, Object> pay(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return service.pay(authorization, id, idempotencyKey);
    }
}
