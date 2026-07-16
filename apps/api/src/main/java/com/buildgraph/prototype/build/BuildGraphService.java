package com.buildgraph.prototype.build;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.query.PartQuery;
import com.buildgraph.prototype.part.tool.BuildSizeFitPolicy;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BuildGraphService {
    private static final Set<String> CATEGORIES = Set.of("CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER");
    private final PartQuery partQuery;
    private final CurrentUserService currentUserService;
    private final BuildGraphLayoutService buildGraphLayoutService;
    private final BuildEvaluationService buildEvaluationService;

    @Autowired
    public BuildGraphService(
            PartQuery partQuery,
            CurrentUserService currentUserService,
            BuildGraphLayoutService buildGraphLayoutService,
            BuildEvaluationService buildEvaluationService
    ) {
        this.partQuery = partQuery;
        this.currentUserService = currentUserService;
        this.buildGraphLayoutService = buildGraphLayoutService;
        this.buildEvaluationService = buildEvaluationService;
    }

    public Map<String, Object> resolve(String authorization, Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String source = firstText(text(body.get("source")), "AI_BUILD").toUpperCase(Locale.ROOT);
        String view = firstText(text(body.get("view")), "FOCUSED").toUpperCase(Locale.ROOT);
        Map<String, Object> focus = objectMap(body.get("focus"));
        String mode = normalizedMode(text(focus.get("mode")));
        Integer requestedBudget = numberValue(body.get("budgetWon"));
        BuildEvaluationService.BuildEvaluation evaluation = switch (source) {
            case "AI_BUILD" -> buildEvaluationService.evaluate(
                    aiBuildParts(body),
                    requestedBudget,
                    text(focus.get("category")),
                    text(focus.get("tool"))
            );
            case "QUOTE_DRAFT_CURRENT" -> {
                CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
                yield buildEvaluationService.evaluateCurrentDraft(
                        user.internalId(),
                        requestedBudget,
                        text(focus.get("category")),
                        text(focus.get("tool"))
                );
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 그래프 source입니다.");
        };
        List<ToolBuildPart> parts = evaluation.parts();
        GraphDraft draft = buildGraph(
                parts,
                evaluation.toolResults(),
                mode,
                view,
                focus,
                evaluation.budgetWon(),
                evaluation.totalPrice()
        );
        List<Map<String, Object>> nodes = withLayoutPositions(draft.nodes(), buildGraphLayoutService.resolvePositions());
        return MockData.map(
                "mode", mode,
                "summary", draft.summary(),
                "nodes", nodes,
                "edges", draft.edges(),
                "focusNodeIds", draft.focusNodeIds(),
                "insights", draft.insights(),
                "compositeScore", evaluation.compositeScore(),
                "buildAssessment", evaluation.buildAssessment(),
                "toolResults", evaluation.toolResults()
        );
    }

    private static List<Map<String, Object>> withLayoutPositions(
            List<Map<String, Object>> nodes,
            Map<String, BuildGraphLayoutService.GraphPosition> positions
    ) {
        if (positions == null || positions.isEmpty()) {
            return nodes;
        }
        return nodes.stream()
                .map(node -> {
                    String category = normalizeCategory(text(node.get("category")));
                    if (category == null) {
                        return node;
                    }
                    BuildGraphLayoutService.GraphPosition position = positions.get(category);
                    if (position == null) {
                        return node;
                    }
                    Map<String, Object> withPosition = new LinkedHashMap<>(node);
                    withPosition.put("position", position.toMap());
                    return withPosition;
                })
                .toList();
    }

    private List<ToolBuildPart> aiBuildParts(Map<String, Object> body) {
        List<Map<String, Object>> items = objectMaps(body.get("items"));
        if (items.isEmpty()) {
            return List.of();
        }
        List<String> partIds = items.stream().map(item -> {
            String partId = text(item.get("partId"));
            if (partId == null || partId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partId가 필요합니다.");
            }
            return partId;
        }).toList();

        // 요청 순서를 유지한 채 cache miss ID만 한 번의 IN 쿼리로 조회한다.
        List<ToolBuildPart> loadedParts = partQuery.partsByPublicIds(partIds);
        List<ToolBuildPart> parts = new ArrayList<>(loadedParts.size());
        for (int index = 0; index < loadedParts.size(); index++) {
            Map<String, Object> item = items.get(index);
            ToolBuildPart part = loadedParts.get(index);
            String requestedCategory = normalizeCategory(text(item.get("category")));
            if (requestedCategory != null && !requestedCategory.equals(part.category())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partId와 category가 일치하지 않습니다.");
            }
            // 요청 item.quantity를 반영한다 — 버리면 RAM 스틱 수·M.2 장착 수·전력 부하·총액이 전부
            // 1개분으로 계산돼 슬롯 초과 구성이 PASS(초록)로 둔갑한다(QUOTE_DRAFT 경로와 동일 계열 규칙).
            parts.add(withRequestedQuantity(part, item.get("quantity")));
        }
        return parts;
    }

    /** 요청 quantity를 부품에 싣는다 — 비숫자·1 미만은 1개로 방어한다(계약 minimum 1). */
    private static ToolBuildPart withRequestedQuantity(ToolBuildPart part, Object quantity) {
        Integer parsed = numberValue(quantity);
        int safeQuantity = parsed == null || parsed < 1 ? 1 : parsed;
        return new ToolBuildPart(
                part.internalId(),
                part.publicId(),
                part.category(),
                part.name(),
                part.manufacturer(),
                part.price(),
                part.attributes(),
                safeQuantity
        );
    }

    private GraphDraft buildGraph(
            List<ToolBuildPart> parts,
            List<Map<String, Object>> toolResults,
            String mode,
            String view,
            Map<String, Object> focus,
            int budget,
            int total
    ) {
        Map<String, ToolBuildPart> byCategory = parts.stream()
                .filter(part -> part.category() != null)
                .collect(Collectors.toMap(part -> part.category().toUpperCase(Locale.ROOT), part -> part, (left, right) -> left, LinkedHashMap::new));
        Map<String, Map<String, Object>> toolByName = toolResults.stream()
                .filter(result -> result.get("tool") != null)
                .collect(Collectors.toMap(result -> text(result.get("tool")), result -> result, (left, right) -> left, LinkedHashMap::new));
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (ToolBuildPart part : parts) {
            nodes.add(node(part));
        }
        addConstraintNodes(nodes, byCategory, toolByName, budget, total);

        Map<String, Object> compatibilityDetails = toolDetails(toolByName, "compatibility");
        Map<String, Object> powerDetails = toolDetails(toolByName, "power");
        Map<String, Object> sizeDetails = toolDetails(toolByName, "size");
        String socketStatus = socketStatus(byCategory, compatibilityDetails, toolStatus(toolByName, "compatibility"));
        // 메인보드-RAM 엣지는 DDR 규격, 폼팩터(SODIMM/RDIMM 차단), 슬롯 수용량을 함께 본다. 셋 다 물리적 장착 문제라 FAIL.
        boolean ramSlotsExceeded = Boolean.FALSE.equals(booleanValue(compatibilityDetails.get("ramSlotsMatched")));
        boolean ramFormFactorBad = Boolean.FALSE.equals(booleanValue(compatibilityDetails.get("ramFormFactorMatched")));
        Boolean memoryTypeMatched = booleanValue(compatibilityDetails.get("memoryTypeMatched"));
        // 규격 정보 결측으로 검사를 생략한 경우(memoryTypeChecked=false)는 WARN(확인 필요)으로 그린다 —
        // 검사 안 된 관계를 초록(근거 없는 통과)이나 무관한 툴 status 상속(빨강)으로 그리지 않는다.
        // 이 응답은 Home 프리뷰·Build 상세(BuildDependencyGraph)도 공유하는데 그쪽 status 계약이
        // PASS/WARN/FAIL이라, 계약 밖 값(PENDING)을 내리면 '호환 가능'(초록)으로 폴백 렌더된다.
        // memoryTypeMatched는 미검사 시에도 true라(booleanStatus 상속 부작용 방지 계약) checked를 먼저 본다.
        Boolean memoryTypeChecked = booleanValue(compatibilityDetails.get("memoryTypeChecked"));
        boolean memoryTypeUnknown = Boolean.FALSE.equals(memoryTypeChecked);
        String memoryStatus;
        if (ramSlotsExceeded || ramFormFactorBad || Boolean.FALSE.equals(memoryTypeMatched)) {
            memoryStatus = "FAIL";
        } else if (memoryTypeUnknown) {
            memoryStatus = "WARN";
        } else {
            memoryStatus = booleanStatus(memoryTypeMatched, toolStatus(toolByName, "compatibility"));
        }
        // 쿨러 엣지는 소켓 지원과 TDP 대응(냉각 용량)을 함께 본다 — 나쁜 쪽 상태가 엣지를 결정한다.
        String coolerSocketStatus = booleanStatus(booleanValue(compatibilityDetails.get("coolerSocketMatched")), toolStatus(toolByName, "compatibility"));
        String coolerStatus = worseStatus(coolerSocketStatus, coolerTdpStatus(compatibilityDetails));
        // 파워 판정은 ToolCheckService.power() 한 곳에서만 내리고 GPU-PSU 엣지도 그 status를 그대로 쓴다.
        // (엣지가 requiredRatedCapacity 기준 headroom으로 별도 재계산하면, 툴은 WARN인데 엣지만 FAIL이 되어
        //  "권장 파워를 충족한 PSU인데 엣지선만 빨강"인 불일치가 생겼다.)
        String powerStatus = toolStatus(toolByName, "power");
        // 치수 엣지의 fallback은 size 툴 전체 status가 아니라 WARN(근거 부족)이다 — 툴 status를 상속하면
        // 무관한 검사(파워 깊이 등)의 FAIL이 쿨러/GPU 엣지에 '높이 간섭' 같은 엉뚱한 라벨로 실린다.
        // (엣지는 addEdgeIfPossible로 양쪽 부품이 있을 때만 생기므로, 데이터 결측 = 근거 부족 WARN이 맞다.)
        String gpuLengthStatus = lengthStatus(
                sizeDetails,
                "gpuLengthMm",
                "maxGpuLengthMm",
                BuildSizeFitPolicy.GPU_WARN_HEADROOM_MM,
                "WARN"
        );
        // 수랭(AIO)은 높이 대신 라디에이터 크기 vs 케이스 지원 목록으로 판정한다.
        String coolerCaseStatus = Boolean.TRUE.equals(booleanValue(sizeDetails.get("radiatorChecked")))
                ? radiatorStatus(sizeDetails)
                : lengthStatus(
                        sizeDetails,
                        "coolerHeightMm",
                        "maxCpuCoolerHeightMm",
                        BuildSizeFitPolicy.COOLER_WARN_HEADROOM_MM,
                        "WARN"
                );
        String psuDepthStatus = lengthStatus(
                sizeDetails,
                "psuDepthMm",
                "maxPsuLengthMm",
                BuildSizeFitPolicy.PSU_WARN_HEADROOM_MM,
                "WARN"
        );
        // 보드 폼팩터 vs 케이스 지원 규격(P1-1) — checked 게이트로 결측이면 WARN(근거 부족).
        String boardFitStatus = boardFitStatus(sizeDetails);
        // M.2 SSD 장착 수 vs 보드 M.2 슬롯(P1-2) — 판정은 ToolCheckService.compatibility()가 단일 소스다.
        boolean m2SlotsExceeded = Boolean.FALSE.equals(booleanValue(compatibilityDetails.get("m2SlotsMatched")));
        String storageStatus = m2StorageStatus(compatibilityDetails);

        List<Map<String, Object>> edges = new ArrayList<>();
        addEdgeIfPossible(edges, byCategory, "CPU", "MOTHERBOARD", "edge-cpu-board-socket", "REQUIRES", socketStatus, socketLabel(socketStatus), socketSummary(byCategory, socketStatus));
        addEdgeIfPossible(edges, byCategory, "MOTHERBOARD", "RAM", "edge-board-ram-memory", "REQUIRES", memoryStatus, memoryLabel(ramFormFactorBad, ramSlotsExceeded, memoryTypeUnknown), memorySummary(byCategory, compatibilityDetails, memoryStatus, memoryTypeUnknown));
        addEdgeIfPossible(edges, byCategory, "CPU", "COOLER", "edge-cpu-cooler-socket", "REQUIRES", coolerStatus, coolerLabel(compatibilityDetails), coolerSummary(byCategory, compatibilityDetails, coolerStatus));
        addEdgeIfPossible(edges, byCategory, "GPU", "PSU", "edge-gpu-psu-power", "AFFECTS", powerStatus, powerLabel(powerDetails, powerStatus), powerSummary(toolByName, powerStatus));
        addEdgeIfPossible(edges, byCategory, "GPU", "CASE", "edge-gpu-case-length", "REQUIRES", gpuLengthStatus, gpuLengthLabel(sizeDetails, gpuLengthStatus), gpuLengthSummary(toolByName, gpuLengthStatus));
        addEdgeIfPossible(edges, byCategory, "COOLER", "CASE", "edge-cooler-case-height", "REQUIRES", coolerCaseStatus, coolerCaseLabel(sizeDetails, coolerCaseStatus), coolerCaseSummary(toolByName, coolerCaseStatus));
        // 파워 깊이 vs 케이스 허용 길이(P0-3) — 엣지가 없으면 FAIL이 보드/구매 차단에 반영되지 않는다.
        addEdgeIfPossible(edges, byCategory, "PSU", "CASE", "edge-psu-case-depth", "REQUIRES", psuDepthStatus, psuDepthLabel(sizeDetails, psuDepthStatus), psuDepthSummary(toolByName, psuDepthStatus));
        // 보드 규격 vs 케이스 지원(P1-1) — ITX 전용 케이스에 ATX 보드 같은 조합을 물리적 장착 불가로 표시한다.
        addEdgeIfPossible(edges, byCategory, "MOTHERBOARD", "CASE", "edge-board-case-form", "REQUIRES", boardFitStatus, boardFitLabel(sizeDetails, boardFitStatus), boardFitSummary(sizeDetails, boardFitStatus));
        // M.2 SSD 장착 수 vs 보드 M.2 슬롯(P1-2) — 슬롯 초과 조합을 물리적 장착 불가로 표시한다.
        addEdgeIfPossible(edges, byCategory, "MOTHERBOARD", "STORAGE", "edge-board-storage-m2", "REQUIRES", storageStatus, storageLabel(compatibilityDetails, storageStatus, m2SlotsExceeded), storageSummary(compatibilityDetails, storageStatus));
        addEdgeIfPossible(edges, byCategory, "CPU", "GPU", "edge-cpu-gpu-performance", "AFFECTS", toolStatus(toolByName, "performance"), "작업 성능", toolSummary(toolByName, "performance", "CPU와 GPU 조합으로 작업 적합도를 확인합니다."));
        if (!parts.isEmpty()) {
            edges.add(edge("edge-budget-total-price", "constraint-budget", "constraint-total-price", "AFFECTS", toolStatus(toolByName, "price"), "예산", priceSummary(toolByName, budget, total)));
        }

        applyWorstEdgeStatusToPartNodes(nodes, edges);

        List<Map<String, Object>> insights = insights(toolByName, byCategory, budget, total);
        List<String> focusNodeIds = focusNodeIds(edges, focus, insights);
        String summary = summary(mode, focus, toolByName, parts, edges);
        return new GraphDraft(summary, nodes, edges, focusNodeIds, insights);
    }

    private void addConstraintNodes(List<Map<String, Object>> nodes, Map<String, ToolBuildPart> byCategory, Map<String, Map<String, Object>> toolByName, int budget, int total) {
        if (byCategory.containsKey("GPU") || byCategory.containsKey("PSU")) {
            nodes.add(constraintNode("constraint-power", "PSU", powerConstraintLabel(byCategory), toolStatus(toolByName, "power"), powerSummary(toolByName)));
        }
        if (byCategory.containsKey("GPU") || byCategory.containsKey("CASE") || byCategory.containsKey("COOLER") || byCategory.containsKey("PSU")) {
            nodes.add(constraintNode("constraint-size", "CASE", partNameOrFallback(byCategory, "CASE", "장착 규격"), toolStatus(toolByName, "size"), toolSummary(toolByName, "size", "케이스와 부품 치수 제약을 확인합니다.")));
        }
        if (byCategory.containsKey("CPU") || byCategory.containsKey("MOTHERBOARD") || byCategory.containsKey("RAM") || byCategory.containsKey("COOLER")) {
            nodes.add(constraintNode("constraint-compatibility", "MOTHERBOARD", partNameOrFallback(byCategory, "MOTHERBOARD", "기본 호환성"), toolStatus(toolByName, "compatibility"), toolSummary(toolByName, "compatibility", "소켓과 메모리 규격을 확인합니다.")));
        }
        if (!byCategory.isEmpty()) {
            nodes.add(constraintNode("constraint-budget", "PRICE", "예산", toolStatus(toolByName, "price"), budget > 0 ? formatWon(budget) : "예산 미지정"));
            nodes.add(constraintNode("constraint-total-price", "PRICE", "총액", toolStatus(toolByName, "price"), formatWon(total)));
        }
    }

    private Map<String, Object> node(ToolBuildPart part) {
        return MockData.map(
                "id", "part-" + part.category(),
                "partId", part.publicId(),
                "type", "PART",
                "category", part.category(),
                "label", part.name(),
                "status", "PASS",
                "detail", nodeDetail(part),
                "price", firstNumber(part.price(), 0)
        );
    }

    // PART 노드 status는 그 부품이 걸린 엣지들의 최악 status를 따른다(엣지·노드 단일 소스).
    // 예: 램 슬롯 초과면 MOTHERBOARD-RAM 엣지가 FAIL이고 RAM/메인보드 카드 뱃지도 FAIL로 표시된다.
    private static void applyWorstEdgeStatusToPartNodes(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, String> worstByNodeId = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges) {
            String status = text(edge.get("status"));
            if (status == null) {
                continue;
            }
            // 노드 뱃지 계약(PASS/WARN/FAIL) 밖의 status는 최악치 계산에서 제외한다(방어) —
            // 백엔드는 현재 세 값만 내리지만, 계약 밖 값이 끼어도 뱃지를 오염시키지 않게 한다.
            if (!"PASS".equals(status) && !"WARN".equals(status) && !"FAIL".equals(status)) {
                continue;
            }
            for (String endpointKey : List.of("source", "target")) {
                String nodeId = text(edge.get(endpointKey));
                if (nodeId != null && nodeId.startsWith("part-")) {
                    worstByNodeId.merge(nodeId, status, BuildGraphService::worseStatus);
                }
            }
        }
        for (Map<String, Object> node : nodes) {
            if ("PART".equals(node.get("type"))) {
                String worst = worstByNodeId.get(text(node.get("id")));
                if (worst != null) {
                    node.put("status", worst);
                }
            }
        }
    }

    private static String worseStatus(String left, String right) {
        return statusRank(right) > statusRank(left) ? right : left;
    }

    private static int statusRank(String status) {
        return switch (firstText(status, "PASS")) {
            case "FAIL" -> 2;
            case "WARN" -> 1;
            default -> 0;
        };
    }

    private static Map<String, Object> constraintNode(String id, String category, String label, String status, String detail) {
        return MockData.map(
                "id", id,
                "type", "CONSTRAINT",
                "category", category,
                "label", label,
                "status", status,
                "detail", detail
        );
    }

    private static String partNameOrFallback(Map<String, ToolBuildPart> byCategory, String category, String fallback) {
        ToolBuildPart part = byCategory.get(category);
        return part == null || part.name() == null || part.name().isBlank() ? fallback : part.name();
    }

    private static String powerConstraintLabel(Map<String, ToolBuildPart> byCategory) {
        ToolBuildPart psu = byCategory.get("PSU");
        if (psu == null) {
            return "전력 조건";
        }
        Integer capacity = firstAvailableNumber(psu, "capacityW", "ratedCapacityW");
        return capacity == null ? partNameOrFallback(byCategory, "PSU", "전력 조건") : "정격 " + capacity + "W";
    }

    private static void addEdgeIfPossible(
            List<Map<String, Object>> edges,
            Map<String, ToolBuildPart> byCategory,
            String sourceCategory,
            String targetCategory,
            String id,
            String type,
            String status,
            String label,
            String summary
    ) {
        if (!byCategory.containsKey(sourceCategory) || !byCategory.containsKey(targetCategory)) {
            return;
        }
        edges.add(edge(id, "part-" + sourceCategory, "part-" + targetCategory, type, status, label, summary));
    }

    private static Map<String, Object> edge(String id, String source, String target, String type, String status, String label, String summary) {
        return MockData.map(
                "id", id,
                "source", source,
                "target", target,
                "type", type,
                "status", status,
                "label", label,
                "summary", summary
        );
    }

    private List<Map<String, Object>> insights(Map<String, Map<String, Object>> toolByName, Map<String, ToolBuildPart> byCategory, int budget, int total) {
        List<Map<String, Object>> items = new ArrayList<>();
        // compatibility/size는 검사 결과의 issueCategories(실제 걸린 부품쌍)를 우선 사용한다 —
        // 고정 목록은 쿨러 높이 문제에 GPU까지 노랗게 칠하는 식의 오귀속을 만든다.
        addToolInsights(items, toolByName, byCategory, "compatibility", "호환성 확인",
                toolIssueNodeIds(toolByName, "compatibility", List.of("part-CPU", "part-MOTHERBOARD", "part-RAM", "part-COOLER")));
        addToolInsights(items, toolByName, byCategory, "power", "파워 여유 확인", List.of("part-GPU", "part-PSU"));
        addToolInsights(items, toolByName, byCategory, "size", "장착 규격 확인",
                toolIssueNodeIds(toolByName, "size", List.of("part-GPU", "part-CASE", "part-COOLER")));
        addToolInsights(items, toolByName, byCategory, "performance", "성능 균형 확인", List.of("part-CPU", "part-GPU"));
        addToolInsights(items, toolByName, byCategory, "price", budget > 0 && total > budget ? "예산 초과 확인" : "예산 범위 확인", List.of("constraint-budget", "constraint-total-price"));
        if (items.isEmpty() && !byCategory.isEmpty()) {
            items.add(MockData.map(
                    "id", "insight-ready",
                    "status", "PASS",
                    "title", "관계 분석 준비 완료",
                    "description", "선택한 부품 간 핵심 의존성을 표시했습니다.",
                    "relatedNodeIds", byCategory.keySet().stream().map(category -> "part-" + category).toList()
            ));
        }
        return items;
    }

    /** 검사 details의 issueCategories(실제 연루 부품)가 있으면 그 노드만, 없으면(정상 등) 기본 목록. */
    private static List<String> toolIssueNodeIds(Map<String, Map<String, Object>> toolByName, String tool, List<String> fallback) {
        Map<String, Object> details = objectMap(toolByName.getOrDefault(tool, Map.of()).get("details"));
        List<String> categories = stringList(details.get("issueCategories"));
        if (categories.isEmpty()) {
            return fallback;
        }
        return categories.stream().map(category -> "part-" + category).toList();
    }

    /**
     * 툴 결과를 인사이트로 바꾼다. 검사 details.issues(걸린 조건별 사유)가 있으면 사유별로 1개씩 만들어
     * 문장과 연루 부품쌍을 1:1로 귀속한다 — 합집합 relatedNodeIds 하나에 summary 1문장을 얹으면
     * 남의 부품 사유가 무관한 슬롯 팝오버에 붙는 오귀속이 생긴다. issues가 없는 툴(power·price 등)은
     * 기존 단일 인사이트를 유지한다. 모든 인사이트에 tool 필드를 실어(신·구 경로 공히) 프론트가
     * 같은 tool의 엣지 사유와 의미가 중복되는 문구를 억제할 수 있게 한다.
     */
    private static void addToolInsights(
            List<Map<String, Object>> insights,
            Map<String, Map<String, Object>> toolByName,
            Map<String, ToolBuildPart> byCategory,
            String tool,
            String title,
            List<String> fallbackNodeIds
    ) {
        Map<String, Object> result = toolByName.get(tool);
        if (result == null) {
            return;
        }
        String status = status(result);
        if ("PASS".equals(status) && insights.stream().anyMatch(insight -> !"PASS".equals(insight.get("status")))) {
            return;
        }
        List<Map<String, Object>> issues = objectMaps(objectMap(result.get("details")).get("issues"));
        if (issues.isEmpty()) {
            insights.add(MockData.map(
                    "id", "insight-" + tool,
                    "tool", tool,
                    "status", status,
                    "title", title,
                    "description", text(result.get("summary")),
                    "relatedNodeIds", fallbackNodeIds
            ));
            return;
        }
        int index = 0;
        for (Map<String, Object> issue : issues) {
            index++;
            String issueStatus = firstText(text(issue.get("status")), status).toUpperCase(Locale.ROOT);
            // 사유에 연루된 부품 중 실제 장착된 노드만 귀속한다 — 없는 노드 id는 렌더 대상이 없다.
            List<String> relatedNodeIds = stringList(issue.get("categories")).stream()
                    .map(category -> category.toUpperCase(Locale.ROOT))
                    .filter(byCategory::containsKey)
                    .map(category -> "part-" + category)
                    .toList();
            insights.add(MockData.map(
                    "id", "insight-" + tool + "-" + index,
                    "tool", tool,
                    "status", issueStatus,
                    "title", title,
                    "description", text(issue.get("message")),
                    "relatedNodeIds", relatedNodeIds
            ));
        }
    }

    private static List<String> focusNodeIds(List<Map<String, Object>> edges, Map<String, Object> focus, List<Map<String, Object>> insights) {
        String category = normalizeCategory(text(focus.get("category")));
        if (category != null) {
            String nodeId = "part-" + category;
            return edges.stream()
                    .filter(edge -> Objects.equals(edge.get("source"), nodeId) || Objects.equals(edge.get("target"), nodeId))
                    .flatMap(edge -> List.of(text(edge.get("source")), text(edge.get("target"))).stream())
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        }
        return insights.stream()
                .findFirst()
                .map(insight -> stringList(insight.get("relatedNodeIds")))
                .orElse(List.of());
    }

    private static String summary(String mode, Map<String, Object> focus, Map<String, Map<String, Object>> toolByName, List<ToolBuildPart> parts, List<Map<String, Object>> edges) {
        String category = normalizeCategory(text(focus.get("category")));
        if ("PART_IMPACT".equals(mode) && category != null) {
            return category + " 선택으로 영향을 받는 부품과 제약을 확인했습니다.";
        }
        if ("ISSUE_PATH".equals(mode)) {
            return "현재 견적에서 주의가 필요한 관계를 먼저 표시했습니다.";
        }
        if (parts.isEmpty()) {
            return "부품을 담으면 관계 그래프가 자동으로 구성됩니다.";
        }
        // "확인이 필요한 관계 N개"의 N은 화면에 실제로 그려지는 문제(WARN/FAIL) 엣지 수다 —
        // 비-PASS 툴 수로 세면 한 툴이 만든 FAIL 엣지 2개가 "1개"로 축소 전달된다.
        long problemEdgeCount = edges.stream()
                .map(edge -> firstText(text(edge.get("status")), "PASS"))
                .filter(status -> "WARN".equals(status) || "FAIL".equals(status))
                .count();
        // 짝 부품이 없어 엣지로 그려지지 않은 툴 문제만 있는 경우가 '문제 없음'으로 읽히지 않게 폴백한다.
        long warningCount = problemEdgeCount > 0
                ? problemEdgeCount
                : toolByName.values().stream().filter(result -> !"PASS".equals(status(result))).count();
        if (warningCount > 0) {
            return "추천 조합에서 확인이 필요한 관계 " + warningCount + "개를 표시했습니다.";
        }
        return "선택한 조합의 핵심 호환성, 전력, 규격 관계를 확인했습니다.";
    }

    private static String nodeDetail(ToolBuildPart part) {
        return switch (firstText(part.category(), "").toUpperCase(Locale.ROOT)) {
            case "CPU" -> firstText(joinSpecs("소켓 " + attr(part, "socket")), fallbackPartDetail(part));
            case "MOTHERBOARD" -> firstText(motherboardDetail(part), fallbackPartDetail(part));
            case "RAM" -> firstText(joinSpecs(attrValue(part, "memoryType"), capacityDetail(part), moduleCountDetail(part)), fallbackPartDetail(part));
            case "GPU" -> firstText(joinSpecs(wattageDetail(part), lengthDetail(part)), fallbackPartDetail(part));
            case "PSU" -> firstText(psuCapacityDetail(part), fallbackPartDetail(part));
            case "CASE" -> firstText(caseGpuLengthDetail(part), fallbackPartDetail(part));
            case "COOLER" -> firstText(coolerDetail(part), fallbackPartDetail(part));
            case "STORAGE" -> firstText(joinSpecs(storageInterfaceDetail(part), storageCapacityDetail(part)), fallbackPartDetail(part));
            default -> fallbackPartDetail(part);
        };
    }

    private static String motherboardDetail(ToolBuildPart part) {
        List<String> specs = new ArrayList<>();
        addIfPresent(specs, attrValue(part, "socket"));
        addIfPresent(specs, attrValue(part, "memoryType"));
        String wifi = firstText(attrValue(part, "wifi"), booleanAttr(part, "hasWifi") ? "Wi-Fi" : null);
        addIfPresent(specs, wifi);
        if (wifi != null || booleanAttr(part, "bluetooth")) {
            specs.add("Bluetooth");
        }
        return String.join(" · ", specs);
    }

    private static String socketSummary(Map<String, ToolBuildPart> byCategory, String status) {
        String cpuSocket = attr(byCategory.get("CPU"), "socket");
        String boardSocket = attr(byCategory.get("MOTHERBOARD"), "socket");
        String base = "CPU 소켓 " + cpuSocket + " / 메인보드 소켓 " + boardSocket + "입니다.";
        if ("FAIL".equals(status)) {
            return base + " 메인보드 소켓이 CPU와 맞지 않습니다.";
        }
        return base + " 소켓이 일치합니다.";
    }

    /** MOTHERBOARD-RAM 엣지 라벨 — 물리 문제(폼팩터/슬롯)가 규격보다 먼저, 미검사는 '규격 미확인'. */
    private static String memoryLabel(boolean ramFormFactorBad, boolean ramSlotsExceeded, boolean memoryTypeUnknown) {
        if (ramFormFactorBad) {
            return "RAM 폼팩터";
        }
        if (ramSlotsExceeded) {
            return "메모리 슬롯";
        }
        return memoryTypeUnknown ? "규격 미확인" : "DDR 규격";
    }

    private static String memorySummary(
            Map<String, ToolBuildPart> byCategory,
            Map<String, Object> compatibilityDetails,
            String status,
            boolean memoryTypeUnknown
    ) {
        if (Boolean.FALSE.equals(booleanValue(compatibilityDetails.get("ramFormFactorMatched")))) {
            Object bad = compatibilityDetails.get("ramBadFormFactors");
            String badText = bad instanceof List<?> list && !list.isEmpty()
                    ? String.join(", ", list.stream().map(String::valueOf).toList())
                    : "SODIMM/RDIMM";
            return "노트북/서버용 RAM 폼팩터(" + badText + ")는 데스크탑 메인보드에 장착할 수 없습니다.";
        }
        if (Boolean.FALSE.equals(booleanValue(compatibilityDetails.get("ramSlotsMatched")))) {
            Object sticks = compatibilityDetails.get("ramSticksTotal");
            Object slots = compatibilityDetails.get("memorySlots");
            return "RAM 스틱 " + sticks + "개가 메인보드 메모리 슬롯 " + slots + "개를 초과합니다. 수량 또는 구성(단품/2개들이 킷)을 조정해 주세요.";
        }
        String boardType = attr(byCategory.get("MOTHERBOARD"), "memoryType");
        // 혼합 규격(DDR4+DDR5)은 byCategory의 첫 RAM만 보면 보드와 일치해 보일 수 있다 —
        // 전 행 집계(ramMemoryTypes)로 실제 사유를 말한다(인사이트가 억제돼도 엣지 문구가 사실을 유지).
        Object ramTypes = compatibilityDetails.get("ramMemoryTypes");
        if ("FAIL".equals(status) && ramTypes instanceof List<?> types && types.size() > 1) {
            String joined = String.join("·", types.stream().map(String::valueOf).toList());
            return "RAM 규격 " + joined + " / 메인보드 지원 " + boardType
                    + "입니다. 서로 다른 RAM 규격이 함께 담겨 있어 같은 보드에 장착할 수 없습니다.";
        }
        String ramType = attr(byCategory.get("RAM"), "memoryType");
        String base = "RAM " + ramType + " / 메인보드 지원 " + boardType + "입니다.";
        if ("FAIL".equals(status)) {
            return base + " 메모리 규격이 달라 장착할 수 없습니다.";
        }
        // 미검사(규격 정보 결측)를 "규격이 맞습니다"로 단정하지 않는다 — 결측은 결측이라고 말한다.
        if (memoryTypeUnknown) {
            return base + " 메모리 규격 정보가 없어 호환 검사를 못 했습니다.";
        }
        return base + " DDR 규격이 맞습니다.";
    }

    private static String coolerSummary(Map<String, ToolBuildPart> byCategory, Map<String, Object> compatibilityDetails, String status) {
        String cpuSocket = attr(byCategory.get("CPU"), "socket");
        String base = "CPU 소켓 " + cpuSocket + "을 쿨러 장착 지원 목록과 비교합니다.";
        if (Boolean.FALSE.equals(booleanValue(compatibilityDetails.get("coolerSocketMatched")))) {
            return base + " CPU 소켓을 지원하지 않습니다.";
        }
        Integer coolerTdp = numberValue(compatibilityDetails.get("coolerTdpW"));
        Integer cpuTdp = numberValue(compatibilityDetails.get("cpuTdpW"));
        if (Boolean.TRUE.equals(booleanValue(compatibilityDetails.get("coolerTdpChecked"))) && coolerTdp != null && cpuTdp != null) {
            String tdp = " 쿨러 TDP 대응 " + coolerTdp + "W / CPU TDP " + cpuTdp + "W";
            if (Boolean.FALSE.equals(booleanValue(compatibilityDetails.get("coolerTdpMatched")))) {
                return base + tdp + "로 냉각 용량이 부족합니다.";
            }
            if ("WARN".equals(status)) {
                return base + tdp + "로 여유가 20% 미만이라 고부하 시 빠듯합니다.";
            }
            return base + tdp + "로 냉각 여유가 있습니다.";
        }
        if ("FAIL".equals(status)) {
            return base + " CPU 소켓을 지원하지 않습니다.";
        }
        return base + " 쿨러 소켓 지원 범위에 포함됩니다.";
    }

    /** CPU-쿨러 엣지의 TDP 대응 status — 미검사면 PASS를 돌려 소켓 status가 엣지를 결정하게 둔다. */
    private static String coolerTdpStatus(Map<String, Object> compatibilityDetails) {
        if (!Boolean.TRUE.equals(booleanValue(compatibilityDetails.get("coolerTdpChecked")))) {
            return "PASS";
        }
        if (Boolean.FALSE.equals(booleanValue(compatibilityDetails.get("coolerTdpMatched")))) {
            return "FAIL";
        }
        Integer coolerTdp = numberValue(compatibilityDetails.get("coolerTdpW"));
        Integer cpuTdp = numberValue(compatibilityDetails.get("cpuTdpW"));
        if (coolerTdp != null && cpuTdp != null && coolerTdp < Math.round(cpuTdp * 1.2f)) {
            return "WARN";
        }
        return "PASS";
    }

    private static String coolerLabel(Map<String, Object> compatibilityDetails) {
        if (Boolean.FALSE.equals(booleanValue(compatibilityDetails.get("coolerSocketMatched")))) {
            return "쿨러 소켓";
        }
        if (Boolean.FALSE.equals(booleanValue(compatibilityDetails.get("coolerTdpMatched")))) {
            return "TDP 대응 부족";
        }
        Integer coolerTdp = numberValue(compatibilityDetails.get("coolerTdpW"));
        if (Boolean.TRUE.equals(booleanValue(compatibilityDetails.get("coolerTdpChecked"))) && coolerTdp != null) {
            return "TDP 대응 " + coolerTdp + "W";
        }
        return "쿨러 소켓";
    }

    private static String powerSummary(Map<String, Map<String, Object>> toolByName) {
        return powerSummary(toolByName, toolStatus(toolByName, "power"));
    }

    private static String powerSummary(Map<String, Map<String, Object>> toolByName, String status) {
        Map<String, Object> result = toolByName.get("power");
        if (result == null) {
            return "GPU 소비전력과 PSU 정격 출력을 함께 확인합니다.";
        }
        Map<String, Object> details = objectMap(result.get("details"));
        // GPU 노드에 "권장 파워"로 표시되는 값(vendorRecommendedPsuW)을 기준으로 안내해 노드/엣지 숫자를 일치시킨다.
        // 벤더 권장값이 없을 때만 내부 요구 용량(requiredRatedCapacityW)으로 폴백한다.
        Integer recommended = numberValue(details.get("vendorRecommendedPsuW"));
        boolean vendorRecommendationKnown = recommended != null && recommended > 0;
        if (!vendorRecommendationKnown) {
            recommended = numberValue(details.get("requiredRatedCapacityW"));
        }
        Integer capacity = numberValue(details.get("psuRatedCapacityW"));
        Integer loadHeadroom = numberValue(details.get("ratedHeadroomW"));
        if (recommended != null && capacity != null) {
            // 폴백한 내부 추정치(부하+120)에 'GPU 권장 파워' 라벨을 붙이지 않는다 —
            // GPU가 없는 견적에 "GPU 권장 파워 331W"가 뜨는 오귀속을 막는다.
            String base = (vendorRecommendationKnown ? "GPU 권장 파워 " : "필요 정격(추정) ")
                    + recommended + "W / 현재 파워 " + capacity + "W입니다.";
            String room = loadHeadroom == null ? "" : " 지속 부하 대비 여유 " + Math.max(loadHeadroom, 0) + "W";
            if ("FAIL".equals(status)) {
                return base + " PSU 정격 출력이 예상 부하에 못 미쳐 상위 용량이 필요합니다.";
            }
            if ("WARN".equals(status)) {
                return base + room + "로 장착은 가능하지만 여유가 넉넉하지 않습니다.";
            }
            return base + room + "로 안정적입니다.";
        }
        // PSU 용량 결측이면 capacity=null이라 여기로 온다 — 숫자를 지어내지 않고
        // 툴 문구("파워 용량 정보가 없어 전력 검사를 못 했습니다" 등)를 그대로 쓴다.
        return toolSummary(toolByName, "power", "GPU 소비전력과 PSU 정격 출력을 함께 확인합니다.");
    }

    private static String gpuLengthSummary(Map<String, Map<String, Object>> toolByName, String status) {
        Map<String, Object> details = objectMap(toolByName.getOrDefault("size", Map.of()).get("details"));
        Integer gpuLength = numberValue(details.get("gpuLengthMm"));
        Integer maxGpuLength = numberValue(details.get("maxGpuLengthMm"));
        if (gpuLength != null && maxGpuLength != null) {
            int headroom = maxGpuLength - gpuLength;
            String base = "GPU 길이 " + gpuLength + "mm / 케이스 허용 " + maxGpuLength + "mm입니다.";
            if ("FAIL".equals(status)) {
                return base + " 그래픽카드 길이가 케이스 허용 길이를 초과합니다.";
            }
            if ("WARN".equals(status)) {
                return base + " 여유 " + Math.max(headroom, 0) + "mm로 장착은 가능하지만 간섭을 주의해야 합니다.";
            }
            return base + " 여유 " + headroom + "mm입니다.";
        }
        return "GPU 길이가 케이스 허용 길이 안에 있는지 확인합니다.";
    }

    /** COOLER-CASE 엣지 서머리 — 수랭(AIO)은 라디에이터 문구, 공랭은 기존 높이 문구. */
    private static String coolerCaseSummary(Map<String, Map<String, Object>> toolByName, String status) {
        Map<String, Object> details = objectMap(toolByName.getOrDefault("size", Map.of()).get("details"));
        if (Boolean.TRUE.equals(booleanValue(details.get("radiatorChecked")))) {
            Integer radiatorSize = numberValue(details.get("radiatorSizeMm"));
            Object support = details.get("radiatorSupportMm");
            String supportText = support instanceof List<?> list && !list.isEmpty()
                    ? String.join("·", list.stream().map(String::valueOf).toList()) + "mm"
                    : null;
            String base = "라디에이터 " + radiatorSize + "mm / 케이스 지원 " + (supportText == null ? "미확인" : supportText) + "입니다.";
            if ("FAIL".equals(status)) {
                return base + " 이 케이스에는 해당 크기 라디에이터를 장착할 수 없습니다.";
            }
            if ("WARN".equals(status)) {
                return base + " 케이스의 라디에이터 지원 정보가 없어 확인이 필요합니다.";
            }
            return base + " 장착 가능한 크기입니다.";
        }
        return coolerHeightSummary(toolByName, status);
    }

    private static String coolerHeightSummary(Map<String, Map<String, Object>> toolByName, String status) {
        Map<String, Object> details = objectMap(toolByName.getOrDefault("size", Map.of()).get("details"));
        Integer coolerHeight = numberValue(details.get("coolerHeightMm"));
        Integer maxCoolerHeight = numberValue(details.get("maxCpuCoolerHeightMm"));
        if (coolerHeight != null && maxCoolerHeight != null) {
            int headroom = maxCoolerHeight - coolerHeight;
            String base = "쿨러 높이 " + coolerHeight + "mm / 케이스 허용 " + maxCoolerHeight + "mm입니다.";
            if ("FAIL".equals(status)) {
                return base + " 쿨러 높이가 케이스 허용 높이를 초과합니다.";
            }
            if ("WARN".equals(status)) {
                return base + " 여유 " + Math.max(headroom, 0) + "mm로 장착은 가능하지만 간섭을 주의해야 합니다.";
            }
            return base + " 여유 " + headroom + "mm입니다.";
        }
        return "CPU 쿨러 높이가 케이스 제한 안에 있는지 확인합니다.";
    }

    private static String socketLabel(String status) {
        return "FAIL".equals(status) ? "소켓 불일치" : "소켓 일치";
    }

    private static String powerLabel(Map<String, Object> details, String status) {
        if ("FAIL".equals(status)) {
            return "파워 부족";
        }
        // 정격 용량이 결측(null)이면 여유를 계산할 수 없다 — 없는 데이터를 여유가 있는 것처럼 말하지 않는다.
        if (details.containsKey("psuRatedCapacityW") && numberValue(details.get("psuRatedCapacityW")) == null) {
            return "파워 용량 미확인";
        }
        Integer headroom = powerHeadroom(details);
        return headroom == null ? "전력 여유" : "전력 여유 " + Math.max(headroom, 0) + "W";
    }

    private static String gpuLengthLabel(Map<String, Object> details, String status) {
        if ("FAIL".equals(status)) {
            return "장착 불가";
        }
        if ("WARN".equals(status)) {
            return "길이 간섭 주의";
        }
        Integer headroom = headroom(details, "gpuLengthMm", "maxGpuLengthMm");
        return headroom == null ? "장착 길이" : "장착 여유 " + headroom + "mm";
    }

    /** COOLER-CASE 엣지 라벨 — 수랭(AIO)은 라디에이터 크기, 공랭은 기존 높이 여유. */
    private static String coolerCaseLabel(Map<String, Object> details, String status) {
        if (Boolean.TRUE.equals(booleanValue(details.get("radiatorChecked")))) {
            if ("FAIL".equals(status)) {
                return "라디에이터 장착 불가";
            }
            if ("WARN".equals(status)) {
                return "라디 지원 미확인";
            }
            Integer radiatorSize = numberValue(details.get("radiatorSizeMm"));
            return radiatorSize == null ? "라디에이터" : "라디에이터 " + radiatorSize + "mm";
        }
        return coolerHeightLabel(details, status);
    }

    /** COOLER-CASE 엣지의 라디에이터 status — 케이스 지원 데이터 결측이면 WARN. */
    private static String radiatorStatus(Map<String, Object> details) {
        if (details.get("radiatorSupportMm") == null) {
            return "WARN";
        }
        return Boolean.FALSE.equals(booleanValue(details.get("radiatorMatched"))) ? "FAIL" : "PASS";
    }

    /** MOTHERBOARD-STORAGE 엣지 status — M.2 슬롯 판정은 ToolCheckService.compatibility()가 단일 소스다. */
    private static String m2StorageStatus(Map<String, Object> details) {
        if (Boolean.TRUE.equals(booleanValue(details.get("m2SlotsChecked")))) {
            return Boolean.FALSE.equals(booleanValue(details.get("m2SlotsMatched"))) ? "FAIL" : "PASS";
        }
        // M.2 저장장치가 0개면 검사할 것이 없는 정상 구성이다(SATA는 M.2 슬롯을 쓰지 않는다) —
        // '미검사=WARN'을 그대로 내면 SATA만 담은 정상 견적이 유령 '간섭 주의'가 된다(툴은 PASS).
        Integer m2StorageTotal = numberValue(details.get("m2StorageTotal"));
        if (m2StorageTotal != null && m2StorageTotal == 0) {
            return "PASS";
        }
        return "WARN";
    }

    private static String storageLabel(Map<String, Object> details, String status, boolean exceeded) {
        if ("FAIL".equals(status) || exceeded) {
            return "M.2 슬롯 부족";
        }
        Integer m2Slots = numberValue(details.get("m2Slots"));
        Integer used = numberValue(details.get("m2StorageTotal"));
        // M.2 저장장치가 0개면 슬롯을 쓰지 않는 구성이다 — "M.2 0/2" 같은 무의미 카운트를 걸지 않는다.
        if (used != null && used == 0) {
            return "M.2 슬롯 미사용";
        }
        if ("WARN".equals(status)) {
            return "M.2 슬롯 미확인";
        }
        return m2Slots == null ? "M.2 장착" : "M.2 " + firstText(String.valueOf(used), "0") + "/" + m2Slots;
    }

    private static String storageSummary(Map<String, Object> details, String status) {
        Integer m2Slots = numberValue(details.get("m2Slots"));
        Integer used = numberValue(details.get("m2StorageTotal"));
        // SATA 등 M.2 방식이 아닌 저장장치만 담긴 구성 — "M.2 SSD 0개 / 슬롯 N개" 같은 무의미 문구를 내지 않는다.
        if (used != null && used == 0) {
            return "선택한 저장장치는 M.2 슬롯을 사용하지 않습니다.";
        }
        if (m2Slots != null && used != null) {
            String base = "M.2 SSD " + used + "개 / 메인보드 M.2 슬롯 " + m2Slots + "개입니다.";
            if ("FAIL".equals(status)) {
                return base + " 장착 가능한 슬롯 수를 초과합니다.";
            }
            return base;
        }
        // M.2 저장장치는 있는데 보드 슬롯 데이터가 없어 검사를 생략한 경우 — 결측을 결측이라고 말한다.
        if (used != null && used > 0 && m2Slots == null) {
            return "메인보드 M.2 슬롯 정보가 없어 장착 검사를 못 했습니다.";
        }
        return "M.2 SSD 수가 메인보드 슬롯 안에 있는지 확인합니다.";
    }

    /** MOTHERBOARD-CASE 엣지 status — 폼팩터 판정은 ToolCheckService.size()가 단일 소스다. */
    private static String boardFitStatus(Map<String, Object> details) {
        if (Boolean.TRUE.equals(booleanValue(details.get("boardFormFactorChecked")))) {
            return Boolean.FALSE.equals(booleanValue(details.get("boardFormFactorMatched"))) ? "FAIL" : "PASS";
        }
        return "WARN";
    }

    private static String boardFitLabel(Map<String, Object> details, String status) {
        if ("FAIL".equals(status)) {
            return "보드 규격 미지원";
        }
        if ("WARN".equals(status)) {
            return "보드 규격 미확인";
        }
        Object boardFormFactor = details.get("boardFormFactor");
        return boardFormFactor == null ? "보드 규격" : boardFormFactor + " 장착";
    }

    private static String boardFitSummary(Map<String, Object> details, String status) {
        Object boardFormFactor = details.get("boardFormFactor");
        Object caseMaxFormFactor = details.get("caseMaxFormFactor");
        if (boardFormFactor != null && caseMaxFormFactor != null) {
            String base = "메인보드 규격 " + boardFormFactor + " / 케이스 지원 최대 " + caseMaxFormFactor + "입니다.";
            if ("FAIL".equals(status)) {
                return base + " 케이스가 이 보드 규격의 장착을 지원하지 않습니다.";
            }
            return base;
        }
        return "메인보드 규격이 케이스 지원 범위에 있는지 확인합니다.";
    }

    private static String psuDepthLabel(Map<String, Object> details, String status) {
        if ("FAIL".equals(status)) {
            return "파워 깊이 초과";
        }
        if ("WARN".equals(status)) {
            return "파워 깊이 주의";
        }
        Integer headroom = headroom(details, "psuDepthMm", "maxPsuLengthMm");
        return headroom == null ? "파워 깊이" : "깊이 여유 " + headroom + "mm";
    }

    private static String psuDepthSummary(Map<String, Map<String, Object>> toolByName, String status) {
        Map<String, Object> details = objectMap(toolByName.getOrDefault("size", Map.of()).get("details"));
        Integer psuDepth = numberValue(details.get("psuDepthMm"));
        Integer maxPsuLength = numberValue(details.get("maxPsuLengthMm"));
        if (psuDepth != null && maxPsuLength != null) {
            int headroom = maxPsuLength - psuDepth;
            String base = "파워 깊이 " + psuDepth + "mm / 케이스 허용 " + maxPsuLength + "mm입니다.";
            if ("FAIL".equals(status)) {
                return base + " 파워 깊이가 케이스 허용 길이를 초과합니다.";
            }
            if ("WARN".equals(status)) {
                return base + " 여유 " + Math.max(headroom, 0) + "mm로 장착은 가능하지만 간섭을 주의해야 합니다.";
            }
            return base + " 여유 " + headroom + "mm입니다.";
        }
        return "파워 깊이가 케이스 허용 길이 안에 있는지 확인합니다.";
    }

    private static String coolerHeightLabel(Map<String, Object> details, String status) {
        if ("FAIL".equals(status)) {
            return "높이 간섭";
        }
        if ("WARN".equals(status)) {
            return "높이 간섭 주의";
        }
        Integer headroom = headroom(details, "coolerHeightMm", "maxCpuCoolerHeightMm");
        return headroom == null ? "쿨러 높이" : "높이 여유 " + headroom + "mm";
    }

    private static String socketStatus(Map<String, ToolBuildPart> byCategory, Map<String, Object> details, String fallback) {
        Boolean socketMatched = booleanValue(details.get("socketMatched"));
        if (socketMatched != null) {
            return socketMatched ? "PASS" : "FAIL";
        }
        String cpuSocket = attrValue(byCategory.get("CPU"), "socket");
        String boardSocket = attrValue(byCategory.get("MOTHERBOARD"), "socket");
        if (cpuSocket != null && boardSocket != null) {
            return cpuSocket.equalsIgnoreCase(boardSocket) ? "PASS" : "FAIL";
        }
        return fallback;
    }

    private static String booleanStatus(Boolean passed, String fallback) {
        return passed == null ? fallback : passed ? "PASS" : "FAIL";
    }

    private static String lengthStatus(
            Map<String, Object> details,
            String currentKey,
            String maxKey,
            int warnBelowMm,
            String fallback
    ) {
        return BuildSizeFitPolicy.graphStatus(
                numberValue(details.get(currentKey)),
                numberValue(details.get(maxKey)),
                warnBelowMm,
                fallback
        );
    }

    private static Integer powerHeadroom(Map<String, Object> details) {
        // 엣지 라벨의 "전력 여유"는 실제 지속 부하 대비 여유(정격 - 추정 부하)를 보여준다.
        Integer ratedHeadroom = numberValue(details.get("ratedHeadroomW"));
        if (ratedHeadroom != null) {
            return ratedHeadroom;
        }
        Integer required = numberValue(details.get("requiredRatedCapacityW"));
        Integer capacity = numberValue(details.get("psuRatedCapacityW"));
        return (required != null && capacity != null) ? capacity - required : null;
    }

    private static Integer headroom(Map<String, Object> details, String currentKey, String maxKey) {
        Integer current = numberValue(details.get(currentKey));
        Integer max = numberValue(details.get(maxKey));
        if (current == null || max == null) {
            return null;
        }
        return max - current;
    }

    private static Map<String, Object> toolDetails(Map<String, Map<String, Object>> toolByName, String tool) {
        return objectMap(toolByName.getOrDefault(tool, Map.of()).get("details"));
    }

    private static String joinSpecs(String... specs) {
        List<String> values = new ArrayList<>();
        for (String spec : specs) {
            addIfPresent(values, spec);
        }
        return String.join(" · ", values);
    }

    private static void addIfPresent(List<String> specs, String value) {
        if (value != null && !value.isBlank() && !"미확인".equals(value)) {
            specs.add(value);
        }
    }

    private static String capacityDetail(ToolBuildPart part) {
        Integer capacityGb = numberValue(part.attributes().get("capacityGb"));
        return capacityGb == null ? null : formatStorageCapacity(capacityGb);
    }

    private static String storageCapacityDetail(ToolBuildPart part) {
        Integer capacityGb = numberValue(part.attributes().get("capacityGb"));
        return capacityGb == null ? null : formatStorageCapacity(capacityGb);
    }

    private static String moduleCountDetail(ToolBuildPart part) {
        Integer moduleCount = numberValue(part.attributes().get("moduleCount"));
        return moduleCount == null ? null : moduleCount + "개";
    }

    private static String wattageDetail(ToolBuildPart part) {
        Integer wattage = firstAvailableNumber(part, "wattage", "tdpW");
        return wattage == null ? null : wattage + "W";
    }

    private static String lengthDetail(ToolBuildPart part) {
        Integer length = numberValue(part.attributes().get("lengthMm"));
        return length == null ? null : "길이 " + length + "mm";
    }

    private static String psuCapacityDetail(ToolBuildPart part) {
        Integer capacity = firstAvailableNumber(part, "capacityW", "ratedCapacityW");
        return capacity == null ? null : "정격 " + capacity + "W";
    }

    private static String caseGpuLengthDetail(ToolBuildPart part) {
        Integer maxGpuLength = numberValue(part.attributes().get("maxGpuLengthMm"));
        return maxGpuLength == null ? null : "GPU 최대 " + maxGpuLength + "mm";
    }

    private static String coolerDetail(ToolBuildPart part) {
        // 수랭(AIO)의 heightMm는 라디에이터 두께(27~38mm)라 "높이 38mm"로 쓰면 오독된다 —
        // 수랭은 라디에이터 크기를 말하고, 크기 결측이면 두께를 높이처럼 표기하지 않는다.
        if (isLiquidCooler(part)) {
            Integer radiatorSize = numberValue(part.attributes().get("radiatorSizeMm"));
            if (radiatorSize != null && radiatorSize > 0) {
                return "라디에이터 " + radiatorSize + "mm";
            }
            return coolerSocketSupportDetail(part);
        }
        Integer height = firstAvailableNumber(part, "heightMm", "coolerHeightMm");
        if (height != null) {
            return "높이 " + height + "mm";
        }
        return coolerSocketSupportDetail(part);
    }

    private static String coolerSocketSupportDetail(ToolBuildPart part) {
        Object support = part.attributes().get("socketSupport");
        if (support instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0)) + " 지원";
        }
        return null;
    }

    /** 수랭(AIO) 판별 — LIQUID 외에 AIO/WATER/수랭 표기도 수랭으로 본다(ToolCheckService와 동일 규칙). */
    private static boolean isLiquidCooler(ToolBuildPart part) {
        String coolerType = attrValue(part, "coolerType");
        if (coolerType == null) {
            return false;
        }
        String normalized = coolerType.toUpperCase(Locale.ROOT);
        return normalized.contains("LIQUID") || normalized.contains("AIO")
                || normalized.contains("WATER") || normalized.contains("수랭");
    }

    private static String storageInterfaceDetail(ToolBuildPart part) {
        String value = firstText(attrValue(part, "interface"), attrValue(part, "formFactor"));
        if (value == null) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT).contains("nvme") ? "NVMe" : value;
    }

    private static String formatStorageCapacity(int capacityGb) {
        if (capacityGb >= 1024 && capacityGb % 1024 == 0) {
            return (capacityGb / 1024) + "TB";
        }
        return capacityGb + "GB";
    }

    private static Integer firstAvailableNumber(ToolBuildPart part, String... keys) {
        for (String key : keys) {
            Integer value = numberValue(part.attributes().get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String fallbackPartDetail(ToolBuildPart part) {
        return part.manufacturer() == null ? formatWon(firstNumber(part.price(), 0)) : part.manufacturer() + " · " + formatWon(firstNumber(part.price(), 0));
    }

    private static String priceSummary(Map<String, Map<String, Object>> toolByName, int budget, int total) {
        Map<String, Object> result = toolByName.get("price");
        if (result != null) {
            return toolSummary(toolByName, "price", "예산과 총액을 비교합니다.");
        }
        return "예산 " + formatWon(budget) + " / 총액 " + formatWon(total) + " 기준입니다.";
    }

    private static String toolSummary(Map<String, Map<String, Object>> toolByName, String tool, String fallback) {
        Map<String, Object> result = toolByName.get(tool);
        if (result == null) {
            return fallback;
        }
        return firstText(text(result.get("summary")), fallback);
    }

    private static String toolStatus(Map<String, Map<String, Object>> toolByName, String tool) {
        return status(toolByName.get(tool));
    }

    private static String status(Map<String, Object> result) {
        if (result == null) {
            return "WARN";
        }
        String value = text(result.get("status"));
        return value == null ? "WARN" : value.toUpperCase(Locale.ROOT);
    }

    private static String attr(ToolBuildPart part, String key) {
        if (part == null) {
            return "미확인";
        }
        String value = attrValue(part, key);
        return value == null ? "미확인" : value;
    }

    private static String attrValue(ToolBuildPart part, String key) {
        if (part == null) {
            return null;
        }
        Object value = part.attributes().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean booleanAttr(ToolBuildPart part, String key) {
        return Boolean.TRUE.equals(booleanValue(part == null ? null : part.attributes().get(key)));
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "no".equals(normalized) || "0".equals(normalized)) {
            return false;
        }
        return null;
    }

    private static int total(List<ToolBuildPart> parts) {
        // 수량 가중 — 그래프의 '총액' 노드가 UI 총액(lineTotal 합)과 같은 규칙으로 계산되게 한다.
        return parts.stream().mapToInt(part -> firstNumber(part.price(), 0) * part.effectiveQuantity()).sum();
    }

    private static String normalizedMode(String value) {
        if (value == null || value.isBlank()) {
            return "BUILD_OVERVIEW";
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "BUILD_OVERVIEW", "PART_IMPACT", "ISSUE_PATH", "DRAFT_ACTION" -> normalized;
            default -> "BUILD_OVERVIEW";
        };
    }

    private static String normalizeCategory(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        return CATEGORIES.contains(normalized) ? normalized : null;
    }

    private static List<Map<String, Object>> objectMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> objectMap(item))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> {
                if (key != null) {
                    result.put(String.valueOf(key), mapValue);
                }
            });
            return result;
        }
        if (value == null) {
            return Map.of();
        }
        return (Map<String, Object>) DbValueMapper.json(Map.of("json", value), "json", Map.of());
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int firstNumber(Object value, int fallback) {
        Integer number = numberValue(value);
        return number == null ? fallback : number;
    }

    private static Integer numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(value.toString()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }


    private static String formatWon(int value) {
        return String.format(Locale.KOREA, "%,d원", value);
    }

    private record GraphDraft(
            String summary,
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges,
            List<String> focusNodeIds,
            List<Map<String, Object>> insights
    ) {
    }
}
