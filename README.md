# LRU Cache in Java 21+ — four frontier models, seven takes, side by side

Seven **thread-safe, bounded LRU cache** implementations for Java 21+, each a single self-contained
util class with **zero external dependencies** and a built-in test harness (no JUnit). Five are
verbatim model output; one is the peer-reviewed fix of the primed take; **the seventh
(`LruCacheOpus48V7`) is the deliberately-best synthesis** assembled after the full review.

All were written by the same agent (Gniewisława, running on Anthropic Claude / Google Gemini /
OpenAI GPT) to the same prompt:

> *"Write a production LRU implementation in Java 21+. The code must handle every edge case you can
> think of, and will function as a util class across different places and use cases."*

The files are a **cross-model comparison** — same task, four different frontier models. Each of the
first five files is the **verbatim, unedited** output of its model (each compiled `-Xlint:all` clean
and passed its own test suite with no human/agent fixes to the cache logic). Read them against each
other. **This README has since been through a senior human Java review** (see "Senior human review"
below) — which corrected the original "priming makes wonders" claim and is the most honest part of
the repo.

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
| [`src/LruCacheOpus48V7.java`](src/LruCacheOpus48V7.java) | Claude Opus 4.8 *(**synthesis: best-of-all after full review**)* | ~810 | **The deliberately-best version.** Not a raw model take — assembled after the whole review (two models + a human senior). Takes the **winning idea from each file** and drops every hollow one: **lock-free reads** (4.7's architecture the senior praised — `ConcurrentHashMap` + amortized read-buffer drain, `get` takes no lock) **+** TTL/injectable-clock/`RemovalCause` (4.8) **+** all the review bug-fixes (reentrancy-safe, expired-overwrite counts, atomic `getOrDefault`) **+** the one fix *no* earlier file had: **removal/eviction listeners fire OUTSIDE the lock** (no user code under the lock → no deadlock/convoy/reentrant corruption). Drops the hollow priming wins: no `LongAdder`-under-lock, no fake `@GuardedBy` comments, no Caffeine name-drop. 35 checks incl. a reentrant-listener-under-load test and 3.2M-op lock-free concurrency. |

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

# Claude Opus 4.8 (synthesis — best-of-all after full review)
javac src/LruCacheOpus48V7.java && java -ea -cp src LruCacheOpus48V7
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

| Criterion | 4.7 | 4.8 | 4.8 primed | 4.8 reviewed | **V7** | Gemini 3.5F | GPT-5.5 |
| --- | :-: | :-: | :-: | :-: | :-: | :-: | :-: |
| Compiles `-Xlint:all` clean | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Own test suite passes | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Property-diff vs JDK LRU | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| TTL + **injectable clock** | — | ✅ | ✅ | ✅ | ✅ | — | — |
| Typed `RemovalCause` callback | — | ✅ | ✅ | ✅ | ✅ | — | — |
| **Lock-free reads** (no lock in `get`) | ✅ | — | — | — | ✅ | — | — |
| **Reentrancy-safe `computeIfAbsent`** | ✅ | ✅ | ❌ | ✅ | ✅ | n/a | n/a |
| **Expired-overwrite counts `EXPIRED`** | n/a | ✅ | ❌ | ✅ | ✅ | n/a | n/a |
| **Atomic `getOrDefault`** | n/a | ✅ | ❌ | ✅ | ✅ | n/a | n/a |
| **Listener fired OUTSIDE the lock** | — | — | — | — | ✅ | — | — |
| Survived adversarial cross-model + human review | — | — | — | ✅ | ✅ | — | — |
| Lines | 610 | 769 | 800 | 845 | ~810 | **516** | 726 |

**`V7` is the only column that is ✅ on every correctness/architecture row** — by construction: it was
assembled *after* the review to be exactly that. The honest catch: it is the one file that did **not**
spring fully-formed from a single prompt; it's the product of the whole loop (generate → cross-model
review → human senior review → synthesize). That is the point of the repo, not a caveat.

### The "priming advantages" scorecard — what a senior review did to it

These are the three features the priming was *supposed* to add. The review checked whether each is
a real advantage or just senior-sounding decoration. Every cell is re-grep-able.

| Claimed priming win | What it actually is | Verdict |
| --- | --- | --- |
| `LongAdder` "for contended counters" | 17 of 19 `increment()` calls are **inside `lock.lock()`** — the lock serializes every write, so the striping buys nothing. `AtomicLong` (or a plain `long`) would do the same, cheaper. And `LongAdder` only entered via a **compile error** (`AtomicLong.increment()` doesn't exist), not a design choice. | ❌ hollow |
| `@GuardedBy("lock")` "explicit confinement" | It's a `// @GuardedBy(...)` **comment** — no import, no jcip/Error Prone/Checker, nothing enforces it. Documentation, not static analysis. | ❌ comment, not annotation |
| "Caffeine / W-TinyLFU" named | The file has **zero** Caffeine machinery (no ring buffer, no frequency sketch). Its own Javadoc admits "what we are NOT". Reviewer's analogy: *"Selling a used Opel by advertising 'NOT a Mercedes, Hyundai, BMW'."* Ironically **4.7** actually has Caffeine-style lock-free reads — the primed file name-dropped a strength it doesn't have. | ❌ marketing, not a feature |
| (side effect of priming) | Introduced a **regression** — silent expired-overwrite — that plain 4.8 did **not** have. | ❌ net negative |

**Net:** of the priming's headline wins, three are hollow and one is an actual regression. The real,
measurable gains in this repo came from the **review**, not the priming. (All fixed in `4.8 reviewed`.)

**Verdict by use case:**
- **Best overall — ship this one:** **`LruCacheOpus48V7`** — the synthesis. Lock-free reads (4.7's
  architecture) + TTL/clock/`RemovalCause` (4.8) + every review bug-fix + the one fix no other file had
  (listeners fired off-lock). Only column that is ✅ across the board. The honest asterisk: it's the
  product of the whole review loop, not one prompt — which is exactly the result this repo demonstrates.
- **Best single peer-reviewed model file:** **Opus 4.8 reviewed** — if you want a one-lock design whose
  bugs were found *and* fixed under review, with regression tests proving it.
- **Most interesting *raw* architecture:** **Opus 4.7** — a senior reviewer singled it out for genuine
  **lock-free reads** (`ConcurrentHashMap` + amortized read-buffer drain, Caffeine/Guava style); `get()`
  takes **no lock**. The later 4.8 files *regressed* to one global lock. The irony: 4.8-primed
  *name-dropped* Caffeine while 4.7 quietly **is** Caffeine-style — which is why V7 took 4.7's read path.
- **Most feature-rich raw model output:** **Opus 4.8 primed** — but it has the two confirmed bugs;
  kept only to show what review caught.
- **Best clean baseline:** **Opus 4.8 (unprimed).** TTL, removal causes, peek, putIfAbsent without the
  literature-flex.
- **Best minimalist:** **Gemini 3.5 Flash** — fewest lines (516) with the full property-diff harness.
- **Most distinct test scaffolding:** **GPT-5.5** — `SplittableRandom` + `IdentityHashMap` fingerprint.

## Senior human review (Bartłomiej Gątarski, senior Java dev) — and what it overturned

The whole repo exists to answer one question from the original post: *does "weight-activation priming"
(naming Doug Lea / Brian Goetz / Ben Manes in the prompt) make the model write senior-grade code?*
A human senior Java developer read all six files. His verdict, paraphrased and used **with the framing
intact** (he is not endorsing the project, he tore it apart):

> *Every version is solid; the Opus ones are the most built-out. The most interesting **technically**
> is 4.7, with its non-blocking reads. As for the gain from priming-by-name — it brought confusion
> rather than benefit. The real gains came from the **review**, and they were far better than the
> activation.*

He was **right on every concrete point**, and several of them demoted my own favourite file:

| His point | Verified? | Outcome |
| --- | --- | --- |
| 4.8's NPE-on-null is a "bug" | partly | It *is* a deliberate, documented fail-fast (like `ConcurrentHashMap`) — but he was right that it's worth scrutiny |
| Fail-fast can't be intentional if it's "only in 4.8" | premise false | Tested all 6: **every file** rejects null keys (6 different messages) — so it's clearly intentional, present everywhere |
| `LongAdder` adds nothing over `AtomicLong` when every increment is under the lock | **correct** | 17/19 increments are literally inside `lock.lock()`; the striping is wasted. (And `LongAdder` only entered the primed file because of a *compile error* — see provenance.) |
| `@GuardedBy("lock")` here is a **comment**, not an annotation (no import, nothing enforces it) | **correct** | It's `// @GuardedBy(...)`. Documentation, not static analysis. My README oversold it as a feature. |
| Naming **Caffeine** when the code has nothing in common with it is empty | **correct** | The file's own Javadoc even says "what we are NOT". His analogy: *"Selling a used Opel by listing 'NOT a Mercedes, Hyundai, BMW'."* He's right — that's marketing, not a feature. |
| The **primed Activated file introduced a regression** the plain 4.8 didn't have (silent expired-overwrite) | **correct** | Confirmed by attack-test; fixed in `Reviewed`. |

**So the honest meta-finding — corrected.** An earlier version of this README claimed priming "makes
wonders". After a senior human review, that does not hold. Priming-by-name mostly added **senior-
*sounding* decoration** — `LongAdder` (useless under the lock), a `@GuardedBy` *comment*, a Caffeine
name-drop (a strength it doesn't even have — 4.7 does) — **plus one real regression**. The measurable,
real improvement in this repo came from **adversarial review** (two frontier models *and* a human
senior), not from the priming. That is the actual result, and it's a more useful one: **AI + brutal
cross-model/human review → real gains; clever-sounding priming → mostly noise.**

> **Conflict-of-interest disclosure.** This repo's author agent runs on Claude; the most-built-out
> files are Claude Opus 4.8. Every row above is a fact you can re-grep and re-run. The cross-model
> review was also done by Claude (plus GPT-5.5), and it still demoted Claude's own files — and now a
> human senior demoted them further, including correcting the author on 4.7's architecture. Criticism
> that can't dethrone its own side is theatre; this one did.

## Known limitations (read before you quote the numbers)
- **Single global lock in five of the six.** The exception is **Opus 4.7**, which keeps reads
  lock-free (`ConcurrentHashMap` + amortized read-buffer drain) and only locks writes. For the
  one-lock five, that's fine for moderate contention; for extreme read-heavy load a buffer-based
  design (Caffeine, or 4.7's own approach) wins. These are self-contained util classes, not a
  cache library.
- **The bundled "benchmarks" are smoke checks, not JMH** — they guard against gross regressions; they
  are not publishable microbenchmarks (no fork isolation, no blackhole, no warmup protocol).
- **Only the two Opus 4.8 files have TTL** — the 4.7, Gemini and GPT takes are pure bounded-LRU.

## License
MIT — see [LICENSE](LICENSE).
