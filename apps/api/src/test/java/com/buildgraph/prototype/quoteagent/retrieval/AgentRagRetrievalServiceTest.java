package com.buildgraph.prototype.quoteagent.retrieval;

import com.buildgraph.prototype.quoteagent.chat.*;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.quoteagent.tools.*;
import com.buildgraph.prototype.opsagent.as.*;
import com.buildgraph.prototype.opsagent.profile.*;
import com.buildgraph.prototype.opsagent.trace.*;
import com.buildgraph.prototype.opsagent.runner.*;

import com.buildgraph.prototype.quoteagent.chat.*;
import com.buildgraph.prototype.quoteagent.retrieval.*;
import com.buildgraph.prototype.quoteagent.tools.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentRagRetrievalServiceTest {
    private static final List<Map<String, Object>> EVIDENCE_ROWS = List.of(
            row(
                    "internal-rule-requirement-parse-premium-open-budget",
                    "INTERNAL_RULE",
                    "REQUIREMENT_PARSE",
                    "If a user asks for the best possible PC without giving a concrete budget, interpret phrases such as 끝판왕, 최고사양, 최고급, 최상급, 하이엔드, 플래그십, RTX 5090급, 돈 상관 없음, 예산 무관, 가장 좋은, or 제일 좋은 as premium intent.",
                    "Premium intent without concrete budget should become ENTHUSIAST and OPEN_BUDGET.",
                    0.97000
            ),
            row(
                    "requirement-counterexample-premium-with-user-budget",
                    "INTERNAL_RULE",
                    "REQUIREMENT_PARSE",
                    "Counterexample: if the user says 최고사양, 끝판왕 느낌, 하이엔드 감성, or 제일 좋은 but also gives a concrete budget such as 200만원 or 300만원, keep budgetPolicy as USER_BUDGET.",
                    "Premium wording with concrete budget must remain USER_BUDGET.",
                    0.96500
            ),
            row(
                    "requirement-example-gaming-resolution-refresh",
                    "BENCHMARK",
                    "REQUIREMENT_PARSE",
                    "Examples: 배그 QHD 144Hz, 로스트아크 4K, 고주사율 FPS, qhd 옵션 타협 없음, fhd 240hz are gaming and display-performance requirements.",
                    "Korean gaming, resolution, refresh-rate, and option-level wording.",
                    0.94500
            ),
            row(
                    "requirement-example-workload-mixed-creator-ai",
                    "GUIDE",
                    "REQUIREMENT_PARSE",
                    "Examples: 개발 IDE, Docker, 컴파일, 영상 편집, 프리미어, 다빈치, 블렌더, 3D 작업, 로컬 AI, LLM 실험, CUDA should map to DEVELOPMENT, VIDEO_EDIT, or AI_DEV.",
                    "Development, creator, 3D, and local AI workload wording.",
                    0.94000
            ),
            row(
                    "requirement-example-noise-upgrade-brand",
                    "INTERNAL_RULE",
                    "REQUIREMENT_PARSE",
                    "Examples: 조용한 PC, 저소음, 밤에 켜둘 PC, 업그레이드 여유, 오래 쓸 PC, NVIDIA 선호, 라데온 싫음, 인텔 선호 should become noise, upgrade, and vendor preference fields.",
                    "Noise sensitivity, upgrade headroom, and brand preference wording.",
                    0.93500
            ),
            row(
                    "build-rule-cpu-gpu-balance-and-bottleneck",
                    "INTERNAL_RULE",
                    "BUILD_RECOMMEND",
                    "Avoid pairing a flagship GPU with a weak CPU for high refresh gaming or creator workloads. Use CPU class, GPU class, resolution, and workload to explain bottleneck risk.",
                    "CPU/GPU balance and bottleneck risk from stored facts only.",
                    0.93000
            ),
            row(
                    "build-rule-memory-storage-workload-floor",
                    "BENCHMARK",
                    "BUILD_RECOMMEND",
                    "For development, editing, 3D, and AI workloads, prefer at least 32GB RAM and consider 64GB for large projects. Prefer fast NVMe storage.",
                    "RAM and NVMe floors for development, editing, 3D, and AI workloads.",
                    0.92000
            ),
            row(
                    "build-rule-airflow-cooler-case-fit",
                    "INTERNAL_RULE",
                    "BUILD_RECOMMEND",
                    "High power CPU and GPU builds should check case airflow, maxGpuLengthMm, slot width, maxCpuCoolerHeightMm, radiator support, and cooler socket support.",
                    "Airflow, cooler, case clearance, and socket support checks.",
                    0.91500
            ),
            row(
                    "build-rule-saved-price-and-psu-headroom",
                    "INTERNAL_RULE",
                    "BUILD_RECOMMEND",
                    "Use saved current parts.price and price_snapshots. For PSU, compare estimated system draw, GPU requiredSystemPowerW, PSU capacityW, connector standard, and headroom.",
                    "Saved price first and PSU headroom validation.",
                    0.91000
            ),
            row(
                    "as-guide-gpu-thermal-frame-drop",
                    "TROUBLESHOOTING",
                    "AS_ANALYZE",
                    "AS examples: 게임 20분 뒤 프레임 급락, GPU 온도 90도 이상, 팬 소음 증가, 프레임 타임 튐 often indicate thermal throttling, airflow restriction, dust, fan curve, or driver instability.",
                    "GPU temperature, frame drop, fan noise, and frame-time spikes suggest thermal or driver causes.",
                    0.95000
            ),
            row(
                    "as-guide-driver-crash-event-log",
                    "TROUBLESHOOTING",
                    "AS_ANALYZE",
                    "AS examples: 화면 멈춤, 블루스크린, 드라이버 오류, nvlddmkm, display driver stopped, 게임 튕김, 이벤트 로그 오류 should prioritize driver rollback/update and event logs.",
                    "Crashes and display driver errors should inspect drivers, event logs, GPU stability, and power.",
                    0.94000
            ),
            row(
                    "as-guide-memory-storage-pressure",
                    "TROUBLESHOOTING",
                    "AS_ANALYZE",
                    "AS examples: 렌더링 느림, IDE 멈춤, 크롬 탭 많음, RAM 90퍼센트, 디스크 100퍼센트, 게임 로딩 지연 should check memory pressure and disk queue.",
                    "Memory pressure and disk saturation can explain slow rendering, IDE stalls, and loading delays.",
                    0.93000
            ),
            row(
                    "as-guide-power-instability",
                    "TROUBLESHOOTING",
                    "AS_ANALYZE",
                    "AS examples: 부하 걸면 재부팅, 전원이 꺼짐, 고사양 게임에서만 다운, 파워 부족 의심 should check PSU capacityW, connector, transient load, GPU requiredSystemPowerW, and event logs.",
                    "Load-related reboot or shutdown should check PSU margin, connector, transient load, and logs.",
                    0.92500
            )
    );

    @Test
    void requirementEvaluationSetExpectedSourceAppearsInTopThree() {
        List<EvalCase> cases = List.of(
                req("끝판왕 컴퓨터 만들어줘", "internal-rule-requirement-parse-premium-open-budget"),
                req("돈 상관없이 제일 좋은 PC로 맞춰줘", "internal-rule-requirement-parse-premium-open-budget"),
                req("최고급 게임 컴퓨터 추천", "internal-rule-requirement-parse-premium-open-budget"),
                req("RTX 5090급 하이엔드 본체", "internal-rule-requirement-parse-premium-open-budget"),
                req("예산 무관 플래그십 구성", "internal-rule-requirement-parse-premium-open-budget"),
                req("200만원으로 최고사양 느낌 내줘", "requirement-counterexample-premium-with-user-budget"),
                req("300만원 안에서 끝판왕처럼", "requirement-counterexample-premium-with-user-budget"),
                req("250만원 예산으로 제일 좋은 구성", "requirement-counterexample-premium-with-user-budget"),
                req("예산 180만원 하이엔드 감성", "requirement-counterexample-premium-with-user-budget"),
                req("200만원 최고급 게임용", "requirement-counterexample-premium-with-user-budget"),
                req("QHD 배그 144Hz 옵션 맞춰줘", "requirement-example-gaming-resolution-refresh"),
                req("FHD 240hz FPS 게임용", "requirement-example-gaming-resolution-refresh"),
                req("4K 로스트아크용 PC", "requirement-example-gaming-resolution-refresh"),
                req("qhd 옵션 타협 없이 게임", "requirement-example-gaming-resolution-refresh"),
                req("고주사율 FPS 위주", "requirement-example-gaming-resolution-refresh"),
                req("개발 IDE Docker 컴파일용", "requirement-example-workload-mixed-creator-ai"),
                req("프리미어 영상 편집 PC", "requirement-example-workload-mixed-creator-ai"),
                req("블렌더 3D 작업용", "requirement-example-workload-mixed-creator-ai"),
                req("로컬 AI CUDA 실험", "requirement-example-workload-mixed-creator-ai"),
                req("게임이랑 개발 같이 할 컴퓨터", "requirement-example-workload-mixed-creator-ai"),
                req("조용한 PC로 맞춰줘", "requirement-example-noise-upgrade-brand"),
                req("저소음으로 밤에 켜둘 본체", "requirement-example-noise-upgrade-brand"),
                req("오래 쓸 업그레이드 여유 있는 구성", "requirement-example-noise-upgrade-brand"),
                req("NVIDIA 선호하는 게임용", "requirement-example-noise-upgrade-brand"),
                req("라데온 싫고 엔비디아로", "requirement-example-noise-upgrade-brand"),
                req("인텔 선호 개발용", "requirement-example-noise-upgrade-brand"),
                req("밤새 켜둘 조용한 개발 PC", "requirement-example-noise-upgrade-brand"),
                req("향후 그래픽카드 업그레이드 여유", "requirement-example-noise-upgrade-brand"),
                req("프리미어랑 게임 둘 다 QHD", "requirement-example-workload-mixed-creator-ai"),
                req("배그 QHD에 NVIDIA 선호", "requirement-example-gaming-resolution-refresh")
        );

        assertEvaluationCases(cases, AgentRunProfiles.requirementParse());
    }

    @Test
    void asEvaluationSetExpectedSourceAppearsInTopThree() {
        List<EvalCase> cases = List.of(
                as("게임 20분 뒤 프레임 급락 GPU 온도 높음", "as-guide-gpu-thermal-frame-drop"),
                as("GPU 온도 90도 넘고 팬 소음 커짐", "as-guide-gpu-thermal-frame-drop"),
                as("프레임 타임 튐과 렉이 같이 발생", "as-guide-gpu-thermal-frame-drop"),
                as("먼지 청소 후에도 게임 중 열이 심함", "as-guide-gpu-thermal-frame-drop"),
                as("케이스 팬 소음 증가 후 프레임 드랍", "as-guide-gpu-thermal-frame-drop"),
                as("화면 멈춤 블루스크린 발생", "as-guide-driver-crash-event-log"),
                as("nvlddmkm 드라이버 오류 반복", "as-guide-driver-crash-event-log"),
                as("display driver stopped 이벤트 로그", "as-guide-driver-crash-event-log"),
                as("게임이 자꾸 튕기고 드라이버 오류", "as-guide-driver-crash-event-log"),
                as("렌더링 느림 RAM 90퍼센트 사용", "as-guide-memory-storage-pressure"),
                as("IDE 멈춤 크롬 탭 많음", "as-guide-memory-storage-pressure"),
                as("디스크 100퍼센트 게임 로딩 지연", "as-guide-memory-storage-pressure"),
                as("SSD 병목처럼 로딩이 너무 느림", "as-guide-memory-storage-pressure"),
                as("부하 걸면 재부팅", "as-guide-power-instability"),
                as("고사양 게임에서만 전원이 꺼짐 파워 부족 의심", "as-guide-power-instability")
        );

        assertEvaluationCases(cases, AgentRunProfiles.forRoot(new AgentSessionRoot(AgentSessionRootType.AS_TICKET, "ticket")));
    }

    @Test
    void retrievalKeepsSourceTypeDiversityWhenPossible() {
        AgentRagRetrievalService service = serviceForRequirement("QHD 배그 개발 IDE 조용한 PC");
        List<AgentRagEvidenceDraft> evidenceSet = service.retrieveEvidenceSet(
                new AgentSessionRoot(AgentSessionRootType.REQUIREMENT, "req"),
                AgentRunProfiles.requirementParse(),
                3
        );

        assertThat(evidenceSet)
                .extracting(evidence -> String.valueOf(evidence.metadata().get("sourceType")))
                .contains("INTERNAL_RULE", "BENCHMARK", "GUIDE");
    }

    private static void assertEvaluationCases(List<EvalCase> cases, AgentRunProfile profile) {
        List<String> missed = cases.stream()
                .filter(evalCase -> !topSources(evalCase, profile).contains(evalCase.expectedSourceId()))
                .map(EvalCase::message)
                .toList();
        double matchRate = (cases.size() - missed.size()) / (double) cases.size();

        assertThat(matchRate)
                .as("expected RAG source should appear in top 3 for at least 85%% of fixed cases; missed=%s", missed)
                .isGreaterThanOrEqualTo(0.85);
    }

    private static List<String> topSources(EvalCase evalCase, AgentRunProfile profile) {
        AgentSessionRoot root = evalCase.rootType() == AgentSessionRootType.AS_TICKET
                ? new AgentSessionRoot(AgentSessionRootType.AS_TICKET, "ticket")
                : new AgentSessionRoot(AgentSessionRootType.REQUIREMENT, "req");
        AgentRagRetrievalService service = evalCase.rootType() == AgentSessionRootType.AS_TICKET
                ? serviceForTicket(evalCase.message())
                : serviceForRequirement(evalCase.message());
        return service.retrieveEvidenceSet(root, profile, 3).stream()
                .map(AgentRagEvidenceDraft::sourceId)
                .toList();
    }

    private static AgentRagRetrievalService serviceForRequirement(String message) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(argThat(sql -> sql.contains("FROM rag_evidence"))))
                .thenReturn(EVIDENCE_ROWS);
        when(jdbcTemplate.queryForList(argThat(sql -> sql.contains("FROM requirements")), eq("req")))
                .thenReturn(List.of(Map.of(
                        "raw_message", message,
                        "usage_tags", "",
                        "parsed_context", "{}"
                )));
        return new AgentRagRetrievalService(jdbcTemplate);
    }

    private static AgentRagRetrievalService serviceForTicket(String symptom) {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(argThat(sql -> sql.contains("FROM rag_evidence"))))
                .thenReturn(EVIDENCE_ROWS);
        when(jdbcTemplate.queryForList(argThat(sql -> sql.contains("FROM as_tickets")), eq("ticket")))
                .thenReturn(List.of(Map.of(
                        "symptom", symptom,
                        "cause_candidates", "[]",
                        "upgrade_candidates", "[]",
                        "log_summary", symptom
                )));
        return new AgentRagRetrievalService(jdbcTemplate);
    }

    private static Map<String, Object> row(
            String sourceId,
            String sourceType,
            String purpose,
            String chunkText,
            String summary,
            double score
    ) {
        return Map.of(
                "id", "source-" + sourceId,
                "source_id", sourceId,
                "chunk_text", chunkText,
                "summary", summary,
                "score", BigDecimal.valueOf(score),
                "metadata", "{\"sourceType\":\"" + sourceType + "\",\"purpose\":\"" + purpose + "\",\"title\":\"" + sourceId + "\"}"
        );
    }

    private static EvalCase req(String message, String expectedSourceId) {
        return new EvalCase(AgentSessionRootType.REQUIREMENT, message, expectedSourceId);
    }

    private static EvalCase as(String message, String expectedSourceId) {
        return new EvalCase(AgentSessionRootType.AS_TICKET, message, expectedSourceId);
    }

    private record EvalCase(AgentSessionRootType rootType, String message, String expectedSourceId) {
    }
}
