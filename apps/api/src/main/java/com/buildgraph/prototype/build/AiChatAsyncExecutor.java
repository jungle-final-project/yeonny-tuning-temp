package com.buildgraph.prototype.build;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI 채팅 처리를 요청 처리 스레드(Tomcat)에서 완전히 분리하는 전용 실행기.
 *
 * 왜: build-chat 컨트롤러가 동기면 채팅 처리(특히 LLM 대기)가 공유 Tomcat 스레드(max 150)를 붙잡아,
 * 채팅이 몰리면 로그인 같은 빠른 요청까지 대기할 수 있다. 컨트롤러가 이 실행기에 작업을 넘기고
 * {@link DeferredResult}를 즉시 반환하면 Tomcat 스레드는 곧바로 반납되고, 채팅은 이 전용 풀에서 처리된다.
 * → 로그인/기타 API(Tomcat 풀)와 AI 채팅(이 풀)이 스레드를 물리적으로 공유하지 않는다.
 *
 * LLM 세부 호출은 안쪽에서 {@code LlmCallBulkhead}가 추가로 상한을 둬, 이 채팅 풀 안에서도
 * LLM 폭주가 빠른 채팅 경로(FAST_*)를 굶기지 못하게 한다(2단 격벽).
 */
@Component
public class AiChatAsyncExecutor {
    private static final Logger log = LoggerFactory.getLogger(AiChatAsyncExecutor.class);

    private final ThreadPoolExecutor executor;
    private final long resultTimeoutMs;
    private final int capacity;

    public AiChatAsyncExecutor(
            @Value("${ai.chat.executor.pool-size:64}") int poolSize,
            @Value("${ai.chat.executor.queue-capacity:256}") int queueCapacity,
            // DeferredResult 전체 타임아웃 — LLM 격벽(call-timeout)보다 넉넉히 길게 둔다.
            @Value("${ai.chat.executor.result-timeout-ms:30000}") long resultTimeoutMs
    ) {
        int pool = Math.max(1, poolSize);
        int queue = Math.max(1, queueCapacity);
        this.capacity = pool + queue;
        this.resultTimeoutMs = Math.max(1000L, resultTimeoutMs);
        AtomicInteger threadIndex = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                pool,
                pool,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue),
                runnable -> {
                    Thread thread = new Thread(runnable, "ai-chat-" + threadIndex.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.executor.allowCoreThreadTimeOut(true);
        log.info("AI chat async executor initialized: poolSize={}, queueCapacity={}, resultTimeoutMs={}",
                pool, queue, this.resultTimeoutMs);
    }

    /**
     * 채팅 작업을 전용 풀에서 실행하고 결과를 {@link DeferredResult}로 돌려준다.
     * 풀+큐가 가득 차면(과부하) 대기 없이 503으로 완료해 요청 스레드를 즉시 반납한다.
     */
    public DeferredResult<Object> submit(Callable<?> task) {
        DeferredResult<Object> deferred = new DeferredResult<>(resultTimeoutMs);
        deferred.onTimeout(() -> {
            log.warn("AI chat processing exceeded {}ms — returning 503", resultTimeoutMs);
            deferred.setErrorResult(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 채팅 처리 지연"));
        });
        try {
            executor.execute(() -> {
                try {
                    Object value = task.call();
                    deferred.setResult(value);
                } catch (Throwable error) {
                    deferred.setErrorResult(error);
                }
            });
        } catch (RejectedExecutionException rejected) {
            log.warn("AI chat executor saturated (in-flight >= {}), shedding request to protect the service", capacity);
            deferred.setErrorResult(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 채팅 처리량 초과", rejected));
        }
        return deferred;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
