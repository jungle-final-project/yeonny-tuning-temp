package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RecommendationController {
    private final RecommendationLearningService recommendationLearningService;
    private final RecommendationTrainingService recommendationTrainingService;
    private final HomePartRecommendationService homePartRecommendationService;
    private final RecommendationDriftService recommendationDriftService;
    private final CurrentUserService currentUserService;

    public RecommendationController(
            RecommendationLearningService recommendationLearningService,
            RecommendationTrainingService recommendationTrainingService,
            HomePartRecommendationService homePartRecommendationService,
            RecommendationDriftService recommendationDriftService,
            CurrentUserService currentUserService
    ) {
        this.recommendationLearningService = recommendationLearningService;
        this.recommendationTrainingService = recommendationTrainingService;
        this.homePartRecommendationService = homePartRecommendationService;
        this.recommendationDriftService = recommendationDriftService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/recommendation-events")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> recordRecommendationEvent(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return recommendationLearningService.recordEvent(request == null ? Map.of() : request, user);
    }

    @GetMapping("/recommendations/home-parts")
    Map<String, Object> homeRecommendedParts(
            @org.springframework.web.bind.annotation.RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return homePartRecommendationService.homeParts(user, limit);
    }

    @PostMapping("/admin/recommendation-feedback/as-tickets/{id}")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> confirmAsRecommendationFeedback(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return recommendationLearningService.confirmAsNegativeFeedback(id, request == null ? Map.of() : request, admin);
    }

    @PostMapping("/admin/recommendation-feedback/home-parts")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> recordHomePartRecommendationFeedback(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return recommendationLearningService.recordHomePartAdminFeedback(request == null ? Map.of() : request, admin);
    }

    @GetMapping("/admin/recommendation-models")
    Map<String, Object> recommendationModels(@RequestHeader(value = "Authorization", required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return recommendationLearningService.modelVersions();
    }

    @GetMapping("/admin/recommendation-models/summary")
    Map<String, Object> recommendationModelSummary(@RequestHeader(value = "Authorization", required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return recommendationLearningService.modelSummary();
    }

    @GetMapping("/admin/recommendation-shadow/summary")
    Map<String, Object> recommendationShadowSummary(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return recommendationLearningService.shadowComparisonSummary(days);
    }

    @GetMapping("/admin/recommendation-drift")
    Map<String, Object> recommendationDrift(
            @RequestParam(value = "days", defaultValue = "14") int days,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return recommendationDriftService.recentSnapshots(days);
    }

    @GetMapping("/admin/recommendation-training/overview")
    Map<String, Object> recommendationTrainingOverview(@RequestHeader(value = "Authorization", required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return recommendationTrainingService.overview();
    }

    @GetMapping("/admin/recommendation-training-datasets")
    Map<String, Object> recommendationTrainingDatasets(@RequestHeader(value = "Authorization", required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return recommendationTrainingService.datasets();
    }

    @PostMapping("/admin/recommendation-training-datasets")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createRecommendationTrainingDataset(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return recommendationTrainingService.createDataset(request == null ? Map.of() : request, admin);
    }

    @PatchMapping("/admin/recommendation-training-datasets/{id}")
    Map<String, Object> updateRecommendationTrainingDataset(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return recommendationTrainingService.updateDataset(id, request == null ? Map.of() : request);
    }

    @PostMapping("/admin/recommendation-training-datasets/{id}/lock")
    Map<String, Object> lockRecommendationTrainingDataset(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return recommendationTrainingService.lockDataset(id);
    }

    @PostMapping("/admin/recommendation-training-datasets/{id}/archive")
    Map<String, Object> archiveRecommendationTrainingDataset(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return recommendationTrainingService.archiveDataset(id);
    }

    @GetMapping("/admin/recommendation-training-datasets/{id}/items")
    Map<String, Object> recommendationTrainingDatasetItems(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return recommendationTrainingService.datasetItems(id);
    }

    @PostMapping("/admin/recommendation-training-datasets/{id}/items/bulk-include")
    Map<String, Object> bulkIncludeRecommendationTrainingDatasetItems(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return recommendationTrainingService.bulkInclude(id, request == null ? Map.of() : request);
    }

    @PostMapping("/admin/recommendation-training-datasets/{id}/items/bulk-exclude")
    Map<String, Object> bulkExcludeRecommendationTrainingDatasetItems(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return recommendationTrainingService.bulkExclude(id, request == null ? Map.of() : request);
    }

    @GetMapping("/admin/recommendation-training-jobs")
    Map<String, Object> recommendationTrainingJobs(@RequestHeader(value = "Authorization", required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return recommendationTrainingService.jobs();
    }

    @PostMapping("/admin/recommendation-training-jobs")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createRecommendationTrainingJob(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return recommendationTrainingService.createJob(request == null ? Map.of() : request, admin);
    }

    @PostMapping("/admin/recommendation-models/{id}/activate")
    Map<String, Object> activateRecommendationModel(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return recommendationTrainingService.activateModel(id);
    }

    @PostMapping("/admin/recommendation-models/{id}/retire")
    Map<String, Object> retireRecommendationModel(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireAdmin(authorization);
        return recommendationTrainingService.retireModel(id);
    }
}
