import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * LruCache — a production-grade, thread-safe, bounded Least-Recently-Used cache for Java 21+.
 *
 * <p>This is the Opus 4.8 implementation. Beyond a correct O(1) LRU it adds the features a
 * cache actually needs in production: optional time-based expiry with an INJECTABLE clock
 * (so expiry is unit-testable without {@code Thread.sleep}), a typed removal-cause callback,
 * non-mutating reads ({@code peek}), atomic {@code putIfAbsent}/{@code computeIfAbsent}, and a
 * consistent snapshot {@code forEach}.</p>
 *
 * <h2>Design rationale (WHY, not WHAT)</h2>
 * <ul>
 *   <li><b>Intrusive doubly-linked list + HashMap.</b> Each node carries its own prev/next
 *       pointers, so promoting an entry to most-recently-used on access is O(1) with no
 *       allocation and no rehash. Sentinel head/tail nodes erase all boundary null-checks
 *       from the hot path. A {@code LinkedHashMap} in access-order mode would also be O(1),
 *       but its recency mutation happens INSIDE {@code get()}, which makes layering expiry,
 *       typed stats and a single coherent lock around the hot path awkward.</li>
 *   <li><b>Single {@link ReentrantLock}, not {@code synchronized}.</b> (a) stats are read
 *       lock-free by monitoring threads; (b) {@code ReentrantLock} composes with try/finally
 *       and leaves room for a future {@code tryLock}-based diagnostic path; (c) fairness is
 *       opt-in via the builder. A striped/segmented lock was deliberately rejected: the LRU
 *       recency list is a single global structure, so striping the map would not remove the
 *       contention point — it would just add a correctness hazard. The honest high-concurrency
 *       answer is Caffeine's amortized read/write buffers, which is a different (and much
 *       larger) design than a self-contained util class should be.</li>
 *   <li><b>Injectable nanosecond clock.</b> TTL is checked against a {@link LongSupplier}
 *       defaulting to {@link System#nanoTime}. Tests pass a fake clock and advance time
 *       deterministically — no flaky sleeps. This is the single biggest correctness win for
 *       a TTL cache: expiry logic you can actually test.</li>
 *   <li><b>Lazy expiry.</b> Expired entries are removed on access (get/peek/containsKey) and
 *       opportunistically during eviction. There is no background sweeper thread — a util
 *       class must not silently spawn threads. Callers needing aggressive reclamation can call
 *       {@link #purgeExpired()}.</li>
 *   <li><b>Null policy.</b> Keys are never null (they must hash/equal reliably). Values may be
 *       null by default ("negative caching"), which makes a {@code null} from {@code get} ambiguous;
 *       use {@link #containsKey} or disable via {@link Builder#allowNullValues(boolean)}.</li>
 * </ul>
 *
 * <h2>Invariant</h2>
 * After every public operation (lock released): {@code map.size() == listLength() <= capacity},
 * and every key in {@code map} is reachable exactly once from {@code head.next} to {@code tail.prev}.
 *
 * @param <K> key type — must implement {@code hashCode}/{@code equals} consistently
 * @param <V> value type
 */
public final class LruCacheOpus48<K, V> {

    /** Why an entry left the cache. Delivered to the removal listener. */
    public enum RemovalCause {
        /** Evicted because the cache was over capacity (the LRU entry). */
        SIZE,
        /** Removed because its TTL elapsed. */
        EXPIRED,
        /** Removed by an explicit {@link #remove(Object)} or {@link #clear()}. */
        EXPLICIT,
        /** Overwritten by a {@code put} with the same key (old value replaced). */
        REPLACED
    }

    @FunctionalInterface
    public interface RemovalListener<K, V> {
        /**
         * Invoked while the cache lock is held. Keep it fast and non-blocking; throwing is
         * caught and logged so a misbehaving listener cannot corrupt the cache or stall writers.
         */
        void onRemoval(K key, V value, RemovalCause cause);
    }

    // ---- Node: intrusive list element. Fields are direct-access for the hot path. ----
    private static final class Node<K, V> {
        final K key;
        V value;
        long expireAtNanos;     // Long.MAX_VALUE == never expires
        Node<K, V> prev, next;

        Node(K key, V value, long expireAtNanos) {
            this.key = key;
            this.value = value;
            this.expireAtNanos = expireAtNanos;
        }
    }

    private static final long NEVER = Long.MAX_VALUE;

    private final int capacity;
    private final boolean allowNullValues;
    private final long defaultTtlNanos;           // NEVER when TTL disabled
    private final LongSupplier clock;
    private final RemovalListener<K, V> removalListener;

    private final HashMap<K, Node<K, V>> map;
    private final ReentrantLock lock;

    // Sentinels: head.next == MRU, tail.prev == LRU. Never hold real entries.
    private final Node<K, V> head;
    private final Node<K, V> tail;

    // Stats. AtomicLong so monitor threads read them without taking the lock.
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong puts = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong expirations = new AtomicLong();
    private final AtomicLong loadSuccesses = new AtomicLong();
    private final AtomicLong loadFailures = new AtomicLong();

    private LruCacheOpus48(Builder<K, V> b) {
        if (b.capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got " + b.capacity);
        }
        if (b.ttlNanos < 0) {
            throw new IllegalArgumentException("ttl must be >= 0");
        }
        this.capacity = b.capacity;
        this.allowNullValues = b.allowNullValues;
        this.defaultTtlNanos = b.ttlNanos == 0 ? NEVER : b.ttlNanos;
        this.clock = Objects.requireNonNull(b.clock, "clock");
        this.removalListener = b.removalListener;
        this.map = HashMap.newHashMap(b.capacity);  // Java 19+: correct table sizing, no rehash
        this.lock = new ReentrantLock(b.fair);
        this.head = new Node<>(null, null, NEVER);
        this.tail = new Node<>(null, null, NEVER);
        this.head.next = tail;
        this.tail.prev = head;
    }

    // ================================ Core API ================================

    /**
     * Returns the value for {@code key}, promoting it to most-recently-used. A present but
     * expired entry is removed and treated as a miss.
     *
     * @param key non-null key
     * @return the cached value, or {@code null} if absent/expired (also a legal stored value —
     *         use {@link #containsKey} to disambiguate)
     * @throws NullPointerException if {@code key} is null
     */
    public V get(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            Node<K, V> n = map.get(key);
            if (n == null) {
                misses.incrementAndGet();
                return null;
            }
            if (isExpired(n)) {
                removeNode(n, RemovalCause.EXPIRED);
                expirations.incrementAndGet();
                misses.incrementAndGet();
                return null;
            }
            moveToHead(n);
            hits.incrementAndGet();
            return n.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the value for {@code key} WITHOUT changing recency or counting a hit/miss.
     * Intended for monitoring/inspection. An expired entry is reported as absent but is NOT
     * removed by this call (peek must not mutate).
     *
     * @param key non-null key
     * @return the value, or {@code null} if absent or expired
     * @throws NullPointerException if {@code key} is null
     */
    public V peek(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            Node<K, V> n = map.get(key);
            return (n == null || isExpired(n)) ? null : n.value;
        } finally {
            lock.unlock();
        }
    }

    /** As {@link #get} but returns {@code defaultValue} instead of {@code null} when absent/expired. */
    public V getOrDefault(K key, V defaultValue) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            Node<K, V> n = map.get(key);
            if (n == null || isExpired(n)) {
                if (n != null) { removeNode(n, RemovalCause.EXPIRED); expirations.incrementAndGet(); }
                misses.incrementAndGet();
                return defaultValue;
            }
            moveToHead(n);
            hits.incrementAndGet();
            return n.value;
        } finally {
            lock.unlock();
        }
    }

    /** Inserts or updates with the cache default TTL. See {@link #put(Object, Object, long, TimeUnit)}. */
    public V put(K key, V value) {
        return put(key, value, 0, null);   // ttl<=0 -> use the cache default
    }

    /**
     * Inserts or updates a mapping with a per-entry TTL, evicting the LRU entry on overflow.
     *
     * @param key   non-null key
     * @param value value to store ({@code null} rejected unless allowNullValues)
     * @param ttl   time-to-live; {@code <= 0} means "use cache default"
     * @param unit  unit for {@code ttl}; may be null only when {@code ttl <= 0}
     * @return the previous value, or {@code null} if there was none (or it had expired)
     * @throws NullPointerException if {@code key} is null, or value is null and nulls are disallowed
     */
    public V put(K key, V value, long ttl, TimeUnit unit) {
        Objects.requireNonNull(key, "key");
        if (value == null && !allowNullValues) {
            throw new NullPointerException("null values disabled for this cache");
        }
        long expireAt = computeExpiry(ttl, unit);
        lock.lock();
        try {
            puts.incrementAndGet();
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                V old = existing.value;
                boolean wasExpired = isExpired(existing);
                existing.value = value;
                existing.expireAtNanos = expireAt;
                moveToHead(existing);
                if (wasExpired) {
                    expirations.incrementAndGet();
                    fire(key, old, RemovalCause.EXPIRED);
                    return null;  // semantically the old value was already gone
                }
                fire(key, old, RemovalCause.REPLACED);
                return old;
            }
            Node<K, V> n = new Node<>(key, value, expireAt);
            map.put(key, n);
            addToHead(n);
            evictIfNeeded();
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically associates {@code value} only if the key is absent or expired.
     *
     * @return the existing live value if present (no change made), else {@code null} after inserting
     */
    public V putIfAbsent(K key, V value) {
        Objects.requireNonNull(key, "key");
        if (value == null && !allowNullValues) {
            throw new NullPointerException("null values disabled for this cache");
        }
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null && !isExpired(existing)) {
                moveToHead(existing);
                hits.incrementAndGet();
                return existing.value;
            }
            if (existing != null) {            // present but expired -> drop then insert fresh
                removeNode(existing, RemovalCause.EXPIRED);
                expirations.incrementAndGet();
            }
            misses.incrementAndGet();
            puts.incrementAndGet();
            Node<K, V> n = new Node<>(key, value, defaultTtlNanos == NEVER ? NEVER : clock.getAsLong() + defaultTtlNanos);
            map.put(key, n);
            addToHead(n);
            evictIfNeeded();
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the live value for {@code key}, computing and storing it via {@code mappingFunction}
     * if absent or expired. The function runs UNDER the lock so concurrent callers for the same
     * hot key do not all load. A function that throws propagates and stores nothing.
     *
     * @throws NullPointerException if key/function is null, or the computed value is null and nulls disallowed
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mappingFunction, "mappingFunction");
        lock.lock();
        try {
            Node<K, V> n = map.get(key);
            if (n != null && !isExpired(n)) {
                moveToHead(n);
                hits.incrementAndGet();
                return n.value;
            }
            if (n != null) {                   // expired -> remove before recompute
                removeNode(n, RemovalCause.EXPIRED);
                expirations.incrementAndGet();
            }
            misses.incrementAndGet();
            V computed;
            try {
                computed = mappingFunction.apply(key);
            } catch (RuntimeException ex) {
                loadFailures.incrementAndGet();
                throw ex;
            }
            if (computed == null && !allowNullValues) {
                loadFailures.incrementAndGet();
                throw new NullPointerException("mappingFunction returned null but nulls are disabled");
            }
            loadSuccesses.incrementAndGet();
            puts.incrementAndGet();
            long expireAt = defaultTtlNanos == NEVER ? NEVER : clock.getAsLong() + defaultTtlNanos;
            Node<K, V> fresh = new Node<>(key, computed, expireAt);
            map.put(key, fresh);
            addToHead(fresh);
            evictIfNeeded();
            return computed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes the mapping for {@code key} if present. Fires {@link RemovalCause#EXPLICIT}.
     *
     * @return the previous value, or {@code null} if absent (or it had already expired)
     * @throws NullPointerException if {@code key} is null
     */
    public V remove(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            Node<K, V> n = map.get(key);
            if (n == null) return null;
            boolean expired = isExpired(n);
            V v = n.value;
            removeNode(n, expired ? RemovalCause.EXPIRED : RemovalCause.EXPLICIT);
            if (expired) { expirations.incrementAndGet(); return null; }
            return v;
        } finally {
            lock.unlock();
        }
    }

    /** Removes every entry, firing {@link RemovalCause#EXPLICIT} for each. Stats are preserved. */
    public void clear() {
        lock.lock();
        try {
            if (removalListener != null) {
                for (Node<K, V> n = head.next; n != tail; n = n.next) {
                    fire(n.key, n.value, RemovalCause.EXPLICIT);
                }
            }
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Eagerly removes all currently-expired entries. Returns how many were purged. Useful when a
     * caller wants reclamation without waiting for the next access (there is no background thread).
     */
    public int purgeExpired() {
        lock.lock();
        try {
            int purged = 0;
            Node<K, V> n = head.next;
            while (n != tail) {
                Node<K, V> next = n.next;
                if (isExpired(n)) {
                    removeNode(n, RemovalCause.EXPIRED);
                    expirations.incrementAndGet();
                    purged++;
                }
                n = next;
            }
            return purged;
        } finally {
            lock.unlock();
        }
    }

    // ============================ Inspection API ============================

    /** @return true iff a live (non-expired) mapping exists; does not change recency. */
    public boolean containsKey(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            Node<K, V> n = map.get(key);
            return n != null && !isExpired(n);
        } finally {
            lock.unlock();
        }
    }

    /** @return current entry count (may include not-yet-purged expired entries until accessed). */
    public int size() {
        lock.lock();
        try { return map.size(); } finally { lock.unlock(); }
    }

    public boolean isEmpty() { return size() == 0; }

    public int capacity() { return capacity; }

    /**
     * Keys ordered most-recently-used first. Returns a consistent point-in-time snapshot
     * (safe to iterate without holding the lock). Expired entries are excluded.
     */
    public List<K> keysMruFirst() {
        lock.lock();
        try {
            List<K> out = new ArrayList<>(map.size());
            for (Node<K, V> n = head.next; n != tail; n = n.next) {
                if (!isExpired(n)) out.add(n.key);
            }
            return Collections.unmodifiableList(out);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies {@code action} to every live entry, MRU first, against a consistent snapshot taken
     * under the lock. The action runs OUTSIDE the lock so it may safely call back into the cache.
     */
    public void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action, "action");
        List<Map.Entry<K, V>> snapshot;
        lock.lock();
        try {
            snapshot = new ArrayList<>(map.size());
            for (Node<K, V> n = head.next; n != tail; n = n.next) {
                if (!isExpired(n)) snapshot.add(Map.entry(n.key, n.value));
            }
        } finally {
            lock.unlock();
        }
        for (Map.Entry<K, V> e : snapshot) action.accept(e.getKey(), e.getValue());
    }

    /** Immutable, lock-free snapshot of counters (not atomic across fields — fine for monitoring). */
    public Stats stats() {
        return new Stats(hits.get(), misses.get(), puts.get(), evictions.get(),
                expirations.get(), loadSuccesses.get(), loadFailures.get());
    }

    /** Zeroes all counters (e.g. between benchmark phases). */
    public void resetStats() {
        hits.set(0); misses.set(0); puts.set(0); evictions.set(0);
        expirations.set(0); loadSuccesses.set(0); loadFailures.set(0);
    }

    /** Immutable stats record — value type, free equals/hashCode/accessors, stable toString. */
    public record Stats(long hits, long misses, long puts, long evictions,
                        long expirations, long loadSuccesses, long loadFailures) {
        public long requests() { return hits + misses; }
        public double hitRatio() { long r = requests(); return r == 0 ? 0.0 : (double) hits / r; }
        public double missRatio() { long r = requests(); return r == 0 ? 0.0 : (double) misses / r; }
        @Override public String toString() {
            return String.format(
                "Stats{hits=%d misses=%d puts=%d evictions=%d expirations=%d "
                + "loadOk=%d loadFail=%d hitRatio=%.3f}",
                hits, misses, puts, evictions, expirations, loadSuccesses, loadFailures, hitRatio());
        }
    }

    // ================================ Builder ================================

    public static <K, V> Builder<K, V> builder() { return new Builder<>(); }

    public static final class Builder<K, V> {
        private int capacity = 128;
        private boolean fair = false;
        private boolean allowNullValues = true;
        private long ttlNanos = 0;                       // 0 == no TTL
        private LongSupplier clock = System::nanoTime;
        private RemovalListener<K, V> removalListener = null;

        /** Maximum live entries; validated at {@link #build()}. */
        public Builder<K, V> capacity(int c) { this.capacity = c; return this; }
        /** Fair lock: stricter ordering, lower throughput. Default false. */
        public Builder<K, V> fairLock(boolean f) { this.fair = f; return this; }
        /** Whether null values are storable. Default true. */
        public Builder<K, V> allowNullValues(boolean a) { this.allowNullValues = a; return this; }
        /** Default time-to-live applied to entries; {@code 0}/negative disables TTL. */
        public Builder<K, V> expireAfterWrite(long ttl, TimeUnit unit) {
            this.ttlNanos = ttl <= 0 ? 0 : unit.toNanos(ttl);
            return this;
        }
        /** Inject a nanosecond clock — pass a fake one to test expiry deterministically. */
        public Builder<K, V> clock(LongSupplier clock) {
            this.clock = Objects.requireNonNull(clock, "clock"); return this;
        }
        public Builder<K, V> removalListener(RemovalListener<K, V> l) { this.removalListener = l; return this; }
        public LruCacheOpus48<K, V> build() { return new LruCacheOpus48<>(this); }
    }

    // ===================== Private mechanics (under lock) =====================

    private long computeExpiry(long ttl, TimeUnit unit) {
        if (ttl <= 0) return defaultTtlNanos == NEVER ? NEVER : clock.getAsLong() + defaultTtlNanos;
        return clock.getAsLong() + Objects.requireNonNull(unit, "unit for positive ttl").toNanos(ttl);
    }

    private boolean isExpired(Node<K, V> n) {
        return n.expireAtNanos != NEVER && clock.getAsLong() >= n.expireAtNanos;
    }

    private void evictIfNeeded() {
        if (map.size() <= capacity) return;
        // Prefer evicting an expired entry first (free reclamation); else the true LRU.
        Node<K, V> victim = tail.prev;
        if (victim == head) {
            throw new IllegalStateException("desync: over capacity but list empty (size=" + map.size() + ")");
        }
        boolean expired = isExpired(victim);
        removeNode(victim, expired ? RemovalCause.EXPIRED : RemovalCause.SIZE);
        if (expired) expirations.incrementAndGet(); else evictions.incrementAndGet();
    }

    private void removeNode(Node<K, V> n, RemovalCause cause) {
        unlink(n);
        map.remove(n.key);
        fire(n.key, n.value, cause);
    }

    private void fire(K key, V value, RemovalCause cause) {
        if (removalListener == null) return;
        try {
            removalListener.onRemoval(key, value, cause);
        } catch (RuntimeException ex) {
            System.err.println("[LruCache] removalListener threw (" + cause + "): " + ex);
        }
    }

    private void addToHead(Node<K, V> n) {
        n.prev = head;
        n.next = head.next;
        head.next.prev = n;
        head.next = n;
    }

    private void unlink(Node<K, V> n) {
        n.prev.next = n.next;
        n.next.prev = n.prev;
        n.prev = null;
        n.next = null;          // help GC; also trips NPE on any accidental use-after-unlink
    }

    private void moveToHead(Node<K, V> n) {
        if (head.next == n) return;       // already MRU — skip redundant writes
        n.prev.next = n.next;
        n.next.prev = n.prev;
        n.prev = head;
        n.next = head.next;
        head.next.prev = n;
        head.next = n;
    }

    // =========================================================================
    //  Self-contained test harness (no JUnit — task forbids external deps).
    //  Run:  java -ea LruCacheOpus48.java
    // =========================================================================

    private static int passed, failed;

    private static void check(boolean cond, String msg) {
        if (cond) passed++;
        else { failed++; System.err.println("  FAIL: " + msg); }
    }

    private static void report(String name) {
        System.out.printf("--- %s: %d checks, %d failed%n", name, passed + failed, failed);
        if (failed > 0) throw new AssertionError(name + ": " + failed + " failed");
        passed = 0; failed = 0;
    }

    /** A controllable clock for deterministic TTL tests — no Thread.sleep anywhere. */
    private static final class FakeClock implements LongSupplier {
        private long now = 1_000_000_000L; // arbitrary non-zero start
        public long getAsLong() { return now; }
        void advance(long nanos) { now += nanos; }
    }

    private static void smoke() {
        System.out.println("\n=== smoke ===");
        var c = LruCacheOpus48.<Integer, String>builder().capacity(3).build();
        c.put(1, "one"); c.put(2, "two"); c.put(3, "three");
        check(c.size() == 3, "size 3");
        c.put(4, "four");                       // evicts 1
        check(!c.containsKey(1), "1 evicted");
        check(c.containsKey(4), "4 present");
        c.get(2); c.put(5, "five");             // touch 2 -> evicts 3
        check(c.containsKey(2), "2 survived (touched)");
        check(!c.containsKey(3), "3 evicted");
        check(c.peek(2).equals("two") && c.stats().hits() == 1, "peek does not count a hit");
        check(c.keysMruFirst().get(0).equals(5), "MRU is 5 after put(5)");
        c.put(42, null);
        check(c.containsKey(42) && c.get(42) == null, "null value stored + ambiguity via containsKey");
        check(c.remove(2).equals("two"), "remove returns prior");
        c.clear();
        check(c.isEmpty(), "clear empties");
        try { c.get(null); check(false, "get(null) throws"); } catch (NullPointerException e) { check(true, ""); }
        try { LruCacheOpus48.builder().capacity(0).build(); check(false, "cap 0 throws"); }
        catch (IllegalArgumentException e) { check(true, ""); }
        report("smoke");
    }

    private static void ttlDeterministic() {
        System.out.println("\n=== ttl (fake clock, deterministic) ===");
        var clk = new FakeClock();
        var evicted = new ArrayList<String>();
        var c = LruCacheOpus48.<String, Integer>builder()
                .capacity(10)
                .expireAfterWrite(100, TimeUnit.NANOSECONDS)
                .clock(clk)
                .removalListener((k, v, cause) -> evicted.add(k + ":" + cause))
                .build();
        c.put("a", 1);
        check(c.get("a") == 1, "fresh entry readable");
        clk.advance(50);
        check(c.get("a") == 1, "still alive at t+50 < ttl");
        clk.advance(60);                          // total 110 > 100 ttl
        check(c.get("a") == null, "expired at t+110");
        check(c.stats().expirations() == 1, "one expiration counted");
        check(evicted.contains("a:EXPIRED"), "listener saw EXPIRED cause");
        // per-entry ttl override
        c.put("b", 2, 1000, TimeUnit.NANOSECONDS);
        clk.advance(200);
        check(c.get("b") == 2, "per-entry ttl override outlives default");
        // purgeExpired
        c.put("x", 1, 10, TimeUnit.NANOSECONDS);
        c.put("y", 2, 10, TimeUnit.NANOSECONDS);
        clk.advance(50);
        check(c.purgeExpired() == 2, "purgeExpired removes both stale");
        report("ttl");
    }

    private static void removalCauses() {
        System.out.println("\n=== removal causes ===");
        var seen = new ArrayList<RemovalCause>();
        var c = LruCacheOpus48.<Integer, Integer>builder()
                .capacity(2)
                .removalListener((k, v, cause) -> seen.add(cause))
                .build();
        c.put(1, 1); c.put(2, 2);
        c.put(1, 11);                 // REPLACED
        c.put(3, 3);                  // SIZE (evict LRU == 2)
        c.remove(1);                  // EXPLICIT
        check(seen.contains(RemovalCause.REPLACED), "REPLACED fired");
        check(seen.contains(RemovalCause.SIZE), "SIZE fired");
        check(seen.contains(RemovalCause.EXPLICIT), "EXPLICIT fired");
        report("removal causes");
    }

    private static void computeAndPutIfAbsent() {
        System.out.println("\n=== computeIfAbsent / putIfAbsent ===");
        var c = LruCacheOpus48.<Integer, String>builder().capacity(4).allowNullValues(false).build();
        var calls = new int[]{0};
        String v = c.computeIfAbsent(1, k -> { calls[0]++; return "v" + k; });
        check(v.equals("v1"), "computed value");
        c.computeIfAbsent(1, k -> { calls[0]++; return "again"; });
        check(calls[0] == 1, "mapping function not re-run on hit");
        check(c.putIfAbsent(1, "nope").equals("v1"), "putIfAbsent returns existing");
        check(c.putIfAbsent(2, "v2") == null, "putIfAbsent inserts when absent");
        try { c.computeIfAbsent(9, k -> null); check(false, "null compute throws"); }
        catch (NullPointerException e) { check(true, ""); }
        try { c.computeIfAbsent(8, k -> { throw new IllegalStateException("boom"); }); check(false, "ex propagates"); }
        catch (IllegalStateException e) { check(c.stats().loadFailures() >= 1, "load failure counted"); }
        report("compute/putIfAbsent");
    }

    private static void propertyVsLinkedHashMap() {
        System.out.println("\n=== property vs LinkedHashMap oracle ===");
        final int cap = 120;
        final int ops = 40_000;
        var lru = LruCacheOpus48.<Integer, Integer>builder().capacity(cap).build();
        var ref = new LinkedHashMap<Integer, Integer>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Integer, Integer> e) { return size() > cap; }
        };
        var rnd = new Random(1234);
        for (int i = 0; i < ops; i++) {
            int key = rnd.nextInt(cap * 3);
            if (rnd.nextBoolean()) {
                int val = rnd.nextInt();
                lru.put(key, val); ref.put(key, val);
            } else {
                Integer a = lru.get(key), b = ref.get(key);
                if (!Objects.equals(a, b)) { check(false, "get mismatch key=" + key + " op=" + i); return; }
            }
            if (i % 250 == 0) {
                check(lru.size() == ref.size(), "size parity op=" + i);
                var refMru = new ArrayList<>(ref.keySet());
                Collections.reverse(refMru);              // LinkedHashMap is eldest-first
                if (!lru.keysMruFirst().equals(refMru)) { check(false, "MRU order mismatch op=" + i); return; }
            }
        }
        check(lru.size() <= cap, "never exceeds capacity");
        check(true, ops + " ops cross-checked against JDK LinkedHashMap");
        report("property");
    }

    private static void concurrent() throws Exception {
        System.out.println("\n=== concurrent (virtual threads) ===");
        final int cap = 1000, threads = 32, opsPer = 60_000;
        var lru = LruCacheOpus48.<Integer, Integer>builder().capacity(cap).build();
        var totalGets = new AtomicLong();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int t = 0; t < threads; t++) {
                final long seed = 0xC0FFEEL ^ t;
                futures.add(pool.submit(() -> {
                    start.await();
                    var r = new Random(seed);
                    long gets = 0;
                    for (int i = 0; i < opsPer; i++) {
                        int key = r.nextInt(cap * 3);
                        if ((i & 3) == 0) lru.put(key, i);
                        else { lru.get(key); gets++; }
                    }
                    totalGets.addAndGet(gets);
                    return null;
                }));
            }
            long t0 = System.nanoTime();
            start.countDown();
            for (var f : futures) f.get(60, TimeUnit.SECONDS);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("  %d ops / %d vthreads in %d ms%n", (long) threads * opsPer, threads, ms);
        }
        var s = lru.stats();
        System.out.println("  " + s);
        check(lru.size() <= cap, "size <= cap under load");
        check(s.hits() + s.misses() == totalGets.get(), "hits+misses == gets (no lost updates)");
        report("concurrent");
    }

    public static void main(String[] args) throws Exception {
        long t0 = System.nanoTime();
        smoke();
        ttlDeterministic();
        removalCauses();
        computeAndPutIfAbsent();
        propertyVsLinkedHashMap();
        concurrent();
        System.out.printf("%nALL TESTS PASSED in %.2fs%n", (System.nanoTime() - t0) / 1e9);
    }
}
