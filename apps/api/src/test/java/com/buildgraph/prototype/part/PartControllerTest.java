package com.buildgraph.prototype.part;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(PartController.class)
class PartControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";
    private static final String ADMIN_TOKEN = "Bearer jwt-admin-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PartQueryService partQueryService;

    @MockitoBean
    private ToolCheckService toolCheckService;

    @MockitoBean
    private NaverShoppingOfferService naverShoppingOfferService;

    @MockitoBean
    private PartCompatibleCandidateService compatibleCandidateService;

    @MockitoBean
    private DanawaPriceSnapshotService danawaPriceSnapshotService;

    @MockitoBean
    private DanawaPriceTrendService danawaPriceTrendService;

    @MockitoBean
    private ManufacturerReleaseIntakeService manufacturerReleaseIntakeService;

    @MockitoBean
    private PartAdminService partAdminService;

    @MockitoBean
    private PartAliasReviewService partAliasReviewService;

    @MockitoBean
    private PartQualityReportService partQualityReportService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @BeforeEach
    void setUpAuth() {
        when(currentUserService.requireUser(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        when(currentUserService.requireAdmin(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }

    @Test
    void toolCheckReturnsUnauthorizedWhenTokenIsMissing() throws Exception {
        mockMvc.perform(post("/api/tools/power/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));

        verifyNoInteractions(toolCheckService);
    }

    @Test
    void toolCheckRunsForAuthenticatedUserToken() throws Exception {
        when(toolCheckService.checkTool(eq("power"), anyMap())).thenReturn(Map.of(
                "tool", "power",
                "status", "PASS",
                "confidence", "HIGH",
                "summary", "전력 검증 통과",
                "details", Map.of("ratedHeadroomW", 180)
        ));

        mockMvc.perform(post("/api/tools/power/check")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tool").value("power"))
                .andExpect(jsonPath("$.status").value("PASS"))
                .andExpect(jsonPath("$.confidence").value("HIGH"));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(toolCheckService).checkTool(eq("power"), anyMap());
    }

    @Test
    void compatibleCandidatesRequireUserToken() throws Exception {
        mockMvc.perform(post("/api/parts/compatible-candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(compatibleCandidateService);
    }

    @Test
    void compatibleCandidatesReturnServerCheckedOptions() throws Exception {
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
                1004L,
                "00000000-0000-4000-8000-000000001004",
                "user@example.com",
                "Demo User",
                "USER",
                "2026-06-30T00:00:00Z"
        );
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(user);
        when(compatibleCandidateService.compatibleCandidates(eq(user), anyMap())).thenReturn(Map.of(
                "category", "GPU",
                "items", java.util.List.of(Map.of(
                        "part", Map.of("id", "part-gpu-pass", "category", "GPU", "name", "RTX 5070 Ti", "price", 990000),
                        "status", "PASS",
                        "statusLabel", "여유 있음",
                        "summary", "현재 PSU/케이스 기준 장착 가능합니다.",
                        "checkedTools", java.util.List.of("power", "size")
                )),
                "rejectedCount", 1,
                "warnings", java.util.List.of()
        ));

        mockMvc.perform(post("/api/parts/compatible-candidates")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source": "AI_BUILD",
                                  "category": "GPU",
                                  "items": [{ "partId": "part-gpu-current", "category": "GPU", "quantity": 1 }],
                                  "limit": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("GPU"))
                .andExpect(jsonPath("$.items[0].part.name").value("RTX 5070 Ti"))
                .andExpect(jsonPath("$.items[0].statusLabel").value("여유 있음"))
                .andExpect(jsonPath("$.rejectedCount").value(1));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(compatibleCandidateService).compatibleCandidates(eq(user), anyMap());
    }

    @Test
    void partsPassCompatibilitySourceToQueryService() throws Exception {
        CurrentUserService.CurrentUser user = new CurrentUserService.CurrentUser(
                1004L,
                "00000000-0000-4000-8000-000000001004",
                "user@example.com",
                "Demo User",
                "USER",
                "2026-06-30T00:00:00Z"
        );
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(user);
        when(partQueryService.parts(
                eq(user),
                eq("GPU"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq("compatibility"),
                eq("QUOTE_DRAFT_CURRENT")
        )).thenReturn(Map.of(
                "items", java.util.List.of(Map.of(
                        "id", "part-gpu-pass",
                        "category", "GPU",
                        "name", "RTX 5070 Ti",
                        "price", 990000,
                        "status", "ACTIVE",
                        "attributes", Map.of(),
                        "compatibility", Map.of(
                                "status", "PASS",
                                "statusLabel", "호환됨",
                                "summary", "현재 조합 기준 호환 가능합니다.",
                                "checkedTools", java.util.List.of("power", "size", "performance")
                        )
                )),
                "page", 0,
                "size", 20,
                "total", 1
        ));

        mockMvc.perform(get("/api/parts")
                        .header("Authorization", USER_TOKEN)
                        .param("category", "GPU")
                        .param("sort", "compatibility")
                        .param("compatibilitySource", "QUOTE_DRAFT_CURRENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].compatibility.status").value("PASS"))
                .andExpect(jsonPath("$.items[0].compatibility.statusLabel").value("호환됨"));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(partQueryService).parts(
                eq(user),
                eq("GPU"),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq("compatibility"),
                eq("QUOTE_DRAFT_CURRENT")
        );
    }

    @Test
    void manufacturerSourcesRequireAdminToken() throws Exception {
        mockMvc.perform(get("/api/admin/manufacturer-sources"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(manufacturerReleaseIntakeService);
    }

    @Test
    void adminPartsListDelegatesToServiceForAdmin() throws Exception {
        when(partAdminService.listParts(eq("GPU"), eq("5090"), eq(null), eq("ACTIVE"), eq(null), eq(null), eq(false), eq(0), eq(20), eq("price_desc")))
                .thenReturn(Map.of(
                        "items", java.util.List.of(Map.of("id", "part-1", "name", "RTX 5090")),
                        "page", 0,
                        "size", 20,
                        "total", 1
                ));

        mockMvc.perform(get("/api/admin/parts")
                        .header("Authorization", ADMIN_TOKEN)
                        .param("category", "GPU")
                        .param("q", "5090")
                        .param("status", "ACTIVE")
                        .param("includeDeleted", "false")
                        .param("page", "0")
                        .param("size", "20")
                        .param("sort", "price_desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(partAdminService).listParts(eq("GPU"), eq("5090"), eq(null), eq("ACTIVE"), eq(null), eq(null), eq(false), eq(0), eq(20), eq("price_desc"));
    }

    @Test
    void createAdminPartDelegatesToServiceForAdmin() throws Exception {
        var admin = new CurrentUserService.CurrentUser(1L, "admin-id", "admin@example.com", "관리자", "ADMIN", null);
        when(currentUserService.requireAdmin(ADMIN_TOKEN)).thenReturn(admin);
        when(partAdminService.create(anyMap(), eq(admin))).thenReturn(Map.of(
                "id", "part-1",
                "name", "테스트 GPU",
                "status", "INACTIVE"
        ));

        mockMvc.perform(post("/api/admin/parts")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"GPU\",\"name\":\"테스트 GPU\",\"price\":1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        verify(partAdminService).create(anyMap(), eq(admin));
    }

    @Test
    void updateAdminPartDelegatesToServiceForAdmin() throws Exception {
        var admin = new CurrentUserService.CurrentUser(1L, "admin-id", "admin@example.com", "관리자", "ADMIN", null);
        when(currentUserService.requireAdmin(ADMIN_TOKEN)).thenReturn(admin);
        when(partAdminService.update(eq("part-1"), anyMap(), eq(admin))).thenReturn(Map.of(
                "id", "part-1",
                "status", "ACTIVE"
        ));

        mockMvc.perform(patch("/api/admin/parts/part-1")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(partAdminService).update(eq("part-1"), anyMap(), eq(admin));
    }

    @Test
    void deleteAdminPartSoftDeletesForAdmin() throws Exception {
        var admin = new CurrentUserService.CurrentUser(1L, "admin-id", "admin@example.com", "관리자", "ADMIN", null);
        when(currentUserService.requireAdmin(ADMIN_TOKEN)).thenReturn(admin);
        when(partAdminService.softDelete(eq("part-1"), eq(admin))).thenReturn(Map.of("id", "part-1", "deleted", true));

        mockMvc.perform(delete("/api/admin/parts/part-1")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        verify(partAdminService).softDelete(eq("part-1"), eq(admin));
    }

    @Test
    void restoreAdminPartDelegatesToServiceForAdmin() throws Exception {
        var admin = new CurrentUserService.CurrentUser(1L, "admin-id", "admin@example.com", "관리자", "ADMIN", null);
        when(currentUserService.requireAdmin(ADMIN_TOKEN)).thenReturn(admin);
        when(partAdminService.restore(eq("part-1"), eq(admin))).thenReturn(Map.of("id", "part-1", "status", "INACTIVE"));

        mockMvc.perform(post("/api/admin/parts/part-1/restore")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        verify(partAdminService).restore(eq("part-1"), eq(admin));
    }

    @Test
    void manualPriceDelegatesToServiceForAdmin() throws Exception {
        var admin = new CurrentUserService.CurrentUser(1L, "admin-id", "admin@example.com", "관리자", "ADMIN", null);
        when(currentUserService.requireAdmin(ADMIN_TOKEN)).thenReturn(admin);
        when(partAdminService.manualPrice(eq("part-1"), anyMap(), eq(admin))).thenReturn(Map.of("id", "part-1", "price", 123000));

        mockMvc.perform(post("/api/admin/parts/part-1/manual-price")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"price\":123000,\"reason\":\"대표가 보정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(123000));

        verify(partAdminService).manualPrice(eq("part-1"), anyMap(), eq(admin));
    }

    @Test
    void externalOfferUpdateDelegatesToServiceForAdmin() throws Exception {
        var admin = new CurrentUserService.CurrentUser(1L, "admin-id", "admin@example.com", "관리자", "ADMIN", null);
        when(currentUserService.requireAdmin(ADMIN_TOKEN)).thenReturn(admin);
        when(partAdminService.updateExternalOffer(eq("part-1"), anyMap(), eq(admin))).thenReturn(Map.of(
                "id", "part-1",
                "externalOffer", Map.of("supplierName", "수동 공급처")
        ));

        mockMvc.perform(patch("/api/admin/parts/part-1/external-offer")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierName\":\"수동 공급처\",\"lowPrice\":123000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalOffer.supplierName").value("수동 공급처"));

        verify(partAdminService).updateExternalOffer(eq("part-1"), anyMap(), eq(admin));
    }

    @Test
    void createManufacturerSourceDelegatesToServiceForAdmin() throws Exception {
        when(manufacturerReleaseIntakeService.createSource(anyMap(), isNull())).thenReturn(Map.of(
                "id", "00000000-0000-4000-8000-000000009001",
                "manufacturer", "ASUS",
                "categoryScope", "GPU",
                "sourceType", "NEWS",
                "sourceUrl", "https://www.asus.com/news/",
                "enabled", true,
                "status", "ACTIVE"
        ));

        mockMvc.perform(post("/api/admin/manufacturer-sources")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "manufacturer": "ASUS",
                                  "categoryScope": "GPU",
                                  "sourceType": "NEWS",
                                  "sourceUrl": "https://www.asus.com/news/",
                                  "enabled": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manufacturer").value("ASUS"))
                .andExpect(jsonPath("$.categoryScope").value("GPU"));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(manufacturerReleaseIntakeService).createSource(anyMap(), isNull());
    }

    @Test
    void updateManufacturerSourceDelegatesToServiceForAdmin() throws Exception {
        when(manufacturerReleaseIntakeService.updateSource(eq("00000000-0000-4000-8000-000000009001"), anyMap(), isNull()))
                .thenReturn(Map.of(
                        "id", "00000000-0000-4000-8000-000000009001",
                        "enabled", false,
                        "status", "PAUSED"
                ));

        mockMvc.perform(patch("/api/admin/manufacturer-sources/00000000-0000-4000-8000-000000009001")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\": false, \"status\": \"PAUSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(manufacturerReleaseIntakeService).updateSource(eq("00000000-0000-4000-8000-000000009001"), anyMap(), isNull());
    }

    @Test
    void deleteAndRestoreManufacturerSourceDelegateToServiceForAdmin() throws Exception {
        when(manufacturerReleaseIntakeService.softDeleteSource(eq("00000000-0000-4000-8000-000000009001"), isNull()))
                .thenReturn(Map.of("id", "00000000-0000-4000-8000-000000009001", "deleted", true));
        when(manufacturerReleaseIntakeService.restoreSource(eq("00000000-0000-4000-8000-000000009001"), isNull()))
                .thenReturn(Map.of("id", "00000000-0000-4000-8000-000000009001", "status", "PAUSED"));

        mockMvc.perform(delete("/api/admin/manufacturer-sources/00000000-0000-4000-8000-000000009001")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        mockMvc.perform(post("/api/admin/manufacturer-sources/00000000-0000-4000-8000-000000009001/restore")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        verify(manufacturerReleaseIntakeService).softDeleteSource(eq("00000000-0000-4000-8000-000000009001"), isNull());
        verify(manufacturerReleaseIntakeService).restoreSource(eq("00000000-0000-4000-8000-000000009001"), isNull());
    }

    @Test
    void scanManufacturerSourceDelegatesToServiceForAdmin() throws Exception {
        when(manufacturerReleaseIntakeService.scanSource(eq("00000000-0000-4000-8000-000000009501"), eq(20), eq(true)))
                .thenReturn(Map.of(
                        "sourceId", "00000000-0000-4000-8000-000000009501",
                        "parsedPosts", 1,
                        "newPosts", 1,
                        "productPosts", 1,
                        "createdCandidates", 1
                ));

        mockMvc.perform(post("/api/admin/manufacturer-sources/00000000-0000-4000-8000-000000009501/scan")
                        .header("Authorization", ADMIN_TOKEN)
                        .param("limit", "20")
                        .param("createCandidates", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value("00000000-0000-4000-8000-000000009501"))
                .andExpect(jsonPath("$.newPosts").value(1))
                .andExpect(jsonPath("$.createdCandidates").value(1));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(manufacturerReleaseIntakeService).scanSource(eq("00000000-0000-4000-8000-000000009501"), eq(20), eq(true));
    }

    @Test
    void approvePartCatalogCandidateCreatesInactiveDraftForAdmin() throws Exception {
        when(naverShoppingOfferService.approveCatalogCandidateAsInactive(eq("00000000-0000-4000-8000-000000009601"), isNull()))
                .thenReturn(Map.of(
                        "candidateId", "00000000-0000-4000-8000-000000009601",
                        "publishedPartId", "00000000-0000-4000-8000-000000009701",
                        "created", true,
                        "partStatus", "INACTIVE",
                        "status", "PUBLISHED"
                ));

        mockMvc.perform(post("/api/admin/part-catalog-candidates/00000000-0000-4000-8000-000000009601/approve")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.partStatus").value("INACTIVE"));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(naverShoppingOfferService).approveCatalogCandidateAsInactive(eq("00000000-0000-4000-8000-000000009601"), isNull());
    }

    @Test
    void rejectPartCatalogCandidateDelegatesToServiceForAdmin() throws Exception {
        when(naverShoppingOfferService.rejectCatalogCandidate(eq("00000000-0000-4000-8000-000000009601"), anyMap(), isNull()))
                .thenReturn(Map.of(
                        "candidateId", "00000000-0000-4000-8000-000000009601",
                        "status", "REJECTED",
                        "reason", "검수 제외"
                ));

        mockMvc.perform(post("/api/admin/part-catalog-candidates/00000000-0000-4000-8000-000000009601/reject")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"검수 제외\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        verify(currentUserService).requireAdmin(ADMIN_TOKEN);
        verify(naverShoppingOfferService).rejectCatalogCandidate(eq("00000000-0000-4000-8000-000000009601"), anyMap(), isNull());
    }

    @Test
    void postAndCandidateCrudEndpointsDelegateForAdmin() throws Exception {
        when(manufacturerReleaseIntakeService.createPost(anyMap(), isNull()))
                .thenReturn(Map.of("id", "00000000-0000-4000-8000-000000009511", "classificationStatus", "PRODUCT_CANDIDATE"));
        when(manufacturerReleaseIntakeService.createCandidateForPost(eq("00000000-0000-4000-8000-000000009511"), isNull()))
                .thenReturn(Map.of("created", true, "candidateId", "00000000-0000-4000-8000-000000009601"));
        when(manufacturerReleaseIntakeService.createAiAssetDraftForPost(eq("00000000-0000-4000-8000-000000009511"), isNull()))
                .thenReturn(Map.of(
                        "postId", "00000000-0000-4000-8000-000000009511",
                        "classificationStatus", "PRODUCT_CANDIDATE",
                        "candidateId", "00000000-0000-4000-8000-000000009601",
                        "partId", "00000000-0000-4000-8000-000000009701",
                        "partStatus", "INACTIVE",
                        "messages", java.util.List.of("AI 분류", "INACTIVE 초안 생성")
                ));
        when(naverShoppingOfferService.updateCatalogCandidate(eq("00000000-0000-4000-8000-000000009601"), anyMap(), isNull()))
                .thenReturn(Map.of("id", "00000000-0000-4000-8000-000000009601", "title", "ASUS RTX 5090"));
        when(naverShoppingOfferService.softDeleteCatalogCandidate(eq("00000000-0000-4000-8000-000000009601"), isNull()))
                .thenReturn(Map.of("id", "00000000-0000-4000-8000-000000009601", "deleted", true));

        mockMvc.perform(post("/api/admin/manufacturer-posts")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceId": "00000000-0000-4000-8000-000000009501",
                                  "externalUrl": "https://press.asus.com/new-gpu",
                                  "title": "ASUS launches RTX 5090",
                                  "classificationStatus": "PRODUCT_CANDIDATE",
                                  "detectedCategory": "GPU",
                                  "detectedProductName": "RTX 5090"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classificationStatus").value("PRODUCT_CANDIDATE"));

        mockMvc.perform(post("/api/admin/manufacturer-posts/00000000-0000-4000-8000-000000009511/create-candidate")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(true));

        mockMvc.perform(post("/api/admin/manufacturer-posts/00000000-0000-4000-8000-000000009511/ai-asset-draft")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classificationStatus").value("PRODUCT_CANDIDATE"))
                .andExpect(jsonPath("$.partStatus").value("INACTIVE"));

        mockMvc.perform(patch("/api/admin/part-catalog-candidates/00000000-0000-4000-8000-000000009601")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"ASUS RTX 5090\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("ASUS RTX 5090"));

        mockMvc.perform(delete("/api/admin/part-catalog-candidates/00000000-0000-4000-8000-000000009601")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));

        verify(manufacturerReleaseIntakeService).createPost(anyMap(), isNull());
        verify(manufacturerReleaseIntakeService).createCandidateForPost(eq("00000000-0000-4000-8000-000000009511"), isNull());
        verify(manufacturerReleaseIntakeService).createAiAssetDraftForPost(eq("00000000-0000-4000-8000-000000009511"), isNull());
        verify(naverShoppingOfferService).updateCatalogCandidate(eq("00000000-0000-4000-8000-000000009601"), anyMap(), isNull());
        verify(naverShoppingOfferService).softDeleteCatalogCandidate(eq("00000000-0000-4000-8000-000000009601"), isNull());
    }

    @Test
    void partAliasReviewEndpointsDelegateForAdmin() throws Exception {
        when(partAliasReviewService.listReviewItems(eq("OPEN"), eq("GPU"), eq("gpuClass"), eq("AI_BUILD_CHAT"), eq(0), eq(20)))
                .thenReturn(Map.of("items", java.util.List.of(), "page", 0, "size", 20, "total", 0));
        when(partAliasReviewService.reviewSummary())
                .thenReturn(Map.of("items", java.util.List.of(Map.of("category", "GPU", "targetField", "gpuClass", "sourceType", "AI_BUILD_CHAT", "count", 1))));
        when(partAliasReviewService.createRule(anyMap(), isNull()))
                .thenReturn(Map.of("id", "rule-1", "aliasText", "5070티아이", "canonicalValue", "RTX_5070_TI"));
        when(partAliasReviewService.resolveReviewItem(eq("review-1"), anyMap(), isNull()))
                .thenReturn(Map.of("id", "review-1", "status", "RESOLVED"));
        when(partAliasReviewService.ignoreReviewItem(eq("review-1"), anyMap(), isNull()))
                .thenReturn(Map.of("id", "review-1", "status", "IGNORED"));

        mockMvc.perform(get("/api/admin/part-alias-review-items")
                        .header("Authorization", ADMIN_TOKEN)
                        .param("status", "OPEN")
                        .param("category", "GPU")
                        .param("targetField", "gpuClass")
                        .param("sourceType", "AI_BUILD_CHAT")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get("/api/admin/part-alias-review-items/summary")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].count").value(1));

        mockMvc.perform(post("/api/admin/part-alias-rules")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aliasText\":\"5070티아이\",\"category\":\"GPU\",\"targetField\":\"gpuClass\",\"canonicalValue\":\"RTX_5070_TI\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalValue").value("RTX_5070_TI"));

        mockMvc.perform(post("/api/admin/part-alias-review-items/review-1/resolve")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aliasText\":\"5070티아이\",\"category\":\"GPU\",\"targetField\":\"gpuClass\",\"canonicalValue\":\"RTX_5070_TI\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        mockMvc.perform(post("/api/admin/part-alias-review-items/review-1/ignore")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"처리 불필요\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IGNORED"));

        verify(partAliasReviewService).listReviewItems(eq("OPEN"), eq("GPU"), eq("gpuClass"), eq("AI_BUILD_CHAT"), eq(0), eq(20));
        verify(partAliasReviewService).reviewSummary();
        verify(partAliasReviewService).createRule(anyMap(), isNull());
        verify(partAliasReviewService).resolveReviewItem(eq("review-1"), anyMap(), isNull());
        verify(partAliasReviewService).ignoreReviewItem(eq("review-1"), anyMap(), isNull());
    }

    @Test
    void partQualityReportEndpointDelegatesForAdmin() throws Exception {
        when(partQualityReportService.qualityReport())
                .thenReturn(Map.of(
                        "summary", Map.of("activeParts", 1, "toolReadyMissing", 0, "requiredSpecMissing", 0, "benchmarkMissing", 0, "fpsCoverageGap", 0, "aliasReviewOpen", 0),
                        "categories", java.util.List.of(),
                        "actionItems", java.util.List.of()
                ));

        mockMvc.perform(get("/api/admin/parts/quality-report")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.activeParts").value(1));

        verify(partQualityReportService).qualityReport();
    }
}
