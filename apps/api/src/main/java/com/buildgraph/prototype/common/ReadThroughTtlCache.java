package com.buildgraph.prototype.common;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 사용자와 무관한 읽기 전용 응답을 짧은 TTL 동안 재사용하는 최소 인메모리 캐시(단일 인스턴스 전제).
 *
 * <ul>
 *   <li>ttl &le; 0이면 캐시를 끄고 항상 loader를 호출한다(캐시 비활성화 스위치).</li>
 *   <li>maxSize를 넘으면 통째로 비운다 — 짧은 TTL·단일 인스턴스라 정교한 축출 대신 단순·안전을 택한다.</li>
 *   <li>캐시 값은 불변으로 취급한다 — 호출처는 캐시된 응답을 변형하지 않는다(직렬화 후 그대로 반환).</li>
 *   <li>같은 키의 동시 miss는 키별 락으로 직렬화한다(single-flight) — spike에서 TTL 만료 순간
 *       수백 요청이 같은 무거운 쿼리를 동시에 쏘는 스탬피드를 막는다. 락 제거와 진입 사이의
 *       미세 레이스로 드물게 loader가 2번 돌 수 있으나(N→2) 정확성엔 영향이 없다.</li>
 * </ul>
 *
 * 신선도는 TTL로만 보장한다. 가격/부품 데이터는 일일 갱신 주기라 수십 초 지연은 무시 가능하며,
 * 더 강한 즉시 무효화가 필요하면 mutation 경로에서 {@link #remove(Object)}/{@link #clear()}를 호출한다.
 */
public final class ReadThroughTtlCache<K, V> {
    private record Entry<V>(V value, long expiresAtNanos) {
    }

    private final Map<K, Entry<V>> store = new ConcurrentHashMap<>();
    private final Map<K, Object> inFlight = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final long jitterNanos;
    private final int maxSize;
    private final LongSupplier jitterSupplier;

    public ReadThroughTtlCache(Duration ttl, int maxSize) {
        this(ttl, Duration.ZERO, maxSize);
    }

    public ReadThroughTtlCache(Duration ttl, Duration jitter, int maxSize) {
        this(ttl, jitter, maxSize, null);
    }

    ReadThroughTtlCache(Duration ttl, Duration jitter, int maxSize, LongSupplier jitterSupplier) {
        this.ttlNanos = positiveNanos(ttl);
        this.jitterNanos = positiveNanos(jitter);
        this.maxSize = Math.max(1, maxSize);
        this.jitterSupplier = jitterSupplier == null ? this::randomJitterNanos : jitterSupplier;
    }

    /** 키가 살아있으면 캐시 값을, 아니면 loader로 계산해 캐시에 담고 반환한다. loader 결과가 null이면 캐시하지 않는다. */
    public V get(K key, Supplier<V> loader) {
        if (ttlNanos <= 0L) {
            return loader.get();
        }
        Entry<V> hit = store.get(key);
        if (hit != null && hit.expiresAtNanos() > System.nanoTime()) {
            return hit.value();
        }
        Object lock = inFlight.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            try {
                // 락 대기 중 다른 스레드가 채웠으면 재사용(double-check) — 스탬피드의 나머지 전원이 여기서 흡수된다.
                Entry<V> fresh = store.get(key);
                if (fresh != null && fresh.expiresAtNanos() > System.nanoTime()) {
                    return fresh.value();
                }
                V value = loader.get();
                if (value == null) {
                    return null;
                }
                if (store.size() >= maxSize) {
                    store.clear();
                }
                store.put(key, new Entry<>(value, expiresAtNanos()));
                return value;
            } finally {
                inFlight.remove(key);
            }
        }
    }

    private long expiresAtNanos() {
        return saturatedAdd(System.nanoTime(), saturatedAdd(ttlNanos, boundedJitterNanos()));
    }

    private long boundedJitterNanos() {
        if (jitterNanos <= 0L) {
            return 0L;
        }
        long value = Math.max(0L, jitterSupplier.getAsLong());
        return Math.min(value, jitterNanos);
    }

    private long randomJitterNanos() {
        if (jitterNanos <= 0L) {
            return 0L;
        }
        if (jitterNanos == Long.MAX_VALUE) {
            return ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
        }
        return ThreadLocalRandom.current().nextLong(jitterNanos + 1L);
    }

    private static long positiveNanos(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return 0L;
        }
        try {
            return Math.max(0L, duration.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0L) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    /** 특정 키 즉시 무효화 — 사용자 프로필 변경처럼 대상이 명확한 mutation 훅용. */
    public void remove(K key) {
        store.remove(key);
    }

    public void clear() {
        store.clear();
    }
}
