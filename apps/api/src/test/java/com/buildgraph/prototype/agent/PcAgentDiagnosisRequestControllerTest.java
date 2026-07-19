package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PcAgentDiagnosisRequestControllerTest {
    private static final String DIAGNOSIS_ID = "00000000-0000-4000-8000-000000000321";
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            7L, "user-id", "user@example.com", "User", "USER", null
    );

    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final PcAgentDiagnosisRequestService requestService = mock(PcAgentDiagnosisRequestService.class);
    private final PcAgentDiagnosisQueryService queryService = mock(PcAgentDiagnosisQueryService.class);
    private final PcAgentDiagnosisRequestController controller = new PcAgentDiagnosisRequestController(
            currentUserService, requestService, queryService
    );

    @Test
    void authenticatedUserCanOnlyQueryThroughResolvedCurrentUser() {
        when(currentUserService.requireUser("Bearer access-token")).thenReturn(USER);
        when(queryService.get(USER, DIAGNOSIS_ID)).thenReturn(Map.of(
                "diagnosisId", DIAGNOSIS_ID,
                "completed", true
        ));

        Map<String, Object> response = controller.get("Bearer access-token", DIAGNOSIS_ID);

        assertThat(response)
                .containsEntry("diagnosisId", DIAGNOSIS_ID)
                .containsEntry("completed", true);
        verify(currentUserService).requireUser("Bearer access-token");
        verify(queryService).get(USER, DIAGNOSIS_ID);
    }

    @Test
    void authenticatedUserCanQueryOnlyTheirLatestDiagnosis() {
        when(currentUserService.requireUser("Bearer access-token")).thenReturn(USER);
        when(queryService.latest(USER)).thenReturn(Map.of(
                "diagnosis", Map.of("diagnosisId", DIAGNOSIS_ID)
        ));

        Map<String, Object> response = controller.latest("Bearer access-token");

        assertThat(response).containsKey("diagnosis");
        verify(currentUserService).requireUser("Bearer access-token");
        verify(queryService).latest(USER);
    }

    @Test
    void unauthenticatedQueryIsRejectedBeforeDiagnosisLookup() {
        when(currentUserService.requireUser(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));

        assertThatThrownBy(() -> controller.get(null, DIAGNOSIS_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(queryService, org.mockito.Mockito.never()).get(USER, DIAGNOSIS_ID);
    }

    @Test
    void unauthenticatedLatestQueryIsRejectedBeforeDiagnosisLookup() {
        when(currentUserService.requireUser(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));

        assertThatThrownBy(() -> controller.latest(null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(queryService, org.mockito.Mockito.never()).latest(USER);
    }
}
