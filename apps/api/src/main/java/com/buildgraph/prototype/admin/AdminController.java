package com.buildgraph.prototype.admin;

import com.buildgraph.prototype.price.PriceSeed;
import com.buildgraph.prototype.ticket.TicketSeed;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    @GetMapping("/dashboard")
    Map<String, Object> dashboard(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return AdminSeed.dashboard();
    }

    @GetMapping("/agent-sessions/{id}")
    Map<String, Object> agentSession(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return AdminSeed.agentSession(id);
    }

    @GetMapping("/tool-invocations")
    Map<String, Object> toolInvocations(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return AdminSeed.toolInvocations();
    }

    @GetMapping("/tool-invocations/{id}")
    Map<String, Object> toolInvocation(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return AdminSeed.toolInvocation(id);
    }

    @GetMapping("/rag-evidence/{id}")
    Map<String, Object> ragEvidence(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return AdminSeed.ragEvidence(id);
    }

    @GetMapping("/as-tickets")
    Map<String, Object> tickets(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return Map.of("items", TicketSeed.tickets());
    }

    @GetMapping("/as-tickets/{id}")
    Map<String, Object> ticket(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return TicketSeed.adminTicket(id);
    }

    @GetMapping("/price-jobs")
    Map<String, Object> priceJobs(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return PriceSeed.priceJobs();
    }

    @PostMapping("/price-jobs/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> runPriceJob(@RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAdmin(authorization);
        return PriceSeed.runPriceJob();
    }

    private static void requireAdmin(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer demo-jwt-")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        if (!authorization.contains("demo-jwt-admin")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }
    }
}
