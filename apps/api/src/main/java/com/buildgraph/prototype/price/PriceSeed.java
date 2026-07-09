package com.buildgraph.prototype.price;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.parts.part.PartSeed;

import java.util.List;
import java.util.Map;

public final class PriceSeed {
    private static final String PRICE_JOB_ID = "00000000-0000-4000-8000-000000008001";
    private static final String ADMIN_ID = "00000000-0000-4000-8000-000000000001";

    private PriceSeed() {
    }

    public static Map<String, Object> alerts() {
        return MockData.map(
                "items", List.of(alert()),
                "page", 0,
                "size", 20,
                "total", 1
        );
    }

    public static Map<String, Object> createAlert() {
        return alert();
    }

    public static Map<String, Object> priceJobs() {
        return MockData.map(
                "items", List.of(priceJob("SUCCEEDED")),
                "page", 0,
                "size", 20,
                "total", 1
        );
    }

    public static Map<String, Object> runPriceJob() {
        return priceJob("QUEUED");
    }

    private static Map<String, Object> alert() {
        return MockData.map(
                "partId", PartSeed.GPU_ID,
                "partName", "RTX 4070 SUPER 12GB",
                "targetPrice", 850000,
                "currentPrice", 890000,
                "status", "ACTIVE",
                "createdAt", MockData.now()
        );
    }

    private static Map<String, Object> priceJob(String status) {
        return MockData.map(
                "id", PRICE_JOB_ID,
                "status", status,
                "requestedBy", ADMIN_ID,
                "startedAt", MockData.now(),
                "finishedAt", "SUCCEEDED".equals(status) ? MockData.now() : null,
                "errorSummary", null,
                "createdAt", MockData.now()
        );
    }
}
