package com.buildgraph.prototype.part.query;

import java.util.LinkedHashMap;
import java.util.Map;

import com.buildgraph.prototype.common.DbValueMapper;

public final class PartQueryUtil {

    private PartQueryUtil() {
    }
    
    public static Map<String, Object> benchmark(
            Map<String, Object> row
    ) {
        String summary = DbValueMapper.string(
                row,
                "benchmark_summary"
        );

        if (summary == null) {
            return null;
        }

        Map<String, Object> benchmark = new LinkedHashMap<>();
        benchmark.put("summary", summary);
        benchmark.put("score", row.get("benchmark_score"));

        return benchmark;
    }

    public static Map<String, Object> latestPrice(
            Map<String, Object> row
    ) {
        String source = DbValueMapper.string(
                row,
                "latest_price_source"
        );

        Object collectedAt = DbValueMapper.timestamp(
                row,
                "latest_price_collected_at"
        );

        if (source == null && collectedAt == null) {
            return null;
        }

        Map<String, Object> latestPrice = new LinkedHashMap<>();
        latestPrice.put("currentPrice", DbValueMapper.integer(row, "price"));
        latestPrice.put(
                "snapshotPrice",
                DbValueMapper.integer(row, "price_snapshot_price")
        );
        latestPrice.put("source", source);
        latestPrice.put("collectedAt", collectedAt);

        return latestPrice;
    }

    public static Map<String, Object> externalOffer(
            Map<String, Object> row
    ) {
        String source = DbValueMapper.string(
                row,
                "external_offer_source"
        );

        if (source == null) {
            return null;
        }

        Map<String, Object> externalOffer = new LinkedHashMap<>();
        externalOffer.put(
                "title",
                DbValueMapper.string(row, "external_offer_title")
        );
        externalOffer.put(
                "imageUrl",
                DbValueMapper.string(row, "external_offer_image_url")
        );
        externalOffer.put(
                "supplierName",
                DbValueMapper.string(row, "external_offer_supplier_name")
        );
        externalOffer.put(
                "offerUrl",
                DbValueMapper.string(row, "external_offer_url")
        );
        externalOffer.put(
                "lowPrice",
                DbValueMapper.integer(row, "external_offer_low_price")
        );
        externalOffer.put("source", source);
        externalOffer.put(
                "refreshedAt",
                DbValueMapper.timestamp(
                        row,
                        "external_offer_refreshed_at"
                )
        );

        return externalOffer;
    }
}
