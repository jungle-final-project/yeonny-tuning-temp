package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PartRouteResolver {
    private static final Pattern UUID_TEXT = Pattern.compile("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern GPU_CLASS = Pattern.compile("(?i)(?:rtx|geforce|지포스)?\\s*(40[6-9]0|50[5-9]0)(?:\\s*(ti|super))?");
    private static final Pattern CPU_MODEL = Pattern.compile("(?i)\\b\\d{4,5}x3d\\b|\\b\\d{4,5}x\\b|\\bi[3579]-?\\d{4,5}\\b");
    private static final List<String> CATEGORIES = List.of("CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER");

    /**
     * 카테고리 이름 그 자체인 토큰. 이미 category= 로 거른 목록에 이 말을 q로 또 실으면
     * 이름에 그 단어가 든 상품만 남아 목록이 잘린다 — 메인보드 상당수는 이름에 "메인보드"가 없다.
     */
    private static final Set<String> CATEGORY_WORD_TOKENS = Set.of(
            "메인보드", "마더보드", "보드", "MOTHERBOARD", "MAINBOARD",
            "쿨러", "COOLER", "히트싱크",
            "SSD", "HDD", "스토리지", "저장장치", "저장공간",
            "파워", "PSU", "파워서플라이", "전원공급장치",
            "케이스", "CASE", "본체",
            "램", "메모리", "RAM", "MEMORY",
            "그래픽카드", "그래픽", "글카", "VGA", "GPU",
            "CPU", "프로세서",
            "부품", "상품", "제품", "컴퓨터"
    );

    // 한글은 단어 경계가 없어 개별 예외로 막는다: '키보드'의 보드, '쿨러마스터'(파워 제조사)의 쿨러, '프로그램'의 램.
    private static final Pattern BOARD_NOUN = Pattern.compile("(?<!키)보드");
    private static final Pattern COOLER_NOUN = Pattern.compile("쿨러(?!마스터)");
    private static final Pattern RAM_NOUN = Pattern.compile("(?<!그)램");
    private static final Pattern ASCII_MOTHERBOARD = asciiWords("motherboard");
    private static final Pattern ASCII_COOLER = asciiWords("cooler", "aio");
    private static final Pattern ASCII_STORAGE = asciiWords("ssd", "nvme");
    private static final Pattern ASCII_PSU = asciiWords("psu");
    private static final Pattern ASCII_CASE = asciiWords("case");
    private static final Pattern ASCII_RAM = asciiWords("ram", "memory", "ddr5", "ddr4");
    private static final Pattern ASCII_GPU = asciiWords("gpu", "vga", "rtx", "geforce", "nvidia");
    private static final Pattern ASCII_CPU = asciiWords("cpu", "ryzen", "intel");

    /** 영문 키워드는 단어 경계에서만 인정한다 — 'FRAME'의 ram, 'audio/studio'의 aio, 'coolermaster'의 cooler를 걸러낸다. */
    private static Pattern asciiWords(String... keywords) {
        return Pattern.compile("(?<![a-z])(?:" + String.join("|", keywords) + ")(?![a-z])");
    }

    private final JdbcTemplate jdbcTemplate;

    public PartRouteResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ResolvedRoute> resolveFastRoute(String message, String selectedCategory) {
        return resolveFastRoute(message, selectedCategory, false);
    }

    /**
     * @param intentAlreadyKnown 이동 의도가 문장 밖에서 확정된 경우(되묻기 칩 선택). 이때는 어휘 게이트를 건너뛴다 —
     *                           칩 라벨은 DB 상품명 전문이라 "추천"·"포함" 같은 말이 섞여 게이트에 걸릴 수 있다.
     */
    public Optional<ResolvedRoute> resolveFastRoute(String message, String selectedCategory, boolean intentAlreadyKnown) {
        String normalized = normalizeCommand(message);
        if (!intentAlreadyKnown && (!hasProductRouteIntent(normalized) || hasActionMutationIntent(normalized))) {
            return Optional.empty();
        }
        String category = firstText(categoryFrom(selectedCategory), inferCategory(message));
        String partQuery = extractPartQuery(message);
        String route = resolvePartDetailRoute(partQuery, category);
        if (route == null && category != null) {
            // 카테고리 추정이 틀리면(예: 'FRAME'이 든 케이스명) 이름이 정확히 일치하는 상품조차 못 찾는다.
            // 카테고리 없이 한 번 더 — 단일 정확 일치만 통과하므로 여전히 안전하다.
            route = resolvePartDetailRoute(partQuery, null);
        }
        if (route != null) {
            return Optional.of(new ResolvedRoute(
                    route,
                    "상품 상세 보기",
                    "상품 상세 화면으로 이동했습니다.",
                    "FAST_PART_DETAIL_ROUTE"
            ));
        }
        // 목록 대체 이동은 이 경로에서 하지 않는다. 상품을 하나로 특정했을 때만 결정적으로 이동하고,
        // 못 특정하면 빈 값을 돌려 기존 LLM 경로(후보 칩 되묻기 → 목록 폴백)가 그대로 담당하게 한다.
        return Optional.empty();
    }

    /**
     * 상품을 하나로 특정하지 못해 카테고리 후보 목록으로 대신 보낼 때의 경로.
     * q에는 사용자가 쓴 말 전체가 아니라 리졸버가 실제 매칭에 쓴 토큰만 싣는다 —
     * 도착 목록(GET /api/parts?q=)은 통짜 LIKE라 원문을 그대로 실으면
     * 리졸버가 14건 찾아 놓고 화면은 0건이 된다. 실을 토큰이 없으면 q를 붙이지 않는다.
     */
    public String categoryFilterRoute(String category, PartDetailResolution resolution) {
        String safeCategory = categoryFrom(category);
        if (safeCategory == null) {
            return null;
        }
        String searchTerm = resolution == null ? null : resolution.listSearchTerm(safeCategory);
        if (searchTerm == null) {
            return "/self-quote?category=" + safeCategory;
        }
        return "/self-quote?category=" + safeCategory + "&q=" + URLEncoder.encode(searchTerm, StandardCharsets.UTF_8);
    }

    public String resolvePartDetailRoute(String partQuery, String category) {
        return resolvePartDetail(partQuery, category).route();
    }

    /**
     * 상품 상세 경로를 해상한다. 하나로 특정하지 못하면 route는 null이고, 대신 질의에 걸린 후보를 함께 돌려준다 —
     * 후보가 두어 개뿐이면 목록 화면으로 보내는 대신 채팅에서 바로 골라 달라고 되물을 수 있기 때문이다.
     */
    public PartDetailResolution resolvePartDetail(String partQuery, String category) {
        String query = firstText(partQuery, null);
        if (query == null) {
            return PartDetailResolution.NONE;
        }
        String safeCategory = categoryFrom(category);
        List<Map<String, Object>> rawExactRows = exactRawRows(query.trim(), safeCategory);
        if (rawExactRows.size() == 1) {
            return PartDetailResolution.detail(rawExactRows.get(0));
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
            return rows.size() == 1 ? PartDetailResolution.detail(rows.get(0)) : PartDetailResolution.NONE;
        }

        String normalizedQuery = normalizePartRouteText(query);
        if (normalizedQuery.length() < 3) {
            return PartDetailResolution.NONE;
        }
        List<Map<String, Object>> normalizedExactRows = exactNormalizedRows(normalizedQuery, safeCategory);
        if (normalizedExactRows.size() == 1) {
            return PartDetailResolution.detail(normalizedExactRows.get(0));
        }
        // 검색어 후보를 순서대로 받아 걸리는 것이 나올 때까지 물어본다. 하나만 골라 두면
        // "최신 메인보드"에서 '최신'(상품명에 없는 수식어)만 써 보고 0건으로 끝나, 종전에 목록으로
        // 가던 카테고리 둘러보기가 "그런 상품이 없어요"로 막힌다.
        String searchTerm = null;
        List<Map<String, Object>> rows = List.of();
        for (String candidate : routeSearchTerms(normalizedQuery)) {
            rows = partRouteRows(candidate, safeCategory);
            searchTerm = candidate;
            if (!rows.isEmpty()) {
                break;
            }
        }
        if (searchTerm == null) {
            return PartDetailResolution.NONE;
        }
        return resolveFromRows(normalizedQuery, rows, searchTerm);
    }

    private PartDetailResolution resolveFromRows(
            String normalizedQuery,
            List<Map<String, Object>> rows,
            String searchTerm
    ) {
        List<Map<String, Object>> exactMatches = rows.stream()
                .filter(row -> isExactPartRouteMatch(normalizedQuery, row))
                .toList();
        if (exactMatches.size() == 1) {
            return PartDetailResolution.detail(exactMatches.get(0));
        }
        List<Map<String, Object>> strictMatches = rows.stream()
                .filter(row -> isHighConfidencePartRouteMatch(normalizedQuery, row))
                .toList();
        if (strictMatches.size() == 1) {
            return PartDetailResolution.detail(strictMatches.get(0));
        }
        List<String> tokens = routeTokens(normalizedQuery);
        if (tokens.size() == 1 && rows.size() == 1 && isSingleTokenDetailMatch(tokens.get(0), rows.get(0))) {
            return PartDetailResolution.detail(rows.get(0));
        }
        // 하나로 못 좁혔다 — 무엇이 걸렸는지 후보로 넘겨, 호출자가 되묻기와 목록 이동 중에 고르게 한다.
        return PartDetailResolution.choices(rows, searchTerm);
    }

    private List<Map<String, Object>> partRouteRows(String searchTerm, String safeCategory) {
        return safeCategory == null
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
        String normalized = safe(message).toLowerCase(Locale.ROOT);   // 공백 유지 — 영문 단어 경계 판정용
        String compact = normalizeCommand(message);                   // 공백 제거 — 한글 띄어쓰기 흡수용
        if (ASCII_MOTHERBOARD.matcher(normalized).find() || BOARD_NOUN.matcher(compact).find()) {
            return "MOTHERBOARD";
        }
        if (ASCII_COOLER.matcher(normalized).find()
                || COOLER_NOUN.matcher(compact).find()
                || containsAnyNormalized(compact, "수랭", "공랭")) {
            return "COOLER";
        }
        if (ASCII_STORAGE.matcher(normalized).find()
                || containsAnyNormalized(compact, "스토리지", "저장장치", "저장공간")) {
            return "STORAGE";
        }
        if (ASCII_PSU.matcher(normalized).find() || containsAnyNormalized(compact, "파워", "전원공급")) {
            return "PSU";
        }
        if (ASCII_CASE.matcher(normalized).find() || containsAnyNormalized(compact, "케이스")) {
            return "CASE";
        }
        if (ASCII_RAM.matcher(normalized).find()
                || RAM_NOUN.matcher(compact).find()
                || containsAnyNormalized(compact, "메모리")) {
            return "RAM";
        }
        if (ASCII_GPU.matcher(normalized).find()
                || containsAnyNormalized(compact, "그래픽카드", "그래픽", "글카", "지포스", "엔비디아")
                || GPU_CLASS.matcher(normalized).find()) {
            return "GPU";
        }
        if (ASCII_CPU.matcher(normalized).find()
                || containsAnyNormalized(compact, "프로세서", "라이젠", "인텔")
                || CPU_MODEL.matcher(normalized).find()) {
            return "CPU";
        }
        return null;
    }

    private static boolean hasProductRouteIntent(String normalized) {
        // 결정적 경로는 '화면을 옮겨 달라'가 문장에 명시된 경우로만 좁힌다. 맨 '상세'·'보여'는
        // "상세 스펙 알려줘"·"성능 보여줘"처럼 답변을 원하는 문장까지 끌어와 LLM 몫을 뺏는다.
        return containsAnyNormalized(normalized, "상세페이지", "상품페이지", "제품페이지", "제품상세", "상품상세", "이동", "열어", "페이지")
                && !containsAnyNormalized(normalized, "추천해", "추천좀", "추천", "견적추천", "pc추천");
    }

    private static boolean hasActionMutationIntent(String normalized) {
        return containsAnyNormalized(normalized, "담아", "넣어", "적용", "추가", "빼", "삭제", "제거", "바꿔", "교체", "수량", "변경", "가격알림",
                // BuildChatIntentRouter의 mutation 어휘와 맞춘다 — 거기서 UNSUPPORTED로 떨어진
                // 드래프트 조작 명령이 이동 게이트로 새지 않게.
                "올려줘", "올려주", "내려줘", "낮춰", "줄여", "늘려");
    }

    public static boolean hasConcreteProductHint(String message) {
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

    /**
     * 무엇을 찾는지 가장 잘 말해 주는 토큰 하나를 고른다. 모델명 → 실제 검색어 → 카테고리 이름 순이다.
     * 카테고리 이름을 마지막에 두는 이유: '삼성 메모리'에서 '메모리'를 고르면 삼성과 무관한 RAM이
     * 통째로 나온다. 두 글자를 받아 주는 이유: 한글 브랜드는 두 글자가 흔해(삼성·인텔·조텍)
     * 세 글자 하한만 두면 '삼성 램'이 통째로 미해상이 되어 "그런 상품이 없어요"가 나간다.
     */
    private static List<String> routeSearchTerms(String normalizedQuery) {
        List<String> tokens = routeTokens(normalizedQuery);
        List<java.util.function.Predicate<String>> tiers = List.of(
                token -> token.length() >= 3 && token.matches(".*\\d.*") && token.matches(".*[A-Z].*"),
                token -> token.length() >= 3 && token.matches(".*\\d.*"),
                token -> token.length() >= 3 && !isCategoryWordToken(token),
                token -> token.length() >= 2 && !isCategoryWordToken(token),
                // 카테고리 이름을 맨 뒤에 둔다. 앞 후보가 하나도 안 걸릴 때는 여기까지 내려와야
                // '최신 메인보드'가 메인보드 목록으로 간다 — '최신'만 써 보고 포기하면
                // 종전에 되던 카테고리 둘러보기가 "그런 상품이 없어요"로 막힌다.
                token -> token.length() >= 3
        );
        List<String> candidates = new java.util.ArrayList<>();
        for (java.util.function.Predicate<String> tier : tiers) {
            tokens.stream()
                    .filter(tier)
                    .findFirst()
                    .filter(token -> !candidates.contains(token))
                    .ifPresent(candidates::add);
        }
        return List.copyOf(candidates);
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

    /**
     * 후보가 정확히 1건일 때 그 1건으로 이동을 확정해도 되는 질의인지.
     * 영숫자 혼합 모델명은 종전대로 즉시 인정하고, 숫자만인 모델명(5050·4060·13600)은
     * 스펙 숫자와 구분이 안 되므로 후보 자체를 근거로 조건을 더 건다.
     */
    private static boolean isSingleTokenDetailMatch(String token, Map<String, Object> row) {
        if (isStrongModelToken(token)) {
            return true;
        }
        if (!isModelNumberToken(token)) {
            return false;
        }
        // (1) 숫자 모델명을 쓰는 카테고리인가 — 1000(W)·6000(MT/s)·2000(GB) 같은 스펙 숫자를 여기서 자른다.
        String category = DbValueMapper.string(row, "category");
        if (!"GPU".equals(category) && !"CPU".equals(category)) {
            return false;
        }
        // (2) 상품명 안에서 독립 단어로 등장하는가 — LIKE '%1000%'가 '1000W'에 걸린 경우를 자른다.
        String haystack = normalizePartRouteText(
                firstText(DbValueMapper.string(row, "manufacturer"), "") + " " + firstText(DbValueMapper.string(row, "name"), ""));
        return routeTokenMatches(haystack, token);
    }

    private static boolean isCategoryWordToken(String token) {
        return token != null && CATEGORY_WORD_TOKENS.contains(token.toUpperCase(Locale.ROOT));
    }

    /** 4~5자리 숫자만인 모델 번호. 연도(1990~2039)는 제외한다 — RTX 2060~2080은 이 범위 밖이라 살아남는다. */
    private static boolean isModelNumberToken(String token) {
        if (!token.matches("\\d{4,5}")) {
            return false;
        }
        int value = Integer.parseInt(token);
        return value < 1990 || value > 2039;
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

    private static String firstText(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 상품 상세 해상 결과. route가 있으면 한 상품으로 특정된 것이고, 없으면 choices가 무엇이 걸렸는지 알려 준다.
     * matchedTerm/matchedCategories는 "서버가 무엇을 보고 무엇을 찾았는지"의 기록이다 —
     * 목록으로 대신 보낼 때 q에 실을 검색어를 여기서만 정한다.
     */
    public record PartDetailResolution(
            String route,
            List<String> choices,
            String matchedTerm,
            List<String> matchedCategories) {
        static final PartDetailResolution NONE = new PartDetailResolution(null, List.of(), null, List.of());

        static PartDetailResolution detail(Map<String, Object> row) {
            return new PartDetailResolution("/parts/" + DbValueMapper.string(row, "id"), List.of(), null, List.of());
        }

        static PartDetailResolution choices(List<Map<String, Object>> rows, String matchedTerm) {
            return new PartDetailResolution(
                    null,
                    rows.stream()
                            .map(row -> DbValueMapper.string(row, "name"))
                            .filter(name -> name != null && !name.isBlank())
                            .distinct()
                            .toList(),
                    rows.isEmpty() ? null : matchedTerm,
                    rows.stream()
                            .map(row -> DbValueMapper.string(row, "category"))
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList());
        }

        /**
         * 이 카테고리 목록으로 보낼 때 q에 실을 검색어. 리졸버가 그 카테고리에서 실제로 걸어 본 토큰일 때만 돌려준다 —
         * 못 찾은 말을 q에 실으면 "후보 목록에서 확인해 주세요"라고 보내 놓고 빈 목록에 떨구게 된다.
         */
        String listSearchTerm(String category) {
            if (matchedTerm == null
                    || !matchedCategories.contains(category)
                    || isCategoryWordToken(matchedTerm)) {
                return null;
            }
            return matchedTerm;
        }
    }

    public record ResolvedRoute(String route, String label, String message, String reason) {
        public Map<String, Object> asPayload() {
            return MockData.map("route", route, "reason", reason);
        }
    }
}
