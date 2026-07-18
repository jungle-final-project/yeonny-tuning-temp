package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthenticatedHomeServiceTest {
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1L,
            "00000000-0000-4000-8000-000000001001",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );

    @Mock
    private HomeCategoryPartsService homeCategoryPartsService;

    @Mock
    private HomePartRecommendationService homePartRecommendationService;

    @Test
    void authenticatedHomeUsesSharedRecommendationPayload() {
        Map<String, Object> categoryParts = Map.of("GPU", List.of(Map.of("name", "RTX 5070")));
        Map<String, Object> recommendedParts = Map.of("items", List.of(), "fallbackUsed", true);
        when(homeCategoryPartsService.priceDescCategoryParts()).thenReturn(categoryParts);
        when(homePartRecommendationService.sharedHomeParts(5)).thenReturn(recommendedParts);
        AuthenticatedHomeService service = new AuthenticatedHomeService(
                homeCategoryPartsService,
                homePartRecommendationService
        );

        Map<String, Object> response = service.home(USER);

        assertThat(response.get("categoryParts")).isSameAs(categoryParts);
        assertThat(response.get("recommendedParts")).isSameAs(recommendedParts);
        verify(homePartRecommendationService).sharedHomeParts(5);
    }

    @Test
    void prewarmFillsSharedAuthenticatedHomeCache() {
        Map<String, Object> categoryParts = Map.of("CPU", List.of(Map.of("name", "Ryzen")));
        Map<String, Object> recommendedParts = Map.of("items", List.of(), "fallbackUsed", true);
        when(homeCategoryPartsService.priceDescCategoryParts()).thenReturn(categoryParts);
        when(homePartRecommendationService.sharedHomeParts(5)).thenReturn(recommendedParts);
        AuthenticatedHomeService service = new AuthenticatedHomeService(
                homeCategoryPartsService,
                homePartRecommendationService
        );

        service.prewarm();
        Map<String, Object> response = service.home(USER);

        assertThat(response.get("categoryParts")).isSameAs(categoryParts);
        verify(homePartRecommendationService, times(1)).sharedHomeParts(5);
    }

    @Test
    void zeroTtlDisablesSharedAuthenticatedHomeCache() {
        Map<String, Object> categoryParts = Map.of("CPU", List.of(Map.of("name", "Ryzen")));
        Map<String, Object> recommendedParts = Map.of("items", List.of(), "fallbackUsed", true);
        when(homeCategoryPartsService.priceDescCategoryParts()).thenReturn(categoryParts);
        when(homePartRecommendationService.sharedHomeParts(5)).thenReturn(recommendedParts);
        AuthenticatedHomeService service = new AuthenticatedHomeService(
                homeCategoryPartsService,
                homePartRecommendationService,
                0L,
                0L,
                Runnable::run
        );

        service.home(USER);
        service.home(USER);

        verify(homePartRecommendationService, times(2)).sharedHomeParts(5);
    }
}
