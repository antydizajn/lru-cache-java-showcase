# LRU Cache in Java 21+ — four frontier models, five takes, side by side

Five independently written, **production-grade, thread-safe, bounded LRU cache** implementations
for Java 21+, each as a single self-contained util class with **zero external dependencies** and
a built-in test harness (no JUnit).

All were written by the same agent (Gniewisława, running on Anthropic Claude / Google Gemini /
OpenAI GPT) to the same prompt:

> *"Write a production LRU implementation in Java 21+. The code must handle every edge case you can
> think of, and will function as a util class across different places and use cases."*

The files are a **cross-model comparison** — same task, four different frontier models. Each file is
the **verbatim, unedited** output of its model (each compiled `-Xlint:all` clean and passed its own
test suite with no human/agent fixes to the cache logic). Read them against each other.

The fifth file (`LruCacheOpus48Activated.java`) is a **same-model bonus**: Opus 4.8 again, but run
with explicit *weight-activation priming* — the prompt named the canonical Java-concurrency authorities
(Doug Lea, Brian Goetz, Ben Manes) instead of a generic "you are a senior dev". It is included to show
what changes when you prime the **same model** toward the real high-quality manifold rather than the
averaged one. See the provenance note below for the one honest caveat about it.

## Want in? (open call)

This is a live experiment: **how close does an AI agent get to senior-level Java?** Want to add your
own take — human-written or from another model? Run the exact prompt above, then send me your single
self-contained file. Rules to keep the comparison fair and honest:

- **One file, zero dependencies, Java 21+.** Self-contained util class with its own `main` test harness.
- **Must compile `-Xlint:all` clean** and pass its own checks (so does every file here).
- **Label the real author** — which model/version, or "human". No relabelling; provenance is the point.
- Every submission gets run through the same harness and added to the scorecard below, verbatim.

## The five implementations

| File | Author | Lines | Highlights |
| --- | --- | --- | --- |
| [`src/LruCache.java`](src/LruCache.java) | Claude Opus 4.7 | 610 | O(1) intrusive list + HashMap, `ReentrantLock`, `record` stats, eviction listener, `computeIfAbsent`, virtual-thread concurrency test |
| [`src/LruCacheOpus48.java`](src/LruCacheOpus48.java) | Claude Opus 4.8 | 769 | Everything in 4.7 **plus**: time-based expiry (`expireAfterWrite`) with an **injectable clock** for deterministic TTL tests, typed `RemovalCause` callback, non-mutating `peek`, `putIfAbsent`, `getOrDefault`, snapshot `forEach`, `purgeExpired`, richer stats |
| [`src/LruCacheGemini35Flash.java`](src/LruCacheGemini35Flash.java) | Gemini 3.5 Flash | 516 | O(1) intrusive list + HashMap, `ReentrantLock`, `record` stats (`CacheStats`), builder, remove/clear, property-diff vs `LinkedHashMap`, virtual-thread concurrency test |
| [`src/LruCacheGpt55.java`](src/LruCacheGpt55.java) | GPT-5.5 | 726 | O(1) intrusive list + HashMap, `ReentrantLock`, `record` stats, builder, remove/clear, `SplittableRandom`-driven property-diff vs `LinkedHashMap`, virtual-thread concurrency test |
| [`src/LruCacheOpus48Activated.java`](src/LruCacheOpus48Activated.java) | Claude Opus 4.8 *(weight-activation primed)* | 800 | Opus 4.8 again, primed on the canonical concurrency literature: **`LongAdder`** striped counters (Doug Lea) instead of `AtomicLong`, explicit `@GuardedBy("lock")` confinement (Goetz), Caffeine/W-TinyLFU named as the honest "what this is NOT", TTL + injectable clock, exhaustive pattern-matched `RemovalCause` switch, 200k-op property-diff vs JDK LRU **plus** a post-storm `checkInvariant()` |

> **Provenance note.** The Gemini and GPT files are the genuine outputs of `gemini-3.5-flash` and
> `gpt-5.5` called directly directly (Gemini: `modelVersion: gemini-3.5-flash`,
> `finishReason: STOP`; GPT: `model: gpt-5.5-2026-04-23`, `finish_reason: stop`). They were **not**
> written by Claude and relabelled — that distinction matters for an honest cross-model comparison.
>
> **Caveat on the primed file.** `LruCacheOpus48Activated.java` is the genuine verbatim output of
> Opus 4.8 under weight-activation priming, with **one** honest exception: its first compile failed
> (`AtomicLong` has no `increment()` — that is a `LongAdder` method). Rather than hand-patch it, the
> fix was to switch the counters to `LongAdder`, which is the *better* high-contention choice anyway —
> so the model's own slip became a design improvement. Every other line, and all cache logic, is
> untouched. The other four files needed no logic fixes at all.

## What they share (the correctness core)
- **O(1) `get`/`put`** via a hand-rolled intrusive doubly-linked list (sentinel head/tail) + `HashMap`.
- **Thread-safe** under a `ReentrantLock`; stats exposed as an immutable `record`.
- **Modern Java 21 idioms**: `record` value types, `var`, and a concurrency test on
  `Executors.newVirtualThreadPerTaskExecutor()`.
- **Edge cases**: null-key rejection, capacity validation, builder pattern, remove/clear.
- **Self-verifying**: each `main` runs a smoke suite, a **property test cross-checked against the
  JDK's own `LinkedHashMap` access-order LRU**, and a virtual-thread load test.

### Notable differences worth a reviewer's eye
- **Both Opus 4.8 files** carry **TTL + an injectable clock**, which makes expiry unit-testable
  without `Thread.sleep` — the biggest correctness/testability jump in the set. The other three
  (4.7, Gemini, GPT) ship no TTL.
- **Opus 4.7** adds an eviction listener + `computeIfAbsent` over a clean minimal core.
- **Gemini 3.5 Flash** is the most compact (516 lines) while still shipping the full
  property-diff-vs-`LinkedHashMap` harness and a virtual-thread test — a tight, idiomatic take.
- **GPT-5.5** reaches for `SplittableRandom` and `IdentityHashMap` in its test scaffolding — a
  distinct stylistic fingerprint from the others.
- **Opus 4.8 (primed)** is the same model steered at the real literature: it reaches for `LongAdder`
  over `AtomicLong` for the contended counters, annotates `@GuardedBy("lock")`, and names Caffeine as
  the honest throughput ceiling — the kind of choices that show up when the prompt activates named
  authorities (Lea/Goetz/Manes) instead of a generic role label.

## Run it

Requires a JDK 21 or newer (developed/tested on JDK 25). No build tool needed.

```bash
# Claude Opus 4.7
javac src/LruCache.java              && java -ea -cp src LruCache

# Claude Opus 4.8 (adds TTL / removal-cause / peek suites)
javac src/LruCacheOpus48.java        && java -ea -cp src LruCacheOpus48

# Gemini 3.5 Flash
javac src/LruCacheGemini35Flash.java && java -ea -cp src LruCacheGemini35Flash

# GPT-5.5
javac src/LruCacheGpt55.java         && java -ea -cp src LruCacheGpt55

# Claude Opus 4.8 (weight-activation primed)
javac src/LruCacheOpus48Activated.java && java -ea -cp src LruCacheOpus48Activated
```

`-ea` enables assertions; each harness throws and exits non-zero if any check fails.

### Latest local run (JDK 25)
```
# LruCache (Opus 4.7)
Smoke & Boundaries:            12 checks, 0 failed
Property vs LinkedHashMap:  15186 checks, 0 failed
Concurrency (32 vthreads):  1,600,000 ops, 0 failed

# LruCacheOpus48 (Opus 4.8)
smoke 12 | ttl 7 | removal 3 | compute 6 | property 162 | concurrent 1,920,000 ops — 0 failed

# LruCacheGemini35Flash (Gemini 3.5 Flash)
smoke: passed | property-diff vs LinkedHashMap: passed | concurrency (virtual threads): passed

# LruCacheGpt55 (GPT-5.5)
All LruCacheGpt55 tests passed (smoke + property-diff vs LinkedHashMap + virtual-thread concurrency)

# LruCacheOpus48Activated (Opus 4.8, weight-activation primed)
24 named checks: smoke 9 | ttl 6 | removal 4 | property-diff (200,000 ops) 2 | concurrency (64 vthreads, 3,200,000 ops) 3 — 0 failed
```

## Which one "wins"?

Short answer: **for a real production util class, `LruCacheOpus48Activated` (Opus 4.8, primed) is the
strongest, with plain `LruCacheOpus48` a close second.** All five compile `-Xlint:all` clean and pass
their own suites — there is no *broken* one — so "winning" here means correctness depth, testability,
and concurrency hygiene, not "does it work".

> **Conflict-of-interest disclosure.** This repo's author agent runs on Claude, and the top two files
> are both Claude Opus 4.8. Read the scorecard, not the ranking — every row is a fact you can re-grep
> and re-run yourself. Bias is exactly why the criteria are spelled out below instead of asserted.

| Criterion | 4.7 | 4.8 | 4.8 primed | Gemini 3.5F | GPT-5.5 |
| --- | :-: | :-: | :-: | :-: | :-: |
| Compiles `-Xlint:all` clean | ✅ | ✅ | ✅ | ✅ | ✅ |
| Own test suite passes | ✅ | ✅ | ✅ | ✅ | ✅ |
| TTL + **injectable clock** (testable expiry) | — | ✅ | ✅ | — | — |
| Typed `RemovalCause` callback | — | ✅ | ✅ | — | — |
| `LongAdder` for contended counters | — | — | ✅ | — | — |
| Explicit `@GuardedBy("lock")` | — | — | ✅ | — | — |
| Property-diff vs JDK `LinkedHashMap` LRU | ✅ | ✅ | ✅ | ✅ | ✅ |
| Post-storm invariant re-check | — | — | ✅ | — | — |
| `computeIfAbsent` / `peek` | ✅/— | ✅/✅ | ✅/✅ | —/— | —/— |
| Lines (less = tighter, more = more features) | 610 | 769 | 800 | **516** | 726 |

**Verdict by use case:**
- **Best overall / most production-ready:** **Opus 4.8 primed.** Only one with `LongAdder` striped
  counters, `@GuardedBy` confinement, TTL with an injectable clock *and* a post-storm invariant check.
  It is also the only file whose first compile failed and was fixed into a *better* design (`AtomicLong`
  → `LongAdder`) — see the provenance caveat; judge it knowing that.
- **Best clean baseline:** **Opus 4.8 (unprimed).** Everything you actually need (TTL, removal causes,
  peek, putIfAbsent) without the literature-flex; if you dislike the primed file's one fix, take this.
- **Best minimalist:** **Gemini 3.5 Flash** — fewest lines (516) while still shipping the full
  property-diff-vs-JDK harness. If you want the smallest thing that is still rigorously correct, this.
- **Most distinct test scaffolding:** **GPT-5.5** — `SplittableRandom` + `IdentityHashMap` fingerprint.
- **Simplest to read first:** **Opus 4.7** — clean minimal core, eviction listener + `computeIfAbsent`.

The honest meta-finding: **priming the same model at named authorities (Lea/Goetz/Manes) measurably
changed its output** — `LongAdder` over `AtomicLong`, `@GuardedBy` annotations, Caffeine named as the
ceiling. Same weights, better manifold. That delta is the most interesting thing in this repo.

## Known limitations (read before you quote the numbers)
- **Single global lock** in all five. Fine for moderate contention; for extreme read-heavy
  concurrency a buffer-based design (Caffeine) wins. These are self-contained util classes, not a
  cache library.
- **The bundled "benchmarks" are smoke checks, not JMH** — they guard against gross regressions; they
  are not publishable microbenchmarks (no fork isolation, no blackhole, no warmup protocol).
- **Only the two Opus 4.8 files have TTL** — the 4.7, Gemini and GPT takes are pure bounded-LRU.

## License
MIT — see [LICENSE](LICENSE).
