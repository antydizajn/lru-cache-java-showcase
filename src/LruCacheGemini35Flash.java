import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A production-grade, thread-safe, bounded Least Recently Used (LRU) Cache.
 * Built for Java 21+ using modern features such as records, pattern matching,
 * enhanced switches, and virtual threads.
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 */
public final class LruCacheGemini35Flash<K, V> {

    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReentrantLock lock = new ReentrantLock();

    // Cache Statistics
    private long hits = 0;
    private long misses = 0;
    private long puts = 0;
    private long evictions = 0;

    /**
     * Cache statistics snapshot.
     */
    public record CacheStats(long hits, long misses, long puts, long evictions, double hitRate) {
        public CacheStats {
            if (hits < 0 || misses < 0 || puts < 0 || evictions < 0) {
                throw new IllegalArgumentException("Stats cannot be negative");
            }
        }
    }

    /**
     * Internal doubly-linked list node.
     */
    private static final class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private LruCacheGemini35Flash(Builder<K, V> builder) {
        this.capacity = builder.capacity;
        this.map = new HashMap<>(builder.capacity);
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        this.head.next = this.tail;
        this.tail.prev = this.head;
    }

    /**
     * Creates a new Builder instance.
     */
    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    /**
     * Builder for {@link LruCacheGemini35Flash}.
     */
    public static final class Builder<K, V> {
        private int capacity = 16;

        public Builder<K, V> capacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("Capacity must be greater than 0");
            }
            this.capacity = capacity;
            return this;
        }

        public LruCacheGemini35Flash<K, V> build() {
            return new LruCacheGemini35Flash<>(this);
        }
    }

    /**
     * Retrieves the value associated with the key.
     * Marks the key as most recently used.
     *
     * @param key the key whose associated value is to be returned
     * @return the value, or {@code null} if the key is not present
     * @throws NullPointerException if the key is null
     */
    public V get(K key) {
        Objects.requireNonNull(key, "Key cannot be null");
        lock.lock();
        try {
            var node = map.get(key);
            if (node == null) {
                misses++;
                return null;
            }
            moveToHead(node);
            hits++;
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Associates the specified value with the specified key.
     * Marks the key as most recently used. If the cache is at capacity,
     * the least recently used entry is evicted.
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @throws NullPointerException if key or value is null
     */
    public void put(K key, V value) {
        Objects.requireNonNull(key, "Key cannot be null");
        Objects.requireNonNull(value, "Value cannot be null");
        lock.lock();
        try {
            var node = map.get(key);
            if (node != null) {
                node.value = value;
                moveToHead(node);
                puts++;
            } else {
                if (map.size() >= capacity) {
                    var evicted = removeTail();
                    if (evicted != null) {
                        map.remove(evicted.key);
                        evictions++;
                    }
                }
                var newNode = new Node<>(key, value);
                addFirst(newNode);
                map.put(key, newNode);
                puts++;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes the mapping for the specified key from this cache if present.
     *
     * @param key key whose mapping is to be removed from the cache
     * @return the previous value associated with key, or null if there was no mapping
     * @throws NullPointerException if the key is null
     */
    public V remove(K key) {
        Objects.requireNonNull(key, "Key cannot be null");
        lock.lock();
        try {
            var node = map.remove(key);
            if (node != null) {
                removeNode(node);
                return node.value;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Clears all mappings from the cache.
     */
    public void clear() {
        lock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current number of key-value mappings in this cache.
     */
    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the maximum capacity of this cache.
     */
    public int capacity() {
        return this.capacity;
    }

    /**
     * Returns a snapshot of the cache statistics.
     */
    public CacheStats stats() {
        lock.lock();
        try {
            long totalLookups = hits + misses;
            double hitRate = totalLookups == 0 ? 0.0 : (double) hits / totalLookups;
            return new CacheStats(hits, misses, puts, evictions, hitRate);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns keys in order from Most Recently Used (MRU) to Least Recently Used (LRU).
     * Primarily used for testing and debugging.
     */
    public List<K> keysInOrder() {
        lock.lock();
        try {
            var list = new ArrayList<K>(map.size());
            var curr = head.next;
            while (curr != tail) {
                list.add(curr.key);
                curr = curr.next;
            }
            return list;
        } finally {
            lock.unlock();
        }
    }

    // --- Doubly Linked List Helper Methods (Must be called under lock) ---

    private void addFirst(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addFirst(node);
    }

    private Node<K, V> removeTail() {
        var res = tail.prev;
        if (res == head) {
            return null;
        }
        removeNode(res);
        return res;
    }

    // --- Internal State Validation for Testing ---

    void validateInternalState() {
        lock.lock();
        try {
            int actualSize = map.size();
            assert actualSize <= capacity : "Size " + actualSize + " exceeds capacity " + capacity;

            var forwardList = new ArrayList<Node<K, V>>();
            var curr = head.next;
            while (curr != tail) {
                assert curr != null : "Null node encountered before tail";
                forwardList.add(curr);
                curr = curr.next;
            }

            var backwardList = new ArrayList<Node<K, V>>();
            curr = tail.prev;
            while (curr != head) {
                assert curr != null : "Null node encountered before head";
                backwardList.add(curr);
                curr = curr.prev;
            }
            Collections.reverse(backwardList);

            assert forwardList.size() == actualSize : "Forward list size " + forwardList.size() + " doesn't match map size " + actualSize;
            assert backwardList.size() == actualSize : "Backward list size " + backwardList.size() + " doesn't match map size " + actualSize;
            assert forwardList.equals(backwardList) : "Forward and backward traversals do not match";

            for (var node : forwardList) {
                assert map.containsKey(node.key) : "Map missing key: " + node.key;
                assert map.get(node.key) == node : "Map node mismatch for key: " + node.key;
            }
        } finally {
            lock.unlock();
        }
    }

    // =========================================================================
    // TEST HARNESS
    // =========================================================================

    private sealed interface CacheOp<K, V> {
        record PutOp<K, V>(K key, V value) implements CacheOp<K, V> {}
        record GetOp<K, V>(K key) implements CacheOp<K, V> {}
        record RemoveOp<K, V>(K key) implements CacheOp<K, V> {}
    }

    private static class ReferenceLruCache<K, V> {
        private final int capacity;
        private final LinkedHashMap<K, V> map;

        ReferenceLruCache(int capacity) {
            this.capacity = capacity;
            this.map = new LinkedHashMap<>(capacity, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > ReferenceLruCache.this.capacity;
                }
            };
        }

        synchronized V get(K key) {
            return map.get(key);
        }

        synchronized void put(K key, V value) {
            map.put(key, value);
        }

        synchronized V remove(K key) {
            return map.remove(key);
        }

        synchronized List<K> keysInOrder() {
            var keys = new ArrayList<>(map.keySet());
            Collections.reverse(keys);
            return keys;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        boolean assertionsEnabled = false;
        assert assertionsEnabled = true; // Intentional side-effect
        if (!assertionsEnabled) {
            System.err.println("ERROR: Assertions must be enabled! Run with 'java -ea LruCacheGemini35Flash'");
            System.exit(1);
        }

        System.out.println("Starting LruCacheGemini35Flash Test Suite...");

        runSmokeTest();
        runPropertyTest();
        runConcurrencyTest();

        System.out.println("ALL TESTS PASSED SUCCESSFULLY!");
    }

    private static void runSmokeTest() {
        System.out.println("Running smoke test...");
        var cache = LruCacheGemini35Flash.<String, Integer>builder().capacity(3).build();

        cache.put("A", 1);
        cache.put("B", 2);
        cache.put("C", 3);
        cache.validateInternalState();

        assert cache.get("A") == 1;
        assert cache.keysInOrder().equals(List.of("A", "C", "B"));

        cache.put("D", 4); // Evicts B
        cache.validateInternalState();
        assert cache.get("B") == null;
        assert cache.keysInOrder().equals(List.of("D", "A", "C"));

        cache.remove("A");
        cache.validateInternalState();
        assert cache.get("A") == null;
        assert cache.keysInOrder().equals(List.of("D", "C"));

        cache.clear();
        cache.validateInternalState();
        assert cache.size() == 0;
        assert cache.keysInOrder().isEmpty();

        // Null checks
        try {
            cache.put(null, 1);
            assert false : "Expected NullPointerException";
        } catch (NullPointerException _) {}

        try {
            cache.put("A", null);
            assert false : "Expected NullPointerException";
        } catch (NullPointerException _) {}

        try {
            cache.get(null);
            assert false : "Expected NullPointerException";
        } catch (NullPointerException _) {}

        // Capacity 1 Edge Case
        var tinyCache = LruCacheGemini35Flash.<String, Integer>builder().capacity(1).build();
        tinyCache.put("A", 1);
        assert tinyCache.get("A") == 1;
        tinyCache.put("B", 2);
        assert tinyCache.get("A") == null;
        assert tinyCache.get("B") == 2;
        tinyCache.validateInternalState();

        System.out.println("Smoke test passed!");
    }

    private static void runPropertyTest() {
        System.out.println("Running property-based differential test vs LinkedHashMap...");
        var random = ThreadLocalRandom.current();
        var capacity = 10;
        var cache = LruCacheGemini35Flash.<Integer, String>builder().capacity(capacity).build();
        var ref = new ReferenceLruCache<Integer, String>(capacity);

        for (int i = 0; i < 20_000; i++) {
            int opType = random.nextInt(3);
            int key = random.nextInt(25); // Contended key space to trigger evictions/hits
            String val = "val-" + key;

            CacheOp<Integer, String> op = switch (opType) {
                case 0 -> new CacheOp.PutOp<>(key, val);
                case 1 -> new CacheOp.GetOp<>(key);
                case 2 -> new CacheOp.RemoveOp<>(key);
                default -> throw new IllegalStateException("Unexpected value: " + opType);
            };

            switch (op) {
                case CacheOp.PutOp<Integer, String> put -> {
                    cache.put(put.key(), put.value());
                    ref.put(put.key(), put.value());
                }
                case CacheOp.GetOp<Integer, String> get -> {
                    var res1 = cache.get(get.key());
                    var res2 = ref.get(get.key());
                    assert Objects.equals(res1, res2) : "Mismatch on get(" + get.key() + "): " + res1 + " vs " + res2;
                }
                case CacheOp.RemoveOp<Integer, String> remove -> {
                    var res1 = cache.remove(remove.key());
                    var res2 = ref.remove(remove.key());
                    assert Objects.equals(res1, res2) : "Mismatch on remove(" + remove.key() + "): " + res1 + " vs " + res2;
                }
            }

            if (i % 100 == 0) {
                cache.validateInternalState();
                assert cache.size() == ref.keysInOrder().size() : "Size mismatch: " + cache.size() + " vs " + ref.keysInOrder().size();
                assert cache.keysInOrder().equals(ref.keysInOrder()) : "Order mismatch!\nCache: " + cache.keysInOrder() + "\nRef:   " + ref.keysInOrder();
            }
        }
        System.out.println("Property-based differential test passed!");
    }

    private static void runConcurrencyTest() throws InterruptedException {
        System.out.println("Running concurrency test with virtual threads...");
        var capacity = 100;
        var cache = LruCacheGemini35Flash.<Integer, Integer>builder().capacity(capacity).build();
        int threadCount = 1000;
        int opsPerThread = 1000;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var latch = new CountDownLatch(threadCount);
            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                executor.submit(() -> {
                    try {
                        var random = ThreadLocalRandom.current();
                        for (int j = 0; j < opsPerThread; j++) {
                            int key = random.nextInt(200); // High contention
                            if (random.nextBoolean()) {
                                cache.put(key, threadId * 1000 + j);
                            } else {
                                cache.get(key);
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
        }

        // Final validation of state consistency
        cache.validateInternalState();
        var keys = cache.keysInOrder();
        var size = cache.size();
        assert size <= capacity : "Size exceeded capacity: " + size;
        assert keys.size() == size : "Key list size " + keys.size() + " doesn't match cache size " + size;

        var uniqueKeys = new HashSet<>(keys);
        assert uniqueKeys.size() == keys.size() : "Duplicate keys found in LRU list: " + keys;

        var stats = cache.stats();
        System.out.println("Concurrency test passed! Final stats: " + stats);
    }
}
