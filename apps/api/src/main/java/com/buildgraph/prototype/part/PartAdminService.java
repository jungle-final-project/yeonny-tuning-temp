package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PartAdminService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> CATEGORIES = Set.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE", "DISCONTINUED");

    private final JdbcTemplate jdbcTemplate;

    public PartAdminService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> listParts(
            String category,
            String query,
            String manufacturer,
            String status,
            Integer minPrice,
            Integer maxPrice,
            Boolean includeDeleted,
            Integer page,
            Integer size,
            String sort
    ) {
        AdminPartSearch search = new AdminPartSearch(category, query, manufacturer, status, minPrice, maxPrice, includeDeleted, page, size, sort);
        SqlWhere where = adminWhere(search);
        List<Object> params = new ArrayList<>(where.params());
        params.add(search.size());
        params.add(search.offset());
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                        SELECT p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.status,
                               p.attributes,
                               p.created_at,
                               p.updated_at,
                               p.deleted_at,
                               peo.title AS external_offer_title,
                               peo.image_url AS external_offer_image_url,
                               peo.supplier_name AS external_offer_supplier_name,
                               peo.offer_url AS external_offer_url,
                               peo.low_price AS external_offer_low_price,
                               peo.source AS external_offer_source,
                               peo.refreshed_at AS external_offer_refreshed_at
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT offer.*
                          FROM part_external_offers offer
                          WHERE offer.part_id = p.id
                            AND offer.deleted_at IS NULL
                          ORDER BY
                            CASE offer.source
                              WHEN 'NAVER_SHOPPING_SEARCH' THEN 1
                              WHEN 'ADMIN_MANUAL' THEN 2
                              ELSE 9
                            END,
                            offer.refreshed_at DESC,
                            offer.id DESC
                          LIMIT 1
                        ) peo ON true
                        """ + where.sql() + " ORDER BY " + orderBy(search.sort()) + " LIMIT ? OFFSET ?",
                        params.toArray())
                .stream()
                .map(this::partMap)
                .toList();
        Integer total = jdbcTemplate.queryForObject("SELECT count(*) FROM parts p " + where.sql(), Integer.class, where.params().toArray());
        return MockData.map("items", items, "page", search.page(), "size", search.size(), "total", total);
    }

    public Map<String, Object> detail(String publicId) {
        Map<String, Object> part = partByPublicId(publicId, true);
        return partMap(part);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        String category = category(text(request, "category", null));
        String name = requiredText(request, "name");
        String manufacturer = blankToNull(text(request, "manufacturer", null));
        int price = nonNegativeInt(request.get("price"), 0, "price는 0 이상이어야 합니다.");
        String status = "INACTIVE";
        Map<String, Object> attributes = adminAttributes(category, mapValue(request.get("attributes")));
        enforceActiveStatus(status, category, attributes);

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO parts (category, name, manufacturer, price, status, attributes, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, now(), now())
                RETURNING public_id::text AS id
                """,
                category,
                limited(name, 255),
                limited(manufacturer, 100),
                price,
                status,
                json(attributes)
        );
        audit(admin, "PART_CREATED", "parts", DbValueMapper.string(row, "id"), MockData.map(
                "category", category,
                "status", status,
                "price", price
        ));
        return detail(DbValueMapper.string(row, "id"));
    }

    @Transactional
    public Map<String, Object> update(String publicId, Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        Map<String, Object> existing = partByPublicId(publicId, true);
        String oldStatus = DbValueMapper.string(existing, "status");
        String category = request.containsKey("category")
                ? category(text(request, "category", null))
                : DbValueMapper.string(existing, "category");
        String name = request.containsKey("name")
                ? requiredText(request, "name")
                : DbValueMapper.string(existing, "name");
        String manufacturer = request.containsKey("manufacturer")
                ? blankToNull(text(request, "manufacturer", null))
                : DbValueMapper.string(existing, "manufacturer");
        int price = request.containsKey("price")
                ? nonNegativeInt(request.get("price"), 0, "price는 0 이상이어야 합니다.")
                : DbValueMapper.integer(existing, "price");
        String nextStatus = request.containsKey("status")
                ? status(text(request, "status", null))
                : oldStatus;
        Map<String, Object> mergedAttributes = new LinkedHashMap<>(jsonMap(existing.get("attributes")));
        if (request.containsKey("attributes")) {
            mergedAttributes.putAll(mapValue(request.get("attributes")));
        }
        mergedAttributes = adminAttributes(category, mergedAttributes);
        enforceActiveStatus(nextStatus, category, mergedAttributes);

        jdbcTemplate.update("""
                UPDATE parts
                SET category = ?,
                    name = ?,
                    manufacturer = ?,
                    price = ?,
                    status = ?,
                    attributes = ?::jsonb,
                    updated_at = now()
                WHERE public_id = ?::uuid
                """,
                category,
                limited(name, 255),
                limited(manufacturer, 100),
                price,
                nextStatus,
                json(mergedAttributes),
                publicId
        );
        audit(admin, "PART_UPDATED", "parts", publicId, MockData.map(
                "category", category,
                "price", price,
                "status", nextStatus
        ));
        if (!oldStatus.equals(nextStatus)) {
            audit(admin, "PART_STATUS_CHANGED", "parts", publicId, MockData.map("before", oldStatus, "after", nextStatus));
        }
        return detail(publicId);
    }

    @Transactional
    public Map<String, Object> manualPrice(String publicId, Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        Map<String, Object> existing = partByPublicId(publicId, false);
        int price = nonNegativeInt(request.get("price"), -1, "price는 0 이상이어야 합니다.");
        if (price < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "price 값이 필요합니다.");
        }
        String reason = blankToNull(text(request, "reason", null));
        Long internalId = ((Number) existing.get("internal_id")).longValue();
        Integer before = DbValueMapper.integer(existing, "price");
        String payload = json(MockData.map(
                "source", "ADMIN_MANUAL",
                "reason", reason,
                "adminUserId", admin == null ? null : admin.id(),
                "beforePrice", before,
                "afterPrice", price
        ));
        jdbcTemplate.update("""
                UPDATE parts
                SET price = ?,
                    updated_at = now()
                WHERE id = ?
                """, price, internalId);
        jdbcTemplate.update("""
                INSERT INTO price_snapshots (part_id, price, source, collected_at, raw_payload)
                VALUES (?, ?, 'ADMIN_MANUAL', now(), ?::jsonb)
                """, internalId, price, payload);
        audit(admin, "PART_PRICE_MANUALLY_UPDATED", "parts", publicId, MockData.map(
                "beforePrice", before,
                "afterPrice", price,
                "reason", reason
        ));
        return detail(publicId);
    }

    @Transactional
    public Map<String, Object> updateExternalOffer(String publicId, Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        Map<String, Object> existing = partByPublicId(publicId, false);
        Long internalId = ((Number) existing.get("internal_id")).longValue();
        String searchQuery = blankToNull(text(request, "searchQuery", null));
        if (searchQuery == null) {
            searchQuery = DbValueMapper.string(existing, "name");
        }
        Integer lowPrice = nullableNonNegativeInt(request.get("lowPrice"), "lowPrice는 0 이상이어야 합니다.");
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
                VALUES (?, 'ADMIN_MANUAL', ?, ?, ?, ?, ?, ?, ?::jsonb, now(), now(), now())
                ON CONFLICT (part_id, source) WHERE deleted_at IS NULL
                DO UPDATE SET
                  search_query = EXCLUDED.search_query,
                  title = EXCLUDED.title,
                  image_url = EXCLUDED.image_url,
                  supplier_name = EXCLUDED.supplier_name,
                  offer_url = EXCLUDED.offer_url,
                  low_price = EXCLUDED.low_price,
                  raw_payload = EXCLUDED.raw_payload,
                  refreshed_at = now(),
                  updated_at = now()
                """,
                internalId,
                limited(searchQuery, 255),
                limited(text(request, "title", null), 500),
                text(request, "imageUrl", null),
                limited(text(request, "supplierName", null), 255),
                text(request, "offerUrl", null),
                lowPrice,
                json(MockData.map("source", "ADMIN_MANUAL", "adminUserId", admin == null ? null : admin.id()))
        );
        audit(admin, "PART_EXTERNAL_OFFER_UPDATED", "parts", publicId, MockData.map("lowPrice", lowPrice, "searchQuery", searchQuery));
        return detail(publicId);
    }

    @Transactional
    public Map<String, Object> softDelete(String publicId, CurrentUserService.CurrentUser admin) {
        partByPublicId(publicId, false);
        jdbcTemplate.update("""
                UPDATE parts
                SET deleted_at = now(),
                    updated_at = now()
                WHERE public_id = ?::uuid
                  AND deleted_at IS NULL
                """, publicId);
        audit(admin, "PART_SOFT_DELETED", "parts", publicId, Map.of());
        return MockData.map("id", publicId, "deleted", true);
    }

    @Transactional
    public Map<String, Object> restore(String publicId, CurrentUserService.CurrentUser admin) {
        partByPublicId(publicId, true);
        jdbcTemplate.update("""
                UPDATE parts
                SET deleted_at = NULL,
                    status = 'INACTIVE',
                    updated_at = now()
                WHERE public_id = ?::uuid
                """, publicId);
        audit(admin, "PART_RESTORED", "parts", publicId, MockData.map("status", "INACTIVE"));
        return detail(publicId);
    }

    private void enforceActiveStatus(String status, String category, Map<String, Object> attributes) {
        List<String> missing = missingRequiredFields(category, attributes);
        attributes.put("toolReady", missing.isEmpty());
        if ("ACTIVE".equals(status) && !missing.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "ACTIVE 게시에 필요한 스펙이 누락되었습니다: " + String.join(", ", missing)
            );
        }
    }

    private List<String> missingRequiredFields(String category, Map<String, Object> attributes) {
        List<String> fields = switch (category) {
            case "CPU" -> List.of("socket", "coreCount", "threadCount", "tdpW");
            case "GPU" -> List.of("vramGb", "lengthMm", "wattage", "requiredSystemPowerW", "powerConnector");
            case "MOTHERBOARD" -> List.of("socket", "chipset", "memoryType", "formFactor");
            case "RAM" -> List.of("memoryType", "capacityGb", "moduleCount", "speedMhz", "formFactor");
            case "STORAGE" -> List.of("capacityGb", "interface", "generation", "formFactor");
            case "PSU" -> List.of("capacityW", "efficiency", "atxSpec", "gpuConnector");
            case "CASE" -> List.of("formFactor", "maxGpuLengthMm", "maxCpuCoolerHeightMm", "maxPsuLengthMm");
            case "COOLER" -> List.of("coolerType", "socketSupport", "tdpW");
            default -> List.of();
        };
        return fields.stream().filter(field -> !hasValue(attributes.get(field))).toList();
    }

    private Map<String, Object> adminAttributes(String category, Map<String, Object> attributes) {
        Map<String, Object> next = new LinkedHashMap<>(attributes);
        next.remove("toolReady");
        next.put("metadataVersion", intValue(next.get("metadataVersion"), 1));
        next.put("specSource", textValue(next.get("specSource"), "ADMIN_MANUAL"));
        next.put("specConfidence", textValue(next.get("specConfidence"), "ADMIN_REVIEWED"));
        next.put("adminManaged", true);
        next.put("categoryForm", category);
        return next;
    }

    private Map<String, Object> partByPublicId(String publicId, boolean includeDeleted) {
        String deletedClause = includeDeleted ? "" : " AND p.deleted_at IS NULL\n";
        return jdbcTemplate.queryForList("""
                        SELECT p.id AS internal_id,
                               p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.status,
                               p.attributes,
                               p.created_at,
                               p.updated_at,
                               p.deleted_at,
                               peo.title AS external_offer_title,
                               peo.image_url AS external_offer_image_url,
                               peo.supplier_name AS external_offer_supplier_name,
                               peo.offer_url AS external_offer_url,
                               peo.low_price AS external_offer_low_price,
                               peo.source AS external_offer_source,
                               peo.refreshed_at AS external_offer_refreshed_at
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT offer.*
                          FROM part_external_offers offer
                          WHERE offer.part_id = p.id
                            AND offer.deleted_at IS NULL
                          ORDER BY
                            CASE offer.source
                              WHEN 'NAVER_SHOPPING_SEARCH' THEN 1
                              WHEN 'ADMIN_MANUAL' THEN 2
                              ELSE 9
                            END,
                            offer.refreshed_at DESC,
                            offer.id DESC
                          LIMIT 1
                        ) peo ON true
                        WHERE p.public_id = ?::uuid
                        """ + deletedClause + """
                        LIMIT 1
                        """, publicId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부품을 찾을 수 없습니다."));
    }

    private Map<String, Object> partMap(Map<String, Object> row) {
        Map<String, Object> attributes = jsonMap(row.get("attributes"));
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "category", DbValueMapper.string(row, "category"),
                "name", DbValueMapper.string(row, "name"),
                "manufacturer", DbValueMapper.string(row, "manufacturer"),
                "price", DbValueMapper.integer(row, "price"),
                "status", DbValueMapper.string(row, "status"),
                "attributes", attributes,
                "toolReady", Boolean.TRUE.equals(attributes.get("toolReady")),
                "missingRequiredFields", missingRequiredFields(DbValueMapper.string(row, "category"), attributes),
                "createdAt", DbValueMapper.timestamp(row, "created_at"),
                "updatedAt", DbValueMapper.timestamp(row, "updated_at"),
                "deletedAt", DbValueMapper.timestamp(row, "deleted_at"),
                "externalOffer", externalOffer(row)
        );
    }

    private Map<String, Object> externalOffer(Map<String, Object> row) {
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

    private SqlWhere adminWhere(AdminPartSearch search) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if ("DELETED".equals(search.status())) {
            clauses.add("p.deleted_at IS NOT NULL");
        } else if (!search.includeDeleted()) {
            clauses.add("p.deleted_at IS NULL");
        }
        if (search.status() != null && !"DELETED".equals(search.status())) {
            clauses.add("p.status = ?");
            params.add(search.status());
        }
        if (search.category() != null) {
            clauses.add("p.category = ?");
            params.add(search.category());
        }
        if (search.manufacturer() != null) {
            clauses.add("lower(coalesce(p.manufacturer, '')) LIKE lower(concat('%', ?, '%'))");
            params.add(search.manufacturer());
        }
        if (search.query() != null) {
            clauses.add("""
                    (
                      lower(p.name) LIKE lower(concat('%', ?, '%'))
                      OR lower(coalesce(p.manufacturer, '')) LIKE lower(concat('%', ?, '%'))
                      OR lower(coalesce(p.attributes::text, '')) LIKE lower(concat('%', ?, '%'))
                    )
                    """);
            params.add(search.query());
            params.add(search.query());
            params.add(search.query());
        }
        if (search.minPrice() != null) {
            clauses.add("p.price >= ?");
            params.add(search.minPrice());
        }
        if (search.maxPrice() != null) {
            clauses.add("p.price <= ?");
            params.add(search.maxPrice());
        }
        return new SqlWhere(clauses.isEmpty() ? "" : "WHERE " + String.join(" AND ", clauses), params);
    }

    private void audit(CurrentUserService.CurrentUser admin, String action, String targetType, String targetId, Object metadata) {
        if (admin == null || admin.internalId() == null) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO admin_audit_logs (actor_user_id, action, target_type, target_id, metadata, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, now())
                """,
                admin.internalId(),
                action,
                targetType,
                targetId,
                json(metadata == null ? Map.of() : metadata)
        );
    }

    private static String orderBy(String sort) {
        return switch (sort) {
            case "price_asc" -> "p.price ASC, p.id ASC";
            case "price_desc" -> "p.price DESC, p.id ASC";
            case "name" -> "p.name ASC, p.id ASC";
            case "updated_desc" -> "p.updated_at DESC NULLS LAST, p.id DESC";
            default -> "p.category ASC, p.id ASC";
        };
    }

    private static String category(String value) {
        String normalized = normalize(value);
        if (normalized == null || !CATEGORIES.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "지원하지 않는 부품 category입니다.");
        }
        return normalized;
    }

    private static String status(String value) {
        String normalized = normalize(value);
        if (normalized == null || !STATUSES.contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "지원하지 않는 부품 status입니다.");
        }
        return normalized;
    }

    private static String optionalStatus(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        if ("DELETED".equals(normalized) || STATUSES.contains(normalized)) {
            return normalized;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "지원하지 않는 부품 status입니다.");
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private static String requiredText(Map<String, Object> request, String key) {
        String value = text(request, key, null);
        if (!StringUtils.hasText(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", key + " 값이 필요합니다.");
        }
        return value.trim();
    }

    private static String text(Map<String, Object> request, String key, String fallback) {
        if (request == null || !request.containsKey(key) || request.get(key) == null) {
            return fallback;
        }
        return String.valueOf(request.get(key));
    }

    private static String textValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int nonNegativeInt(Object value, int fallback, String message) {
        int parsed = intValue(value, fallback);
        if (parsed < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
        }
        return parsed;
    }

    private static Integer nullableNonNegativeInt(Object value, String message) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return nonNegativeInt(value, 0, message);
    }

    private static int intValue(Object value, int fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "숫자 값이 올바르지 않습니다.");
        }
    }

    private static boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String string) {
            return StringUtils.hasText(string);
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private static Map<String, Object> jsonMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        if (value == null) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(OBJECT_MAPPER.readValue(value.toString(), MAP_TYPE));
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
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

    private record SqlWhere(String sql, List<Object> params) {
    }

    private record AdminPartSearch(
            String category,
            String query,
            String manufacturer,
            String status,
            Integer minPrice,
            Integer maxPrice,
            boolean includeDeleted,
            int page,
            int size,
            String sort
    ) {
        AdminPartSearch(
                String category,
                String query,
                String manufacturer,
                String status,
                Integer minPrice,
                Integer maxPrice,
                Boolean includeDeleted,
                Integer page,
                Integer size,
                String sort
        ) {
            this(
                    category == null || category.isBlank() ? null : PartAdminService.category(category),
                    blankToNull(query),
                    blankToNull(manufacturer),
                    optionalStatus(status),
                    minPrice == null ? null : nonNegativeInt(minPrice, 0, "minPrice는 0 이상이어야 합니다."),
                    maxPrice == null ? null : nonNegativeInt(maxPrice, 0, "maxPrice는 0 이상이어야 합니다."),
                    Boolean.TRUE.equals(includeDeleted),
                    page == null ? 0 : Math.max(page, 0),
                    size == null ? 20 : Math.min(Math.max(size, 1), 100),
                    sort == null || sort.isBlank() ? "category" : sort
            );
            if (this.minPrice != null && this.maxPrice != null && this.minPrice > this.maxPrice) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "minPrice는 maxPrice보다 클 수 없습니다.");
            }
        }

        int offset() {
            return page * size;
        }
    }
}
