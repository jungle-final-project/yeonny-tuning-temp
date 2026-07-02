package com.buildgraph.prototype.part;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private final PartCompatibleCandidateService compatibleCandidateService;
    private final DanawaPriceSnapshotService danawaPriceSnapshotService;
    private final DanawaPriceTrendService danawaPriceTrendService;
    private final ManufacturerReleaseIntakeService manufacturerReleaseIntakeService;
    private final PartAdminService partAdminService;
    private final PartAliasReviewService partAliasReviewService;
    private final PartQualityReportService partQualityReportService;
    private final CurrentUserService currentUserService;

    public PartController(
            PartQueryService partQueryService,
            ToolCheckService toolCheckService,
            NaverShoppingOfferService naverShoppingOfferService,
            PartCompatibleCandidateService compatibleCandidateService,
            DanawaPriceSnapshotService danawaPriceSnapshotService,
            DanawaPriceTrendService danawaPriceTrendService,
            ManufacturerReleaseIntakeService manufacturerReleaseIntakeService,
            PartAdminService partAdminService,
            PartAliasReviewService partAliasReviewService,
            PartQualityReportService partQualityReportService,
            CurrentUserService currentUserService
    ) {
        this.partQueryService = partQueryService;
        this.toolCheckService = toolCheckService;
        this.naverShoppingOfferService = naverShoppingOfferService;
        this.compatibleCandidateService = compatibleCandidateService;
        this.danawaPriceSnapshotService = danawaPriceSnapshotService;
        this.danawaPriceTrendService = danawaPriceTrendService;
        this.manufacturerReleaseIntakeService = manufacturerReleaseIntakeService;
        this.partAdminService = partAdminService;
        this.partAliasReviewService = partAliasReviewService;
        this.partQualityReportService = partQualityReportService;
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
            @RequestParam(value = "compatibilitySource", required = false) String compatibilitySource,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return partQueryService.parts(user, category, query, manufacturer, status, minPrice, maxPrice, page, size, sort, compatibilitySource);
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

    @PostMapping("/parts/compatible-candidates")
    Map<String, Object> compatibleCandidates(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return compatibleCandidateService.compatibleCandidates(user, request);
    }

    @GetMapping("/admin/parts")
    Map<String, Object> adminParts(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "manufacturer", required = false) String manufacturer,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "minPrice", required = false) Integer minPrice,
            @RequestParam(value = "maxPrice", required = false) Integer maxPrice,
            @RequestParam(value = "includeDeleted", required = false) Boolean includeDeleted,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return partAdminService.listParts(category, query, manufacturer, status, minPrice, maxPrice, includeDeleted, page, size, sort);
    }

    @PostMapping("/admin/parts")
    Map<String, Object> createAdminPart(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return partAdminService.create(request, admin);
    }

    @GetMapping("/admin/parts/{id}")
    Map<String, Object> adminPart(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return partAdminService.detail(id);
    }

    @PatchMapping("/admin/parts/{id}")
    Map<String, Object> updateAdminPart(
            @PathVariable String id,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return partAdminService.update(id, request, admin);
    }

    @DeleteMapping("/admin/parts/{id}")
    Map<String, Object> deleteAdminPart(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return partAdminService.softDelete(id, admin);
    }

    @PostMapping("/admin/parts/{id}/restore")
    Map<String, Object> restoreAdminPart(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return partAdminService.restore(id, admin);
    }

    @PostMapping("/admin/parts/{id}/manual-price")
    Map<String, Object> updateAdminPartManualPrice(
            @PathVariable String id,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return partAdminService.manualPrice(id, request, admin);
    }

    @PatchMapping("/admin/parts/{id}/external-offer")
    Map<String, Object> updateAdminPartExternalOffer(
            @PathVariable String id,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return partAdminService.updateExternalOffer(id, request, admin);
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

    @GetMapping("/admin/parts/quality-report")
    Map<String, Object> adminPartsQualityReport(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return partQualityReportService.qualityReport();
    }

    @GetMapping("/admin/manufacturer-sources")
    Map<String, Object> manufacturerSources(
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "includeDeleted", required = false) Boolean includeDeleted,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.listSources(enabled, status, category, includeDeleted);
    }

    @GetMapping("/admin/manufacturer-sources/{id}")
    Map<String, Object> manufacturerSource(
            @PathVariable String id,
            @RequestParam(value = "includeDeleted", required = false) Boolean includeDeleted,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.getSource(id, includeDeleted);
    }

    @PostMapping("/admin/manufacturer-sources")
    Map<String, Object> createManufacturerSource(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.createSource(request, admin);
    }

    @PatchMapping("/admin/manufacturer-sources/{id}")
    Map<String, Object> updateManufacturerSource(
            @PathVariable String id,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.updateSource(id, request, admin);
    }

    @DeleteMapping("/admin/manufacturer-sources/{id}")
    Map<String, Object> deleteManufacturerSource(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.softDeleteSource(id, admin);
    }

    @PostMapping("/admin/manufacturer-sources/{id}/restore")
    Map<String, Object> restoreManufacturerSource(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.restoreSource(id, admin);
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
            @RequestParam(value = "includeDeleted", required = false) Boolean includeDeleted,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.listPosts(status, category, page, size, includeDeleted);
    }

    @GetMapping("/admin/manufacturer-posts/{id}")
    Map<String, Object> manufacturerPost(
            @PathVariable String id,
            @RequestParam(value = "includeDeleted", required = false) Boolean includeDeleted,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.getPost(id, includeDeleted);
    }

    @PostMapping("/admin/manufacturer-posts")
    Map<String, Object> createManufacturerPost(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.createPost(request, admin);
    }

    @PatchMapping("/admin/manufacturer-posts/{id}")
    Map<String, Object> updateManufacturerPost(
            @PathVariable String id,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.updatePost(id, request, admin);
    }

    @DeleteMapping("/admin/manufacturer-posts/{id}")
    Map<String, Object> deleteManufacturerPost(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.softDeletePost(id, admin);
    }

    @PostMapping("/admin/manufacturer-posts/{id}/restore")
    Map<String, Object> restoreManufacturerPost(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.restorePost(id, admin);
    }

    @PostMapping("/admin/manufacturer-posts/{id}/create-candidate")
    Map<String, Object> createCandidateForManufacturerPost(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.createCandidateForPost(id, admin);
    }

    @PostMapping("/admin/manufacturer-posts/{id}/ai-asset-draft")
    Map<String, Object> createAiAssetDraftForManufacturerPost(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.createAiAssetDraftForPost(id, admin);
    }

    @GetMapping("/admin/part-catalog-candidates")
    Map<String, Object> partCatalogCandidates(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "includeDeleted", required = false) Boolean includeDeleted,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return manufacturerReleaseIntakeService.listCatalogCandidates(status, category, source, page, size, includeDeleted);
    }

    @GetMapping("/admin/part-catalog-candidates/{id}")
    Map<String, Object> partCatalogCandidate(
            @PathVariable String id,
            @RequestParam(value = "includeDeleted", required = false) Boolean includeDeleted,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return naverShoppingOfferService.getCatalogCandidate(id, includeDeleted);
    }

    @PatchMapping("/admin/part-catalog-candidates/{id}")
    Map<String, Object> updatePartCatalogCandidate(
            @PathVariable String id,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return naverShoppingOfferService.updateCatalogCandidate(id, request, admin);
    }

    @DeleteMapping("/admin/part-catalog-candidates/{id}")
    Map<String, Object> deletePartCatalogCandidate(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return naverShoppingOfferService.softDeleteCatalogCandidate(id, admin);
    }

    @PostMapping("/admin/part-catalog-candidates/{id}/restore")
    Map<String, Object> restorePartCatalogCandidate(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return naverShoppingOfferService.restoreCatalogCandidate(id, admin);
    }

    @PostMapping("/admin/part-catalog-candidates/{id}/approve")
    Map<String, Object> approvePartCatalogCandidate(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return naverShoppingOfferService.approveCatalogCandidateAsInactive(id, admin);
    }

    @PostMapping("/admin/part-catalog-candidates/{id}/reject")
    Map<String, Object> rejectPartCatalogCandidate(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return naverShoppingOfferService.rejectCatalogCandidate(id, request, admin);
    }

    @PostMapping("/admin/part-catalog-candidates/{id}/refresh-offers")
    Map<String, Object> refreshPartCatalogCandidateOffers(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return naverShoppingOfferService.refreshCatalogCandidateOffer(id);
    }

    @GetMapping("/admin/part-alias-review-items")
    Map<String, Object> partAliasReviewItems(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "targetField", required = false) String targetField,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return partAliasReviewService.listReviewItems(status, category, targetField, sourceType, page, size);
    }

    @GetMapping("/admin/part-alias-review-items/summary")
    Map<String, Object> partAliasReviewSummary(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return partAliasReviewService.reviewSummary();
    }

    @PostMapping("/admin/part-alias-review-items/{id}/resolve")
    Map<String, Object> resolvePartAliasReviewItem(
            @PathVariable String id,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return partAliasReviewService.resolveReviewItem(id, request, admin);
    }

    @PostMapping("/admin/part-alias-review-items/{id}/ignore")
    Map<String, Object> ignorePartAliasReviewItem(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return partAliasReviewService.ignoreReviewItem(id, request, admin);
    }

    @GetMapping("/admin/part-alias-rules")
    Map<String, Object> partAliasRules(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "targetField", required = false) String targetField,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return partAliasReviewService.listRules(category, targetField, page, size);
    }

    @PostMapping("/admin/part-alias-rules")
    Map<String, Object> createPartAliasRule(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        var admin = currentUserService.requireAdmin(authorization);
        return partAliasReviewService.createRule(request, admin);
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
