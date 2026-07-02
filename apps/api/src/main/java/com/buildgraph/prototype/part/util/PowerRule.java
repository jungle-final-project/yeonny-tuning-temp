package com.buildgraph.prototype.part.util;

import static com.buildgraph.prototype.part.util.RuleValueReader.intAttr;

import java.util.List;

import com.buildgraph.prototype.part.ToolBuildPart;

/* 전력 산출하는 util 함수들 */
public class PowerRule {

    private PowerRule() {
    }

    /* 예상전력 산출하기 */
    public static int estimatedWattage(List<ToolBuildPart> parts) {
        return parts.stream()
                .mapToInt(PowerRule::estimatedPartPowerDraw)
                .sum() + 60;
    }

    /** Estimates per-part draw using spec attributes. */
    private static int estimatedPartPowerDraw(ToolBuildPart part) {
        if (part == null) {
            return 0;
        }
        return switch (part.category()) {
            case "CPU" -> Math.max(intAttr(part, "wattage", 0), intAttr(part, "tdpW", 65));
            case "GPU" -> intAttr(part, "wattage", 0);
            case "MOTHERBOARD" -> intAttr(part, "wattage", 50);
            case "RAM" -> intAttr(part, "wattage", 10);
            case "STORAGE" -> intAttr(part, "wattage", 8);
            case "COOLER" -> firstPositive(intAttr(part, "electricalW", 0), intAttr(part, "pumpW", 0), intAttr(part, "fanW", 0), 8);
            case "CASE" -> firstPositive(intAttr(part, "fanW", 0), intAttr(part, "wattage", 0), 10);
            case "PSU" -> 0;
            default -> intAttr(part, "wattage", 0);
        };
    }

    /** Returns the first positive numeric candidate. */
    private static int firstPositive(int... values) {
        for (int value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }
}
