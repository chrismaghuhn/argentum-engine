# Pre-C1 performance diagnostic — 2026-09-08

Task: `PRE_C1_PERFORMANCE_DIAGNOSTIC_01`

Status: **BLOCKED before a valid final trusted performance baseline**.

## 1. Executive result

The live fork was fetched and PR #157 was verified as merged into the measured source:
`origin/main=7b6da4f9b9406cfc8d6826fa7b748468a409f425`, with the reviewed PR head
`ed56a538b6d2bd7ba3105cd7c29a42dff95de81d` as the merge commit's second parent.

The existing accepted History-D witness still passes its 64-choice test. However, when the same
locked Akiri/Chevill path is run with the History-C/D binding enabled, the first full-workload
attempt fails closed after 92 successful choices, at committed step 93:

```text
History-D operation rejected: HISTORY_A_PROJECTION_INCOMPLETE
CardCycledEvent -> REQUIRES_SEMANTIC_REFERENCE_C
last events = ManaSpentEvent, CardsDiscardedEvent, ZoneChangeEvent, CardCycledEvent, CardsDrawnEvent
```

This is a correctness/completeness blocker, not a performance result. The diagnostic therefore does
not claim current final trusted throughput, scaling, generation, reader, replay, canonical-byte, or
D4-overhead metrics. No production semantics, schema, replay, privacy, or trajectory membership was
changed, and no optimization was attempted.

The former History-D final acceptance remains historical evidence for the bounded 64-choice witness.
The longer reachable seed-0 characterization reopens the current History-D/Track-B gate; it does not
retroactively invalidate the separately accepted B2 trajectory source, Commander environment, or
`DATA_TRUSTED` foundation.

## 2. Exact source provenance

```text
TASK=PRE_C1_PERFORMANCE_DIAGNOSTIC_01
BASE=7b6da4f9b9406cfc8d6826fa7b748468a409f425
MEASUREMENT_SOURCE_HEAD=7b6da4f9b9406cfc8d6826fa7b748468a409f425
PARENT=16870fb90dd34044b6dce72722b19335e6ac26fa
ORIGIN_MAIN=7b6da4f9b9406cfc8d6826fa7b748468a409f425
UPSTREAM_MAIN=99ff8de3a7e94c810dbd30e3a48167f0bee94eb3
PR_157_HEAD=ed56a538b6d2bd7ba3105cd7c29a42dff95de81d
PR_157_MERGE_SHA=7b6da4f9b9406cfc8d6826fa7b748468a409f425
HISTORY_D_IMPLEMENTATION_PASS=YES
HISTORY_D_CODE_REVIEW_PASS=YES
HISTORY_D_HOSTED_CI_PASS=YES
HISTORY_D_PREVIOUS_FINAL_ACCEPTANCE_PASS=YES
HISTORY_D_FINAL_ACCEPTANCE_PASS=NO__CURRENT_GATE_REOPENED
HISTORY_D_CURRENT_GATE=REOPENED__REACHABLE_CARD_CYCLED_EVENT
HISTORY_D_HOSTED_RUN=34173378918
HISTORY_D_HOSTED_COVERAGE=SKIPPED
HISTORY_A_MERGE_SHA=caf688015a35712c07d6fc489319f48c71285dc4
HISTORY_B_MERGE_SHA=a7d73f7bf0e1061a696516b86246bcffb08eae8b
HISTORY_C_INFRASTRUCTURE_MERGE_SHA=16870fb90dd34044b6dce72722b19335e6ac26fa
HISTC_A_MERGE_SHA=7b444962f36aa1e4536f5bdfc91966f7bf46d348
HISTC_B_MERGE_SHA=613814ca3d31bc10962db9ea3234125e4147a619
HISTC_C_MERGE_SHA=ba2bc3e63ba7a45e9fd2b542ab9f3450758de3ca
HISTC_D_MERGE_SHA=16870fb90dd34044b6dce72722b19335e6ac26fa
HISTORY_D_MERGE_SHA=7b6da4f9b9406cfc8d6826fa7b748468a409f425
D4_MERGE_SHA=b270855aabf5afc7639010e921855918cf160b8b
```

The diagnostic worktree was created from the exact `origin/main` source at:
`C:\Users\chris\.config\superpowers\worktrees\argentum-engine\pre-c1-performance-diagnostic-20260908`.
The source checkout was clean before the worktree was created. The existing historical reports in
`docs/ml/` retain their point-in-time pre-acceptance fields; the current acceptance/authorization
state used here is the user-supplied status update tied to the verified PR #157 merge.

## 3. Hardware and runtime configuration

```text
OS=Microsoft Windows 11 Home 10.0.26200 build 26200
CPU=AMD Ryzen 7 5800X 8-Core Processor
PHYSICAL_CORES=8
LOGICAL_CORES=16
JDK=OpenJDK Temurin 21.0.12+8 LTS
JVM=OpenJDK 64-Bit Server VM
GRADLE=9.6.1
TEST_WORKER_HEAP=-Xmx2g
GC=G1 (observed G1 Young/G1 Concurrent/G1 Old MXBeans)
BUILD_MODE=NATIVE_GRADLE_FALLBACK
```

The repository's prescribed `just`/`scripts/gradle-locked` path was attempted for a focused check
and failed before Gradle with Windows `WinError 193`. Native `gradlew.bat` results below are labeled
separately and are not reported as `just` results.

## 4. Measurement methodology

The intended workload was the exact locked Akiri, Fearless Voyager versus Chevill, Bane of Monsters
curriculum. No deck file was modified. The existing deterministic external policy and accepted B1
seed corpus were reused:

- seeds `0..3`;
- starting-player indexes `0` and `1`;
- eight episode labels;
- `maxSteps=2,000`;
- 256 warmup steps per environment where the B1 harness supports warmup;
- no native AI, heuristic fallback, self-play, learner, or corpus generation.

The existing B1 measurement harness did not set `semanticEpisodeId`, so its first successful runs
were deliberately retained only as a **pre-History-D control**. A test-only `preC1.history=true`
switch was added to bind deterministic, reset-unique semantic episode IDs and exercise the current
History-C/D path. The switch caused the concrete blocker above; it was not used to bypass or weaken
the History-A projection.

## 5. Gym baseline

No valid final trusted Gym baseline exists because the history-enabled workload does not complete.
The following control is included only to quantify what the old B1 harness measured; it is not a
current pre-C1 acceptance value.

### Pre-History-D control: B1 scaling, one measured repetition

Artifact: `gym/build/reports/pre-c1/b1-scaling-r1/b1-scaling.json`.

The artifact's inherited B1 metadata still contains the historical B1 base/accepted-head values
(`f50c0c9…` / `9140a56…`). The measured source identity for this report is the worktree HEAD recorded
in section 2; the inherited fields are not used as provenance.

| Environments | Measured transitions | Wall seconds | Transitions/sec | Allocation bytes/transition | GC collections | GC time (ms) | Peak heap (bytes) | Peak RSS (bytes) |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 16,000 | 98.625886 | 162.229214 | 5,546,457 | 542 | 1,022 | 281,487,648 | 870,580,224 |
| 2 | 16,000 | 55.354901 | 289.043964 | 5,543,950 | 475 | 640 | 287,320,464 | 870,629,376 |
| 4 | 16,000 | 33.069966 | 483.822699 | 5,543,697 | 345 | 571 | 363,481,760 | 978,157,568 |
| 8 | 16,000 | 20.633983 | 775.419835 | 5,543,539 | 267 | 598 | 463,698,712 | 1,171,222,528 |

The control-only 8-versus-1 speedup is `4.779779x`, with `0.597472` parallel efficiency. These
figures omit History-C/D because the existing configs used `semanticEpisodeId=null`.

## 6. Per-phase attribution

The B1 corpus-8 control artifact measured 16,000 external transitions and 16,008 observations:

| Control phase | Wall time | Share of control workload | Interpretation |
|---|---:|---:|---|
| Service setup | 23.158 s | 18.91% | Includes registry/classpath setup; not a per-decision cost. |
| Resets | 0.995 s | 0.81% | Eight episode starts. |
| Deterministic policy | 2.101 s | 1.72% | Policy selection was not dominant. |
| Strict transition plus observation | 96.004 s | 78.40% | Rules and observation were combined; History-D was disabled. |
| Diagnostics snapshot query | 0.012 s | 0.01% | Null/empty operational diagnostics query. |
| Explicit canonicalization in baseline pass | 0 s | 0% | The baseline path did not run replay-frame canonicalization. |

For the final History-D path, the following are `MEASUREMENT_NOT_CURRENTLY_SEPARABLE` or
`NOT_RUN`: Rules transition, History-A/B/C/D append, semantic-reference work, ObservationBuilder,
PlayerObservation conversion, legal-domain construction, candidate-domain digest, chosen-input
binding, trajectory encoding, and diagnostics callback. The failure occurs inside the committed
transition-to-history handoff before a complete final run can be timed.

## 7. Canonicalization audit

The control B1 probe recorded the following construction counts over 16,000 transitions:

```text
ObservationBuilder calls                 16,066
GameEnvironment legalActions calls       31,712
LegalActionEnumerator calls              31,350
Legal action candidates                  208,994
Action view constructions                 208,994
Decision option view constructions          2,382
Target-domain derivations                 209,244
Attack-domain derivations                 208,994
Blocker-domain derivations                208,994
Payment qualification calls               208,994
PaymentDomainV5 calls                      61,316
Required-payload-field calls              224,640
Action semantic calls                     208,994
Stable ability-key calls                  191,349
Structural ability signature calls        270,052
Structural ability JSON calls             461,401
```

The same-action duplicate counts observed by the test-only probe were:

```text
resolveActivatedAbility                 192,149 duplicate calls
targetDomain                                200 duplicate calls
targetCostDependency                        200 duplicate calls
paymentDomainRequest                        696 duplicate calls
paymentDomainV5                             696 duplicate calls
```

The historical `~5.678x` canonical sort-key redundancy was **not** remeasured. The existing probe
does not instrument `ObservationCanonicalizer.sortSemanticActionFingerprints`, and the control
baseline does not execute the explicit replay canonicalization pass. The control JFR sampled the
overall `ObservationCanonicalizer.canonicalize` method at 1.69% of execution samples in the
multi-environment recording, but that is not a sort-key call count and is not final-path evidence.

```text
CANONICAL_SORT_KEY_CALLS=NOT_MEASURED
CANONICAL_SORT_KEY_UNIQUE_INPUTS=NOT_MEASURED
CANONICAL_SORT_KEY_REDUNDANCY_RATIO=NOT_DETERMINED
```

## 8. Observation, domain, and history size

No final trusted decision sample reached trajectory encoding, so authoritative canonical byte
distribution is `NOT_RUN`. In particular, no control bytes are promoted to current History-D
representation sizes.

The partial prefix available at the controlled failure was:

```text
Perspective = first locked player perspective
History entries = 167
Canonical history bytes = 33,584
Semantic references = 42
Typed relations = 0
```

This is a failed-prefix characterization only, not an episode-size distribution.

## 9. PerspectiveHistory cost

The current source does not perform a valid complete History-D append for the full locked workload.
The bounded probe proves that the first rejected transition is a `CardCycledEvent` whose History-A
classification requires semantic-reference support that is not available to the accepted automatic
path. Because the append is fail-closed, no history timing, allocation, or cumulative episode size
can be reported as a valid final workload metric.

The prefix evidence does not establish whether the complete implementation would be close to
`O(new committed history delta)` or would accidentally perform `O(total episode history)` work.
That measurement remains open after the correctness boundary is repaired/accepted.

## 10. Allocation and GC

Control-only corpus-8 B1 allocation and GC evidence:

```text
Allocated bytes                    90,305,094,024
Allocated bytes/transition          5,644,068.3765
GC: G1 Young                       547 collections / 1,339 ms
GC: G1 Concurrent                  152 collections /   532 ms
GC: G1 Old                           0 collections /     0 ms
Total MXBean GC time               1,871 ms
Control wall time                 122.450489 s
Control GC-time share               1.527965%
Control peak heap                 306,607,640 bytes
```

The control JFR sampled allocation pressure as:

| Allocated class | Sampled pressure |
|---|---:|
| `byte[]` | 55.75% |
| `java.util.LinkedHashMap$Entry` | 7.39% |
| `EntityId` | 6.06% |
| `String` | 3.89% |
| `Object[]` | 3.44% |
| `StringBuilder` | 2.77% |
| `HashMap$Node[]` | 2.73% |
| `ArrayList` | 2.28% |

These are control-path samples. They cannot be attributed to final History-D, trajectory, or reader
costs until the trusted workload completes.

## 11. Trusted trajectory generation

`EnvironmentV1TrustedGenerationTest` was not run on the current diagnostic branch. Running its
existing path without the History-D binding would characterize an intermediate architecture; adding
the binding would hit the same unresolved History-A completeness boundary before a trusted dataset
could be published.

```text
TRUSTED_GENERATION=NOT_RUN_FINAL_TRUSTED
LARGE_CORPUS_GENERATED=NO
```

## 12. A7 reader

No finalized current-head dataset containing complete History-D-enabled trajectories was available
for the strict A7 reader. The existing `PostB2TrajectoryReaderMeasurementTest` requires an explicit
dataset path and was not run against an intermediate no-history artifact.

```text
A7_READER=NOT_RUN
READER_DIAGNOSTICS=NOT_RUN
```

## 13. Replay verification

Exact replay verification was not run as a performance workload because the final trusted generation
path did not produce a complete current-head trajectory. No replay contract or verification rule was
weakened.

```text
REPLAY_VERIFICATION=NOT_RUN_FINAL_TRUSTED
REPLAY_CHANGES=0
```

## 14. Diagnostics overhead

The prior three-transition D4 characterization was not reused as a production overhead percentage.
The requested disabled/scalar/sidecar matrix was not run because the final trusted locked workload
fails before a comparable complete sample. Synchronous sidecar waiting was not inserted into the
workload to manufacture a measurement.

```text
DIAGNOSTICS_DISABLED_THROUGHPUT=NOT_RUN_FINAL_TRUSTED
DIAGNOSTICS_SCALAR_THROUGHPUT=NOT_RUN_FINAL_TRUSTED
DIAGNOSTICS_SIDECAR_THROUGHPUT=NOT_RUN_FINAL_TRUSTED
```

## 15. Multi-environment scaling

The B1 1/2/4/8 matrix completed only with History-D inactive. It is retained as a control and is not
a current trusted scaling result. No 16- or 32-environment run was attempted.

The control JFR showed no evidence that a shared filesystem was the dominant cost. It did show
substantial application allocation and hash/collection activity, but the final path cannot be ranked
against those costs yet. The limiting factor for this diagnostic is the fail-closed History-A
correctness boundary, not an inferred CPU/GC/filesystem bottleneck.

## 16. Historical comparison

| Metric | Historical value | Current value | Delta | Comparable? | Interpretation |
|---|---:|---:|---:|---|---|
| Single-env transitions/sec | 204.772 | 162.229 control | not claimed | NO | Current control omits History-D and uses a different source/head contract. |
| 8-env aggregate transitions/sec | 742.022 | 775.420 control | not claimed | NO | Same reason; control is not final trusted architecture. |
| 8-vs-1 speedup | 3.624x | 4.779779x control | not claimed | NO | Different history activation and run configuration. |
| Trusted generation decisions/sec | 8.714 | NOT_RUN | not available | NO | Current final generator did not complete. |
| A7 reader decisions/sec | ~70.9 | NOT_RUN | not available | NO | No current finalized History-D dataset. |
| Canonical bytes/decision | Historical #119 only | NOT_RUN | not available | NO | No current final trajectory sample. |
| Observation/domain/history byte shares | Historical #119 only | NOT_RUN | not available | NO | History-D path failed before publication. |
| Canonical sort-key redundancy | ~5.678x historical | NOT_DETERMINED | not available | NO | Exact current sort-key calls were not instrumented. |
| Allocation/decision | Historical context only | 5,644,068 control | not claimed | NO | Control omits History-D and includes only B1 baseline allocation. |
| Peak reader heap | Historical 4 GiB PASS / 2 GiB boundary | NOT_RUN | not available | NO | No current final reader workload. |
| GC share | Historical context only | 1.527965% control | not claimed | NO | Control-only MXBean evidence. |

## 17. Top hotspots

The only available profiles are explicitly control-only JFR recordings. They are useful for choosing
what to recheck after the correctness boundary is fixed, but they are not final trusted hotspots.

| Rank | Method/class | Module/role | JFR evidence | Allocation relevance | Safety classification |
|---:|---|---|---:|---|---|
| 1 | `HashMap.getNode` | JVM / collection lookup | 24.46% of multi-env control samples | Indirect; hash-map churn | `NEEDS_MORE_MEASUREMENT` |
| 2 | `AbstractStringBuilder.ensureCapacityInternal` | JVM / text construction | 6.55% of multi-env control samples | Correlates with temporary strings/JSON | `NEEDS_MORE_MEASUREMENT` |
| 3 | `ActivatedAbilityEnumerator.enumerateOwnPermanents` | Rules / legal enumeration | 2.76% of multi-env control samples | Collection and action-domain churn | `RULES_SENSITIVE` |
| 4 | `JsonObject.toString` | Canonicalization/serialization | 2.41% of multi-env control samples | JSON tree/string allocation | `DETERMINISM_SENSITIVE` |
| 5 | `ObservationCanonicalizer.canonicalize` | Canonicalization | 1.69% of multi-env control samples | Sampled `byte[]`/string pressure | `SAFE_WITH_EQUIVALENCE_TEST` |

Single-env control JFR additionally showed `HashMap.getNode` at 14.28%, `String.hashCode` at 8.33%,
and `ObservationCanonicalizer.canonicalize` at 1.48%. The recording includes setup/classpath work,
so these percentages are not treated as a clean per-transition profile.

## 18. Safe optimization candidates

No performance candidate is authorized for implementation from this run. The only evidence-supported
future candidates are:

1. Re-profile the complete History-D path after the `CardCycledEvent` projection is accepted. The
   control probe suggests duplicate activated-ability and payment-domain work, but it does not prove
   current final CPU share.
2. Re-measure canonical sort-key calls directly before considering reuse. Any reuse must preserve
   immutable-input lifetime, candidate order, semantic bytes, and digest equality.
3. Re-measure temporary `byte[]`, string, and map allocation on the complete path before considering
   mechanical allocation reduction.

Each future slice requires the invariance gates in the task specification: identical Rules outcomes,
complete legal candidate sets, candidate order, observation/domain/history bytes, chosen input,
replay fidelity, digests, privacy, deterministic replay, trajectory membership, and episode closure.

## 19. Rejected optimization ideas

- Skipping `CardCycledEvent` or treating it as hidden: rejected because it would weaken committed
  history completeness and could change model-visible semantics.
- Running the B1 no-history control as the current baseline: rejected because it omits the accepted
  History-C/D architecture.
- Changing `TrainingObservation`, `TrajectoryV1`, or `CompactReplay` to reduce bytes: rejected as
  outside authorization and not supported by current measurements.
- Caching aliases or canonical values without a lifecycle/version boundary: rejected as
  determinism/privacy-sensitive and unproven.
- Running A7/generation on an old or no-history artifact: rejected because it would not represent the
  final trusted source.
- Starting C1, Teacher, BC, RL, self-play, or large corpus generation: not authorized.

## 20. Recommended first optimization slice

```text
FIRST_OPTIMIZATION_RECOMMENDATION=NONE_BLOCKED_BY_HISTORY_A_PROJECTION_INCOMPLETE
FIRST_OPTIMIZATION_EVIDENCE=CardCycledEvent at committed step 93 is classified REQUIRES_SEMANTIC_REFERENCE_C; History-D rejects the transition as HISTORY_A_PROJECTION_INCOMPLETE
FIRST_OPTIMIZATION_RISK=RULES_SENSITIVE, PRIVACY_SENSITIVE, DETERMINISM_SENSITIVE
```

The smallest next engineering slice is a separately authorized correctness characterization/fix for
the missing `CardCycledEvent` History-A/semantic-reference boundary. It is not a performance PR and
was not implemented here. After independent review of that boundary, rerun this diagnostic from the
new exact `origin/main` before ranking performance work.

## 21. Open measurement gaps

- Complete History-A/C/D projection for every event family reached by the locked curriculum, starting
  with `CardCycledEvent`.
- Re-run the 1/2/4/8 trusted Gym matrix with History-D active and current source metadata.
- Capture phase-separated Rules, History-A/B/C/D, observation, legal-domain, canonicalization,
  trajectory, and diagnostics timings without double-counting nested work.
- Directly count canonical sort-key calls, unique semantic inputs, duplicates, and digest calls.
- Measure current canonical bytes for PlayerObservation, CompleteLegalDomain, chosen input,
  PerspectiveHistory, commander state, and metadata.
- Measure complete History-D append deltas, alias/reference state, history canonicalization, and
  cumulative trajectory contribution; test for per-decision `O(total history)` behavior.
- Run bounded trusted generation, strict A7 reading at 4 GiB, exact replay verification, and the
  disabled/scalar/sidecar D4 matrix only after a complete trusted workload exists.
- Capture clean single-env, multi-env, generation, and reader JFR profiles with setup separated from
  steady-state workload.

## Output flags

```text
TASK=PRE_C1_PERFORMANCE_DIAGNOSTIC_01
BASE=7b6da4f9b9406cfc8d6826fa7b748468a409f425
HEAD=7b6da4f9b9406cfc8d6826fa7b748468a409f425
ORIGIN_MAIN=7b6da4f9b9406cfc8d6826fa7b748468a409f425
UPSTREAM_MAIN=99ff8de3a7e94c810dbd30e3a48167f0bee94eb3

HISTORY_A_FINAL_ACCEPTANCE_PASS=YES
HISTORY_B_FINAL_ACCEPTANCE_PASS=YES
HISTC_A_FINAL_ACCEPTANCE_PASS=YES
HISTC_B_FINAL_ACCEPTANCE_PASS=YES
HISTC_C_FINAL_ACCEPTANCE_PASS=YES
HISTC_D_FINAL_ACCEPTANCE_PASS=YES
HISTORY_D_PREVIOUS_FINAL_ACCEPTANCE_PASS=YES
HISTORY_D_FINAL_ACCEPTANCE_PASS=NO__CURRENT_GATE_REOPENED
HISTORY_D_CURRENT_GATE=REOPENED__REACHABLE_CARD_CYCLED_EVENT
HISTORY_C_INFRASTRUCTURE_COMPLETE=YES
TRACK_B_HISTORY_COMPLETE=NO__REOPENED_BY_REACHABLE_HISTORY_A_GAP
TRACK_C_DIAGNOSTICS_COMPLETE=YES
DIAGNOSTIC_EXECUTION_AUTHORIZED=YES

DIAGNOSTIC_CORE_FINDING=CONFIRMED
CARD_CYCLED_BLOCKER=CONFIRMED
P1_CURRENT_HISTORY_GATE_STATUS=OPEN
P2_CARD_CYCLED_RED_ASSERTION=PASS
PRE_C1_DIAGNOSTIC_CODE_REVIEW_PASS=NO__NARROW_REMEDIATION_REQUIRED

COMMANDER_ENVIRONMENT_V1_COMPLETE=YES
PHASE_A_FINAL_ACCEPTANCE_PASS=YES
B0_FINAL_ACCEPTANCE_PASS=YES
B1_FINAL_ACCEPTANCE_PASS=YES
B2_FINAL_ACCEPTANCE_PASS=YES
DATA_TRUSTED=YES

BUILD_MODE=NATIVE_GRADLE_FALLBACK
JDK=Temurin-21.0.12+8
OS=Windows_11_Home_10.0.26200_build_26200
CPU=AMD_Ryzen_7_5800X_8-Core_Processor
LOGICAL_CORES=16
HEAP_CONFIGURATION=TestWorker_-Xmx2g
GC=G1

LOCKED_ENVIRONMENT_USED=YES_FOR_CONTROL_AND_FAILURE_PROBE
LOCKED_DECKS_CHANGED=NO

SINGLE_ENV_TRANSITIONS_PER_SEC=NOT_RUN_FINAL_TRUSTED_CONTROL_ONLY=162.229214
TWO_ENV_TRANSITIONS_PER_SEC=NOT_RUN_FINAL_TRUSTED_CONTROL_ONLY=289.043964
FOUR_ENV_TRANSITIONS_PER_SEC=NOT_RUN_FINAL_TRUSTED_CONTROL_ONLY=483.822699
EIGHT_ENV_TRANSITIONS_PER_SEC=NOT_RUN_FINAL_TRUSTED_CONTROL_ONLY=775.419835
EIGHT_VS_ONE_SPEEDUP=NOT_RUN_FINAL_TRUSTED_CONTROL_ONLY=4.779779x
EIGHT_ENV_PARALLEL_EFFICIENCY=NOT_RUN_FINAL_TRUSTED_CONTROL_ONLY=0.597472

TRUSTED_GENERATION_DECISIONS_PER_SEC=NOT_RUN
A7_READER_DECISIONS_PER_SEC=NOT_RUN
REPLAY_VERIFICATION_ACTIONS_PER_SEC=NOT_RUN

MEAN_CANONICAL_BYTES_PER_DECISION=NOT_RUN
OBSERVATION_BYTE_SHARE=NOT_RUN
DOMAIN_BYTE_SHARE=NOT_RUN
HISTORY_BYTE_SHARE=NOT_RUN

CANONICAL_SORT_KEY_CALLS=NOT_MEASURED
CANONICAL_SORT_KEY_UNIQUE_INPUTS=NOT_MEASURED
CANONICAL_SORT_KEY_REDUNDANCY_RATIO=NOT_DETERMINED
CANONICALIZATION_CPU_SHARE=NOT_RUN_FINAL_CONTROL_ONLY_JFR_CANONICALIZE=1.69%

ALLOC_BYTES_PER_DECISION=NOT_RUN_FINAL_CONTROL_ONLY=5644068.3765
GC_TIME_SHARE=NOT_RUN_FINAL_CONTROL_ONLY=1.527965%
PEAK_HEAP=NOT_RUN_FINAL_CONTROL_ONLY=306607640

DIAGNOSTICS_DISABLED_THROUGHPUT=NOT_RUN
DIAGNOSTICS_SCALAR_THROUGHPUT=NOT_RUN
DIAGNOSTICS_SIDECAR_THROUGHPUT=NOT_RUN

TOP_HOTSPOT_1=HashMap.getNode__24.46%_CONTROL_ONLY
TOP_HOTSPOT_2=AbstractStringBuilder.ensureCapacityInternal__6.55%_CONTROL_ONLY
TOP_HOTSPOT_3=ActivatedAbilityEnumerator.enumerateOwnPermanents__2.76%_CONTROL_ONLY
TOP_HOTSPOT_4=JsonObject.toString__2.41%_CONTROL_ONLY
TOP_HOTSPOT_5=ObservationCanonicalizer.canonicalize__1.69%_CONTROL_ONLY

FIRST_OPTIMIZATION_RECOMMENDATION=NONE_BLOCKED_BY_HISTORY_A_PROJECTION_INCOMPLETE
FIRST_OPTIMIZATION_EVIDENCE=CardCycledEvent_requires_semantic_reference_C_at_step_93
FIRST_OPTIMIZATION_RISK=RULES_SENSITIVE_PRIVACY_SENSITIVE_DETERMINISM_SENSITIVE

SEMANTIC_CHANGES=0
PRIVACY_CHANGES=0
REPLAY_CHANGES=0
TRAJECTORY_MEMBERSHIP_CHANGES=0
TRAINING_STARTED=NO
LARGE_CORPUS_GENERATED=NO

PERFORMANCE_DIAGNOSTIC_PASS=BLOCKED
PERFORMANCE_OPTIMIZATION_AUTHORIZED=NO

C0_FINAL_ACCEPTANCE_PASS=NO
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
RL_AUTHORIZED=NO
SELF_PLAY_AUTHORIZED=NO
```

STOP: no optimization, C1, training, or corpus generation was started.
