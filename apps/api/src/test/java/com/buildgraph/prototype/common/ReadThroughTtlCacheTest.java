package com.buildgraph.prototype.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReadThroughTtlCacheTest {

    @Test
    void cachesValueWithinTtl() {
        ReadThroughTtlCache<String, String> cache = new ReadThroughTtlCache<>(Duration.ofSeconds(30), 16);
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get("k", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        assertThat(cache.get("k", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void jitterExtendsEntryExpiry() throws Exception {
        ReadThroughTtlCache<String, String> cache = new ReadThroughTtlCache<>(
                Duration.ofMillis(1),
                Duration.ofSeconds(2),
                16,
                () -> TimeUnit.SECONDS.toNanos(2)
        );
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get("k", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        Thread.sleep(50);
        assertThat(cache.get("k", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void zeroTtlDisablesCaching() {
        ReadThroughTtlCache<String, String> cache = new ReadThroughTtlCache<>(Duration.ZERO, 16);
        AtomicInteger loads = new AtomicInteger();

        cache.get("k", () -> "v" + loads.incrementAndGet());
        cache.get("k", () -> "v" + loads.incrementAndGet());
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void refreshRebuildsValueBeforeTtlExpires() {
        ReadThroughTtlCache<String, String> cache = new ReadThroughTtlCache<>(Duration.ofSeconds(30), 16);
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get("k", () -> "v" + loads.incrementAndGet())).isEqualTo("v1");
        assertThat(cache.refresh("k", () -> "v" + loads.incrementAndGet(), Duration.ofSeconds(30))).isEqualTo("v2");
        assertThat(cache.get("k", () -> "v" + loads.incrementAndGet())).isEqualTo("v2");
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void staleWhileRevalidateReturnsStaleValueAndRefreshesInBackground() throws Exception {
        ReadThroughTtlCache<String, String> cache = new ReadThroughTtlCache<>(Duration.ofMillis(5), 16);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch finishRefresh = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertThat(cache.getStaleWhileRevalidate(
                    "k",
                    () -> "v" + loads.incrementAndGet(),
                    Duration.ofSeconds(1),
                    executor
            )).isEqualTo("v1");
            Thread.sleep(30);

            String value = cache.getStaleWhileRevalidate(
                    "k",
                    () -> {
                        int load = loads.incrementAndGet();
                        refreshStarted.countDown();
                        try {
                            finishRefresh.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        }
                        return "v" + load;
                    },
                    Duration.ofSeconds(1),
                    executor
            );

            assertThat(value).isEqualTo("v1");
            assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(loads.get()).isEqualTo(2);
            finishRefresh.countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            String refreshed = null;
            while (System.nanoTime() < deadline) {
                refreshed = cache.getStaleWhileRevalidate(
                        "k",
                        () -> "v" + loads.incrementAndGet(),
                        Duration.ofSeconds(1),
                        executor
                );
                if ("v2".equals(refreshed)) {
                    break;
                }
                Thread.sleep(10);
            }
            assertThat(refreshed).isEqualTo("v2");
        } finally {
            finishRefresh.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void nullLoaderResultIsNotCached() {
        ReadThroughTtlCache<String, String> cache = new ReadThroughTtlCache<>(Duration.ofSeconds(30), 16);
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.get("k", () -> {
            loads.incrementAndGet();
            return null;
        })).isNull();
        assertThat(cache.get("k", () -> {
            loads.incrementAndGet();
            return "real";
        })).isEqualTo("real");
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void removeEvictsSingleKey() {
        ReadThroughTtlCache<String, String> cache = new ReadThroughTtlCache<>(Duration.ofSeconds(30), 16);
        AtomicInteger loads = new AtomicInteger();

        cache.get("k", () -> "v" + loads.incrementAndGet());
        cache.remove("k");
        cache.get("k", () -> "v" + loads.incrementAndGet());
        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    void concurrentMissesOnSameKeyLoadOnce() throws Exception {
        // single-flight: TTL 만료(첫 로드) 순간 동시 요청들이 같은 무거운 loader를 중복 실행하지 않는다.
        ReadThroughTtlCache<String, String> cache = new ReadThroughTtlCache<>(Duration.ofSeconds(30), 16);
        AtomicInteger loads = new AtomicInteger();
        int threads = 16;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < threads; i += 1) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return cache.get("k", () -> {
                        loads.incrementAndGet();
                        try {
                            Thread.sleep(50); // 무거운 쿼리 흉내 — 스탬피드 창을 벌린다
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        return "value";
                    });
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (var future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("value");
            }
            // 락 제거/진입 미세 레이스로 이론상 2회까지 허용 — N(16)회가 아님을 고정한다.
            assertThat(loads.get()).isLessThanOrEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }
}
