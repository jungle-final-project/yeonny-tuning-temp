package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class RecommendationLearningServiceTest {
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1L,
            "00000000-0000-4000-8000-000000001001",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );

    @Test
    void publicEventApiRejectsAdminFeedbackTypes() {
        RecommendationLearningService service = new RecommendationLearningService(org.mockito.Mockito.mock(JdbcTemplate.class));

        assertThatThrownBy(() -> service.recordEvent(Map.of("eventType", "ADMIN_PROMOTE"), USER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void bulkEventApiRejectsEmptyEvents() {
        RecommendationLearningService service = new RecommendationLearningService(org.mockito.Mockito.mock(JdbcTemplate.class));

        assertThatThrownBy(() -> service.recordEvents(Map.of("events", List.of()), USER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void bulkEventApiRejectsMoreThanTwentyEvents() {
        RecommendationLearningService service = new RecommendationLearningService(org.mockito.Mockito.mock(JdbcTemplate.class));
        List<Map<String, Object>> events = new ArrayList<>();
        for (int index = 0; index < 21; index += 1) {
            events.add(Map.of("eventType", "IMPRESSION"));
        }

        assertThatThrownBy(() -> service.recordEvents(Map.of("events", events), USER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void bulkValidationReturnsAcceptedEventCountWithoutWriting() {
        RecommendationLearningService service = new RecommendationLearningService(org.mockito.Mockito.mock(JdbcTemplate.class));

        int count = service.validateBulkUserEvents(Map.of(
                "events", List.of(
                        Map.of("eventType", "IMPRESSION", "sourceSurface", "HOME_RECOMMENDED_PARTS"),
                        Map.of("eventType", "CLICK", "sourceSurface", "HOME_RECOMMENDED_PARTS")
                )
        ));

        assertThat(count).isEqualTo(2);
    }

    @Test
    void bulkValidationRejectsAdminFeedbackTypes() {
        RecommendationLearningService service = new RecommendationLearningService(org.mockito.Mockito.mock(JdbcTemplate.class));

        assertThatThrownBy(() -> service.validateBulkUserEvents(Map.of(
                        "events", List.of(Map.of("eventType", "ADMIN_PROMOTE"))
                )))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
