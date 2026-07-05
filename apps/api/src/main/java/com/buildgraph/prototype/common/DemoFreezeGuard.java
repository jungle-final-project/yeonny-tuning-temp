package com.buildgraph.prototype.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 데모 시연 중 데이터 변동을 한 번에 막는 단일 동결 스위치.
 *
 * 가격 수집 스케줄러(네이버/다나와)·제조사 릴리스 스캔·관리자 가격 Job이 각각 별도 플래그라
 * 데모 전 일괄 동결이 번거롭고 누락되기 쉬웠다(감사 O6 — 리허설에서 캡처한 견적 합계가
 * 본 데모에서 달라지는 사고). DEMO_FREEZE_MUTATIONS=true 하나로 mutating 수집 경로를 모두 멈춘다.
 * 읽기 경로(추천/견적/그래프)는 영향받지 않는다.
 */
@Component
public class DemoFreezeGuard {
    private final boolean frozen;

    public DemoFreezeGuard(@Value("${demo.freeze-mutations:false}") boolean frozen) {
        this.frozen = frozen;
    }

    public boolean frozen() {
        return frozen;
    }
}
