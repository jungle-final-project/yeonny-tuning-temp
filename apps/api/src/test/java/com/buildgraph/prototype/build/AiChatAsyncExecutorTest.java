package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

class AiChatAsyncExecutorTest {

    @Test
    void runsTaskOnSeparateThreadAndCompletesResult() throws InterruptedException {
        AiChatAsyncExecutor executor = new AiChatAsyncExecutor(4, 16, 5000L);
        String callerThread = Thread.currentThread().getName();

        DeferredResult<Object> result = executor.submit(() -> "thread:" + Thread.currentThread().getName());

        Object value = awaitResult(result);
        assertThat(value).isInstanceOf(String.class);
        // 채팅 작업은 요청 스레드가 아니라 전용 ai-chat-* 스레드에서 실행된다.
        assertThat((String) value).startsWith("thread:ai-chat-");
        assertThat((String) value).doesNotContain(callerThread);
    }

    @Test
    void shedsWithServiceUnavailableWhenPoolAndQueueAreSaturated() throws InterruptedException {
        // pool 1 + queue 1 = 총 2개 수용. 세 번째는 대기 없이 503으로 흘려보낸다.
        AiChatAsyncExecutor executor = new AiChatAsyncExecutor(1, 1, 5000L);
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);

        executor.submit(() -> { running.countDown(); block.await(); return "first"; });
        assertThat(running.await(2, TimeUnit.SECONDS)).isTrue();
        executor.submit(() -> { block.await(); return "queued"; });
        Thread.sleep(150);

        DeferredResult<Object> shed = executor.submit(() -> "third");
        Object value = awaitResult(shed);
        assertThat(value).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) value).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        block.countDown();
    }

    @Test
    void propagatesTaskExceptionAsErrorResult() throws InterruptedException {
        AiChatAsyncExecutor executor = new AiChatAsyncExecutor(2, 4, 5000L);
        DeferredResult<Object> result = executor.submit(() -> {
            throw new IllegalStateException("boom");
        });
        Object value = awaitResult(result);
        assertThat(value).isInstanceOf(IllegalStateException.class);
        assertThat(((IllegalStateException) value).getMessage()).isEqualTo("boom");
    }

    private static Object awaitResult(DeferredResult<Object> result) throws InterruptedException {
        for (int i = 0; i < 100 && result.getResult() == null; i++) {
            Thread.sleep(20);
        }
        return result.getResult();
    }
}
