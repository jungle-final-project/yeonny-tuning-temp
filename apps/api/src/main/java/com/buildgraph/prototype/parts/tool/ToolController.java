package com.buildgraph.prototype.parts.tool;

import com.buildgraph.prototype.parts.part.PartQueryService;
import com.buildgraph.prototype.parts.util.NaverShoppingOfferService;
import com.buildgraph.prototype.user.CurrentUserService;

import lombok.RequiredArgsConstructor;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ToolController {
    private final PartQueryService partQueryService;
    private final ToolService toolCheckService;
    private final NaverShoppingOfferService naverShoppingOfferService;
    private final CurrentUserService currentUserService;

    @GetMapping("/parts")
    Map<String, Object> parts(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "manufacturer", required = false) String manufacturer,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "minPrice", required = false) Integer minPrice,
            @RequestParam(value = "maxPrice", required = false) Integer maxPrice,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "compatibilitySource", required = false) String compatibilitySource,
            @RequestParam(value = "compatibilityMode", required = false) String compatibilityMode,
            @RequestParam(value = "replaceTargetPartId", required = false) String replaceTargetPartId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = compatibilitySource == null || compatibilitySource.isBlank()
                ? null
                : currentUserService.requireUser(authorization);
        return partQueryService.parts(
                user,
                category,
                query,
                manufacturer,
                status,
                minPrice,
                maxPrice,
                page,
                size,
                sort,
                compatibilitySource,
                compatibilityMode,
                replaceTargetPartId
        );
    }

    @GetMapping("/parts/{id}")
    Map<String, Object> part(
            @PathVariable String id
    ) {
        return partQueryService.part(id);
    }

    @GetMapping("/parts/{id}/price-history")
    Map<String, Object> priceHistory(
            @PathVariable String id,
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return partQueryService.priceHistory(id, days, source, limit);
    }

    @PostMapping("/admin/parts/external-offers/refresh")
    Map<String, Object> refreshExternalOffers(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "force", required = false) Boolean force,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return naverShoppingOfferService.refreshOffers(category, limit, force);
    }

    @PostMapping("/admin/parts/catalog/refresh")
    Map<String, Object> refreshCatalog(
            @RequestParam(value = "category") String category,
            @RequestParam(value = "limitPerQuery", required = false) Integer limitPerQuery,
            @RequestParam(value = "publish", required = false) Boolean publish,
            @RequestParam(value = "q", required = false) String query,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return naverShoppingOfferService.refreshCatalog(category, limitPerQuery, publish, query);
    }

    @PostMapping("/tools/compatibility/check")
    Map<String, Object> compatibility(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return tool("compatibility", request, authorization);
    }

    /* 파워 - 전력 검증 */
    @PostMapping("/tools/power/check")
    Map<String, Object> power(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return tool("power", request, authorization);
    }

    @PostMapping("/tools/size/check")
    Map<String, Object> size(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return tool("size", request, authorization);
    }

    @PostMapping("/tools/performance/check")
    Map<String, Object> performance(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return tool("performance", request, authorization);
    }

    @PostMapping("/tools/price/check")
    Map<String, Object> price(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return tool("price", request, authorization);
    }


    /* 묶어서 보내는 함수 */
    private Map<String, Object> tool(String tool, Map<String, Object> request, String authorization) {
        currentUserService.requireUser(authorization);
        return toolCheckService.checkTool(tool, request);
    }

}
