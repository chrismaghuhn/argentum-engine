# B1 Performance Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Measure the trusted Akiri/Chevill Gym path on the exact B0 base, attribute runtime with JVM evidence, and publish a concise B1 baseline without changing production behavior.

**Architecture:** Add one opt-in src/test profiler that drives the existing MultiEnvService, GameGymEnv, ObservationBuilder, and DeterministicExternalPolicy through the strict public path. It records phase wall time, JVM CPU/allocation/GC counters, public semantic decision counters, and a JFR recording; generated measurements remain under gym/build/reports/b1, while only the report, plan, and test-only profiler are committed. Replay comparison is diagnostic-only and never changes trust policy.

**Tech Stack:** Kotlin/JDK 21, Kotest, Gradle through scripts/gradle-locked, JFR (jdk.jfr.Recording), JVM management MXBeans, PowerShell timing, and existing Argentum Gym/replay contracts.

---

### Task 1: Establish the exact measurement checkout and baseline

**Files:**
- Read only: AGENTS.md, gym/build.gradle.kts, gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExactPairAcceptanceTest.kt, gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt
- Create: docs/superpowers/plans/2026-09-01-b1-performance-baseline.md

- [ ] **Step 1: Verify exact source identity and clean isolated branch**

Run:

~~~powershell
$expected = "ffcf897213f09932d30020f3c1df20b99f369b84"
git remote get-url origin
git ls-remote origin refs/heads/main
git rev-parse refs/remotes/origin/main
git rev-parse HEAD
git status --short --branch
~~~

Expected: the required origin URL is shown, both main refs equal $expected, the dedicated branch is clean, and HEAD equals $expected before profiling changes.

- [ ] **Step 2: Run the pre-change Gym contract baseline**

Run:

~~~powershell
just test-class B0HarnessTimeoutPolicyTest --console=plain
just test-class ObservationPrivacyTest --console=plain
just test-class StateDigestTest --console=plain
~~~

Record each command's exit code and pass/skip/failure counts. A failure is reported as pre-existing and stops implementation; no unrelated file is reverted.

---

### Task 2: Add the opt-in test-only profiling harness

**Files:**
- Create: gym/src/test/kotlin/com/wingedsheep/gym/B1PerformanceBaselineTest.kt
- Modify: gym/build.gradle.kts (test-worker forwarding for the opt-in b1.* properties)
- Do not modify: any src/main file, locked deck file, card definition, policy source, or replay schema

- [ ] **Step 1: Define fixed workloads and the strict public loop**

The profiler must use MAX_STEPS = 2,000 and these exact workload cases:

~~~kotlin
private val workloads = mapOf(
    "witness" to listOf(EpisodeSpec(0L, 0, "Akiri", "Chevill")),
    "normal4" to listOf(
        EpisodeSpec(0L, 0, "Akiri", "Chevill"),
        EpisodeSpec(0L, 1, "Akiri", "Chevill"),
        EpisodeSpec(0L, 0, "Chevill", "Akiri"),
        EpisodeSpec(0L, 1, "Chevill", "Akiri"),
    ),
    "corpus8" to (0L..3L).flatMap { seed ->
        listOf(EpisodeSpec(seed, 0, "Akiri", "Chevill"),
               EpisodeSpec(seed, 1, "Akiri", "Chevill"))
    },
    "replay2" to listOf(
        EpisodeSpec(0L, 0, "Akiri", "Chevill"),
        EpisodeSpec(0L, 1, "Chevill", "Akiri"),
    ),
)
~~~

Construct EnvConfig from the existing locked Akiri/Chevill files exactly as the acceptance test does: explicit deck counts with only the commander row removed, Format.Commander(), life 40, hand size 7, skipMulligans=true, useHandSmoother=false, the selected seed/start seat, perspective index 0, and maxSteps=2,000. Populate the registry from MtgSetCatalog.all and basic lands.

Create one DeterministicExternalPolicy per episode and drive only MultiEnvService.create, DeterministicExternalPolicy.choose, MultiEnvService.step, and MultiEnvService.submitDecision. Count one semantic decision for every policy choice, one external transition for every accepted strict call, and one engine progress unit for every committed strict step. A policy gap, non-empty diagnostic, missing observation, or premature nonterminal empty domain is a measured failure; do not substitute native AI, AutoPay, hidden state, or a different candidate rule.

- [ ] **Step 2: Record phase timers and JVM counters**

Time these existing calls with System.nanoTime(): episode reset/create; external policy choice; strict transition plus returned observation; and diagnostic replay verification. Count legal candidates, structured decisions, action kinds, decision families, and visible cards.

Capture OperatingSystemMXBean.processCpuTime, ThreadMXBean.getThreadAllocatedBytes, MemoryMXBean.heapMemoryUsage, and every GarbageCollectorMXBean count/time pair before and after the workload. Sample heap usage at a fixed 64-observation interval and retain its sampled peak. Unsupported MXBean values are emitted as NOT_AVAILABLE, never guessed.

- [ ] **Step 3: Start and dump JFR only for opt-in runs**

When -Db1.profile=true, start a JDK 21 Recording(Configuration.getConfiguration("profile")), set jdk.ExecutionSample to a 10 ms period, and dump <workload>-<mode>.jfr under the explicit -Db1.outputDir in a finally block. Write a matching JSON metric file. The Kotest case is disabled unless b1.profile=true, so ordinary just test does not run a multi-minute workload.

- [ ] **Step 4: Implement the diagnostic public replay comparison**

For replay2, capture each selected public action as semantic key plus public payload and each observation as ObservationCanonicalizer.semanticJson. In the same test JVM, reset a fresh service for the same two episodes, rebind each captured action to the current opaque handle by semantic key and ordinal, submit it through the same strict public API, and compare every semantic frame and StateDigest.compute result. Count capture/canonicalization and verification time separately. A mismatch fails closed as replay divergence.

---

### Task 3: Run measured workloads and collect profiler evidence

**Files:**
- Generated and ignored: gym/build/reports/b1/*
- Read after runs: JFR summaries and JSON metric files

- [ ] **Step 1: Verify opt-in gating**

Run:

~~~powershell
scripts/gradle-locked :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" --console=plain
~~~

Expected: the profiler case is skipped because b1.profile is absent and no B1 workload artifact is written.

- [ ] **Step 2: Measure the reproducible witness**

Run with an explicit output directory and record the outer PowerShell wall clock:

~~~powershell
$out = (Resolve-Path "gym/build/reports/b1").Path
Measure-Command { scripts/gradle-locked :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" -Db1.profile=true -Db1.workload=witness -Db1.mode=baseline -Db1.outputDir="$out" --console=plain }
~~~

Record exit code, JSON, JFR path, workload wall time, and command wall time. Nonzero episode, transition, and semantic-decision counts are required for a usable result.

- [ ] **Step 3: Measure normal episodes and the bounded corpus**

Run separately:

~~~powershell
scripts/gradle-locked :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" -Db1.profile=true -Db1.workload=normal4 -Db1.mode=baseline -Db1.outputDir="$out" --console=plain
scripts/gradle-locked :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" -Db1.profile=true -Db1.workload=corpus8 -Db1.mode=baseline -Db1.outputDir="$out" --console=plain
~~~

The corpus remains exactly eight episodes (four seeds times two starting-player positions), each with the existing maxSteps=2,000; report terminal/truncated results without changing the budget.

- [ ] **Step 4: Measure replay diagnostics**

Run:

~~~powershell
scripts/gradle-locked :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" -Db1.profile=true -Db1.workload=replay2 -Db1.mode=baseline -Db1.outputDir="$out" --console=plain
scripts/gradle-locked :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" -Db1.profile=true -Db1.workload=replay2 -Db1.mode=replay -Db1.outputDir="$out" --console=plain
~~~

Identify this as public semantic replay comparison. If the existing CompactReplay/ReplayFingerprint gate is also run, identify it separately from this diagnostic.

- [ ] **Step 5: Extract JFR evidence**

Run:

~~~powershell
jfr summary gym/build/reports/b1/corpus8-baseline.jfr
jfr view hot-methods gym/build/reports/b1/corpus8-baseline.jfr
jfr view allocation-by-class gym/build/reports/b1/corpus8-baseline.jfr
jfr view gc-heap gym/build/reports/b1/corpus8-baseline.jfr
~~~

Use only sampled stacks/events present in the recordings. A category with no sample is NOT_OBSERVED, not evidence of zero cost.

---

### Task 4: Analyze and write the B1 report

**Files:**
- Create: docs/ml/b1-performance-baseline.md
- Read only: gym/build/reports/b1/*.json, *.jfr, and JFR command output

- [ ] **Step 1: Classify measured costs**

Classify top hotspots as HOT_CPU, ALLOCATION/GC, REPEATED_REDUNDANT_WORK, SERIALIZATION/DIGEST_COST, REPLAY_ONLY_COST, or B0_HARNESS_OVERHEAD. Use phase timers and JFR stacks; never call a source-inspection guess a measured hotspot.

- [ ] **Step 2: Compute comparable rates and replay overhead**

Use:

~~~text
episodes_per_second = episodes / workload_wall_seconds
transitions_per_second = external_transitions / workload_wall_seconds
semantic_decisions_per_second = semantic_decisions / workload_wall_seconds
engine_progress_per_second = engine_progress / workload_wall_seconds
replay_overhead = replay_verify_wall_seconds - matching_baseline_wall_seconds
~~~

Compare the bounded observed baseline with the supplied B0-64 runtime of approximately 1h41m49s. State whether evidence supports normal trusted simulation or verification/instrumentation as the dominant cost. Do not present a small-workload ratio as a proven full B0-64 ratio when workloads differ.

- [ ] **Step 3: Write the required fields without implementing an optimization**

The report includes BASE_HEAD, workload/count fields, wall/rate metrics, CPU/allocation/GC evidence, ten sampled hotspots, B0-specific overhead, replay-verification overhead, primary and secondary bottlenecks, and an optimization order. Every recommendation has expected impact, complexity, determinism risk, rules risk, privacy risk, and replay risk. Unmeasured fields are NOT_RUN, NOT_OBSERVED, or BLOCKED.

---

### Task 5: Verify scope, commit, push, and stop

**Files:**
- Commit only: the plan, gym/build.gradle.kts, docs/ml/b1-performance-baseline.md, and gym/src/test/kotlin/com/wingedsheep/gym/B1PerformanceBaselineTest.kt
- Do not commit: gym/build/reports/b1/*, logs, JFR binaries, or unrelated changes

- [ ] **Step 1: Run post-measurement semantic checks**

Run:

~~~powershell
just test-class B0HarnessTimeoutPolicyTest --console=plain
just test-class ObservationPrivacyTest --console=plain
just test-class StateDigestTest --console=plain
just test-class TargetPaymentDomainContractTest --console=plain
git diff --check
git status --short
~~~

Record each exact exit code and test result. The profiler must remain test-only and opt-in; locked deck bytes and production source must be unchanged.

- [ ] **Step 2: Inspect the final diff and identity**

Run:

~~~powershell
git diff --stat
git diff --name-only
git diff -- gym/src/main
git rev-parse HEAD
git status --short --branch
~~~

Expected: no src/main or locked-deck diff, and only the plan, test-only profiler, and report are staged.

- [ ] **Step 3: Commit and push only profiling/report work**

Run:

~~~powershell
git add docs/superpowers/plans/2026-09-01-b1-performance-baseline.md gym/build.gradle.kts docs/ml/b1-performance-baseline.md gym/src/test/kotlin/com/wingedsheep/gym/B1PerformanceBaselineTest.kt
git commit -m "docs: record B1 performance baseline"
git push -u origin chris/b1-performance-baseline
~~~

The final response includes the resulting HEAD, the required parent, PRODUCTION_OPTIMIZATIONS=0, baseline runtime, primary bottleneck, top hotspots, best measured target, IMPLEMENTATION_PASS, CODE_REVIEW_PASS=PENDING, DATA_TRUSTED=NO, and NEXT=B1_OPTIMIZATION_PLAN_AFTER_INDEPENDENT_REVIEW. Stop after push; do not begin optimization or create a PR.
