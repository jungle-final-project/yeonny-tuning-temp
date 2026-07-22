package com.buildgraph.prototype.recommender.partvector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
/* 서버 시작 시, 백터 연상을 수행하는 곳 */
public class PartVectorInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(PartVectorInitializer.class);

    private final PartVectorQuery partVectorQuery;
    private final PartVectorCalculator partVectorCalculator;

    public PartVectorInitializer(
            PartVectorQuery partVectorQuery,
            PartVectorCalculator partVectorCalculator
    ) {
        this.partVectorQuery = partVectorQuery;
        this.partVectorCalculator = partVectorCalculator;
    }

    /* 서버 시작 시 부품 벡터가 없을 때만 최초 계산을 수행한다 */
    @EventListener(ApplicationReadyEvent.class)
    public void initializePartVectors() {
        /* 이미 연산이 수행 되었다면? => 진행 x */
        if (partVectorQuery.countAll() > 0) {
            return;
        }

        int calculatedParts = partVectorCalculator.recalculateAll();
        LOGGER.info("Initial part vector calculation completed: calculatedParts={}", calculatedParts);
    }
}