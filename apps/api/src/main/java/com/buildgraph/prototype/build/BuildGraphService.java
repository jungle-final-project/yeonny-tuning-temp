package com.buildgraph.prototype.build;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.ToolBuildPart;
import com.buildgraph.prototype.part.ToolCheckService;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BuildGraphService {
    private static final Set<String> CATEGORIES = Set.of("CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER");
    private final JdbcTemplate jdbcTemplate;
    private final ToolCheckService toolCheckService;
    private final CurrentUserService currentUserService;

    public BuildGraphService(JdbcTemplate jdbcTemplate, ToolCheckService toolCheckService, CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.toolCheckService = toolCheckService;
        this.currentUserService = currentUserService;
    }

    public Map<String, Object> resolve(String authorization, Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String source = firstText(text(body.get("source")), "AI_BUILD").toUpperCase(Locale.ROOT);
        String view = firstText(text(body.get("view")), "FOCUSED").toUpperCase(Locale.ROOT);
        Map<String, Object> focus = objectMap(body.get("focus"));
        String mode = normalizedMode(text(focus.get("mode")));
        List<ToolBuildPart> parts = switch (source) {
            case "AI_BUILD" -> aiBuildParts(body);
            case "QUOTE_DRAFT_CURRENT" -> currentQuoteDraftParts(authorization);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 그래프 source입니다.");
        };
        int total = total(parts);
        int budget = firstNumber(body.get("budgetWon"), total);
        List<Map<String, Object>> toolResults = parts.isEmpty() ? List.of() : toolCheckService.checkBuild(parts, budget);
        GraphDraft draft = buildGraph(parts, toolResults, mode, view, focus, budget, total);
        return MockData.map(
                "mode", mode,
                "summary", draft.summary(),
                "nodes", draft.nodes(),
                "edges", draft.edges(),
                "focusNodeIds", draft.focusNodeIds(),
                "insights", draft.insights(),
                "toolResults", toolResults
        );
    }

    private List<ToolBuildPart> aiBuildParts(Map<String, Object> body) {
        List<Map<String, Object>> items = objectMaps(body.get("items"));
        if (items.isEmpty()) {
            return List.of();
        }
        List<ToolBuildPart> parts = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String partId = text(item.get("partId"));
            if (partId == null || partId.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partId가 필요합니다.");
            }
            ToolBuildPart part = partByPublicId(partId);
            String requestedCategory = normalizeCategory(text(item.get("category")));
            if (requestedCategory != null && !requestedCategory.equals(part.category())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partId와 category가 일치하지 않습니다.");
            }
            parts.add(part);
        }
        return parts;
    }

    private ToolBuildPart partByPublicId(String publicId) {
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               category,
                               name,
                               manufacturer,
                               price,
                               attributes
                        FROM parts
                        WHERE public_id::text = ?
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        """, publicId)
                .stream()
                .findFirst()
                .map(this::part)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "활성 부품을 찾을 수 없습니다."));
    }

    private List<ToolBuildPart> currentQuoteDraftParts(String authorization) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        List<Map<String, Object>> drafts = jdbcTemplate.queryForList("""
                SELECT id AS internal_id,
                       public_id::text AS id,
                       status,
                       name
                FROM quote_drafts
                WHERE user_id = ?
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """, user.internalId());
        if (drafts.isEmpty()) {
            return List.of();
        }
        Long draftId = longValue(drafts.get(0).get("internal_id"));
        return jdbcTemplate.queryForList("""
                        SELECT p.id AS internal_id,
                               p.public_id::text AS part_id,
                               qdi.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price AS current_price,
                               qdi.quantity,
                               p.attributes
                        FROM quote_draft_items qdi
                        JOIN parts p ON p.id = qdi.part_id
                        WHERE qdi.quote_draft_id = ?
                          AND qdi.deleted_at IS NULL
                          AND p.deleted_at IS NULL
                        ORDER BY qdi.id
                        """, draftId)
                .stream()
                .map(row -> new ToolBuildPart(
                        longValue(row.get("internal_id")),
                        DbValueMapper.string(row, "part_id"),
                        DbValueMapper.string(row, "category"),
                        DbValueMapper.string(row, "name"),
                        DbValueMapper.string(row, "manufacturer"),
                        numberValue(row.get("current_price")),
                        objectMap(row.get("attributes"))
                ))
                .toList();
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
        String memoryStatus = booleanStatus(booleanValue(compatibilityDetails.get("memoryTypeMatched")), toolStatus(toolByName, "compatibility"));
        String coolerSocketStatus = booleanStatus(booleanValue(compatibilityDetails.get("coolerSocketMatched")), toolStatus(toolByName, "compatibility"));
        String powerStatus = powerStatus(powerDetails, toolStatus(toolByName, "power"));
        String gpuLengthStatus = lengthStatus(sizeDetails, "gpuLengthMm", "maxGpuLengthMm", toolStatus(toolByName, "size"));
        String coolerHeightStatus = lengthStatus(sizeDetails, "coolerHeightMm", "maxCpuCoolerHeightMm", toolStatus(toolByName, "size"));

        List<Map<String, Object>> edges = new ArrayList<>();
        addEdgeIfPossible(edges, byCategory, "CPU", "MOTHERBOARD", "edge-cpu-board-socket", "REQUIRES", socketStatus, socketLabel(socketStatus), socketSummary(byCategory, socketStatus));
        addEdgeIfPossible(edges, byCategory, "MOTHERBOARD", "RAM", "edge-board-ram-memory", "REQUIRES", memoryStatus, "DDR 규격", memorySummary(byCategory, memoryStatus));
        addEdgeIfPossible(edges, byCategory, "CPU", "COOLER", "edge-cpu-cooler-socket", "REQUIRES", coolerSocketStatus, "쿨러 소켓", coolerSummary(byCategory, coolerSocketStatus));
        addEdgeIfPossible(edges, byCategory, "GPU", "PSU", "edge-gpu-psu-power", "AFFECTS", powerStatus, powerLabel(powerDetails, powerStatus), powerSummary(toolByName, powerStatus));
        addEdgeIfPossible(edges, byCategory, "GPU", "CASE", "edge-gpu-case-length", "REQUIRES", gpuLengthStatus, gpuLengthLabel(sizeDetails, gpuLengthStatus), gpuLengthSummary(toolByName, gpuLengthStatus));
        addEdgeIfPossible(edges, byCategory, "COOLER", "CASE", "edge-cooler-case-height", "REQUIRES", coolerHeightStatus, coolerHeightLabel(sizeDetails, coolerHeightStatus), coolerHeightSummary(toolByName, coolerHeightStatus));
        addEdgeIfPossible(edges, byCategory, "CPU", "GPU", "edge-cpu-gpu-performance", "AFFECTS", toolStatus(toolByName, "performance"), "작업 성능", toolSummary(toolByName, "performance", "CPU와 GPU 조합으로 작업 적합도를 확인합니다."));
        if (!parts.isEmpty()) {
            edges.add(edge("edge-budget-total-price", "constraint-budget", "constraint-total-price", "AFFECTS", toolStatus(toolByName, "price"), "예산", priceSummary(toolByName, budget, total)));
        }

        List<Map<String, Object>> insights = insights(toolByName, byCategory, budget, total);
        List<String> focusNodeIds = focusNodeIds(edges, focus, insights);
        String summary = summary(mode, focus, toolByName, byCategory, parts);
        return new GraphDraft(summary, nodes, edges, focusNodeIds, insights);
    }

    private void addConstraintNodes(List<Map<String, Object>> nodes, Map<String, ToolBuildPart> byCategory, Map<String, Map<String, Object>> toolByName, int budget, int total) {
        if (byCategory.containsKey("GPU") || byCategory.containsKey("PSU")) {
            nodes.add(constraintNode("constraint-power", "PSU", powerConstraintLabel(byCategory), toolStatus(toolByName, "power"), powerSummary(toolByName)));
        }
        if (byCategory.containsKey("GPU") || byCategory.containsKey("CASE") || byCategory.containsKey("COOLER")) {
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
        addToolInsight(items, toolByName, "compatibility", "호환성 확인", List.of("part-CPU", "part-MOTHERBOARD", "part-RAM", "part-COOLER"));
        addToolInsight(items, toolByName, "power", "파워 여유 확인", List.of("part-GPU", "part-PSU"));
        addToolInsight(items, toolByName, "size", "장착 규격 확인", List.of("part-GPU", "part-CASE", "part-COOLER"));
        addToolInsight(items, toolByName, "performance", "성능 균형 확인", List.of("part-CPU", "part-GPU"));
        addToolInsight(items, toolByName, "price", budget > 0 && total > budget ? "예산 초과 확인" : "예산 범위 확인", List.of("constraint-budget", "constraint-total-price"));
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

    private static void addToolInsight(List<Map<String, Object>> insights, Map<String, Map<String, Object>> toolByName, String tool, String title, List<String> nodeIds) {
        Map<String, Object> result = toolByName.get(tool);
        if (result == null) {
            return;
        }
        String status = status(result);
        if ("PASS".equals(status) && insights.stream().anyMatch(insight -> !"PASS".equals(insight.get("status")))) {
            return;
        }
        insights.add(MockData.map(
                "id", "insight-" + tool,
                "status", status,
                "title", title,
                "description", text(result.get("summary")),
                "relatedNodeIds", nodeIds
        ));
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

    private static String summary(String mode, Map<String, Object> focus, Map<String, Map<String, Object>> toolByName, Map<String, ToolBuildPart> byCategory, List<ToolBuildPart> parts) {
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
        long warningCount = toolByName.values().stream().filter(result -> !"PASS".equals(status(result))).count();
        if (warningCount > 0) {
            return "추천 조합에서 확인이 필요한 관계 " + warningCount + "개를 표시했습니다.";
        }
        return "선택한 조합의 핵심 호환성, 전력, 규격 관계를 확인했습니다.";
    }

    private ToolBuildPart part(Map<String, Object> row) {
        return new ToolBuildPart(
                longValue(row.get("internal_id")),
                firstText(DbValueMapper.string(row, "id"), DbValueMapper.string(row, "part_id")),
                DbValueMapper.string(row, "category"),
                DbValueMapper.string(row, "name"),
                DbValueMapper.string(row, "manufacturer"),
                firstNumber(row.get("price"), firstNumber(row.get("current_price"), 0)),
                objectMap(row.get("attributes"))
        );
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

    private static String memorySummary(Map<String, ToolBuildPart> byCategory, String status) {
        String ramType = attr(byCategory.get("RAM"), "memoryType");
        String boardType = attr(byCategory.get("MOTHERBOARD"), "memoryType");
        String base = "RAM " + ramType + " / 메인보드 지원 " + boardType + "입니다.";
        if ("FAIL".equals(status)) {
            return base + " 메인보드 RAM 규격 확인이 필요합니다.";
        }
        return base + " DDR 규격이 맞습니다.";
    }

    private static String coolerSummary(Map<String, ToolBuildPart> byCategory, String status) {
        String cpuSocket = attr(byCategory.get("CPU"), "socket");
        String base = "CPU 소켓 " + cpuSocket + "을 쿨러 장착 지원 목록과 비교합니다.";
        if ("FAIL".equals(status)) {
            return base + " CPU 소켓을 지원하지 않습니다.";
        }
        return base + " 쿨러 소켓 지원 범위에 포함됩니다.";
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
        Integer required = numberValue(details.get("requiredRatedCapacityW"));
        Integer capacity = numberValue(details.get("psuRatedCapacityW"));
        if (required != null && capacity != null) {
            Integer headroom = capacity - required;
            String base = "권장 출력 " + required + "W / 현재 파워 " + capacity + "W입니다.";
            if ("FAIL".equals(status)) {
                return base + " 권장 출력보다 파워 여유가 부족합니다.";
            }
            if ("WARN".equals(status)) {
                return base + " 여유 " + Math.max(headroom, 0) + "W로 장착은 가능하지만 권장 여유가 낮습니다.";
            }
            return base + " 여유 " + Math.max(headroom, 0) + "W로 안정적인 편입니다.";
        }
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

    private static String powerStatus(Map<String, Object> details, String fallback) {
        Integer headroom = powerHeadroom(details);
        if (headroom == null) {
            return fallback;
        }
        if (headroom < 50) {
            return "FAIL";
        }
        if (headroom < 150) {
            return "WARN";
        }
        return "PASS";
    }

    private static String lengthStatus(Map<String, Object> details, String currentKey, String maxKey, String fallback) {
        Integer headroom = headroom(details, currentKey, maxKey);
        if (headroom == null) {
            return fallback;
        }
        if (headroom < 0) {
            return "FAIL";
        }
        if (headroom < 30) {
            return "WARN";
        }
        return "PASS";
    }

    private static Integer powerHeadroom(Map<String, Object> details) {
        Integer required = numberValue(details.get("requiredRatedCapacityW"));
        Integer capacity = numberValue(details.get("psuRatedCapacityW"));
        if (required != null && capacity != null) {
            return capacity - required;
        }
        return numberValue(details.get("ratedHeadroomW"));
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
        Integer height = firstAvailableNumber(part, "heightMm", "coolerHeightMm");
        if (height != null) {
            return "높이 " + height + "mm";
        }
        Object support = part.attributes().get("socketSupport");
        if (support instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0)) + " 지원";
        }
        return null;
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
        return parts.stream().mapToInt(part -> firstNumber(part.price(), 0)).sum();
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

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        return Long.valueOf(value.toString());
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
