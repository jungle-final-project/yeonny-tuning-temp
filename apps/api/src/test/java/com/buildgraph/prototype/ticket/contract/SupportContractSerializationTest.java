package com.buildgraph.prototype.ticket.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SupportContractSerializationTest {
    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void supportDecisionSerializesAsApiEnumAndKeepsKoreanLabelsSeparately() throws Exception {
        String json = OBJECT_MAPPER.writeValueAsString(SupportDecision.REPAIR_OR_REPLACE);

        assertThat(json).isEqualTo("\"REPAIR_OR_REPLACE\"");
        assertThat(OBJECT_MAPPER.readValue("\"MONITOR_ONLY\"", SupportDecision.class))
                .isEqualTo(SupportDecision.MONITOR_ONLY);
        assertThat(SupportDecision.uiLabelsKo())
                .containsEntry("SELF_SOLVABLE", "자가 조치 가능")
                .containsEntry("REMOTE_POSSIBLE", "원격지원 가능")
                .containsEntry("VISIT_REQUIRED", "방문지원 필요")
                .containsEntry("REPAIR_OR_REPLACE", "수리/교체 필요")
                .containsEntry("NEEDS_MORE_INFO", "추가 정보 필요")
                .containsEntry("MONITOR_ONLY", "관찰 필요")
                .containsEntry("UNSUPPORTED", "지원 범위 밖");
    }

    @Test
    void aiDiagnosisRequestSerializesWithLogSummaryRawSamplesAndSupportRouting() throws Exception {
        AiDiagnosisRequestDto request = aiDiagnosisRequest(List.of(rawSample(1)));

        String json = OBJECT_MAPPER.writeValueAsString(request);
        AiDiagnosisRequestDto parsed = OBJECT_MAPPER.readValue(json, AiDiagnosisRequestDto.class);

        assertThat(parsed.ticketId()).isEqualTo("ticket-public-id");
        assertThat(parsed.locale()).isEqualTo("ko-KR");
        assertThat(parsed.rawSamples()).hasSize(1);
        assertThat(parsed.supportRouting().recommendedDecision()).isEqualTo(SupportDecision.REMOTE_POSSIBLE);
        assertThat(parsed.supportRouting().safetyAdviceLevel()).isEqualTo(SafetyAdviceLevel.NONE);
        assertThat(parsed.supportRouting().allowAutoResponse()).isFalse();
        assertThat(parsed.logSummary().incidentWindow().symptomType()).isEqualTo(SymptomType.REMOTE_DRIVER_OS);
        assertThat(VALIDATOR.validate(parsed)).isEmpty();
    }

    @Test
    void aiDiagnosisRequestRejectsMoreThanTwentyRawSamples() {
        List<RawLogSampleDto> rawSamples = IntStream.rangeClosed(1, 21)
                .mapToObj(SupportContractSerializationTest::rawSample)
                .toList();
        AiDiagnosisRequestDto request = aiDiagnosisRequest(rawSamples);

        assertThat(VALIDATOR.validate(request))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("rawSamples"));
    }

    @Test
    void logSummaryRejectsRawSampleThatStillContainsRawPath() {
        RawLogSampleDto rawPathSample = new RawLogSampleDto(
                "1",
                Instant.parse("2026-07-02T10:00:00Z"),
                "agent-public-id",
                "device-hash",
                1,
                "SYSTEM_METRIC",
                Map.of(),
                new PrivacyFlagsDto(true, true)
        );
        AiDiagnosisRequestDto request = aiDiagnosisRequest(List.of(rawPathSample));

        assertThat(VALIDATOR.validate(request))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).contains("rawPathMasked"));
    }

    private static AiDiagnosisRequestDto aiDiagnosisRequest(List<RawLogSampleDto> rawSamples) {
        IncidentWindowDto incidentWindow = new IncidentWindowDto(
                "incident-public-id",
                IncidentTriggerType.USER_REQUEST,
                SymptomType.REMOTE_DRIVER_OS,
                Instant.parse("2026-07-02T10:00:00Z"),
                Instant.parse("2026-07-02T09:45:00Z"),
                Instant.parse("2026-07-02T10:05:00Z"),
                900,
                300,
                true,
                "consent-public-id"
        );
        LogSummaryDto logSummary = new LogSummaryDto(
                "1",
                "ticket-public-id",
                incidentWindow,
                Map.of(),
                Map.of("symptomType", "REMOTE_DRIVER_OS"),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new DataQualityDto(DataQualityLevel.ENOUGH, List.of()),
                List.of(),
                rawSamples
        );
        SupportRoutingDto supportRouting = new SupportRoutingDto(
                SupportDecision.REMOTE_POSSIBLE,
                SupportConfidence.HIGH,
                List.of(SupportReasonCode.DRIVER_ERROR_REPEAT),
                List.of(RemoteAction.DRIVER_ROLLBACK, RemoteAction.WINDOWS_UPDATE_CHECK),
                List.of(),
                List.of(BlockingFactor.ADMIN_APPROVAL_REQUIRED),
                SafetyAdviceLevel.NONE,
                List.of(),
                false,
                true
        );
        return new AiDiagnosisRequestDto(
                "ai-request-id",
                "ticket-public-id",
                Map.of("symptomType", "REMOTE_DRIVER_OS"),
                logSummary,
                rawSamples,
                supportRouting,
                "ko-KR",
                "1"
        );
    }

    private static RawLogSampleDto rawSample(int sequence) {
        return new RawLogSampleDto(
                "1",
                Instant.parse("2026-07-02T10:00:00Z"),
                "agent-public-id",
                "device-hash",
                sequence,
                "SYSTEM_METRIC",
                Map.of(),
                new PrivacyFlagsDto(true, false)
        );
    }
}
