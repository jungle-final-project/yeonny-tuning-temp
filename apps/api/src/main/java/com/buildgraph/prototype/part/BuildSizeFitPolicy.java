package com.buildgraph.prototype.part;

/** Shared dimensional headroom policy for Tool results and build-graph edges. */
public final class BuildSizeFitPolicy {
    public static final int GPU_WARN_HEADROOM_MM = 20;
    public static final int COOLER_WARN_HEADROOM_MM = 5;
    public static final int PSU_WARN_HEADROOM_MM = 10;

    private BuildSizeFitPolicy() {
    }

    public static boolean hasLowHeadroom(int headroomMm, int warnBelowMm) {
        return headroomMm >= 0 && headroomMm < warnBelowMm;
    }

    public static String graphStatus(Integer currentMm, Integer maximumMm, int warnBelowMm, String unknownStatus) {
        if (currentMm == null || maximumMm == null) {
            return unknownStatus;
        }
        int headroomMm = maximumMm - currentMm;
        if (headroomMm < 0) {
            return "FAIL";
        }
        return hasLowHeadroom(headroomMm, warnBelowMm) ? "WARN" : "PASS";
    }
}
