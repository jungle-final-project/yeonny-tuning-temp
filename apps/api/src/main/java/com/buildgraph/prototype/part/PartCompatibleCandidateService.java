package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PartCompatibleCandidateService {
    private static final Set<String> CATEGORIES = Set.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER");
    private final JdbcTemplate jdbcTemplate;
    private final ToolCheckService toolCheckService;

    public PartCompatibleCandidateService(JdbcTemplate jdbcTemplate, ToolCheckService toolCheckService) {
        this.jdbcTemplate = jdbcTemplate;
        this.toolCheckService = toolCheckService;
    }

    public Map<String, Object> compatibleCandidates(CurrentUserService.CurrentUser user, Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String source = firstText(text(body.get("source")), "AI_BUILD").toUpperCase(Locale.ROOT);
        String category = normalizeCategory(text(body.get("category")));
        if (category == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 부품 카테고리입니다.");
        }
        int limit = Math.min(Math.max(firstNumber(body.get("limit"), 5), 1), 10);
        List<ToolBuildPart> baseParts = switch (source) {
            case "AI_BUILD" -> aiBuildParts(body);
            case "QUOTE_DRAFT_CURRENT" -> currentQuoteDraftParts(user);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 후보 source입니다.");
        };
        List<String> checkedTools = checkedTools(category);
        List<String> selectedPartIds = baseParts.stream()
                .filter(part -> category.equals(part.category()))
                .map(ToolBuildPart::publicId)
                .toList();
        List<CandidateEvaluation> evaluations = activeCandidates(category, Math.max(20, limit * 4)).stream()
                .filter(candidate -> !selectedPartIds.contains(candidate.toolPart().publicId()))
                .map(candidate -> evaluate(baseParts, candidate, category, checkedTools, "REPLACE", null))
                .sorted(Comparator
                        .comparingInt((CandidateEvaluation evaluation) -> statusRank(evaluation.status()))
                        .thenComparingInt(evaluation -> firstNumber(evaluation.partMap().get("price"), 0)))
                .toList();
        List<Map<String, Object>> accepted = evaluations.stream()
                .filter(evaluation -> !"FAIL".equals(evaluation.status()))
                .limit(limit)
                .map(CandidateEvaluation::response)
                .toList();
        long rejectedCount = evaluations.stream().filter(evaluation -> "FAIL".equals(evaluation.status())).count();
        return MockData.map(
                "category", category,
                "items", accepted,
                "rejectedCount", (int) rejectedCount,
                "warnings", List.of()
        );
    }

    public List<Map<String, Object>> partRowsWithCompatibility(
            CurrentUserService.CurrentUser user,
            String source,
            String category,
            String compatibilityMode,
            String replaceTargetPartId,
            List<Map<String, Object>> rows
    ) {
        String normalizedSource = firstText(text(source), "QUOTE_DRAFT_CURRENT").toUpperCase(Locale.ROOT);
        String normalizedCategory = normalizeCategory(category);
        if (normalizedCategory == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 부품 카테고리입니다.");
        }
        // 담기/교체 의미론 — 생략 시 REPLACE(교체-전체)로 기존 소비자 동작을 그대로 유지한다.
        String normalizedMode = firstText(text(compatibilityMode), "REPLACE").toUpperCase(Locale.ROOT);
        if (!"ADD".equals(normalizedMode) && !"REPLACE".equals(normalizedMode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 compatibilityMode입니다.");
        }
        String normalizedTarget = text(replaceTargetPartId);
        if ("ADD".equals(normalizedMode) && normalizedTarget != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "compatibilityMode=ADD에서는 replaceTargetPartId를 지정할 수 없습니다.");
        }
        List<ToolBuildPart> baseParts = switch (normalizedSource) {
            case "QUOTE_DRAFT_CURRENT" -> currentQuoteDraftParts(user);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 compatibilitySource입니다.");
        };
        List<String> selectedCategories = baseParts.stream()
                .map(ToolBuildPart::category)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        List<String> checkedTools = checkedTools(normalizedCategory);
        return rows.stream()
                .map(row -> {
                    CandidateEvaluation evaluation = evaluate(baseParts, new CandidatePart(toolPart(row), responsePart(row)), normalizedCategory, checkedTools, normalizedMode, normalizedTarget);
                    Map<String, Object> part = new LinkedHashMap<>(evaluation.partMap());
                    part.put("compatibility", evaluation.partListCompatibility());
                    // 추천기는 Tool이 이미 적재한 결과와 현재 선택 category만 읽는다. 응답 직전
                    // PartQueryService가 제거하는 내부 필드라 클라이언트 계약에는 노출되지 않는다.
                    part.put("_candidateToolResults", evaluation.toolResults());
                    part.put("_recommendationContext", Map.of("selectedCategories", selectedCategories));
                    return part;
                })
                .toList();
    }

    /**
     * Filters a caller-provided candidate list against the authoritative active quote draft.
     * Build Chat uses this gate immediately before exposing recommendation chips, so a candidate
     * rejected by the same compatibility Tool policy as the parts screen cannot be recommended.
     */
    public List<String> compatibleCandidateIds(
            CurrentUserService.CurrentUser user,
            String category,
            String compatibilityMode,
            List<String> candidatePartIds
    ) {
        return compatibleCandidateIds(user, category, compatibilityMode, candidatePartIds, Integer.MAX_VALUE);
    }

    /**
     * Returns compatible ids in caller order and stops as soon as enough display candidates exist.
     * Build Chat can therefore scan a wider fallback pool without Tool-checking every product.
     */
    public List<String> compatibleCandidateIds(
            CurrentUserService.CurrentUser user,
            String category,
            String compatibilityMode,
            List<String> candidatePartIds,
            int maxAccepted
    ) {
        return compatibleCandidateSelection(user, category, compatibilityMode, candidatePartIds, maxAccepted).acceptedIds();
    }

    public CompatibleCandidateSelection compatibleCandidateSelection(
            CurrentUserService.CurrentUser user,
            String category,
            String compatibilityMode,
            List<String> candidatePartIds,
            int maxAccepted
    ) {
        String normalizedCategory = normalizeCategory(category);
        if (normalizedCategory == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 부품 카테고리입니다.");
        }
        String normalizedMode = firstText(text(compatibilityMode), "REPLACE").toUpperCase(Locale.ROOT);
        if (!"ADD".equals(normalizedMode) && !"REPLACE".equals(normalizedMode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 compatibilityMode입니다.");
        }
        if (user == null || candidatePartIds == null || candidatePartIds.isEmpty() || maxAccepted <= 0) {
            return CompatibleCandidateSelection.empty();
        }
        List<ToolBuildPart> baseParts = currentQuoteDraftParts(user);
        List<String> checkedTools = checkedTools(normalizedCategory);
        Set<String> selectedPartIds = baseParts.stream()
                .filter(part -> normalizedCategory.equals(part.category()))
                .map(ToolBuildPart::publicId)
                .filter(id -> id != null && !id.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> distinctIds = new LinkedHashSet<>(candidatePartIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .toList());
        Map<String, ToolBuildPart> candidatesById = partsByPublicIds(List.copyOf(distinctIds));
        List<String> passed = new ArrayList<>();
        List<String> warningFallbacks = new ArrayList<>();
        List<String> alreadySelected = new ArrayList<>();
        for (String candidateId : distinctIds) {
            // A recommendation list should not offer the exact product that is already selected.
            // RAM/STORAGE still support adding another unit through the explicit add command; this
            // gate only prevents a generic TOP3 response from looping back to the current item.
            if (selectedPartIds.contains(candidateId)) {
                alreadySelected.add(candidateId);
                continue;
            }
            ToolBuildPart candidate = candidatesById.get(candidateId);
            if (candidate == null || !normalizedCategory.equals(candidate.category())) {
                continue;
            }
            CandidateEvaluation evaluation = evaluate(
                    baseParts,
                    new CandidatePart(candidate, Map.of()),
                    normalizedCategory,
                    checkedTools,
                    normalizedMode,
                    null
            );
            if ("PASS".equals(evaluation.status())) {
                passed.add(candidateId);
                if (passed.size() >= maxAccepted) {
                    break;
                }
            } else if (!"FAIL".equals(evaluation.status())) {
                warningFallbacks.add(candidateId);
            }
        }
        if (passed.size() < maxAccepted) {
            warningFallbacks.stream()
                    .limit(maxAccepted - passed.size())
                    .forEach(passed::add);
        }
        Set<String> warningIds = Set.copyOf(warningFallbacks);
        List<String> acceptedWarnings = passed.stream().filter(warningIds::contains).toList();
        return new CompatibleCandidateSelection(
                List.copyOf(passed),
                List.copyOf(alreadySelected),
                acceptedWarnings
        );
    }

    private Map<String, ToolBuildPart> partsByPublicIds(List<String> publicIds) {
        if (publicIds == null || publicIds.isEmpty()) {
            return Map.of();
        }
        String predicates = String.join(" OR ", java.util.Collections.nCopies(publicIds.size(), "public_id = ?::uuid"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               category,
                               name,
                               manufacturer,
                               price,
                               status,
                               attributes
                        FROM parts
                        WHERE (%s)
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        """.formatted(predicates), publicIds.toArray());
        Map<String, ToolBuildPart> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            ToolBuildPart part = toolPart(row);
            if (part.publicId() != null) {
                result.put(part.publicId(), part);
            }
        }
        return result;
    }

    private List<ToolBuildPart> aiBuildParts(Map<String, Object> body) {
        List<Map<String, Object>> items = objectList(body.get("items"));
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AI_BUILD 후보 계산에는 items가 필요합니다.");
        }
        List<ToolBuildPart> parts = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String partId = text(item.get("partId"));
            String requestedCategory = normalizeCategory(text(item.get("category")));
            if (partId == null || requestedCategory == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partId와 category가 필요합니다.");
            }
            ToolBuildPart part = partByPublicId(partId);
            if (!requestedCategory.equals(part.category())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partId와 category가 일치하지 않습니다.");
            }
            parts.add(part);
        }
        return parts;
    }

    private List<ToolBuildPart> currentQuoteDraftParts(CurrentUserService.CurrentUser user) {
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
                               p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price AS current_price,
                               p.price,
                               qdi.quantity,
                               p.attributes
                        FROM quote_draft_items qdi
                        JOIN parts p ON p.id = qdi.part_id
                        WHERE qdi.quote_draft_id = ?
                          AND qdi.deleted_at IS NULL
                          AND p.deleted_at IS NULL
                        ORDER BY qdi.created_at ASC, qdi.id ASC
                        """, draftId)
                .stream()
                .map(this::toolPart)
                .toList();
    }

    private ToolBuildPart partByPublicId(String publicId) {
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               category,
                               name,
                               manufacturer,
                               price,
                               status,
                               attributes
                        FROM parts
                        WHERE public_id = ?::uuid
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        """, publicId)
                .stream()
                .findFirst()
                .map(this::toolPart)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부품을 찾을 수 없습니다."));
    }

    private List<CandidatePart> activeCandidates(String category, int queryLimit) {
        return jdbcTemplate.queryForList("""
                        SELECT p.id AS internal_id,
                               p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.status,
                               p.attributes,
                               bs.summary AS benchmark_summary,
                               bs.score AS benchmark_score,
                               CASE
                                 WHEN peo.low_price IS NOT NULL AND peo.low_price = p.price THEN peo.source
                                 ELSE ps.source
                               END AS latest_price_source,
                               CASE
                                 WHEN peo.low_price IS NOT NULL AND peo.low_price = p.price THEN peo.refreshed_at
                                 ELSE ps.collected_at
                               END AS latest_price_collected_at,
                               peo.title AS external_offer_title,
                               peo.image_url AS external_offer_image_url,
                               peo.supplier_name AS external_offer_supplier_name,
                               peo.offer_url AS external_offer_url,
                               peo.low_price AS external_offer_low_price,
                               peo.source AS external_offer_source,
                               peo.refreshed_at AS external_offer_refreshed_at
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT b.summary, b.score
                          FROM benchmark_summaries b
                          WHERE b.part_id = p.id
                            AND b.deleted_at IS NULL
                          ORDER BY b.created_at DESC, b.id DESC
                          LIMIT 1
                        ) bs ON true
                        LEFT JOIN LATERAL (
                          SELECT snapshot.source, snapshot.collected_at
                          FROM price_snapshots snapshot
                          WHERE snapshot.part_id = p.id
                            AND snapshot.collected_at <= now()
                          ORDER BY snapshot.collected_at DESC, snapshot.id DESC
                          LIMIT 1
                        ) ps ON true
                        LEFT JOIN part_external_offers peo
                          ON peo.part_id = p.id
                         AND peo.source = 'NAVER_SHOPPING_SEARCH'
                         AND peo.deleted_at IS NULL
                        WHERE p.category = ?
                          AND p.status = 'ACTIVE'
                          AND p.deleted_at IS NULL
                        ORDER BY p.price ASC, p.id ASC
                        LIMIT ?
                        """, category, queryLimit)
                .stream()
                .map(row -> new CandidatePart(toolPart(row), partMap(row)))
                .toList();
    }

    private CandidateEvaluation evaluate(List<ToolBuildPart> baseParts, CandidatePart candidate, String category, List<String> checkedTools, String mode, String replaceTargetPartId) {
        if (checkedTools.isEmpty()) {
            return new CandidateEvaluation(candidate.partMap(), "PASS", "ACTIVE 부품 후보입니다.", checkedTools, List.of());
        }
        List<ToolBuildPart> nextParts;
        if ("ADD".equals(mode)) {
            // 담기 평가: 기존 구성을 유지한 채 후보를 더한 상태로 검사한다 — RAM 만석에서 후보 킷이
            // '호환 가능'으로 보였다가 담는 순간 FAIL로 반전되는 오탐을 막는다. 이미 담긴 상품을 다시
            // 고르면 실제 API가 동일 행 quantity를 +1 하므로, 평가에서도 행을 복제하지 않고 수량을 늘린다.
            nextParts = new ArrayList<>();
            boolean incremented = false;
            for (ToolBuildPart part : baseParts) {
                boolean samePart = category.equals(part.category())
                        && candidate.toolPart().publicId() != null
                        && candidate.toolPart().publicId().equals(part.publicId());
                if (!samePart) {
                    nextParts.add(part);
                    continue;
                }
                incremented = true;
                nextParts.add(new ToolBuildPart(
                        part.internalId(),
                        part.publicId(),
                        part.category(),
                        part.name(),
                        part.manufacturer(),
                        part.price(),
                        part.attributes(),
                        part.effectiveQuantity() + candidate.toolPart().effectiveQuantity()
                ));
            }
            if (!incremented) {
                nextParts.add(candidate.toolPart());
            }
        } else if (replaceTargetPartId != null) {
            // 대상 교체 평가: 지정한 행만 빼고 후보를 더한다. 대상이 이미 사라진 드래프트 레이스에서는
            // 표시용 평가라 400 대신 담기처럼 관대하게 평가한다.
            nextParts = new ArrayList<>(baseParts.stream()
                    .filter(part -> !replaceTargetPartId.equals(part.publicId()))
                    .toList());
            nextParts.add(candidate.toolPart());
        } else {
            // 기본(교체-전체): 같은 카테고리를 후보 1개로 바꾼 상태로 검사한다 — 기존 소비자 동작.
            nextParts = new ArrayList<>(baseParts.stream()
                    .filter(part -> !category.equals(part.category()))
                    .toList());
            nextParts.add(candidate.toolPart());
        }
        List<Map<String, Object>> toolResults = toolCheckService.checkBuild(nextParts, total(nextParts));
        List<String> applicableCheckedTools = ToolApplicabilityPolicy.applicableCandidateTools(checkedTools, nextParts);
        List<Map<String, Object>> relevantResults = toolResults.stream()
                .filter(result -> applicableCheckedTools.contains(text(result.get("tool"))))
                .map(result -> projectCandidateResult(category, result))
                .toList();
        String status = worstStatus(relevantResults);
        String summary = summary(status, relevantResults);
        return new CandidateEvaluation(candidate.partMap(), status, summary, applicableCheckedTools, relevantResults);
    }

    /**
     * A full-build Tool result can contain a failure unrelated to the candidate being viewed.
     * Project aggregate compatibility/size details onto the candidate category so, for example,
     * an existing RAM slot overflow does not label every CPU candidate as impossible.
     */
    private static Map<String, Object> projectCandidateResult(String category, Map<String, Object> result) {
        Map<String, Object> details = objectMap(result.get("details"));
        if (details.isEmpty()) {
            return result;
        }
        String tool = text(result.get("tool"));
        String projectedStatus = switch (tool == null ? "" : tool) {
            case "compatibility" -> projectedCompatibilityStatus(category, details);
            case "size" -> projectedSizeStatus(category, details);
            default -> null;
        };
        if (projectedStatus == null) {
            return result;
        }
        Map<String, Object> projected = new LinkedHashMap<>(result);
        projected.put("status", projectedStatus);
        projected.put("summary", projectedCandidateSummary(
                category,
                tool,
                projectedStatus,
                details,
                text(result.get("summary"))));
        return projected;
    }

    private static String projectedCompatibilityStatus(String category, Map<String, Object> details) {
        List<String> relevantKeys = switch (category) {
            case "CPU" -> List.of("socketMatched", "coolerSocketMatched", "coolerTdpMatched");
            case "RAM" -> List.of("memoryTypeMatched", "ramFormFactorMatched", "ramSlotsMatched");
            case "MOTHERBOARD" -> List.of("socketMatched", "memoryTypeMatched", "ramSlotsMatched", "m2SlotsMatched");
            case "COOLER" -> List.of("coolerSocketMatched", "coolerTdpMatched");
            case "STORAGE" -> List.of("m2SlotsMatched");
            default -> List.of();
        };
        if (relevantKeys.isEmpty() || relevantKeys.stream().noneMatch(details::containsKey)) {
            return null;
        }
        if (relevantKeys.stream().anyMatch(key -> Boolean.FALSE.equals(details.get(key)))) {
            return "FAIL";
        }
        if (("CPU".equals(category) || "COOLER".equals(category))
                && Boolean.TRUE.equals(details.get("coolerTdpMarginLow"))) {
            return "WARN";
        }
        return "PASS";
    }

    private static String projectedSizeStatus(String category, Map<String, Object> details) {
        return switch (category) {
            case "GPU" -> dimensionalStatus(
                    details.get("gpuLengthMm"),
                    details.get("maxGpuLengthMm"),
                    BuildSizeFitPolicy.GPU_WARN_HEADROOM_MM);
            case "PSU" -> dimensionalStatus(
                    details.get("psuDepthMm"),
                    details.get("maxPsuLengthMm"),
                    BuildSizeFitPolicy.PSU_WARN_HEADROOM_MM);
            case "MOTHERBOARD" -> checkedMatchStatus(details, "boardFormFactorChecked", "boardFormFactorMatched");
            case "COOLER" -> coolerSizeStatus(details);
            // A case candidate changes every clearance relation, so keep the aggregate size result.
            case "CASE" -> null;
            default -> null;
        };
    }

    private static String dimensionalStatus(Object currentValue, Object maximumValue, int warnBelowMm) {
        Integer current = nullableNumber(currentValue);
        Integer maximum = nullableNumber(maximumValue);
        return BuildSizeFitPolicy.graphStatus(current, maximum, warnBelowMm, "WARN");
    }

    private static String checkedMatchStatus(Map<String, Object> details, String checkedKey, String matchedKey) {
        if (!details.containsKey(checkedKey) && !details.containsKey(matchedKey)) {
            return null;
        }
        if (!Boolean.TRUE.equals(details.get(checkedKey))) {
            return "WARN";
        }
        return Boolean.FALSE.equals(details.get(matchedKey)) ? "FAIL" : "PASS";
    }

    private static String coolerSizeStatus(Map<String, Object> details) {
        String coolerType = firstText(text(details.get("coolerType")), "").toUpperCase(Locale.ROOT);
        if (coolerType.contains("LIQUID")) {
            if (Boolean.FALSE.equals(details.get("radiatorMatched"))) {
                return "FAIL";
            }
            if (!Boolean.TRUE.equals(details.get("radiatorChecked")) || details.get("radiatorSupportMm") == null) {
                return "WARN";
            }
            return "PASS";
        }
        return dimensionalStatus(
                details.get("coolerHeightMm"),
                details.get("maxCpuCoolerHeightMm"),
                BuildSizeFitPolicy.COOLER_WARN_HEADROOM_MM);
    }

    private static String projectedCandidateSummary(
            String category,
            String tool,
            String status,
            Map<String, Object> details,
            String originalSummary
    ) {
        if ("PASS".equals(status)) {
            return "현재 조합에서 이 후보와 직접 관련된 검증을 통과했습니다.";
        }
        if ("WARN".equals(status)) {
            return "장착은 가능하지만 이 후보의 여유 치수 또는 스펙 근거를 추가 확인해 주세요.";
        }
        if ("compatibility".equals(tool)) {
            if (("CPU".equals(category) || "MOTHERBOARD".equals(category))
                    && Boolean.FALSE.equals(details.get("socketMatched"))) {
                return "CPU와 메인보드 소켓이 맞지 않습니다.";
            }
            if (("RAM".equals(category) || "MOTHERBOARD".equals(category))
                    && Boolean.FALSE.equals(details.get("memoryTypeMatched"))) {
                return "RAM 규격과 메인보드 메모리 규격이 맞지 않습니다.";
            }
            if (Boolean.FALSE.equals(details.get("ramSlotsMatched"))) {
                return "RAM 모듈 수가 메인보드 메모리 슬롯 수를 초과합니다.";
            }
            if (Boolean.FALSE.equals(details.get("m2SlotsMatched"))) {
                return "M.2 SSD 수가 메인보드 M.2 슬롯 수를 초과합니다.";
            }
            if (("CPU".equals(category) || "COOLER".equals(category))
                    && Boolean.FALSE.equals(details.get("coolerSocketMatched"))) {
                return "CPU 소켓과 쿨러 지원 소켓이 맞지 않습니다.";
            }
            if (("CPU".equals(category) || "COOLER".equals(category))
                    && Boolean.FALSE.equals(details.get("coolerTdpMatched"))) {
                return "쿨러 냉각 용량이 CPU 발열 기준에 부족합니다.";
            }
        }
        return firstText(originalSummary, "현재 조합과 함께 장착할 수 없는 후보입니다.");
    }

    private static Integer nullableNumber(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String summary(String status, List<Map<String, Object>> toolResults) {
        if ("PASS".equals(status)) {
            return "현재 조합 기준 호환 가능합니다";
        }
        return toolResults.stream()
                .filter(result -> status.equals(text(result.get("status"))))
                .map(result -> text(result.get("summary")))
                .filter(summary -> summary != null && !summary.isBlank())
                .findFirst()
                .orElse("현재 조합 기준 추가 확인이 필요합니다");
    }

    private static String worstStatus(List<Map<String, Object>> toolResults) {
        if (toolResults.stream().anyMatch(result -> "FAIL".equals(text(result.get("status"))))) {
            return "FAIL";
        }
        if (toolResults.stream().anyMatch(result -> "WARN".equals(text(result.get("status"))))) {
            return "WARN";
        }
        return "PASS";
    }

    private static List<String> checkedTools(String category) {
        return switch (category) {
            case "CPU", "RAM" -> List.of("compatibility");
            // 메인보드는 소켓/DDR(compatibility)에 더해 폼팩터 vs 케이스 지원 규격(size)도 본다 —
            // ITX 전용 케이스가 담긴 견적에서 ATX 보드 후보가 회색(장착 불가)으로 보여야 한다.
            case "MOTHERBOARD" -> List.of("compatibility", "size");
            // 쿨러는 소켓/TDP(compatibility)에 더해 수랭 라디에이터 장착(size)도 본다.
            case "COOLER" -> List.of("compatibility", "size");
            case "GPU" -> List.of("power", "size", "performance");
            // 파워는 용량(power)에 더해 깊이 vs 케이스 허용 길이(size)도 본다.
            case "PSU" -> List.of("power", "size");
            case "CASE" -> List.of("size");
            // 저장장치는 M.2 SSD 장착 수 vs 보드 M.2 슬롯(compatibility)을 본다 —
            // M.2 슬롯이 꽉 찬 견적에서 추가 M.2 SSD 후보가 회색(장착 불가)으로 보여야 한다.
            case "STORAGE" -> List.of("compatibility");
            default -> List.of();
        };
    }

    private Map<String, Object> partMap(Map<String, Object> row) {
        return MockData.map(
                "id", firstText(DbValueMapper.string(row, "id"), DbValueMapper.string(row, "part_id")),
                "category", DbValueMapper.string(row, "category"),
                "name", DbValueMapper.string(row, "name"),
                "manufacturer", DbValueMapper.string(row, "manufacturer"),
                "price", firstNumber(row.get("price"), firstNumber(row.get("current_price"), 0)),
                "status", DbValueMapper.string(row, "status"),
                "attributes", objectMap(row.get("attributes")),
                "benchmarkSummary", benchmarkSummary(row),
                "latestPriceSource", DbValueMapper.string(row, "latest_price_source"),
                "latestPriceCollectedAt", DbValueMapper.timestamp(row, "latest_price_collected_at"),
                "externalOffer", externalOffer(row)
        );
    }

    private Map<String, Object> responsePart(Map<String, Object> row) {
        if (row.containsKey("externalOffer") || row.containsKey("latestPriceSource") || row.containsKey("benchmarkSummary")) {
            return new LinkedHashMap<>(row);
        }
        return partMap(row);
    }

    private ToolBuildPart toolPart(Map<String, Object> row) {
        return new ToolBuildPart(
                longValue(row.get("internal_id")),
                firstText(DbValueMapper.string(row, "id"), DbValueMapper.string(row, "part_id")),
                DbValueMapper.string(row, "category"),
                DbValueMapper.string(row, "name"),
                DbValueMapper.string(row, "manufacturer"),
                firstNumber(row.get("price"), firstNumber(row.get("current_price"), 0)),
                objectMap(row.get("attributes")),
                // 드래프트 행에는 quantity가 있고, 후보 행에는 없어 1로 평가된다(교체 단품 가정).
                firstNumber(row.get("quantity"), 1)
        );
    }

    private static Map<String, Object> benchmarkSummary(Map<String, Object> row) {
        String summary = DbValueMapper.string(row, "benchmark_summary");
        if (summary == null) {
            return null;
        }
        return MockData.map("summary", summary, "score", row.get("benchmark_score"));
    }

    private static Map<String, Object> externalOffer(Map<String, Object> row) {
        String source = DbValueMapper.string(row, "external_offer_source");
        if (source == null) {
            return null;
        }
        return MockData.map(
                "title", DbValueMapper.string(row, "external_offer_title"),
                "imageUrl", DbValueMapper.string(row, "external_offer_image_url"),
                "supplierName", DbValueMapper.string(row, "external_offer_supplier_name"),
                "offerUrl", DbValueMapper.string(row, "external_offer_url"),
                "lowPrice", DbValueMapper.integer(row, "external_offer_low_price"),
                "source", source,
                "refreshedAt", DbValueMapper.timestamp(row, "external_offer_refreshed_at")
        );
    }

    private static String normalizeCategory(String category) {
        if (category == null) {
            return null;
        }
        String normalized = category.toUpperCase(Locale.ROOT);
        return CATEGORIES.contains(normalized) ? normalized : null;
    }

    private static int total(List<ToolBuildPart> parts) {
        // 수량 가중 — 드래프트 행 quantity를 반영해 UI 총액과 같은 규칙으로 계산한다.
        return parts.stream()
                .mapToInt(part -> firstNumber(part.price(), 0) * part.effectiveQuantity())
                .sum();
    }

    private static int statusRank(String status) {
        if ("PASS".equals(status)) {
            return 0;
        }
        if ("WARN".equals(status)) {
            return 1;
        }
        return 2;
    }

    private static String statusLabel(String status) {
        if ("FAIL".equals(status)) {
            return "장착 불가";
        }
        if ("WARN".equals(status)) {
            return "간섭 주의";
        }
        return "여유 있음";
    }

    private static String partListStatusLabel(String status) {
        if ("FAIL".equals(status)) {
            return "장착 불가";
        }
        if ("WARN".equals(status)) {
            return "간섭 주의";
        }
        return "호환 가능";
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static int firstNumber(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    @SafeVarargs
    private static <T> T firstText(T... values) {
        for (T value : values) {
            if (value instanceof String text && !text.isBlank()) {
                return value;
            }
            if (value != null && !(value instanceof String)) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        if (value == null) {
            return Map.of();
        }
        Object parsed = DbValueMapper.json(Map.of("value", value), "value", Map.of());
        return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item instanceof Map<?, ?>)
                    .map(item -> {
                        Map<String, Object> result = new LinkedHashMap<>();
                        ((Map<?, ?>) item).forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
                        return result;
                    })
                    .toList();
        }
        return List.of();
    }

    private record CandidatePart(ToolBuildPart toolPart, Map<String, Object> partMap) {
    }

    public record CompatibleCandidateSelection(
            List<String> acceptedIds,
            List<String> alreadySelectedIds,
            List<String> warningIds
    ) {
        private static CompatibleCandidateSelection empty() {
            return new CompatibleCandidateSelection(List.of(), List.of(), List.of());
        }
    }

    private record CandidateEvaluation(
            Map<String, Object> partMap,
            String status,
            String summary,
            List<String> checkedTools,
            List<Map<String, Object>> toolResults
    ) {
        private Map<String, Object> response() {
            return MockData.map(
                    "part", partMap,
                    "status", status,
                    "statusLabel", statusLabel(status),
                    "summary", summary,
                    "checkedTools", checkedTools
            );
        }

        private Map<String, Object> partListCompatibility() {
            return MockData.map(
                    "status", status,
                    "statusLabel", partListStatusLabel(status),
                    "summary", summary,
                    "checkedTools", checkedTools
            );
        }
    }
}
