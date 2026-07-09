package com.buildgraph.prototype.verification.part;

import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;

public final class PartSeed {
    public static final String CPU_ID = "00000000-0000-4000-8000-000000010001";
    public static final String MOTHERBOARD_ID = "00000000-0000-4000-8000-000000010002";
    public static final String RAM_ID = "00000000-0000-4000-8000-000000010003";
    public static final String GPU_ID = "00000000-0000-4000-8000-000000010004";
    public static final String STORAGE_ID = "00000000-0000-4000-8000-000000010005";
    public static final String PSU_ID = "00000000-0000-4000-8000-000000010006";

    private PartSeed() {
    }

    public static List<Map<String, Object>> parts() {
        return List.of(
                part(CPU_ID, "CPU", "AMD Ryzen 5 7600", "AMD", 259000, MockData.map("socket", "AM5", "wattage", 65, "metadataVersion", 1)),
                part(MOTHERBOARD_ID, "MOTHERBOARD", "B650M WiFi", "ASRock", 179000, MockData.map("socket", "AM5", "chipset", "B650", "memoryType", "DDR5", "metadataVersion", 1)),
                part(RAM_ID, "RAM", "DDR5 32GB 5600", "Samsung", 128000, MockData.map("memoryType", "DDR5", "metadataVersion", 1)),
                part(GPU_ID, "GPU", "RTX 4070 SUPER 12GB", "NVIDIA", 890000, MockData.map("wattage", 220, "lengthMm", 304, "metadataVersion", 1)),
                part(STORAGE_ID, "STORAGE", "NVMe Gen4 1TB", "SK hynix", 99000, MockData.map("metadataVersion", 1)),
                part(PSU_ID, "PSU", "750W 80+ Gold", "Seasonic", 126000, MockData.map("wattage", 750, "metadataVersion", 1))
        );
    }

    public static Map<String, Object> part(String id) {
        return parts().stream()
                .filter(part -> id.equals(part.get("id")))
                .findFirst()
                .orElse(MockData.map("id", id, "status", "NOT_FOUND"));
    }

    private static Map<String, Object> part(String id, String category, String name, String manufacturer, int price, Map<String, Object> attributes) {
        return MockData.map(
                "id", id,
                "category", category,
                "name", name,
                "manufacturer", manufacturer,
                "price", price,
                "status", "ACTIVE",
                "attributes", attributes
        );
    }
}
