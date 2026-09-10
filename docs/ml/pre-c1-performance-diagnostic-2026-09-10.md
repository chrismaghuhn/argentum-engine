# Pre-C1 performance diagnostic — 2026-09-10

```text
TASK=PRE_C1_PERFORMANCE_DIAGNOSTIC_02
BASE=32cac5f2f99eda863414fe9881df0ca93dfff537
ORIGIN_MAIN=32cac5f2f99eda863414fe9881df0ca93dfff537
UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872
```

## Executive result

The diagnostic is **blocked before trusted timing**. The existing B1 1/2/4/8 harness was run with
the accepted History-D path enabled through `preC1.history=true`. It failed closed with
`HISTORY_A_PROJECTION_INCOMPLETE` before it could produce a complete comparable scaling artifact.

This is a correctness result, not a performance result. No timing, allocation, GC, heap, RSS,
phase, History-D-cost, or hotspot value from the failed partial run is promoted as a trusted
measurement.

```text
TRUSTED_PATH_COMPLETES=NO
PERFORMANCE_DIAGNOSTIC_PASS=NO
PERFORMANCE_OPTIMIZATION_AUTHORIZED=NO
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
```

The accepted History-D final-acceptance gate for its separately ratified locked matrix remains
unchanged. This B1 seed/start-player corpus is a broader diagnostic workload and exposes a
separate reachable History-A gap.

## Source and method

The previous diagnosis is [`pre-c1-performance-diagnostic-2026-09-08.md`](./pre-c1-performance-diagnostic-2026-09-08.md).
The existing measurement implementation is
[`B1ScalingMeasurementTest.kt`](../../gym/src/test/kotlin/com/wingedsheep/gym/B1ScalingMeasurementTest.kt).
It already provides the 8-cell B1 corpus, 1/2/4/8 environment scaling, warmup, repeated runs,
reset/policy/step timing, allocation, GC, heap/RSS, concurrency, and optional JFR measurement.

The exact B1 cells are:

```text
roster=Akiri vs Chevill
engine seeds=0,1,2,3
startingPlayerIndex=0,1
cells=8
maxSteps=2000
policy=DeterministicExternalPolicy
policy seed=the existing per-cell B1 policy-seed derivation
History-D=enabled with deterministic nonblank semanticEpisodeId
native AI/fallback=not used
```

The main measurement command was the native Windows fallback because the repository wrapper cannot
start on this host:

```text
gradlew.bat :gym:test --tests "*B1ScalingMeasurementTest" --max-workers=1
  -Db1.scaling=true
  -DpreC1.history=true
  -Db1.scaling.repetitions=3
  -Db1.scaling.warmupSteps=256
```

The Gradle properties were passed quoted because unquoted `-D` arguments are misparsed by the
Windows shell. The first correctly formed run failed in the existing History-D append path at
`GameGymEnv.appendAutomaticPerspectiveHistory`, before `B1ScalingMeasurementTest` wrote a scaling
artifact.

## A — correctness gate before timing

The failed B1 run reported:

```text
failure=HistoryDOperationException
code=HISTORY_A_PROJECTION_INCOMPLETE
stage=GameGymEnv.appendAutomaticPerspectiveHistory
trusted scaling artifact=not produced
```

To attribute the failure without changing production or measurement semantics, a temporary
test-only probe executed each of the eight cells once with a fresh trusted `GameGymEnv`, the same
persisted decks/configuration, `maxSteps=2000`, and a nonblank deterministic History-D episode ID.
The probe was removed before this report was committed.

| Engine seed | Starting player | Successful choices | Committed steps | Closure/failure | Raw events on failing transition |
| ---: | ---: | ---: | ---: | --- | --- |
| 0 | 0 | 2000 | 2000 | `INTERRUPTED / HORIZON_REACHED` | none |
| 0 | 1 | 2000 | 2000 | `INTERRUPTED / HORIZON_REACHED` | none |
| 1 | 0 | 638 | 639 | `HISTORY_A_PROJECTION_INCOMPLETE` | `SpellFizzledEvent, ZoneChangeEvent` |
| 1 | 1 | 665 | 666 | `HISTORY_A_PROJECTION_INCOMPLETE` | `SpellFizzledEvent, ZoneChangeEvent` |
| 2 | 0 | 1082 | 1083 | `HISTORY_A_PROJECTION_INCOMPLETE` | `SpellFizzledEvent, ZoneChangeEvent` |
| 2 | 1 | 1073 | 1074 | `HISTORY_A_PROJECTION_INCOMPLETE` | `SpellFizzledEvent, ZoneChangeEvent` |
| 3 | 0 | 2000 | 2000 | `INTERRUPTED / HORIZON_REACHED` | none |
| 3 | 1 | 2000 | 2000 | `INTERRUPTED / HORIZON_REACHED` | none |

Exact correctness summary for this one probe pass:

```text
WORKLOAD_CELLS=8
HORIZON_CLOSURES=4
TERMINAL_CLOSURES=0
HISTORY_D_FAILURE_CELLS=4
HISTORY_D_FAILURES=4
FIRST_OBSERVED_FAILURE_CODE=HISTORY_A_PROJECTION_INCOMPLETE
FIRST_OBSERVED_FAILURE_EVENT=SpellFizzledEvent
UNSUPPORTED_POLICY_GAPS=0
PUBLIC_CHOICE_REJECTIONS=0
NATIVE_AI_OR_FALLBACK_COUNT=0
```

The failing transition's safe event-class evidence is retained only as the class names above. No
runtime entity IDs, card names, object stamps, hidden state, or GameState dumps were used.

This report does not characterize or fix `SpellFizzledEvent`; it is the correctness boundary that
prevents a trusted performance claim on the 8-cell corpus.

```text
HISTORY_D_FAILURES=4 of 8 correctness-probe cells
UNSUPPORTED/FALLBACK_COUNT=0 observed policy gaps or native fallbacks
PUBLIC_CHOICE_REJECTIONS=0
```

## B — Gym scaling

No trusted scaling condition completed, so the requested current-path metrics are not run:

```text
1_ENV_TPS=NOT_RUN_BLOCKED_BY_HISTORY_D
2_ENV_TPS=NOT_RUN_BLOCKED_BY_HISTORY_D
4_ENV_TPS=NOT_RUN_BLOCKED_BY_HISTORY_D
8_ENV_TPS=NOT_RUN_BLOCKED_BY_HISTORY_D
8V1_SPEEDUP=NOT_RUN
8_ENV_PARALLEL_EFFICIENCY=NOT_RUN
ALLOC_BYTES_PER_TRANSITION=NOT_RUN_BLOCKED_BY_HISTORY_D
GC_TIME=NOT_RUN_BLOCKED_BY_HISTORY_D
PEAK_HEAP=NOT_RUN_BLOCKED_BY_HISTORY_D
PEAK_RSS=NOT_RUN_BLOCKED_BY_HISTORY_D
```

The failed run is not converted into a partial throughput estimate. The fact that some individual
cells reach 2000 steps does not make the complete 8-cell comparison corpus valid.

## C — phase attribution

The current B1 harness can measure reset latency, deterministic policy time, combined external
step latency, setup, process CPU, and concurrency. It does not separately time Rules,
ObservationBuilder/legal-domain construction, History-A/B/C/D append/projection, or canonical
digest work inside the returned step path. Because the trusted 8-cell workload fails before
completion, no final-path phase values are reported.

```text
PHASE_ATTRIBUTION=
  setup/reset/policy/combined-step instrumentation exists;
  trusted final values NOT_RUN;
  History-A/B/C/D subphases NOT_SEPARATELY_MEASURABLE;
  semantic canonicalization/digest subphase NOT_SEPARATELY_MEASURABLE
```

## D — History-D size and cost

No complete current B1 cell reached a valid final History-D closure under the full corpus probe.
No history entry count, canonical-history byte distribution, semantic-reference count, or
History-D allocation/cost value is promoted:

```text
HISTORY_D_SIZE/COST=NOT_RUN_FINAL_TRUSTED
HISTORY_ENTRIES=NOT_RUN_FINAL_TRUSTED
CANONICAL_HISTORY_BYTES=NOT_RUN_FINAL_TRUSTED
SEMANTIC_REFERENCE_COUNTS=NOT_RUN_FINAL_TRUSTED
HISTORY_D_ALLOCATION=NOT_RUN_FINAL_TRUSTED
O_TOTAL_HISTORY_BEHAVIOR=NOT_DETERMINED
```

No conclusion about linear versus total-history work is drawn from the failed prefix.

## E — historical control comparison

The old pre-History-D control values are retained only as historical context:

| Environments | Historical control transitions/sec | Current trusted value | Comparable |
| ---: | ---: | ---: | --- |
| 1 | 162.229 | `NOT_RUN` | No |
| 2 | 289.04 | `NOT_RUN` | No |
| 4 | 483.82 | `NOT_RUN` | No |
| 8 | 775.42 | `NOT_RUN` | No |

Those values were measured with History-D disabled and cannot be used as a baseline for the
current accepted History-C/D path. No speedup or regression claim is made.

## F — hotspot characterization

```text
TOP_MEASURED_HOTSPOTS=NONE_ON_COMPLETE_TRUSTED_PATH
JFR=NOT_RUN_TO_COMPLETION
```

The historical control JFR/hotspot observations in the previous report are not reclassified as
current History-D hotspots. Measurement of dominant costs is deferred until the correctness gate
for all eight cells is closed.

## Layer and stop decision

This run found a reachable History-A completeness failure in a broader B1 diagnostic cell. It does
not change the accepted bounded History-D final-acceptance contract, B2/data authority, or
`DATA_TRUSTED=YES`. It does block this performance diagnostic and keeps optimization unauthorized.

```text
HISTORY_D_FINAL_ACCEPTANCE_PASS=YES
TRACK_B_HISTORY_COMPLETE=YES
PERFORMANCE_DIAGNOSTIC_PASS=NO
PERFORMANCE_OPTIMIZATION_AUTHORIZED=NO
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
RL_AUTHORIZED=NO
SELF_PLAY_AUTHORIZED=NO
```

The next correctness work, if separately authorized, is a focused characterization/closure of the
first observed `SpellFizzledEvent` History-A boundary. It is not included here, and no later event
family or post-failure workload was investigated.

```text
STOP_FOR_EXACT_SHA_REVIEW=YES
```
