package com.buildgraph.prototype.part.catalog;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.part.tool.ToolCheckService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PartCompatibleCandidateServiceTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ToolCheckService toolCheckService = mock(ToolCheckService.class);
    private final PartCompatibleCandidateService service = new PartCompatibleCandidateService(jdbcTemplate, toolCheckService);

    @Test
    void aiBuildCandidatesUseServerPartsAndFilterFailingOptions() {
        stubPart("base-cpu", part("base-cpu", 101L, "CPU", "Ryzen 7", 420000, MockData.map("socket", "AM5", "tdpW", 120)));
        stubPart("base-gpu", part("base-gpu", 102L, "GPU", "RTX 5070", 890000, MockData.map("wattage", 250, "lengthMm", 304)));
        stubPart("base-psu", part("base-psu", 103L, "PSU", "850W Gold", 150000, MockData.map("capacityW", 850)));
        stubPart("base-case", part("base-case", 104L, "CASE", "Airflow Case", 130000, MockData.map("maxGpuLengthMm", 360)));
        when(jdbcTemplate.queryForList(anyString(), eq("GPU"), eq(20))).thenReturn(List.of(
                candidate("candidate-fail", 201L, "GPU", "RTX 5090 Long", 2_900_000, MockData.map("wattage", 575, "lengthMm", 390)),
                candidate("candidate-pass", 202L, "GPU", "RTX 5070 Ti", 990000, MockData.map("wattage", 285, "lengthMm", 310)),
                candidate("candidate-warn", 203L, "GPU", "RTX 5080 Compact", 1_490_000, MockData.map("wattage", 360, "lengthMm", 330))
        ));
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any())).thenAnswer(invocation -> {
            List<ToolBuildPart> parts = invocation.getArgument(1);
            String gpuId = parts.stream()
                    .filter(part -> "GPU".equals(part.category()))
                    .findFirst()
                    .map(ToolBuildPart::publicId)
                    .orElse("");
            if ("candidate-fail".equals(gpuId)) {
                return List.of(
                        tool("power", "FAIL", "파워 출력이 부족합니다."),
                        tool("size", "FAIL", "케이스 장착이 불가능합니다."),
                        tool("performance", "PASS", "성능은 충분합니다.")
                );
            }
            if ("candidate-warn".equals(gpuId)) {
                return List.of(
                        tool("power", "WARN", "파워 여유가 낮습니다."),
                        tool("size", "PASS", "케이스 장착이 가능합니다."),
                        tool("performance", "PASS", "성능은 충분합니다.")
                );
            }
            return List.of(
                    tool("power", "PASS", "파워 여유가 충분합니다."),
                    tool("size", "PASS", "케이스 장착이 가능합니다."),
                    tool("performance", "PASS", "성능은 충분합니다.")
            );
        });

        Map<String, Object> response = service.compatibleCandidates(user(), Map.of(
                "source", "AI_BUILD",
                "category", "GPU",
                "items", List.of(
                        item("base-cpu", "CPU"),
                        item("base-gpu", "GPU"),
                        item("base-psu", "PSU"),
                        item("base-case", "CASE")
                ),
                "limit", 5
        ));

        assertThat(response.get("category")).isEqualTo("GPU");
        List<Map<String, Object>> items = castList(response.get("items"));
        assertThat(items).hasSize(2);
        assertThat(part(items.get(0)).get("name")).isEqualTo("RTX 5070 Ti");
        assertThat(items.get(0).get("status")).isEqualTo("PASS");
        assertThat(items.get(0).get("statusLabel")).isEqualTo("여유 있음");
        assertThat(items.get(0).get("checkedTools")).isEqualTo(List.of("power", "size", "performance"));
        assertThat(part(items.get(1)).get("name")).isEqualTo("RTX 5080 Compact");
        assertThat(items.get(1).get("status")).isEqualTo("WARN");
        assertThat(response.get("rejectedCount")).isEqualTo(1);
    }

    @Test
    void quoteDraftCandidatesReadOnlyCurrentUserDraftAndUseCategoryTools() {
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftItem("draft-cpu", 301L, "CPU", "Ryzen 7", 420000, MockData.map("socket", "AM5")),
                draftItem("draft-psu", 302L, "PSU", "650W Bronze", 90000, MockData.map("capacityW", 650)),
                draftItem("draft-case", 303L, "CASE", "Compact Case", 100000, MockData.map("maxGpuLengthMm", 330))
        ));
        when(jdbcTemplate.queryForList(anyString(), eq("PSU"), eq(20))).thenReturn(List.of(
                candidate("psu-pass", 401L, "PSU", "850W Gold", 150000, MockData.map("capacityW", 850)),
                candidate("psu-warn", 402L, "PSU", "750W Bronze", 110000, MockData.map("capacityW", 750))
        ));
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any())).thenAnswer(invocation -> {
            List<ToolBuildPart> parts = invocation.getArgument(1);
            String psuId = parts.stream()
                    .filter(part -> "PSU".equals(part.category()))
                    .findFirst()
                    .map(ToolBuildPart::publicId)
                    .orElse("");
            return List.of(tool("power", "psu-pass".equals(psuId) ? "PASS" : "WARN", "파워 후보를 확인했습니다."));
        });

        Map<String, Object> response = service.compatibleCandidates(user(), Map.of(
                "source", "QUOTE_DRAFT_CURRENT",
                "category", "PSU",
                "items", List.of(item("malicious-client-item", "GPU"))
        ));

        List<Map<String, Object>> items = castList(response.get("items"));
        assertThat(items).hasSize(2);
        // P0-3: 파워는 용량(power)에 더해 깊이 vs 케이스 허용 길이(size)도 본다.
        assertThat(items.get(0).get("checkedTools")).isEqualTo(List.of("power", "size"));
        assertThat(part(items.get(0)).get("name")).isEqualTo("850W Gold");
        assertThat(part(items.get(1)).get("name")).isEqualTo("750W Bronze");
    }

    @Test
    void compatibleCandidateIdsBackfillsThreeCandidatesAfterWrongSocketCpuFailures() {
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftItem("cpu-current", 301L, "CPU", "Core Ultra 7", 420000, MockData.map("socket", "LGA1851")),
                draftItem("board-current", 302L, "MOTHERBOARD", "B860 Board", 250000, MockData.map("socket", "LGA1851"))
        ));
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("public_id = ?::uuid")),
                any(Object[].class))).thenReturn(List.of(
                candidate("cpu-am5-a", 501L, "CPU", "Ryzen 9 9950X3D", 990000, MockData.map("socket", "AM5")),
                candidate("cpu-am5-b", 502L, "CPU", "Ryzen 9 9900X3D", 890000, MockData.map("socket", "AM5")),
                candidate("cpu-lga-a", 503L, "CPU", "Core Ultra 9 285K", 880000, MockData.map("socket", "LGA1851")),
                candidate("cpu-lga-b", 504L, "CPU", "Core Ultra 7 265K", 500000, MockData.map("socket", "LGA1851")),
                candidate("cpu-lga-c", 505L, "CPU", "Core Ultra 5 245K", 320000, MockData.map("socket", "LGA1851")),
                candidate("cpu-lga-unchecked", 506L, "CPU", "Core Ultra 5 235", 250000, MockData.map("socket", "LGA1851"))
        ));
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any())).thenAnswer(invocation -> {
            List<ToolBuildPart> parts = invocation.getArgument(1);
            boolean socketMatched = parts.stream()
                    .filter(part -> "CPU".equals(part.category()))
                    .map(ToolBuildPart::publicId)
                    .anyMatch(id -> id != null && id.startsWith("cpu-lga"));
            return List.of(tool(
                    "compatibility",
                    socketMatched ? "PASS" : "FAIL",
                    socketMatched ? "CPU와 메인보드 소켓이 맞습니다." : "CPU와 메인보드 소켓이 맞지 않습니다.",
                    MockData.map(
                            "socketMatched", socketMatched,
                            "coolerSocketMatched", true,
                            "coolerTdpMatched", true)));
        });

        List<String> accepted = service.compatibleCandidateIds(
                user(), "CPU", "REPLACE",
                List.of("cpu-am5-a", "cpu-am5-b", "cpu-lga-a", "cpu-lga-b", "cpu-lga-c", "cpu-lga-unchecked"),
                3);

        assertThat(accepted).containsExactly("cpu-lga-a", "cpu-lga-b", "cpu-lga-c");
        verify(toolCheckService, times(5)).checkBuildTools(anyList(), anyList(), anyInt(), any());
        verify(jdbcTemplate, times(1)).queryForList(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("public_id = ?::uuid")),
                any(Object[].class));
    }

    @Test
    void compatibleCandidateIdsPrefersThreePassesOverEarlierWarnings() {
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftItem("gpu-current", 301L, "GPU", "Current GPU", 420000, MockData.map()),
                draftItem("psu-current", 302L, "PSU", "Current PSU", 150000, MockData.map("capacityW", 850))
        ));
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("public_id = ?::uuid")),
                any(Object[].class))).thenReturn(List.of(
                candidate("gpu-warn-a", 501L, "GPU", "Warn A", 600000, MockData.map()),
                candidate("gpu-warn-b", 502L, "GPU", "Warn B", 610000, MockData.map()),
                candidate("gpu-pass-a", 503L, "GPU", "Pass A", 620000, MockData.map()),
                candidate("gpu-pass-b", 504L, "GPU", "Pass B", 630000, MockData.map()),
                candidate("gpu-pass-c", 505L, "GPU", "Pass C", 640000, MockData.map())
        ));
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any())).thenAnswer(invocation -> {
            List<ToolBuildPart> parts = invocation.getArgument(1);
            String gpuId = parts.stream()
                    .filter(part -> "GPU".equals(part.category()))
                    .map(ToolBuildPart::publicId)
                    .filter(id -> id != null && id.startsWith("gpu-"))
                    .findFirst()
                    .orElse("");
            return List.of(tool("power", gpuId.contains("warn") ? "WARN" : "PASS", "파워 검사를 마쳤습니다."));
        });

        List<String> accepted = service.compatibleCandidateIds(
                user(), "GPU", "REPLACE",
                List.of("gpu-warn-a", "gpu-warn-b", "gpu-pass-a", "gpu-pass-b", "gpu-pass-c"),
                3);

        assertThat(accepted).containsExactly("gpu-pass-a", "gpu-pass-b", "gpu-pass-c");
        verify(toolCheckService, times(5)).checkBuildTools(anyList(), anyList(), anyInt(), any());
    }

    @Test
    void compatibleCandidateSelectionSkipsCurrentSingleItemOnReplace() {
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftPartRow("psu-current", 301L, "PSU", "Current PSU", 150000, MockData.map("capacityW", 850))
        ));
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("public_id = ?::uuid")),
                any(Object[].class))).thenReturn(List.of(
                candidate("psu-current", 301L, "PSU", "Current PSU", 150000, MockData.map("capacityW", 850)),
                candidate("psu-alternative", 302L, "PSU", "Alternative PSU", 170000, MockData.map("capacityW", 1000))
        ));
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any()))
                .thenReturn(List.of(tool("power", "PASS", "파워 검사를 통과했습니다.")));

        PartCompatibleCandidateService.CompatibleCandidateSelection selection = service.compatibleCandidateSelection(
                user(), "PSU", "REPLACE", List.of("psu-current", "psu-alternative"), 3);

        assertThat(selection.acceptedIds()).containsExactly("psu-alternative");
        assertThat(selection.alreadySelectedIds()).containsExactly("psu-current");
        verify(toolCheckService, times(1)).checkBuildTools(anyList(), anyList(), anyInt(), any());
    }

    @Test
    void compatibleCandidateSelectionReportsAndSkipsCurrentMultiItemOnAddRecommendation() {
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftPartRow("ram-current", 301L, "RAM", "Current RAM", 150000,
                        MockData.map("capacityGb", 32, "moduleCount", 2))
        ));
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("public_id = ?::uuid")),
                any(Object[].class))).thenReturn(List.of(
                candidate("ram-current", 301L, "RAM", "Current RAM", 150000,
                        MockData.map("capacityGb", 32, "moduleCount", 2)),
                candidate("ram-alternative-a", 302L, "RAM", "Alternative RAM A", 170000,
                        MockData.map("capacityGb", 32, "moduleCount", 2)),
                candidate("ram-alternative-b", 303L, "RAM", "Alternative RAM B", 180000,
                        MockData.map("capacityGb", 32, "moduleCount", 2))
        ));
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any()))
                .thenReturn(List.of(tool("compatibility", "PASS", "RAM 검사를 통과했습니다.")));

        PartCompatibleCandidateService.CompatibleCandidateSelection selection = service.compatibleCandidateSelection(
                user(), "RAM", "ADD", List.of("ram-current", "ram-alternative-a", "ram-alternative-b"), 3);

        assertThat(selection.acceptedIds()).containsExactly("ram-alternative-a", "ram-alternative-b");
        assertThat(selection.alreadySelectedIds()).containsExactly("ram-current");
        verify(toolCheckService, times(2)).checkBuildTools(anyList(), anyList(), anyInt(), any());
    }

    @Test
    void candidateLoopPrefetchesBenchmarksOnceInsteadOfPerCandidate() {
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftPartRow("draft-cpu", 301L, "CPU", "Current CPU", 420000, MockData.map()),
                draftPartRow("draft-psu", 302L, "PSU", "Current PSU", 150000, MockData.map("capacityW", 850))
        ));
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("public_id = ?::uuid")),
                any(Object[].class))).thenReturn(List.of(
                candidate("gpu-alternative-a", 501L, "GPU", "Alternative GPU A", 900000, MockData.map("wattage", 250)),
                candidate("gpu-alternative-b", 502L, "GPU", "Alternative GPU B", 950000, MockData.map("wattage", 285))
        ));
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any()))
                .thenReturn(List.of(tool("power", "PASS", "파워 검사를 통과했습니다.")));

        service.compatibleCandidateSelection(
                user(), "GPU", "ADD", List.of("gpu-alternative-a", "gpu-alternative-b"), 3);

        // 핵심 계약: 후보가 몇 개든 벤치마크 배치 로드는 요청당 1회 — 후보별 재조회(N+1) 금지
        verify(toolCheckService, times(1)).loadLatestBenchmarks(anyList());
        verify(toolCheckService, times(2)).checkBuildTools(anyList(), anyList(), anyInt(), any());
    }

    @Test
    void benchmarkPrefetchIsSkippedWhenPerformanceToolIsNotChecked() {
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftPartRow("ram-current", 301L, "RAM", "Current RAM", 150000,
                        MockData.map("capacityGb", 32, "moduleCount", 2))
        ));
        when(jdbcTemplate.queryForList(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("public_id = ?::uuid")),
                any(Object[].class))).thenReturn(List.of(
                candidate("ram-alternative-a", 302L, "RAM", "Alternative RAM A", 170000,
                        MockData.map("capacityGb", 32, "moduleCount", 2))
        ));
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any()))
                .thenReturn(List.of(tool("compatibility", "PASS", "RAM 검사를 통과했습니다.")));

        service.compatibleCandidateSelection(user(), "RAM", "ADD", List.of("ram-alternative-a"), 3);

        // 벤치마크는 performance 툴만 소비한다 — performance를 검사하지 않는 카테고리는 조회 자체가 없다.
        verify(toolCheckService, times(0)).loadLatestBenchmarks(anyList());
    }

    @Test
    void partRowsWithCompatibilityIncludeFailingOptionsForCategoryList() {
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftItem("draft-cpu", 301L, "CPU", "Ryzen 7", 420000, MockData.map("socket", "AM5")),
                draftItem("draft-psu", 302L, "PSU", "650W Bronze", 90000, MockData.map("capacityW", 650)),
                draftItem("draft-case", 303L, "CASE", "Compact Case", 100000, MockData.map("maxGpuLengthMm", 330))
        ));
        List<Map<String, Object>> rows = List.of(
                candidate("gpu-fail", 501L, "GPU", "RTX 5090 Long", 2_900_000, MockData.map("wattage", 575, "lengthMm", 390)),
                candidate("gpu-pass", 502L, "GPU", "RTX 5070 Ti", 990000, MockData.map("wattage", 285, "lengthMm", 310)),
                candidate("gpu-warn", 503L, "GPU", "RTX 5080 Compact", 1_490_000, MockData.map("wattage", 360, "lengthMm", 330))
        );
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any())).thenAnswer(invocation -> {
            List<ToolBuildPart> parts = invocation.getArgument(1);
            String gpuId = parts.stream()
                    .filter(part -> "GPU".equals(part.category()))
                    .findFirst()
                    .map(ToolBuildPart::publicId)
                    .orElse("");
            if ("gpu-fail".equals(gpuId)) {
                return List.of(
                        tool("power", "FAIL", "파워 출력이 부족합니다."),
                        tool("size", "FAIL", "케이스 장착이 불가능합니다."),
                        tool("performance", "PASS", "성능은 충분합니다.")
                );
            }
            if ("gpu-warn".equals(gpuId)) {
                return List.of(
                        tool("power", "WARN", "파워 여유가 낮습니다."),
                        tool("size", "PASS", "케이스 장착이 가능합니다."),
                        tool("performance", "PASS", "성능은 충분합니다.")
                );
            }
            return List.of(
                    tool("power", "PASS", "파워 여유가 충분합니다."),
                    tool("size", "PASS", "케이스 장착이 가능합니다."),
                    tool("performance", "PASS", "성능은 충분합니다.")
            );
        });

        List<Map<String, Object>> items = service.partRowsWithCompatibility(user(), "QUOTE_DRAFT_CURRENT", "GPU", null, null, rows);

        assertThat(items).hasSize(3);
        assertThat(compatibility(items.get(0)).get("status")).isEqualTo("FAIL");
        assertThat(compatibility(items.get(0)).get("statusLabel")).isEqualTo("장착 불가");
        assertThat(compatibility(items.get(1)).get("status")).isEqualTo("PASS");
        assertThat(compatibility(items.get(1)).get("statusLabel")).isEqualTo("호환 가능");
        assertThat(compatibility(items.get(2)).get("status")).isEqualTo("WARN");
        assertThat(compatibility(items.get(2)).get("statusLabel")).isEqualTo("간섭 주의");
    }

    @Test
    void existingRamFailureDoesNotMarkUnrelatedCpuCandidatesAsFail() {
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftItem("draft-cpu", 301L, "CPU", "Current CPU", 420000, MockData.map("socket", "AM5")),
                draftItem("draft-board", 302L, "MOTHERBOARD", "AM5 Board", 250000, MockData.map("socket", "AM5", "memorySlots", 2)),
                draftItem("draft-ram", 303L, "RAM", "Dual DIMM Kit", 180000, MockData.map("memoryType", "DDR5", "moduleCount", 2))
        ));
        List<Map<String, Object>> rows = List.of(
                candidate("cpu-compatible", 501L, "CPU", "Compatible CPU", 450000, MockData.map("socket", "AM5")),
                candidate("cpu-wrong-socket", 502L, "CPU", "Wrong Socket CPU", 460000, MockData.map("socket", "LGA1851"))
        );
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any())).thenAnswer(invocation -> {
            List<ToolBuildPart> parts = invocation.getArgument(1);
            String cpuId = parts.stream()
                    .filter(part -> "CPU".equals(part.category()))
                    .findFirst()
                    .map(ToolBuildPart::publicId)
                    .orElse("");
            boolean socketMatched = "cpu-compatible".equals(cpuId);
            return List.of(tool(
                    "compatibility",
                    "FAIL",
                    socketMatched ? "RAM 스틱 수가 메인보드 슬롯을 초과합니다." : "CPU와 메인보드 소켓이 맞지 않습니다.",
                    MockData.map(
                            "socketMatched", socketMatched,
                            "memoryTypeMatched", true,
                            "coolerSocketMatched", true,
                            "coolerTdpMatched", true,
                            "coolerTdpMarginLow", false,
                            "ramFormFactorMatched", true,
                            "ramSlotsMatched", false,
                            "m2SlotsMatched", true)));
        });

        List<Map<String, Object>> items = service.partRowsWithCompatibility(
                user(), "QUOTE_DRAFT_CURRENT", "CPU", null, null, rows);

        assertThat(compatibility(items.get(0))).containsEntry("status", "PASS");
        assertThat(compatibility(items.get(0)).get("summary")).isEqualTo("현재 조합 기준 호환 가능합니다");
        assertThat(compatibility(items.get(1))).containsEntry("status", "FAIL");
        assertThat(compatibility(items.get(1)).get("summary")).isEqualTo("CPU와 메인보드 소켓이 맞지 않습니다.");
    }

    @Test
    void existingPsuSizeFailureDoesNotMarkFittingGpuCandidateAsFail() {
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftItem("draft-cpu", 301L, "CPU", "Current CPU", 420000, Map.of()),
                draftItem("draft-gpu", 302L, "GPU", "Current GPU", 900000, MockData.map("lengthMm", 300)),
                draftItem("draft-psu", 303L, "PSU", "Long PSU", 150000, MockData.map("capacityW", 1000, "depthMm", 200)),
                draftItem("draft-case", 304L, "CASE", "Compact Case", 120000, MockData.map("maxGpuLengthMm", 360, "maxPsuLengthMm", 180))
        ));
        List<Map<String, Object>> rows = List.of(
                candidate("gpu-fitting", 501L, "GPU", "Fitting GPU", 950000, MockData.map("lengthMm", 320, "wattage", 250))
        );
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any())).thenReturn(List.of(
                tool("power", "PASS", "파워 용량을 충족합니다."),
                tool("size", "FAIL", "파워 깊이가 케이스 허용 길이를 초과합니다.", MockData.map(
                        "gpuLengthMm", 320,
                        "maxGpuLengthMm", 360,
                        "gpuHeadroomMm", 40,
                        "psuDepthMm", 200,
                        "maxPsuLengthMm", 180,
                        "psuHeadroomMm", -20)),
                tool("performance", "PASS", "성능을 충족합니다.")
        ));

        List<Map<String, Object>> items = service.partRowsWithCompatibility(
                user(), "QUOTE_DRAFT_CURRENT", "GPU", null, null, rows);

        assertThat(compatibility(items.get(0))).containsEntry("status", "PASS");
    }

    @Test
    void partRowsWithCompatibilityPreservesApiDtoExternalOfferForCategoryList() {
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftItem("draft-cpu", 301L, "CPU", "Ryzen 7", 420000, MockData.map("socket", "AM5")),
                draftItem("draft-gpu", 302L, "GPU", "RTX 5070", 900000, MockData.map("wattage", 250))
        ));
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any()))
                .thenReturn(List.of(tool("power", "PASS", "파워 후보를 확인했습니다.")));
        Map<String, Object> externalOffer = MockData.map(
                "title", "AONE 컴퓨터 파워 ATX 300W 600T",
                "imageUrl", "https://shopping-phinf.pstatic.net/main_1234567/1234567.jpg",
                "supplierName", "네이버",
                "offerUrl", "https://shopping.naver.com/catalog/1234567",
                "lowPrice", 35500,
                "source", "NAVER_SHOPPING_SEARCH",
                "refreshedAt", "2026-07-02T12:00:00Z"
        );
        List<Map<String, Object>> rows = List.of(MockData.map(
                "id", "psu-image",
                "category", "PSU",
                "name", "AONE 컴퓨터 파워 ATX 300W 600T",
                "manufacturer", "AONE",
                "price", 35500,
                "status", "ACTIVE",
                "attributes", MockData.map("capacityW", 300),
                "externalOffer", externalOffer
        ));

        List<Map<String, Object>> items = service.partRowsWithCompatibility(user(), "QUOTE_DRAFT_CURRENT", "PSU", null, null, rows);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("externalOffer")).isEqualTo(externalOffer);
        assertThat(externalOffer(items.get(0)).get("imageUrl")).isEqualTo("https://shopping-phinf.pstatic.net/main_1234567/1234567.jpg");
        assertThat(externalOffer(items.get(0)).get("supplierName")).isEqualTo("네이버");
        assertThat(externalOffer(items.get(0)).get("offerUrl")).isEqualTo("https://shopping.naver.com/catalog/1234567");
        assertThat(externalOffer(items.get(0)).get("source")).isEqualTo("NAVER_SHOPPING_SEARCH");
        assertThat(compatibility(items.get(0)).get("status")).isEqualTo("PASS");
    }

    @Test
    void addModeKeepsExistingCategoryRowsAndAppendsCandidate() {
        // 감사 P0-6: RAM 만석에서 후보 킷을 '담기' 기준으로 평가 — 기존 행 유지 + 후보 합산.
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftPartRow("draft-ram", 301L, "RAM", "DDR5 킷", 180000, MockData.map("memoryType", "DDR5", "moduleCount", 2)),
                draftPartRow("draft-board", 302L, "MOTHERBOARD", "B850 보드", 250000, MockData.map("memorySlots", 4))
        ));
        List<List<ToolBuildPart>> capturedBuilds = new java.util.ArrayList<>();
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any())).thenAnswer(invocation -> {
            capturedBuilds.add(new java.util.ArrayList<>(invocation.getArgument(1)));
            return List.of(tool("compatibility", "FAIL", "램 스틱 수(4개)가 메인보드 메모리 슬롯(4개)을 초과합니다."));
        });
        List<Map<String, Object>> rows = List.of(
                candidate("ram-candidate", 501L, "RAM", "DDR5 후보 킷", 190000, MockData.map("memoryType", "DDR5", "moduleCount", 2))
        );

        List<Map<String, Object>> items = service.partRowsWithCompatibility(user(), "QUOTE_DRAFT_CURRENT", "RAM", "ADD", null, rows);

        assertThat(capturedBuilds.get(0)).extracting(ToolBuildPart::publicId)
                .containsExactlyInAnyOrder("draft-ram", "draft-board", "ram-candidate");
        assertThat(compatibility(items.get(0)).get("status")).isEqualTo("FAIL");
    }

    @Test
    void addModeIncrementsQuantityWhenCandidateAlreadyExistsInBuild() {
        // 실제 담기 API는 같은 상품을 별도 행으로 복제하지 않고 기존 quantity를 +1 한다.
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftPartRow("draft-ram", 301L, "RAM", "DDR5 킷", 180000, MockData.map("memoryType", "DDR5", "moduleCount", 2))
        ));
        List<List<ToolBuildPart>> capturedBuilds = new java.util.ArrayList<>();
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any())).thenAnswer(invocation -> {
            capturedBuilds.add(new java.util.ArrayList<>(invocation.getArgument(1)));
            return List.of(tool(
                    "compatibility",
                    "FAIL",
                    "RAM 스틱 수가 메인보드 슬롯을 초과합니다.",
                    MockData.map("memoryTypeMatched", true, "ramFormFactorMatched", true, "ramSlotsMatched", false)));
        });
        List<Map<String, Object>> rows = List.of(
                candidate("draft-ram", 301L, "RAM", "DDR5 킷", 180000, MockData.map("memoryType", "DDR5", "moduleCount", 2))
        );

        List<Map<String, Object>> items = service.partRowsWithCompatibility(
                user(), "QUOTE_DRAFT_CURRENT", "RAM", "ADD", null, rows);

        assertThat(capturedBuilds.get(0)).singleElement().satisfies(part -> {
            assertThat(part.publicId()).isEqualTo("draft-ram");
            assertThat(part.effectiveQuantity()).isEqualTo(2);
        });
        assertThat(compatibility(items.get(0))).containsEntry("status", "FAIL");
    }

    @Test
    void replaceModeWithTargetExcludesOnlyTargetRow() {
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftPartRow("draft-ram-a", 301L, "RAM", "DDR5 킷 A", 180000, MockData.map("memoryType", "DDR5", "moduleCount", 2)),
                draftPartRow("draft-ram-b", 302L, "RAM", "DDR5 킷 B", 170000, MockData.map("memoryType", "DDR5", "moduleCount", 2))
        ));
        List<List<ToolBuildPart>> capturedBuilds = new java.util.ArrayList<>();
        when(toolCheckService.checkBuildTools(anyList(), anyList(), anyInt(), any())).thenAnswer(invocation -> {
            capturedBuilds.add(new java.util.ArrayList<>(invocation.getArgument(1)));
            return List.of(tool("compatibility", "PASS", "호환됩니다."));
        });
        List<Map<String, Object>> rows = List.of(
                candidate("ram-candidate", 501L, "RAM", "DDR5 후보 킷", 190000, MockData.map("memoryType", "DDR5", "moduleCount", 2))
        );

        service.partRowsWithCompatibility(user(), "QUOTE_DRAFT_CURRENT", "RAM", "REPLACE", "draft-ram-a", rows);

        // 대상 행(A)만 빠지고 나머지 행(B)은 유지된 채 후보가 더해진다.
        assertThat(capturedBuilds.get(0)).extracting(ToolBuildPart::publicId)
                .containsExactlyInAnyOrder("draft-ram-b", "ram-candidate");
    }

    @Test
    void rejectsUnknownCompatibilityModeAndAddWithTarget() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.partRowsWithCompatibility(user(), "QUOTE_DRAFT_CURRENT", "RAM", "MERGE", null, List.of()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("compatibilityMode");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.partRowsWithCompatibility(user(), "QUOTE_DRAFT_CURRENT", "RAM", "ADD", "draft-ram", List.of()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("replaceTargetPartId");
    }

    private void stubPart(String publicId, Map<String, Object> row) {
        when(jdbcTemplate.queryForList(anyString(), eq(publicId))).thenReturn(List.of(row));
    }

    private static Map<String, Object> item(String partId, String category) {
        return Map.of("partId", partId, "category", category, "quantity", 1);
    }

    private static Map<String, Object> part(String publicId, long internalId, String category, String name, int price, Map<String, Object> attributes) {
        return MockData.map(
                "internal_id", internalId,
                "id", publicId,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "price", price,
                "status", "ACTIVE",
                "attributes", attributes
        );
    }

    private static Map<String, Object> candidate(String publicId, long internalId, String category, String name, int price, Map<String, Object> attributes) {
        return part(publicId, internalId, category, name, price, attributes);
    }

    private static Map<String, Object> activeDraft() {
        return Map.of(
                "internal_id", 700L,
                "id", "draft-public-id",
                "status", "ACTIVE",
                "name", "셀프 견적"
        );
    }

    /** currentQuoteDraftParts의 실 SQL 형태(p.public_id AS id) 그대로의 드래프트 행 — 대상 매칭 테스트용. */
    private static Map<String, Object> draftPartRow(String publicId, long internalId, String category, String name, int price, Map<String, Object> attributes) {
        return MockData.map(
                "internal_id", internalId,
                "id", publicId,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "current_price", price,
                "price", price,
                "quantity", 1,
                "attributes", attributes
        );
    }

    private static Map<String, Object> draftItem(String publicId, long internalId, String category, String name, int price, Map<String, Object> attributes) {
        return MockData.map(
                "internal_id", internalId,
                "part_id", publicId,
                "id", "draft-item-" + category,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "current_price", price,
                "quantity", 1,
                "attributes", attributes
        );
    }

    private static Map<String, Object> tool(String tool, String status, String summary) {
        return MockData.map("tool", tool, "status", status, "confidence", "MEDIUM", "summary", summary, "details", Map.of());
    }

    private static Map<String, Object> tool(String tool, String status, String summary, Map<String, Object> details) {
        return MockData.map("tool", tool, "status", status, "confidence", "MEDIUM", "summary", summary, "details", details);
    }

    private static CurrentUserService.CurrentUser user() {
        return new CurrentUserService.CurrentUser(
                1004L,
                "00000000-0000-4000-8000-000000001004",
                "user@example.com",
                "Demo User",
                "USER",
                "2026-06-30T00:00:00Z"
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> part(Map<String, Object> candidate) {
        return (Map<String, Object>) candidate.get("part");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> externalOffer(Map<String, Object> part) {
        return (Map<String, Object>) part.get("externalOffer");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compatibility(Map<String, Object> part) {
        return (Map<String, Object>) part.get("compatibility");
    }
}
