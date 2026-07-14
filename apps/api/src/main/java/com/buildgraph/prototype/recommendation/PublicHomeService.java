package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.PartQueryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PublicHomeService {
    private static final List<String> CATEGORIES =
            List.of("CPU", "GPU", "MOTHERBOARD", "RAM", "STORAGE", "PSU", "CASE", "COOLER");
    private final PartQueryService partQueryService;
    private final HomePartRecommendationService homePartRecommendationService;

    public PublicHomeService(
            PartQueryService partQueryService,
            HomePartRecommendationService homePartRecommendationService
    ) {
        this.partQueryService = partQueryService;
        this.homePartRecommendationService = homePartRecommendationService;
    }

    public Map<String, Object> home() {
        Map<String, Object> categoryParts = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            Map<String, Object> page = partQueryService.parts(
                    category, null, null, "ACTIVE", null, null, 0, 4, "price_desc"
            );
            categoryParts.put(category, page.getOrDefault("items", List.of()));
        }
        return MockData.map(
                "categoryParts", categoryParts,
                "recommendedParts", homePartRecommendationService.publicHomeParts(5)
        );
    }
}