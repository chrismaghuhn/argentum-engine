# Argentum B1 Deep Performance Audit

Status: research / analysis only. No production optimization, production semantic change, card
change, deck change, policy change, replay-schema change, or B0 harness change was made by this
audit.

```text
AUDIT_DATE=2026-09-02
BASE_HEAD=ffcf897213f09932d30020f3c1df20b99f369b84
BASELINE_REPORT_HEAD=d0f4fe9eddb377fbddd7e675ec396ecbabe254d5
ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM=https://github.com/wingedsheep/argentum-engine.git
PRODUCTION_CHANGES=0
PRODUCTION_OPTIMIZATIONS=0
DATA_TRUSTED=NO
AUDIT_HEAD=reported by the delivery commit; not part of the measured source head
```

The audit was performed after fetching `origin` and `upstream`. The live `origin/main` and the
dedicated audit worktree both resolved to `BASE_HEAD`. The accepted baseline report at
`BASELINE_REPORT_HEAD` was read from its immutable commit; it is not present on current `main` and
was not modified. The separate B1 canonicalization characterization completed its authorized
measurement-only Tasks 1–3 at `5d974a85dcfc0e8256c4c52da54985c126df25af`; that branch/worktree was
not touched by this audit, and its Task 4–6 optimization work remains unauthorized.

Primary source locations below refer to `BASE_HEAD` and are the authority for the code-inspection
claims. The baseline JFR/JSON artifacts were read from the existing baseline worktree under
`C:\Users\chris\.config\superpowers\worktrees\argentum-engine\b1-performance-baseline-20260901\gym\build\reports\b1\`.

## Executive result

The highest-value normal-path opportunity is not automatically the current Canonicalizer plan. The
trusted path spends its time at the boundary where a Rules transition becomes a complete,
perspective-safe observation. Three kinds of work overlap there:

1. strict candidate binding re-enumerates the pre-transition legal set that was already published by
   the current observation;
2. `ObservationBuilder` walks every public candidate again for target, combat, payment, required
   payload, and semantic-action metadata, and currently recomputes some of that metadata within the
   same build; and
3. `StateDigest` performs a JSON-tree, semantic-fingerprint, recursive-canonicalization, UTF-8,
   SHA-256, and hex-formatting pipeline for every returned observation.

The baseline proves the third class is the largest sampled normal-path CPU/allocation hotspot. The
source proves the first two classes contain generic duplicate work, but their exact contribution is
not yet measured separately. The safest high-value sequence is therefore:

```text
instrument exact same-state reuse boundaries
  -> remove only in-build / same-generation duplicate derived work
  -> characterize exact legal-list reuse with a generation proof
  -> optimize the semantic-byte pipeline differentially
  -> address immutable-state allocation only as an architectural phase
  -> measure cross-environment scaling
```

No speedup is claimed for any candidate in this document.

The independent canonicalization characterization has now supplied one important measurement: in
corpus8, the legal-action selector evaluated 1,200,164 canonical sort keys for 211,368 semantic
fingerprints (`m/f=5.6781`). That makes sort-key precomputation the strongest narrow first candidate
for normal single-environment work, while the larger legal/domain and B0 candidates remain
end-to-end or phase-attribution questions.

The B0 excess is a separate conclusion. The supplied B0-64 time is not explained by the normal
corpus8 simulation alone, but the exact split is not measured. The live exact-pair test source also
contains a 72-episode corpus, four replay-trace cases, a two-perspective privacy gate, and a
reflective CompactReplay bridge, while the accepted B0 comment reports a 64-episode acceptance
run. Those workloads must not be collapsed into one ratio. The strongest B0-only candidates are
the privacy gate's repeated full observation builds and replay reconstruction/fingerprint work;
both require direct phase measurement before any change.

## Evidence labels and measurement boundary

Every conclusion in this report is labeled by evidence type:

```text
MEASURED                 existing accepted B1 JSON/JFR or a read-only JFR view rerun here
CODE-INSPECTION FINDING  direct control/data-flow fact at BASE_HEAD
INFERENCE                arithmetic or consequence of measured/source facts
HYPOTHESIS               plausible attribution that still needs a bounded measurement
```

The primary normal-path measurement is the accepted B1 corpus8 profile:

| Metric | Evidence |
|---|---:|
| Episodes | 8 |
| External transitions | 16,000 |
| Observations | 16,008 |
| Median workload wall time | 96.943 s |
| Episodes/s | 0.082523 |
| Transitions/s | 165.046 |
| Strict transition + returned observation | 75.965 s, 79.489% of the latest 95.566 s pass |
| External policy | 1.417 s, 1.483% |
| First-process registry/service setup | 17.474 s, 18.285% |
| Thread allocation | 119,986,978,144 bytes |
| Allocation per external transition | 7,499,186 bytes |
| GC pause time | approximately 1.23 s over 578 JFR pauses |
| Sampled heap peak | 386.8 MB |
| Aggregate public legal-action entries | 211,318 |
| Visible card entries | 907,207 |
| Structured pending-decision observations | 47 |

Source for these values: the immutable baseline report at
[`docs/ml/b1-performance-baseline.md`](https://github.com/chrismaghuhn/argentum-engine/blob/d0f4fe9eddb377fbddd7e675ec396ecbabe254d5/docs/ml/b1-performance-baseline.md).

The accepted JFR recording was also inspected read-only with `jfr summary`, `hot-methods`,
`allocation-by-class`, `allocation-by-site`, `gc-pauses`, `gc-cpu-time`, `cpu-load`,
`cpu-information`, and a bounded stack-name count over `jdk.ExecutionSample`. These views are
sampling evidence, not exact method call counts.

## Current repository state

The live repository procedure produced:

```text
origin/main=ffcf897213f09932d30020f3c1df20b99f369b84
upstream/main=36c5a0c2a2c688e2a814940495ce64c55baced25
origin URL=the required chrismaghuhn/argentum-engine repository
worktree used for this report=clean dedicated audit worktree
```

The live issue/PR inventory showed:

- B0 issue #98 closed with the final-acceptance comment, while `DATA_TRUSTED=NO` remains the
  documented boundary.
- B1 issue #99 open. Its acceptance text still requires 1/2/4/8-environment measurements,
  latency percentiles, memory/GC evidence, semantic trajectory regression, exact replay, and
  Hosted CI; this report does not claim those gates.
- B2 issue #100 open and dependent on B1.
- Issue #106 remains open; its payment-domain work is relevant to payment enumeration cost but is
  not altered or reinterpreted by this audit.
- The independent measurement report `docs/ml/b1-canonicalization-characterization.md` used here
  was recorded at `5d974a85dcfc0e8256c4c52da54985c126df25af`; that branch may advance with
  metadata-only follow-ups, but its production optimization task remains out of scope and it was
  not touched by this report.

The current `main` tree has no checked-in `gym/build/reports/b0` artifact writer or separate
`B0CommanderSoakHarnessTest`. The exact-pair acceptance source is the available first-party B0
architecture evidence; historical B0 runtime numbers are retained as supplied/recorded evidence,
not re-run here.

## End-to-end trusted path

The production/in-process route is:

```text
MultiEnvService.step / submitDecision
  -> GameGymEnv.step / step(actionId, payload) / submitDecision
      -> ActionRegistry.resolve
      -> public target/combat/color/payment preconditions
      -> GameEnvironment.stepFromCandidateStrict or stepStrict
          -> GameEnvironment.legalActions
              -> LegalActionEnumerator.enumerate(ACTIONS_ONLY)
          -> ActionProcessor.process
              -> handler validate
              -> handler execute
              -> immutable GameState + GameEvent list
          -> GameGymEnv.build
              -> ObservationPerspective.resolve
              -> GameEnvironment.legalActions
              -> ObservationBuilder.build
                  -> projected features / zones / visibility / stack
                  -> target + attack + blocker public domains
                  -> payment qualification and PaymentDomainV5
                  -> action semantics and stable ability identity
                  -> TrainingObservation
                  -> StateDigest.compute
                      -> ObservationCanonicalizer.semanticJson
                      -> SHA-256 and lowercase hex
              -> fresh action-handle remap and ActionRegistry
  -> TrainingObservation / HTTP response
```

The adapter and service are thin state-routing layers. `MultiEnvService` owns a concurrent env map
and delegates each env to one `GameGymEnv`; `stepBatch` fans independent requests out through
`EnvWorkerPool` and returns results in request order. See
[`MultiEnvService.kt:42-145`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym/src/main/kotlin/com/wingedsheep/gym/service/MultiEnvService.kt#L42-L145)
and
[`EnvWorkerPool.kt:18-29`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym/src/main/kotlin/com/wingedsheep/gym/service/EnvWorkerPool.kt#L18-L29).

The trusted adapter path is visible in
[`GameGymEnv.kt:89-143`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt#L89-L143),
[`GameGymEnv.kt:169-183`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt#L169-L183),
and
[`GameGymEnv.kt:213-290`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt#L213-L290).

## Classification matrix

| Work | Classification | Why |
|---|---|---|
| `GameState.projectedState` for one immutable state | `NECESSARY_ONCE` | `GameState` memoizes `StateProjector().project(this)` with `by lazy`; repeated reads on the same object share it. |
| `EnumerationContext.projected`, `battlefieldPermanents`, `availableManaSources`, and `manaStatics` | `NECESSARY_ONCE` per enumeration pass | The context deliberately caches cross-cutting values, but a new `LegalActionEnumerator.enumerate` creates a new context. |
| Strict candidate membership enumeration after the current observation already published the same state | `REDUNDANT_ACROSS_UNCHANGED_STATE` | Correctness needs a current-generation proof, but the work duplicates the already exposed candidate set in ordinary service usage. |
| Post-transition legal-action enumeration | `NECESSARY_ONCE` | The state has changed and the next public domain must be rebuilt. It cannot be reused from the pre-transition state. |
| `GameGymEnv.build` on an unchanged state | `REDUNDANT_ACROSS_UNCHANGED_STATE` | The cache check occurs after `ObservationBuilder.build` and `environment.legalActions`, so a cache hit still pays the build. |
| Ordinary target-domain mapping from `LegalAction.targetRequirements` | `NECESSARY_ONCE` per public action view | It maps the Rules-owned certificate and checks addressability; it does not re-search the battlefield. |
| Target/payment qualification and V5 payment-domain construction | `REDUNDANT_WITHIN_OBSERVATION` in several branches | The same action is re-mapped/re-costed for diagnostics and again for its view; target-dependent actions may build a V5 domain once per target. Exact frequency needs counters. |
| Stable activated-ability identity | `REDUNDANT_WITHIN_OBSERVATION` | Each `ActivateAbility` view can re-resolve the same source/grants and serialize structural ability signatures while computing an ordinal. |
| `ObservationCanonicalizer.semanticJson` internal tree/string passes | `REDUNDANT_WITHIN_OBSERVATION` | Full DTO encoding, per-action fingerprint construction, comparator canonicalization, and final recursive canonicalization overlap. The independent characterization measured `m/f=5.6781` for legal-action sort keys in corpus8. |
| `StateDigest.compute` once for a returned observation | `NECESSARY_ONCE` | The public digest is part of the observation contract. Its byte/string implementation is a performance target, not its semantic removal. |
| B1 public replay diagnostic's second semantic frame computation | `REPLAY_ONLY` | It intentionally compares semantic frames, but it calls the semantic canonicalizer again after the builder already computed the digest. |
| B0 two-perspective privacy projection | `B0_HARNESS_ONLY` and `REDUNDANT_ACROSS_UNCHANGED_STATE` | Each perspective needs its own privacy result, but state-global work and some exact immutable intermediates are repeated. Whole-observation reuse is unsafe. |
| CompactReplay reconstruction, checkpoint fingerprint, and spectator delta generation | `REPLAY_ONLY` / `B0_HARNESS_ONLY` | Re-execution is required for trust; its cost must be isolated rather than removed. |
| `MtgSetCatalog`/ClassGraph discovery and first registry construction | `COLD_START_ONLY` | Catalog discovery is lazy and cached; `MultiEnvService.create` receives a prebuilt registry and reset does not rescan the classpath. |
| Cumulative `GameEnvironment.events = events + result.events` | `UNKNOWN_NEEDS_MEASUREMENT` | The source copies the full history on each accepted step; JFR shows a small `CollectionsKt.plus` allocation-site share, but no event-count correlation exists. |
| `stepBatch` across independent environments | `UNKNOWN_NEEDS_MEASUREMENT` | The seam exists, but no 1/2/4/8 scaling measurement or global-service concurrency proof is accepted here. |

## 1. Legal-action enumeration duplication

### Exact call-site trace

All production call sites of `legalActions()` are:

| Call site | Role |
|---|---|
| `GameEnvironment.stepStrict`: `GameEnvironment.kt:220-233` | Validates a raw strict action; the `DeclareAttackers` and `DeclareBlockers` branches make a second current-list lookup. |
| `GameEnvironment.stepFromCandidate`: `GameEnvironment.kt:252-261` | Legacy caller path; not the trusted Gym path. |
| `GameEnvironment.stepFromCandidateStrict`: `GameEnvironment.kt:273-315` | Trusted action-ID candidate binding and current-candidate/domain comparison. |
| `GameEnvironment.validateActionMembership`: `GameEnvironment.kt:318-333` | Legacy `step` path and direct strict replay actions. |
| `GameEnvironment.playGame`: `GameEnvironment.kt:622-627` | Legacy convenience loop. |
| `GameGymEnv.build`: `GameGymEnv.kt:223-228` | Builds the next public observation. |
| `GameGymEnv.currentTargetPaymentSnapshot`: `GameGymEnv.kt:403-431` | Rechecks a target-bound payment relation before a structured activation. |

`GameEnvironment.legalActions()` returns early for no actor, pending decision, terminal state, or
truncation. In those cases the wrapper call is real but `LegalActionEnumerator.enumerate()` is not
called. The implementation uses `EnumerationMode.ACTIONS_ONLY` and filters only actions with an
unfillable mandatory target requirement; it does not choose a candidate or use a fallback. See
[`GameEnvironment.kt:391-402`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym/src/main/kotlin/com/wingedsheep/gym/GameEnvironment.kt#L391-L402).

### Per-transition call-count matrix

The following counts are exact for the stated control-flow shape, not estimates. “Enumerator calls”
means calls that reach `LegalActionEnumerator.enumerate`; early-return states are noted separately.

| Accepted entry shape | `GameEnvironment.legalActions()` calls in the entry | Enumerator calls | Observation builds caused by the entry | Notes |
|---|---:|---:|---:|---|
| `GameGymEnv.step(actionId)` resolving an ordinary legal `GameAction` | 2 | 2 | 1 | One pre-state current-candidate scan in `stepFromCandidateStrict`; one post-state scan in `build`. |
| `GameGymEnv.step(actionId, payload)` for an ordinary structured action | 2 | 2 | 1 | Adds JSON materialization and payload checks, but no extra legal-list scan unless it is target-bound payment. |
| `GameGymEnv.step(actionId)` resolving a folded simple decision | 1 | 0 or 1 | 1 | `stepStrict(SubmitDecision)` does not enumerate pre-state actions; the post-state build may return early if another decision is pending. |
| `GameGymEnv.submitDecision(response)` | 1 | 0 or 1 | 1 | Same decision branch as above. |
| Target-bound `ActivateAbility` structured payment | 3 | 3 | 2 | `currentTargetPaymentSnapshot` enumerates and builds a one-action observation; `stepFromCandidateStrict` enumerates again; final `build` enumerates the new state. |
| Direct `GameEnvironment.stepStrict(ordinary action)` followed by caller `gym.observe()` | 2 | 2 | 1 | One raw strict membership scan, then one observation scan. This is the B0 replay/privacy direct-environment shape. |
| Direct `stepStrict(DeclareAttackers/DeclareBlockers)` followed by `gym.observe()` | 3 | 3 | 1 | `validateActionMembership` plus the explicit combat-domain support scan, then the post-step observation scan. |
| `GameGymEnv.observe()` on an unchanged active state | 1 | 1 | 1 built, then discarded on a cache hit | The cache comparison is after building; this is a proven wasted same-state computation. |

For ordinary service actions, the step call therefore performs two actual legal enumerations when
the pre- and post-state are both active priority states. The first one is required if the adapter
must prove the candidate against a live state that may have changed since the observation; it is
also the same-state work already used to produce the current observation in normal sequential
service use. The second one is required because the Rules state changed. A safe optimization must
preserve the first guarantee with an explicit state-generation/identity proof; simply trusting an
opaque action ID is not safe.

The independent scalar characterization measured the actual enumerator entry point on the same
fixed corpus (with opt-in diagnostic hooks and no production optimization): 31,342 enumerations,
16,058 `ObservationBuilder.build` calls, and 16,058 semantic/digest calls over 16,000 transitions.
This proves the scale of repeated enumeration for that run. The baseline report itself recorded
aggregate candidate entries rather than invocation counters, and the wrapper-level
`GameEnvironment.legalActions()` call total (including early returns) remains
`UNKNOWN_NEEDS_MEASUREMENT`.

### Enumeration context boundary

`LegalActionEnumerator.enumerate` creates one `EnumerationContext` and all specialized enumerators
share its lazy projection, controlled battlefield list, available mana sources, and mana statics.
This is a good existing seam. It does not help across separate calls from
`GameEnvironment.legalActions()`. See
[`LegalActionEnumerator.kt:64-88`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/LegalActionEnumerator.kt#L64-L88)
and
[`EnumerationContext.kt:43-83`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/EnumerationContext.kt#L43-L83).

The `LegalActionEnumerator` itself still allocates the result of every specialized enumerator and
then combines them through `enumerators.flatMap`. A future accumulator API could reduce empty-list
and intermediate-list churn, but it is not the first target: the actions and their Rules-owned
metadata must remain complete and ordered where order is semantic.

## 2. ObservationBuilder and public domain construction

`ObservationBuilder.build` performs the following work once for the supplied perspective and legal
list:

- builds player summaries, all owner/zone views, visible entity features, and stack views;
- resolves the actor and pending decision;
- maps every `LegalAction` to a target-domain result, attack-domain result, blocker-domain result,
  and target-payment qualification;
- filters unsupported mappings without shrinking a public domain silently;
- builds diagnostics, legal-action views, and the action registry;
- computes `StateDigest` over the finished `TrainingObservation`.

The central implementation is
[`ObservationBuilder.kt:198-343`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt#L198-L343).

### State-global versus action-specific

| Work | Scope | Finding |
|---|---|---|
| `state.projectedState` | State-global | Cached once per immutable `GameState`, including across calls that hold the same object. This is not an obvious duplicate within one state. |
| Zone map traversal and visible entity feature construction | State + perspective | Necessary for a fresh perspective-safe observation. It can be shared only through privacy-audited immutable intermediates. |
| `mapPublicTargetDomain` | Per legal action | It copies/sorts already Rules-produced target candidates and checks addressability. Ordinary mapping does not re-run `TargetEnumerationUtils`. |
| Attack/blocker mappers | Per combat action | They validate and copy Rules certificates; they do not enumerate combat assignments again. |
| `targetCostDependencyFor` | Per activated action | It resolves the ability, may enumerate target combinations up to a hard limit, and calculates unbound/bound effective costs. |
| `paymentDomainV5For` | Per applicable action | It resolves the payment request and runs `PaymentDomainBuilder.buildV5`, including Rules-owned source/profile/stability checks. |
| `actionSemantic` / `stableAbilityKey` | Per activated-action view | It serializes action/ability structure and normalizes runtime ability IDs to semantic provenance. |
| `ActionRegistry.ofLegalActions` + `GameGymEnv` remap | Per observation | Fresh opaque handles are required after every state generation; the current implementation creates several temporary maps/lists. |

### Proven same-build duplicate work

The action mapping at `ObservationBuilder.kt:220-232` computes `targetResult`,
`attackResult`, `blockerResult`, and `targetPaymentQualification`. The diagnostic check at
`ObservationBuilder.kt:274-285` can then call `paymentDomainV5For` for the same action, and
`legalActionToView` at `ObservationBuilder.kt:640-707` can call `paymentDomainV5For` again for the
same action. The payment request itself calls `mapPublicTargetDomain` again for an activated
ability (`ObservationBuilder.kt:1084-1110`).

This is not a license to cache a Boolean “payable” result. A `null` V5 domain is a strict
unsupported result, while an empty source set, a non-applicable action, an unaffordable action, and
a stale/unsupported target relation have different contract meanings. The smallest safe seam is a
per-build memo containing the complete typed result (including the reason/sentinel for unsupported),
keyed by the exact immutable state and the exact legal-action instance/index. It must reuse only
the result that was already computed; it must not drop a candidate or infer a source/payment choice.

### Stable activated-ability identity is a separate hotspot

For every `ActivateAbility` view, `actionSemantic` calls `stableAbilityKey`. For printed abilities it
resolves the source definition and then computes a structural signature/ordinal. The ordinal helper
serializes structural ability JSON for the target and for other abilities while counting
structurally equal predecessors. Granted, static, emblem, and intrinsic paths scan different
authoritative sources. See
[`ObservationBuilder.kt:1605-1795`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt#L1605-L1795).

The corpus exposed 191,299 `ActivateAbility` action entries out of 211,318 total public legal
entries, about 90.5%. JFR stack samples also contained `stableAbilityKey`,
`stableAbilityOrdinal`, `structuralAbilitySignature`, and `structuralAbilityJson`, while the
standard hot-method view showed `HashMap.getNode`, `String.hashCode`, JSON tree/string, and sorting
work. This is `MEASURED` as a sampled hotspot and `CODE-INSPECTION FINDING` as a repeated per-view
operation; the exact per-observation call count is not measured.

A safe cache must be scoped to the exact immutable state and source/ability identity. It must retain
the distinction between duplicate structural abilities, preserve the authoritative provenance path,
and never fall back to a runtime `AbilityId`, donor ID, map order, or entity allocation order.

### Projection and visibility are already partly optimized

`GameState.projectedState` is explicitly memoized per immutable state, and `GameState.getBattlefield`
is also memoized. The target/visibility code correctly passes projected state to predicate matching.
Do not recommend replacing this with base-card reads or a global mutable projection cache. The
remaining opportunity is to avoid rebuilding safe immutable intermediates across a same-state
observation/domain pass, not to weaken privacy or Rule 613 behavior.

## 3. Canonicalization and digest

`StateDigest.compute` is exactly:

```text
semanticJson(observation)
  -> UTF-8 byte array
  -> MessageDigest(SHA-256)
  -> 64-character lowercase hex string
```

See
[`StateDigest.kt:13-20`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym/src/main/kotlin/com/wingedsheep/gym/contract/StateDigest.kt#L13-L20).

One `semanticJson` call is necessary to compute the current public digest, but its implementation
does several overlapping passes:

1. encode the complete `TrainingObservation` into a `JsonElement` tree;
2. copy the root map and remove `stateDigest`;
3. rebuild every legal-action semantic fingerprint, including nested payment/target domains;
4. sort fingerprints by repeatedly canonicalizing them to strings; and
5. recursively canonicalize the complete semantic object, sorting unordered arrays and rendering
   the tree to a string.

The implementation is visible at
[`ObservationCanonicalizer.kt:32-121`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt#L32-L121)
and
[`ObservationCanonicalizer.kt:409-425`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt#L409-L425).

### Measured normal-path evidence

The accepted corpus8 JFR reported:

- `ObservationCanonicalizer.canonicalize` as the leading application-side sampled method group;
- `ObservationCanonicalizer.canonicalize` at 58 top-method samples in the standard hot-method view;
- `AbstractStringBuilder.ensureCapacityInternal` at 224 samples / 9.42%;
- `JsonObject.toString` at 46 / 1.93%;
- `TimSort.binarySort` at 57 / 2.40%;
- allocation pressure led by `byte[]` 63.25%, `String` 4.89%, `StringBuilder` 3.76%,
  `JsonLiteral` 1.15%, and `JsonObject` 0.31%;
- allocation-site pressure led by `Arrays.copyOf` 28.58%, `copyOfRangeByte` 14.24%,
  `StringBuilder` construction 5.69%, `StringBuilder.toString` 3.68%, UTF-8/string byte work,
  and canonicalizer frames.

This proves JSON/string/byte materialization is intrinsically expensive on this path. It is also
repeated: the same observation's action fingerprint trees and final tree are not one shared byte
stream.

### Public replay diagnostic evidence

The accepted B1 public semantic replay diagnostic ran the same two 2,000-transition episodes:

```text
matching baseline = 46.384 s
capture + verify   = 95.405 s
delta              = +49.021 s
capture pass       = 58.775 s
verify pass        = 36.514 s
capture semantic canonicalization = 15.368 s
verify semantic canonicalization  = 14.484 s
```

The replay diagnostic also raised measured thread allocation from approximately 7.907 MB per
transition to 41.482 MB per transition and raised the sampled heap peak from 298.6 MB to 691.5 MB.
This is diagnostic evidence that a second public semantic comparison is expensive. It is not a
reason to remove replay verification.

### Independent `n` / `m` characterization

The separate characterization branch completed its measurement-only Tasks 1–3 at
`5d974a85dcfc0e8256c4c52da54985c126df25af`, based on `BASELINE_REPORT_HEAD`. It added scalar,
opt-in counters only; it did not implement the sort-key optimization. Its primary corpus8 result
was:

| Counter | corpus8 value |
|---|---:|
| ObservationBuilder calls | 16,058 |
| Actual `LegalActionEnumerator.enumerate` calls | 31,342 |
| `semanticJson` calls | 16,058 |
| `StateDigest.compute` calls | 16,058 |
| Semantic action fingerprints (`f`) | 211,368 |
| Legal-action canonical sort-key evaluations (`m`) | 1,200,164 |
| Aggregate `m/f` | 5.6781 |
| Maximum `(n, f, m)` in one call | `(29, 29, 216)` |
| Structured-domain sort-key evaluations | 0 |
| Wire JSON calls | 0 |

This is `MEASURED` characterization evidence, not a production speedup. It proves `m > f` in all
three measured workloads and makes the current legal-action sort-key selector a credible duplicate-
work candidate. The 50 additional builder calls beyond the 16,008 returned observations are
consistent with the source-level target-bound `currentTargetPaymentSnapshot` path, which builds a
one-action observation; the characterization report does not assign every extra call to a branch,
so that attribution remains an `INFERENCE`.

The independent report is
[`docs/ml/b1-canonicalization-characterization.md`](https://github.com/chrismaghuhn/argentum-engine/blob/5d974a85dcfc0e8256c4c52da54985c126df25af/docs/ml/b1-canonicalization-characterization.md).
The result moves sort-key precomputation to the front of the narrow normal-path candidate list, but
it does not prove that it beats a full in-build domain reuse or generation-scoped legal-list reuse
in end-to-end wall time. Those comparisons still require an A/B run with production semantics
unchanged.

`wireJson` is a separate canonicalized wire-DTO helper used by tests/privacy auditing. The Gym HTTP
server uses `KotlinSerializationJsonHttpMessageConverter` directly, so `wireJson` must not be
treated as the normal network serialization cost. See
[`WebConfig.kt:20-39`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym-server/src/main/kotlin/com/wingedsheep/gym/server/config/WebConfig.kt#L20-L39).

### Non-JSON semantic digest: eventual answer

Yes, a non-JSON internal encoder could eventually be justified, but only under one of these
strictly equivalent contracts:

1. it emits exactly the current canonical semantic JSON byte stream directly, so the existing
   SHA-256 digest bytes remain byte-for-byte identical; or
2. it becomes an explicitly versioned replacement for the state-digest contract, with migration and
   replay/transposition compatibility deliberately designed and accepted.

The first option is the safer long-term shape: a streaming semantic encoder can remove intermediate
`JsonElement`, `String`, and UTF-8 copies without changing the semantic schema. It still needs
differential tests over object-key order, unordered versus ordered arrays, hidden-zone masking,
structured domains, generated ability IDs, target/payment relations, and replay frames. A faster
hash over a different ad hoc representation would not be a performance-only change; it would change
identity, replay equality, and potentially MCTS transposition behavior.

## 4. Allocation and immutable-state pressure

### Measured allocation categories

The normal corpus allocated approximately 120 GB over 16,000 transitions while retaining a much
smaller heap and pausing for only approximately 1.23 seconds. This is primarily allocation
bandwidth, not a long-GC-pause bottleneck.

The strongest measured target is the canonical JSON/string/byte pipeline. Other measured categories
are meaningful but smaller:

| Allocation evidence | Interpretation |
|---|---|
| `byte[]` 63.25% | UTF-8, string growth/copy, digest/input materialization; target the complete semantic-byte pipeline. |
| `LinkedHashMap.Entry` 8.06% | JSON trees and immutable/map-building operations; source allocation site does not partition the two yet. |
| `String` 4.89% and `StringBuilder` 3.76% | Canonical text, descriptions, IDs, and rule/domain strings. |
| `PredicateContext` 1.64% | Per-predicate context construction is real, but not the leading allocation class. |
| `ArrayList` 1.49%, iterator 1.00%, grow 0.99% | Kotlin collection pipelines and repeated list copies are material, not sufficient alone to explain all bytes. |
| `CollectionsKt___CollectionsKt.plus` allocation site 0.49% | Includes possible event-history and collection concatenations; current evidence does not prove it is a top target. |

### Immutable GameState contribution

The immutable architecture does materially contribute to allocation pressure:

- `GameState.withEntity` uses `entities + (id to container)`;
- `ComponentContainer.with/without` uses map `+`/`-`;
- zone updates use `zones + (key to current +/- entityId)`;
- `GameEnvironment` appends cumulative event history using `events + result.events` on every
  accepted transition.

These operations preserve the pure `(GameState, GameAction) -> result` contract and make fork/restore
cheap by reference. They also create transient map/list structures for many state updates inside a
Rules transition. The JFR `LinkedHashMap.Entry` share is compatible with this pressure, but it also
includes JSON/map construction; direct state attribution is therefore `HYPOTHESIS`, not a measured
percentage.

The cumulative event list is a particularly clear asymptotic concern: if `E_t` is the number of
events retained after step `t`, `events = events + newEvents` copies the existing prefix at every
step. It is not returned by the Gym observation and only `lastStepEvents` is returned in a
`StepResult`, but `GameEnvironment.events` is public API and legacy callers may rely on it. The
smallest safe future experiment is a test-only event-count/allocation characterization, not a silent
change to event retention.

An eventual transition-local batch builder or persistent-map representation could reduce this cost,
but it is an architectural Rules candidate. It must preserve every intermediate state visible to
triggers/continuations, event order, RNG threading, last-known information, and replay output. Do
not mutate `GameState` components in place.

## 5. Rules, target, payment, and predicate enumeration

### What is already shared

Within one `LegalActionEnumerator.enumerate` call, `EnumerationContext` shares projected state,
controlled battlefield, available mana sources, mana statics, and lazy helper objects. The
enumerators also use `ACTIONS_ONLY`, so the trusted Gym path does not pay the optional full auto-tap
preview cost solely to enumerate an action.

### What is recomputed across boundaries

`ObservationBuilder` owns a separate `Visibility`, `PredicateEvaluator`, `ManaSolver`,
`PaymentDomainBuilder`, cost calculator, and action serializer. It must own a privacy-safe public
projection, but that means it does not receive the `EnumerationContext`'s cached source list or
helper results. Reusing them blindly would be unsafe because:

- V5 payment discovery asks `ManaSolver` for `paymentOrderRequired=true` and full source/profile/
  stability facts;
- ordinary enumeration's `availableManaSources` is discovered without that V5 payment-order
  requirement;
- payment source filtering depends on the specific spell/ability payment context and source
  exclusions;
- target-dependent activated costs require a different target-bound cost and payment domain for
  each selected target;
- visibility and addressability are perspective-dependent; and
- a `null`/unsupported domain is a typed fail-closed result, not an empty legal set.

`PaymentDomainBuilder.buildV5` performs source discovery, producer-order validation, perspective
checks, profile/certificate validation, and per-source activation-domain construction. See
[`PaymentDomain.kt:552-617`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym/src/main/kotlin/com/wingedsheep/gym/contract/PaymentDomain.kt#L552-L617).
`ManaSolver.findAvailableManaSources` also performs an optional paid-source execution-stability
probe that can re-run source discovery after simulated self-damage for painful sources. See
[`ManaSolver.kt:2755-2913`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/ManaSolver.kt#L2755-L2913).

### Current evidence for payment/target completeness cost

The baseline JFR sampled `PaymentDomainBuilder.buildV5` only five direct application stack frames
in the recorded hot-method evidence, with approximately 20-21 stack-name occurrences in the
bounded execution-sample count. This is far below the dominant canonicalizer sample group, but it
is not a call count and does not prove low total cost. The baseline did not record:

```text
paymentDomainV5For calls per observation
targetPaymentDomainV1 binding count
ManaSolver.findAvailableManaSources calls by payment context
paid-source stability probe iterations
target-domain mapper calls
payment-domain build time by action kind
```

Therefore:

```text
PAYMENT_TARGET_COMPLETENESS_COST=UNKNOWN_NEEDS_MEASUREMENT
PAYMENT_TARGET_COMPLETENESS_IS_PRIMARY_CORPUS_HOTSPOT=NOT_PROVEN
PAYMENT_TARGET_COMPLETENESS_HAS_HIGH_WORST_CASE_COST=CODE-INSPECTION FINDING
```

The locked corpus contains 191,299 public `ActivateAbility` entries, so the measurement is worth
doing even though the current sampled attribution is not primary. It should be a typed counter and
timer, not a shortcut that drops action candidates or payment choices.

## 6. Registry and cold start

`MtgSetCatalog.all` is lazy and invokes `CardDiscovery.findSets`, which runs a ClassGraph scan. Each
set's lazy `cards`/`basicLands` access is backed by a per-package ClassGraph cache. The server wires
one `CardRegistry` and one `MultiEnvService` singleton. See
[`MtgSetCatalog.kt:19-33`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/mtg-sets/src/main/kotlin/com/wingedsheep/mtg/sets/MtgSetCatalog.kt#L19-L33),
[`CardDiscovery.kt:43-153`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/mtg-sets/core/src/main/kotlin/com/wingedsheep/mtg/sets/discovery/CardDiscovery.kt#L43-L153),
and
[`GymBeansConfig.kt:14-59`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/gym-server/src/main/kotlin/com/wingedsheep/gym/server/config/GymBeansConfig.kt#L14-L59).

The measured first-process registry/service setup was 17.474 s in corpus8 and 21.163 s in the
one-episode witness. The second B1 replay verification pass in the same JVM reported approximately
14 ms for its warm registry construction. This is strong evidence for:

```text
CLASSGRAPH_FIRST_SCAN=COLD_START_ONLY
REGISTRY_SETUP_FOR_LONG_RUNNING_TRAINING=LOW_VALUE_AFTER_WARMUP
REGISTRY_SETUP_FOR_SHORT_LIVED_WORKERS_OR_B0_TESTS=POSSIBLY_RELEVANT
```

The corpus JFR also showed `ClasspathElementZip.open` and thousands of thread-start/end events in
the ClassGraph-heavy recording. That validates a cold-start investigation, not a per-transition
optimization. For long-running actors, process reuse and multiple episodes amortize it; reset does
not rebuild the card registry.

## 7. B0-specific overhead

### Comparable-runtime boundary

The accepted baseline records:

```text
corpus8 median linear 64-episode projection = 775.540 s = 12.93 min
supplied B0-64 wall time                 = 6,109 s = 101.8 min
ratio                                    = 7.877x
```

This comparison is useful as a trigger for attribution, not as a phase measurement. The B1 corpus8
profile ran eight fixed 2,000-transition episodes and all eight truncated. The accepted B0 run
reported 64 episodes with 23 natural terminals and 41 semantic-budget interruptions. The current
exact-pair test source calls its full corpus 72 episodes (`0..31` in the primary orientation plus
four swapped seeds, each with both starting players). These are materially different workloads.

### B0 work found in the live source

The exact-pair acceptance source provides the following first-party architecture evidence:

1. **Corpus capture and replay-frame duplication.** The four `replayCases` are run through the
   normal corpus. For those cases, `recordReplayObservation` calls `replayFrame`, which invokes
   `ObservationCanonicalizer.semanticJson` even though `ObservationBuilder.build` has already
   called `StateDigest.compute` and therefore already built the same semantic representation class
   of data. It also computes semantic action keys/ordinals for the recorded choices.
2. **Authoritative checkpoint capture.** Every configured cadence boundary calls the reflective
   `CompactReplayBridge.fingerprint`, which enters `ReplayFingerprint` and the full transition-
   semantic GameState canonicalizer. This is not the Gym `StateDigest` and has different schema/
   version semantics.
3. **Replay gate.** The replay gate replays two public cases, checks every public digest/frame,
   takes cadence/tail fingerprints, constructs a CompactReplay through reflection, codec-round-
   trips it, and invokes `ReplayReconstructor.reconstruct`. Reconstruction folds every recorded
   action and builds spectator snapshots/deltas.
4. **Two-perspective privacy gate.** For two cases, `auditAllPerspectives` calls
   `environment.legalActions()` once and then calls `ObservationBuilder.build` once per player
   perspective. The normal `gym.observe` already built the acting perspective. After each direct
   `environment.stepStrict`/observation, the audit repeats the two-perspective full build. This is
   a privacy requirement, but state-global projection/features and exact public-domain intermediates
   are revisited; the phase is a likely large B0-only multiplier.
5. **Wire and digest audit.** The privacy gate performs wire serialization once per perspective
   schema and performs dynamic-reference/privacy checks on every frame. It recomputes a digest for
   the schema-audited perspectives, in addition to the builder's digest.
6. **Reflection/compilation cold work.** The bridge can load the current game-server classes through
   the parent classloader or build a URL classloader; if replay classes are absent it launches a
   separate game-server compile. This is cold/harness work, not normal Gym throughput.

The exact source sections are:

- corpus and replay cases: `EnvironmentV1ExactPairAcceptanceTest.kt:1197-1229`,
  `1357-1433`, `1480-1688`, and `2915-3330`;
- privacy fan-out and checks: `EnvironmentV1ExactPairAcceptanceTest.kt:1863-1973` and
  `1998-2173`;
- reflective bridge, codec, reconstruction, and version construction:
  `EnvironmentV1ExactPairAcceptanceTest.kt:3891-4172`;
- production replay checkpoint/fingerprint behavior:
  `GameSession.kt:1354-1382`, `1472-1488`, and
  `ReplayFingerprint.kt:29-100`;
- production CompactReplay reconstruction:
  `ReplayReconstructor.kt:105-175`.

The current bridge constructs CompactReplay version `4` while `CompactReplay.CURRENT_VERSION` is
`5`, and the current action carrier can contain `PaymentStrategy.ExplicitV3`. The accepted baseline
therefore correctly records the actual CompactReplay/ReplayFingerprint gate as FAIL/NOT_RUN for
overhead attribution rather than weakening the version guard. See
[`CompactReplay.kt:84-114`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/CompactReplay.kt#L84-L114)
and
[`ReplayFingerprint.kt:29-100`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayFingerprint.kt#L29-L100).

### B0 conclusion

```text
B0_NORMAL_SIMULATION_ALONE_EXPLAINS_6109S=NO under the fixed-horizon comparison
B0_PRIVACY_FANOUT=VERY_HIGH_LIKELIHOOD_CANDIDATE; direct timing NOT_RUN
B0_REPLAY_RECONSTRUCTION_AND_FINGERPRINT=VERY_HIGH_LIKELIHOOD_CANDIDATE; direct timing NOT_RUN
B0_PUBLIC_SEMANTIC_REPLAY_DUPLICATION=MEASURED_IN_B1_DIAGNOSTIC; not full B0 attribution
B0_EXACT_PHASE_SPLIT=UNKNOWN_NEEDS_MEASUREMENT
```

The first bounded attribution should run the existing gates as isolated phase workloads with
process CPU, thread allocation, GC, heap, observation-build count, legal-enumeration count,
semantic-frame count, privacy-perspective count, checkpoint count, and registry-build count. Do not
run a new multi-hour B0-64 merely to obtain this split.

## 8. Parallelism and scaling

### Existing safe seam

`MultiEnvService.stepBatch` creates one `Callable` per request and `EnvWorkerPool` submits those
independent calls to a `ForkJoinPool`, collecting futures in input order. This is the correct
granularity for deterministic cross-environment throughput: do not parallelize one environment's
Rules transition or reorder a single environment's actions/RNG.

### Current caveats

Each `GameEnvironment.create` constructs an `EngineServices` graph and an `ActionProcessor`/
`LegalActionEnumerator` pair. The shared `CardRegistry` is read-only after setup in the intended
service lifecycle, but `EngineServices` currently assigns process-global mutable collaborators:
`DamageUtils.cardRegistry`, `ZoneTransitionService.staticAbilityHandler`,
`ZoneTransitionService.cardRegistry`, and `ZoneTransitionService.replacementEffectProcessor`. See
[`EngineServices.kt:38-115`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/rules-engine/src/main/kotlin/com/wingedsheep/engine/core/EngineServices.kt#L38-L115)
and
[`ZoneTransitionService.kt:153-166`](https://github.com/chrismaghuhn/argentum-engine/blob/ffcf897213f09932d30020f3c1df20b99f369b84/rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/ZoneTransitionService.kt#L153-L166).

The replacement processor is currently stateless, and the card registry is normally the same
object, so this is not proof that every batched run diverges. It is, however, a real concurrency
boundary and prevents a high-confidence parallelism claim until global service state is either
removed, made immutable, or explicitly thread-confined. `GameState.projectedState` also uses a
synchronized lazy value; independent environments have distinct states, while forked environments
can initially share a state object and contend on its first projection.

### Required scaling measurement

After single-environment characterization, run the same fixed seed/policy corpus with warmup and
separate measured repetitions at 1, 2, 4, and 8 environments/workers. Record:

```text
batch wall time and per-env latency
external transitions/s and episodes/s
actual concurrent worker count
process CPU / host CPU
thread allocation and GC time
heap/RSS and post-reset retention
cross-env digest/diagnostic isolation
sequential-vs-batch result ordering and replay equality
```

The expected multi-env gain is `UNKNOWN` until this is measured. The existing seam suggests a
potentially high throughput gain, but current allocation is already about 1.238 GB/s at the
single-run rate and can become the limiting resource before all logical cores are useful.

## Ranked optimization candidates

Risk legend: `L` low, `M` medium, `H` high. “Evidence” is deliberately separated from “impact”;
impact categories are not measured speedups.

| Rank | Candidate / hot path | Evidence, root cause, redundant work | Expected impact / confidence / complexity | Rules risk | Determinism risk | Privacy risk | Decision-completeness risk | Replay risk | Smallest safe seam | Required RED test | Benchmark to prove value | Required regression gates |
|---:|---|---|---|:---:|:---:|:---:|:---:|:---:|---|---|---|---|
| 1 | Canonicalizer legal-action sort-key precomputation (current independent plan) | `MEASURED`: canonicalizer is the leading sampled application hotspot; TimSort/string/tree methods and byte allocation dominate. The independent characterization measured corpus8 `f=211,368`, `m=1,200,164`, aggregate `m/f=5.6781`, and maximum `(n,f,m)=(29,29,216)`. `CODE-INSPECTION`: `sortedBy { canonicalize(it).toString() }` repeats selector work. | `HIGH` normal-path candidate / `H` duplicate-work confidence / `L/M` complexity; no speedup measured | L/M | M/H | H | M | H | Materialize one canonical sort key per semantic action inside the existing semantic schema; preserve the exact final canonical JSON and no new ID/order authority. | Existing canonicalization tests plus byte-for-byte semantic JSON/digest differential over reordered candidates, unordered arrays, structured domains, generated IDs, and hidden cards. | Run an A/B on the same production-semantics corpus8: wall/CPU/allocation/GC, exact semantic bytes/digests, and `m` reduction; keep probe-disabled runs as the KPI. | `ObservationCanonicalizationTest`, `StateDigestTest`, privacy, replay, strict Gym, full module/Hosted CI. |
| 2 | Single-pass `ObservationBuilder` action metadata, especially payment/target qualification | `CODE-INSPECTION`: `paymentDomainV5For` can run once for diagnostics and again for the same action view; payment request maps target domains again. Target-dependent V1 can build V5 per target. | `HIGH` potential / `M` confidence / `M` complexity | H | H | H | H | H | Per-build typed memo keyed by exact state and legal-action index/identity; cache complete supported/unsupported results, never a Boolean or inferred payment choice. | Golden matrix for supported, empty, unaffordable, unsupported, target-dependent, restricted/painful, and stale payment domains; assert candidate count and diagnostics unchanged. | Add test-only counters/timers for mapper, qualification, V5 build, source-discovery, target-binding, and per-action bytes; compare domain byte/semantic equality. | Gym payment/target/privacy/decision tests; Rules payment/target tests; CompactReplay; exact-pair/B0; Hosted CI. |
| 3 | Generation-scoped reuse of the exact current legal-action snapshot for strict candidate binding (`GameGymEnv` -> `stepFromCandidateStrict`) | `MEASURED`: legal-enumerator and `GameEnvironment.legalActions` are sampled hotspots, and the independent characterization counted 31,342 actual enumerations over corpus8. `CODE-INSPECTION`: ordinary Gym actions do one pre-state strict scan and one post-state scan, while the current observation already came from the same pre-state scan. | `HIGH` potential / `M` confidence / `H` complexity | H | H | M | H | H | Add an explicit immutable state-generation/identity token and reuse only when the registry, state generation, actor, and candidate certificate match; preserve a live revalidation path for direct environment callers. | Same semantic candidate set, order/certificates, stale-handle rejection, and zero mutation under direct-state-advance controls; include duplicate action templates and combat domains. | Count exact enum calls/hits/misses and compare fixed corpus8 wall, CPU, allocation, domain cardinality, and replay frames. | Gym strict/privacy/domain tests; Rules tests; exact B0 corpus/replay/privacy; Hosted CI; no fallback/unsupported counters. |
| 4 | Cache stable activated-ability semantic identity and structural signatures per exact immutable state/source | `MEASURED`: structural ability JSON and stable-ordinal frames appear in JFR; 191,299 `ActivateAbility` public entries. `CODE-INSPECTION`: grant/source scans and structural serialization repeat per view. | `HIGH` potential / `M` confidence / `M` complexity | M/H | H | M | H | H | Cache only `(state identity, source entity, runtime ability identity, provenance path)` results inside one builder generation; retain duplicate structural-ability ordinals and fail-closed unresolved behavior. | Two identical abilities with distinct IDs, duplicate grants, static/emblem/intrinsic abilities, renamed copies, and generated-ID replay differential. | Count stable-key calls, structural serializations, grant scans, and action-view bytes; compare corpus8 CPU/allocation and semantic JSON. | Observation canonicalization/privacy; ability enumeration/Rules; replay fingerprint and exact-pair; Hosted CI. |
| 5 | Move the `GameGymEnv` observation-cache guard before expensive build work | `CODE-INSPECTION`: cache comparison is at `GameGymEnv.kt:238-245`, after `ObservationBuilder.build` and `environment.legalActions` at `223-228`. `MEASURED`: current baseline does not issue repeated `observe()` calls in its inner loop, so current throughput impact is unknown. | `HIGH` for polling/B0 repeated reads / `H` existence confidence / `L` complexity | L | M | H | M | H | Check cached generation/perspective before any legal enumeration/build; invalidate on every environment generation change and reset/restore. | Repeated observe identity/semantic/wire equality, terminal/truncated reads, perspective changes, reset/restore, and direct environment advancement controls. | Add a test-only build/enum counter; benchmark repeated observe ratios and the exact B0 privacy/replay call pattern. | Gym cache, privacy, replay, strict-domain, exact-pair, Hosted CI. |
| 6 | Share V5 mana-source discovery within one observation/player | `CODE-INSPECTION`: V5 calls `findAvailableManaSources(... paymentOrderRequired=true)` and source stability/profile logic for each payment domain; action-level contexts still differ. `JFR`: ManaSolver/source-discovery stacks are present but not separately timed. | `HIGH` worst-case / `L` confidence / `H` complexity | H | H | H | H | H | Cache only the exact context-independent source discovery/certificate layer; leave per-action cost/context, exclusions, target binding, and producer choices intact. | Paid source, pain/self-damage, restricted floating mana, mixed restrictions, target-bound cost, source exclusion, and no-source controls; assert complete source/production domains. | Count source discovery, stability probes, per-source domain builds, and target bindings by action; compare fixed corpus and pathological payment fixtures. | Rules payment suite, Gym payment/target/visibility, replay, exact B0, Hosted CI. |
| 7 | Isolate and reduce B0 privacy fan-out without weakening the audit | `CODE-INSPECTION`: two full `ObservationBuilder.build` calls per audited state plus a legal-action scan; privacy gate covers two cases and both perspectives. | `VERY_HIGH` B0-only potential / `M` confidence / `M/H` complexity | M | H | H | H | H | Keep one build per perspective, but share only proven state-global immutable intermediates and phase accounting; never reuse hidden-sensitive features or suppress a perspective. | Both perspectives' semantic/wire outputs, hidden-zone/reference audit, digest, candidate/domain completeness, and no diagnostic changes before/after intermediate reuse. | Run isolated one-episode privacy-only and corpus-only profiles with build/enum/perspective counters; do not run full B0-64. | Exact-pair privacy, strict Gym, observation privacy, replay/digest, B0 zero-trust invariants, Hosted CI. |
| 8 | Isolate B0 CompactReplay/fingerprint/reconstruction work | `CODE-INSPECTION`: replay capture calls semantic frames and cadence fingerprints; gate codec-round-trips and reconstructs every action/spectator delta. `MEASURED`: public semantic replay adds +49.021 s on 4,000 transitions; actual CompactReplay overhead is NOT_RUN due v5 precondition. | `VERY_HIGH` B0-only potential / `L` confidence / `H` complexity | M | H | H | M | H | Add phase timers/counters and eliminate only redundant diagnostic serialization or classloader/codec work; retain exact replay reconstruction and checkpoint verification. | Replay fidelity, checkpoint equality, action/frame cardinality, codec round-trip, divergence handling, and failure-closed behavior. | Separate corpus, replay-capture, replay-verify, reconstruction, fingerprint, codec, privacy, and artifact phases on bounded cases. | CompactReplay v1-v5, ReplayFingerprint, reconstruction, exact-pair, privacy, B0 invariants, Hosted CI. |
| 9 | Reduce immutable transition/map/event-history allocation | `CODE-INSPECTION`: `GameState`/`ComponentContainer` map `+` copies and cumulative event history copies prefixes. `MEASURED`: 8.06% `LinkedHashMap.Entry`, 0.49% `plus` site, but source split is unknown. | `HIGH` allocation potential / `M` confidence / `H` complexity | H | H | M | H | H | First characterize; then use a transition-local accumulator or persistent structure with one immutable commit, preserving all intermediate Rule observations/events. | Full Rules event/state/RNG/replay matrix plus cumulative-event API and fork/snapshot identity controls; no in-place component mutation. | JFR allocation-by-site with stack depth, event-history length, state-copy counters, and fixed corpus CPU/allocation/GC. | Full Rules, Gym, replay, scenario, determinism, exact B0, Hosted CI. |
| 10 | Stream or otherwise reduce the semantic JSON/UTF-8/digest pipeline | `MEASURED`: byte arrays 63.25%, string/builder/canonicalizer methods dominate. `CODE-INSPECTION`: current semantic digest materializes multiple JSON trees/strings before hashing. | `VERY_HIGH` normal-path potential / `H` intrinsic-cost confidence / `H` complexity | M | H | H | H | H | Prefer a differential streaming encoder that emits the exact current canonical semantic JSON bytes; do not change the digest schema or hash identity without versioning. | Known-answer byte/digest matrix, object/array order, privacy, structured domains, generated IDs, replay and transposition equality. | Compare exact semantic bytes, digest, allocation, CPU, and GC on corpus8 plus replay diagnostic; separately measure transport serialization. | Canonicalization, StateDigest, privacy, replay, strict Gym, full module/Hosted CI. |
| 11 | Reduce fresh action-handle/remap collection churn | `CODE-INSPECTION`: each observation allocates raw IDs, fresh IDs, a zip map, copied action views, and registry remaps; 211,318 action entries were rebuilt in corpus8. | `MEDIUM` / `H` existence confidence / `L/M` complexity | M | H | M | M | H | Preserve monotonically fresh opaque handles and registry invalidation; replace only temporary map/list shape with equivalent one-pass construction. | Exact handle freshness/staleness, registry cardinality, action order, semantic digest, reset/restore, and no handle reuse. | Allocation and CPU comparison with identical action counts; measure registry remap bytes separately from builder/canonicalizer. | Gym action-contract, privacy, replay, strict-domain, exact-pair, Hosted CI. |
| 12 | Cross-environment `stepBatch` scaling | `CODE-INSPECTION`: existing `ForkJoinPool` fan-out is the intended independent-env seam. `MEASURED`: no 1/2/4/8 throughput evidence exists; current single-env process uses approximately 2.43 CPU cores including runtime work and allocates 1.238 GB/s. | `HIGH` multi-env potential / `L` confidence / `M/H` complexity | H | H | M | H | H | Benchmark existing batch seam first; fix/prove global service state and same-env exclusion before changing scheduling. | Sequential versus batch exact observations/digests/replays, request-order result mapping, env isolation, fork/projection contention, and deterministic diagnostics. | Fixed corpus at 1/2/4/8 envs/workers with warmups, CPU/GC/allocation/RSS, p50/p95/p99 latency. | Gym batch, Rules concurrency, privacy, replay, exact B0 regression, Hosted CI. |

The table intentionally does not rank registry setup or external policy sorting in the top twelve:
registry setup is a cold-start concern, and the policy consumed only 1.483% of the measured corpus
pass. Both remain useful controls in the benchmark contract.

## Candidate categories

### A — low-risk / high-confidence immediate candidates

- Move the observation-cache guard before the build, but only after a small counter confirms the
  target call pattern. The existence of the wasted work is certain; its current corpus impact is not.
- Fuse `ObservationBuilder`'s already-computed per-action metadata into diagnostics/view/registry
  consumption, retaining typed unsupported results and exact public domains.
- Reduce `ActionRegistry`/opaque-handle remap temporaries with a differential action-contract test.
- Precompute canonical action sort keys: the independent characterization already proves `m > n`
  and supplies the fixed differential benchmark; exact semantic output must still remain
  byte-identical in any implementation.

### B — promising but characterization required

- Generation-scoped reuse of the current legal-action snapshot for strict candidate binding.
- Stable activated-ability identity/structural-signature memoization.
- Sharing the context-independent portion of V5 mana-source discovery.
- Immutable event/state allocation reduction after JFR stack attribution.
- B0 privacy fan-out reduction that keeps every perspective and every privacy check.

### C — architectural / later B1 candidates

- A streaming exact-semantic-byte encoder or a versioned non-JSON digest implementation.
- B0 replay reconstruction/fingerprint phase redesign after the v5 bridge is runnable and measured.
- EngineServices/global-state isolation required for high-confidence multi-env parallel scaling.
- Transition-local immutable-state batching or a persistent state-map redesign.
- A generation-owned evaluation object that spans strict validation, public observation, and replay
  without weakening direct-caller freshness checks.

### D — not worth pursuing at the current profile

- External policy sorting/canonicalization as the first optimization: measured share is approximately
  1.5% and it is a test/harness policy, not the production Rules authority.
- Registry/classpath micro-optimizations for long-running training: the first scan is real, but warm
  construction is already milliseconds and reset does not rescan.
- Cosmetic `toString` or small collection substitutions before the semantic-byte and duplicate-domain
  counters identify their parent cost.
- Parallelizing actions within one environment, using unordered map/set iteration, or replacing a
  complete domain with one preferred candidate: these are not valid optimizations under the project
  invariants.

## Named conclusions

```text
PRIMARY_SYSTEMIC_BOTTLENECK=
  transition-plus-public-observation construction, especially repeated legal/domain work and
  semantic JSON/tree/string/byte materialization

BEST_FIRST_OPTIMIZATION=
  canonicalizer legal-action sort-key precomputation: the independent characterization proves
  corpus8 m=1,200,164 versus f=211,368 (m/f=5.6781); first prove end-to-end wall/allocation value
  with a production-semantics A/B and preserve byte-identical semantic output

BEST_EXPECTED_SINGLE_ENV_GAIN=
  HIGH potential, unmeasured: the strongest narrow candidate is the measured canonicalizer sort-key
  duplication; larger gains may come from same-state legal/domain/ability reuse but are less proven

BEST_EXPECTED_MULTI_ENV_GAIN=
  HIGH potential, unmeasured: existing independent-environment stepBatch/EnvWorkerPool seam after
  global-service-state and allocation scaling are proven

BIGGEST_DUPLICATE_WORK=
  same-state legality/public-domain recomputation: strict candidate scan plus observation scan,
  ObservationBuilder payment/target re-evaluation, and repeated stable ability resolution

BIGGEST_ALLOCATION_TARGET=
  canonical semantic JSON tree -> StringBuilder/String -> UTF-8 byte[] -> SHA-256/hex pipeline;
  immutable state/map copies are the second architectural allocation target

BIGGEST_B0_ONLY_TARGET=
  two-perspective privacy fan-out of full ObservationBuilder/legal-domain work, with CompactReplay
  reconstruction/fingerprint work as the co-equal unmeasured contender

HIGHEST-RISK_TEMPTING_OPTIMIZATION=
  trusting cached/first/sorted legal candidates or a solver-selected payment instead of revalidating
  the complete Rules-owned public domain; this risks decision completeness, determinism, privacy,
  replay, and ZERO-UNSUPPORTED simultaneously
```

## Adversarial questions

### 1. Are we spending significant time computing legal information more than once per state?

Yes, in the control-flow sense. An ordinary active `GameGymEnv.step` performs one pre-state strict
candidate enumeration and one post-state observation enumeration; the current observation already
resulted from an earlier pre-state enumeration. Target-bound payment adds another pre-state scan.
The baseline JFR independently samples legal enumeration as a major hotspot. The separate
measurement-only characterization counted 31,342 actual `LegalActionEnumerator.enumerate` calls
over corpus8 and 16,058 `ObservationBuilder.build` calls; the wrapper-level
`legalActions()` total, including early-return calls, is still not recorded.

### 2. Are `ObservationBuilder` and `LegalActionEnumerator` duplicating work?

Partly. The builder does not normally re-search target candidates: it maps the complete
Rules-produced `TargetInfo` list and checks perspective addressability. It does, however, redo
target-domain mapping inside payment qualification, recompute effective costs, build V5 payment
domains independently of the enumeration context, repeatedly resolve activated-ability identity,
and call payment-domain construction from both diagnostics and views. This is duplicate derived
work, not proof that the two components can share all data without an authority audit.

### 3. Is JSON canonicalization fundamentally expensive, or merely repeatedly invoked?

Both. JFR shows intrinsic allocation/copy cost in byte arrays, strings, builders, JSON rendering, and
sorting. The implementation also repeatedly builds/canonicalizes related trees within one digest,
and B1's replay diagnostic invokes semantic canonicalization again per frame. The independent
characterization proves the legal-action selector evaluated 1,200,164 canonical sort keys for
211,368 semantic fingerprints in corpus8, an aggregate `m/f` of 5.6781. The current plan addresses
that real repeated sub-pass, but not the whole canonicalization pipeline.

### 4. Could a non-JSON semantic digest eventually be justified?

Yes, as an exact implementation of the current canonical semantic byte stream, with differential
known-answer tests. A different semantic representation/hash would require an explicit versioned
contract and replay/transposition migration; it is not an invisible optimization.

### 5. How much of current cost is caused by payment/target-domain completeness?

The worst-case cost is high: V5 source/profile/stability certificates can run per applicable
action, target-dependent relations can run per target, and the same result is currently queried in
multiple builder branches. The current corpus has 191,299 `ActivateAbility` public entries. The
accepted JFR did not isolate these calls and sampled only a small number of direct V5-builder
frames, so the current percentage is `UNKNOWN_NEEDS_MEASUREMENT`, not a proven primary hotspot.

### 6. Does immutable `GameState` materially contribute to the bottleneck?

Yes to allocation bandwidth and probably to CPU spent copying maps/lists. No to the claim that it is
the sole or primary CPU hotspot: the state projection is cached per immutable object, and the
measured top CPU/allocation evidence is dominated by semantic JSON/string/byte work plus legal/domain
enumeration. Any state representation change is a later architectural candidate with broad Rules
and replay risk.

### 7. Are Kotlin collection pipelines a meaningful allocation fraction?

Yes, but not the largest byte fraction. JFR shows `ArrayList`/iterator/grow, map entries, sorting,
and `CollectionsKt.plus` activity. The biggest category is still `byte[]`; collection pipeline
cleanup should follow source attribution rather than replace every `map/filter/sortedBy` blindly.

### 8. Is registry setup worth optimizing for long-running training?

Usually no. It is 17-21 seconds on a first process, but a warm second construction was about 14 ms
and reset reuses the registry. It matters for short-lived actors, test JVMs, and B0 harness lifecycle;
process reuse is the higher-value operational answer for long-running training.

### 9. Is B0's approximately 8x excess primarily verification overhead or different gameplay work?

It is not proven to be one or the other. The workloads differ, and the source adds privacy fan-out,
semantic replay frames, fingerprints, CompactReplay reconstruction, spectator deltas, and possible
reflection/classloader/compile work. The evidence supports “B0-specific verification/instrumentation
plus different workload” and does not support assigning an exact share to any one phase.

### 10. After safe single-thread optimizations, where should parallelism enter?

Across independent environments at `MultiEnvService.stepBatch`/`EnvWorkerPool`, with fixed request
ordering and one owner thread per environment. Do not parallelize a single Rules transition, action
order, RNG stream, or decision domain. First remove/prove process-global engine collaborators and
measure 1/2/4/8 scaling.

### 11. What would likely prevent Argentum from reaching 1 episode/s?

At the fixed 2,000-transition horizon, 1 episode/s requires approximately 12.12x the measured
single-process episode rate and 2,000 transitions/s. The current profile allocates about 15.0 GB
per 2,000-transition episode, or about 15 GB/s at 1 episode/s. No measured single-thread candidate
in this audit supports a 12x claim. Reaching 1 episode/s likely requires both duplicate-work removal
and a large semantic-byte/allocation reduction, possibly followed by cross-env scaling.

### 12. What would likely prevent 5-10 episodes/s on a modern desktop CPU?

That target is approximately 60.59-121.18x the measured single-environment rate. At the current
allocation profile it implies roughly 75-150 GB/s of short-lived allocation for 2,000-step episodes,
before accounting for GC, state copies, or transport. That is not a credible current-architecture
target without major allocation reduction and substantial independent-environment scaling. A
shorter terminal-episode distribution could change the arithmetic, so the target must be stated with
the episode-length contract.

## Canonicalizer current-plan assessment

```text
CANONICALIZER_CURRENT_PLAN_ASSESSMENT=
  MAJOR normal-path duplicate-work candidate for the narrow sort-key seam: independent corpus8
  characterization proves m=1,200,164 versus f=211,368 (m/f=5.6781);
  USEFUL_BUT_SMALL relative to the unexplained B0 gap;
  end-to-end A/B wall/allocation value still needs measurement before claiming a speedup
```

The plan is correctly aimed at a real sampled hotspot and should remain independent. It should not
be treated as the complete canonicalization solution: full observation JSON encoding, semantic action
fingerprint construction, recursive final canonicalization, UTF-8 materialization, SHA-256, and hex
formatting remain. It also cannot explain B0 privacy/replay work by itself.

## Measurements required before implementation

The next measurement package should remain test-only and bounded:

1. Add counters in a disposable characterization harness for `GameEnvironment.legalActions`, actual
   `LegalActionEnumerator.enumerate`, `ObservationBuilder.build`, `paymentDomainV5For`, target
   bindings, stable-ability-key resolution, `StateDigest`, semantic JSON, and cache hits/misses.
2. Record action-shape distributions: ordinary, structured, folded decision, target-bound payment,
   combat declaration, and pending-decision states.
3. Record target/payment domain sizes, source-discovery calls, paid-source stability probe iterations,
   and unsupported/null reasons without exposing hidden state or selecting a payment.
4. Characterize event-history length and allocation stack traces for `GameEnvironment.events +`.
5. Use the completed separate canonicalizer `n/m` characterization as call-count evidence without
   changing its worktree; the next independent step is a production-semantics A/B, not another
   unbounded profiler run.
6. Run isolated bounded B0 phases: corpus only, replay capture/replay verification,
   privacy-only, CompactReplay reconstruction/fingerprint when the version precondition is fixed,
   and artifact/diagnostic output. Do not run a multi-hour B0-64 for the first attribution.
7. Run the B1 issue's 1/2/4/8 environment batch matrix with identical seed/policy/deck and separate
   in-process versus HTTP measurements.

Each measurement must preserve:

```text
same locked decks and hashes
same engine/card identity
same seeds and external policy
same action/domain cardinality
same perspective/privacy output
same RNG consumption/order
same replay carrier/checkpoints
zero unsupported/native-fallback/public-rejection changes
```

## Final audit status

```text
AUDIT_SCOPE=INDEPENDENT_DEEP_PERFORMANCE_AUDIT
PRODUCTION_CHANGES=0
PRODUCTION_OPTIMIZATIONS=0
BASE_HEAD=ffcf897213f09932d30020f3c1df20b99f369b84
BASELINE_HEAD=d0f4fe9eddb377fbddd7e675ec396ecbabe254d5
DATA_TRUSTED=NO
IMPLEMENTATION_STARTED=NO
UNKNOWN_REQUIRING_MEASUREMENT=exact legal-action call totals, payment/target cost share,
  B0 phase attribution, CompactReplay fingerprint cost, 1/2/4/8 scaling, event-history allocation,
  and canonicalizer sort-key end-to-end wall/allocation value
NEXT=INDEPENDENT_REVIEW
```
