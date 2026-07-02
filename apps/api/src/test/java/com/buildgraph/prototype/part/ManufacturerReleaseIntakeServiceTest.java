package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.buildgraph.prototype.agent.OpenAiResponsesClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class ManufacturerReleaseIntakeServiceTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final NaverShoppingOfferService naverShoppingOfferService = mock(NaverShoppingOfferService.class);
    private final OpenAiResponsesClient openAiResponsesClient = mock(OpenAiResponsesClient.class);
    private final ManufacturerReleaseIntakeService service = new ManufacturerReleaseIntakeService(
            jdbcTemplate,
            naverShoppingOfferService,
            openAiResponsesClient,
            "BuildGraphTest/0.1"
    );

    @Test
    void createSourceRejectsNonOfficialManufacturerDomainBeforeDatabaseWrite() {
        assertThatThrownBy(() -> service.createSource(Map.of(
                "manufacturer", "ASUS",
                "categoryScope", "GPU",
                "sourceType", "NEWS",
                "sourceUrl", "https://example.com/asus-news",
                "enabled", true
        )))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
