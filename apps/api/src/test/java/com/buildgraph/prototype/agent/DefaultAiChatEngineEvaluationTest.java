package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.buildgraph.prototype.part.PartAliasReviewService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DefaultAiChatEngineEvaluationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<EvalCase>> CASE_LIST = new TypeReference<>() {
    };
    private static final Set<String> BUILD_CATEGORIES = Set.of(
            "CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER"
    );

    private JdbcTemplate jdbcTemplate;
    private DefaultAiChatEngine engine;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        AgentTraceService agentTraceService = mock(AgentTraceService.class);
        AgentRagRetrievalService agentRagRetrievalService = mock(AgentRagRetrievalService.class);
        OpenAiResponsesClient openAiResponsesClient = mock(OpenAiResponsesClient.class);
        engine = new DefaultAiChatEngine(
                jdbcTemplate,
                agentTraceService,
                agentRagRetrievalService,
                openAiResponsesClient,
                AiProfileConfigTest.config("AS_CHAT_FAST", "BUILD_CHAT_FAST"),
                new PartReplacementRanker(mock(PartAliasReviewService.class))
        );

        doAnswer(invocation -> {
                    Object category = invocation.getArgument(1);
                    int limit = invocation.getArgument(2);
                    return partRows(String.valueOf(category)).stream().limit(limit).toList();
                })
                .when(jdbcTemplate)
                .queryForList(anyString(), anyString(), anyInt());
    }

    @Test
    void fixedCaseSetMeetsQuantitativeThresholds() throws Exception {
        List<EvalCase> cases = readCases();
        EvalCounters counters = new EvalCounters();

        for (EvalCase evalCase : cases) {
            long started = System.nanoTime();
            AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                    evalCase.message(),
                    evalCase.surface(),
                    evalCase.selectedCategory(),
                    null,
                    null,
                    contextFor(evalCase),
                    1L
            ));
            long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            counters.record(evalCase, response, latencyMs);
        }

        EvaluationResult result = counters.result();
        System.out.printf(
                "AI_CHAT_ENGINE_EVAL cases=%d totalScore=%.1f intent=%.3f actionType=%.3f actionPayload=%.3f recCount=%.3f categoryCoverage=%.3f toolReady=%.3f directionSafety=%.3f forbiddenWrite=%.3f p50Ms=%d p95Ms=%d%n",
                result.caseCount(),
                result.totalScore(),
                result.intentAccuracy(),
                result.actionTypeAccuracy(),
                result.actionPayloadValidRate(),
                result.recommendationCountPassRate(),
                result.categoryCoverageRate(),
                result.toolReadyRate(),
                result.directionSafetyRate(),
                result.noForbiddenWriteRate(),
                result.p50LatencyMs(),
                result.p95LatencyMs()
        );
        if (!result.failedCaseIds().isEmpty()) {
            System.out.println("AI_CHAT_ENGINE_EVAL_FAILED " + String.join(",", result.failedCaseIds()));
        }

        assertThat(result.caseCount()).isGreaterThanOrEqualTo(100);
        assertThat(result.intentAccuracy()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.actionTypeAccuracy()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.actionPayloadValidRate()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.recommendationCountPassRate()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.categoryCoverageRate()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.toolReadyRate()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.directionSafetyRate()).isEqualTo(1.0);
        assertThat(result.noForbiddenWriteRate()).isEqualTo(1.0);
        assertThat(result.p95LatencyMs()).isLessThan(1_000L);
        assertThat(result.totalScore()).isGreaterThanOrEqualTo(85.0);
        verify(jdbcTemplate, never()).update(anyString(), (Object[]) org.mockito.ArgumentMatchers.any());
    }

    private static List<EvalCase> readCases() throws Exception {
        Path path = Path.of("..", "..", "tools", "ai_chat_engine_cases.json");
        return OBJECT_MAPPER.readValue(Files.readString(path), CASE_LIST);
    }

    private static Map<String, Object> contextFor(EvalCase evalCase) {
        return evalCase.currentQuoteDraft() == null ? Map.of() : Map.of("currentQuoteDraft", evalCase.currentQuoteDraft());
    }

    private static List<Map<String, Object>> partRows(String category) {
        return switch (category) {
            case "GPU" -> rows(category, 1_100_000, 760_000, 520_000);
            case "CPU" -> rows(category, 500_000, 300_000, 180_000);
            case "MOTHERBOARD" -> rows(category, 350_000, 240_000, 150_000);
            case "RAM" -> rows(category, 300_000, 150_000, 80_000);
            case "STORAGE" -> rows(category, 260_000, 150_000, 80_000);
            case "PSU" -> rows(category, 260_000, 150_000, 80_000);
            case "CASE" -> rows(category, 220_000, 120_000, 70_000);
            case "COOLER" -> rows(category, 200_000, 110_000, 60_000);
            default -> rows(category, 200_000, 120_000, 70_000);
        };
    }

    private static List<Map<String, Object>> rows(String category, int high, int mid, int low) {
        return List.of(
                partRow(category, category + "-high", category + " High", high, attributes(category, "high")),
                partRow(category, category + "-mid", category + " Mid", mid, attributes(category, "mid")),
                partRow(category, category + "-low", category + " Low", low, attributes(category, "low"))
        );
    }

    private static Map<String, Object> partRow(String category, String id, String name, int price, Map<String, Object> attributes) {
        return Map.of(
                "id", id,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "price", price,
                "attributes", attributesJson(attributes)
        );
    }

    private static Map<String, Object> attributes(String category, String tier) {
        int rank = switch (tier) {
            case "high" -> 3;
            case "mid" -> 2;
            default -> 1;
        };
        return switch (category) {
            case "GPU" -> Map.of("toolReady", true, "gpuClass", rank == 3 ? "RTX_5080" : rank == 2 ? "RTX_5070" : "RTX_5060");
            case "CPU" -> Map.of("toolReady", true, "cpuClass", rank == 3 ? "RYZEN_9" : rank == 2 ? "RYZEN_7" : "RYZEN_5", "coreCount", rank == 3 ? 16 : rank == 2 ? 8 : 6, "threadCount", rank == 3 ? 32 : rank == 2 ? 16 : 12);
            case "RAM" -> Map.of("toolReady", true, "memoryType", "DDR5", "capacityGb", rank == 3 ? 64 : rank == 2 ? 32 : 16, "speedMhz", rank == 3 ? 7200 : rank == 2 ? 6400 : 5600, "moduleCount", 2);
            case "STORAGE" -> Map.of("toolReady", true, "capacityGb", rank == 3 ? 4000 : rank == 2 ? 2000 : 1000, "readMbps", rank == 3 ? 14000 : rank == 2 ? 7400 : 5000, "writeMbps", rank == 3 ? 12000 : rank == 2 ? 6500 : 4200, "generation", rank == 3 ? "PCIe 5.0" : "PCIe 4.0");
            case "PSU" -> Map.of("toolReady", true, "capacityW", rank == 3 ? 1000 : rank == 2 ? 850 : 650, "efficiency", rank == 3 ? "PLATINUM" : rank == 2 ? "GOLD" : "BRONZE", "atxSpec", rank == 1 ? "2.4" : "3.1", "modular", rank > 1);
            case "MOTHERBOARD" -> Map.of("toolReady", true, "chipset", rank == 3 ? "X870E" : rank == 2 ? "B850" : "A620", "memoryType", "DDR5", "pcieGeneration", rank == 3 ? "5.0" : "4.0", "hasWifi", rank > 1, "formFactor", "ATX");
            case "CASE" -> Map.of("toolReady", true, "maxGpuLengthMm", rank == 3 ? 430 : rank == 2 ? 380 : 330, "maxCpuCoolerHeightMm", rank == 3 ? 180 : rank == 2 ? 170 : 160, "maxPsuLengthMm", rank == 3 ? 250 : rank == 2 ? 220 : 180, "frontMesh", rank > 1, "airflowFocus", rank > 1);
            case "COOLER" -> Map.of("toolReady", true, "tdpW", rank == 3 ? 280 : rank == 2 ? 220 : 160, "radiatorLengthMm", rank == 3 ? 360 : rank == 2 ? 280 : 0, "heightMm", rank == 1 ? 155 : 165, "coolerType", rank == 3 ? "AIO" : "AIR");
            default -> Map.of("toolReady", true);
        };
    }

    private static String attributesJson(Map<String, Object> attributes) {
        return "{" + attributes.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + jsonValue(entry.getValue()))
                .reduce((left, right) -> left + "," + right)
                .orElse("") + "}";
    }

    private static String jsonValue(Object value) {
        if (value instanceof Boolean bool) {
            return Boolean.toString(bool);
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return "\"" + String.valueOf(value) + "\"";
    }

    private record EvalCase(
            String id,
            String group,
            String message,
            String surface,
            String selectedCategory,
            AiChatIntent expectedIntent,
            List<AiChatActionType> expectedActions,
            AiChatActionType requiredActionType,
            String expectedCategory,
            Map<String, Object> currentQuoteDraft,
            String expectedDirection,
            List<String> forbiddenPartIds,
            List<String> forbiddenClasses,
            List<String> expectedWarnings
    ) {
        private List<AiChatActionType> expectedActionTypes() {
            List<AiChatActionType> result = new ArrayList<>();
            if (expectedActions != null) {
                result.addAll(expectedActions);
            }
            if (requiredActionType != null) {
                result.add(requiredActionType);
            }
            return result;
        }
    }

    private static final class EvalCounters {
        private int caseCount;
        private int intentPass;
        private int actionTypePass;
        private int actionPayloadPass;
        private int recommendationCases;
        private int recommendationCountPass;
        private int categoryCoverageChecks;
        private int categoryCoveragePass;
        private int toolReadyChecks;
        private int toolReadyPass;
        private int directionSafetyChecks;
        private int directionSafetyPass;
        private final List<Long> latencies = new ArrayList<>();
        private final List<String> failedCaseIds = new ArrayList<>();

        void record(EvalCase evalCase, AiChatEngineResponse response, long latencyMs) {
            caseCount++;
            latencies.add(latencyMs);
            if (evalCase.expectedIntent() == response.intent()) {
                intentPass++;
            } else {
                failedCaseIds.add(evalCase.id() + ":intent:" + response.intent());
            }
            if (hasExpectedActions(evalCase.expectedActionTypes(), response.actions())) {
                actionTypePass++;
            } else {
                failedCaseIds.add(evalCase.id() + ":actions");
            }
            if (payloadsValid(response.actions())) {
                actionPayloadPass++;
            } else {
                failedCaseIds.add(evalCase.id() + ":payload");
            }
            recordRecommendationQuality(evalCase, response);
            recordDirectionSafety(evalCase, response);
        }

        EvaluationResult result() {
            double intentRate = rate(intentPass, caseCount);
            double actionTypeRate = rate(actionTypePass, caseCount);
            double payloadRate = rate(actionPayloadPass, caseCount);
            double recommendationRate = rate(recommendationCountPass, recommendationCases);
            double coverageRate = rate(categoryCoveragePass, categoryCoverageChecks);
            double toolReadyRate = rate(toolReadyPass, toolReadyChecks);
            double directionSafetyRate = rate(directionSafetyPass, directionSafetyChecks);
            double noForbiddenWriteRate = 1.0;
            double totalScore = (20.0 * intentRate)
                    + (10.0 * actionTypeRate)
                    + (10.0 * payloadRate)
                    + (8.0 * recommendationRate)
                    + (6.0 * coverageRate)
                    + (6.0 * toolReadyRate)
                    + (10.0 * directionSafetyRate)
                    + 10.0
                    + 10.0
                    + 5.0;
            return new EvaluationResult(
                    caseCount,
                    totalScore,
                    intentRate,
                    actionTypeRate,
                    payloadRate,
                    recommendationRate,
                    coverageRate,
                    toolReadyRate,
                    directionSafetyRate,
                    noForbiddenWriteRate,
                    percentile(0.50),
                    percentile(0.95),
                    List.copyOf(failedCaseIds)
            );
        }

        private void recordDirectionSafety(EvalCase evalCase, AiChatEngineResponse response) {
            if (evalCase.expectedDirection() == null
                    && empty(evalCase.forbiddenPartIds())
                    && empty(evalCase.forbiddenClasses())
                    && empty(evalCase.expectedWarnings())) {
                return;
            }
            directionSafetyChecks++;
            boolean passed = forbiddenPartsAbsent(evalCase, response)
                    && forbiddenClassesAbsent(evalCase, response)
                    && expectedWarningsPresent(evalCase, response)
                    && directionMatches(evalCase, response);
            if (passed) {
                directionSafetyPass++;
            } else {
                failedCaseIds.add(evalCase.id() + ":direction:" + directionDebug(evalCase, response));
            }
        }

        private static String directionDebug(EvalCase evalCase, AiChatEngineResponse response) {
            Map<String, Object> current = currentDraftItem(evalCase);
            int currentPrice = number(current.get("currentPrice"), number(current.get("price"), number(current.get("lineTotal"), 0)));
            int currentTier = tier(evalCase.expectedCategory(), text(current.get("partId")), objectMap(current.get("attributes")));
            String candidates = response.partRecommendations().stream()
                    .map(part -> part.partId() + "/" + part.price() + "/t" + tier(part.category(), part.partId(), part.attributes()))
                    .reduce((left, right) -> left + ";" + right)
                    .orElse("none");
            return evalCase.expectedDirection() + ":current=" + currentPrice + "/t" + currentTier + ":candidates=" + candidates;
        }

        private static boolean forbiddenPartsAbsent(EvalCase evalCase, AiChatEngineResponse response) {
            if (empty(evalCase.forbiddenPartIds())) {
                return true;
            }
            Set<String> forbidden = new HashSet<>(evalCase.forbiddenPartIds());
            return response.partRecommendations().stream()
                    .map(AiChatEngineResponse.PartRecommendation::partId)
                    .noneMatch(forbidden::contains);
        }

        private static boolean forbiddenClassesAbsent(EvalCase evalCase, AiChatEngineResponse response) {
            if (empty(evalCase.forbiddenClasses())) {
                return true;
            }
            String responseText = response.partRecommendations().toString().toUpperCase();
            return evalCase.forbiddenClasses().stream()
                    .map(value -> value.toUpperCase().replace('-', '_'))
                    .noneMatch(responseText::contains);
        }

        private static boolean expectedWarningsPresent(EvalCase evalCase, AiChatEngineResponse response) {
            if (empty(evalCase.expectedWarnings())) {
                return true;
            }
            Object warnings = response.parsedContext().get("warnings");
            String warningText = warnings == null ? "" : warnings.toString();
            return evalCase.expectedWarnings().stream().allMatch(warningText::contains);
        }

        private static boolean directionMatches(EvalCase evalCase, AiChatEngineResponse response) {
            if (evalCase.expectedDirection() == null) {
                return true;
            }
            Map<String, Object> current = currentDraftItem(evalCase);
            if (current.isEmpty() || response.partRecommendations().isEmpty()) {
                return false;
            }
            String category = evalCase.expectedCategory();
            int currentPrice = number(current.get("currentPrice"), number(current.get("price"), number(current.get("lineTotal"), 0)));
            int currentTier = tier(category, text(current.get("partId")), objectMap(current.get("attributes")));
            return switch (evalCase.expectedDirection()) {
                case "MORE_EXPENSIVE" -> response.partRecommendations().stream()
                        .allMatch(part -> tier(part.category(), part.partId(), part.attributes()) > currentTier);
                case "CHEAPER" -> response.partRecommendations().stream()
                        .allMatch(part -> part.price() < currentPrice);
                case "SIMILAR_PRICE" -> response.partRecommendations().stream()
                        .allMatch(part -> tier(part.category(), part.partId(), part.attributes()) >= currentTier);
                default -> true;
            };
        }

        private static Map<String, Object> currentDraftItem(EvalCase evalCase) {
            Map<String, Object> draft = evalCase.currentQuoteDraft();
            if (draft == null || evalCase.expectedCategory() == null) {
                return Map.of();
            }
            Object items = draft.get("items");
            if (!(items instanceof List<?> rawItems)) {
                return Map.of();
            }
            for (Object rawItem : rawItems) {
                Map<String, Object> item = objectMap(rawItem);
                if (evalCase.expectedCategory().equals(text(item.get("category")))) {
                    return item;
                }
            }
            return Map.of();
        }

        private static boolean empty(List<?> values) {
            return values == null || values.isEmpty();
        }

        private static Map<String, Object> objectMap(Object value) {
            if (!(value instanceof Map<?, ?> rawMap)) {
                return Map.of();
            }
            java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
            rawMap.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }

        private static String text(Object value) {
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? null : text;
        }

        private static int number(Object value, int fallback) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            String text = text(value);
            if (text == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(text.replace(",", ""));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static int tier(String category, String partId, Map<String, Object> attributes) {
            String id = text(partId);
            if (id != null) {
                if (id.endsWith("-high")) return 3;
                if (id.endsWith("-mid")) return 2;
                if (id.endsWith("-low")) return 1;
            }
            String normalizedCategory = text(category);
            if ("GPU".equals(normalizedCategory)) {
                String gpuClass = text(attributes.get("gpuClass"));
                if (gpuClass != null && gpuClass.contains("5090")) return 5;
                if (gpuClass != null && gpuClass.contains("5080")) return 4;
                if (gpuClass != null && gpuClass.contains("5070")) return gpuClass.toUpperCase().contains("TI") ? 3 : 2;
                if (gpuClass != null && gpuClass.contains("5060")) return 1;
            }
            if ("CPU".equals(normalizedCategory)) {
                String cpuClass = text(attributes.get("cpuClass"));
                if (cpuClass != null && (cpuClass.contains("9") || cpuClass.toUpperCase().contains("ENTHUSIAST"))) return 3;
                if (cpuClass != null && (cpuClass.contains("7") || cpuClass.toUpperCase().contains("PERFORMANCE"))) return 2;
                if (cpuClass != null && cpuClass.contains("5")) return 1;
            }
            return number(attributes.get("capacityGb"), number(attributes.get("capacityW"), number(attributes.get("readMbps"), 0)));
        }

        private void recordRecommendationQuality(EvalCase evalCase, AiChatEngineResponse response) {
            if (evalCase.expectedIntent() == AiChatIntent.FULL_BUILD_RECOMMEND) {
                recommendationCases++;
                if (response.recommendations().size() == 3) {
                    recommendationCountPass++;
                } else {
                    failedCaseIds.add(evalCase.id() + ":recommendationCount:" + response.recommendations().size());
                }
                for (AiChatEngineResponse.BuildRecommendation recommendation : response.recommendations()) {
                    Set<String> categories = new HashSet<>();
                    for (AiChatEngineResponse.PartRecommendation part : recommendation.items()) {
                        categories.add(part.category());
                        recordToolReady(part);
                    }
                    categoryCoverageChecks++;
                    if (categories.containsAll(BUILD_CATEGORIES)) {
                        categoryCoveragePass++;
                    }
                }
                return;
            }
            if (evalCase.expectedIntent() == AiChatIntent.PART_RECOMMEND || evalCase.expectedIntent() == AiChatIntent.BUILD_MODIFY) {
                recommendationCases++;
                if (!response.partRecommendations().isEmpty() && response.partRecommendations().size() <= 3) {
                    recommendationCountPass++;
                } else {
                    failedCaseIds.add(evalCase.id() + ":partRecommendationCount:" + response.partRecommendations().size());
                }
                for (AiChatEngineResponse.PartRecommendation part : response.partRecommendations()) {
                    if (evalCase.expectedCategory() != null) {
                        categoryCoverageChecks++;
                        if (evalCase.expectedCategory().equals(part.category())) {
                            categoryCoveragePass++;
                        }
                    }
                    recordToolReady(part);
                }
            }
        }

        private void recordToolReady(AiChatEngineResponse.PartRecommendation part) {
            toolReadyChecks++;
            if (Boolean.TRUE.equals(part.attributes().get("toolReady"))) {
                toolReadyPass++;
            }
        }

        private long percentile(double percentile) {
            if (latencies.isEmpty()) {
                return 0L;
            }
            List<Long> sorted = latencies.stream().sorted(Comparator.naturalOrder()).toList();
            int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile) - 1);
            return sorted.get(Math.max(0, index));
        }

        private static boolean hasExpectedActions(List<AiChatActionType> expected, List<AiChatAction> actual) {
            Set<AiChatActionType> actualTypes = new HashSet<>();
            for (AiChatAction action : actual) {
                actualTypes.add(action.type());
            }
            return actualTypes.containsAll(expected);
        }

        private static boolean payloadsValid(List<AiChatAction> actions) {
            return actions.stream().allMatch(action -> switch (action.type()) {
                case OPEN_SELF_QUOTE -> has(action.payload(), "route");
                case ADD_PART_TO_DRAFT -> has(action.payload(), "partId") && has(action.payload(), "category") && has(action.payload(), "quantity");
                case REPLACE_DRAFT_PART -> has(action.payload(), "category") && has(action.payload(), "quantity");
                case REMOVE_DRAFT_PART -> has(action.payload(), "partId") && has(action.payload(), "category");
                case UPDATE_DRAFT_QUANTITY -> has(action.payload(), "partId") && has(action.payload(), "category") && has(action.payload(), "quantity");
                case ADD_BUILD_TO_DRAFT -> action.payload().get("items") instanceof List<?> items && !items.isEmpty();
                case CREATE_PRICE_ALERT -> has(action.payload(), "targetPrice");
                case ASK_FOLLOW_UP -> has(action.payload(), "missing") && has(action.payload(), "message");
            });
        }

        private static boolean has(Map<String, Object> payload, String key) {
            return payload != null && payload.get(key) != null;
        }

        private static double rate(int pass, int total) {
            return total == 0 ? 1.0 : (double) pass / (double) total;
        }
    }

    private record EvaluationResult(
            int caseCount,
            double totalScore,
            double intentAccuracy,
            double actionTypeAccuracy,
            double actionPayloadValidRate,
            double recommendationCountPassRate,
            double categoryCoverageRate,
            double toolReadyRate,
            double directionSafetyRate,
            double noForbiddenWriteRate,
            long p50LatencyMs,
            long p95LatencyMs,
            List<String> failedCaseIds
    ) {
    }
}
