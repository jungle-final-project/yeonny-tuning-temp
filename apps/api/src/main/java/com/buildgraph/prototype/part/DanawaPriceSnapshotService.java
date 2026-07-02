package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

@Service
public class DanawaPriceSnapshotService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> CATEGORIES = Set.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER");
    private static final String SOURCE = "DANAWA_BACKUP";
    private static final String SELECTOR_VERSION = "danawa-search-html-v1";
    private static final Pattern TITLE_LINK_PATTERN = Pattern.compile(
            "<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.{3,220}?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern STRONG_PRICE_PATTERN = Pattern.compile(
            "<strong[^>]*>\\s*([0-9][0-9,]{2,})\\s*</strong>\\s*원",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern PRICE_PATTERN = Pattern.compile("([0-9][0-9,]{2,})\\s*원");

    private final JdbcTemplate jdbcTemplate;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final long rateLimitMs;

    public DanawaPriceSnapshotService(
            JdbcTemplate jdbcTemplate,
            @Value("${part.danawa-refresh.base-url:https://search.danawa.com}") String baseUrl,
            @Value("${part.danawa-refresh.rate-limit-ms:3000}") Long rateLimitMs
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.rateLimitMs = rateLimitMs == null ? 3000L : Math.max(0L, rateLimitMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Map<String, Object> refreshSnapshots(String category, Integer limit, Boolean force) {
        String normalizedCategory = normalizeCategory(category);
        int safeLimit = limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
        boolean forceRefresh = Boolean.TRUE.equals(force);
        List<Map<String, Object>> parts = targetParts(normalizedCategory, safeLimit);
        int attempted = 0;
        int collected = 0;
        int skipped = 0;
        int failed = 0;

        for (Map<String, Object> part : parts) {
            if (!forceRefresh && recentlyCollected(part)) {
                skipped += 1;
                continue;
            }
            attempted += 1;
            try {
                String keyword = searchKeyword(part);
                Optional<DanawaOffer> offer = fetchOffer(keyword);
                if (offer.isEmpty()) {
                    skipped += 1;
                    sleepBetweenRequests();
                    continue;
                }
                storeOffer(((Number) part.get("id")).longValue(), keyword, offer.get());
                collected += 1;
            } catch (RuntimeException exception) {
                failed += 1;
            }
            sleepBetweenRequests();
        }

        return MockData.map(
                "configured", true,
                "category", normalizedCategory,
                "limit", safeLimit,
                "force", forceRefresh,
                "attempted", attempted,
                "collected", collected,
                "skipped", skipped,
                "failed", failed
        );
    }

    public Map<String, Object> refreshDailySnapshots() {
        int attempted = 0;
        int collected = 0;
        int skipped = 0;
        int failed = 0;
        List<Map<String, Object>> categoryResults = new ArrayList<>();
        for (String category : CATEGORIES.stream().sorted().toList()) {
            Map<String, Object> result = refreshSnapshots(category, 50, false);
            categoryResults.add(result);
            attempted += numberValue(result.get("attempted"));
            collected += numberValue(result.get("collected"));
            skipped += numberValue(result.get("skipped"));
            failed += numberValue(result.get("failed"));
        }
        return MockData.map(
                "configured", true,
                "categories", categoryResults,
                "attempted", attempted,
                "collected", collected,
                "skipped", skipped,
                "failed", failed
        );
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
                       dps.last_collected_at
                FROM parts p
                LEFT JOIN LATERAL (
                  SELECT max(ps.collected_at) AS last_collected_at
                  FROM price_snapshots ps
                  WHERE ps.part_id = p.id
                    AND ps.source = 'DANAWA_BACKUP'
                ) dps ON true
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

    private Optional<DanawaOffer> fetchOffer(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return Optional.empty();
        }
        String html;
        String searchUrl = baseUrl + "/dsearch.php?k1=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&module=goods&act=dispMain";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(searchUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "BuildGraphPrototype/1.0 (educational price trend refresh)")
                    .header("Accept", "text/html")
                    .header("Accept-Language", "ko-KR,ko;q=0.9")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }
            html = response.body();
        } catch (RuntimeException exception) {
            return Optional.empty();
        } catch (Exception exception) {
            return Optional.empty();
        }
        if (!StringUtils.hasText(html)) {
            return Optional.empty();
        }
        String candidateUrl = null;
        String candidateTitle = null;
        Matcher linkMatcher = TITLE_LINK_PATTERN.matcher(html);
        while (linkMatcher.find()) {
            String title = cleanText(linkMatcher.group(2));
            String href = absoluteUrl(linkMatcher.group(1));
            if (!StringUtils.hasText(title) || !looksLikeProductTitle(title, keyword)) {
                continue;
            }
            candidateTitle = title;
            candidateUrl = href;
            break;
        }

        Integer lowPrice = firstPrice(html);
        if (lowPrice == null) {
            return Optional.empty();
        }
        return Optional.of(new DanawaOffer(
                StringUtils.hasText(candidateTitle) ? candidateTitle : keyword,
                candidateUrl,
                lowPrice,
                searchUrl
        ));
    }

    private void storeOffer(long partId, String keyword, DanawaOffer offer) {
        String rawPayload = json(MockData.map(
                "sourceName", "Danawa",
                "source", SOURCE,
                "searchQuery", keyword,
                "searchUrl", offer.searchUrl(),
                "title", offer.title(),
                "offerUrl", offer.offerUrl(),
                "lowPrice", offer.lowPrice(),
                "selectorVersion", SELECTOR_VERSION,
                "capturedAt", OffsetDateTime.now().toString()
        ));
        jdbcTemplate.update("""
                INSERT INTO part_external_offers (
                  part_id,
                  source,
                  search_query,
                  title,
                  image_url,
                  supplier_name,
                  offer_url,
                  low_price,
                  raw_payload,
                  refreshed_at,
                  created_at,
                  updated_at
                )
                VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?::jsonb, now(), now(), now())
                ON CONFLICT (part_id, source) WHERE deleted_at IS NULL
                DO UPDATE SET
                  search_query = EXCLUDED.search_query,
                  title = EXCLUDED.title,
                  supplier_name = EXCLUDED.supplier_name,
                  offer_url = EXCLUDED.offer_url,
                  low_price = EXCLUDED.low_price,
                  raw_payload = EXCLUDED.raw_payload,
                  refreshed_at = now(),
                  updated_at = now()
                """,
                partId,
                SOURCE,
                limited(keyword, 255),
                limited(offer.title(), 500),
                "Danawa",
                offer.offerUrl(),
                offer.lowPrice(),
                rawPayload
        );
        jdbcTemplate.update("""
                INSERT INTO price_snapshots (
                  part_id,
                  price,
                  source,
                  collected_at,
                  raw_payload
                )
                VALUES (?, ?, ?, now(), ?::jsonb)
                """,
                partId,
                offer.lowPrice(),
                SOURCE,
                rawPayload
        );
    }

    private static boolean recentlyCollected(Map<String, Object> part) {
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
        return collectedAt != null && collectedAt.isAfter(OffsetDateTime.now().minus(Duration.ofDays(1)));
    }

    private static Integer firstPrice(String html) {
        Matcher strongMatcher = STRONG_PRICE_PATTERN.matcher(html);
        if (strongMatcher.find()) {
            return integerValue(strongMatcher.group(1).replace(",", ""));
        }
        Matcher priceMatcher = PRICE_PATTERN.matcher(cleanText(html));
        if (priceMatcher.find()) {
            return integerValue(priceMatcher.group(1).replace(",", ""));
        }
        return null;
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
        String manufacturer = stringValue(part.get("manufacturer"));
        String name = stringValue(part.get("name"));
        if (StringUtils.hasText(manufacturer) && !manufacturer.endsWith("Partner")) {
            return manufacturer + " " + name;
        }
        return name;
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

    private static boolean looksLikeProductTitle(String title, String keyword) {
        String normalizedTitle = normalizeForMatch(title);
        String normalizedKeyword = normalizeForMatch(keyword);
        if (!StringUtils.hasText(normalizedTitle) || !StringUtils.hasText(normalizedKeyword)) {
            return false;
        }
        String[] tokens = normalizedKeyword.split(" ");
        int matched = 0;
        int important = 0;
        for (String token : tokens) {
            if (token.length() < 2) {
                continue;
            }
            important += 1;
            if (normalizedTitle.contains(token)) {
                matched += 1;
            }
        }
        return important == 0 || matched >= Math.max(1, important / 2);
    }

    private static String absoluteUrl(String href) {
        if (!StringUtils.hasText(href)) {
            return null;
        }
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        if (href.startsWith("//")) {
            return "https:" + href;
        }
        if (href.startsWith("/")) {
            return "https://www.danawa.com" + href;
        }
        return "https://www.danawa.com/" + href;
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

    private static String normalizeBaseUrl(String value) {
        String normalized = StringUtils.hasText(value) ? value.trim() : "https://search.danawa.com";
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static String normalizeForMatch(String value) {
        String cleaned = cleanText(value);
        if (!StringUtils.hasText(cleaned)) {
            return "";
        }
        return cleaned
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^0-9A-Z가-힣]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String cleanText(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll("<[^>]+>", "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#40;", "(")
                .replace("&#41;", ")")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static int numberValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String limited(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String json(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private record DanawaOffer(String title, String offerUrl, int lowPrice, String searchUrl) {
    }
}
