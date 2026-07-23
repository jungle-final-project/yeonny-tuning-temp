package com.buildgraph.prototype.aichat.chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.buildgraph.prototype.agent.AiProfileConfig;
import com.buildgraph.prototype.agent.AiProfileDefinition;
import com.buildgraph.prototype.aichat.chat.dto.AiChatResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class AiChatUtil {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AiChatUtil() {
    }

    /* 견적을 객체형태로 조립해서 반환하는 함수 */
    public static AiChatResponseDto.BuildRecommendation toBuildRecommendation(
            String recommendedFor,
            List<Map<String, Object>> matchedParts
    ) {
        List<AiChatResponseDto.PartRecommendation> items =
                matchedParts.stream()
                        .map(part -> {
                            String partId = firstText(
                                    text(part.get("partId")),
                                    text(part.get("id"))
                            );
                            Integer price = firstNumber(
                                    part.get("price"),
                                    part.get("currentPrice")
                            );

                            Map<String, Object> attributes =
                                    objectMap(part.get("attributes"));
                            if (part.get("performance_score") != null) {
                                attributes.put(
                                        "performanceScore",
                                        part.get("performance_score")
                                );
                            }
                            if (part.get("value_score") != null) {
                                attributes.put(
                                        "valueScore",
                                        part.get("value_score")
                                );
                            }
                            if (part.get("match_score") != null) {
                                attributes.put(
                                        "matchScore",
                                        part.get("match_score")
                                );
                            }

                            return new AiChatResponseDto.PartRecommendation(
                                    partId,
                                    text(part.get("category")),
                                    text(part.get("name")),
                                    text(part.get("manufacturer")),
                                    price == null ? 0 : price,
                                    attributes
                            );
                        })
                        .toList();

        int estimatedTotalPrice = items.stream()
                .mapToInt(AiChatResponseDto.PartRecommendation::price)
                .sum();

        return new AiChatResponseDto.BuildRecommendation(
                recommendedFor,
                estimatedTotalPrice,
                items
        );
    }


    public static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return new LinkedHashMap<>();
    }

    public static List<Map<String, Object>> objectMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(AiChatUtil::objectMap)
                .filter(map -> !map.isEmpty())
                .toList();
    }

    public static Integer firstNumber(Object first, Object fallback) {
        Integer value = numberValue(first);
        return value == null ? numberValue(fallback) : value;
    }

    public static Integer numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        if (text == null) return null;
        return Integer.valueOf(text.replace(",", ""));
    }

    public static String requireText(Object value, String message) {
        String text = text(value);
        if (text == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return text;
    }

    public static AiProfileDefinition requireBuildChatProfile(
            AiProfileConfig aiProfileConfig,
            String requestedAiProfile
    ) {
        try {
            return aiProfileConfig.buildChatProfile(requestedAiProfile);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage(), error);
        }
    }

    public static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    public static String firstText(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    public static String safe(String value) {
        return value == null ? "" : value;
    }

    public static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public static Map<String, Object> parseJsonObject(String output) {
        try {
            Object parsed = OBJECT_MAPPER.readValue(extractJsonObject(output), Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
            throw new IllegalArgumentException("JSON object가 아닙니다.");
        } catch (Exception error) {
            throw new IllegalArgumentException("LLM JSON 응답을 해석할 수 없습니다.", error);
        }
    }

    public static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new IllegalArgumentException("JSON 직렬화에 실패했습니다.", error);
        }
    }

    public static String extractJsonObject(String output) {
        String text = text(output);
        if (text == null) {
            throw new IllegalArgumentException("빈 LLM 응답입니다.");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("JSON object를 찾을 수 없습니다.");
        }
        return text.substring(start, end + 1);
    }
}