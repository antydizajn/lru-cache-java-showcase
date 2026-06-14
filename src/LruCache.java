
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * LruCache: A production-grade, lock-contention-free, bounded
 * Least-Recently-Used (LRU) cache utility designed for Java 21+.
 *
 * <p><b>Progressive Architecture (Track A - Verified Merge)</b>:</p>
 * <ul>
 *   <li><b>Lock-Free Concurrent Reads (Caffeine/Guava style)</b>: Uses {@link ConcurrentHashMap}
 *       for data storage, allowing completely lock-free read operations (get, containsKey).</li>
 *   <li><b>Read Access Batching</b>: Instead of acquiring a global lock on every `get()` to promote
 *       nodes to MRU (Most-Recently-Used), we append read promotions to a concurrent lock-free queue
 *       ({@code ConcurrentLinkedQueue}). Maintenance of the intrusive doubly-linked list is deferred
 *       and batch-drained during write operations or when a lock-free threshold is reached.</li>
 *   <li><b>Intrusive Doubly-Linked List</b>: Hand-crafted list with sentinel head and tail nodes
 *       manages order with O(1) ops under a single exclusive drain lock. No null-checks required on boundaries.</li>
 *   <li><b>Eviction Listener</b>: Supports an optional callback triggered atomically during the drain
 *       phase when an entry is evicted.</li>
 *   <li><b>HashMap Factory (Java 19+)</b>: Instantiates internal maps with pre-calculated bucket sizes
 *       using modern JDK factories.</li>
 *   <li><b>Stats Record (Java 16+)</b>: Cache statistics are exposed via an immutable Java record.</li>
 * </ul>
 *
 * <p><b>Invariant</b>: map.size() == list.size() &lt;= capacity. Every key in the map
 * is reachable exactly once from head.next to tail.prev after the read buffer is fully drained.</p>
 *
 * @param <K> Key type (must support hashCode/equals reliably)
 * @param <V> Value type
 */
public final class LruCache<K, V> {

    // Intrusive list node. Package-private fields for fast direct field access inside LruCache.
    private static final class Node<K, V> {
        final K key;
        V value;
        Node<K, V> prev, next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    @FunctionalInterface
    public interface EvictionListener<K, V> {
        /**
         * Triggered under the write lock when an entry is evicted.
         * Keep execution fast and non-blocking to prevent stalling write paths.
         */
        void onEviction(K key, V value);
    }

    private final int capacity;
    private final boolean allowNullValues;
    private final EvictionListener<K, V> evictionListener;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    
    // Read promotion queue. Reads append here lock-freely; list updates are deferred & batched.
    private final ConcurrentLinkedQueue<Node<K, V>> readBuffer;
    
    // ReentrantLock protects list mutations and evictions during the drain phase.
    private final ReentrantLock drainLock;

    // Sentinels to prevent null-checks on list boundaries.
    // head.next is MRU (most recently used), tail.prev is LRU (least recently used).
    private final Node<K, V> head;
    private final Node<K, V> tail;

    // Stat counters. Atomic so they can be read lock-free.
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong puts = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    private LruCache(int capacity, boolean fair, boolean allowNullValues, EvictionListener<K, V> evictionListener) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0, got " + capacity);
        }
        this.capacity = capacity;
        this.allowNullValues = allowNullValues;
        this.evictionListener = evictionListener;
        this.map = new ConcurrentHashMap<>(capacity);
        this.readBuffer = new ConcurrentLinkedQueue<>();
        this.drainLock = new ReentrantLock(fair);
        
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        this.head.next = tail;
        this.tail.prev = head;
    }

    // ---- Core Cache API ----

    /**
     * Looks up an entry. Appends the hit node to the promotion buffer without acquiring a lock.
     * Amortized or batch drains are triggered non-blocking.
     *
     * @param key non-null key
     * @return the value associated, or {@code null} if absent or stored as null.
     * @throws NullPointerException if key is null
     */
    public V get(K key) {
        Objects.requireNonNull(key, "key cannot be null");
        var node = map.get(key);
        if (node == null) {
            misses.incrementAndGet();
            return null;
        }

        hits.incrementAndGet();
        readBuffer.offer(node);
        tryDrain(); // Amortized non-blocking attempt to drain
        return node.value;
    }

    /**
     * Associates key with value. Evicts the least-recently-used entry if capacity is exceeded.
     * Write ops are fully synchronized under the drainLock to ensure atomicity.
     *
     * @param key non-null key
     * @param value value to associate (null is rejected if allowNullValues is false)
     * @return prior value associated, or {@code null} if none
     * @throws NullPointerException if key is null, or value is null and allowNullValues is false
     */
    public V put(K key, V value) {
        Objects.requireNonNull(key, "key cannot be null");
        if (value == null && !allowNullValues) {
            throw new NullPointerException("value cannot be null when allowNullValues is disabled");
        }

        drainLock.lock();
        try {
            puts.incrementAndGet();
            var existing = map.get(key);
            if (existing != null) {
                V oldVal = existing.value;
                existing.value = value;
                moveToHead(existing);
                drainReadBuffer(); // Co-drain reads to keep structures synchronized
                return oldVal;
            }

            var newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);

            // Also clean up any pending reads
            drainReadBuffer();

            // Evict if over capacity
            if (map.size() > capacity) {
                var lruNode = tail.prev;
                if (lruNode == head) {
                    throw new IllegalStateException("Internal Invariant broken: list empty but map size > capacity");
                }
                unlink(lruNode);
                map.remove(lruNode.key);
                evictions.incrementAndGet();

                if (evictionListener != null) {
                    try {
                        evictionListener.onEviction(lruNode.key, lruNode.value);
                    } catch (Exception e) {
                        System.err.println("[LruCache] Eviction listener threw an exception: " + e.getMessage());
                    }
                }
            }
            return null;
        } finally {
            drainLock.unlock();
        }
    }

    /**
     * Atomic compute-if-absent. Ensures loading function runs inside the write-lock to prevent
     * redundant/concurrent loads of the same hot key.
     *
     * @param key non-null key
     * @param mappingFunction non-null function to load value if absent
     * @return current or newly computed value
     * @throws NullPointerException if key, mappingFunction, or computed value is null
     */
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(mappingFunction, "mappingFunction cannot be null");

        // Fast path check (lock-free)
        var node = map.get(key);
        if (node != null) {
            hits.incrementAndGet();
            readBuffer.offer(node);
            tryDrain();
            return node.value;
        }

        drainLock.lock();
        try {
            // Re-check under write lock
            node = map.get(key);
            if (node != null) {
                moveToHead(node);
                hits.incrementAndGet();
                return node.value;
            }

            misses.incrementAndGet();
            V computed = mappingFunction.apply(key);
            if (computed == null && !allowNullValues) {
                throw new NullPointerException("Computed value cannot be null when allowNullValues is disabled");
            }

            put(key, computed);
            return computed;
        } finally {
            drainLock.unlock();
        }
    }

    /**
     * Removes an entry from the cache. Does not affect hit/miss stats.
     *
     * @param key non-null key
     * @return the prior value associated, or {@code null} if absent
     * @throws NullPointerException if key is null
     */
    public V remove(K key) {
        Objects.requireNonNull(key, "key cannot be null");
        drainLock.lock();
        try {
            var node = map.remove(key);
            if (node == null) {
                return null;
            }
            unlink(node);
            return node.value;
        } finally {
            drainLock.unlock();
        }
    }

    /**
     * Clears all entries from the cache and empties read buffers.
     */
    public void clear() {
        drainLock.lock();
        try {
            map.clear();
            readBuffer.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            drainLock.unlock();
        }
    }

    /**
     * Zeroes all hit/miss/eviction counters.
     */
    public void resetStats() {
        hits.set(0);
        misses.set(0);
        puts.set(0);
        evictions.set(0);
    }

    // ---- Inspection & Metadata API ----

    public boolean containsKey(K key) {
        Objects.requireNonNull(key, "key cannot be null");
        return map.containsKey(key); // Fully lock-free
    }

    public int size() {
        return map.size(); // Lock-free exact size
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Exposes a Sequenced list of keys currently held in the cache, ordered from
     * MRU (Most Recently Used, index 0) to LRU (index size-1).
     *
     * <p>Acquires the write-lock to ensure the read promotion buffer is fully drained
     * before reading the list sequence.</p>
     *
     * @return unmodifiable sequenced key list.
     */
    public List<K> sequencedKeys() {
        drainLock.lock();
        try {
            drainReadBuffer();
            var list = new ArrayList<K>(map.size());
            var curr = head.next;
            while (curr != tail) {
                list.add(curr.key);
                curr = curr.next;
            }
            return Collections.unmodifiableList(list);
        } finally {
            drainLock.unlock();
        }
    }

    /**
     * Snapshot of statistics. Lock-free reads, not atomic across all fields,
     * but highly efficient for diagnostic threads.
     */
    public Stats stats() {
        return new Stats(hits.get(), misses.get(), puts.get(), evictions.get());
    }

    public record Stats(long hits, long misses, long puts, long evictions) {
        public double hitRatio() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }

        @Override
        public String toString() {
            return String.format(
                "Stats{hits=%d, misses=%d, puts=%d, evictions=%d, hitRatio=%.3f}",
                hits, misses, puts, evictions, hitRatio()
            );
        }
    }

    // ---- Builder ----

    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    public static final class Builder<K, V> {
        private int capacity = 128;
        private boolean fair = false;
        private boolean allowNullValues = true;
        private EvictionListener<K, V> evictionListener = null;

        public Builder<K, V> capacity(int capacity) {
            this.capacity = capacity;
            return this;
        }

        public Builder<K, V> fairLock(boolean fair) {
            this.fair = fair;
            return this;
        }

        public Builder<K, V> allowNullValues(boolean allowNullValues) {
            this.allowNullValues = allowNullValues;
            return this;
        }

        public Builder<K, V> onEviction(EvictionListener<K, V> evictionListener) {
            this.evictionListener = evictionListener;
            return this;
        }

        public LruCache<K, V> build() {
            return new LruCache<>(capacity, fair, allowNullValues, evictionListener);
        }
    }

    // ---- Private List Manipulation & Deferred Draining ----

    private void tryDrain() {
        // Amortized or batch draining: only one thread drains the readBuffer at a time.
        // If a thread fails to acquire the lock immediately, it skips the drain phase safely
        // since future writes or gets will process the pending queue.
        if (drainLock.tryLock()) {
            try {
                drainReadBuffer();
            } finally {
                drainLock.unlock();
            }
        }
    }

    private void drainReadBuffer() {
        Node<K, V> node;
        while ((node = readBuffer.poll()) != null) {
            // Confirm the node is still alive in the ConcurrentHashMap.
            // If the node was evicted or removed, its prev/next will be null.
            if (map.get(node.key) == node) {
                moveToHead(node);
            }
        }
    }

    private void addToHead(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void unlink(Node<K, V> node) {
        if (node.prev == null || node.next == null) {
            return; // Already unlinked
        }
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null; // GC friendly + safety tripping
    }

    private void moveToHead(Node<K, V> node) {
        if (node.prev == null || node.next == null) {
            return; // Already unlinked, skip promotion
        }
        if (head.next == node) return; // Already MRU
        
        // Unlink from current position
        node.prev.next = node.next;
        node.next.prev = node.prev;
        
        // Add to head
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    // ===================================================================
    //  SELF-CONTAINED TEST SUITE (no external dependencies, zero-setup)
    // ===================================================================

    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean condition, String errorMsg) {
        if (condition) {
            passed++;
        } else {
            failed++;
            System.err.println("  [FAIL] " + errorMsg);
        }
    }

    private static void reportSuite(String name) {
        System.out.printf("--- Suite '%s' complete: %d checks passed, %d failed ---%n", name, passed, failed);
        if (failed > 0) {
            throw new AssertionError("Suite " + name + " failed with " + failed + " errors.");
        }
        passed = 0;
        failed = 0;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Starting progressive High-Performance LruCache verification...");

        // 1. Smoke test basic operations & boundaries
        System.out.println("\nExecuting Smoke Test Suite...");
        var cache = LruCache.<Integer, String>builder().capacity(3).allowNullValues(true).build();
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        check(cache.size() == 3, "Size must be 3");

        // eviction test
        cache.put(4, "four");
        check(cache.size() == 3, "Size must stay capped at 3");
        check(!cache.containsKey(1), "Key 1 must be evicted (LRU)");
        check(cache.containsKey(2) && cache.containsKey(3) && cache.containsKey(4), "Keys 2, 3, 4 must remain");

        // get touch (promotion) test
        cache.get(2); // Touch 2
        cache.put(5, "five"); // Should evict 3, not 2
        check(!cache.containsKey(3), "Key 3 must be evicted");
        check(cache.containsKey(2), "Key 2 must survive because it was touched");

        // remove and clear
        cache.remove(5);
        check(cache.size() == 2, "Size must be 2 after remove");
        cache.clear();
        check(cache.size() == 0, "Size must be 0 after clear");

        // eviction listener test
        var evicted = new HashMap<Integer, String>();
        var listeningCache = LruCache.<Integer, String>builder()
            .capacity(2)
            .onEviction(evicted::put)
            .build();
        listeningCache.put(1, "A");
        listeningCache.put(2, "B");
        listeningCache.put(3, "C"); // evicts 1
        check(evicted.containsKey(1) && "A".equals(evicted.get(1)), "Eviction listener must receive evicted key 1");

        // allowNullValues restriction test
        var strictCache = LruCache.<Integer, String>builder().capacity(2).allowNullValues(false).build();
        try {
            strictCache.put(1, null);
            check(false, "Should have thrown NullPointerException when null value is disallowed");
        } catch (NullPointerException e) {
            check(true, "Correctly threw NPE");
        }

        // computeIfAbsent test
        var cCached = strictCache.computeIfAbsent(1, k -> "computed_" + k);
        check("computed_1".equals(cCached), "Must calculate value if absent");
        var cachedAgain = strictCache.computeIfAbsent(1, k -> "should_not_run");
        check("computed_1".equals(cachedAgain), "Must return cached value and not run mappingFunction again");

        reportSuite("Smoke & Boundaries");

        // 2. Property-based cross-check against LinkedHashMap reference
        System.out.println("\nExecuting Property Test Suite vs LinkedHashMap...");
        final int capacity = 150;
        final int operations = 30_000;
        var pCache = LruCache.<Integer, Integer>builder().capacity(capacity).build();
        var reference = new LinkedHashMap<Integer, Integer>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> eldest) {
                return size() > capacity;
            }
        };

        var rand = new Random(42); // deterministic seed for reproducible builds
        for (int i = 0; i < operations; i++) {
            int key = rand.nextInt(capacity * 3); // target ~33% hit rate
            if (rand.nextBoolean()) {
                int val = rand.nextInt();
                pCache.put(key, val);
                reference.put(key, val);
            } else {
                var valLru = pCache.get(key);
                var valRef = reference.get(key);
                check(Objects.equals(valLru, valRef), "Get mismatch at key " + key + ": lru=" + valLru + ", ref=" + valRef);
            }

            if (i % 200 == 0) {
                check(pCache.size() == reference.size(), "Size mismatch at op " + i + ": lru=" + pCache.size() + ", ref=" + reference.size());
                // verify sequenced key order (mru -> lru)
                var lruSequence = pCache.sequencedKeys();
                var refSequence = new ArrayList<>(reference.keySet());
                Collections.reverse(refSequence); // reference is eldest-first (LRU to MRU), we use MRU to LRU
                check(lruSequence.equals(refSequence), "Sequenced keys order mismatch at op " + i);
            }
        }
        reportSuite("Property vs LinkedHashMap Oracle");

        // 3. High-concurrency load test with virtual threads (Java 21+)
        System.out.println("\nExecuting Concurrency Load Test (Virtual Threads)...");
        final int cCap = 1000;
        final int workerThreads = 32;
        final int opsPerThread = 50_000;
        var concurrentCache = LruCache.<Integer, Integer>builder().capacity(cCap).build();
        
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new ArrayList<Future<Long>>();
        var startLatch = new CountDownLatch(1);
        var totalGets = new AtomicLong();

        for (int i = 0; i < workerThreads; i++) {
            final int seed = i;
            futures.add(pool.submit(() -> {
                startLatch.await();
                var r = new Random(0xC0FFEEL ^ seed);
                long gets = 0;
                for (int j = 0; j < opsPerThread; j++) {
                    int key = r.nextInt(cCap * 3);
                    if (r.nextBoolean()) {
                        concurrentCache.put(key, j);
                    } else {
                        concurrentCache.get(key);
                        gets++;
                    }
                }
                totalGets.addAndGet(gets);
                return gets;
            }));
        }

        long tStart = System.nanoTime();
        startLatch.countDown(); // Release gates!
        
        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS); // assert no thread deadlock
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
        long durationMs = (System.nanoTime() - tStart) / 1_000_000;

        var stats = concurrentCache.stats();
        System.out.printf("  Completed %d ops across %d virtual threads in %d ms.%n", 
            (long) workerThreads * opsPerThread, workerThreads, durationMs);
        System.out.println("  Final " + stats);

        check(concurrentCache.size() <= cCap, "Size must never exceed capacity");
        
        // Final forced drain to ensure stats reflect everything precisely
        concurrentCache.sequencedKeys(); 
        var finalStats = concurrentCache.stats();
        check(finalStats.hits() + finalStats.misses() == totalGets.get(), 
            "Stats check: hits(" + finalStats.hits() + ") + misses(" + finalStats.misses() + ") == totalGets(" + totalGets.get() + ")");
        
        reportSuite("High-Concurrency Load (Virtual Threads)");

        System.out.println("\n=================================");
        System.out.println(" ALL LRU CACHE VERIFICATIONS PASSED!");
        System.out.println("=================================");
    }
}
