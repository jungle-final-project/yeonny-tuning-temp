package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.query.PartQuery;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.part.tool.ToolCheckService;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class BuildGraphServiceTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ToolCheckService toolCheckService = mock(ToolCheckService.class);
    private final BuildGraphLayoutService buildGraphLayoutService = mock(BuildGraphLayoutService.class);
    private final BuildCompositeScoreService buildCompositeScoreService = new BuildCompositeScoreService();
    private final PartQuery partQuery = mock(PartQuery.class);
    private final Map<String, ToolBuildPart> stubParts = new LinkedHashMap<>();
    private final BuildGraphService buildGraphService = new BuildGraphService(
            partQuery,
            buildGraphLayoutService,
            new BuildEvaluationService(partQuery, toolCheckService, buildCompositeScoreService, new BuildScoreAdviceService(), null)
    );

    BuildGraphServiceTest() {
        when(partQuery.partsByPublicIds(anyList())).thenAnswer(invocation -> {
            List<String> requestedIds = invocation.getArgument(0);
            return requestedIds.stream().map(partId -> {
                ToolBuildPart part = stubParts.get(partId);
                if (part == null) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "활성 부품을 찾을 수 없습니다.");
                }
                return part;
            }).toList();
        });
    }

    @Test
    void aiBuildGraphShowsCoreDependenciesAndWarnsForPowerHeadroom() {
        stubPart("part-cpu", part("part-cpu", 101L, "CPU", "Ryzen 7", 420000, MockData.map("socket", "AM5", "tdpW", 120)));
        stubPart("part-board", part("part-board", 102L, "MOTHERBOARD", "B650 Board", 260000, MockData.map("socket", "AM5", "memoryType", "DDR5", "wifi", "Wi-Fi 7")));
        stubPart("part-ram", part("part-ram", 103L, "RAM", "DDR5 32GB", 140000, MockData.map("memoryType", "DDR5", "capacityGb", 32, "moduleCount", 2)));
        stubPart("part-gpu", part("part-gpu", 104L, "GPU", "RTX 5070", 890000, MockData.map("wattage", 250, "requiredSystemPowerW", 750, "lengthMm", 304)));
        stubPart("part-psu", part("part-psu", 105L, "PSU", "850W Gold", 150000, MockData.map("capacityW", 850)));
        stubPart("part-case", part("part-case", 106L, "CASE", "Compact Case", 110000, MockData.map("maxGpuLengthMm", 320, "maxCpuCoolerHeightMm", 160)));
        stubPart("part-cooler", part("part-cooler", 107L, "COOLER", "AM5 Cooler", 80000, MockData.map("socketSupport", List.of("AM5"), "heightMm", 155)));
        when(toolCheckService.checkBuild(anyList(), eq(2_000_000))).thenReturn(List.of(
                tool("compatibility", "PASS", "CPU, 메인보드, RAM, 쿨러 기본 호환성이 맞습니다.",
                        MockData.map("socketMatched", true, "memoryTypeMatched", true, "coolerSocketMatched", true)),
                tool("power", "WARN", "PSU 정격 출력 여유가 낮습니다.",
                        MockData.map("requiredRatedCapacityW", 750, "psuRatedCapacityW", 850, "ratedHeadroomW", 100, "vendorRecommendedPsuW", 750)),
                tool("size", "WARN", "GPU 장착 여유가 낮아 추가 확인이 필요합니다.",
                        MockData.map("gpuLengthMm", 304, "maxGpuLengthMm", 320, "coolerHeightMm", 155, "maxCpuCoolerHeightMm", 160)),
                tool("performance", "PASS", "요구 작업에 무리가 적은 조합입니다.", MockData.map("gpu", "RTX 5070", "cpu", "Ryzen 7")),
                tool("price", "PASS", "저장된 현재가 기준 예산 안에 들어옵니다.",
                        MockData.map("budget", 2000000, "totalPrice", 1990000, "priceDiff", -10000))
        ));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "view", "FOCUSED",
                "budgetWon", 2_000_000,
                "items", List.of(
                        requestItem("part-cpu", "CPU"),
                        requestItem("part-board", "MOTHERBOARD"),
                        requestItem("part-ram", "RAM"),
                        requestItem("part-gpu", "GPU"),
                        requestItem("part-psu", "PSU"),
                        requestItem("part-case", "CASE"),
                        requestItem("part-cooler", "COOLER")
                ),
                "focus", Map.of("mode", "PART_IMPACT", "category", "GPU", "tool", "power")
        ));

        assertThat(graph.get("mode")).isEqualTo("PART_IMPACT");
        assertThat(castMap(graph.get("compositeScore")).get("score")).isInstanceOf(Integer.class);
        assertThat(castMap(graph.get("buildAssessment")))
                .containsEntry("type", "COMPOSITE_SCORE_EXPLANATION")
                .containsEntry("score", castMap(graph.get("compositeScore")).get("score"));
        assertThat((String) graph.get("summary")).contains("GPU");
        List<Map<String, Object>> nodes = castList(graph.get("nodes"));
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-CPU");
            assertThat(node.get("detail")).isEqualTo("소켓 AM5");
        });
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-MOTHERBOARD");
            assertThat(node.get("detail")).isEqualTo("AM5 · DDR5 · Wi-Fi 7 · Bluetooth");
        });
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-RAM");
            assertThat(node.get("detail")).isEqualTo("DDR5 · 32GB · 2개");
        });
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-GPU");
            assertThat(node.get("detail")).isEqualTo("250W · 길이 304mm");
        });
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-PSU");
            assertThat(node.get("detail")).isEqualTo("정격 850W");
        });
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-CASE");
            assertThat(node.get("detail")).isEqualTo("GPU 최대 320mm");
        });
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-COOLER");
            assertThat(node.get("detail")).isEqualTo("높이 155mm");
        });
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("constraint-compatibility");
            assertThat(node.get("label")).isEqualTo("B650 Board");
        });
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("constraint-power");
            assertThat(node.get("label")).isEqualTo("정격 850W");
        });
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("constraint-size");
            assertThat(node.get("label")).isEqualTo("Compact Case");
        });
        List<Map<String, Object>> edges = castList(graph.get("edges"));
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-gpu-psu-power");
            assertThat(edge.get("status")).isEqualTo("WARN");
            assertThat(edge.get("label")).isEqualTo("전력 여유 100W");
            assertThat(edge.get("summary")).isEqualTo("GPU 권장 파워 750W / 현재 파워 850W입니다. 지속 부하 대비 여유 100W로 장착은 가능하지만 여유가 넉넉하지 않습니다.");
        });
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-gpu-case-length");
            assertThat(edge.get("label")).isEqualTo("길이 간섭 주의");
            assertThat(edge.get("summary")).isEqualTo("GPU 길이 304mm / 케이스 허용 320mm입니다. 여유 16mm로 장착은 가능하지만 간섭을 주의해야 합니다.");
        });
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-cooler-case-height");
            assertThat(edge.get("status")).isEqualTo("PASS");
            assertThat(edge.get("label")).isEqualTo("높이 여유 5mm");
            assertThat(edge.get("summary")).isEqualTo("쿨러 높이 155mm / 케이스 허용 160mm입니다. 여유 5mm입니다.");
        });
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-cpu-board-socket");
            assertThat(edge.get("label")).isEqualTo("소켓 일치");
        });
        List<Map<String, Object>> insights = castList(graph.get("insights"));
        assertThat(insights).anySatisfy(insight -> {
            assertThat(insight.get("title")).isEqualTo("파워 여유 확인");
            assertThat(insight.get("status")).isEqualTo("WARN");
        });
    }

    @Test
    void coolerSpecDetailDoesNotBecomeWarningWhenToolHeadroomPolicyPasses() {
        stubPart("fit-case", part("fit-case", 701L, "CASE", "Height Fit Case", 150000,
                MockData.map("maxCpuCoolerHeightMm", 165)));
        stubPart("fit-cooler", part("fit-cooler", 702L, "COOLER", "157mm Air Cooler", 90000,
                MockData.map("heightMm", 157)));
        when(toolCheckService.checkBuild(anyList(), eq(240_000))).thenReturn(List.of(
                tool("size", "PASS", "쿨러 높이가 케이스 제약 안에 있습니다.",
                        MockData.map("coolerHeightMm", 157, "maxCpuCoolerHeightMm", 165))
        ));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "budgetWon", 240_000,
                "items", List.of(
                        requestItem("fit-case", "CASE"),
                        requestItem("fit-cooler", "COOLER")
                )
        ));

        assertThat(castList(graph.get("edges"))).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-cooler-case-height");
            assertThat(edge.get("status")).isEqualTo("PASS");
            assertThat(edge.get("label")).isEqualTo("높이 여유 8mm");
            assertThat(edge.get("summary")).isEqualTo("쿨러 높이 157mm / 케이스 허용 165mm입니다. 여유 8mm입니다.");
        });
        assertThat(castList(graph.get("nodes"))).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-COOLER");
            assertThat(node.get("status")).isEqualTo("PASS");
            assertThat(node.get("detail")).isEqualTo("높이 157mm");
        });
    }

    @Test
    void ramSlotOverflowFailsMemoryEdgeAndPartNodes() {
        // 2개들이 킷 초과 시나리오: 스틱 4개 > 보드 2슬롯. 엣지가 FAIL이면 걸린 부품 노드(카드 뱃지)도 FAIL이어야 한다.
        stubPart("slot-board", part("slot-board", 501L, "MOTHERBOARD", "2슬롯 ITX 보드", 250000, MockData.map("socket", "AM5", "memoryType", "DDR5", "memorySlots", 2)));
        stubPart("slot-ram", part("slot-ram", 502L, "RAM", "32GB 2개들이 킷", 180000, MockData.map("memoryType", "DDR5", "moduleCount", 2)));
        when(toolCheckService.checkBuild(anyList(), eq(430_000))).thenReturn(List.of(
                tool("compatibility", "FAIL", "램 스틱 수(4개)가 메인보드 메모리 슬롯(2개)을 초과합니다.",
                        MockData.map(
                                "socketMatched", true,
                                "memoryTypeMatched", true,
                                "coolerSocketMatched", true,
                                "ramSticksTotal", 4,
                                "memorySlots", 2,
                                "ramSlotsChecked", true,
                                "ramSlotsMatched", false))
        ));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "view", "FULL",
                "budgetWon", 430_000,
                "items", List.of(
                        requestItem("slot-board", "MOTHERBOARD"),
                        requestItem("slot-ram", "RAM")
                )
        ));

        List<Map<String, Object>> edges = castList(graph.get("edges"));
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-board-ram-memory");
            assertThat(edge.get("status")).isEqualTo("FAIL");
            assertThat(edge.get("label")).isEqualTo("메모리 슬롯");
            assertThat((String) edge.get("summary")).contains("RAM 스틱 4개").contains("메모리 슬롯 2개");
        });
        List<Map<String, Object>> nodes = castList(graph.get("nodes"));
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-RAM");
            assertThat(node.get("status")).isEqualTo("FAIL");
        });
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-MOTHERBOARD");
            assertThat(node.get("status")).isEqualTo("FAIL");
        });
    }

    @Test
    void aiBuildGraphPowerEdgeFollowsToolStatusEvenWhenPsuBelowRequiredRatedCapacity() {
        // 사용자 시나리오: RTX 5090(GPU 권장 1000W) + 1000W PSU. 툴은 권장 파워를 충족했으므로 WARN을 준다.
        // 예전에는 엣지가 psuRatedCapacity - requiredRatedCapacity(=1000-1020=-20) headroom으로 별도 재계산해
        // FAIL(빨강)이 떴다. 이제 엣지는 파워 툴 status(WARN)를 단일 소스로 그대로 따라야 한다.
        stubPart("edge-gpu", part("edge-gpu", 401L, "GPU", "RTX 5090", 3980000, MockData.map("wattage", 575, "requiredSystemPowerW", 1000, "lengthMm", 340)));
        stubPart("edge-psu", part("edge-psu", 402L, "PSU", "1000W Gold", 200000, MockData.map("capacityW", 1000)));
        when(toolCheckService.checkBuild(anyList(), eq(5_000_000))).thenReturn(List.of(
                tool("power", "WARN", "PSU 정격 출력이 GPU 권장 파워는 충족하지만 여유가 넉넉하지 않습니다.",
                        MockData.map("requiredRatedCapacityW", 1020, "psuRatedCapacityW", 1000, "ratedHeadroomW", 100, "vendorRecommendedPsuW", 1000))
        ));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "budgetWon", 5_000_000,
                "items", List.of(
                        requestItem("edge-gpu", "GPU"),
                        requestItem("edge-psu", "PSU")
                )
        ));

        List<Map<String, Object>> edges = castList(graph.get("edges"));
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-gpu-psu-power");
            assertThat(edge.get("status")).isEqualTo("WARN");
            assertThat(edge.get("summary")).isEqualTo("GPU 권장 파워 1000W / 현재 파워 1000W입니다. 지속 부하 대비 여유 100W로 장착은 가능하지만 여유가 넉넉하지 않습니다.");
        });
    }

    @Test
    void aiBuildGraphLabelsFailedSocketPowerAndCaseRelationships() {
        stubPart("bad-cpu", part("bad-cpu", 201L, "CPU", "Ryzen 7", 420000, MockData.map("socket", "AM5")));
        stubPart("bad-board", part("bad-board", 202L, "MOTHERBOARD", "Z890 Board", 260000, MockData.map("socket", "LGA1851", "memoryType", "DDR5", "hasWifi", true)));
        stubPart("bad-gpu", part("bad-gpu", 203L, "GPU", "RTX 5090", 3980000, MockData.map("wattage", 575, "lengthMm", 380)));
        stubPart("bad-psu", part("bad-psu", 204L, "PSU", "650W Bronze", 90000, MockData.map("capacityW", 650)));
        stubPart("bad-case", part("bad-case", 205L, "CASE", "Small Case", 110000, MockData.map("maxGpuLengthMm", 360, "maxCpuCoolerHeightMm", 150)));
        stubPart("bad-cooler", part("bad-cooler", 206L, "COOLER", "Tall Cooler", 80000, MockData.map("socketSupport", List.of("LGA1851"), "heightMm", 170)));
        when(toolCheckService.checkBuild(anyList(), eq(5_000_000))).thenReturn(List.of(
                tool("compatibility", "FAIL", "호환되지 않습니다.",
                        MockData.map("socketMatched", false, "memoryTypeMatched", true, "coolerSocketMatched", false)),
                tool("power", "FAIL", "파워 출력이 부족합니다.",
                        MockData.map("requiredRatedCapacityW", 850, "psuRatedCapacityW", 650, "ratedHeadroomW", 20)),
                tool("size", "FAIL", "케이스 장착이 불가능합니다.",
                        MockData.map("gpuLengthMm", 380, "maxGpuLengthMm", 360, "coolerHeightMm", 170, "maxCpuCoolerHeightMm", 150))
        ));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "budgetWon", 5_000_000,
                "items", List.of(
                        requestItem("bad-cpu", "CPU"),
                        requestItem("bad-board", "MOTHERBOARD"),
                        requestItem("bad-gpu", "GPU"),
                        requestItem("bad-psu", "PSU"),
                        requestItem("bad-case", "CASE"),
                        requestItem("bad-cooler", "COOLER")
                )
        ));

        Map<String, Object> compositeScore = castMap(graph.get("compositeScore"));
        assertThat(compositeScore.get("score")).isEqualTo(0);
        assertThat(compositeScore.get("label")).isEqualTo("구성 재검토");
        assertThat(castMap(graph.get("buildAssessment"))).containsEntry("score", 0);
        assertThat(castList(castMap(graph.get("buildAssessment")).get("cautions")))
                .first()
                .extracting(item -> item.get("severity"))
                .isEqualTo("FAIL");
        List<Map<String, Object>> edges = castList(graph.get("edges"));
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-cpu-board-socket");
            assertThat(edge.get("label")).isEqualTo("소켓 불일치");
            assertThat(edge.get("summary")).isEqualTo("CPU 소켓 AM5 / 메인보드 소켓 LGA1851입니다. 메인보드 소켓이 CPU와 맞지 않습니다.");
        });
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-gpu-psu-power");
            assertThat(edge.get("label")).isEqualTo("파워 부족");
        });
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-gpu-case-length");
            assertThat(edge.get("label")).isEqualTo("장착 불가");
            assertThat(edge.get("summary")).isEqualTo("GPU 길이 380mm / 케이스 허용 360mm입니다. 그래픽카드 길이가 케이스 허용 길이를 초과합니다.");
        });
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-cooler-case-height");
            assertThat(edge.get("label")).isEqualTo("높이 간섭");
            assertThat(edge.get("summary")).isEqualTo("쿨러 높이 170mm / 케이스 허용 150mm입니다. 쿨러 높이가 케이스 허용 높이를 초과합니다.");
        });
    }

    @Test
    void quoteDraftGraphReadsCurrentUserDraftWithoutClientItems() {
        // 호출처가 넘긴 CurrentUser의 internalId(1004L)로 draft를 읽어야 한다 — 서비스 내 재인증 없음.
        when(partQuery.partsByActiveDraftUserId(1004L)).thenReturn(List.of(
                new ToolBuildPart(100L, "part-gpu", "GPU", "RTX 5070", "BuildGraph", 890000,
                        MockData.map("wattage", 250, "requiredSystemPowerW", 750, "lengthMm", 304), 1),
                new ToolBuildPart(101L, "part-psu", "PSU", "750W Gold", "BuildGraph", 150000,
                        MockData.map("capacityW", 750), 1),
                new ToolBuildPart(102L, "part-case", "CASE", "Airflow Case", "BuildGraph", 160000,
                        MockData.map("maxGpuLengthMm", 360, "maxCpuCoolerHeightMm", 170), 1)
        ));
        when(toolCheckService.checkBuild(anyList(), eq(1_200_000))).thenReturn(List.of(tool("price", "PASS", "확인되었습니다.", Map.of())));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "QUOTE_DRAFT_CURRENT",
                "view", "FOCUSED",
                "focus", Map.of("mode", "ISSUE_PATH")
        ));

        verify(partQuery).partsByActiveDraftUserId(1004L);
        List<Map<String, Object>> nodes = castList(graph.get("nodes"));
        assertThat(nodes).extracting(node -> node.get("id")).contains("part-GPU", "part-PSU", "part-CASE");
        assertThat(graph.get("mode")).isEqualTo("ISSUE_PATH");
    }

    @Test
    void resolvedNodesIncludeAdminSavedLayoutPositionByCategory() {
        stubPart("layout-cpu", part("layout-cpu", 301L, "CPU", "Ryzen 7", 420000, MockData.map("socket", "AM5")));
        stubPart("layout-gpu", part("layout-gpu", 302L, "GPU", "RTX 5070", 890000, MockData.map("wattage", 250)));
        when(toolCheckService.checkBuild(anyList(), eq(1_310_000))).thenReturn(List.of());
        when(buildGraphLayoutService.resolvePositions()).thenReturn(Map.of(
                "CPU", new BuildGraphLayoutService.GraphPosition(880, 120),
                "GPU", new BuildGraphLayoutService.GraphPosition(420, 360)
        ));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "items", List.of(
                        requestItem("layout-cpu", "CPU"),
                        requestItem("layout-gpu", "GPU")
                )
        ));

        List<Map<String, Object>> nodes = castList(graph.get("nodes"));
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-CPU");
            assertThat(node.get("position")).isEqualTo(Map.of("x", 880, "y", 120));
        });
        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-GPU");
            assertThat(node.get("position")).isEqualTo(Map.of("x", 420, "y", 360));
        });
    }

    @Test
    void aiBuildQuantityCountsIntoRamStickOverflow() {
        // B1 회귀: AI_BUILD resolve가 item.quantity를 버리면 2개들이 킷 × 수량 2(스틱 4 > 슬롯 2)가
        // PASS(초록)로 둔갑한다. 실제 ToolCheckService로 스틱 합산까지 관통 검증한다.
        stubPart("qty-board", part("qty-board", 801L, "MOTHERBOARD", "2슬롯 보드", 250000,
                MockData.map("socket", "AM5", "memoryType", "DDR5", "memorySlots", 2)));
        stubPart("qty-ram", part("qty-ram", 802L, "RAM", "32GB 2개들이 킷", 180000,
                MockData.map("memoryType", "DDR5", "moduleCount", 2)));
        BuildGraphService realToolService = new BuildGraphService(
                partQuery, buildGraphLayoutService,
                new BuildEvaluationService(partQuery, new ToolCheckService(jdbcTemplate), buildCompositeScoreService, new BuildScoreAdviceService(), null));

        Map<String, Object> graph = realToolService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "items", List.of(
                        Map.of("partId", "qty-board", "category", "MOTHERBOARD", "quantity", 1),
                        Map.of("partId", "qty-ram", "category", "RAM", "quantity", 2)
                )
        ));

        List<Map<String, Object>> edges = castList(graph.get("edges"));
        assertThat(edges).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-board-ram-memory");
            assertThat(edge.get("status")).isEqualTo("FAIL");
            assertThat((String) edge.get("summary")).contains("RAM 스틱 4개").contains("슬롯 2개");
        });
        // 사유별 분리 인사이트: 걸린 조건(스틱 초과) 문장이 연루 부품(RAM/보드)에만 tool 필드와 함께 귀속된다.
        List<Map<String, Object>> insights = castList(graph.get("insights"));
        assertThat(insights).anySatisfy(insight -> {
            assertThat(insight.get("tool")).isEqualTo("compatibility");
            assertThat(insight.get("status")).isEqualTo("FAIL");
            assertThat((String) insight.get("description")).contains("RAM 스틱 수(4개)");
            assertThat(castStringList(insight.get("relatedNodeIds")))
                    .containsExactlyInAnyOrder("part-RAM", "part-MOTHERBOARD");
        });
    }

    @Test
    void aiBuildNonNumericOrMissingQuantityDefaultsToSingle() {
        stubPart("qty-board", part("qty-board", 801L, "MOTHERBOARD", "2슬롯 보드", 250000,
                MockData.map("socket", "AM5", "memoryType", "DDR5", "memorySlots", 2)));
        stubPart("qty-ram", part("qty-ram", 802L, "RAM", "32GB 2개들이 킷", 180000,
                MockData.map("memoryType", "DDR5", "moduleCount", 2)));
        BuildGraphService realToolService = new BuildGraphService(
                partQuery, buildGraphLayoutService,
                new BuildEvaluationService(partQuery, new ToolCheckService(jdbcTemplate), buildCompositeScoreService, new BuildScoreAdviceService(), null));

        Map<String, Object> graph = realToolService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "items", List.of(
                        Map.of("partId", "qty-board", "category", "MOTHERBOARD"),
                        Map.of("partId", "qty-ram", "category", "RAM", "quantity", "한가득")
                )
        ));

        // 비숫자/누락 quantity는 1개로 방어한다 — 스틱 2 ≤ 슬롯 2라 PASS.
        assertThat(castList(graph.get("edges"))).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-board-ram-memory");
            assertThat(edge.get("status")).isEqualTo("PASS");
        });
    }

    @Test
    void insightsSplitPerIssueAndAttributeOnlyMountedParts() {
        stubPart("iss-cpu", part("iss-cpu", 811L, "CPU", "Ryzen 7", 420000, MockData.map("socket", "AM5", "tdpW", 120)));
        stubPart("iss-cooler", part("iss-cooler", 812L, "COOLER", "저가 쿨러", 30000, MockData.map("socketSupport", List.of("AM5"), "tdpW", 65)));
        when(toolCheckService.checkBuild(anyList(), eq(450_000))).thenReturn(List.of(
                tool("compatibility", "FAIL", "쿨러 TDP 65W가 CPU TDP 120W에 못 미쳐 냉각이 부족합니다",
                        MockData.map(
                                "socketMatched", true,
                                "memoryTypeMatched", true,
                                "coolerSocketMatched", true,
                                "issueCategories", List.of("COOLER", "CPU", "RAM"),
                                "issues", List.of(
                                        MockData.map("categories", List.of("COOLER", "CPU"), "message", "쿨러 TDP 65W가 CPU TDP 120W에 못 미쳐 냉각이 부족합니다", "status", "FAIL"),
                                        // 장착되지 않은 부품(RAM/MOTHERBOARD)은 인사이트 귀속에서 걸러져야 한다.
                                        MockData.map("categories", List.of("RAM", "MOTHERBOARD"), "message", "메모리 규격 정보가 없어 검사를 못 했습니다", "status", "WARN")
                                )))
        ));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "budgetWon", 450_000,
                "items", List.of(
                        requestItem("iss-cpu", "CPU"),
                        requestItem("iss-cooler", "COOLER")
                )
        ));

        List<Map<String, Object>> insights = castList(graph.get("insights"));
        assertThat(insights).anySatisfy(insight -> {
            assertThat(insight.get("id")).isEqualTo("insight-compatibility-1");
            assertThat(insight.get("tool")).isEqualTo("compatibility");
            assertThat(insight.get("status")).isEqualTo("FAIL");
            assertThat(insight.get("title")).isEqualTo("호환성 확인");
            assertThat(insight.get("description")).isEqualTo("쿨러 TDP 65W가 CPU TDP 120W에 못 미쳐 냉각이 부족합니다");
            assertThat(castStringList(insight.get("relatedNodeIds"))).containsExactlyInAnyOrder("part-COOLER", "part-CPU");
        });
        assertThat(insights).anySatisfy(insight -> {
            assertThat(insight.get("id")).isEqualTo("insight-compatibility-2");
            assertThat(insight.get("status")).isEqualTo("WARN");
            assertThat(castStringList(insight.get("relatedNodeIds"))).isEmpty();
        });
    }

    @Test
    void summaryCountsProblemEdgesInsteadOfProblemTools() {
        // B5b: size 툴 하나가 FAIL 엣지 2개(GPU 길이·쿨러 높이)를 만들면 "확인이 필요한 관계 2개"라고 말해야 한다.
        stubPart("cnt-gpu", part("cnt-gpu", 821L, "GPU", "RTX 5090", 3980000, MockData.map("wattage", 575, "lengthMm", 380)));
        stubPart("cnt-cooler", part("cnt-cooler", 822L, "COOLER", "Tall Cooler", 80000, MockData.map("heightMm", 170)));
        stubPart("cnt-case", part("cnt-case", 823L, "CASE", "Small Case", 110000, MockData.map("maxGpuLengthMm", 360, "maxCpuCoolerHeightMm", 150)));
        when(toolCheckService.checkBuild(anyList(), eq(5_000_000))).thenReturn(List.of(
                tool("size", "FAIL", "GPU 길이(380mm)가 케이스 허용(360mm)을 초과합니다 · 쿨러 높이(170mm)가 케이스 허용(150mm)을 초과합니다",
                        MockData.map("gpuLengthMm", 380, "maxGpuLengthMm", 360, "coolerHeightMm", 170, "maxCpuCoolerHeightMm", 150)),
                tool("price", "PASS", "저장된 현재가 기준 예산 안에 들어옵니다.", MockData.map("budget", 5000000, "totalPrice", 4170000, "priceDiff", -830000))
        ));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "budgetWon", 5_000_000,
                "items", List.of(
                        requestItem("cnt-gpu", "GPU"),
                        requestItem("cnt-cooler", "COOLER"),
                        requestItem("cnt-case", "CASE")
                )
        ));

        assertThat(graph.get("summary")).isEqualTo("추천 조합에서 확인이 필요한 관계 2개를 표시했습니다.");
    }

    @Test
    void powerSummaryWithoutGpuDoesNotClaimGpuRecommendation() {
        // B7: GPU가 없어 vendorRecommendedPsuW=0이면 내부 추정치(부하+120)를 'GPU 권장 파워'로 부르지 않는다.
        stubPart("nogpu-psu", part("nogpu-psu", 831L, "PSU", "600W Bronze", 70000, MockData.map("capacityW", 600)));
        when(toolCheckService.checkBuild(anyList(), eq(70_000))).thenReturn(List.of(
                tool("power", "PASS", "PSU 정격 출력이 예상 지속 부하와 GPU 권장 정격 파워를 충족합니다.",
                        MockData.map(
                                "estimatedContinuousLoadW", 211,
                                "psuRatedCapacityW", 600,
                                "vendorRecommendedPsuW", 0,
                                "requiredRatedCapacityW", 331,
                                "ratedHeadroomW", 389))
        ));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "budgetWon", 70_000,
                "items", List.of(requestItem("nogpu-psu", "PSU"))
        ));

        assertThat(castList(graph.get("nodes"))).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("constraint-power");
            assertThat((String) node.get("detail"))
                    .contains("필요 정격(추정) 331W")
                    .doesNotContain("GPU 권장 파워");
        });
    }

    @Test
    void powerEdgeWithMissingPsuCapacityDoesNotFabricateZeroWatt() {
        // PSU 용량 결측은 details가 null로 내려온다 — "현재 파워 0W" 같은 허구 숫자를 만들지 않는다.
        stubPart("cap-gpu", part("cap-gpu", 841L, "GPU", "RTX 5070", 890000, MockData.map("wattage", 250, "requiredSystemPowerW", 750)));
        stubPart("cap-psu", part("cap-psu", 842L, "PSU", "용량 미표기 PSU", 90000, MockData.map()));
        when(toolCheckService.checkBuild(anyList(), eq(980_000))).thenReturn(List.of(
                tool("power", "WARN", "파워 용량 정보가 없어 전력 검사를 못 했습니다",
                        MockData.map(
                                "estimatedContinuousLoadW", 320,
                                "psuRatedCapacityW", null,
                                "vendorRecommendedPsuW", 750,
                                "requiredRatedCapacityW", 750,
                                "ratedHeadroomW", null,
                                "ratedLoadPercent", null))
        ));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "budgetWon", 980_000,
                "items", List.of(
                        requestItem("cap-gpu", "GPU"),
                        requestItem("cap-psu", "PSU")
                )
        ));

        assertThat(castList(graph.get("edges"))).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-gpu-psu-power");
            assertThat(edge.get("status")).isEqualTo("WARN");
            assertThat(edge.get("label")).isEqualTo("파워 용량 미확인");
            assertThat((String) edge.get("summary"))
                    .isEqualTo("파워 용량 정보가 없어 전력 검사를 못 했습니다")
                    .doesNotContain("0W");
        });
    }

    @Test
    void sataOnlyStorageEdgeStaysPassWithoutGhostWarning() {
        // SATA SSD만 담긴 정상 구성(M.2 저장장치 0개)은 '미검사=WARN'이 아니라 검사할 것 없음(PASS)이다.
        stubPart("sata-board", part("sata-board", 851L, "MOTHERBOARD", "B850 Board", 250000, MockData.map("socket", "AM5", "memoryType", "DDR5", "m2Slots", 2)));
        stubPart("sata-ssd", part("sata-ssd", 852L, "STORAGE", "SATA 2.5인치 SSD", 90000, MockData.map("interface", "SATA", "capacityGb", 1024)));
        when(toolCheckService.checkBuild(anyList(), eq(340_000))).thenReturn(List.of(
                tool("compatibility", "PASS", "CPU, 메인보드, RAM, 쿨러 기본 호환성이 맞습니다.",
                        MockData.map(
                                "socketMatched", true,
                                "memoryTypeMatched", true,
                                "coolerSocketMatched", true,
                                "m2StorageTotal", 0,
                                "m2Slots", 2,
                                "m2SlotsChecked", false,
                                "m2SlotsMatched", true))
        ));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "budgetWon", 340_000,
                "items", List.of(
                        requestItem("sata-board", "MOTHERBOARD"),
                        requestItem("sata-ssd", "STORAGE")
                )
        ));

        assertThat(castList(graph.get("edges"))).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-board-storage-m2");
            assertThat(edge.get("status")).isEqualTo("PASS");
            assertThat(edge.get("label")).isEqualTo("M.2 슬롯 미사용");
            assertThat(edge.get("summary")).isEqualTo("선택한 저장장치는 M.2 슬롯을 사용하지 않습니다.");
        });
    }

    @Test
    void aioCoolerNodeDetailShowsRadiatorSizeInsteadOfThickness() {
        // 수랭 쿨러의 heightMm(라디에이터 두께 38mm)를 "높이 38mm"로 표기하지 않는다.
        stubPart("aio-cooler", part("aio-cooler", 861L, "COOLER", "360mm 수랭", 180000,
                MockData.map("coolerType", "LIQUID_AIO", "heightMm", 38, "radiatorSizeMm", 360, "socketSupport", List.of("AM5"))));
        when(toolCheckService.checkBuild(anyList(), eq(180_000))).thenReturn(List.of());

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "budgetWon", 180_000,
                "items", List.of(requestItem("aio-cooler", "COOLER"))
        ));

        assertThat(castList(graph.get("nodes"))).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-COOLER");
            assertThat(node.get("detail")).isEqualTo("라디에이터 360mm");
        });

        // 라디에이터 크기 결측이면 두께를 높이처럼 쓰지 않고 소켓 지원 표기로 폴백한다.
        stubPart("aio-noradiator", part("aio-noradiator", 862L, "COOLER", "크기 미표기 수랭", 150000,
                MockData.map("coolerType", "LIQUID", "heightMm", 38, "socketSupport", List.of("AM5"))));
        when(toolCheckService.checkBuild(anyList(), eq(150_000))).thenReturn(List.of());

        Map<String, Object> fallbackGraph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "budgetWon", 150_000,
                "items", List.of(requestItem("aio-noradiator", "COOLER"))
        ));

        assertThat(castList(fallbackGraph.get("nodes"))).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-COOLER");
            assertThat(node.get("detail")).isEqualTo("AM5 지원");
        });
    }

    @Test
    void memoryEdgeIsWarnWhenTypeUnchecked() {
        // 규격 정보 결측으로 검사를 생략한 RAM-보드 관계는 초록(근거 없는 통과)도 빨강도 아닌
        // WARN(확인 필요)이다 — PENDING은 공유 소비처(BuildDependencyGraph)의 PASS/WARN/FAIL 계약 밖이라
        // '호환 가능'(초록)으로 폴백 렌더되는 부작용이 있어 쓰지 않는다.
        stubPart("pend-board", part("pend-board", 871L, "MOTHERBOARD", "B850 Board", 250000, MockData.map("socket", "AM5", "memoryType", "DDR4")));
        stubPart("pend-ram", part("pend-ram", 872L, "RAM", "규격 미표기 RAM", 140000, MockData.map("capacityGb", 32)));
        when(toolCheckService.checkBuild(anyList(), eq(390_000))).thenReturn(List.of(
                tool("compatibility", "WARN", "메모리 규격 정보가 없어 검사를 못 했습니다",
                        MockData.map(
                                "socketMatched", true,
                                "memoryTypeMatched", true,
                                "memoryTypeChecked", false,
                                "coolerSocketMatched", true,
                                "ramSlotsMatched", true,
                                "ramFormFactorMatched", true))
        ));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "budgetWon", 390_000,
                "items", List.of(
                        requestItem("pend-board", "MOTHERBOARD"),
                        requestItem("pend-ram", "RAM")
                )
        ));

        assertThat(castList(graph.get("edges"))).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-board-ram-memory");
            assertThat(edge.get("status")).isEqualTo("WARN");
            assertThat(edge.get("label")).isEqualTo("규격 미확인");
            assertThat(edge.get("summary")).isEqualTo("RAM 미확인 / 메인보드 지원 DDR4입니다. 메모리 규격 정보가 없어 호환 검사를 못 했습니다.");
        });
        // 미확인 WARN 엣지는 부품 카드 뱃지에도 '주의'로 반영된다(툴 WARN과 일관).
        assertThat(castList(graph.get("nodes"))).anySatisfy(node -> {
            assertThat(node.get("id")).isEqualTo("part-RAM");
            assertThat(node.get("status")).isEqualTo("WARN");
        });
    }

    @Test
    void memoryEdgeSummaryNamesMixedRamTypesInsteadOfFirstRamOnly() {
        // 혼합 규격(DDR4+DDR5)에서 첫 RAM만 보면 보드와 일치해 보이는 거짓 문구가 된다 —
        // 엣지 요약이 전 행 집계(ramMemoryTypes)로 실제 사유를 말해야 같은 tool 인사이트가
        // 프론트에서 억제돼도 팝오버에 사실이 남는다.
        stubPart("mixed-board", part("mixed-board", 881L, "MOTHERBOARD", "B850 Board", 250000, MockData.map("socket", "AM5", "memoryType", "DDR5")));
        stubPart("mixed-ram", part("mixed-ram", 882L, "RAM", "DDR5 32GB", 140000, MockData.map("memoryType", "DDR5")));
        when(toolCheckService.checkBuild(anyList(), eq(390_000))).thenReturn(List.of(
                tool("compatibility", "FAIL", "서로 다른 RAM 규격(DDR5, DDR4)이 함께 담겨 있어 같은 보드에 장착할 수 없습니다",
                        MockData.map(
                                "socketMatched", true,
                                "memoryTypeMatched", false,
                                "memoryTypeChecked", true,
                                "ramMemoryTypes", List.of("DDR5", "DDR4"),
                                "coolerSocketMatched", true,
                                "ramSlotsMatched", true,
                                "ramFormFactorMatched", true))
        ));

        Map<String, Object> graph = buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "budgetWon", 390_000,
                "items", List.of(
                        requestItem("mixed-board", "MOTHERBOARD"),
                        requestItem("mixed-ram", "RAM")
                )
        ));

        assertThat(castList(graph.get("edges"))).anySatisfy(edge -> {
            assertThat(edge.get("id")).isEqualTo("edge-board-ram-memory");
            assertThat(edge.get("status")).isEqualTo("FAIL");
            assertThat(edge.get("summary")).isEqualTo(
                    "RAM 규격 DDR5·DDR4 / 메인보드 지원 DDR5입니다. 서로 다른 RAM 규격이 함께 담겨 있어 같은 보드에 장착할 수 없습니다.");
        });
    }

    @Test
    void aiBuildGraphRejectsUnknownPartIdBeforeToolCheck() {

        assertThatThrownBy(() -> buildGraphService.resolve(currentUser(), Map.of(
                "source", "AI_BUILD",
                "items", List.of(requestItem("missing-part", "GPU"))
        )))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void stubPart(String publicId, Map<String, Object> row) {
        stubParts.put(publicId, new ToolBuildPart(
                ((Number) row.get("internal_id")).longValue(),
                String.valueOf(row.get("id")),
                String.valueOf(row.get("category")),
                String.valueOf(row.get("name")),
                String.valueOf(row.get("manufacturer")),
                ((Number) row.get("price")).intValue(),
                castMap(row.get("attributes")),
                1
        ));
    }

    private static Map<String, Object> requestItem(String partId, String category) {
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
                "attributes", attributes
        );
    }

    private static Map<String, Object> tool(String tool, String status, String summary, Map<String, Object> details) {
        return MockData.map(
                "tool", tool,
                "status", status,
                "confidence", "MEDIUM",
                "summary", summary,
                "details", details
        );
    }

    private CurrentUserService.CurrentUser currentUser() {
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
    private static List<String> castStringList(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
