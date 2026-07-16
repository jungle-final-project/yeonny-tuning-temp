package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LlmCallBulkheadTest {

    @Test
    void runsTaskAndReturnsResult() {
        LlmCallBulkhead bulkhead = new LlmCallBulkhead(2, 2, 2000L);
        String result = bulkhead.call(() -> "ok");
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void rejectsCallsBeyondConcurrencyAndQueueToProtectRequestThreads() throws InterruptedException {
        // 동시 실행 1 + 큐 1 = 총 2개까지만 수용. 세 번째 동시 호출은 대기 없이 거절돼야 한다.
        LlmCallBulkhead bulkhead = new LlmCallBulkhead(1, 1, 5000L);
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();

        // 슬롯 1(실행 중) + 슬롯 2(큐 대기)를 별도 스레드로 채운다.
        Thread occupy1 = new Thread(() -> bulkhead.call(() -> {
            running.countDown();
            block.await();
            return "first";
        }));
        occupy1.setDaemon(true);
        occupy1.start();
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();

        Thread occupy2 = new Thread(() -> {
            try {
                bulkhead.call(() -> {
                    block.await();
                    return "queued";
                });
            } catch (LlmBulkheadRejectedException ignored) {
                rejected.incrementAndGet();
            }
        });
        occupy2.setDaemon(true);
        occupy2.start();
        Thread.sleep(150);

        // 이제 pool(1)과 queue(1)가 모두 찼다 — 세 번째는 즉시 거절된다.
        assertThatThrownBy(() -> bulkhead.call(() -> "third"))
                .isInstanceOf(LlmBulkheadRejectedException.class);

        block.countDown();
        occupy1.join(2000);
        occupy2.join(2000);
        assertThat(rejected.get()).isZero(); // 큐에 있던 두 번째는 정상 완료(거절 아님)
    }

    @Test
    void timesOutWhenCallExceedsBudgetSoTheRequestThreadIsReleased() {
        LlmCallBulkhead bulkhead = new LlmCallBulkhead(1, 1, 200L);
        assertThatThrownBy(() -> bulkhead.call(() -> {
            Thread.sleep(2000);
            return "too-slow";
        })).isInstanceOf(LlmBulkheadTimeoutException.class);
    }

    @Test
    void propagatesRuntimeExceptionFromTask() {
        LlmCallBulkhead bulkhead = new LlmCallBulkhead(1, 1, 2000L);
        assertThatThrownBy(() -> bulkhead.call(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }
}
