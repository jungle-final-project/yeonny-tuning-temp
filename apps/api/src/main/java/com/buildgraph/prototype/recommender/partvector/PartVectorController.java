package com.buildgraph.prototype.recommender.partvector;

import org.springframework.web.bind.annotation.RestController;

import com.buildgraph.prototype.user.CurrentUserService;

import lombok.RequiredArgsConstructor;

import java.util.Map;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PartVectorController {

    private final PartVectorCalculator partVectorCalculator;
    private final CurrentUserService currentUserService;
    
    /* 관리자가 호출을 하여 백터 임베딩을 수행한다 */
    @PostMapping("/admin/part-vectors/recalculate")
    public Map<String, Object> recalculateAll(
            @RequestHeader(value = "Authorization", required = false)
            String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        Integer calculatedParts = partVectorCalculator.recalculateAll();

        return Map.of(
                "status", "COMPLETED",
                "calculatedParts", calculatedParts
        );
    }
}
