package com.buildgraph.prototype.common;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Small in-memory read-through TTL cache for expensive, mostly-read responses in a single API instance.
 *
 * <ul>
 *   <li>ttl <= 0 disables caching and always calls the loader.</li>
 *   <li>Same-key misses use a single-flight lock to avoid duplicating heavy loader work.</li>
 *   <li>Optional jitter spreads expiry times so many entries do not expire at the same instant.</li>
 *   <li>The stale-while-revalidate path can return an expired value immediately while one background refresh runs.</li>
 * </ul>
 */
public final class ReadThroughTtlCache<K, V> {
    private record Entry<V>(V value, long expiresAtNanos, long staleExpiresAtNanos) {
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

    /**
     * Returns a fresh cached value, or blocks one same-key caller group while loader builds and stores it.
     * Null loader results are not cached.
     */
    public V get(K key, Supplier<V> loader) {
        if (ttlNanos <= 0L) {
            return loader.get();
        }
        return loadFreshOrBlock(key, loader, 0L);
    }

    /**
     * Returns a stale value immediately after fresh TTL expiry while one background refresh rebuilds the entry.
     * If no usable stale value exists, callers fall back to the normal single-flight blocking load.
     */
    public V getStaleWhileRevalidate(K key, Supplier<V> loader, Duration staleTtl, Executor refreshExecutor) {
        if (ttlNanos <= 0L) {
            return loader.get();
        }
        long staleNanos = positiveNanos(staleTtl);
        if (staleNanos <= 0L || refreshExecutor == null) {
            return loadFreshOrBlock(key, loader, 0L);
        }

        Entry<V> hit = store.get(key);
        long now = System.nanoTime();
        if (hit != null) {
            if (hit.expiresAtNanos() > now) {
                return hit.value();
            }
            if (hit.staleExpiresAtNanos() > now) {
                refreshInBackground(key, loader, staleNanos, refreshExecutor);
                return hit.value();
            }
        }
        return loadFreshOrBlock(key, loader, staleNanos);
    }

    private V loadFreshOrBlock(K key, Supplier<V> loader, long staleNanos) {
        Object lock = inFlight.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            try {
                Entry<V> fresh = store.get(key);
                if (fresh != null && fresh.expiresAtNanos() > System.nanoTime()) {
                    return fresh.value();
                }
                return loadAndStore(key, loader, staleNanos);
            } finally {
                inFlight.remove(key);
            }
        }
    }

    private void refreshInBackground(K key, Supplier<V> loader, long staleNanos, Executor refreshExecutor) {
        Object lock = new Object();
        Object existing = inFlight.putIfAbsent(key, lock);
        if (existing != null) {
            return;
        }
        try {
            refreshExecutor.execute(() -> {
                synchronized (lock) {
                    try {
                        loadAndStore(key, loader, staleNanos);
                    } finally {
                        inFlight.remove(key);
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            inFlight.remove(key);
        }
    }

    private V loadAndStore(K key, Supplier<V> loader, long staleNanos) {
        V value = loader.get();
        if (value == null) {
            return null;
        }
        if (store.size() >= maxSize) {
            store.clear();
        }
        long expiresAtNanos = expiresAtNanos();
        store.put(key, new Entry<>(value, expiresAtNanos, staleExpiresAtNanos(expiresAtNanos, staleNanos)));
        return value;
    }

    private long expiresAtNanos() {
        return saturatedAdd(System.nanoTime(), saturatedAdd(ttlNanos, boundedJitterNanos()));
    }

    private long staleExpiresAtNanos(long expiresAtNanos, long staleNanos) {
        return staleNanos <= 0L ? expiresAtNanos : saturatedAdd(expiresAtNanos, staleNanos);
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

    public void remove(K key) {
        store.remove(key);
    }

    public void clear() {
        store.clear();
    }
}
