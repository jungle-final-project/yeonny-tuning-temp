package com.buildgraph.prototype.agent;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * LLM(OpenAI) 호출을 요청 처리 스레드(Tomcat)와 분리하는 격벽(bulkhead).
 *
 * 왜: build-chat은 동기 컨트롤러라 LLM을 타는 요청은 응답이 올 때까지 Tomcat 스레드를 붙잡는다.
 * LLM 지연(수 초)이 몰리면 공유 스레드풀(maxThreads=150)이 고갈돼 로그인 같은 빠른 요청까지 대기한다.
 * 이 격벽은 (1) LLM HTTP를 전용 스레드풀에서 실행하고, (2) 동시 실행 수를 상한(pool+queue)으로 묶어
 * 그 상한을 넘는 LLM 요청은 대기 없이 즉시 거절해 호출부가 결정론 폴백으로 강등하게 한다.
 * 결과적으로 아무리 LLM 요청이 몰려도 Tomcat 스레드 대다수는 항상 빠른 요청용으로 남는다.
 */
@Component
public class LlmCallBulkhead {
    private static final Logger log = LoggerFactory.getLogger(LlmCallBulkhead.class);

    private final ThreadPoolExecutor executor;
    private final long callTimeoutMs;
    private final int capacity;

    public LlmCallBulkhead(
            @Value("${llm.bulkhead.max-concurrency:8}") int maxConcurrency,
            @Value("${llm.bulkhead.queue-capacity:8}") int queueCapacity,
            // future.get 안전망 — 실제 HTTP read timeout(OpenAiResponsesClient)보다 약간 길게 잡는다.
            @Value("${llm.bulkhead.call-timeout-ms:22000}") long callTimeoutMs
    ) {
        int pool = Math.max(1, maxConcurrency);
        int queue = Math.max(0, queueCapacity);
        this.capacity = pool + queue;
        this.callTimeoutMs = Math.max(1000L, callTimeoutMs);
        AtomicInteger threadIndex = new AtomicInteger();
        // 큐 용량 0이면 SynchronousQueue처럼 동작하지 않도록 최소 1로 만들되, 거절은 CallerRejects로 처리.
        this.executor = new ThreadPoolExecutor(
                pool,
                pool,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queue)),
                runnable -> {
                    Thread thread = new Thread(runnable, "llm-call-" + threadIndex.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.executor.allowCoreThreadTimeOut(true);
        log.info("LLM bulkhead initialized: maxConcurrency={}, queueCapacity={}, callTimeoutMs={}",
                pool, queue, this.callTimeoutMs);
    }

    /**
     * LLM 호출을 격벽 스레드풀에서 실행하고 결과를 기다린다.
     * 격벽이 포화(동시 상한 초과)면 {@link LlmBulkheadRejectedException}을,
     * 제한 시간 초과면 {@link LlmBulkheadTimeoutException}을 던진다 — 둘 다 호출부가 결정론 폴백으로 강등한다.
     */
    public <T> T call(Callable<T> task) {
        Future<T> future;
        try {
            future = executor.submit(task);
        } catch (RejectedExecutionException rejected) {
            log.warn("LLM bulkhead saturated (in-flight >= {}), rejecting call to protect request threads", capacity);
            throw new LlmBulkheadRejectedException("LLM 동시 처리 한도 초과", rejected);
        }
        try {
            return future.get(callTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new LlmBulkheadTimeoutException("LLM 응답 제한 시간(" + callTimeoutMs + "ms) 초과", timeout);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("LLM 호출 처리 실패", cause);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmBulkheadRejectedException("LLM 호출 대기 중 인터럽트", interrupted);
        }
    }

    /** 요청 스레드에서 인라인 실행하는 격벽 — 단위 테스트나 격벽 비활성 구성용. */
    static LlmCallBulkhead directForTests() {
        return new LlmCallBulkhead(1, 1, 60_000L);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
