# B1 Post-Replay Performance Re-Prioritization and Characterization

Status: measurement and analysis only. No production optimization, Rules change, Gym semantic
change, replay change, deck change, policy change, or parallel-execution change was made.

```text
BASE_ORIGIN_MAIN=f50c0c92249fe7d5c2f7b8044b1371462630135e
UPSTREAM_MAIN=36c5a0c2a2c688e2a814940495ce64c55baced25
BRANCH=chris/b1-post-replay-performance-characterization
WORKTREE=dedicated clean worktree
DATA_TRUSTED=NO
PRODUCTION_FILES_CHANGED=0
PRODUCTION_OPTIMIZATIONS=0
PRODUCTION_SEMANTIC_CHANGES=0
```

The live fork and upstream refs were fetched before the worktree was created. The worktree starts
at the live fork `origin/main`, not at the historical head supplied as context. Issue #99 remains
the authoritative B1 acceptance contract. It has no arbitrary games-per-second acceptance target;
the remaining acceptance work is measurement, semantic trajectory/replay evidence, and hosted CI.

## Required result fields

```text
BASE_ORIGIN_MAIN:
  f50c0c92249fe7d5c2f7b8044b1371462630135e
UPSTREAM_MAIN:
  36c5a0c2a2c688e2a814940495ce64c55baced25

CURRENT_CORPUS8_MEDIAN:
  87.137365s workload wall; median of three probe-free runs
CURRENT_TRANSITIONS_PER_SEC:
  183.618130
CURRENT_ALLOCATION_PER_TRANSITION:
  5,117,938.195 thread-allocated bytes; median of the same three runs

DEEP_AUDIT_REPRIORITIZED:YES

CANONICAL_SORT_KEY:
  ALREADY_FIXED; accepted decorate-sort is present on current origin/main
CACHE_CANDIDATE:
  NOT_PROVEN for the normal B1 path; the early cache guard remains after build work
REPLAY_VERSION_BLOCKER:
  CLOSED; V5 exact-pair gate PASS on current origin/main

OBSERVATION_BUILDER_DUPLICATION:
  DUPLICATE_WORK_PROVEN, but not the current leading CPU hotspot
TARGET_DOMAIN_COUNTERS:
  witness=28,650 calls for 28,555 builder-input candidates; 76 same-action extra calls
  normal4=109,527 calls for 109,177 builder-input candidates; 280 same-action extra calls
  corpus8=209,236 calls for 208,986 builder-input candidates; 200 same-action extra calls
PAYMENT_QUALIFICATION_COUNTERS:
  witness=28,555; normal4=109,177; corpus8=208,986; one per builder-input action
PAYMENT_DOMAIN_COUNTERS:
  paymentDomainV5For witness/normal4/corpus8=8,434/30,760/61,308
  PaymentDomainBuilder.buildV5 attempts=233/1,095/1,516
  target-dependent relation calls=0/0/0; same-action V5 extras=103/488/696
ABILITY_IDENTITY_COUNTERS:
  stableAbilityKey=26,490/100,533/191,349
  resolveActivatedAbility=54,111/205,380/389,738
  same-action resolve extras=26,794/101,653/192,149
  stableAbilityOrdinal=18,901/68,164/129,962
  structuralAbilitySignature=41,237/143,225/270,052
  structuralAbilityJson=67,727/243,758/461,401
ACTION_METADATA_COUNTERS:
  actionSemantic=28,555/109,177/208,986
  requiredPayloadFields=30,506/117,001/224,632
  paymentDomainRequest=8,434/30,760/61,308
  targetCostDependency=26,585/100,883/191,599

STRICT_PRESTATE_ENUMERATION:
  exact test-path totals witness/normal4/corpus8:
  GameEnvironment.legalActions=3,971/15,898/31,704
  LegalActionEnumerator.enumerate=3,921/15,718/31,342
  ObservationBuilder.build=2,020/8,074/16,058
STATE_DIGEST_REMAINING_COST:
  MEASURED_CURRENT_HOTSPOT; current JFR still shows canonicalizer/string/byte work after sort-key fix
PAYMENT_SOURCE_DISCOVERY:
  V5 builder source-discovery calls=233/1,095/1,516; target-dependent path not exercised
EVENT_HISTORY_ALLOCATION:
  UNKNOWN_NEEDS_MEASUREMENT; events = events + result.events copies cumulative history
REPLAYFINGERPRINT_OVERHEAD:
  NOT_RUN; exact gate is PASS, but no isolated existing timing boundary exists

RANK_1:
  remaining semantic JSON/tree/String/UTF-8/SHA-256/hex materialization
RANK_2:
  strict pre-transition legal-action re-enumeration
RANK_3:
  ObservationBuilder derived target/payment/ability work reuse

IS_ANOTHER_SINGLE_ENV_OPTIMIZATION_JUSTIFIED:NO

RECOMMENDED_NEXT_TASK:
  B1 scaling and final measurement (1/2/4/8 environments, latency percentiles, memory/GC,
  reset trend, semantic trajectory, replay exactness, and Hosted CI); do not start an optimization
```

The current corpus median is a live measurement, not a causal comparison with the accepted
pre-fix number. The three probe-free workload times were `87.137365s`, `85.322476s`, and
`90.960881s`; the same timing boundary was used, but JVM/host noise is visible. The first two
artifacts were collected before the test-only profiler metadata constant was corrected to the live
head; their engine/runtime source was already `f50c0c9`. The final artifact records the live head.
No production class changed between those runs.

## Evidence labels and measurement boundary

```text
MEASURED            direct result from a bounded current-head run, JFR view, or scalar probe
CODE-INSPECTION     direct current-source control/data-flow fact
INFERENCE           arithmetic or call-site consequence of measured/source facts
HYPOTHESIS          plausible attribution not established by the current run
NOT_RUN             deliberately not measured, with the reason stated
```

The characterization probe is test-only. It patches compiled class outputs only inside an opt-in
test JVM, records scalar counts plus short-lived integer-key maps for the current build, and
restores every class file before the test exits. It does not retain `GameState`,
`TrainingObservation`, `LegalAction`, JSON, payment-domain DTOs, or card/state graphs. It is not
part of the production classpath and is disabled unless `b1.characterize=true` is explicitly
forwarded to the test worker.

## Current-head profiler

### Workload contract

The existing `B1PerformanceBaselineTest` was reused. It drives the public
`MultiEnvService.create/step/submitDecision` boundary with the locked Akiri/Chevill curriculum,
the existing deterministic external policy, Commander format, starting life 40, starting hand 7,
skip mulligans, no hand smoother, explicit starting-player index, and `maxSteps=2,000`. Every
workload reached the requested transition and semantic-decision horizon. No candidate, order, RNG,
visibility, policy, or replay behavior was changed.

The workload timer and JVM after-snapshot complete before JFR stop/dump. JFR stop and dump are
reported as artifact overhead, not workload time. The current profiler checks the diagnostics
sidecar after every returned observation; that check is existing test-harness work.

### Probe-free results

| Workload | Episodes | Transitions | Wall | Transitions/s | Transition + observation | Setup | Policy | Allocation/transition | Heap peak | GC time |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| witness | 1 | 2,000 | 33.653906s | 59.428 | 13.355848s | 19.358587s | 0.299253s | 6,309,177.996 | 293.561 MiB | 1,112ms |
| normal4 | 4 | 8,000 | 69.729831s | 114.729 | 46.033902s | 21.844767s | 0.983598s | 5,283,395.906 | 323.587 MiB | 1,142ms |
| corpus8 run 1 | 8 | 16,000 | 87.137365s | 183.618 | 66.727320s | 18.120790s | 1.532815s | 5,118,232.760 | 309.729 MiB | 1,442ms |
| corpus8 run 2 | 8 | 16,000 | 85.322476s | 187.524 | 64.515305s | 18.600018s | 1.448074s | 5,117,224.602 | 356.984 MiB | 1,085ms |
| corpus8 run 3 | 8 | 16,000 | 90.960881s | 175.900 | 71.335334s | 17.321586s | 1.589050s | 5,117,938.195 | 318.613 MiB | 1,752ms |
| corpus8 median | 8 | 16,000 | **87.137365s** | **183.618** | 66.727320s | 18.120790s | 1.532815s | **5,117,938.195** | 324.774 MB | 1,442ms |

These values are not compared to the accepted `90.761s` median as an isolated speedup: that
comparison also changed the measurement boundary and normal JVM variance is visible.

Current corpus accounting is 16,008 returned observations, 211,318 public legal/decision-option
entries, 47 structured pending-decision observations, and 16,000 semantic decisions. The external
policy remains a small phase at approximately 1.45–1.59s. First registry/service setup remains
approximately 17.3–18.6s per test pass and is not a per-transition cost.

### Current JFR evidence

`jfr summary`, `hot-methods`, `allocation-by-class`, `allocation-by-site`, `gc-pauses`, and
`cpu-load` were run on the second probe-free corpus recording. The recording contained 2,071
execution samples, 24,477 allocation samples, and 418 GC pauses. JFR is sampling evidence, not
exact method self-time.

```text
String.hashCode                                           225 samples / 10.86%
HashMap.getNode                                           189 / 9.13%
AbstractStringBuilder.ensureCapacityInternal              116 / 5.60%
ArraysSupport.mismatch                                      95 / 4.59%
ConcurrentHashMap.transfer                                  65 / 3.14%
HashMap.putVal                                             62 / 2.99%
ClasspathElementZip.open                                   61 / 2.95%
JsonObject.toString lambda                                 30 / 1.45%
ObservationCanonicalizer.canonicalize                      29 / 1.40%
```

Test-worker stack-frame counts (also sampling evidence) were:

```text
ObservationCanonicalizer.canonicalize + default       360
CardRegistry.getCard                                   151
LegalActionEnumerator.enumerate                        136
GameEnvironment.legalActions                           113
ManaAbilityEnumerator.enumerate                         79
ObservationBuilder.resolveActivatedAbility             55
GameGymEnv.build                                         59
ObservationBuilder.actionSemantic                       30
ObservationBuilder.stableAbilityKey                     25
PaymentDomainBuilder.buildV5                              3
ObservationBuilder.paymentDomainV5For                    3
```

Current allocation pressure was led by `byte[]` (62.00%), `LinkedHashMap.Entry` (6.55%), `String`
(4.00%), `StringBuilder` (3.36%), `Object[]` (2.63%), `ArrayList` (2.47%), and
`PredicateContext` (2.06%). Allocation sites were led by `Arrays.copyOf` (26.40%), byte range
copy (12.41%), UTF-16 string bytes (7.21%), `LinkedHashMap.newNode` (6.55%), StringBuilder
construction (4.89%), UTF-8 encoding (3.63%), and `StringBuilder.toString` (2.66%).
`CollectionsKt.plus` was 0.68% of sampled allocation sites. The `gc-heap` view was attempted and
is unavailable in this JDK; `gc-pauses` and MXBean GC deltas were available.

## Full current execution path

The current trusted path is:

```text
MultiEnvService.create / step / submitDecision
  -> GameGymEnv.observe / step / submitDecision
  -> GameGymEnv.build
  -> GameEnvironment.legalActions
  -> LegalActionEnumerator.enumerate(ACTIONS_ONLY)
  -> ObservationBuilder.build
  -> target/combat/payment/action metadata construction
  -> StateDigest.compute
  -> ObservationCanonicalizer.semanticJson
  -> UTF-8 -> SHA-256 -> lowercase hex
  -> fresh opaque action handles / TrainingObservation
```

The post-transition observation must be rebuilt because the immutable `GameState`, priority,
projection generation, legal candidates, and privacy view may have changed. The candidate reuse
question is specifically whether the exact pre-state public legal list already used to publish the
last observation can also satisfy strict current-candidate validation.

### Work classification

| Work | Classification | Current finding |
|---|---|---|
| `GameState.projectedState` for one immutable state | `NECESSARY_ONCE` | `by lazy`; a single immutable state shares its projection. |
| `EnumerationContext` projection/battlefield/mana caches | `NECESSARY_ONCE` per enumeration | Each `LegalActionEnumerator.enumerate` creates a new context; reuse across calls is not established. |
| post-transition legal-action enumeration | `NECESSARY_ONCE` | The Rules state has changed. |
| strict pre-state candidate scan after the prior public observation | `REDUNDANT_ACROSS_UNCHANGED_STATE` | Exact aggregate count is measured; reuse needs a generation proof. |
| target/attack/blocker mapping in one builder | `NECESSARY_ONCE` per input action | These map Rules-owned certificates and privacy-addressability. |
| target-payment qualification / V5 payment construction | `REDUNDANT_WITHIN_OBSERVATION` in observed branches | Same-action duplicate counts are now measured; target-dependent relation path is absent in locked corpus. |
| `resolveActivatedAbility` / structural signatures | `REDUNDANT_WITHIN_OBSERVATION` | Same action receives multiple authoritative provenance/signature resolutions. |
| `StateDigest.compute` | `NECESSARY_ONCE` per returned builder result | Digest is part of the public contract; its implementation remains an optimization target. |
| public semantic replay frame recomputation | `REPLAY_ONLY` | Deliberate equality verification, not normal B1 path. |
| B0 two-perspective privacy builds | `B0_HARNESS_ONLY` | Both perspectives remain required; state-global reuse needs a privacy audit. |
| CompactReplay reconstruction/checkpoint fingerprints | `REPLAY_ONLY` / `B0_HARNESS_ONLY` | Correctness is required and must not be removed for speed. |
| `MtgSetCatalog`/ClassGraph first discovery | `COLD_START_ONLY` | Lazy catalog; reset does not rescan. |
| cumulative `events = events + result.events` | `UNKNOWN_NEEDS_MEASUREMENT` | Full history prefix is copied; current JFR does not isolate its share. |
| `stepBatch` across independent environments | `DEFER_TO_SCALING` | Existing seam, no accepted 1/2/4/8 measurements. |

## Candidate re-prioritization against current `origin/main`

| Candidate | Classification | Current evidence and conclusion |
|---|---|---|
| Canonical legal-action sort-key recomputation | `ALREADY_FIXED` | Current `sortSemanticActionFingerprints` decorates each fingerprint with one existing canonical sort string before stable sorting. The accepted RED/GREEN evidence was `m=182 -> m=26` for `n=f=26`. Residual canonicalization remains rank 1, but the current sort-key plan is not a new candidate. |
| ObservationBuilder repeated action/payment metadata | `DUPLICATE_WORK_PROVEN` | Current test-only counters prove same-action target/payment/ability work. Its current JFR share is below the semantic byte pipeline, so no production seam is authorized. |
| Strict pre-transition legal-action re-enumeration | `MEASURED_CURRENT_HOTSPOT` | 31,342 actual enumerations and 31,704 wrapper calls over 16,000 transitions; current JFR samples both `LegalActionEnumerator` and `GameEnvironment.legalActions`. Safe reuse remains high-risk. |
| Stable activated-ability identity/signatures | `STILL_PLAUSIBLE` | 191,349 stable-key calls and 389,738 resolver calls; same-action resolver extras are large, but exact self-time is not isolated. |
| Early `GameGymEnv` cache guard | `NOT_PROVEN` | The guard is still after enumeration/build. B1 create/step/submitDecision returned their newly built observations and issued zero additional service `observe()` calls. HTTP observe traffic and B0 trainer traffic were not measured. |
| PaymentDomainV5 mana-source discovery reuse | `STILL_PLAUSIBLE` | One source-discovery call per observed V5 builder attempt, 1,516 in corpus8; target-dependent branches were not exercised. Current JFR shows only a small direct sample. |
| B0 privacy fanout | `DEFER_TO_B0_ONLY` | Two perspectives and their privacy checks are required. No B0 phase timing was added. |
| CompactReplay / ReplayFingerprint | `DEFER_TO_B0_ONLY` | The old V5 version blocker is closed by the exact-pair bridge repair. Isolated cost remains `NOT_RUN`. |
| Immutable GameState/event-history allocation | `NOT_PROVEN` | Cumulative event-history copying is visible in source; sampled `CollectionsKt.plus` is not a state/event attribution. Architectural risk is broad. |
| Semantic JSON/string/UTF-8/digest pipeline | `MEASURED_CURRENT_HOTSPOT` | Current JFR still shows canonicalizer recursion, hash/string builders, byte copies, UTF-8, and SHA-256 input materialization after the narrow sort-key fix. |
| Action-handle/remap churn | `STILL_PLAUSIBLE` | `GameGymEnv.build` allocates fresh IDs, a mapping, copied views, and a remapped registry; no current isolated allocation share proves priority. |
| Multi-environment scaling | `DEFER_TO_SCALING` | `MultiEnvService.stepBatch` / `EnvWorkerPool` is the intended independent-environment seam; no 1/2/4/8 throughput or latency matrix exists. |

## ObservationBuilder duplicate-work characterization

### Instrumentation contract

The new characterization is test-only. `B1ObservationBytecodeInstrumentation` uses ASM as a
test-only dependency to patch compiled `ObservationBuilder`, `PaymentDomainBuilder`, and
`GameEnvironment` methods, plus the payment builder's call site. The patch is applied only after
`b1.characterize=true`, before the workload loads those classes, and is restored in `finally`.
It adds no `src/main` probe class or production hook. The default-disabled profiler test does not
patch bytecode and emits no workload artifact.

Per-action correlation uses `System.identityHashCode` integers for objects observed in the current
build. It retains no action objects. The probe records no collision among the action-view indexes
in any required workload; if a collision had occurred, same-action rows would be invalidated rather
than used to authorize a change. Maps are bounded by the current build's action count and are
discarded when that build closes.

### Required workloads and counters

All three characterization runs passed with the expected episode/transition/decision counts:

| Workload | Episodes | Transitions | Returned observations | Public candidates | Structured observations | Builder calls | Builder input candidates | Action views in all builds | Decision option views |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| witness | 1 | 2,000 | 2,001 | 28,846 | 8 | 2,020 | 28,555 | 28,555 | 310 |
| normal4 | 4 | 8,000 | 8,004 | 110,215 | 27 | 8,074 | 109,177 | 109,177 | 1,108 |
| corpus8 | 8 | 16,000 | 16,008 | 211,318 | 47 | 16,058 | 208,986 | 208,986 | 2,382 |

The 19/70/50 builder calls above the returned-observation count are the selected action's
one-action `currentTargetPaymentSnapshot` builds. Their action views are included in the all-build
column. Therefore public action entries are not obtained by treating every action view in the
probe as a distinct returned action; the profiler's returned observations are authoritative for
the public-candidate column.

The scalar counter results are:

| Counter | Witness | Normal4 | Corpus8 | Meaning |
|---|---:|---:|---:|---|
| `GameEnvironment.legalActions` | 3,971 | 15,898 | 31,704 | Wrapper calls, including early returns |
| `LegalActionEnumerator.enumerate` | 3,921 | 15,718 | 31,342 | Actual enumerator entries |
| `ObservationBuilder.build` | 2,020 | 8,074 | 16,058 | All builder calls, including one-action snapshots |
| builder-input legal candidates | 28,555 | 109,177 | 208,986 | `legalActions.size` supplied to each builder |
| action-view constructions | 28,555 | 109,177 | 208,986 | `legalActionToView` entries |
| target-domain derivations | 28,650 | 109,527 | 209,236 | Includes re-mapping from payment request paths |
| attack-domain derivations | 28,555 | 109,177 | 208,986 | One per builder-input candidate |
| blocker-domain derivations | 28,555 | 109,177 | 208,986 | One per builder-input candidate |
| payment qualification | 28,555 | 109,177 | 208,986 | One per builder-input candidate |
| target-payment relation builds | 0 | 0 | 0 | No target-dependent V5 relation in locked path |
| `paymentDomainV5For` | 8,434 | 30,760 | 61,308 | Includes diagnostics and views; 27/119/124 outside a builder |
| `PaymentDomainBuilder.buildV5` attempts | 233 | 1,095 | 1,516 | Calls that reached V5 builder |
| V5 mana-source discovery | 233 | 1,095 | 1,516 | Call-site count in V5 builder |
| `resolveActivatedAbility` | 54,111 | 205,380 | 389,738 | Repeated provenance resolution |
| `stableAbilityKey` | 26,490 | 100,533 | 191,349 | One per activated action view, including snapshots |
| stable ability ordinal | 18,901 | 68,164 | 129,962 | Structural ordering calculations |
| structural ability signature | 41,237 | 143,225 | 270,052 | Signature serializations |
| structural ability JSON | 67,727 | 243,758 | 461,401 | Structural JSON construction |
| `actionSemantic` | 28,555 | 109,177 | 208,986 | One per builder action view |
| required payload fields | 30,506 | 117,001 | 224,632 | Includes strict preflight calls outside builds |
| payment-domain request | 8,434 | 30,760 | 61,308 | Mirrors V5 request entry |
| target-cost dependency | 26,585 | 100,883 | 191,599 | Ability target-cost classification |

### Same-action duplicate evidence

The following distinguishes a real duplicate from two merely similar source call sites. A
same-action row means that the same object identity hash received more than one entry for that
derived operation during one builder build; no action-view identity-hash collision was detected.

| Derived operation | Witness extra calls / action rows | Normal4 extra calls / action rows | Corpus8 extra calls / action rows | Maximum calls for one action |
|---|---:|---:|---:|---:|
| target-domain mapping | 76 / 38 | 280 / 140 | 200 / 100 | 3 |
| `paymentDomainV5For` | 103 / 79 | 488 / 399 | 696 / 526 | 2 |
| payment-domain request | 103 / 79 | 488 / 399 | 696 / 526 | 2 |
| `resolveActivatedAbility` | 26,794 / 1,955 | 101,653 / 7,811 | 192,149 / 15,575 | 10 |
| target-cost dependency | 76 / 38 | 280 / 140 | 200 / 100 | 3 |

The direct conclusion is:

```text
OBSERVATION_BUILDER_DUPLICATION=DUPLICATE_WORK_PROVEN
MATERIALITY_AS_CURRENT_LEADING_CPU_HOTSPOT=NOT_PROVEN
CURRENT_JFR_RELATIVE_SIGNAL=canonicalizer/string/byte frames exceed builder-derived frames
```

`paymentDomainV5For` has the expected diagnostic/view overlap for the same action. Its 61,308
entries are much larger than the 1,516 calls that reach `PaymentDomainBuilder.buildV5`; most
requests return before actual V5 source-domain construction. The target-dependent payment path is
zero in the locked corpus, so no claim about its worst-case per-target cost is made.

`stableAbilityKey` itself is not duplicated for one action view: it occurs once for each activated
action view. The repeated work is lower in the call tree: `resolveActivatedAbility` and structural
ability signature/JSON construction are revisited by target-cost, payment, and semantic metadata
paths. Caching only the stable key would not necessarily remove all observed work.

`requiredPayloadFields` has one in-build call per action and an additional 15,646 strict preflight
calls in corpus8. The preflight call is not a duplicate inside `ObservationBuilder.build`, but it is
relevant duplicate metadata work across one accepted strict transition.

The smallest safe future seam would be a per-build typed derived-candidate record, keyed by exact
immutable state/generation, perspective, legal-action identity/index, and pending-decision shape.
It would have to preserve complete `Supported`/`Unsupported` distinctions and every public domain,
rather than cache a Boolean “payable” result. Stable ability caching would separately need the exact
source, runtime ability identity, authoritative provenance path, and duplicate structural ordinal
semantics. No such seam was implemented here.

### How much is payment/target completeness costing?

The current fixed workload performs one payment-qualification call for every builder-input action
(208,986 in corpus8), 61,308 V5 request attempts, and 1,516 actual V5 builder attempts/source
discoveries. It performs no target-dependent payment-relation build. That establishes substantial
qualification frequency and a smaller actual V5 builder frequency, but it does not provide a wall
time percentage: current JFR samples `PaymentDomainBuilder.buildV5` only three times and
`ObservationBuilder.paymentDomainV5For` three times in the test-worker view. Payment/target
completeness is therefore a credible secondary candidate, not the current measured primary.

## Legal-action duplication and the safe reuse boundary

Current production callers of `GameEnvironment.legalActions()` are `GameEnvironment.stepStrict`,
`stepFromCandidateStrict`, `validateActionMembership`, the legacy `playGame` loop,
`GameGymEnv.build`, and `GameGymEnv.currentTargetPaymentSnapshot`.

For an active trusted `GameGymEnv` step, code inspection gives this exact shape:

| Accepted entry | Pre-state legal list | Post-state legal list | Extra build |
|---|---:|---:|---:|
| ordinary action-ID or structured step | 1 strict candidate scan | 1 observation scan | 0 |
| folded/explicit pending decision | 0 strict action scan | 0 or 1 post-state scan | 0 |
| target-bound activated payment | 1 target snapshot scan + 1 strict scan | 1 post-state scan | 1 one-action observation |
| unchanged explicit `observe()` | 1 scan before cache check | 0 | 1 build, then discarded on cache hit |

Current corpus8 counters are 31,704 `GameEnvironment.legalActions()` calls and 31,342 actual
`LegalActionEnumerator.enumerate()` entries over 16,000 accepted transitions. The 50 builder calls
above 16,008 returned observations are the target-payment snapshot builds. The measured count proves
substantial repeated legal-list work; safe reuse still needs a generation/certificate proof.

Before any future generation-scoped reuse, the implementation must prove all of the following:

```text
state object/generation identity is the exact pre-state
projection generation and Rules state are unchanged
acting perspective and agentToAct are unchanged
pending decision identity and terminal/truncated status are unchanged
candidate list and every domain certificate are complete and untruncated
the registered action handle still points at that exact candidate snapshot
reset/restore/fork/rollback invalidate the token
direct GameEnvironment callers retain live validation
stale action rejection remains fail-closed
```

Trusting an opaque action ID merely because it was recently published is the highest-risk tempting
single-environment shortcut: it can weaken stale-action rejection and change candidate/domain
semantics.

## Canonicalization and digest after the accepted fix

The current semantic path remains:

```text
TrainingObservation -> semantic JSON tree -> semantic action fingerprints
  -> one existing canonical sort-key materialization per fingerprint
  -> recursive canonicalization -> JsonElement.toString()
  -> UTF-8 byte array -> SHA-256 -> lowercase hex
```

The sort-key plan is `ALREADY_ACCEPTED`; it is not a new candidate. The accepted `m/f=5.6781`
characterization remains historical proof for the merged change. Current JFR still shows
`String.hashCode`, StringBuilder growth, byte copies, and canonicalizer recursion, so the residual
JSON/tree/string/UTF-8/digest path remains a major measured hotspot. A future non-JSON encoder is
safe only if it emits exactly the existing canonical semantic JSON byte stream, or if a new identity
and replay contract is explicitly versioned. An ad hoc alternate digest would be a semantic change.

## Registry, immutable state, and allocation findings

`MtgSetCatalog.all` is a lazy `CardDiscovery.findSets` result sorted by release date/code. The B1
profiler constructs one registry per pass; current setup was 17.3–21.8s in the bounded runs and
JFR shows ClassGraph ZIP scanning in that phase. This is real for short-lived test/training
processes, but it is `COLD_START_ONLY`: long-running actors can keep the catalog/registry alive and
reset does not rescan the classpath. Registry micro-optimizations are not worth pursuing before
process lifecycle and final B1 scaling are measured.

Immutable `GameState` contributes materially to allocation bandwidth. `GameEnvironment` appends
every accepted result with `events = events + result.events`, which copies the cumulative prefix;
component/state map updates also create immutable maps and lists. The current JFR's 0.68% sampled
`CollectionsKt.plus` allocation-site share is not enough to attribute all 5.1MB/transition to event
history. A state/event representation change is an architectural candidate with broad Rules,
trigger, snapshot, fork, replay, and determinism risk.

Kotlin collection pipelines are a meaningful but secondary allocation source. Current JFR shows
`LinkedHashMap.Entry` 6.55%, `ArrayList` 2.47%, `ArrayList$Itr` 1.16%, `HashMap.Node[]` 2.04%, and
`CollectionsKt.plus` 0.68% by sampled allocation pressure. Replacing every `map/filter/sortedBy`
blindly is not justified; only a stack-attributed parent operation should be changed.

Fresh action-handle/remap work is real and contract-bound. `GameGymEnv.build` creates fresh opaque
IDs, a zip mapping, copied views, and a remapped registry for each new state generation. The
current JFR shows `ActionRegistry.remapIds` only as a small sample group, so this remains a medium
candidate until an allocation-isolated run proves otherwise.

## B0 and replay

The V5 exact-pair gate was run on current `origin/main` and passed: 39 tests completed, 0 failed,
38 were skipped by the exact-pair filter, and the two authoritative cases reported version 5,
codec round-trip exactness, reconstruction fidelity exactness, 2,001 frames, and 101 checkpoints.

This closes the old bridge/version blocker. It does not make replay cheap or prove B0 phase
attribution. The existing B1 profiler has no isolated timer around CompactReplay construction,
codec encode/decode, `ReplayFingerprint`, and `ReplayReconstructor`; therefore:

```text
REPLAYFINGERPRINT_OVERHEAD=NOT_RUN
REASON=the existing B1 profiler exposes no isolated replay timing boundary; no new replay
instrumentation was added because replay correctness and task scope take precedence
```

Current source inspection identifies these B0-only cost candidates:

1. replay capture may compute a semantic frame after the builder already computed the digest;
2. cadence checkpoints invoke the authoritative `ReplayFingerprint` path;
3. exact-pair verification codec-round-trips and reconstructs the trace;
4. the privacy gate builds both player perspectives and performs hidden-reference audits;
5. reflection/classloader/compile/artifact work can be cold harness cost.

The previous public semantic replay diagnostic measured a +49.021s delta on a matching 4,000-
transition two-episode diagnostic, but it was not CompactReplay V5 timing and is historical
diagnostic evidence only. The earlier approximately 7.877x B0-vs-linear-projection comparison also
mixed different episode/terminal/budget workloads. The current conclusion is:

```text
B0_8X_ATTRIBUTION=UNKNOWN_NEEDS_MEASUREMENT
B0_NORMAL_SIMULATION_ALONE=INSUFFICIENT_EVIDENCE
B0_VERIFICATION_OR_ARTIFACT_OVERHEAD=HIGH_LIKELIHOOD
B0_DIFFERENT_WORKLOAD_CONTRIBUTION=ALSO_POSSIBLE
```

No B0-64 rerun was performed.

## Parallelism and scaling

`MultiEnvService.stepBatch` creates one `Callable` per request and `EnvWorkerPool` runs independent
environment calls through a `ForkJoinPool`, returning results in request order. This is the safe
granularity for future throughput work:

```text
single environment: sequential Rules transition and decision/domain order
multiple environments: one owner task per environment, stable result order
```

The current `EngineServices` constructor still assigns process-global collaborators such as
`DamageUtils.cardRegistry`, `ZoneTransitionService.staticAbilityHandler`, and
`ZoneTransitionService.replacementEffectProcessor`. The shared card registry is intended to be
read-only after setup, but this is not enough for a high-confidence concurrency claim. Forked
environments may also share an immutable state object until their first synchronized lazy
projection. These boundaries should be proven or isolated before relying on CPU scaling.

No 1/2/4/8 environment benchmark was run. Parallelism is deferred to the next B1 measurement
package, not introduced here.

## Ranked candidate table

This ranking is a recommendation for future measurement/implementation, not authorization. Numeric
speedup estimates are intentionally absent. Risk order in each row is `Rules / determinism /
privacy / decision completeness / replay`; `L/M/H` means low/medium/high.

| Rank / candidate | Evidence, root cause, redundant work | Expected impact / confidence / complexity | Risks | Smallest safe seam | RED / benchmark / regression gates |
|---|---|---|---|---|---|
| 1. Exact semantic byte pipeline | **MEASURED_CURRENT_HOTSPOT.** JFR shows canonicalizer recursion, string hashing/builders, byte copies, and UTF-8 materialization. `StateDigest` still materializes a JSON tree/string and hashes its UTF-8 bytes. | `VERY_HIGH / H / H` | `M / H / H / H / H` | Stream the exact existing canonical semantic JSON byte sequence, or explicitly version identity; never silently change digest bytes. | RED: known-answer semantic bytes/digests across privacy, unordered arrays, domains, generated IDs, replay. Benchmark probe-free corpus8 CPU/allocation/GC. Gates: canonicalization, digest, privacy, strict Gym, replay, Hosted CI. |
| 2. Generation-scoped pre-state legal-list reuse | **MEASURED_CURRENT_HOTSPOT.** 31,342 actual enumerations over corpus8 and one strict pre-state scan around most accepted active actions. | `HIGH / M / H` | `H / H / H / H / H` | Exact immutable state/generation token plus perspective, pending-decision, registry certificate, and invalidation proof; retain live direct-caller validation. | RED: stale action, reset/restore/fork, changed actor/domain, full candidate-order equality. Benchmark enum hit/miss counts and probe-free corpus8. Gates: strict Gym, domains, privacy, replay, Rules, Hosted CI. |
| 3. ObservationBuilder typed derived-candidate reuse | **DUPLICATE_WORK_PROVEN.** Corpus8 has 696 extra same-action V5 calls, 200 extra target mappings, and 192,149 extra resolver calls. JFR shows derived ability work, but below canonicalizer/enum groups. | `HIGH / M / M/H` | `H / H / H / H / H` | Per-build typed record keyed by exact state/generation and legal-action identity/index; preserve complete supported/unsupported results. | RED: same-action counter equality plus byte/semantic/domain equality. Benchmark probe-free A/B corpus8 after implementation. Gates: target/payment/ability, privacy, decision, replay, Rules, Hosted CI. |
| 4. Stable ability provenance/signature memoization | **STILL_PLAUSIBLE.** Stable key is once per activated view, but resolver and structural JSON/signature work repeats for 15,575 corpus8 action identities. | `MEDIUM/HIGH / M / M` | `M/H / H / M / H / H` | Memoize exact immutable state/source/runtime ability/provenance and retain duplicate structural ordinal semantics. | RED: distinct equal-structure abilities, grants, emblem/intrinsic paths, generated handles. Benchmark structural JSON/CPU/allocation counters. Gates: observation, privacy, Rules ability, replay. |
| 5. V5 source-discovery sharing | **STILL_PLAUSIBLE.** 1,516 V5 builder attempts/source scans in corpus8; target-dependent route absent and JFR direct signal small. | `HIGH worst-case / L/M / H` | `H/H/H/H/H` | Share only context-independent discovery/certificate data; leave action cost, exclusions, target binding, producer order, and stability checks intact. | RED: paid sources, restrictions, pain, floating provenance, source exclusion. Benchmark payment fixtures plus corpus8. Gates: Rules payment, Gym payment/domain, privacy, replay, B0. |
| 6. Immutable event/state allocation reduction | **NOT_PROVEN.** Cumulative event prefix copy is source-visible; current `CollectionsKt.plus` sample is 0.68% and unisolated. | `HIGH allocation / M / H` | `H/H/M/H/H` | Transition-local accumulator or persistent structure only after proving all intermediate event/state observations identical. | RED: full event API, triggers, forks, snapshots, replay, state identity. Benchmark allocation stack traces/event length. Gates: full Rules/Gym/replay/determinism/B0. |
| 7. Fresh action-handle/remap churn | **STILL_PLAUSIBLE.** Fresh ID/list/map/registry remap is required per generation; current direct sample is small. | `MEDIUM / H existence / L/M` | `M/M/M/M/H` | One-pass equivalent construction preserving fresh monotonic opaque IDs and stale registry rejection. | RED: handle freshness/staleness/cardinality/order/digest. Benchmark isolated allocation site. Gates: Gym action contract, privacy, replay, strict domains. |
| 8. B0 privacy fanout isolation | **DEFER_TO_B0_ONLY.** Both perspectives and privacy audits are required; state-global intermediate sharing might reduce only safe work. | `VERY_HIGH B0-only / M / M/H` | `M/H/H/H/H` | Share only privacy-audited immutable state-global intermediates; keep one complete observation per perspective. | RED: both perspectives, hidden references, domains, semantic/wire/digest equality. Benchmark isolated privacy-only phase. Gates: exact pair, privacy, replay, B0 policy. |
| 9. B0 CompactReplay/reconstruction attribution | **DEFER_TO_B0_ONLY.** V5 correctness PASS, cost `NOT_RUN`; exact reconstruction/fingerprint must remain. | `VERY_HIGH B0-only / L / H` | `M/H/H/M/H` | Add test-only phase timing at existing bridge boundaries only; no verification removal. | RED: codec/fingerprint/reconstruction exactness. Benchmark bounded capture/verify/reconstruct. Gates: V1–V5 replay, exact pair, privacy, B0. |
| 10. Cross-environment batch scaling | **DEFER_TO_SCALING.** Existing `stepBatch`/`ForkJoinPool` seam; no measurements and global collaborators remain. | `HIGH multi-env / L / M/H` | `H/H/M/H/H` | Benchmark existing batch seam first, one owner per env and request-order results. | RED: sequential-vs-batch semantic/digest/replay equality and env isolation. Benchmark 1/2/4/8 with p50/p95/p99 and GC. Gates: Gym, Rules concurrency, replay, Hosted CI. |
| 11. Early observation-cache guard | **NOT_PROVEN.** Guard is definitely late, but B1 has zero extra `service.observe()` calls and no B0 caller evidence. | `UNKNOWN for B1 / H existence / L` | `L/M/H/M/H` | Guard by valid env generation/perspective before enum/build only after real caller evidence. | RED: repeated observe identity, terminal/truncated, reset/restore, direct advance. Benchmark real HTTP/trainer pattern. Gates: cache, privacy, replay, strict domains. |
| 12. Registry/cold-start micro-optimization | **COLD_START_ONLY.** First setup is seconds; lazy catalog/reset behavior makes it low value for long-running actors. | `LOW steady-state / H / M` | `L/M/L/L/M` | Process/registry lifecycle reuse, not card-loading semantic changes. | RED: registry identity/card coverage/order. Benchmark cold vs warm actor startup. Gates: registry/card closure and B1 lifecycle. |

## Answers to the adversarial questions

1. **Are legal facts computed more than once per state?** Yes in control-flow and call-count terms.
   Corpus8 has 31,342 actual enumerations for 16,000 accepted transitions and 31,704 wrapper calls.
   The pre-state scan is safety-critical until the exact generation/certificate contract is proven.

2. **Do ObservationBuilder and LegalActionEnumerator duplicate work?** Partly, not wholesale.
   The builder maps Rules-produced target/combat certificates once per action, but it redoes target
   mapping inside payment qualification, repeats payment-domain requests for diagnostics and views,
   and resolves ability provenance/signatures repeatedly. The probe proves these derived duplicates;
   it does not prove that all target enumeration can be shared.

3. **Is JSON canonicalization fundamentally expensive or merely repeated?** Both. Current JFR shows
   intrinsic string/tree/byte allocation and hashing cost, while the accepted sort-key fix removed
   only one proven repeated comparator sub-pass. The remaining pipeline is rank 1.

4. **Could a non-JSON semantic digest be justified?** Yes only as an exact encoder for the current
   canonical semantic JSON byte stream, or as an explicitly versioned identity/replay contract.
   An unversioned alternate representation is not safe.

5. **How much cost is payment/target completeness?** One qualification per builder action, 61,308
   V5 requests, and 1,516 builder/source scans in corpus8. Target-dependent payment is absent. The
   frequency is real; the wall-time share is unknown and JFR ranks it below digest and enumeration.

6. **Does immutable GameState materially contribute?** Yes to allocation bandwidth and likely map/
   list copy CPU, including cumulative event history. It is not proven to be the primary CPU source;
   current JFR ranks semantic byte and legal/domain work higher. A representation change is
   architectural and high risk.

7. **Are Kotlin collection pipelines meaningful?** Yes, but secondary. Current sampled allocation
   includes map entries, ArrayList/iterator/grow, and `plus`; byte arrays remain the largest class.

8. **Is registry setup worth optimizing for long-running training?** Usually no. It is a cold process
   cost. Reusing a process/registry dominates micro-optimizing ClassGraph for long-lived actors.

9. **Is B0's roughly 8x excess primarily verification overhead or different gameplay?** Not proven.
   The accepted ratio mixed horizons/outcomes/workloads. Privacy fanout, semantic frames, checkpoint
   fingerprints, reconstruction, artifacts, reflection, and workload differences all remain
   possible. No phase is assigned a percentage here.

10. **Where should parallelism enter?** Across independent environments at `stepBatch`/
    `EnvWorkerPool`, after global-service collaborator safety and 1/2/4/8 scaling are measured.
    Never parallelize one environment's Rules transition or decision ordering.

11. **What prevents 1 episode/s?** At the current 0.091809 episodes/s median, one episode/s at the
    fixed 2,000-step horizon needs about 10.89x throughput and 2,000 transitions/s. The current
    allocation rate is roughly 10.24GB per 2,000-transition episode, or 10.24GB/s at one episode/s,
    before GC and concurrency overhead. No current measurement supports a 10.89x single-env claim;
    reaching it likely needs semantic-byte/allocation reduction and independent-environment scaling.

12. **What prevents 5–10 episodes/s?** That is roughly 54.5–108.9x the measured single-environment
    rate and implies about 51–102GB/s of short-lived allocation at the fixed horizon. It is not a
    credible current-architecture target without major allocation reduction and substantial
    independent-environment scaling. Shorter natural episodes would change the arithmetic.

## Stop decision

The current evidence proves additional ObservationBuilder duplicate work, but it does not make that
candidate a safe or measured winner over the residual semantic byte pipeline. The accepted
canonicalizer optimization is already merged; the V5 replay blocker is closed; the cache has no
normal B1 caller evidence; and the exact B0 phase split remains unavailable.

```text
IS_ANOTHER_SINGLE_ENV_OPTIMIZATION_JUSTIFIED=NO
NO_FURTHER_SINGLE_ENV_OPTIMIZATION_JUSTIFIED
NEXT=B1_SCALING_AND_FINAL_MEASUREMENT
```

The next task should run the remaining Issue #99 matrix:

```text
1 / 2 / 4 / 8 independent environments
step latency p50 / p95 / p99
reset latency p50 / p95 / p99
observation-build and legal-domain publication latency
allocation, heap/RSS, GC, and reset-memory trend
final semantic trajectory regression
replay exactness
Hosted CI
```

That task is not started by this commit.

## Verification record

```text
just test-class B1PerformanceBaselineTest --console=plain
  BLOCKED before Gradle: Windows WinError 193 from scripts/gradle-locked

native B1 default-disabled test
  PASS: B1PerformanceBaselineTest SKIPPED; no workload artifact

native focused contract matrix
  PASS: 73 selected, 73 passed, 0 failed, 0 skipped
  includes ObservationCanonicalizationTest, StateDigestTest, ObservationPrivacyTest,
  GameGymEnvStrictExecutionTest, TargetPaymentDomainContractTest, B0HarnessTimeoutPolicyTest

native full :gym:test
  PASS: 432 tests, 0 failures, 0 errors, 1 expected B1 profiler skip

test-only characterization
  PASS: witness 1/1 episodes, 2,000 transitions
  PASS: normal4 4/4 episodes, 8,000 transitions
  PASS: corpus8 8/8 episodes, 16,000 transitions
  exact scalar counters recorded; probe-enabled runtime excluded from performance KPI

current probe-free profiler
  PASS: witness, normal4, corpus8; three corpus8 repetitions
  JFR summary/hot-methods/allocation/gc/cpu views PASS where available
  gc-heap view NOT_RUN: unavailable in this JDK

current exact-pair replay gate
  PASS: 2 authoritative V5 cases, codec round-trip exact, reconstruction fidelity exact
  38 non-selected tests skipped by the focused filter

Hosted CI
  NOT_ESTABLISHED
B0-64 phase attribution
  NOT_RUN
isolated CompactReplay/ReplayFingerprint timing
  NOT_RUN by scope; no existing timing boundary
1/2/4/8 scaling matrix
  NOT_RUN; recommended next task

git diff --check
  PASS before commit
```

No optimization, replay weakening, candidate reuse, cache change, payment fallback, event/state
refactor, parallel change, deck change, card change, or ML work is included.

```text
IMPLEMENTATION_STARTED=NO
DATA_TRUSTED=NO
```
