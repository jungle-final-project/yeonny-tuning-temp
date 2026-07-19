package com.buildgraph.prototype.recommendation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class HomePartSummaryMapper {
    private static final List<String> REQUIRED_PART_FIELDS =
            List.of("id", "category", "name", "price", "status");
    private static final List<String> OPTIONAL_PART_FIELDS =
            List.of("manufacturer");
    private static final List<String> HOME_ATTRIBUTE_FIELDS =
            List.of("imageUrl", "shortSpec");

    private HomePartSummaryMapper() {
    }

    static Map<String, Object> categoryParts(Map<String, Object> categoryParts) {
        Map<String, Object> result = new LinkedHashMap<>();
        categoryParts.forEach((category, parts) -> result.put(category, partSummaries(parts)));
        return result;
    }

    static Map<String, Object> recommendedParts(Map<String, Object> recommendedParts) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", recommendationSummaries(recommendedParts.get("items")));
        copyIfPresent(recommendedParts, result, "generatedAt");
        copyIfPresent(recommendedParts, result, "fallbackUsed");
        return result;
    }

    private static List<Map<String, Object>> recommendationSummaries(Object value) {
        if (!(value instanceof Iterable<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> rawItem) {
                result.add(recommendedPartSummary(rawItem));
            }
        }
        return result;
    }

    private static Map<String, Object> recommendedPartSummary(Map<?, ?> item) {
        Map<String, Object> result = new LinkedHashMap<>();
        copyIfPresent(item, result, "recommendationId");
        copyIfPresent(item, result, "rankPosition");
        result.put("part", partSummary(item.get("part")));
        copyIfPresent(item, result, "scoreSource");
        copyIfPresent(item, result, "modelVersion");
        copyIfPresent(item, result, "reasonTags");
        return result;
    }

    private static List<Map<String, Object>> partSummaries(Object value) {
        if (!(value instanceof Iterable<?> parts)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object part : parts) {
            result.add(partSummary(part));
        }
        return result;
    }

    private static Map<String, Object> partSummary(Object value) {
        if (!(value instanceof Map<?, ?> part)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : REQUIRED_PART_FIELDS) {
            copyIfPresent(part, result, field);
        }
        for (String field : OPTIONAL_PART_FIELDS) {
            copyIfPresent(part, result, field);
        }

        Map<String, Object> attributes = lightweightAttributes(part.get("attributes"));
        if (!attributes.isEmpty()) {
            result.put("attributes", attributes);
        }

        Map<String, Object> externalOffer = lightweightExternalOffer(part.get("externalOffer"));
        if (!externalOffer.isEmpty()) {
            result.put("externalOffer", externalOffer);
        }
        return result;
    }

    private static Map<String, Object> lightweightAttributes(Object value) {
        if (!(value instanceof Map<?, ?> attributes)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : HOME_ATTRIBUTE_FIELDS) {
            copyIfPresent(attributes, result, field);
        }
        return result;
    }

    private static Map<String, Object> lightweightExternalOffer(Object value) {
        if (!(value instanceof Map<?, ?> externalOffer)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        copyIfPresent(externalOffer, result, "imageUrl");
        return result;
    }

    private static void copyIfPresent(Map<?, ?> source, Map<String, Object> target, String key) {
        if (!source.containsKey(key)) {
            return;
        }
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }
}
