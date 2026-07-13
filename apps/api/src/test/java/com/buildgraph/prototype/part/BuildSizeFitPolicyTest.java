package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildSizeFitPolicyTest {
    @Test
    void appliesCategorySpecificHeadroomBoundaries() {
        assertThat(BuildSizeFitPolicy.graphStatus(301, 320, BuildSizeFitPolicy.GPU_WARN_HEADROOM_MM, "WARN"))
                .isEqualTo("WARN");
        assertThat(BuildSizeFitPolicy.graphStatus(300, 320, BuildSizeFitPolicy.GPU_WARN_HEADROOM_MM, "WARN"))
                .isEqualTo("PASS");

        assertThat(BuildSizeFitPolicy.graphStatus(161, 165, BuildSizeFitPolicy.COOLER_WARN_HEADROOM_MM, "WARN"))
                .isEqualTo("WARN");
        assertThat(BuildSizeFitPolicy.graphStatus(160, 165, BuildSizeFitPolicy.COOLER_WARN_HEADROOM_MM, "WARN"))
                .isEqualTo("PASS");

        assertThat(BuildSizeFitPolicy.graphStatus(221, 230, BuildSizeFitPolicy.PSU_WARN_HEADROOM_MM, "WARN"))
                .isEqualTo("WARN");
        assertThat(BuildSizeFitPolicy.graphStatus(220, 230, BuildSizeFitPolicy.PSU_WARN_HEADROOM_MM, "WARN"))
                .isEqualTo("PASS");
    }

    @Test
    void preservesFailureAndUnknownStates() {
        assertThat(BuildSizeFitPolicy.graphStatus(166, 165, BuildSizeFitPolicy.COOLER_WARN_HEADROOM_MM, "WARN"))
                .isEqualTo("FAIL");
        assertThat(BuildSizeFitPolicy.graphStatus(null, 165, BuildSizeFitPolicy.COOLER_WARN_HEADROOM_MM, "WARN"))
                .isEqualTo("WARN");
    }
}
