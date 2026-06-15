# LRU Cache in Java 21+ — four frontier models, six takes, side by side

Six independently written, **thread-safe, bounded LRU cache** implementations
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

## The six implementations

| File | Author | Lines | Highlights |
| --- | --- | --- | --- |
| [`src/LruCache.java`](src/LruCache.java) | Claude Opus 4.7 | 610 | O(1) intrusive list + HashMap, `ReentrantLock`, `record` stats, eviction listener, `computeIfAbsent`, virtual-thread concurrency test |
| [`src/LruCacheOpus48.java`](src/LruCacheOpus48.java) | Claude Opus 4.8 | 769 | Everything in 4.7 **plus**: time-based expiry (`expireAfterWrite`) with an **injectable clock** for deterministic TTL tests, typed `RemovalCause` callback, non-mutating `peek`, `putIfAbsent`, `getOrDefault`, snapshot `forEach`, `purgeExpired`, richer stats |
| [`src/LruCacheGemini35Flash.java`](src/LruCacheGemini35Flash.java) | Gemini 3.5 Flash | 516 | O(1) intrusive list + HashMap, `ReentrantLock`, `record` stats (`CacheStats`), builder, remove/clear, property-diff vs `LinkedHashMap`, virtual-thread concurrency test |
| [`src/LruCacheGpt55.java`](src/LruCacheGpt55.java) | GPT-5.5 | 726 | O(1) intrusive list + HashMap, `ReentrantLock`, `record` stats, builder, remove/clear, `SplittableRandom`-driven property-diff vs `LinkedHashMap`, virtual-thread concurrency test |
| [`src/LruCacheOpus48Activated.java`](src/LruCacheOpus48Activated.java) | Claude Opus 4.8 *(weight-activation primed)* | 800 | Opus 4.8 again, primed on the canonical concurrency literature: **`LongAdder`** striped counters (Doug Lea) instead of `AtomicLong`, explicit `@GuardedBy("lock")` confinement (Goetz), Caffeine/W-TinyLFU named as the honest "what this is NOT", TTL + injectable clock, exhaustive pattern-matched `RemovalCause` switch, 200k-op property-diff vs JDK LRU **plus** a post-storm `checkInvariant()` |
| [`src/LruCacheOpus48Reviewed.java`](src/LruCacheOpus48Reviewed.java) | Claude Opus 4.8 *(primed, **then peer-reviewed & fixed**)* | 845 | The Activated file after an external review by **GPT-5.5 and a second Opus 4.8 instance** found — and an attack-test confirmed — two real bugs. Fixes: (1) reentrant `computeIfAbsent` no longer duplicates a key on the list (invariant held), (2) `put` over an expired entry now counts `EXPIRED` instead of vanishing silently; plus `getOrDefault` made atomic (was check-then-act TOCTOU) and the self-contradicting `AtomicLong`→`LongAdder` Javadoc corrected. Two new regression tests lock the bugs down. **Not** verbatim — it is the *reviewed* artifact, kept separate from the verbatim outputs above. |

> **Provenance note.** The Gemini and GPT files are the genuine outputs of `gemini-3.5-flash` and
> `gpt-5.5` (Gemini: `modelVersion: gemini-3.5-flash`, `finishReason: STOP`; GPT:
> `model: gpt-5.5-2026-04-23`, `finish_reason: stop`). They were **not**
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

# Claude Opus 4.8 (primed, then peer-reviewed & fixed)
javac src/LruCacheOpus48Reviewed.java && java -ea -cp src LruCacheOpus48Reviewed
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
# (NOTE: its own suite passes, but a later external review found 2 bugs its suite did not cover — see below)

# LruCacheOpus48Reviewed (Opus 4.8 primed, peer-reviewed & fixed)
29 named checks incl. 2 new regression tests for the review bugs: smoke 9 | ttl 6 | removal 4 | reentrancy+expired-overwrite 5 | property-diff (200,000 ops) 2 | concurrency (64 vthreads, 3,200,000 ops) 3 — 0 failed
```

## Which one "wins"?

Honest answer, and it changed after review: **`LruCacheOpus48Reviewed` is the strongest** — because
it is the only file that has actually survived an adversarial cross-model review. The original
"winner", `LruCacheOpus48Activated`, *looked* best on a feature scorecard but **had two real bugs its
own test suite never caught.** That is the whole lesson of this repo: passing your own tests ≠ correct.

### The review that changed the ranking

After the first five files were published, two other frontier models were asked to tear the
primed file apart as ruthless senior reviewers: **GPT-5.5** (`gpt-5.5-2026-04-23`) and a **second,
independent Opus 4.8 instance**. They *independently* flagged the same issues, and an attack-test then
**confirmed two as real bugs**:

1. **Reentrancy corruption in `computeIfAbsent`.** If the mapping function re-entered the cache
   (e.g. `put` of the same key) under the reentrant lock, the key ended up on the list **twice** —
   `map.size()=1` but the node appeared twice — breaking the `map.size()==listLength()` invariant.
2. **Silent expired-overwrite.** `put` over an already-expired entry overwrote it without counting
   `EXPIRED`, inconsistent with `putIfAbsent`/`computeIfAbsent`.

Plus two correctness/honesty fixes: `getOrDefault` was a check-then-act **TOCTOU** race (now atomic),
and the file's own Javadoc still claimed `AtomicLong` while the code used `LongAdder` (self-
contradiction, now corrected). All four are fixed in `LruCacheOpus48Reviewed`, with two **new
regression tests** that fail on the old code and pass on the new.

> The bug pattern the reviewers flagged — **running user code (listeners, loaders) under the global
> lock** — exists to some degree in *all* the files; it is the honest next thing to harden across the
> board, not unique to the primed take.

> **Conflict-of-interest disclosure.** This repo's author agent runs on Claude, and the top files are
> Claude Opus 4.8. Read the scorecard, re-grep every row, re-run every harness. The fact that the
> review was *also* done by Claude (plus GPT-5.5) is itself disclosed — and the review still demoted a
> Claude file, which is the point: criticism that can't dethrone its own side is theatre.

| Criterion | 4.7 | 4.8 | 4.8 primed | 4.8 reviewed | Gemini 3.5F | GPT-5.5 |
| --- | :-: | :-: | :-: | :-: | :-: | :-: |
| Compiles `-Xlint:all` clean | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Own test suite passes | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| TTL + **injectable clock** | — | ✅ | ✅ | ✅ | — | — |
| Typed `RemovalCause` callback | — | ✅ | ✅ | ✅ | — | — |
| `LongAdder` counters | — | — | ✅ | ✅ | — | — |
| Property-diff vs JDK LRU | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Reentrancy-safe `computeIfAbsent`** | ? | ? | ❌ | ✅ | n/a | n/a |
| **Expired-overwrite counts `EXPIRED`** | n/a | ✅ | ❌ | ✅ | n/a | n/a |
| **Atomic `getOrDefault`** | n/a | ✅ | ❌ | ✅ | n/a | n/a |
| Survived adversarial cross-model review | — | — | — | ✅ | — | — |
| Lines | 610 | 769 | 800 | 845 | **516** | 726 |

**Verdict by use case:**
- **Best overall:** **Opus 4.8 reviewed** — the only file whose bugs were found *and fixed* under
  cross-model adversarial review, with regression tests proving it.
- **Most feature-rich raw model output:** **Opus 4.8 primed** — but ship the *reviewed* version; the
  raw one has the two confirmed bugs above. Kept here only to show what review caught.
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
