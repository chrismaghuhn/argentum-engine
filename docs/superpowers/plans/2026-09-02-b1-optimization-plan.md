# B1 Observation Canonicalization Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** First prove the amount of canonical action sort-key recomputation inside one semantic observation, then—only after an independently reviewed characterization—apply the smallest generic fix that preserves every trusted contract.

**Architecture:** Keep the deep public-observation module behind the existing GameGymEnv.observe/step -> ObservationBuilder.build -> StateDigest.compute -> ObservationCanonicalizer.semanticJson seam. The first characterization is canonicalizer-only and uses scalar, opt-in counters; it does not retain GameState objects or create a second UTF-8 copy. The GameGymEnv observe cache is a separate relevance measurement and is not promoted to the first production optimization without evidence from the real trainer/B0 call path.

**Tech Stack:** Kotlin/JVM 21, Gradle/Kotest, existing strict Gym service, locked Akiri/Chevill decks, deterministic external policy, JFR, JVM MXBeans.

---

## Scope and fixed identity

This plan starts from the accepted profiling commit:

```text
BASE_HEAD=d0f4fe9eddb377fbddd7e675ec396ecbabe254d5
PARENT=ffcf897213f09932d30020f3c1df20b99f369b84
PRODUCTION_OPTIMIZATIONS=0
PRODUCTION_SEMANTIC_CHANGES=0
DIAGNOSTIC_PRODUCTION_HOOKS=YES
PROBE_DEFAULT_ENABLED=NO
CODE_REVIEW_PASS=YES
DATA_TRUSTED=NO
```

This plan revision authorizes execution of Tasks 1–3 only. Task 2 may add disabled-by-default diagnostic hooks under src/main; it must not change production semantics. Tasks 4–6 remain unauthorized. The authorized execution must preserve Rules correctness, observation/privacy, decision completeness, replay/determinism, ZERO-UNSUPPORTED, locked decks, external policy control, RNG, collection ordering, and the existing 2,000-step workload horizon.

The accepted baseline evidence establishes the measurement order:

1. Correct test-only measurement boundaries.
2. Count canonicalizer work on the real returned observations, without repeated-read amplification.
3. Prove the number of legal-action sort-key evaluations in one semanticJson execution.
4. Write a RED contract only if the measured selector count exceeds the fingerprint count.
5. Apply one smallest canonicalizer fix and benchmark matched corpus8.
6. Measure the GameGymEnv cache separately against the real trainer/B0 call path.
7. Run the full trust and determinism regression matrix.

## Existing modules and seams

| File | Existing responsibility | Relevant fact |
|---|---|---|
| gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt | Public strict Gym adapter and action registry | build() calls environment.legalActions() and ObservationBuilder.build() before checking its existing step/perspective cache. This is a real cache bug, but normal create()/step() already return their newly built ObservationResult. |
| gym/src/main/kotlin/com/wingedsheep/gym/GameEnvironment.kt | Immutable Rules state holder and strict transition adapter | legalActions() is the input to public observation construction; strict commits increment stepCount and projectionGeneration. |
| gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt | Perspective-safe observation and complete domain construction | build() creates TrainingObservation and calls StateDigest.compute(obs). |
| gym/src/main/kotlin/com/wingedsheep/gym/contract/StateDigest.kt | SHA-256 over the semantic observation projection | compute() creates the actual UTF-8 byte array used by SHA-256 and formats the digest. |
| gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt | Deterministic wire and semantic JSON projection | semanticJson() maps legal actions to semantic fingerprints, sorts by canonicalize(fingerprint).toString(), then canonicalizes the final JSON tree. |
| gym/src/test/kotlin/com/wingedsheep/gym/B1PerformanceBaselineTest.kt | Existing witness/normal4/corpus8/replay2 profiler | Reuse its locked fixtures and deterministic policy; keep profiler artifacts separate from hard workload measurements. |
| gym/src/test/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizationTest.kt | Existing semantic, ordering, and privacy-independent canonicalization contracts | Extend only with byte/order assertions required by a proven canonicalizer change. |

The accepted JFR result showed ObservationCanonicalizer and its comparator ahead of legal-action/ability enumeration in the normal path. The external policy was about 1.5% of corpus8. The cache bug is therefore retained as a separate candidate, not treated as the first corpus optimization.

## Measurement contract

The normal corpus characterization uses aggregate scalar counters only. It must not use a GameState IdentityHashMap, retain state objects, or retain semantic strings/byte arrays across transitions. The targeted cache witness uses a fresh probe session for one environment/state at a time and releases it before the next session.

The diagnostic session reports these fields:

```text
observationBuilderCalls
legalActionEnumerationCalls
semanticJsonCalls
wireJsonCalls
semanticActionFingerprintCalls
legalActionSortKeyEvaluations
structuredDomainSortKeyEvaluations
finalCanonicalizationCalls
semanticJsonChars
stateDigestCalls
stateDigestInputBytes
sha256Calls
digestHexFormattingCalls
```

The stateDigestInputBytes counter is incremented from the actual bytes array already created inside StateDigest.compute(). The probe must never call semanticJson().toByteArray() or any other UTF-8 conversion. semanticJsonChars uses String.length only. The probe must not retain the semantic String; byte identity for future fixes is established by direct semantic String equality plus the actual StateDigest input path, while the measured byte count remains the real hashed UTF-8 length.

For canonicalizer-only measurement, count legal-action sort-key evaluations at the selector site and structured-domain sort-key evaluations at their separate selector site. Do not instrument every recursive canonicalize node. A canonical-string helper may report the site and return the exact existing canonicalize(element).toString() result without changing comparison behavior.

The hard benchmark boundaries are:

1. Start the workload timer and before-snapshot immediately before the measured workload.
2. Capture the after-snapshot and stop the workload timer immediately after the measured workload.
3. Stop and dump JFR only after the after-snapshot; report recording stop/dump time as artifact overhead.
4. In replay2, capture baseline, capture, and verify allocation/GC snapshots separately. Never use the aggregate capture-plus-verify allocation number divided by baseline transitions as a KPI.
5. Run hard before/after benchmarks with the probe disabled; probe-enabled runs are characterization evidence, not speed evidence.

## Execution sequence

### Task 1: Correct test-only measurement boundaries

**Files:**

- Modify: gym/src/test/kotlin/com/wingedsheep/gym/B1PerformanceBaselineTest.kt
- Modify: gym/build.gradle.kts to forward b1.characterize beside the existing B1 properties

- [x] **Step 1: Separate workload timing from JFR stop/dump.**

Move the workload timer end and JvmSnapshot.capture() immediately after runWorkload(specs, mode) and before Recording.stop()/Recording.dump(). Record recording-stop and recording-dump durations in separate diagnostic fields. Do not change episode-driving code.

Extract the existing Recording cleanup into a private stopAndDumpRecording(recording: Recording?, jfrPath: Path) helper that returns a recording-artifact timing data class and reports JFR failure as NOT_RUN without changing the workload result.

```kotlin
val before = JvmSnapshot.capture()
val start = System.nanoTime()
val measurement = runWorkload(specs, mode)
val workloadWallNanos = System.nanoTime() - start
val after = JvmSnapshot.capture()
val artifact = stopAndDumpRecording(recording, jfrPath)
```

- [x] **Step 2: Split replay pass counters.**

Record allocation and GC deltas for baseline/capture and verify independently, using the transitions completed by the corresponding pass as each denominator. Keep replay verification enabled; mark an aggregate replay allocation value NOT_COMPARABLE rather than comparing it with baseline.

- [x] **Step 3: Verify the measurement-only change.**

Run the repository wrapper first:

```powershell
just test-class B1PerformanceBaselineTest --console=plain
```

If the wrapper stops before Gradle with the known Windows WinError 193, report that command as BLOCKED and run the separate native fallback:

```powershell
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" --console=plain
```

Expected result: without b1.profile and b1.characterize, the profiler test is SKIPPED and produces no workload artifact. Any unavailable JFR view is NOT_RUN.

### Task 2: Add canonicalizer-only scalar instrumentation

**Files:**

- Create: gym/src/main/kotlin/com/wingedsheep/gym/contract/B1CanonicalizationProbe.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/StateDigest.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/GameEnvironment.kt
- Modify: gym/src/test/kotlin/com/wingedsheep/gym/B1PerformanceBaselineTest.kt

- [x] **Step 1: Define a no-retention diagnostic session.**

Implement B1CanonicalizationProbe as internal, disabled-by-default instrumentation. Its Session owns scalar counters and per-sort-site counts; it has no GameState map and no semantic string or byte-array retention. The interface is:

```kotlin
internal object B1CanonicalizationProbe {
    internal enum class SortSite { LEGAL_ACTION, STRUCTURED_DOMAIN }
    internal class Session
    internal fun start(): Session
    internal fun snapshot(session: Session): Snapshot
    internal fun stop(session: Session): Snapshot
    internal fun recordObservationBuild()
    internal fun recordLegalActionEnumeration()
    internal fun recordSemanticJson(semanticChars: Int)
    internal fun recordWireJson(wireChars: Int)
    internal fun recordSemanticActionFingerprint()
    internal fun recordSortKeyEvaluation(site: SortSite)
    internal fun recordFinalCanonicalization()
    internal fun recordStateDigest(inputBytes: Int)
    internal fun recordSha256()
    internal fun recordDigestHexFormatting()
}
```

Snapshot is a data class containing the counters listed in the measurement contract and the selected observation's legal-action count. It does not contain a GameState reference, a semantic String, or a second UTF-8 byte array.

- [x] **Step 2: Hook only existing operations.**

Record the existing ObservationBuilder.build() and GameEnvironment.legalActions() entry points. Record StateDigest.compute() after its existing semantic.toByteArray(StandardCharsets.UTF_8) expression, passing bytes.size and retaining no bytes. Record the existing SHA-256 and hex-format operations. Record top-level semanticJson(), wireJson(), semanticActionFingerprint(), and final canonical-string calls. Keep every serializer option, field filter, array normalization rule, comparator, and return value unchanged.

- [x] **Step 3: Add the opt-in control and artifact.**

Add b1.characterize forwarding in gym/build.gradle.kts and a separate opt-in test branch in B1PerformanceBaselineTest.kt. Write one machine-readable characterization per run to gym/build/reports/b1/canonicalization-<workload>-<mode>.json, which is the test-worker output directory. Do not mix it into the accepted baseline report until the measurements are reviewed.

### Task 3: Prove sort-key recomputation inside one semanticJson execution

**Files:**

- Modify: gym/src/test/kotlin/com/wingedsheep/gym/B1PerformanceBaselineTest.kt
- Modify: only the diagnostic hooks from Task 2

- [x] **Step 1: Select one real observation without changing the workload.**

Drive the existing seed-0 Akiri/Chevill witness with its locked decks and deterministic external policy. Collect aggregate counts for witness, normal4, and corpus8. The probe keeps only scalar data for the observed semanticJson call with the largest legalActions.size and requires at least two legal actions. It must not retain that TrainingObservation after the call. If the fixed workload produces no such observation, report CANONICAL_SORT_CHARACTERIZATION=NOT_RUN and do not synthesize an alternate candidate domain.

- [x] **Step 2: Measure the canonicalizer inside the real baseline path.**

Run the existing baseline workload with one probe session around the complete pass. Each actual ObservationBuilder -> StateDigest -> semanticJson invocation records its legal-action count, fingerprint count, legal-action sort-key count, structured-domain sort-key count, and semantic String length as scalar values. Do not invoke StateDigest.compute() or semanticJson() a second time for measurement. The same session records the actual StateDigest UTF-8 input bytes created by the production hash path.

For the largest observed semanticJson call, record:

```text
n = legal-action count for one actual semanticJson call
f = semanticActionFingerprint calls for that call
m = legal-action sort-key evaluations for that call
s = total semanticJson calls in the pass
d = total StateDigest calls in the pass
b = total StateDigest input bytes actually hashed in the pass
```

The expected proof relation is f=n for each complete call. The candidate is a confirmed duplicate only when one call has m>n. Record structured-domain sort-key evaluations separately; do not combine them with the legal-action selector count.

- [x] **Step 3: Run the characterization commands.**

```powershell
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" -Db1.profile=true -Db1.characterize=true -Db1.workload=witness -Db1.mode=baseline --console=plain
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" -Db1.profile=true -Db1.characterize=true -Db1.workload=normal4 -Db1.mode=baseline --console=plain
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" -Db1.profile=true -Db1.characterize=true -Db1.workload=corpus8 -Db1.mode=baseline --console=plain
```

Expected status: PASS for each available workload with the existing 1/1, 4/4, and 8/8 episode counts; matching transition, semantic-decision, external-transition, and engine-progress counts; and zero trusted-path diagnostics. The characterization artifact may contain JFR=NOT_RUN, but an absent JFR is never PASS.

### Task 4: RED, smallest canonicalizer fix, and matched benchmark

**Files:**

- Modify: gym/src/test/kotlin/com/wingedsheep/gym/B1PerformanceBaselineTest.kt
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt only when Task 3 proves m>n
- Remove: diagnostic probe files after measurement unless an independent review accepts them as test instrumentation

- [ ] **Step 1: Write the RED contract only for a proven duplicate.**

When Task 3 proves m>n, assert that one semanticJson execution evaluates the legal-action sort key exactly once per semantic fingerprint. Also add two distinct semantic fingerprints that intentionally produce the same canonical sort key and assert that their pre-existing stable input order is preserved after the fix:

```kotlin
val expectedSemanticJson = ObservationCanonicalizer.semanticJson(selectedObservation)
val session = B1CanonicalizationProbe.start()
val before = B1CanonicalizationProbe.snapshot(session)
val semantic = ObservationCanonicalizer.semanticJson(selectedObservation)
val after = B1CanonicalizationProbe.snapshot(session)
B1CanonicalizationProbe.stop(session)

(after.semanticJsonCalls - before.semanticJsonCalls) shouldBe 1L
(after.semanticActionFingerprintCalls - before.semanticActionFingerprintCalls) shouldBe
    selectedObservation.legalActions.size.toLong()
(after.legalActionSortKeyEvaluations - before.legalActionSortKeyEvaluations) shouldBe
    selectedObservation.legalActions.size.toLong()
semantic shouldBe expectedSemanticJson
```

Run the focused test before changing production code and record FAIL with the measured m and n. If m==n, record SORT_KEY_DUPLICATE=NOT_FOUND, do not write this RED assertion, and move to the next measured hotspot.

- [ ] **Step 2: Apply only the smallest proven fix.**

If the RED test fails solely because the selector is evaluated more than once, decorate each semantic fingerprint with its existing canonical sort string once, sort by the stored string, and project the fingerprint back. Preserve stable ordering, equal-key ordering, the existing canonicalize implementation, and the final JSON tree:

```kotlin
val semanticActions = observation.legalActions
    .map(::semanticActionFingerprint)
    .map { fingerprint ->
        fingerprint to canonicalize(fingerprint).toString()
    }
    .sortedBy { (_, sortKey) -> sortKey }
    .map { (fingerprint, _) -> fingerprint }

semantic["legalActions"] = JsonArray(semanticActions)
```

Do not change the serializer, legal-action membership, candidate order before canonical sorting, unordered-array policy, privacy filters, StateDigest algorithm, or any Rules/Gym execution path. Remove the diagnostic hook from hard benchmarks.

- [ ] **Step 3: Turn RED to GREEN and benchmark.**

Run the focused test, then matched witness, normal4, corpus8, and replay2 baseline profiles. Use at least two correct corpus8 runs and report the median. Compare workload-only wall time, episodes/sec, semantic decisions/sec, external transitions/sec, engine progress/sec, process CPU, pass-local allocation, heap peak, and GC. Keep JFR recording stop/dump outside the workload and keep replay capture/verify allocation denominators separate.

The benchmark is accepted only if all episode/transition/decision/progress counts remain equal, semantic and wire outputs remain byte-identical under the defined test, StateDigest values remain equal, complete public domains remain unchanged, and the focused RED test is GREEN.

### Task 5: Measure the GameGymEnv cache as a separate candidate

**Files:**

- Modify: gym/src/test/kotlin/com/wingedsheep/gym/B1PerformanceBaselineTest.kt for aggregate call-site counters
- Modify: gym-server/src/main/kotlin/com/wingedsheep/gym/server/controller/EnvController.kt only if the HTTP observe endpoint is included in the real caller measurement
- Modify: gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt only after cache relevance is proven

- [ ] **Step 1: Count real caller behavior.**

The in-repository non-step observation endpoint is EnvController.observe(), which calls MultiEnvService.observe(); gym-trainer SelfPlayLoop drives GameEnvironment directly and does not use the GameGymEnv observation cache. Locate any external B0 harness caller before adding a synthetic workload:

```powershell
rg -n "service\.observe|MultiEnvService.*observe|\.observe\(" gym docs scripts
```

Count observations returned directly by MultiEnvService.create(), strict step(), and submitDecision() separately from additional public observe() calls. Report CACHE_RELEVANCE=NOT_PROVEN when the real B0/trainer harness is unavailable; do not infer relevance from the ten-read witness.

- [ ] **Step 2: Keep the local cache reproduction bounded.**

For correctness only, use one fresh environment and one fresh probe session to call observe() repeatedly without a transition, then discard the session. Label the row with an integer environment-instance token, explicit reset/restore epoch, projectionGeneration, perspective, and truncated. Do not retain GameState objects, use an IdentityHashMap, or carry rows across reset, restore, fork, or corpus transitions.

- [ ] **Step 3: Defer the cache RED/fix unless the real path warrants it.**

Only if real B0/trainer counters show additional observe() calls should a separate RED test assert that a valid existing stepCount/perspective cache returns before legal-action enumeration and ObservationBuilder.build(). This candidate is not part of the canonicalizer-first corpus benchmark.

### Task 6: Run trust, determinism, and replay regressions

**Files:**

- No additional production files beyond the single reviewed canonicalizer change from Task 4
- Existing contract tests and exact-pair acceptance tests only

- [ ] **Step 1: Run focused public-contract gates.**

Run the repository wrapper first:

```powershell
just test-class ObservationCanonicalizationTest --console=plain
just test-class StateDigestTest --console=plain
just test-class ObservationPrivacyTest --console=plain
just test-class GameGymEnvStrictExecutionTest --console=plain
just test-class TargetPaymentDomainContractTest --console=plain
```

If the Windows wrapper is BLOCKED by WinError 193, run the native fallback separately:

```powershell
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.contract.ObservationCanonicalizationTest" --tests "com.wingedsheep.gym.contract.StateDigestTest" --tests "com.wingedsheep.gym.contract.ObservationPrivacyTest" --tests "com.wingedsheep.gym.GameGymEnvStrictExecutionTest" --tests "com.wingedsheep.gym.contract.TargetPaymentDomainContractTest" --console=plain
```

Expected result: every selected test reports PASS with zero failures; a blocked wrapper and a passing native fallback remain separately labeled.

- [ ] **Step 2: Run exact-pair replay and B0 harness checks.**

Run the existing exact-pair replay filter and B0HarnessTimeoutPolicyTest. If CompactReplay still rejects the current ExplicitV3 payload with “PaymentStrategy.ExplicitV3 requires CompactReplay v5 or newer”, record FAIL and ReplayFingerprint overhead=NOT_RUN. Do not alter the replay contract to make this gate pass.

- [ ] **Step 3: Verify the final diff and workload invariants.**

Confirm zero changes to ActionProcessor/Rules semantics, external policy control/order, locked decks, RNG, legal candidate membership, observation/privacy fields, decision completeness, replay payloads, and ZERO-UNSUPPORTED behavior. Report exact PASS, FAIL, NOT_RUN, SKIPPED, or BLOCKED status for every command. Keep DATA_TRUSTED=NO until independent review accepts the exact optimization commit.

## Candidate order and risk classification

These are future candidates only; this plan implements none of them.

| Order | Candidate after proof | Expected impact | Complexity | Determinism risk | Rules risk | Privacy risk | Replay risk |
|---:|---|---|---|---|---|---|---|
| 1 | Precompute each legal-action semantic fingerprint sort key once per semanticJson call | Medium/High CPU and allocation impact if m>n; directly targets the measured comparator path | Low/Medium | Medium | Low | High | High |
| 2 | Reduce final JSON/string materialization only after exact counters show byte-identical intermediate work | Medium normal-path impact | Medium/High | High | Low | High | High |
| 3 | Replace digest hex formatting with an equivalent lowercase formatter after a golden digest test | Low/Medium allocation impact | Low | Low | Low | Low | Medium/High |
| 4 | Avoid repeated observe() cache misses only when real B0/trainer call counts show them | High for repeated-read callers; negligible for one-observation-per-transition corpus8 | Low/Medium | Medium | Low | High | High |
| 5 | Remove proven duplicate target/payment/ability domain scans within one generation | Medium/High after canonicalizer work | High | High | High | High | High |
| 6 | Isolate B0-specific replay/artifact verification after a B0-stage profile; never weaken verification | High for B0 wall time, not normal simulation | Medium | Medium | Low | High | High |

The external policy remains out of scope as a first target because its accepted baseline share is about 1.5%. CompactReplay remains a separate existing FAIL/NOT_RUN condition until its version precondition is independently resolved.

## Stop conditions

Stop and report the exact status if:

- m is not greater than n, because there is no proven legal-action sort-key duplicate;
- the probe would require a second UTF-8 conversion, retain semantic strings/bytes, or retain GameState objects across transitions;
- semantic JSON, wire JSON, digest, legal-action membership/order, or domain contents differ;
- any action or decision is skipped, added, reordered, or selected by hidden fallback;
- privacy, Rules, replay, deterministic RNG, locked-deck, external-policy, or ZERO-UNSUPPORTED behavior changes;
- the benchmark includes JFR stop/dump or mixed replay capture/verify allocation;
- the real B0/trainer call path cannot be located, in which case cache relevance remains NOT_PROVEN.

The required state before independent review is:

```text
PLAN_DIRECTION=CANONICALIZER_FIRST
PRODUCTION_OPTIMIZATIONS=0
DATA_TRUSTED=NO
NEXT=B1_OPTIMIZATION_AFTER_INDEPENDENT_REVIEW
```
