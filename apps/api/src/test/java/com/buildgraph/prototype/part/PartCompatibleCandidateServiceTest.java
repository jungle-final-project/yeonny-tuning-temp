package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenAnswer(invocation -> {
            List<ToolBuildPart> parts = invocation.getArgument(0);
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
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenAnswer(invocation -> {
            List<ToolBuildPart> parts = invocation.getArgument(0);
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
        assertThat(items.get(0).get("checkedTools")).isEqualTo(List.of("power"));
        assertThat(part(items.get(0)).get("name")).isEqualTo("850W Gold");
        assertThat(part(items.get(1)).get("name")).isEqualTo("750W Bronze");
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
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenAnswer(invocation -> {
            List<ToolBuildPart> parts = invocation.getArgument(0);
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

        List<Map<String, Object>> items = service.partRowsWithCompatibility(user(), "QUOTE_DRAFT_CURRENT", "GPU", rows);

        assertThat(items).hasSize(3);
        assertThat(compatibility(items.get(0)).get("status")).isEqualTo("FAIL");
        assertThat(compatibility(items.get(0)).get("statusLabel")).isEqualTo("안 맞음");
        assertThat(compatibility(items.get(1)).get("status")).isEqualTo("PASS");
        assertThat(compatibility(items.get(1)).get("statusLabel")).isEqualTo("호환됨");
        assertThat(compatibility(items.get(2)).get("status")).isEqualTo("WARN");
        assertThat(compatibility(items.get(2)).get("statusLabel")).isEqualTo("간섭 주의");
    }

    @Test
    void partRowsWithCompatibilityPreservesApiDtoExternalOfferForCategoryList() {
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftItem("draft-cpu", 301L, "CPU", "Ryzen 7", 420000, MockData.map("socket", "AM5")),
                draftItem("draft-gpu", 302L, "GPU", "RTX 5070", 900000, MockData.map("wattage", 250))
        ));
        when(toolCheckService.checkBuild(anyList(), anyInt()))
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

        List<Map<String, Object>> items = service.partRowsWithCompatibility(user(), "QUOTE_DRAFT_CURRENT", "PSU", rows);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("externalOffer")).isEqualTo(externalOffer);
        assertThat(externalOffer(items.get(0)).get("imageUrl")).isEqualTo("https://shopping-phinf.pstatic.net/main_1234567/1234567.jpg");
        assertThat(externalOffer(items.get(0)).get("supplierName")).isEqualTo("네이버");
        assertThat(externalOffer(items.get(0)).get("offerUrl")).isEqualTo("https://shopping.naver.com/catalog/1234567");
        assertThat(externalOffer(items.get(0)).get("source")).isEqualTo("NAVER_SHOPPING_SEARCH");
        assertThat(compatibility(items.get(0)).get("status")).isEqualTo("PASS");
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
