package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PartRouteResolver {
    private static final Pattern UUID_TEXT = Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern GPU_CLASS = Pattern.compile("(?i)(?:rtx|geforce|지포스)?\\s*(40[6-9]0|50[6-9]0)(?:\\s*(ti|super))?");
    private static final Pattern CPU_MODEL = Pattern.compile("(?i)\\b\\d{4,5}x3d\\b|\\b\\d{4,5}x\\b|\\bi[3579]-?\\d{4,5}\\b");
    private static final List<String> CATEGORIES = List.of("CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER");

    private final JdbcTemplate jdbcTemplate;

    public PartRouteResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ResolvedRoute> resolveFastRoute(String message, String selectedCategory) {
        String normalized = normalizeCommand(message);
        if (!hasProductRouteIntent(normalized) || hasActionMutationIntent(normalized)) {
            return Optional.empty();
        }
        String category = firstText(categoryFrom(selectedCategory), inferCategory(message));
        String partQuery = extractPartQuery(message);
        String route = resolvePartDetailRoute(partQuery, category);
        if (route != null) {
            return Optional.of(new ResolvedRoute(
                    route,
                    "상품 상세 보기",
                    "상품 상세 화면으로 이동했습니다.",
                    "FAST_PART_DETAIL_ROUTE"
            ));
        }
        if (category != null) {
            String filterRoute = resolveCategoryFilterRoute(partQuery, category);
            return Optional.of(new ResolvedRoute(
                    filterRoute,
                    categoryLabel(category) + " 후보 보기",
                    categoryLabel(category) + " 후보 화면으로 이동했습니다.",
                    "FAST_PART_DETAIL_FILTER_ROUTE"
            ));
        }
        return Optional.empty();
    }

    public String resolveCategoryFilterRoute(String partQuery, String category) {
        String safeCategory = categoryFrom(category);
        if (safeCategory == null) {
            return null;
        }
        String query = firstText(extractPartQuery(partQuery), null);
        if (query == null) {
            return "/self-quote?category=" + safeCategory;
        }
        return "/self-quote?category=" + safeCategory + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    public String resolvePartDetailRoute(String partQuery, String category) {
        String query = firstText(partQuery, null);
        if (query == null) {
            return null;
        }
        String safeCategory = categoryFrom(category);
        List<Map<String, Object>> rawExactRows = exactRawRows(query.trim(), safeCategory);
        if (rawExactRows.size() == 1) {
            return "/parts/" + DbValueMapper.string(rawExactRows.get(0), "id");
        }
        if (UUID_TEXT.matcher(query.trim()).matches()) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                            SELECT public_id::text AS id, category, name, manufacturer
                            FROM parts
                            WHERE public_id = ?::uuid
                              AND status = 'ACTIVE'
                              AND deleted_at IS NULL
                            LIMIT 1
                            """, query.trim());
            return rows.size() == 1 ? "/parts/" + DbValueMapper.string(rows.get(0), "id") : null;
        }

        String normalizedQuery = normalizePartRouteText(query);
        if (normalizedQuery.length() < 3) {
            return null;
        }
        List<Map<String, Object>> normalizedExactRows = exactNormalizedRows(normalizedQuery, safeCategory);
        if (normalizedExactRows.size() == 1) {
            return "/parts/" + DbValueMapper.string(normalizedExactRows.get(0), "id");
        }
        String searchTerm = routeSearchTerm(normalizedQuery);
        if (searchTerm == null) {
            return null;
        }
        List<Map<String, Object>> rows = safeCategory == null
                ? jdbcTemplate.queryForList("""
                                SELECT public_id::text AS id, category, name, manufacturer
                                FROM parts
                                WHERE status = 'ACTIVE'
                                  AND deleted_at IS NULL
                                  AND (
                                    lower(name) LIKE '%' || lower(?) || '%'
                                    OR lower(coalesce(manufacturer, '')) LIKE '%' || lower(?) || '%'
                                    OR lower(coalesce(manufacturer, '') || ' ' || name) LIKE '%' || lower(?) || '%'
                                  )
                                ORDER BY price DESC, id ASC
                                LIMIT 50
                                """, searchTerm, searchTerm, searchTerm)
                : jdbcTemplate.queryForList("""
                                SELECT public_id::text AS id, category, name, manufacturer
                                FROM parts
                                WHERE status = 'ACTIVE'
                                  AND deleted_at IS NULL
                                  AND category = ?
                                  AND (
                                    lower(name) LIKE '%' || lower(?) || '%'
                                    OR lower(coalesce(manufacturer, '')) LIKE '%' || lower(?) || '%'
                                    OR lower(coalesce(manufacturer, '') || ' ' || name) LIKE '%' || lower(?) || '%'
                                  )
                                ORDER BY price DESC, id ASC
                                LIMIT 50
                                """, safeCategory, searchTerm, searchTerm, searchTerm);
        List<Map<String, Object>> exactMatches = rows.stream()
                .filter(row -> isExactPartRouteMatch(normalizedQuery, row))
                .toList();
        if (exactMatches.size() == 1) {
            return "/parts/" + DbValueMapper.string(exactMatches.get(0), "id");
        }
        List<Map<String, Object>> strictMatches = rows.stream()
                .filter(row -> isHighConfidencePartRouteMatch(normalizedQuery, row))
                .toList();
        if (strictMatches.size() == 1) {
            return "/parts/" + DbValueMapper.string(strictMatches.get(0), "id");
        }
        List<String> tokens = routeTokens(normalizedQuery);
        if (tokens.size() == 1 && isStrongModelToken(tokens.get(0)) && rows.size() == 1) {
            return "/parts/" + DbValueMapper.string(rows.get(0), "id");
        }
        return null;
    }

    private List<Map<String, Object>> exactRawRows(String query, String safeCategory) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (safeCategory == null) {
            return jdbcTemplate.queryForList("""
                            SELECT public_id::text AS id, category, name, manufacturer
                            FROM parts
                            WHERE status = 'ACTIVE'
                              AND deleted_at IS NULL
                              AND (
                                lower(name) = lower(?)
                                OR lower(coalesce(manufacturer, '') || ' ' || name) = lower(?)
                              )
                            ORDER BY price DESC, id ASC
                            LIMIT 3
                            """, query, query);
        }
        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id, category, name, manufacturer
                        FROM parts
                        WHERE status = 'ACTIVE'
                          AND deleted_at IS NULL
                          AND category = ?
                          AND (
                            lower(name) = lower(?)
                            OR lower(coalesce(manufacturer, '') || ' ' || name) = lower(?)
                          )
                        ORDER BY price DESC, id ASC
                        LIMIT 3
                        """, safeCategory, query, query);
    }

    private List<Map<String, Object>> exactNormalizedRows(String normalizedQuery, String safeCategory) {
        String normalizedNameSql = "trim(regexp_replace(regexp_replace(upper(%s), '[^0-9A-Z가-힣]+', ' ', 'g'), '\\s+', ' ', 'g'))";
        String nameExpr = normalizedNameSql.formatted("name");
        String manufacturerNameExpr = normalizedNameSql.formatted("coalesce(manufacturer, '') || ' ' || name");
        if (safeCategory == null) {
            return jdbcTemplate.queryForList("""
                            SELECT public_id::text AS id, category, name, manufacturer
                            FROM parts
                            WHERE status = 'ACTIVE'
                              AND deleted_at IS NULL
                              AND (
                                %s = ?
                                OR %s = ?
                              )
                            ORDER BY price DESC, id ASC
                            LIMIT 3
                            """.formatted(nameExpr, manufacturerNameExpr), normalizedQuery, normalizedQuery);
        }
        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id, category, name, manufacturer
                        FROM parts
                        WHERE status = 'ACTIVE'
                          AND deleted_at IS NULL
                          AND category = ?
                          AND (
                            %s = ?
                            OR %s = ?
                          )
                        ORDER BY price DESC, id ASC
                        LIMIT 3
                        """.formatted(nameExpr, manufacturerNameExpr), safeCategory, normalizedQuery, normalizedQuery);
    }

    public static String inferCategory(String message) {
        String normalized = safe(message).toLowerCase(Locale.ROOT);
        String compact = normalizeCommand(message);
        if (containsAnyNormalized(compact, "메인보드", "마더보드", "보드", "motherboard")) {
            return "MOTHERBOARD";
        }
        if (containsAnyNormalized(compact, "쿨러", "cooler", "수랭", "공랭", "aio")) {
            return "COOLER";
        }
        if (containsAnyNormalized(compact, "ssd", "스토리지", "저장장치", "저장공간", "nvme")) {
            return "STORAGE";
        }
        if (containsAnyNormalized(compact, "파워", "psu", "전원공급")) {
            return "PSU";
        }
        if (containsAnyNormalized(compact, "케이스", "case")) {
            return "CASE";
        }
        if (containsAnyNormalized(compact, "ram", "램", "메모리", "memory", "ddr5", "ddr4")) {
            return "RAM";
        }
        if (containsAnyNormalized(compact, "gpu", "그래픽카드", "그래픽", "글카", "vga", "rtx", "geforce", "지포스", "nvidia", "엔비디아")
                || GPU_CLASS.matcher(normalized).find()) {
            return "GPU";
        }
        if (containsAnyNormalized(compact, "cpu", "프로세서", "라이젠", "ryzen", "intel", "인텔")
                || CPU_MODEL.matcher(normalized).find()) {
            return "CPU";
        }
        return null;
    }

    private static boolean hasProductRouteIntent(String normalized) {
        return containsAnyNormalized(normalized, "상세", "상품페이지", "제품페이지", "제품상세", "상품상세", "보여", "열어", "이동", "페이지")
                && !containsAnyNormalized(normalized, "추천해", "추천좀", "추천", "견적추천", "pc추천");
    }

    private static boolean hasActionMutationIntent(String normalized) {
        return containsAnyNormalized(normalized, "담아", "넣어", "적용", "추가", "빼", "삭제", "제거", "바꿔", "교체", "수량", "변경", "가격알림");
    }

    private static boolean hasConcreteProductHint(String message) {
        String normalized = safe(message).toLowerCase(Locale.ROOT);
        String compact = normalizeCommand(message);
        return GPU_CLASS.matcher(normalized).find()
                || CPU_MODEL.matcher(normalized).find()
                || containsAnyNormalized(compact,
                "asus", "msi", "gigabyte", "기가바이트", "lianli", "리안리", "samsung", "삼성",
                "corsair", "커세어", "noctua", "녹투아", "arctic", "amd", "intel", "인텔", "라이젠",
                "nvidia", "엔비디아", "ddr5", "ddr4", "nvme", "수랭", "공랭", "aio");
    }

    public static String extractPartQuery(String message) {
        String query = safe(message)
                .replaceAll("(?i)(상세\\s*페이지|상품\\s*페이지|제품\\s*페이지|제품\\s*상세|상품\\s*상세|상세|보여줘봐|보여줘|보여|열어줘|열어|이동해줘|이동해|이동|가줘|가자|페이지|화면|정보|좀)", " ")
                .replaceAll("\\s+(로|으로)\\s*$", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return query.isEmpty() ? message : query;
    }

    private static boolean isExactPartRouteMatch(String normalizedQuery, Map<String, Object> row) {
        String name = DbValueMapper.string(row, "name");
        String manufacturer = DbValueMapper.string(row, "manufacturer");
        String normalizedName = normalizePartRouteText(name);
        String normalizedManufacturerName = normalizePartRouteText(firstText(manufacturer, "") + " " + firstText(name, ""));
        return normalizedName.equals(normalizedQuery) || normalizedManufacturerName.equals(normalizedQuery);
    }

    private static boolean isHighConfidencePartRouteMatch(String normalizedQuery, Map<String, Object> row) {
        String name = DbValueMapper.string(row, "name");
        String manufacturer = DbValueMapper.string(row, "manufacturer");
        String normalizedManufacturerName = normalizePartRouteText(firstText(manufacturer, "") + " " + firstText(name, ""));
        if (isExactPartRouteMatch(normalizedQuery, row)) {
            return true;
        }
        List<String> tokens = routeTokens(normalizedQuery);
        if (tokens.size() < 3) {
            return false;
        }
        String haystack = normalizedManufacturerName;
        return tokens.stream().allMatch(token -> routeTokenMatches(haystack, token));
    }

    private static List<String> routeTokens(String normalized) {
        return Arrays.stream(normalized.split(" "))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .filter(token -> !isRouteStopToken(token))
                .distinct()
                .toList();
    }

    private static String routeSearchTerm(String normalizedQuery) {
        List<String> tokens = routeTokens(normalizedQuery);
        return tokens.stream()
                .filter(token -> token.length() >= 3)
                .filter(token -> token.matches(".*\\d.*") && token.matches(".*[A-Z].*"))
                .findFirst()
                .or(() -> tokens.stream()
                        .filter(token -> token.length() >= 3)
                        .filter(token -> token.matches(".*\\d.*"))
                        .findFirst())
                .or(() -> tokens.stream()
                        .filter(token -> token.length() >= 3)
                        .findFirst())
                .orElse(null);
    }

    private static boolean isRouteStopToken(String token) {
        if (List.of("GPU", "CPU", "RTX", "GEFORCE", "그래픽카드", "글카", "상품", "제품", "상세", "보기", "보여줘").contains(token)) {
            return true;
        }
        return token.contains("상세") || token.contains("페이지") || token.contains("이동") || token.contains("보여") || token.contains("열어");
    }

    private static boolean routeTokenMatches(String normalizedHaystack, String token) {
        if (token.matches(".*[0-9A-Z].*")) {
            return Arrays.asList(normalizedHaystack.split(" ")).contains(token);
        }
        return normalizedHaystack.contains(token);
    }

    private static boolean isStrongModelToken(String token) {
        return token.length() >= 4 && token.matches(".*\\d.*") && token.matches(".*[A-Z].*");
    }

    private static String normalizePartRouteText(String value) {
        return safe(value)
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^0-9A-Z가-힣]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizeCommand(String message) {
        return safe(message).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static boolean containsAnyNormalized(String normalized, String... keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(normalizeCommand(keyword))) {
                return true;
            }
        }
        return false;
    }

    private static String categoryFrom(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return CATEGORIES.contains(normalized) ? normalized : null;
    }

    private static String categoryLabel(String category) {
        return switch (category) {
            case "CPU" -> "CPU";
            case "MOTHERBOARD" -> "메인보드";
            case "RAM" -> "RAM";
            case "GPU" -> "GPU";
            case "STORAGE" -> "SSD";
            case "PSU" -> "파워";
            case "CASE" -> "케이스";
            case "COOLER" -> "쿨러";
            default -> "부품";
        };
    }

    private static String firstText(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record ResolvedRoute(String route, String label, String message, String reason) {
        public Map<String, Object> asPayload() {
            return MockData.map("route", route, "reason", reason);
        }
    }
}
