package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DanawaPriceTrendService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> CATEGORIES = Set.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER");
    private static final Set<Integer> MONTHLY_RANGES = Set.of(6, 12, 24);
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final String SOURCE = "DANAWA_PRICE_TREND";
    private static final String SELECTOR_VERSION = "danawa-price-trend-ajax-v1";
    private static final Pattern PRODUCT_URL_PATTERN = Pattern.compile(
            "(?:https?:)?//prod\\.danawa\\.com/info/\\?[^\"'<>\\s]*pcode=([0-9]+)[^\"'<>\\s]*",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PCODE_PATTERN = Pattern.compile("[?&]pcode=([0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIN_PRICE_PATTERN = Pattern.compile("nMinPrice\\s*:\\s*\"([0-9,]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_TITLE_PATTERN = Pattern.compile("<meta\\s+property=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TITLE_PATTERN = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final JdbcTemplate jdbcTemplate;
    private final HttpClient httpClient;
    private final String productBaseUrl;
    private final String searchBaseUrl;
    private final long rateLimitMs;

    public DanawaPriceTrendService(
            JdbcTemplate jdbcTemplate,
            @Value("${part.danawa-trend-refresh.base-url:https://prod.danawa.com}") String productBaseUrl,
            @Value("${part.danawa-trend-refresh.search-base-url:https://search.danawa.com}") String searchBaseUrl,
            @Value("${part.danawa-trend-refresh.rate-limit-ms:3000}") Long rateLimitMs
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.productBaseUrl = normalizeBaseUrl(productBaseUrl, "https://prod.danawa.com");
        this.searchBaseUrl = normalizeBaseUrl(searchBaseUrl, "https://search.danawa.com");
        this.rateLimitMs = rateLimitMs == null ? 3000L : Math.max(0L, rateLimitMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> refreshTrends(String category, Integer limit, Integer months, Boolean force) {
        String normalizedCategory = normalizeCategory(category);
        int safeLimit = limit == null ? 286 : Math.min(Math.max(limit, 1), 500);
        int safeMonths = normalizeMonths(months);
        boolean forceRefresh = Boolean.TRUE.equals(force);
        List<Map<String, Object>> parts = targetParts(normalizedCategory, safeLimit);
        int attempted = 0;
        int collectedParts = 0;
        int collectedPoints = 0;
        int skipped = 0;
        int missing = 0;
        int failed = 0;
        List<Map<String, Object>> missingItems = new ArrayList<>();
        List<Map<String, Object>> failedItems = new ArrayList<>();

        for (Map<String, Object> part : parts) {
            if (!forceRefresh && recentlyCollected(part)) {
                skipped += 1;
                continue;
            }
            attempted += 1;
            try {
                TrendCollection collection = collectPartTrend(part, safeMonths);
                if (collection.points().isEmpty()) {
                    missing += 1;
                    appendLimited(missingItems, partIssue(part, collection.reason()));
                } else {
                    int stored = storePoints(((Number) part.get("id")).longValue(), collection.product(), safeMonths, collection.points());
                    collectedPoints += stored;
                    collectedParts += stored > 0 ? 1 : 0;
                }
            } catch (RuntimeException exception) {
                failed += 1;
                appendLimited(failedItems, partIssue(part, exception.getMessage()));
            }
            sleepBetweenRequests();
        }

        return MockData.map(
                "configured", true,
                "category", normalizedCategory,
                "months", safeMonths,
                "limit", safeLimit,
                "force", forceRefresh,
                "attempted", attempted,
                "collectedParts", collectedParts,
                "collectedPoints", collectedPoints,
                "skipped", skipped,
                "missing", missing,
                "failed", failed,
                "missingItems", missingItems,
                "failedItems", failedItems
        );
    }

    public Map<String, Object> refreshMonthlyTrends() {
        int attempted = 0;
        int collectedParts = 0;
        int collectedPoints = 0;
        int skipped = 0;
        int missing = 0;
        int failed = 0;
        List<Map<String, Object>> categoryResults = new ArrayList<>();
        for (String category : CATEGORIES.stream().sorted().toList()) {
            Map<String, Object> result = refreshTrends(category, 500, 6, false);
            categoryResults.add(result);
            attempted += numberValue(result.get("attempted"));
            collectedParts += numberValue(result.get("collectedParts"));
            collectedPoints += numberValue(result.get("collectedPoints"));
            skipped += numberValue(result.get("skipped"));
            missing += numberValue(result.get("missing"));
            failed += numberValue(result.get("failed"));
        }
        return MockData.map(
                "configured", true,
                "categories", categoryResults,
                "attempted", attempted,
                "collectedParts", collectedParts,
                "collectedPoints", collectedPoints,
                "skipped", skipped,
                "missing", missing,
                "failed", failed
        );
    }

    private TrendCollection collectPartTrend(Map<String, Object> part, int months) {
        Optional<DanawaProduct> product = resolveProduct(part);
        if (product.isEmpty()) {
            return new TrendCollection(null, List.of(), "다나와 상품 상세 URL을 찾지 못했습니다.");
        }
        Optional<String> mismatchReason = productMismatchReason(part, product.get().productTitle());
        if (mismatchReason.isPresent()) {
            return new TrendCollection(product.get(), List.of(), mismatchReason.get());
        }
        List<TrendPoint> points = new ArrayList<>(fetchMonthlyPoints(product.get(), months));
        fetchCurrentPrice(product.get()).ifPresent(currentPrice -> points.add(currentPoint(product.get(), currentPrice)));
        if (points.isEmpty()) {
            return new TrendCollection(product.get(), List.of(), "다나와 최저가 추이 포인트가 없습니다.");
        }
        return new TrendCollection(product.get(), points, null);
    }

    private Optional<DanawaProduct> resolveProduct(Map<String, Object> part) {
        for (String candidate : productUrlCandidates(part)) {
            Optional<DanawaProduct> product = productFromUrl(candidate, "cached-offer");
            if (product.isPresent() && productMismatchReason(part, product.get().productTitle()).isEmpty()) {
                return product;
            }
        }
        return searchProduct(part, searchKeyword(part));
    }

    private List<String> productUrlCandidates(Map<String, Object> part) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addIfText(candidates, stringValue(part.get("danawa_offer_url")));
        Object rawPayload = part.get("danawa_raw_payload");
        if (rawPayload != null) {
            Object parsedPayload = DbValueMapper.json(Map.of("raw", rawPayload), "raw", Map.of());
            if (parsedPayload instanceof Map<?, ?> payload) {
                addIfText(candidates, stringValue(payload.get("offerUrl")));
                addIfText(candidates, stringValue(payload.get("sourceUrl")));
                addIfText(candidates, stringValue(payload.get("searchUrl")));
            }
        }
        return candidates.stream().toList();
    }

    private Optional<DanawaProduct> searchProduct(Map<String, Object> part, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return Optional.empty();
        }
        String searchUrl = searchBaseUrl + "/dsearch.php?k1=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&module=goods&act=dispMain";
        try {
            String html = httpGet(searchUrl, "text/html", null);
            Matcher matcher = PRODUCT_URL_PATTERN.matcher(html);
            while (matcher.find()) {
                String url = absoluteProductUrl(matcher.group());
                Optional<DanawaProduct> product = productFromUrl(url, "search");
                if (product.isPresent() && productMismatchReason(part, product.get().productTitle()).isEmpty()) {
                    return product;
                }
            }
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private Optional<DanawaProduct> productFromUrl(String url, String resolvedFrom) {
        if (!StringUtils.hasText(url)) {
            return Optional.empty();
        }
        String absolute = absoluteProductUrl(url);
        Matcher matcher = PCODE_PATTERN.matcher(absolute);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String productCode = matcher.group(1);
        String productUrl = productBaseUrl + "/info/?pcode=" + productCode;
        return Optional.of(new DanawaProduct(productCode, productUrl, resolvedFrom, fetchProductTitle(productUrl)));
    }

    private String fetchProductTitle(String productUrl) {
        try {
            String html = httpGet(productUrl, "text/html", null);
            Matcher ogTitle = OG_TITLE_PATTERN.matcher(html);
            if (ogTitle.find()) {
                return cleanHtmlTitle(ogTitle.group(1));
            }
            Matcher title = HTML_TITLE_PATTERN.matcher(html);
            if (title.find()) {
                return cleanHtmlTitle(title.group(1));
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return "";
    }

    private List<TrendPoint> fetchMonthlyPoints(DanawaProduct product, int months) {
        String trendUrl = productBaseUrl + "/info/ajax/getProductPriceList.ajax.php?productCode=" + product.productCode();
        try {
            String json = httpGet(trendUrl, "application/json,text/javascript,*/*", product.productUrl());
            JsonNode root = OBJECT_MAPPER.readTree(json);
            JsonNode period = root.path(String.valueOf(months));
            JsonNode results = period.path("result");
            if (!results.isArray()) {
                return List.of();
            }
            List<TrendPoint> points = new ArrayList<>();
            for (JsonNode result : results) {
                Integer price = integerValue(result.path("minPrice").asText(null));
                YearMonth month = parseYearMonth(result.path("date").asText(null));
                if (price == null || month == null) {
                    continue;
                }
                points.add(new TrendPoint(
                        month.atDay(1).atStartOfDay(KOREA).toOffsetDateTime(),
                        price,
                        "MONTHLY",
                        result.path("date").asText(),
                        product.productUrl()
                ));
            }
            return points;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Optional<Integer> fetchCurrentPrice(DanawaProduct product) {
        try {
            String html = httpGet(product.productUrl(), "text/html", null);
            Matcher matcher = MIN_PRICE_PATTERN.matcher(html);
            if (matcher.find()) {
                return Optional.ofNullable(integerValue(matcher.group(1).replace(",", "")));
            }
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private TrendPoint currentPoint(DanawaProduct product, int currentPrice) {
        LocalDate today = LocalDate.now(KOREA);
        OffsetDateTime collectedAt = today.atTime(12, 0).atZone(KOREA).toOffsetDateTime();
        return new TrendPoint(collectedAt, currentPrice, "CURRENT", "현재", product.productUrl());
    }

    private int storePoints(long partId, DanawaProduct product, int months, List<TrendPoint> points) {
        int stored = 0;
        String capturedAt = OffsetDateTime.now().toString();
        for (TrendPoint point : points) {
            String rawPayload = json(MockData.map(
                    "range", months + "M",
                    "pointType", point.pointType(),
                    "label", point.label(),
                    "sourceUrl", point.sourceUrl(),
                    "productCode", product.productCode(),
                    "productTitle", product.productTitle(),
                    "resolvedFrom", product.resolvedFrom(),
                    "matchValidated", true,
                    "selectorVersion", SELECTOR_VERSION,
                    "capturedAt", capturedAt
            ));
            stored += jdbcTemplate.update("""
                    INSERT INTO price_snapshots (
                      part_id,
                      price,
                      source,
                      collected_at,
                      raw_payload
                    )
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (part_id, source, collected_at) WHERE source = 'DANAWA_PRICE_TREND'
                    DO UPDATE SET
                      price = EXCLUDED.price,
                      raw_payload = EXCLUDED.raw_payload
                    """,
                    partId,
                    point.price(),
                    SOURCE,
                    point.collectedAt(),
                    rawPayload
            );
        }
        return stored;
    }

    private List<Map<String, Object>> targetParts(String category, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT p.id,
                       p.public_id::text AS public_id,
                       p.category,
                       p.name,
                       p.manufacturer,
                       p.attributes,
                       peo.offer_url AS danawa_offer_url,
                       peo.search_query AS danawa_search_query,
                       peo.raw_payload AS danawa_raw_payload,
                       dpt.last_collected_at
                FROM parts p
                LEFT JOIN part_external_offers peo
                  ON peo.part_id = p.id
                 AND peo.source = 'DANAWA_BACKUP'
                 AND peo.deleted_at IS NULL
                LEFT JOIN LATERAL (
                  SELECT max(ps.collected_at) AS last_collected_at
                  FROM price_snapshots ps
                  WHERE ps.part_id = p.id
                    AND ps.source = 'DANAWA_PRICE_TREND'
                ) dpt ON true
                WHERE p.deleted_at IS NULL
                  AND p.status = 'ACTIVE'
                """);
        if (category != null) {
            sql.append(" AND p.category = ?");
            params.add(category);
        }
        sql.append(" ORDER BY p.category, p.id LIMIT ?");
        params.add(limit);
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    private boolean recentlyCollected(Map<String, Object> part) {
        Object value = part.get("last_collected_at");
        OffsetDateTime collectedAt = null;
        if (value instanceof OffsetDateTime offsetDateTime) {
            collectedAt = offsetDateTime;
        } else if (value != null) {
            try {
                collectedAt = OffsetDateTime.parse(value.toString());
            } catch (RuntimeException ignored) {
                collectedAt = null;
            }
        }
        OffsetDateTime monthStart = YearMonth.now(KOREA).atDay(1).atStartOfDay(KOREA).toOffsetDateTime();
        return collectedAt != null && !collectedAt.isBefore(monthStart);
    }

    @SuppressWarnings("unchecked")
    private static String searchKeyword(Map<String, Object> part) {
        Object attributes = DbValueMapper.json(part, "attributes", Map.of());
        if (attributes instanceof Map<?, ?> attributeMap) {
            Object externalSources = attributeMap.get("externalSources");
            if (externalSources instanceof Map<?, ?> externalSourceMap) {
                Object danawa = externalSourceMap.get("danawa");
                if (danawa instanceof Map<?, ?> danawaMap) {
                    String keyword = stringValue(danawaMap.get("keyword"));
                    if (StringUtils.hasText(keyword)) {
                        return keyword.trim();
                    }
                }
                Object naver = externalSourceMap.get("naver");
                if (naver instanceof Map<?, ?> naverMap) {
                    String keyword = stringValue(naverMap.get("keyword"));
                    if (StringUtils.hasText(keyword)) {
                        return keyword.trim();
                    }
                }
            }
        }
        String cachedSearchQuery = stringValue(part.get("danawa_search_query"));
        if (StringUtils.hasText(cachedSearchQuery)) {
            return cachedSearchQuery.trim();
        }
        String manufacturer = stringValue(part.get("manufacturer"));
        String name = stringValue(part.get("name"));
        if (StringUtils.hasText(manufacturer) && !manufacturer.endsWith("Partner")) {
            return manufacturer + " " + name;
        }
        return name;
    }

    private String httpGet(String url, String accept, String referer) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "BuildGraphPrototype/1.0 (educational price trend refresh)")
                    .header("Accept", accept)
                    .header("Accept-Language", "ko-KR,ko;q=0.9")
                    .GET();
            if (StringUtils.hasText(referer)) {
                builder.header("Referer", referer);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("다나와 요청 실패: HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("다나와 요청이 중단되었습니다.", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("다나와 요청 실패: " + exception.getMessage(), exception);
        }
    }

    private static YearMonth parseYearMonth(String label) {
        if (!StringUtils.hasText(label)) {
            return null;
        }
        String[] chunks = label.trim().split("-");
        if (chunks.length != 2) {
            return null;
        }
        try {
            int year = Integer.parseInt(chunks[0]);
            int month = Integer.parseInt(chunks[1]);
            return YearMonth.of(2000 + year, month);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Map<String, Object> partIssue(Map<String, Object> part, String reason) {
        return MockData.map(
                "partId", stringValue(part.get("public_id")),
                "category", stringValue(part.get("category")),
                "name", stringValue(part.get("name")),
                "reason", StringUtils.hasText(reason) ? reason : "수집 실패"
        );
    }

    private static Optional<String> productMismatchReason(Map<String, Object> part, String productTitle) {
        return productMismatchReason(
                stringValue(part.get("category")),
                stringValue(part.get("name")),
                stringValue(part.get("manufacturer")),
                productTitle
        );
    }

    static Optional<String> productMismatchReason(String category, String partName, String manufacturer, String productTitle) {
        if (!StringUtils.hasText(productTitle)) {
            return Optional.of("다나와 상품 상세 제목을 확인하지 못했습니다.");
        }
        String normalizedPart = normalizeMatchText((manufacturer == null ? "" : manufacturer + " ") + partName);
        String normalizedTitle = normalizeMatchText(productTitle);
        String normalizedCategory = StringUtils.hasText(category) ? category.trim().toUpperCase(Locale.ROOT) : "";

        Optional<String> modelMismatch = switch (normalizedCategory) {
            case "GPU" -> firstMismatch(normalizedPart, normalizedTitle, List.of(
                    Pattern.compile("RTX\\s*([0-9]{4})\\s*(TI)?", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b([0-9]{1,2})GB\\b", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b(DUAL|PRIME|ASTRAL|MATRIX|TWIN\\s*EDGE|AMP\\s*WHITE|AMP\\s*EXTREME|SOLID\\s*CORE|SOLID\\s*OC|EAGLE|AERO|WINDFORCE|VENTUS|GAMING\\s*TRIO|SHADOW|VANGUARD|SUPRIM|MASTER\\s*ICE|MASTER|BTF)\\b", Pattern.CASE_INSENSITIVE)
            ));
            case "CPU" -> firstMismatch(normalizedPart, normalizedTitle, List.of(
                    Pattern.compile("\\b([0-9]{4}X3D|[0-9]{4}X|[0-9]{3}K)\\b", Pattern.CASE_INSENSITIVE)
            ));
            case "MOTHERBOARD" -> firstMismatch(normalizedPart, normalizedTitle, List.of(
                    Pattern.compile("\\b(B[0-9]{3}I?|B[0-9]{3}M?|X[0-9]{3}E?\\s*[AE]?|Z[0-9]{3}I?)\\b", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b(WIFI7|WIFI6E|WIFI6|WIFI)\\b", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b(PRO\\s*RS|STEEL\\s*LEGEND|TAICHI|MAX\\s*GAMING|PRIME|CROSSHAIR|DARK\\s*HERO|HERO|STRIX|TUF|AYW|AORUS\\s*ELITE|AORUS\\s*PRO|X3D|MORTAR|TOMAHAWK|CARBON|UNIFY|NOVA|ROCK)\\b", Pattern.CASE_INSENSITIVE)
            ));
            case "PSU" -> firstMismatch(normalizedPart, normalizedTitle, List.of(
                    Pattern.compile("\\b(RM[0-9]{3,4}E?|RM[0-9]{3,4}X|SF[0-9]{3,4}[A-Z0-9]*|GX-[0-9]{3,4}|A[0-9]{3,4}GLS?|A[0-9]{3,4}GN|A[0-9]{3,4}GS)\\b", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b([0-9]{3,4}W)\\b", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("(BRONZE|SILVER|GOLD|PLATINUM)", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b(CLASSIC\\s*II|MWE\\s*GOLD|V\\s*SFX|CORE\\s*V2|FOCUS\\s*V4|HX[0-9]{4}I|RM[0-9]{3,4}E|RM[0-9]{3,4}X|ELITE\\s*GOLD|ELITE\\s*PLATINUM|HYDRO\\s*G\\s*PRO|HYPER\\s*K\\s*PRO|MEGA\\s*GM|VIC\\s*GM|VITA\\s*GD|A[0-9]{3,4}GLS|A[0-9]{3,4}GN|A[0-9]{3,4}GS|A[0-9]{3,4}GL|LEADEX\\s*III|LEADEX\\s*VII|ZILLION|VERTEX\\s*GX|WATTBIT|MEGAMAX)\\b", Pattern.CASE_INSENSITIVE)
            ));
            case "RAM" -> firstMismatch(normalizedPart, normalizedTitle, List.of(
                    Pattern.compile("\\bDDR5[- ]?([0-9]{4})\\b", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b([0-9]{2,3}GB)\\b", Pattern.CASE_INSENSITIVE)
            ));
            case "STORAGE" -> firstMismatch(normalizedPart, normalizedTitle, List.of(
                    Pattern.compile("\\b([0-9]{1,2}TB)\\b", Pattern.CASE_INSENSITIVE)
            ));
            case "COOLER" -> firstMismatch(normalizedPart, normalizedTitle, List.of(
                    Pattern.compile("\\b(240|280|360)\\b", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b(ASSASSIN\\s*IV|VC\\s*ELITE|TITAN\\s*360\\s*RX\\s*LCD|TITAN\\s*360\\s*RX\\s*RGB|KRAKEN\\s*ELITE|KRAKEN\\s*PLUS|ATMOS\\s*II|ATMOS|PHANTOM\\s*SPIRIT\\s*120\\s*EVO|VISION\\s*EVO)\\b", Pattern.CASE_INSENSITIVE)
            ));
            case "CASE" -> firstMismatch(normalizedPart, normalizedTitle, List.of(
                    Pattern.compile("\\b((?:MESHIFY\\s*[0-9]+(?:\\s*XL)?)|4000D|AP201|A3-?MATX|LANCOOL\\s*217|EVOLV\\s*X2|H9\\s*FLOW|(?:NORTH(?:\\s*XL)?)|TERRA|LIGHT\\s*BASE\\s*900\\s*DX|NR200P\\s*V2)\\b", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\b(LCD|INF)\\b", Pattern.CASE_INSENSITIVE)
            ));
            default -> Optional.empty();
        };
        if (modelMismatch.isPresent()) {
            return modelMismatch;
        }
        Optional<String> colorMismatch = colorVariantMismatch(normalizedPart, normalizedTitle);
        if (colorMismatch.isPresent()) {
            return colorMismatch;
        }
        Optional<String> strictMissing = requiredVariantMismatch(normalizedCategory, normalizedPart, normalizedTitle);
        if (strictMissing.isPresent()) {
            return strictMissing;
        }

        double overlap = tokenOverlap(normalizedPart, normalizedTitle);
        if (overlap < 0.55d) {
            return Optional.of("다나와 상품명과 내부 자산명이 충분히 일치하지 않습니다.");
        }
        return Optional.empty();
    }

    private static Optional<String> requiredVariantMismatch(String category, String internalText, String externalText) {
        List<String> requiredTerms = switch (category) {
            case "GPU" -> List.of("DUAL", "PRIME", "ASTRAL", "MATRIX", "TWIN EDGE", "AMP WHITE", "AMP EXTREME", "SOLID CORE", "SOLID OC", "EAGLE", "AERO", "WINDFORCE", "VENTUS", "GAMING TRIO", "SHADOW", "VANGUARD", "SUPRIM", "MASTER ICE", "BTF");
            case "MOTHERBOARD" -> List.of("WIFI", "WIFI6", "WIFI6E", "WIFI7", "ICE", "DARK HERO", "PRO RS", "TAICHI", "MAX GAMING", "PRIME", "CROSSHAIR", "HERO", "STRIX", "TUF", "AYW", "AORUS ELITE", "AORUS PRO", "X3D", "MORTAR", "TOMAHAWK", "CARBON", "UNIFY", "NOVA", "ROCK");
            case "PSU" -> List.of("CLASSIC II", "MWE GOLD", "V SFX", "CORE V2", "FOCUS V4", "HX1200I", "HX1500I", "RM1000E", "RM750E", "RM850E", "RM1000X", "RM850X", "ELITE GOLD", "ELITE PLATINUM", "HYDRO G PRO", "HYPER K PRO", "MEGA GM", "VIC GM", "VITA GD", "A850GS", "A1000GL", "A650BN", "A750GLS", "A850GN", "A1000GS", "LEADEX III", "LEADEX VII", "ZILLION", "VERTEX GX", "WATTBIT", "MEGAMAX");
            case "COOLER" -> List.of("A RGB", "ASSASSIN IV", "TITAN 360 RX LCD", "TITAN 360 RX RGB", "DARK ROCK PRO 5", "AK400", "AK620", "KRAKEN ELITE", "NH D15 G2", "NH L9A AM5", "NH U12A", "LBC", "PHANTOM SPIRIT 120 EVO", "RYUJIN III", "PEERLESS ASSASSIN");
            case "CASE" -> List.of("LCD", "INF", "NR200P V2");
            default -> List.of();
        };
        for (String term : requiredTerms) {
            if (containsTerm(internalText, term) && !containsTerm(externalText, term)) {
                return Optional.of("다나와 상품 세부 라인업이 내부 자산과 다릅니다: " + term);
            }
        }
        List<String> titleOnlyRejectTerms = switch (category) {
            case "GPU" -> List.of("BTF");
            case "MOTHERBOARD" -> List.of("WIFI", "WIFI6", "WIFI6E", "WIFI7", "ICE", "DARK HERO");
            default -> List.of();
        };
        for (String term : titleOnlyRejectTerms) {
            if (containsTerm(externalText, term) && !containsTerm(internalText, term)) {
                return Optional.of("다나와 상품 세부 라인업이 내부 자산과 다릅니다: " + term);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> colorVariantMismatch(String internalText, String externalText) {
        for (String color : List.of("WHITE", "PINK")) {
            if (containsTerm(internalText, color) && !containsTerm(externalText, color)) {
                return Optional.of("다나와 상품 색상/variant가 내부 자산과 다릅니다: " + color);
            }
            if (containsTerm(externalText, color) && !containsTerm(internalText, color)) {
                return Optional.of("다나와 상품 색상/variant가 내부 자산과 다릅니다: " + color);
            }
        }
        if (containsTerm(internalText, "BLACK") && containsTerm(externalText, "WHITE")) {
            return Optional.of("다나와 상품 색상/variant가 내부 자산과 다릅니다: BLACK != WHITE");
        }
        return Optional.empty();
    }

    private static boolean containsTerm(String value, String term) {
        return value.contains(term);
    }

    private static Optional<String> firstMismatch(String internalText, String externalText, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            Set<String> internalTokens = capturedTokens(pattern, internalText);
            Set<String> externalTokens = capturedTokens(pattern, externalText);
            if (!internalTokens.isEmpty() && !externalTokens.isEmpty() && internalTokens.stream().noneMatch(externalTokens::contains)) {
                return Optional.of("다나와 상품 핵심 규격이 내부 자산과 다릅니다: " + internalTokens + " != " + externalTokens);
            }
        }
        return Optional.empty();
    }

    private static Set<String> capturedTokens(Pattern pattern, String value) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            StringBuilder token = new StringBuilder();
            for (int index = 1; index <= matcher.groupCount(); index++) {
                String group = matcher.group(index);
                if (StringUtils.hasText(group)) {
                    token.append(group);
                }
            }
            if (!token.isEmpty()) {
                tokens.add(token.toString().toUpperCase(Locale.ROOT).replace(" ", ""));
            }
        }
        return tokens;
    }

    private static double tokenOverlap(String internalText, String externalText) {
        Set<String> internalTokens = matchTokens(internalText);
        Set<String> externalTokens = matchTokens(externalText);
        if (internalTokens.isEmpty()) {
            return 0.0d;
        }
        long matched = internalTokens.stream().filter(externalTokens::contains).count();
        return (double) matched / (double) internalTokens.size();
    }

    private static Set<String> matchTokens(String value) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("[A-Z0-9]+|[가-힣]+").matcher(value);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() > 1 && !matchStopWords().contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static Set<String> matchStopWords() {
        return Set.of(
                "정품", "벌크", "블랙", "화이트", "게이밍", "그래픽카드", "메인보드", "파워", "파워서플라이", "컴퓨터",
                "조립용", "패키지", "대원씨티에스", "인텍앤컴퍼니", "피씨디렉트", "제이씨현", "코잇", "STCOM",
                "AMD", "INTEL", "NVIDIA", "GEFORCE", "지포스", "DDR5", "GDDR7", "ATX", "WIFI", "PLUS", "OC"
        );
    }

    private static String normalizeMatchText(String value) {
        String text = value == null ? "" : value.toUpperCase(Locale.ROOT);
        return text
                .replace("기가바이트", "GIGABYTE")
                .replace("조텍", "ZOTAC")
                .replace("커세어", "CORSAIR")
                .replace("에이수스", "ASUS")
                .replace("화이트", "WHITE")
                .replace("블랙", "BLACK")
                .replace("핑크", "PINK")
                .replace("삼성전자", "SAMSUNG")
                .replace("삼성", "SAMSUNG")
                .replace("마이크로닉스", "MICRONICS")
                .replace("마이크론", "CRUCIAL")
                .replace("크루셜", "CRUCIAL")
                .replace("시소닉", "SEASONIC")
                .replace("쿨러마스터", "COOLER MASTER")
                .replace("녹투아", "NOCTUA")
                .replace("리안리", "LIAN LI")
                .replace("프렉탈디자인", "FRACTAL DESIGN")
                .replace("애즈락", "ASROCK")
                .replace("슈퍼플라워", "SUPERFLOWER")
                .replace("벤투스", "VENTUS")
                .replace("쉐도우", "SHADOW")
                .replace("게이밍 트리오", "GAMING TRIO")
                .replace("슈프림", "SUPRIM")
                .replace("윈드포스", "WINDFORCE")
                .replace("어로스", "AORUS")
                .replace("마스터", "MASTER")
                .replace("이글", "EAGLE")
                .replace("프라임", "PRIME")
                .replace("듀얼", "DUAL")
                .replace("박격포", "MORTAR")
                .replace("토마호크", "TOMAHAWK")
                .replace("카본", "CARBON")
                .replace("엣지", "EDGE")
                .replace("스틸레전드", "STEEL LEGEND")
                .replace("유니파이", "UNIFY")
                .replace("브론즈", "BRONZE")
                .replace("실버", "SILVER")
                .replace("골드", "GOLD")
                .replace("플래티넘", "PLATINUM")
                .replace("티아이", "TI");
    }

    private static String cleanHtmlTitle(String value) {
        String title = value == null ? "" : value
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
        return title.replaceAll("\\s*:\\s*다나와.*$", "").trim();
    }

    private void sleepBetweenRequests() {
        if (rateLimitMs <= 0) {
            return;
        }
        try {
            Thread.sleep(rateLimitMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void appendLimited(List<Map<String, Object>> target, Map<String, Object> issue) {
        if (target.size() < 50) {
            target.add(issue);
        }
    }

    private static void addIfText(Set<String> values, String value) {
        if (StringUtils.hasText(value)) {
            values.add(value.trim());
        }
    }

    private static String absoluteProductUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return null;
        }
        String trimmed = url.trim().replace("&amp;", "&");
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        if (trimmed.startsWith("/")) {
            return "https://prod.danawa.com" + trimmed;
        }
        return trimmed;
    }

    private static String normalizeCategory(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 부품 category입니다.");
        }
        return upper;
    }

    private static int normalizeMonths(Integer value) {
        int months = value == null ? 6 : value;
        if (!MONTHLY_RANGES.contains(months)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "월별 추이 수집은 months=6, 12, 24만 지원합니다.");
        }
        return months;
    }

    private static String normalizeBaseUrl(String value, String fallback) {
        String normalized = StringUtils.hasText(value) ? value.trim() : fallback;
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static int numberValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private record DanawaProduct(String productCode, String productUrl, String resolvedFrom, String productTitle) {
    }

    private record TrendPoint(OffsetDateTime collectedAt, int price, String pointType, String label, String sourceUrl) {
    }

    private record TrendCollection(DanawaProduct product, List<TrendPoint> points, String reason) {
    }
}
