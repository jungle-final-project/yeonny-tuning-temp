package com.buildgraph.prototype.build;

import com.buildgraph.prototype.build.dto.BuildChatResponseDto;
import com.buildgraph.prototype.quoteagent.chat.AiChatIntent;
import com.buildgraph.prototype.quoteagent.chat.dto.AiChatRequestDto;
import com.buildgraph.prototype.quoteagent.chat.dto.AiChatResponseDto;
import com.buildgraph.prototype.quoteagent.chat.AiChatEngine;
import com.buildgraph.prototype.parts.tool.ToolBuildPart;
import com.buildgraph.prototype.parts.tool.ToolService;
import com.buildgraph.prototype.user.CurrentUserService;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class BuildChatService {
    private static final Logger log = LoggerFactory.getLogger(BuildChatService.class);
    private static final Pattern BUDGET_MANWON = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:만원|만)");
    private static final Pattern BUDGET_WON = Pattern.compile("(\\d{6,})\\s*원?");
    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "CPU", "CPU",
            "MOTHERBOARD", "메인보드",
            "RAM", "RAM",
            "GPU", "GPU",
            "STORAGE", "SSD",
            "PSU", "파워",
            "CASE", "케이스",
            "COOLER", "쿨러"
    );
    private static final List<String> BLOCKING_FAIL_TOOLS = List.of("compatibility", "power", "size");

    private final ToolService toolCheckService;
    private final AiChatEngine aiChatEngine;
    private final BuildChatCacheService buildChatCacheService;

    public BuildChatResponseDto chat(Map<String, Object> request) {
        return chat(request, (String) null);
    }

    public BuildChatResponseDto chat(Map<String, Object> request, String requestedAiProfile) {
        return chat(request, requestedAiProfile, null);
    }

    public BuildChatResponseDto chat(Map<String, Object> request, CurrentUserService.CurrentUser user) {
        return chat(request, null, user);
    }

    public BuildChatResponseDto chat(Map<String, Object> request, String requestedAiProfile, CurrentUserService.CurrentUser user) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String message = requireText(body.get("message"), "message는 필수입니다.");
        Long userId = user == null ? null : user.internalId();
        log.debug(
                "Build Chat request received: userId={}, requestedAiProfile={}, cacheLookup=true, cacheService={}",
                userId,
                requestedAiProfile,
                buildChatCacheService.getClass().getName()
        );
        var cachedResponse = buildChatCacheService.lookup(body, requestedAiProfile, userId);
        if (cachedResponse.isPresent()) {
            return cachedResponse.get();
        }
        AiChatResponseDto engineResponse = aiChatEngine.LLMorchestrator(new AiChatRequestDto(
                message,
                text(body.get("sessionId"))
        ), requestedAiProfile);
        BuildChatResponseDto response = responseDto(engineResponse, body);
        log.debug("Build Chat response generated: userId={}, requestedAiProfile={}, cacheStore=true", userId, requestedAiProfile);
        buildChatCacheService.store(body, requestedAiProfile, userId, response);
        return response;
    }

    static Integer parseBudgetWon(String message) {
        if (message == null) {
            return null;
        }
        String normalized = message.replace(",", "").toLowerCase(Locale.ROOT);
        Matcher manWonMatcher = BUDGET_MANWON.matcher(normalized);
        if (manWonMatcher.find()) {
            return (int) Math.round(Double.parseDouble(manWonMatcher.group(1)) * 10_000);
        }
        Matcher wonMatcher = BUDGET_WON.matcher(normalized);
        if (wonMatcher.find()) {
            return Integer.parseInt(wonMatcher.group(1));
        }
        return null;
    }

    static String detectPartCategory(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        List<CategoryKeywords> checks = List.of(
                new CategoryKeywords("MOTHERBOARD", List.of("메인보드", "마더보드", "보드", "motherboard")),
                new CategoryKeywords("COOLER", List.of("쿨러", "cooler", "수랭", "공랭")),
                new CategoryKeywords("STORAGE", List.of("ssd", "스토리지", "저장장치", "저장 공간", "nvme")),
                new CategoryKeywords("PSU", List.of("파워", "psu", "전원공급", "전원 공급")),
                new CategoryKeywords("CASE", List.of("케이스", "case")),
                new CategoryKeywords("GPU", List.of("gpu", "그래픽카드", "그래픽 카드", "그래픽", "vga", "rtx", "cuda")),
                new CategoryKeywords("CPU", List.of("cpu", "프로세서", "라이젠", "ryzen", "intel", "인텔")),
                new CategoryKeywords("RAM", List.of("ram", "램", "메모리", "memory"))
        );
        return checks.stream()
                .filter(check -> check.keywords().stream().anyMatch(normalized::contains))
                .map(CategoryKeywords::category)
                .findFirst()
                .orElse(null);
    }

    private BuildChatResponseDto responseDto(AiChatResponseDto engineResponse, Map<String, Object> request) {
        AiChatIntent intent = intentFrom(engineResponse.respondType());
        if (intent == AiChatIntent.FULL_BUILD_RECOMMEND) {
            return fullBuildResponse(engineResponse);
        }
        if (intent == AiChatIntent.PART_RECOMMEND || intent == AiChatIntent.BUILD_MODIFY) {
            List<String> warnings = new ArrayList<>();
            List<AiChatResponseDto.PartRecommendation> parts =
                    failSafePartRecommendations(engineResponse.partRecommendations(), request, warnings);
            return partResponse(engineResponse, parts);
        }
        return messageResponse(engineResponse);
    }

    private BuildChatResponseDto fullBuildResponse(AiChatResponseDto engineResponse) {
        List<AiChatResponseDto.BuildRecommendation> recommendations = engineResponse.recommendations();
        if (recommendations == null || recommendations.isEmpty()) {
            return messageResponse(engineResponse);
        }

        AiChatResponseDto.BuildRecommendation recommendation = recommendations.get(0);
        List<BuildChatResponseDto.Part> items = recommendation.items() == null
                ? List.of()
                : recommendation.items().stream().map(this::responsePart).toList();

        return new BuildChatResponseDto(
                BuildChatResponseDto.OutputType.FULL_BUILD,
                engineResponse.replyMessage(),
                new BuildChatResponseDto.Build(
                        items.stream().mapToInt(BuildChatResponseDto.Part::price).sum(),
                        items
                ),
                null,
                engineResponse.sessionId()
        );
    }

    private BuildChatResponseDto partResponse(
            AiChatResponseDto engineResponse,
            List<AiChatResponseDto.PartRecommendation> recommendations
    ) {
        if (recommendations == null || recommendations.isEmpty()) {
            return messageResponse(engineResponse);
        }
        return new BuildChatResponseDto(
                BuildChatResponseDto.OutputType.PART,
                engineResponse.replyMessage(),
                null,
                responsePart(recommendations.get(0)),
                engineResponse.sessionId()
        );
    }

    private BuildChatResponseDto messageResponse(AiChatResponseDto engineResponse) {
        return new BuildChatResponseDto(
                BuildChatResponseDto.OutputType.MESSAGE,
                engineResponse.replyMessage(),
                null,
                null,
                engineResponse.sessionId()
        );
    }

    private BuildChatResponseDto.Part responsePart(AiChatResponseDto.PartRecommendation part) {
        return new BuildChatResponseDto.Part(
                part.partId(),
                part.category(),
                part.name(),
                part.manufacturer(),
                part.price(),
                recommendationReason(part)
        );
    }

    private String recommendationReason(AiChatResponseDto.PartRecommendation part) {
        Map<String, Object> attributes = part.attributes() == null ? Map.of() : part.attributes();
        String description = firstText(
                text(attributes.get("reason")),
                firstText(
                        text(attributes.get("shortSpec")),
                        text(attributes.get("_benchmarkSummary"))
                )
        );
        if (description != null) {
            return description;
        }
        if (attributes.get("performanceScore") instanceof Number performance
                && attributes.get("valueScore") instanceof Number value) {
            return String.format(
                    Locale.ROOT,
                    "성능 %.2f, 가성비 %.2f 점수를 기준으로 선택했습니다.",
                    performance.doubleValue(),
                    value.doubleValue()
            );
        }
        return "사용자 요청과 부품 데이터를 기준으로 선택했습니다.";
    }

    private List<AiChatResponseDto.PartRecommendation> failSafePartRecommendations(
            List<AiChatResponseDto.PartRecommendation> recommendations,
            Map<String, Object> request,
            List<String> warnings
    ) {
        if (recommendations == null || recommendations.isEmpty()) {
            return List.of();
        }
        Map<String, Object> currentQuoteDraft = objectMap(request.get("currentQuoteDraft"));
        List<Map<String, Object>> draftItems = objectMaps(currentQuoteDraft.get("items"));
        if (draftItems.isEmpty()) {
            return recommendations;
        }
        List<AiChatResponseDto.PartRecommendation> safe = new ArrayList<>();
        int excluded = 0;
        for (AiChatResponseDto.PartRecommendation recommendation : recommendations) {
            List<PartCandidate> nextParts = replacementPreviewParts(draftItems, recommendation);
            List<String> localWarnings = new ArrayList<>();
            List<Map<String, Object>> toolResults = toolResults(nextParts, totalPrice(nextParts), localWarnings);
            if (hasBlockingToolFailure(toolResults)) {
                excluded += 1;
                continue;
            }
            safe.add(recommendation);
        }
        if (excluded > 0) {
            warnings.add("Tool FAIL 후보 " + excluded + "개를 추천/적용 후보에서 제외했습니다.");
        }
        return safe;
    }

    private List<PartCandidate> replacementPreviewParts(List<Map<String, Object>> draftItems, AiChatResponseDto.PartRecommendation recommendation) {
        String category = recommendation.category();
        List<PartCandidate> nextParts = new ArrayList<>();
        boolean replaced = false;
        for (Map<String, Object> item : draftItems) {
            if (category.equals(text(item.get("category")))) {
                if (!replaced) {
                    nextParts.add(partCandidate(recommendation));
                    replaced = true;
                }
                continue;
            }
            PartCandidate draftPart = partCandidateFromDraftItem(item);
            if (draftPart != null) {
                nextParts.add(draftPart);
            }
        }
        if (!replaced) {
            nextParts.add(partCandidate(recommendation));
        }
        return nextParts;
    }

    private PartCandidate partCandidateFromDraftItem(Map<String, Object> item) {
        String partId = text(item.get("partId"));
        String category = text(item.get("category"));
        if (partId == null || category == null) {
            return null;
        }
        return new PartCandidate(
                null,
                partId,
                category,
                firstText(text(item.get("name")), categoryLabel(category)),
                text(item.get("manufacturer")),
                firstNumber(item.get("currentPrice"), item.get("price"), item.get("unitPriceAtAdd"), item.get("lineTotal")) == null
                        ? 0
                        : firstNumber(item.get("currentPrice"), item.get("price"), item.get("unitPriceAtAdd"), item.get("lineTotal")),
                objectMap(item.get("attributes"))
        );
    }

    private boolean hasBlockingToolFailure(List<Map<String, Object>> toolResults) {
        return toolResults.stream()
                .anyMatch(result -> "FAIL".equals(text(result.get("status")))
                        && BLOCKING_FAIL_TOOLS.contains(text(result.get("tool"))));
    }

    private List<Map<String, Object>> toolResults(List<PartCandidate> parts, int budgetWon, List<String> warnings) {
        try {
            return toolCheckService.checkBuild(parts.stream().map(BuildChatService::toolPart).toList(), budgetWon);
        } catch (RuntimeException error) {
            warnings.add("Tool 검증을 완료하지 못했습니다: " + error.getMessage());
            return List.of();
        }
    }

    private PartCandidate partCandidate(AiChatResponseDto.PartRecommendation part) {
        return new PartCandidate(
                null,
                part.partId(),
                part.category(),
                part.name(),
                part.manufacturer(),
                part.price(),
                part.attributes() == null ? Map.of() : part.attributes()
        );
    }

    private static ToolBuildPart toolPart(PartCandidate part) {
        return new ToolBuildPart(
                part.internalId(),
                part.publicId(),
                part.category(),
                part.name(),
                part.manufacturer(),
                part.price(),
                part.attributes()
        );
    }

    private static AiChatIntent intentFrom(String respondType) {
        if (respondType == null || respondType.isBlank() || "CONVERSATION".equals(respondType)) {
            return null;
        }
        try {
            return AiChatIntent.valueOf(respondType);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int defaultQuantity(String category) {
        return "RAM".equals(category) ? 2 : 1;
    }

    private static int totalPrice(List<PartCandidate> parts) {
        return parts.stream()
                .mapToInt(part -> (part.price() == null ? 0 : part.price()) * defaultQuantity(part.category()))
                .sum();
    }

    private static String categoryLabel(String category) {
        return CATEGORY_LABELS.getOrDefault(category, category);
    }

    private static String requireText(Object value, String message) {
        String text = text(value);
        if (text == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return text;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Integer numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        return Integer.parseInt(text.replace(",", ""));
    }

    private static Integer firstNumber(Object... values) {
        for (Object value : values) {
            Integer number = numberValue(value);
            if (number != null) {
                return number;
            }
        }
        return null;
    }

    private static List<Map<String, Object>> objectMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            result.add(objectMap(item));
        }
        return result;
    }

    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private record CategoryKeywords(String category, List<String> keywords) {
    }


    private record PartCandidate(
            Long internalId,
            String publicId,
            String category,
            String name,
            String manufacturer,
            Integer price,
            Map<String, Object> attributes
    ) {
    }
}
