package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.ToolCheckService;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class BuildGraphServiceTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ToolCheckService toolCheckService = mock(ToolCheckService.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final BuildGraphLayoutService buildGraphLayoutService = mock(BuildGraphLayoutService.class);
    private final BuildCompositeScoreService buildCompositeScoreService = new BuildCompositeScoreService();
    private final BuildGraphService buildGraphService = new BuildGraphService(jdbcTemplate, toolCheckService, currentUserService, buildGraphLayoutService, buildCompositeScoreService);

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

        Map<String, Object> graph = buildGraphService.resolve(USER_TOKEN, Map.of(
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

        Map<String, Object> graph = buildGraphService.resolve(USER_TOKEN, Map.of(
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

        Map<String, Object> graph = buildGraphService.resolve(USER_TOKEN, Map.of(
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

        Map<String, Object> graph = buildGraphService.resolve(USER_TOKEN, Map.of(
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

        Map<String, Object> graph = buildGraphService.resolve(USER_TOKEN, Map.of(
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
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(currentUser());
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftItem("part-gpu", "GPU", "RTX 5070", 890000, MockData.map("wattage", 250, "requiredSystemPowerW", 750, "lengthMm", 304)),
                draftItem("part-psu", "PSU", "750W Gold", 150000, MockData.map("capacityW", 750)),
                draftItem("part-case", "CASE", "Airflow Case", 160000, MockData.map("maxGpuLengthMm", 360, "maxCpuCoolerHeightMm", 170))
        ));
        when(toolCheckService.checkBuild(anyList(), eq(1_200_000))).thenReturn(List.of(tool("price", "PASS", "확인되었습니다.", Map.of())));

        Map<String, Object> graph = buildGraphService.resolve(USER_TOKEN, Map.of(
                "source", "QUOTE_DRAFT_CURRENT",
                "view", "FOCUSED",
                "focus", Map.of("mode", "ISSUE_PATH")
        ));

        verify(currentUserService).requireUser(USER_TOKEN);
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

        Map<String, Object> graph = buildGraphService.resolve(USER_TOKEN, Map.of(
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
    void aiBuildGraphRejectsUnknownPartIdBeforeToolCheck() {
        when(jdbcTemplate.queryForList(anyString(), eq("missing-part"))).thenReturn(List.of());

        assertThatThrownBy(() -> buildGraphService.resolve(USER_TOKEN, Map.of(
                "source", "AI_BUILD",
                "items", List.of(requestItem("missing-part", "GPU"))
        )))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void stubPart(String publicId, Map<String, Object> row) {
        when(jdbcTemplate.queryForList(anyString(), eq(publicId))).thenReturn(List.of(row));
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

    private static Map<String, Object> activeDraft() {
        return Map.of(
                "internal_id", 700L,
                "id", "draft-public-id",
                "status", "ACTIVE",
                "name", "셀프 견적"
        );
    }

    private static Map<String, Object> draftItem(String publicId, String category, String name, int price, Map<String, Object> attributes) {
        return MockData.map(
                "internal_id", 100L,
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
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
