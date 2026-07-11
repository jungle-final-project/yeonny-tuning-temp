package com.buildgraph.prototype.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class DbValueMapper {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Object> OBJECT_TYPE = new TypeReference<>() {
    };

    private DbValueMapper() {
    }

    public static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    public static Integer integer(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? null : Integer.valueOf(value.toString());
    }

    public static Object timestamp(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        return value;
    }

    public static Object json(Map<String, Object> row, String key, Object fallback) {
        Object value = row.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return value;
        }
        try {
            return OBJECT_MAPPER.readValue(value.toString(), OBJECT_TYPE);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static List<Object> textArray(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof Array array) {
            try {
                Object arrayValue = array.getArray();
                if (arrayValue instanceof Object[] values) {
                    return List.of(values);
                }
            } catch (SQLException ignored) {
                return List.of();
            }
        }
        if (value instanceof Object[] values) {
            return List.of(values);
        }
        return List.of(value.toString());
    }
}
