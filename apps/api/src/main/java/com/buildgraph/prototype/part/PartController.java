package com.buildgraph.prototype.part;

import com.buildgraph.prototype.user.CurrentUserService;
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
public class PartController {
    private final PartQueryService partQueryService;
    private final ToolCheckService toolCheckService;
    private final NaverShoppingOfferService naverShoppingOfferService;
    private final DanawaPriceSnapshotService danawaPriceSnapshotService;
    private final DanawaPriceTrendService danawaPriceTrendService;
    private final ManufacturerReleaseIntakeService manufacturerReleaseIntakeService;
    private final CurrentUserService currentUserService;

    public PartController(
            PartQueryService partQueryService,
            ToolCheckService toolCheckService,
            NaverShoppingOfferService naverShoppingOfferService,
            DanawaPriceSnapshotService danawaPriceSnapshotService,
            DanawaPriceTrendService danawaPriceTrendService,
            ManufacturerReleaseIntakeService manufacturerReleaseIntakeService,
            CurrentUserService currentUserService
    ) {
        this.partQueryService = partQueryService;
        this.toolCheckService = toolCheckService;
        this.naverShoppingOfferService = naverShoppingOfferService;
        this.danawaPriceSnapshotService = danawaPriceSnapshotService;
        this.danawaPriceTrendService = danawaPriceTrendService;
        this.manufacturerReleaseIntakeService = manufacturerReleaseIntakeService;
        this.currentUserService = currentUserService;
    }

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
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return partQueryService.parts(category, query, manufacturer, status, minPrice, maxPrice, page, size, sort);
    }

    @GetMapping("/parts/{id}")
    Map<String, Object> part(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return partQueryService.part(id);
    }

    @GetMapping("/parts/{id}/price-history")
    Map<String, Object> priceHistory(
            @PathVariable String id,
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
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

    @PostMapping("/admin/parts/danawa-price-snapshots/refresh")
    Map<String, Object> refreshDanawaPriceSnapshots(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "force", required = false) Boolean force,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return danawaPriceSnapshotService.refreshSnapshots(category, limit, force);
    }

    @PostMapping("/admin/parts/danawa-price-trends/refresh")
    Map<String, Object> refreshDanawaPriceTrends(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "months", required = false) Integer months,
            @RequestParam(value = "force", required = false) Boolean force,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return danawaPriceTrendService.refreshTrends(category, limit, months, force);
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

    @GetMapping("/admin/manufacturer-sources")
    Map<String, Object> manufacturerSources(
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.listSources(enabled);
    }

    @PostMapping("/admin/manufacturer-sources")
    Map<String, Object> createManufacturerSource(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.createSource(request);
    }

    @org.springframework.web.bind.annotation.PatchMapping("/admin/manufacturer-sources/{id}")
    Map<String, Object> updateManufacturerSource(
            @PathVariable String id,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.updateSource(id, request);
    }

    @PostMapping("/admin/manufacturer-sources/{id}/scan")
    Map<String, Object> scanManufacturerSource(
            @PathVariable String id,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "createCandidates", required = false) Boolean createCandidates,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.scanSource(id, limit, createCandidates);
    }

    @PostMapping("/admin/manufacturer-sources/scan")
    Map<String, Object> scanManufacturerSources(
            @RequestParam(value = "limitPerSource", required = false) Integer limitPerSource,
            @RequestParam(value = "createCandidates", required = false) Boolean createCandidates,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.scanAll(limitPerSource, createCandidates);
    }

    @GetMapping("/admin/manufacturer-posts")
    Map<String, Object> manufacturerPosts(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.listPosts(status, category, page, size);
    }

    @GetMapping("/admin/part-catalog-candidates")
    Map<String, Object> partCatalogCandidates(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.listCatalogCandidates(status, category, source, page, size);
    }

    @PostMapping("/admin/part-catalog-candidates/{id}/approve")
    Map<String, Object> approvePartCatalogCandidate(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return naverShoppingOfferService.approveCatalogCandidateAsInactive(id);
    }

    @PostMapping("/admin/part-catalog-candidates/{id}/reject")
    Map<String, Object> rejectPartCatalogCandidate(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return naverShoppingOfferService.rejectCatalogCandidate(id, request);
    }

    @PostMapping("/admin/part-catalog-candidates/{id}/refresh-offers")
    Map<String, Object> refreshPartCatalogCandidateOffers(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return naverShoppingOfferService.refreshCatalogCandidateOffer(id);
    }

    @PostMapping("/tools/compatibility/check")
    Map<String, Object> compatibility(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return tool("compatibility", request, authorization);
    }

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

    private Map<String, Object> tool(String tool, Map<String, Object> request, String authorization) {
        currentUserService.requireUser(authorization);
        return toolCheckService.checkTool(tool, request);
    }

}
