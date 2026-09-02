# B1 Scaling and Final Measurement

Status: measurement and benchmark-infrastructure only. No Rules, Gym, observation, replay,
deck, policy, or production scheduling behavior was changed. `DATA_TRUSTED=NO` remains in force.

```text
BASE_ORIGIN_MAIN=f50c0c92249fe7d5c2f7b8044b1371462630135e
UPSTREAM_MAIN=36c5a0c2a2c688e2a814940495ce64c55baced25
ACCEPTED_CHARACTERIZATION_HEAD=9140a56be0c93e9b0cdbbb43f1a39ea88d6a67cf
BRANCH=chris/b1-scaling-final-measurement
MEASUREMENT_SOURCE_HEAD=9140a56be0c93e9b0cdbbb43f1a39ea88d6a67cf
DATA_TRUSTED=NO
PRODUCTION_FILES_CHANGED=0
PRODUCTION_OPTIMIZATIONS=0
PRODUCTION_SEMANTIC_CHANGES=0
```

`HEAD` below identifies the accepted source head used by the measurement harness. The final
report commit SHA is recorded by the delivery evidence after commit; putting that SHA into its
own committed contents would be self-referential.

```text
HEAD=9140a56be0c93e9b0cdbbb43f1a39ea88d6a67cf (measurement source head)
```

## Executive result

The existing `EnvWorkerPool` seam scales the locked workload, but not linearly. In the final
bounded run, median external throughput rose from `204.772 transitions/s` at one environment to
`742.022 transitions/s` at eight environments. Eight environments therefore achieved `3.624x`
the one-environment throughput, or `45.3%` of ideal eight-way scaling. There is no arbitrary B1
throughput target, so this is evidence about the current scaling curve rather than a pass/fail
claim against games/sec.

The normal single-environment profiler remains a different boundary: its corpus8 median is
`102.532491s` including service/registry setup and the profiler's per-observation diagnostics
checks. The scaling workload timer starts after setup and warmup and reuses environments through
`reset`, so its one-environment `78.136s` median must not be compared as an isolated optimization
speedup.

```text
CURRENT_CORPUS8_MEDIAN=102.532491s (two current profiler runs; setup included)
CURRENT_TRANSITIONS_PER_SEC=156.048095 (16,000 / median corpus8 wall)
CURRENT_EPISODES_PER_SEC=0.078024
CURRENT_ALLOCATION_PER_TRANSITION=5,116,869.430 bytes (profiler ThreadMXBean boundary)

1_ENV=204.772 transitions/s; 0.102386 episodes/s
2_ENVS=319.020 transitions/s; 0.159510 episodes/s
4_ENVS=493.952 transitions/s; 0.246976 episodes/s
8_ENVS=742.022 transitions/s; 0.371011 episodes/s
```

The final scaling harness itself reported `PASS` for every condition: each condition executed
8 episodes, 16,000 external transitions, 16,008 observations, 211,318 public legal candidates,
and 47 structured-decision observations. The semantic trajectory hash matched the one-environment
reference for every environment count.

## Workload and measurement boundary

### MEASURED

All scaling conditions used the same eight locked-corpus specifications:

- seeds `0..3`, starting-player indices `0` and `1`;
- `Akiri` in seat 0 versus `Chevill` in seat 1;
- Commander format, starting life 40, hand size 7, skipped mulligans, no hand smoother;
- explicit starting player, perspective player index 0, and `maxSteps=2,000`;
- the existing observation-only `DeterministicExternalPolicy`;
- 256 warmup steps per environment and three measured repetitions;
- eight episodes and exactly 16,000 external transitions per measured repetition.

The final run was executed on Windows 11, JDK 21.0.12, OpenJDK 64-bit Server VM, 16 reported
processors, G1 GC. The workload timer excludes registry/service setup and warmup. It includes
policy choice time, task submission/wait time, strict environment operation, and returned public
observation handling. Per-operation latency starts inside the worker task, so it measures the
operation rather than queue wait; workload wall time retains queue/scheduling effects.

The scaling benchmark calls `EnvWorkerPool.invokeAll`, the same pool fan-out used internally by
`MultiEnvService.stepBatch`. Structured decisions use `MultiEnvService.submitDecision` through
the same pool because the existing `stepBatch` API accepts `StepRequest` actions only. No
production scheduler was changed.

## Reproducible benchmark contract

The review-fix harness now emits the following contract in every new scaling, structured-latency,
and reset-heavy artifact. The focused characterization run captured the contract below at the
same accepted source head; the previously accepted scaling numbers are not rerun or relabeled.

```text
BENCHMARK_CONTRACT=PASS (captured by the focused v2 characterization run)
SCALING_BENCHMARK_SCHEMA_VERSION=argentum-b1-scaling-v2
GRADLE_TASK=:gym:test
RUN_MODE=native-gradle-jdk21
CPU_IDENTITY=AMD Ryzen 7 5800X 8-Core Processor; cores=8; logical=16; maxClockSpeed=3801 MHz
MEMORY_LIMIT_BYTES=34278862848 (OperatingSystemMXBean.totalMemorySize)
JVM_MAX_HEAP_BYTES=2147483648
JVM=OpenJDK 64-Bit Server VM; Java=21.0.12; os=Windows 11 10.0; arch=amd64
JVM_INPUT_ARGUMENTS=
  -Db1.latency=true; -Db1.latency.outputDir=build/reports/b1-latency-final-v2;
  -Db1.latency.warmupSteps=256; -Db1.resetHeavy=true;
  -Db1.resetHeavy.outputDir=build/reports/b1-reset-heavy-final-v2; -Db1.resetHeavy.resets=256;
  -Db1.scaling.gradleTask=:gym:test; -Db1.scaling.runMode=native-gradle-jdk21;
  -Dbenchmark=false; -DbenchmarkGames=10; -DbenchmarkMaxTurns=50;
  -DbenchmarkOutputDir=C:\Users\chris\AppData\Local\Temp\;
  -DbenchmarkSet=POR;
  -Dorg.gradle.internal.worker.tmpdir=C:\Users\chris\.config\superpowers\worktrees\argentum-engine\b1-scaling-final-measurement\gym\build\tmp\test\work;
  -Xmx2g; -Dfile.encoding=UTF-8; -Duser.country=DE; -Duser.language=de; -Duser.variant; -ea
```

The full JVM argument list, including Gradle worker and benchmark properties, is retained in the
JSON artifact rather than abbreviated in this report. The identity inputs are:

```text
ENGINE_SEED_CORPUS_IDENTITY_SHA256=524C5EA743D266E4191AEDBA7E0D42FC6F6EE8430E68506AF776CB161E6D0DBF
POLICY_SEED_CORPUS_IDENTITY_SHA256=F763D209C4E03BEEF9FCFAEFA7507E2A7EBF48A440F55137E835C819FAEF54F0
POLICY_IDENTITY_SHA256=7A3824E4568FDB52BF8EDCE117B834BC42ADCF8D3281EDA7CA1795DBD232120C
AKIRI_DECK_SHA256=0C5878E3B393A2CB6317FBE64E0827E4E9A562A0346E5A75820F11081F0909C6
CHEVILL_DECK_SHA256=D158760D404F32C32110C377B1CA6E3EF9406FD6E0CC29B620CB5BCF573AC8B2
LOCKED_CARD_IDENTITY_SHA256=B522EF3706289DDEB68769A1D642BD90AB80805DE2CFDC45F92F997D84E76AA6
LOCKED_DEFINITION_IDENTITY_SHA256=2290EE07D8C5BC6F616851C221FD81DCB01F0F1198FDFE00341178CF7842F195
LOCKED_UNIQUE_CARD_COUNT=146
REGISTERED_CARD_NAME_COUNT=9725
```

The deck hashes use canonical LF text. The engine seed-corpus hash covers each labeled engine seed,
starting-player index, seat assignment, and max-step contract. The policy seed-corpus hash covers
the same eight labels paired with `ScalingEpisodeSpec.policySeed()`. It is deliberately separate
from `POLICY_IDENTITY_SHA256`, which identifies the `EnvironmentV1ExternalPolicy.kt` source file.
The card identity hash is the sorted unique locked-card name set. The definition hash is the
sorted `(card name, CardSerialization CardDefinition JSON)` stream with object keys canonicalized
and serialized array order retained. This is a run identity contract, not a replacement for the
authoritative exact-pair definition-digest gate.

The test-only JVM snapshot sums allocated bytes over all live JVM threads, rather than only the
calling thread. Heap is sampled at reset boundaries, every 64 measured observations, and the
end of each repetition. Windows RSS is sampled at setup/measurement boundaries with
`Get-Process WorkingSet64`; the combined JFR also records `jdk.ResidentSetSize`. These probes are
outside the measured workload timer except for the ordinary heap sampling calls described above.
The setup-only stabilization is exactly `System.gc()` followed by a bounded sleep; no
finalization API is invoked.

### NOT_SEPARATELY_MEASURABLE

The current public return boundary does not expose independent production timers for
`ObservationBuilder.build()` or legal-domain publication. Returned step latency includes both.
Adding production phase hooks was outside scope. The existing profiler/JFR and prior accepted
characterization remain the source for qualitative attribution of those phases.

```text
OBSERVATION_BUILD_LATENCY=NOT_SEPARATELY_MEASURABLE; included in per-operation step latency
LEGAL_DOMAIN_PUBLICATION_LATENCY=NOT_SEPARATELY_MEASURABLE; included in per-operation step latency
```

## Required result fields

```text
BASE_ORIGIN_MAIN=f50c0c92249fe7d5c2f7b8044b1371462630135e
HEAD=9140a56be0c93e9b0cdbbb43f1a39ea88d6a67cf (measurement source head)
```

### Throughput and latency

Values below are medians over three measured repetitions in the final run. Step/reset entries are
`p50 / p95 / p99 / max`, in milliseconds. The percentile summary is computed per repetition;
the displayed condition summary is the component-wise median of those three summaries.

| Environments | Setup wall | Workload wall median | External transitions/s | Episodes/s | Step p50/p95/p99/max ms | Reset p50/p95/p99/max ms | Actual max concurrency |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 20.910s | 78.136s | 204.772 | 0.102386 | 4.486 / 9.087 / 11.371 / 40.584 | 5.078 / 6.808 / 6.808 / 6.808 | 1 |
| 2 | 0.411s | 50.154s | 319.020 | 0.159510 | 5.227 / 10.326 / 12.706 / 52.400 | 5.106 / 6.387 / 6.387 / 6.387 | 2 |
| 4 | 0.434s | 32.392s | 493.952 | 0.246976 | 5.875 / 11.706 / 14.167 / 44.949 | 5.738 / 6.316 / 6.316 / 6.316 | 4 |
| 8 | 0.448s | 21.563s | 742.022 | 0.371011 | 6.962 / 14.214 / 17.051 / 43.180 | 5.769 / 6.582 / 6.582 / 6.582 | 8 |

```text
STEP_P50_P95_P99=
  1 env: 4.486 / 9.087 / 11.371 ms; max 40.584 ms
  2 env: 5.227 / 10.326 / 12.706 ms; max 52.400 ms
  4 env: 5.875 / 11.706 / 14.167 ms; max 44.949 ms
  8 env: 6.962 / 14.214 / 17.051 ms; max 43.180 ms

RESET_P50_P95_P99=
  1 env: 5.078 / 6.808 / 6.808 ms; max 6.808 ms
  2 env: 5.106 / 6.387 / 6.387 ms; max 6.387 ms
  4 env: 5.738 / 6.316 / 6.316 ms; max 6.316 ms
  8 env: 5.769 / 6.582 / 6.582 ms; max 6.582 ms

ACTUAL_CONCURRENCY=
  requested/observed: 1/1, 2/2, 4/4, 8/8
  observed worker threads: 1, 2, 4, 8 respectively
```

The final per-repetition workload wall samples were:

```text
1 env: 81.629, 78.136, 76.515 s
2 env: 50.154, 49.909, 51.380 s
4 env: 32.392, 32.439, 31.701 s
8 env: 21.563, 21.768, 21.497 s
```

### Separated step latency characterization

The focused `B1StructuredLatencyMeasurementTest` used the exact locked corpus8 workload with the
same 256-step warmup. It did not rerun the scaling matrix. The existing public service boundary
was timed separately by choice class:

```text
STRUCTURED_LATENCY_CHARACTERIZATION=PASS
NORMAL_STEP_COUNT=15,953
STRUCTURED_PENDING_STEP_COUNT=47
NORMAL_STEP_P50_P95_P99_MAX=4.018 / 7.711 / 10.712 / 38.531 ms
STRUCTURED_PENDING_STEP_P50_P95_P99_MAX=4.268 / 7.527 / 9.984 / 9.984 ms
MIXED_STEP_COUNT=16,000
EPISODES=8
OBSERVATIONS=16,008
PUBLIC_LEGAL_CANDIDATES=211,318
STRUCTURED_DECISION_OBSERVATIONS=47
```

`STRUCTURED_PENDING_STEP` means a transition submitted through the existing structured pending
decision path; no candidate, domain, payload, or policy selection was changed. The structured
P99 equals its maximum because there are 47 samples; the larger reset sample is reported below.

### Reset-heavy characterization

The focused `B1ResetHeavyMeasurementTest` performed 256 real resets of the same locked seed-0
Akiri-vs-Chevill environment. It measured only reset calls, not game transitions, and checked
that every reset returned the same semantic state digest.

```text
RESET_HEAVY=PASS
RESET_HEAVY_RESETS=256
RESET_HEAVY_WALL=1.273s
RESET_HEAVY_RESETS_PER_SEC=201.151
RESET_HEAVY_P50_P95_P99_MAX=4.450 / 8.833 / 12.255 / 16.914 ms
RESET_HEAVY_ALLOCATION_PER_RESET=3,786,750 B
RESET_HEAVY_GC=4 collections / 26 ms
RESET_HEAVY_HEAP_PEAK=414.3 MiB
RESET_HEAVY_SEMANTIC_RESET_REGRESSION=PASS
RESET_HEAVY_MEMORY_TREND=first/last delta -181,186,128 B across 256 samples
```

This closes the small-sample reset percentile gap for B1 evidence without claiming that one
in-process reset trend is a fresh-JVM retained-memory study.

Relative to the one-environment median, measured speedup/ideal efficiency was:

```text
1 env: 1.000x / 100.0%
2 env: 1.558x / 77.9%
4 env: 2.412x / 60.3%
8 env: 3.624x / 45.3%
```

### Current single-environment profiler

These are separate `B1PerformanceBaselineTest` runs at the accepted source head. They include
registry/service setup, use the profiler's existing diagnostics checks, and keep JFR stop/dump
outside the workload timer.

| Workload | Episodes | Transitions | Workload wall | Transitions/s | Setup | Transition + observation | Policy | Allocation/transition | Sampled heap peak | MXBean GC |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| witness | 1 | 2,000 | 35.579s | 56.213 | 19.950s | 14.651s | 0.342s | 6,307,445 B | 282.0 MiB | 200 / 877 ms |
| normal4 | 4 | 8,000 | 65.274s | 122.560 | 20.064s | 43.457s | 0.963s | 5,281,049 B | 435.2 MiB | 222 / 801 ms |
| corpus8 run 1 | 8 | 16,000 | 103.184s | 155.063 | 21.535s | 78.885s | 1.820s | 5,117,697 B | 284.1 MiB | 692 / 1,806 ms |
| corpus8 run 2 | 8 | 16,000 | 101.881s | 157.046 | 20.654s | 78.405s | 1.886s | 5,116,042 B | 308.9 MiB | 488 / 1,153 ms |
| corpus8 median | 8 | 16,000 | **102.532s** | **156.048** | — | — | — | **5,116,869 B** | — | — |

The current profiler measurements are not a causal comparison with the historical accepted
`90.761s` post-canonicalizer number. The measurement boundaries and host/JVM conditions differ;
no isolated canonicalizer speedup is claimed here.

## Allocation, heap, RSS, and GC

```text
CURRENT_ALLOCATION_PER_TRANSITION=
  1 env: 5,042,280 B (three-repetition total: 242,029,451,032 B)
  2 env: 5,040,353 B (three-repetition total: 241,936,941,320 B)
  4 env: 5,040,182 B (three-repetition total: 241,928,736,208 B)
  8 env: 5,040,097 B (three-repetition total: 241,924,677,016 B)
```

The allocation rate is nearly flat per external transition while aggregate allocation bandwidth
rises with concurrency. This is allocation pressure, not retained heap. The final scaling JFR
recording covered 579 seconds and contained 31,736 execution samples, 166,256 object-allocation
samples, and 536 resident-set samples.

```text
PEAK_MEMORY=
  heap sampled peak: 275.2 / 273.3 / 369.9 / 464.1 MiB for 1 / 2 / 4 / 8 envs
  RSS boundary/JFR peak: 779.5 / 806.0 / 977.2 / 1,132.4 MiB for 1 / 2 / 4 / 8 envs
```

The normalized peak RSS per configured environment was approximately `779.5`, `403.0`, `244.3`,
and `141.6 MiB` for 1/2/4/8 environments. This is a capacity-normalization figure containing
the shared JVM, registry, class metadata, worker pool, and transient workload; it is not an
isolated retained environment footprint.

```text
MEMORY_PER_ENV=
  retained setup heap/RSS delta: NOT_STABLE_AS_AN_ISOLATED_ENV_SIZE
  normalized peak RSS capacity figure: 779.5 / 403.0 / 244.3 / 141.6 MiB per configured env
  reason: process reuse, class loading, GC timing, and Windows working-set behavior make the
          before/after delta non-monotonic even after setup-only GC stabilization
```

The setup-only retained heap deltas were `+1,536,832 B` for 1 env, `-252,680 B` for 2 envs,
`-671,896 B` for 4 envs, and `+237,632 B` for 8 envs. They are recorded to expose the limitation,
not promoted to a memory-per-environment claim. A future capacity study should launch one fresh
JVM per environment count and measure retained state after a controlled full-GC boundary.

```text
GC_TIME=
  MXBean cumulative over the 3 measured repetitions: 1,889 / 1,892 / 1,566 / 1,761 ms
  MXBean collection counts: 1,355 / 1,333 / 864 / 716
  JFR combined run: 8.26 s total pause, 4,406 pauses, P95 3.62 ms, maximum 75.1 ms
```

Reset-memory samples were taken after each of 24 measured resets per condition (8 episodes × 3
repetitions). They are occupancy samples, not a forced-GC leak test:

```text
RESET_MEMORY_TREND=
  1 env: +68.9 MiB first-to-last; +2.99 MiB/sample
  2 env: +73.0 MiB first-to-last; +3.17 MiB/sample
  4 env: -61.1 MiB first-to-last; -2.66 MiB/sample
  8 env: -63.3 MiB first-to-last; -2.75 MiB/sample
  interpretation: no monotonic retained-memory conclusion; GC/occupancy noise dominates this
                 bounded in-process reset trend
```

### JFR current residual hotspot

The final combined scaling JFR remains consistent with the accepted deep-audit direction. The
largest method samples were `HashMap.getNode` (10.65%), `String.hashCode` (8.33%),
`AbstractStringBuilder.ensureCapacityInternal` (6.49%), `HashMap.putVal` (4.60%),
`AbstractCollection.toArray` (3.26%), and `Arrays.copyOf(byte[])` (2.89%). Application-side
signals included `JsonObject.toString` (2.20%), `ObservationCanonicalizer.canonicalize` (1.88%),
`ActivatedAbilityEnumerator.enumerateOwnPermanents` (2.06%), `PredicateContext` construction
(1.43%), and static/gained ability resolution.

Allocation-by-class led with `byte[]` (60.76%), `LinkedHashMap.Entry` (7.35%), `String` (3.96%),
`Object[]` (3.22%), `HashMap.Node[]` (2.97%), `StringBuilder` (2.81%), `PredicateContext`
(2.55%), and `ArrayList` (2.38%). Allocation-by-site led with `Arrays.copyOf(byte[])`
(26.30%), `copyOfRangeByte` (12.96%), `LinkedHashMap.newNode` (7.35%), UTF-16 byte creation
(7.06%), `copyOfRange` (4.72%), `AbstractStringBuilder` (3.93%), and UTF-8 encoding (3.89%).

```text
PRIMARY_REMAINING_MEASURED_HOTSPOT=
  residual semantic JSON/tree/String/UTF-8/digest pipeline
```

These are JFR sampling/allocation signals across all scaling conditions, not isolated self-time
percentages for `StateDigest`.

## Multi-environment observation isolation

```text
MULTI_ENV_OBSERVATION_ISOLATION=PASS
```

The opt-in `B1MultiEnvIsolationTest` ran the same complete corpus through:

1. one-environment sequential reference;
2. natural-order 2-, 4-, and 8-environment batches using `MultiEnvService.stepBatch` whenever
   the selected choices were flat actions;
3. reverse-order 8-environment batches;
4. an 8-environment rotating sequential interleave.

Every scenario produced 8 episodes, 16,000 transitions, 16,008 observations, 211,318 public
legal candidates, 47 structured-decision observations, and `semanticTrajectory=PASS`. The online
reference comparator consumed compact per-frame trace lines and reported the first event index
on a mismatch; it did not retain `GameState`, `TrainingObservation`, legal-action lists, or
semantic JSON trees.

The compared frame signature includes the existing `stateDigest` plus public candidate count,
pending-decision kind/structured marker, actor, terminal, and truncation fields. `StateDigest`
already covers the perspective-safe semantic observation, including semantic legal-action
fingerprints and target/payment/structured domains. Choice traces compare transport-independent
semantic action/payload hashes.

```text
single-env-reference:          stepBatch=15,953; worker-pool fallback batches=47; max concurrency=1
parallel-2:                    stepBatch=7,953;  requests=15,906; max concurrency=2
parallel-4:                    stepBatch=3,953;  requests=15,812; max concurrency=4
parallel-8:                    stepBatch=1,953;  requests=15,624; max concurrency=8
parallel-8-reverse-batch:      stepBatch=1,953;  requests=15,624; max concurrency=8
sequential-8-rotating-interleave: stepBatch=0;   sequential operations; max reset concurrency=8
```

The lifecycle probe used distinct seed/start inputs for env A and env B, kept a separate control
env for B, advanced/reset/disposed A, and then compared B's public frame and 16 continuation
steps with the control. It also proved:

- a post-generation env-A action handle was rejected by env B and did not change B;
- an env-A structured decision ID was rejected while B had no matching pending decision and did
  not change B;
- disposing A left B registered and observable;
- resetting/disposal of A did not change B's semantic observation, action choice, digest, or
  terminal continuation;
- distinct seed/start inputs remained behind the perspective-safe public observation boundary;
  no hidden-zone identity or state graph was retained by the probe.

```text
ACTION_HANDLE_ISOLATION=PASS
DECISION_HANDLE_ISOLATION=PASS
REGISTRY_ISOLATION=PASS (per-env ActionRegistry resolution; CardRegistry shared read-only)
RESET_DISPOSE_ISOLATION=PASS
HIDDEN_INFORMATION_ISOLATION=PASS
```

## OCGForge reuse classification

Source inspected read-only: `C:\yogiohML`, verified HEAD
`1727f09eb0fdc4e4e25e3f9ced9748feb4058234`. No OCGForge source was copied into Argentum.

| OCGForge source | Classification | Argentum use / boundary |
| --- | --- | --- |
| `tools/m4/benchmark.py` | `ADAPT_WITH_ARGENTUM_ADAPTER` | Warmup/steady-state separation, persistent lifecycle, complete-result validation. Argentum uses in-process `MultiEnvService`/`EnvWorkerPool`; no subprocess protocol or YGO job identity was reused. |
| `tools/m4/process_metrics.py` | `DIRECT_REUSE` only for a separately isolated Python PID sampler; `ADAPT_WITH_ARGENTUM_ADAPTER` for this run | The generic Windows working-set idea informed the boundary RSS probe. Its `process_count - 1` assumption is invalid for multiple in-process environments and was not copied. |
| `tools/m4/report.py` | `DIRECT_REUSE` as formula concept; `ADAPT_WITH_ARGENTUM_ADAPTER` for schema | Percentile rank and rate/efficiency arithmetic were reproduced for Argentum fields. OCGForge matrix IDs, constants, and YGO semantics were not reused. |
| `tools/m4/job_generation.py` | `ADAPT_WITH_ARGENTUM_ADAPTER` | Deterministic schedule/seed partitioning is the reusable idea. Argentum uses locked Commander specs, not OCGForge seat partitions or rule IDs. |
| `tools/m4/evidence_packaging.py` | `DIRECT_REUSE` as byte-normalization/provenance concept; no source copy | LF normalization and evidence hashing are applicable to report provenance; Argentum paths and schema remain independent. |
| `tools/m4/worker_protocol.py` | `CONCEPT_ONLY` | Strict handshake/result-integrity boundaries are useful concepts only. OCGForge JSONL fields, protocol constants, executable assumptions, and third-party-derived semantics are not reused. |

## Candidate interpretation after scaling

### MEASURED_CURRENT_HOTSPOT

The accepted single-environment JFR still points to the residual semantic JSON/tree/String/UTF-8
pipeline and legal/ability enumeration as the principal normal-path work. The current scaling
run shows that parallel fan-out is the strongest measured multi-environment throughput seam, but
it increases per-operation latency and does not reduce per-transition allocation.

### DEFER_TO_SCALING

The existing `EnvWorkerPool`/`stepBatch` seam is sufficient to characterize next. No scheduler or
parallel execution change is authorized by this task. Before changing it, retain the proven
identity requirements: same-env calls must not overlap, result order must remain request order,
worker failures must fail closed, and shared `EngineServices` collaborators must be proven safe
or isolated.

### NOT_JUSTIFIED_AS_A_NEW_SINGLE_ENV_OPTIMIZATION

No additional single-environment optimization is justified by this task. The prior canonical
sort-key optimization is already accepted; the GameGymEnv cache remains `NOT_PROVEN` for the
normal B1 path; and the ObservationBuilder duplicate work remains a characterization candidate,
not an implementation authorization. The scaling results do not isolate a new single-env causal
gain.

## Trust and final gates

```text
SEMANTIC_TRAJECTORY_REGRESSION=PASS
  scaling test: compact stateDigest/action trajectory hashes matched one-env reference
  isolation test: online frame/choice trace comparison matched all batch/interleave scenarios

REPLAY_EXACTNESS=PASS
  native :gym:environmentV1AcceptanceTest with kotest.filter.tests=.*exact-pair.*
  replay gate: PASS; privacy gate: PASS; other exact-pair class cases explicitly SKIPPED

B0_TRUST_INVARIANTS=PASS for targeted local regression
  B0HarnessTimeoutPolicyTest: 3/3 PASS
  no B0-64 soak or hosted CI was run in this task

PROFILER_DEFAULT_DISABLED=PASS as a skip gate
  B1PerformanceBaselineTest: SKIPPED without b1.profile=true
  B1ScalingMeasurementTest: SKIPPED without b1.scaling=true
  B1MultiEnvIsolationTest: SKIPPED without b1.scaling.isolation=true

GYM_TEST=PASS
  native :gym:test completed successfully; opt-in benchmark/isolation tests remained skipped
  when the default properties were absent

REPLAY_SERVER_TESTS=PASS
  native :game-server:test --tests "*Replay*" completed successfully; benchmark/diagnostic
  cases that are intentionally skipped remained explicitly SKIPPED

RELEVANT_GYM_SERVER_TESTS=PASS
  native :gym-server:test --tests "*EnvControllerTest"; 16/16 PASS

JUST_WRAPPER=BLOCKED
  just test-gym and just test-server fail before Gradle because WSL cannot launch /bin/bash
  (CreateProcessCommon:818 / execvpe(/bin/bash) failed). Native JDK-21 Gradle fallbacks above are
  separate evidence, not a claim that the wrapper passed.

HOSTED_CI=NOT_ESTABLISHED
```

The first method-filtered replay attempt had a passing target test but failed as a whole because
Gradle could not find its binary result file. It is not used as the final gate result. The
subsequent Kotest system-property run completed successfully and is the result recorded above.

## Final status and recommendation

```text
1_ENV=PASS locally; 8 episodes, 16,000 transitions, concurrency=1
2_ENV=PASS locally; 8 episodes, 16,000 transitions, concurrency=2
4_ENV=PASS locally; 8 episodes, 16,000 transitions, concurrency=4
8_ENV=PASS locally; 8 episodes, 16,000 transitions, concurrency=8

B1_FINAL_PASS=NOT_CLAIMED
  reason: no arbitrary throughput target exists, but Hosted CI is not established; the retained
          memory-per-environment limitation is a later capacity-study concern, not a B1 blocker

RECOMMENDED_NEXT_TASK=B1 hosted CI/final exact-head acceptance review
NEXT=INDEPENDENT_REVIEW
DATA_TRUSTED=NO
```

The local measurement scope is complete and does not justify beginning B2 or implementing another
single-environment optimization. Any future optimization must preserve the exact public semantic
trajectory, candidate/domain completeness, privacy boundary, deterministic policy/RNG behavior,
replay fidelity, and ZERO-UNSUPPORTED contract.
