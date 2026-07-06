package com.buildgraph.prototype.ticket.contract;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public enum SupportDecision {
    SELF_SOLVABLE("자가 조치 가능"),
    REMOTE_POSSIBLE("원격지원 가능"),
    VISIT_REQUIRED("방문지원 필요"),
    REPAIR_OR_REPLACE("수리/교체 필요"),
    NEEDS_MORE_INFO("추가 정보 필요"),
    MONITOR_ONLY("관찰 필요"),
    UNSUPPORTED("지원 범위 밖");

    private final String uiLabelKo;

    SupportDecision(String uiLabelKo) {
        this.uiLabelKo = uiLabelKo;
    }

    public String uiLabelKo() {
        return uiLabelKo;
    }

    public static Set<String> names() {
        return Arrays.stream(values())
                .map(Enum::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Map<String, String> uiLabelsKo() {
        return Arrays.stream(values())
                .collect(Collectors.toUnmodifiableMap(Enum::name, SupportDecision::uiLabelKo));
    }
}
