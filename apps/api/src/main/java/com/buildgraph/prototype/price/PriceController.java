package com.buildgraph.prototype.price;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PriceController {
    private final PriceQueryService priceQueryService;
    private final CurrentUserService currentUserService;

    public PriceController(PriceQueryService priceQueryService, CurrentUserService currentUserService) {
        this.priceQueryService = priceQueryService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/price-alerts")
    Map<String, Object> alerts(@RequestHeader(value = "Authorization", required = false) String authorization) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return priceQueryService.alerts(user);
    }

    @PostMapping("/price-alerts")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createAlert(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return priceQueryService.createAlert(request, user);
    }
}
