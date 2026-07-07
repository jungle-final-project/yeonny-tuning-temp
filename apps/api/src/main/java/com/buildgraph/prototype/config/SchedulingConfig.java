package com.buildgraph.prototype.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 모든 {@code @Scheduled} 잡을 켜고 끄는 단일 스위치.
 *
 * <p>다중 인스턴스 배포(ECS Fargate 오토스케일)에서 web-facing(스케일) 태스크는
 * {@code buildgraph.scheduling.enabled=false}로 스케줄러를 전부 끄고, worker 단일 태스크만 켠다.
 * fixedDelay 기반 프리웜({@code BuildChatCachePrewarmService})·티어 스냅샷({@code BuildChatTierSnapshotRefresher})은
 * advisory lock이 없어 다중 인스턴스에서 N배 실행되고 OpenAI를 N배 호출·과금하므로 web-facing에서 반드시 꺼야 한다
 * (가격/추천 크론 7종은 {@code PipelineJobRunRecorder} advisory lock으로 이미 단일 실행이 보장됨).
 *
 * <p>기본값(속성 미지정)은 {@code true}라 로컬/단일 배포 동작은 변하지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "buildgraph.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
