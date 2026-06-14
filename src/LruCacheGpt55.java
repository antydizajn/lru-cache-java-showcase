import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class LruCacheGpt55<K, V> {
    private final int capacity;
    private final ReentrantLock lock;
    private final HashMapAdapter<K, V> map;

    private Node<K, V> headMostRecent;
    private Node<K, V> tailLeastRecent;

    private long hits;
    private long misses;
    private long puts;
    private long evictions;
    private long removals;
    private long clears;

    public LruCacheGpt55(int capacity) {
        this(new Builder<K, V>().capacity(capacity));
    }

    private LruCacheGpt55(Builder<K, V> builder) {
        this.capacity = builder.checkedCapacity();
        this.lock = new ReentrantLock(builder.fairLock);
        var initialHashCapacity = builder.initialHashCapacity >= 0
                ? builder.initialHashCapacity
                : hashMapCapacityFor(this.capacity);
        this.map = new HashMapAdapter<>(initialHashCapacity);
    }

    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    public static <K, V> Builder<K, V> builder(int capacity) {
        return LruCacheGpt55.<K, V>builder().capacity(capacity);
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return map.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    public boolean containsKey(K key) {
        requireKey(key);
        lock.lock();
        try {
            return map.containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the cached value, or {@code null} on miss.
     * <p>
     * Null values are supported, so use {@link #containsKey(Object)} if callers
     * need to distinguish an absent key from a present key mapped to null.
     */
    public V get(K key) {
        requireKey(key);
        lock.lock();
        try {
            var node = map.get(key);
            if (node == null) {
                misses = saturatedIncrement(misses);
                return null;
            }
            hits = saturatedIncrement(hits);
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Like {@link #get(Object)}, but returns {@code defaultValue} only when the key is absent.
     * A present key mapped to null returns null and counts as a cache hit.
     */
    public V getOrDefault(K key, V defaultValue) {
        requireKey(key);
        lock.lock();
        try {
            var node = map.get(key);
            if (node == null) {
                misses = saturatedIncrement(misses);
                return defaultValue;
            }
            hits = saturatedIncrement(hits);
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts or replaces a value and marks the key most-recently-used.
     *
     * @return the previous value, or {@code null} if absent or previously mapped to null.
     */
    public V put(K key, V value) {
        requireKey(key);
        lock.lock();
        try {
            puts = saturatedIncrement(puts);

            var existing = map.get(key);
            if (existing != null) {
                var previous = existing.value;
                existing.value = value;
                moveToHead(existing);
                return previous;
            }

            if (capacity == 0) {
                evictions = saturatedIncrement(evictions);
                return null;
            }

            var node = new Node<>(key, value);
            map.put(key, node);
            linkAtHead(node);

            if (map.size() > capacity) {
                evictTail();
            }

            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes a key if present.
     *
     * @return the removed value, or {@code null} if absent or mapped to null.
     */
    public V remove(K key) {
        requireKey(key);
        lock.lock();
        try {
            var node = map.remove(key);
            if (node == null) {
                return null;
            }
            unlink(node);
            removals = saturatedIncrement(removals);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            for (var node = headMostRecent; node != null; ) {
                var next = node.next;
                node.prev = null;
                node.next = null;
                node = next;
            }
            headMostRecent = null;
            tailLeastRecent = null;
            map.clear();
            clears = saturatedIncrement(clears);
        } finally {
            lock.unlock();
        }
    }

    public void resetStats() {
        lock.lock();
        try {
            hits = 0L;
            misses = 0L;
            puts = 0L;
            evictions = 0L;
            removals = 0L;
            clears = 0L;
        } finally {
            lock.unlock();
        }
    }

    public Stats stats() {
        lock.lock();
        try {
            return new Stats(capacity, map.size(), hits, misses, puts, evictions, removals, clears);
        } finally {
            lock.unlock();
        }
    }

    public Map<K, V> snapshotMostRecentFirst() {
        lock.lock();
        try {
            var copy = new LinkedHashMap<K, V>(map.size());
            for (var node = headMostRecent; node != null; node = node.next) {
                copy.put(node.key, node.value);
            }
            return Collections.unmodifiableMap(copy);
        } finally {
            lock.unlock();
        }
    }

    public Map<K, V> snapshotLeastRecentFirst() {
        lock.lock();
        try {
            var copy = new LinkedHashMap<K, V>(map.size());
            for (var node = tailLeastRecent; node != null; node = node.prev) {
                copy.put(node.key, node.value);
            }
            return Collections.unmodifiableMap(copy);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return "LruCacheGpt55" + stats();
    }

    private void linkAtHead(Node<K, V> node) {
        node.prev = null;
        node.next = headMostRecent;

        if (headMostRecent != null) {
            headMostRecent.prev = node;
        }
        headMostRecent = node;

        if (tailLeastRecent == null) {
            tailLeastRecent = node;
        }
    }

    private void moveToHead(Node<K, V> node) {
        if (node == headMostRecent) {
            return;
        }
        unlink(node);
        linkAtHead(node);
    }

    private void unlink(Node<K, V> node) {
        var prev = node.prev;
        var next = node.next;

        if (prev != null) {
            prev.next = next;
        } else {
            headMostRecent = next;
        }

        if (next != null) {
            next.prev = prev;
        } else {
            tailLeastRecent = prev;
        }

        node.prev = null;
        node.next = null;
    }

    private void evictTail() {
        var victim = tailLeastRecent;
        if (victim == null) {
            return;
        }
        unlink(victim);
        map.remove(victim.key);
        evictions = saturatedIncrement(evictions);
    }

    private static void requireKey(Object key) {
        Objects.requireNonNull(key, "LruCacheGpt55 rejects null keys");
    }

    private static int hashMapCapacityFor(int capacity) {
        if (capacity <= 0) {
            return 0;
        }
        var needed = (long) Math.ceil(capacity / 0.75d) + 1L;
        return (int) Math.min(1 << 30, Math.max(1L, needed));
    }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private void assertHealthyForTests() {
        lock.lock();
        try {
            assert map.size() <= capacity : "cache exceeded capacity";
            if (map.isEmpty()) {
                assert headMostRecent == null : "empty cache has head";
                assert tailLeastRecent == null : "empty cache has tail";
                return;
            }

            assert headMostRecent != null : "non-empty cache missing head";
            assert tailLeastRecent != null : "non-empty cache missing tail";
            assert headMostRecent.prev == null : "head has prev";
            assert tailLeastRecent.next == null : "tail has next";

            var seen = Collections.newSetFromMap(new IdentityHashMap<Node<K, V>, Boolean>());
            var count = 0;
            Node<K, V> previous = null;

            for (var node = headMostRecent; node != null; node = node.next) {
                assert seen.add(node) : "cycle detected";
                assert node.prev == previous : "broken prev link";
                assert node.key != null : "null key stored";
                assert map.get(node.key) == node : "map/list disagree";
                previous = node;
                count++;
                assert count <= map.size() : "list longer than map";
            }

            assert previous == tailLeastRecent : "tail mismatch";
            assert count == map.size() : "map/list size mismatch";
        } finally {
            lock.unlock();
        }
    }

    public static final class Builder<K, V> {
        private Integer capacity;
        private int initialHashCapacity = -1;
        private boolean fairLock;

        private Builder() {
        }

        public Builder<K, V> capacity(int capacity) {
            if (capacity < 0) {
                throw new IllegalArgumentException("capacity must be >= 0");
            }
            this.capacity = capacity;
            return this;
        }

        public Builder<K, V> initialHashCapacity(int initialHashCapacity) {
            if (initialHashCapacity < 0) {
                throw new IllegalArgumentException("initialHashCapacity must be >= 0");
            }
            this.initialHashCapacity = initialHashCapacity;
            return this;
        }

        public Builder<K, V> fairLock(boolean fairLock) {
            this.fairLock = fairLock;
            return this;
        }

        public LruCacheGpt55<K, V> build() {
            return new LruCacheGpt55<>(this);
        }

        private int checkedCapacity() {
            if (capacity == null) {
                throw new IllegalStateException("capacity must be configured");
            }
            return capacity;
        }
    }

    public record Stats(
            int capacity,
            int size,
            long hits,
            long misses,
            long puts,
            long evictions,
            long removals,
            long clears
    ) {
        public long requests() {
            return saturatedAdd(hits, misses);
        }

        public double hitRate() {
            var total = (double) hits + (double) misses;
            return total == 0.0d ? 0.0d : hits / total;
        }

        public double missRate() {
            var total = (double) hits + (double) misses;
            return total == 0.0d ? 0.0d : misses / total;
        }

        private static long saturatedAdd(long a, long b) {
            if (a < 0 || b < 0) {
                throw new IllegalArgumentException("stats counters must be non-negative");
            }
            return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
        }
    }

    private static final class Node<K, V> {
        private final K key;
        private V value;
        private Node<K, V> prev;
        private Node<K, V> next;

        private Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final class HashMapAdapter<K, V> {
        private final java.util.HashMap<K, Node<K, V>> delegate;

        private HashMapAdapter(int initialCapacity) {
            this.delegate = new java.util.HashMap<>(initialCapacity);
        }

        private int size() {
            return delegate.size();
        }

        private boolean isEmpty() {
            return delegate.isEmpty();
        }

        private boolean containsKey(K key) {
            return delegate.containsKey(key);
        }

        private Node<K, V> get(K key) {
            return delegate.get(key);
        }

        private Node<K, V> put(K key, Node<K, V> value) {
            return delegate.put(key, value);
        }

        private Node<K, V> remove(K key) {
            return delegate.remove(key);
        }

        private void clear() {
            delegate.clear();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        if (!LruCacheGpt55.class.desiredAssertionStatus()) {
            throw new IllegalStateException("Run with assertions enabled: java -ea LruCacheGpt55");
        }

        smokeTest();
        propertyTestAgainstLinkedHashMap();
        virtualThreadConcurrencyTest();

        System.out.println("All LruCacheGpt55 tests passed");
    }

    private static void smokeTest() throws Exception {
        expectThrows(IllegalArgumentException.class, () -> LruCacheGpt55.builder(-1));
        expectThrows(IllegalStateException.class, () -> LruCacheGpt55.builder().build());

        var cache = LruCacheGpt55.<String, String>builder()
                .capacity(2)
                .initialHashCapacity(4)
                .fairLock(false)
                .build();

        expectThrows(NullPointerException.class, () -> cache.get(null));
        expectThrows(NullPointerException.class, () -> cache.put(null, "x"));
        expectThrows(NullPointerException.class, () -> cache.remove(null));
        expectThrows(NullPointerException.class, () -> cache.containsKey(null));

        assert cache.capacity() == 2;
        assert cache.isEmpty();

        assert cache.put("a", "A") == null;
        assert cache.put("b", "B") == null;
        assert cache.size() == 2;
        assert "A".equals(cache.get("a"));

        assert cache.put("c", "C") == null;
        assert !cache.containsKey("b") : "b should have been evicted";
        assert cache.containsKey("a");
        assert cache.containsKey("c");

        assert cache.get("b") == null;
        assert "fallback".equals(cache.getOrDefault("missing", "fallback"));
        assert "A".equals(cache.get("a"));

        var leastFirst = new ArrayList<>(cache.snapshotLeastRecentFirst().keySet());
        assert leastFirst.equals(List.of("c", "a")) : leastFirst;

        assert cache.put("n", null) == null;
        assert !cache.containsKey("c");
        assert cache.containsKey("n");
        assert cache.getOrDefault("n", "not-used") == null;

        assert cache.remove("n") == null;
        assert !cache.containsKey("n");
        assert cache.size() == 1;

        var stats = cache.stats();
        assert stats.hits() == 3 : stats;
        assert stats.misses() == 2 : stats;
        assert stats.puts() == 4 : stats;
        assert stats.evictions() == 2 : stats;
        assert stats.removals() == 1 : stats;
        assert stats.requests() == 5 : stats;
        assert stats.hitRate() > 0.59d && stats.hitRate() < 0.61d : stats;

        cache.clear();
        assert cache.isEmpty();
        assert cache.stats().clears() == 1;

        cache.resetStats();
        assert cache.stats().requests() == 0;
        assert cache.stats().puts() == 0;

        var zero = new LruCacheGpt55<String, Integer>(0);
        assert zero.put("x", 1) == null;
        assert zero.size() == 0;
        assert zero.get("x") == null;
        assert zero.stats().puts() == 1;
        assert zero.stats().evictions() == 1;
        assert zero.stats().misses() == 1;

        cache.assertHealthyForTests();
        zero.assertHealthyForTests();
    }

    private static void propertyTestAgainstLinkedHashMap() {
        for (var capacity = 0; capacity <= 20; capacity++) {
            final var cap = capacity;
            var cache = LruCacheGpt55.<Integer, String>builder(cap).build();
            var reference = new LinkedHashMap<Integer, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                    return size() > cap;
                }
            };

            var rnd = new SplittableRandom(0x5EED_1234L + cap);
            var expectedHits = 0L;
            var expectedMisses = 0L;

            for (var i = 0; i < 5_000; i++) {
                var key = rnd.nextInt(35);
                var operation = rnd.nextInt(6);

                switch (operation) {
                    case 0 -> {
                        var value = switch (rnd.nextInt(5)) {
                            case 0 -> null;
                            case 1 -> "";
                            default -> "v-" + rnd.nextInt(1_000);
                        };
                        var expected = reference.put(key, value);
                        var actual = cache.put(key, value);
                        assert Objects.equals(actual, expected)
                                : "put return mismatch capacity=" + cap + " key=" + key;
                    }
                    case 1 -> {
                        var wasPresent = reference.containsKey(key);
                        var expected = reference.get(key);
                        var actual = cache.get(key);
                        assert Objects.equals(actual, expected)
                                : "get mismatch capacity=" + cap + " key=" + key;
                        if (wasPresent) {
                            expectedHits++;
                        } else {
                            expectedMisses++;
                        }
                    }
                    case 2 -> {
                        var expected = reference.remove(key);
                        var actual = cache.remove(key);
                        assert Objects.equals(actual, expected)
                                : "remove mismatch capacity=" + cap + " key=" + key;
                    }
                    case 3 -> {
                        assert cache.containsKey(key) == reference.containsKey(key)
                                : "contains mismatch capacity=" + cap + " key=" + key;
                    }
                    case 4 -> {
                        reference.clear();
                        cache.clear();
                    }
                    default -> {
                        var defaultValue = "default-" + key;
                        var wasPresent = reference.containsKey(key);
                        var expected = reference.getOrDefault(key, defaultValue);
                        var actual = cache.getOrDefault(key, defaultValue);
                        assert Objects.equals(actual, expected)
                                : "getOrDefault mismatch capacity=" + cap + " key=" + key;
                        if (wasPresent) {
                            expectedHits++;
                        } else {
                            expectedMisses++;
                        }
                    }
                }

                assert cache.size() == reference.size()
                        : "size mismatch capacity=" + cap + " op=" + i;

                var actualEntries = new ArrayList<>(cache.snapshotLeastRecentFirst().entrySet());
                var expectedEntries = new ArrayList<>(reference.entrySet());
                assert actualEntries.equals(expectedEntries)
                        : "order mismatch capacity=" + cap + " op=" + i
                        + " expected=" + expectedEntries + " actual=" + actualEntries;

                var stats = cache.stats();
                assert stats.hits() == expectedHits : stats;
                assert stats.misses() == expectedMisses : stats;
                cache.assertHealthyForTests();
            }
        }
    }

    private static void virtualThreadConcurrencyTest() throws Exception {
        var cache = LruCacheGpt55.<Integer, Integer>builder()
                .capacity(128)
                .fairLock(false)
                .build();

        var virtualThreads = 300;
        var operationsPerThread = 1_000;
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var thread = 0; thread < virtualThreads; thread++) {
                final var threadId = thread;
                futures.add(executor.submit(() -> {
                    var rnd = new SplittableRandom(0xC0FFEE_0000L + threadId);
                    start.await();

                    for (var i = 0; i < operationsPerThread; i++) {
                        var key = rnd.nextInt(512);
                        switch (rnd.nextInt(5)) {
                            case 0 -> cache.put(key, rnd.nextInt());
                            case 1 -> cache.get(key);
                            case 2 -> cache.remove(key);
                            case 3 -> cache.containsKey(key);
                            default -> {
                                if ((i & 255) == 0) {
                                    cache.snapshotMostRecentFirst();
                                } else {
                                    cache.getOrDefault(key, -1);
                                }
                            }
                        }
                    }
                    return null;
                }));
            }

            start.countDown();

            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }

        assert cache.size() <= cache.capacity();
        var snapshot = cache.snapshotMostRecentFirst();
        assert snapshot.size() == cache.size();
        assert !snapshot.containsKey(null);
        assert cache.stats().requests() > 0;
        cache.assertHealthyForTests();
    }

    private static void expectThrows(Class<? extends Throwable> expectedType, ThrowingRunnable action) throws Exception {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expectedType.isInstance(actual)) {
                return;
            }
            throw new AssertionError("Expected " + expectedType.getName() + " but got " + actual, actual);
        }
        throw new AssertionError("Expected " + expectedType.getName() + " but nothing was thrown");
    }
}
