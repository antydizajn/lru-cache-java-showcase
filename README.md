# LRU Cache in Java 21+ — two AI takes, side by side

Two independently written, **production-grade, thread-safe, bounded LRU cache** implementations
for Java 21+, each as a single self-contained util class with **zero external dependencies** and
a built-in test harness (no JUnit).

Both were written by the same agent (Gniewisława, running on Anthropic Claude)
to the same prompt:

> *"Write a production LRU implementation in Java 21+. The code must handle every edge case you can
> think of, and will function as a util class across different places and use cases."*

The two files are a **progress comparison** — same task, two model generations (`claude-4-7-opus`
then `claude-4-8-opus`). They're meant to be read against each other.

## The two implementations

| File | Author | Lines | Highlights |
| --- | --- | --- | --- |
| [`src/LruCache.java`](src/LruCache.java) | Claude Opus 4.7 | ~600 | O(1) intrusive list + HashMap, `ReentrantLock`, `record` stats, eviction listener, `computeIfAbsent`, virtual-thread concurrency test |
| [`src/LruCacheOpus48.java`](src/LruCacheOpus48.java) | Claude Opus 4.8 | ~770 | Everything in v1 **plus**: time-based expiry (`expireAfterWrite`) with an **injectable clock** for deterministic TTL tests, typed `RemovalCause` callback, non-mutating `peek`, `putIfAbsent`, `getOrDefault`, snapshot `forEach`, `purgeExpired`, richer stats (expirations + load success/failure) |

### What both share (the correctness core)
- **O(1) `get`/`put`** via a hand-rolled intrusive doubly-linked list (sentinel head/tail) + `HashMap`.
- **Thread-safe** under a single `ReentrantLock` (opt-in fairness); stats read lock-free via `AtomicLong`.
- **Modern Java 21 idioms**: `record` for the immutable stats value type, `HashMap.newHashMap(n)`
  (JDK 19+) for correct table sizing, `var`, and a concurrency test built on
  `Executors.newVirtualThreadPerTaskExecutor()`.
- **Edge cases**: null-key rejection, configurable null-value policy (ambiguity resolved via
  `containsKey`), capacity validation, an invariant guard that **throws** (not `assert`) on
  list/map desync, builder pattern, and a removal/eviction listener whose exceptions are isolated.
- **Self-verifying**: each file's `main` runs a smoke suite, a **property test cross-checked against
  the JDK's own `LinkedHashMap` access-order LRU**, and a high-concurrency virtual-thread load test.

### What changed from 4.7 → 4.8 (the actual progress)
1. **Time-based expiry that's actually testable.** v2 takes an injectable `LongSupplier` clock, so
   TTL expiry is unit-tested by advancing a fake clock — **no `Thread.sleep`, no flaky timing**.
   This is the single biggest correctness win: expiry logic you can verify deterministically.
2. **Typed `RemovalCause`** (`SIZE` / `EXPIRED` / `EXPLICIT` / `REPLACED`) delivered to the listener,
   so callers can manage resource lifecycles (close sockets, files, connections) correctly.
3. **Richer read API**: `peek` (read without touching recency or stats), `getOrDefault`,
   `putIfAbsent`, and a `forEach` that runs against a consistent snapshot **outside** the lock
   (so the action can safely call back into the cache).
4. **Lazy expiry + `purgeExpired()`** — expired entries are dropped on access and preferentially
   during eviction, with an explicit purge for callers who want eager reclamation. No background
   sweeper thread (a util class must not silently spawn threads).
5. **Honest concurrency scope.** v2's docs state plainly why it uses one global lock rather than
   lock striping (the recency list is a single global structure; striping the map wouldn't remove
   the contention point), and names Caffeine's amortized read/write buffers as the real answer if
   you outgrow a single-lock util class.

## Run it

Requires a JDK 21 or newer (developed/tested on JDK 25). No build tool needed.

```bash
# v1 — Opus 4.7
javac src/LruCache.java && java -ea -cp src LruCache

# v2 — Opus 4.8 (adds the TTL / removal-cause / peek suites)
javac src/LruCacheOpus48.java && java -ea -cp src LruCacheOpus48
```

`-ea` enables assertions; each harness throws and exits non-zero if any check fails.

### Latest local run (JDK 25)
```
# LruCache (4.7)
Smoke & Boundaries:            12 checks, 0 failed
Property vs LinkedHashMap:  15186 checks, 0 failed
Concurrency (32 vthreads):  1,600,000 ops in ~416 ms, 0 failed

# LruCacheOpus48 (4.8)
smoke:                12 checks, 0 failed
ttl (fake clock):      7 checks, 0 failed
removal causes:        3 checks, 0 failed
compute/putIfAbsent:   6 checks, 0 failed
property:            162 checks, 0 failed
concurrent:         1,920,000 ops in ~313 ms, 0 failed
```

## Known limitations (read before you quote the numbers)
- **Single global lock.** Fine for moderate contention; for extreme read-heavy concurrency a
  buffer-based design (Caffeine) wins. This is a self-contained util class, not a cache library.
- **The bundled "benchmark" is a smoke check, not JMH.** It guards against a 10× regression; it is
  not a publishable microbenchmark (no fork isolation, no blackhole, no warmup protocol).
- **v1 has no TTL.** That capability is the headline difference in v2.

## License
MIT — see [LICENSE](LICENSE).
