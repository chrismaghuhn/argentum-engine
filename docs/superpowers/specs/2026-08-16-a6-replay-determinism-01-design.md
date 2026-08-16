# A6 Replay Determinism, Snapshot/Fork Equivalence, and Reconstruction Foundation

Status: reviewed with required edits incorporated; ready for implementation planning

Design checkpoint: APPROVED_WITH_REQUIRED_CHANGE

Versioning decision: CompactReplay v3

This document specifies the narrow hardening work for A6-REPLAY-DETERMINISM-01. It reuses the
existing CompactReplay input-log model. It does not introduce a second replay format, a trajectory
format, an ML export, or a second observation model.

## 1. Decision summary

CompactReplay v3 is required because the meaning of the persisted replay checkpoints changes. v1 and
v2 remain readable, with their historical verification semantics preserved. v3 is the first version
that uses the transition-complete canonical GameState fingerprint defined below. Every v3 replay also
stores a checkpoint at the current action-stream tail. A replay with a version above v3 is rejected
explicitly before best-effort decoding.

The v3 fingerprint treats a GameState as the complete authoritative transition state. It includes
hidden information, RNG continuation, ordered zones, stack order, pending decisions, continuations,
yields, combat, commander state, and replacement/resume state. It is not a perspective observation
digest.

Decision nonces are normalized only in typed decision-identity fields. The normalization preserves
the alias relationship across every correlated PendingDecision, ContinuationFrame, and future
persisted yield reference. It does not ignore arbitrary fields named id or decisionId.

Gym snapshots restore the environment step count captured at the snapshot. The restored horizon
therefore resumes from the original episode position.

The A4 files remain untouched:

* TrainingObservation.kt
* ObservationBuilder.kt
* StateDigest.kt
* SchemaHash.kt

## 2. Scope and non-goals

### In scope

* Characterize the existing replay setup, action log, persistence, fidelity, pinning, yield, and
  reconstruction paths.
* Prove independent replay reconstruction and live-versus-replay prefix equality.
* Replace the incomplete current replay fingerprint with the versioned v3 canonical fingerprint.
* Add a matching v3 checkpoint at the action-stream tail and require it for v3 EXACT fidelity.
* Preserve v1 and v2 decode and verification behavior.
* Reject unsupported future replay versions explicitly.
* Route unsupported-version records to the existing durable-presentation fallback when available.
* Prove codec round-trip semantics.
* Prove entity-ID and seeded-RNG determinism.
* Prove pending-decision replay for decisions already represented by GameAction.
* Prove persistent-yield replay and pinned-card behavior.
* Prove Commander, modern combat, intervening-if, and LKI replay regressions where compact fixtures
  can exercise them.
* Fix and test Gym snapshot step-count restoration, snapshot continuation, fork equivalence, and
  fork isolation.
* Reconstruct a perspective observation through the existing ObservationBuilder as a secondary
  bridge test.
* Audit pending-decision presentation fields and add a checkpoint-boundary performance sanity gate.
* Preserve the existing public replay privacy boundary.

### Out of scope

* A5 decision completeness.
* A4 privacy implementation or changes to its contract files.
* B2 Trajectory V1, bulk replay export, or a raw CompactReplay endpoint.
* A new ML-specific replay model.
* Replay performance optimization.
* Card, decklist, attack-grouping, or unrelated upstream work.
* Snapshot reblessing or snapshot-harness changes.

## 3. Repository checkpoint

The audit was performed in an isolated worktree from the requested repository.

~~~text
BASE_MAIN: c70acda8d9579ec38078acc2b7c434ca68c8c729
UPSTREAM_MAIN_AT_START: 8992b18e95a905972d899e52cb1e53761f8650b0
CURRENT_REPLAY_VERSION_AT_AUDIT: 2
WORKTREE: C:\argentum-engine-a6-replay-determinism01
BRANCH: agent/a6-replay-determinism-01
~~~

The origin remote points to chrismaghuhn/argentum-engine. The worktree was clean before the
specification change. The main checkout's unrelated untracked .codex/ directory remains outside this
worktree and is not part of A6.

### Current architecture

| Contract | Current implementation |
| --- | --- |
| Replay input model | ReplaySetup plus resolved seed, ordered GameAction input, and persistent yield mutations |
| Setup contents | Format, attack mode, starting hand rules, starting player, seat roster, players, decks, commander configuration, and seed |
| Action log | Ordered GameAction values, including SubmitDecision responses and concession/mulligan actions where recorded |
| RNG source | Immutable, state-threaded GameRng seeded by GameInitializer; the resolved seed is stored in ReplaySetup |
| Entity IDs | State-threaded deterministic e0/e1/... allocation through nextEntityId |
| Persistent yields | ReplayYieldEntry events keyed to afterActionCount and reapplied during reconstruction |
| Pinned definitions | Compact serialized card definitions overlay the live registry for captured replay cards |
| Current fingerprint | Short SHA-256 digest over turn metadata, stack size, zone sizes, and life totals |
| Checkpoints | Sparse checkpoints at every 20 actions; current records have no guaranteed final checkpoint |
| Fidelity | EXACT, UNVERIFIED, or DIVERGED, with truncation at the last verifiable frame |
| Gym snapshot | Immutable GameState plus player IDs and step count in SnapshotCodec; GameGymEnv currently saves stepCount as 0 |
| Gym fork | Shares immutable GameState, copies player IDs and step count, and clears event history |
| Reconstruction | ReplayReconstructor initializes from setup, folds actions, reapplies yields, and checks checkpoints |
| Observation bridge | Reconstructed GameState is passed to the existing perspective ObservationBuilder; no parallel digest/model is created |

### Confirmed current gaps

1. The current ReplayFingerprint omits authoritative fields such as full entities and components,
   ordered zone contents, stack order, RNG state, counters, attachments, commander state,
   continuation state, delayed and replacement state, and yields.
2. ReplayCodec currently decodes without rejecting a future version.
3. CompactReplay v2 currently stores the incomplete fingerprint semantics. Reusing version 2 for the
   v3 algorithm would reinterpret persisted values and could turn an old correct replay into
   DIVERGED.
4. GameGymEnv.snapshot passes stepCount = 0 instead of environment.stepCount.
5. Existing focused replay and Gym tests are green but do not prove full-state prefix equality,
   decision-nonce normalization, or horizon-preserving snapshot restore.
6. The current reconstructor reports EXACT after any non-empty checkpoint set matches, even when the
   unchecked action suffix has drifted.
7. ReplayStore drops a row when CompactReplay decoding throws, so an archived presentation cannot
   currently be selected for an unsupported future compact version.

## 4. CompactReplay v3 compatibility contract

### 4.1 Version meanings

The supported wire versions are:

| Version | Decode | Fingerprint verification | Meaning |
| --- | --- | --- | --- |
| 1 | Supported | Legacy decode/verification path; normally UNVERIFIED when no checkpoints exist | Original compact replay shape |
| 2 | Supported | Historical v2 fingerprint algorithm | Added engineVersion, pinnedCards, and checkpoints |
| 3 | Supported | Transition-complete canonical v3 fingerprint | Current writer and verifier |
| Greater than 3 | Rejected | None | Unsupported future format |

The implementation sets CompactReplay.CURRENT_VERSION to 3. It does not silently upgrade a decoded
v1 or v2 record to v3. Encoding a historical record preserves its version and its historical
checkpoint meaning unless an explicit migration is added in a later, separately reviewed change.

### 4.2 Version is the fingerprint algorithm selector

CompactReplay.version is the sole compatibility selector for persisted checkpoint fingerprints in
this milestone. A separate optional fingerprintVersion field is not added. This avoids two version
authorities that could disagree.

Every checkpoint comparison and every in-progress resume comparison selects the algorithm from the
associated replay version:

~~~text
replay.version = 1 or 2  -> exact legacy fingerprint implementation
replay.version = 3      -> complete canonical v3 implementation
~~~

The implementation must not leave an unqualified current-algorithm call at a compatibility boundary.
In particular, GameSession.restoreReplayRecording must compute the live comparison with the
algorithm selected by record.version, because the persisted resumeFingerprint belongs to that
record. The current recorder and ReplayRecordingSnapshot use v3.

The legacy implementation is isolated and named as a legacy algorithm. It remains byte-for-byte
compatible with the current v2 digest: the same fields, ordering, SHA-256 input, and truncated
16-hex output. A v1 record without historical checkpoints remains UNVERIFIED; the implementation
does not fabricate a v1 comparison. The new implementation does not modify the legacy function.

### 4.3 Wire decoding

ReplayCodec.decode performs a small version inspection before normal Kotlin serialization:

* A missing version field is treated as legacy v1 for compatibility with the oldest records.
* An explicit version of 1, 2, or 3 follows its defined decode path.
* A version below 1 is an invalid replay error.
* A version above 3 throws an explicit unsupported-version error containing the received and
  supported versions.
* Unknown future fields do not authorize best-effort decoding of an unknown future version.

The future-version error is deterministic and testable. It occurs before replay reconstruction and
before any checkpoint is interpreted.

### 4.4 Fingerprint representation

The historical v1/v2 representation remains a lowercase 16-hex-character string. A v3 fingerprint
is the lowercase 64-hex-character SHA-256 digest of the canonical v3 bytes. The containing replay
version, rather than an ad hoc prefix inside the string, identifies the algorithm. Tests assert both
the version and the expected representation length so an old short value cannot be mistaken for a
v3 value.

### 4.5 v3 tail checkpoint

Every newly written v3 CompactReplay contains exactly one checkpoint at the current action-stream
tail:

~~~text
ReplayCheckpoint(
    afterActionCount = replay.actions.size,
    fingerprint = fingerprint of exactly that resulting GameState,
)
~~~

The rule also applies to an empty action stream, where the tail count is zero and the checkpoint
describes the initialized state after any action-count-zero yields have been applied. The live
ReplayRecordingSnapshot already captures a coherent fingerprint beside its action list; v3 record
creation uses that value for the tail checkpoint. If an interval checkpoint already exists at the
tail count, the writer keeps one checkpoint at that count and does not append a duplicate.

This is a v3 write requirement, not a migration rule. v1 and v2 records are not rewritten and do not
gain a retroactive tail checkpoint. A v3 record missing its tail checkpoint can never be classified
as EXACT; if all actions apply and no checkpoint disagrees, it is UNVERIFIED with an explicit missing
tail reason rather than trusted as an exact replay.

### 4.6 Unsupported versions at the service boundary

ReplayCodec.decode rejects a version above CURRENT_VERSION deterministically. The exception must not
escape as an uncontrolled HTTP 500 and must not be converted into a fabricated CompactReplay.

The persistence read path handles the rejection separately from ordinary corrupt-payload failures:

1. It retains the row's replay metadata and independently decodable archived presentation, together
   with an unsupported-version marker. It does not construct a partial CompactReplay from unknown
   fields.
2. ReplayService does not invoke ReplayReconstructor for that marker. If presentation is available,
   it serves that durable presentation through the existing viewer payload with fidelity DIVERGED,
   stateReproducible = false, and a controlled reason such as "unsupported CompactReplay version".
   It never reports EXACT.
3. If no presentation is available, the ordinary replay API returns the existing controlled
   unavailable/not-found response rather than a 500 or a partial reconstruction. The response does
   not expose a stack trace or raw future-version payload.

This preserves the existing archive fallback while making the decode failure visible and controlled.
VERSION-03A covers deterministic codec rejection. VERSION-03B covers the store/service/API behavior
with and without an archived presentation.

## 5. Transition-complete canonical GameState fingerprint

### 5.1 Authority

ReplayFingerprint remains the single replay equality and checkpoint authority. The v3 implementation
does not create a second state-hash system. It canonicalizes the existing serializable GameState
graph and hashes that canonical representation.

The fingerprint authority is the complete transition-semantic GameState, not byte-for-byte
presentation state and not merely visible board data. It is not TrainingObservation.stateDigest. A
matching observation digest never proves a matching full state. A serialized field is included by
default, but a presentation-only field may be excluded only after the audit gate in Section 5.3
proves that it cannot affect an engine transition, decision validation, or replay-authoritative
observable state.

### 5.2 Included state

The v3 canonical representation includes every transition-semantic GameState constructor field
unless it is explicitly proven derived or presentation-only. At minimum, the representation
includes:

* Turn number, phase, step, active player, priority player, timestamp, turn order, game-over state,
  winner, and priority-passed state.
* Every entity identity and every serialized component, including card identity, counters,
  attachments, characteristics, combat markers, last-known data, command-zone data, and any other
  component that affects a future transition or an observable full state.
* Zone keys and each zone's semantic contents. Lists such as Library and any ordered zone retain
  their order.
* Stack contents and stack order, including every stack entity and its components.
* Pending decision type and complete transition semantics: acting player, source and subject
  identities, target and candidate identities, options, min/max, mode shape, payment requirements,
  constraints, and all structured decision fields. Presentation strings follow Section 5.3.
* The complete continuation stack, including continuation type, order, all resume data, and
  decision-reference aliases.
* Trigger queues, delayed triggers, floating and granted effects, pending spell state, combat
  state, turn trackers, replacement state, and every other serialized resume field.
* Commander state, command-zone movement and cast-history data, commander tax/history, and
  commander damage where represented.
* RNG state, nextEntityId, and all state-threaded counters.
* The applied GameState.yieldsByPlayer configuration and its semantic identity and scope.

CompactReplay.yields is a separate out-of-band input log. A future ReplayYieldEntry whose
afterActionCount is greater than the current state does not enter that earlier GameState fingerprint.
Once reconstruction applies the entry at its recorded action count, the resulting
GameState.yieldsByPlayer value is included in every later checkpoint and tail fingerprint.

Transient-looking fields are included when they can affect the next transition. This includes
pending sacrifice/discard causes, active replacement chains, pending decision data, and other
short-lived resume fields. A field is not excluded because it is usually empty or because it exists
only between two engine calls.

### 5.3 Pending-decision field classification

The v3 contract distinguishes transition semantics from presentation text. The following table is
the required classification boundary:

| Field | Class | v3 rule |
| --- | --- | --- |
| PendingDecision.id | VOLATILE_ROUTING | Replace with the global deterministic decision alias |
| ContinuationFrame.decisionId | VOLATILE_ROUTING | Replace with the same alias table as PendingDecision.id |
| Decision kind / polymorphic type | SEMANTIC | Include; it selects the response and validator shape |
| Acting player / playerId | SEMANTIC | Include |
| Source, subject, triggering entity, targets, options, legal candidates | SEMANTIC | Include identities and semantic order |
| Min/max, constraints, mode structure, payment requirements | SEMANTIC | Include all values used to validate or resume the choice |
| Continuation relationship | SEMANTIC plus VOLATILE_ROUTING | Include the relationship after alias substitution |
| prompt | AUDIT_REQUIRED | Exclude only after proving it is presentation-only; include if any transition, validator, or replay-authoritative observable uses its value |
| effectHint | AUDIT_REQUIRED | Apply the same proof rule as prompt |
| yesText, noText, hint, display labels, and card-info display metadata | AUDIT_REQUIRED | Exclude when proven presentation-only; do not assume that from the field name |

The audit must search engine transition handlers, decision validators, continuation resumers,
action-error construction, and the existing observation boundary. A value used only to render a
prompt, build a log/event message, or display a UI hint is presentation-only for the v3 transition
fingerprint. A value that changes legal response validation, continuation data, or the next state is
semantic and remains in the fingerprint. The audit result is recorded in the implementation change
and protected by a regression test; v3 is not considered complete while an AUDIT_REQUIRED field is
silently classified.

### 5.4 Canonical encoding

The canonicalizer uses the existing serializer-visible state graph and a type-aware canonical form:

1. Include stable polymorphic type discriminators and field names.
2. Preserve list and array order by default. This rule covers Library, Stack, turn order,
   continuation order, queue order, and all other semantically ordered structures.
3. Sort map entries by the canonical encoding of their keys, then encode the canonical key and
   value. This removes map-iteration dependence without changing map semantics.
4. Sort set members by their canonical encoding. Sets represent unordered collections.
5. Sort component-map entries by stable component type identity before encoding them.
6. Encode nulls, booleans, strings, enums, numbers, and entity IDs with unambiguous typed canonical
   values. Hash UTF-8 bytes with the domain separator
   argentum-engine/replay-fingerprint/v3 followed by the canonical representation.
7. Use stable serialization names rather than Kotlin reflection names where the serializer provides
   an explicit name.

No list is sorted merely to make a test pass. A list may be treated as unordered only after a
field-specific semantic proof and a regression test. Any new GameState constructor field must be
reviewed for inclusion before the v3 fingerprint contract is considered complete.

### 5.5 Explicit exclusions

The initial exclusion set contains only derived caches that are recomputed from the authoritative
GameState, especially the lazy projected-state cache. It excludes no authoritative constructor field,
debug field, telemetry field, or runtime object solely because it is inconvenient to serialize.

The projected-state exclusion is valid only if tests show that materializing or clearing the cache
does not change the v3 fingerprint and that all projected values are derivable from included state.
If a later audit shows that a cache influences a transition or observable full state, it becomes
fingerprint input. The implementation records the exclusion and its proof next to the canonicalizer.

### 5.6 Performance sanity boundary

V3 canonicalization runs only at replay verification boundaries: interval checkpoints, the mandatory
tail checkpoint, in-progress resume capture, and explicit replay-state comparisons. It is not added
to the per-action engine transition path or called after every action merely because the state is
immutable.

The A6 performance gate is a sanity check, not a B1 benchmark. A fixed fixture records the expected
number of v3 fingerprint evaluations for its checkpoints, tail, and resume operation, and a code
audit verifies that no full-state canonicalization call was added to ActionProcessor's ordinary
action path. The result is reported as PERF_SANITY = PASS. No wall-clock threshold or optimization
claim is part of A6.

## 6. Decision nonce normalization

### 6.1 Allowed normalization

Decision IDs are runtime routing nonces. The v3 canonicalizer replaces only the known volatile
decision-identity slots with deterministic aliases:

~~~text
first semantic decision identity in canonical path order -> D0
next distinct identity                              -> D1
...
~~~

The alias table is global for one GameState. Equal raw values share one alias. Every occurrence in a
known correlated reference uses the same alias.

The current allowlist covers:

* PendingDecision.id.
* ContinuationFrame.decisionId for every serialized continuation type.
* Any persisted yield reference that is proven to refer to the same decision identity.

The implementation builds and applies aliases using stable serialized type and field paths, not map
iteration order or object identity. It must audit all current continuation types and fail closed if a
new continuation stores a volatile decision identity outside the registered paths.

The current PlayerYields model uses AbilityIdentity and does not currently contain a decision nonce.
Those ability identities remain ordinary semantic state. If a future yield field references a
decision, it must join the same alias table before v3 accepts that field.

### 6.2 Fields that remain semantic

The canonicalizer does not normalize:

* Decision kind or polymorphic type.
* Acting/player identity.
* Source, subject, triggering entity, targets, legal candidates, or options.
* Semantic decision context, mode shape, min/max, ordering flags, payment requirements, and
  structured decision constraints. prompt, effectHint, and other presentation labels follow
  Section 5.3 and are not normalized when they remain in the canonical form.
* Entity IDs, stack IDs, component identities, card identities, or any non-decision UUID.
* The relationship between a pending decision and its continuation or yield references.

A continuation that refers to D0 must not become a continuation referring to the raw old nonce while
the PendingDecision is normalized to D0. The alias relationship is part of the fingerprint.

### 6.3 Required tests

The replay test suite includes at least these cases:

1. Two otherwise identical states with decision nonce abc and xyz produce the same v3 fingerprint,
   including a continuation reference to the nonce.
2. Changing decision kind, acting player, source, targets/options, min/max, mode shape, payment
   requirements, continuation semantics, or effect semantics changes the v3 fingerprint, whether
   the nonce is the same or different.
3. A state where a continuation references a different decision alias changes the fingerprint.
4. A non-decision entity or source ID that changes value changes the fingerprint.

## 7. Replay reconstruction and fidelity

ReplayReconstructor continues to fold the existing ordered action stream from ReplayEngine.initialState.
It rebinds a recorded SubmitDecision response to the fresh pending decision's runtime routing nonce,
but it preserves the response payload. It never chooses a default or heuristic response in place of a
recorded player choice.

Persistent yield mutations remain out-of-band replay inputs. Reconstruction applies each
ReplayYieldEntry at its recorded afterActionCount, including action-count zero where supported, before
the next transition that can consume it.

Checkpoint evaluation selects the fingerprint algorithm from CompactReplay.version. The expected
fidelity behavior is:

* v3 EXACT: every recorded action applies, every checkpoint matches, a tail checkpoint exists at
  afterActionCount = replay.actions.size, and that tail fingerprint matches the resulting state.
  Equivalently, the verified tail action count equals the replay action count.
* v1/v2 EXACT: the existing historical rule remains unchanged; v1/v2 are not retroactively required
  to gain a tail checkpoint.
* UNVERIFIED: the replay has no historical checkpoints or no stored resume fingerprint, under the
  existing compatibility policy. A v3 replay with an absent tail checkpoint is also UNVERIFIED, with
  a specific missing-tail reason, even when its available checkpoints match.
* DIVERGED: a recorded action fails, a checkpoint differs, or the replay cannot prove the next
  frame. The first mismatch reports the first divergent checkpoint/action window.

An old v2 checkpoint is never compared with v3 merely because the current executable is new.
Only EXACT satisfies a DATA_TRUSTED replay gate; UNVERIFIED may be rendered under the existing
degraded policy but cannot be used as proof of full replay fidelity.

## 8. Gym snapshot and fork contract

GameEnvironment remains a wrapper around immutable GameState. SnapshotCodec stores the current state,
player IDs, and the actual environment stepCount. GameGymEnv.snapshot passes environment.stepCount,
not a literal zero. Restore writes the stored count back to the environment.

The contract requires:

* Snapshot restore preserves full GameState equality/fingerprint, player IDs, pending decision,
  priority, stack, zones, RNG continuation, and stepCount.
* Continuing the original and restored environments with the same suffix produces the same final
  state, observations, legal candidates, and truncation result.
* A snapshot taken at step N restores with stepCount N.
* With maxSteps = 100 and a snapshot at step 73, the restored environment truncates after 27 further
  steps, not after a fresh 100 steps.
* Fork copies the stepCount and player-ID list, shares only immutable state, clears child event
  history, and leaves the parent unchanged when the child advances.

The snapshot/fork tests use full-state comparison as the primary check and the existing observation
and legal-action projections as secondary checks.

## 9. Replay-to-observation boundary

A6 does not define a new observation or digest. For each selected reconstructable action prefix, the
test pipeline is:

~~~text
CompactReplay
  -> ReplayReconstructor
  -> reconstructed GameState before action i
  -> existing ObservationBuilder for the requested player
  -> existing legal candidates or pending decision
~~~

The bridge compares the reconstructed observation and legal decision surface with the normal live
execution at the same prefix. The full-state v3 fingerprint remains the primary replay proof.

If A4 has not merged before publication, the final classification is:

~~~text
A6_OBSERVATION_EQUIVALENCE = BASELINE_CONTRACT_ONLY
A6_A4_INTEGRATION = DEFERRED
~~~

If A4 has merged into origin/main before publication, A6 merges the current origin/main normally
without rebasing or directly merging the A4 branch, then reruns the observation and digest equality
tests. A6 reports only the observation boundary it actually verified; it does not claim that A6
implemented or independently proved A4 privacy.

## 10. Privacy boundary

The existing public and player replay/viewer payloads remain perspective-safe. A6 must not expose
opponent hands, library order, or raw unmasked GameState through ordinary replay endpoints.

An existing explicit full-state frame endpoint for scenario sharing remains a separate intentional
endpoint. A6 does not merge its payload model with ordinary public replay output and does not add a
raw CompactReplay export.

## 11. Test and evidence matrix

The implementation follows RED-before evidence for each genuine defect. Existing correct behavior is
reported as ALREADY_GREEN. Tests are added only for missing proof; no artificial failure is created.

| Requirement | Evidence |
| --- | --- |
| REPLAY-DET-01 | Two independent reconstructions of one recipe match full v3 fingerprint and semantic state |
| REPLAY-DET-02 | Live and reconstructed prefixes match at early, decision, combat, and late frames |
| REPLAY-DET-03 | Terminal winner, game-over state, full semantic state, and fingerprint match |
| REPLAY-DET-04 | Encode/decode/encode preserves all durable replay fields and version |
| REPLAY-DET-05 | Entity IDs, stack objects, tokens, commanders, and action references remain deterministic |
| REPLAY-DET-06 | Same seed/input matches RNG continuation; controlled different-seed fixture differs |
| REPLAY-DET-07 | Matching, missing, and altered checkpoint fixtures produce EXACT, UNVERIFIED, and DIVERGED as defined |
| SNAP-01 | Restore preserves full state and secondary observation/legal-action projections |
| SNAP-02 | Original and restored environments produce the same suffix result |
| FORK-01 | Parent-derived and forked environments produce the same deterministic suffix result |
| FORK-02 | Child mutation leaves parent fingerprint, observation, and legal candidates unchanged |
| DECISION-01 | A paused decision and exact recorded response reconstruct without heuristic substitution |
| DECISION-02 | At least one structured decision replays exactly where current infrastructure represents it |
| YIELD-01..03 | Yield codec, application point, and future-state effect are preserved |
| PINNED-DEFS-01..02 | Pinned definition wins over a deliberately changed fixture registry and survives persistence |
| VERSION3-SHORT-GAME | A v3 replay with fewer than 20 actions still has a matching tail checkpoint and can be EXACT |
| VERSION3-NON-MULTIPLE | A 43-action v3 replay has checkpoints at 20, 40, and 43, and action 43 is authoritative |
| VERSION-01..02 | v3 round-trip and v1/v2 historical fixtures preserve their version-specific behavior |
| VERSION-03A | ReplayCodec rejects v > CURRENT_VERSION deterministically before reconstruction |
| VERSION-03B | Store/service/API serves archived presentation for unsupported versions, or a controlled unavailable response without it |
| Nonce normalization | Positive and negative aliasing tests from Section 6.3 |
| COMMANDER-REPLAY-01..03 | Command-zone identity, replacement choice, tax/history, and commander damage where supported |
| COMBAT-REPLAY-01..02 | Pre-assignment snapshot and modern combat continuation match |
| A2_2_REPLAY | Modern assignment and resulting combat state match |
| A2_3_REPLAY | Intervening-if recheck remains before target legality during replay |
| LKI_REPLAY | Bounty/LKI result matches live execution |
| OBS-BRIDGE-01 | Reconstructed pre-action state reaches the same existing perspective observation boundary |
| Public replay privacy | Existing endpoint masking regression remains green |
| Gym horizon | Snapshot at step 73 with maxSteps 100 truncates after 27 restored steps |
| PERF_SANITY | V3 hashing occurs only at checkpoint, tail, resume, and explicit comparison boundaries |

The required full gates remain the repository task gates:

~~~text
:game-server:test
:rules-engine:test
:gym:test
:gym-server:test
:gym-trainer:test
:mtg-sdk:test
:mtg-sets:scenarioTest
:ai:test
:oracle-assay:test
npm run build
npm run test -- --run
~~~

FrozenBaseline must remain 6ff9ded1403d59ac. A changed hash stops the A6 verification; snapshots are
not reblessed.

On Windows, run the repository's just recipes first. If the direct PowerShell invocation fails with
WinError 193 because an extensionless helper is executed as a program, use the already installed
Git-Bash fallback from the A6 worktree without editing repository scripts:

~~~powershell
& 'C:\Program Files\Git\bin\bash.exe' -lc './scripts/gradle-locked :game-server:test'
~~~

The same fallback applies to the other Gradle module gates. The final report records when this
environment fallback was used and keeps the direct-just failure separate from test results.

## 12. Expected implementation boundary

Expected production changes are limited to:

* game-server replay version constants, codec version inspection, legacy/current fingerprint
  dispatch, canonical v3 fingerprinting, checkpoint verification, resume-gate selection, and the
  mandatory v3 tail checkpoint at record creation/finalization.
* game-server persistence/service handling for unsupported future versions, retaining an independently
  decodable archived presentation when available and returning a controlled unavailable response
  otherwise.
* Gym snapshot step-count capture if the current bug is confirmed by the RED test.

Expected test changes are replay reconstruction/durability/codec/fidelity tests, tail-checkpoint
fixtures, canonical fingerprint and nonce-alias tests, the pending-decision presentation-field audit,
Commander/combat/LKI regression fixtures where needed, unsupported-version service fallback tests, Gym
snapshot/fork continuation tests, the replay-to-observation bridge test, and PERF_SANITY call-boundary
coverage.

Documentation may update the replay contract if implementation details require it. The A4 contract
files, card definitions, decklists, ML/trajectory files, and unrelated feature work remain outside
the change.

Semantic commits may separate characterization, production fixes, snapshot/fork tests, observation
bridge tests, and contract documentation. The final publication is one Draft PR only after all
required gates pass. It targets chrismaghuhn/argentum-engine, never the upstream repository.

## 13. Acceptance and stop conditions

The core A6 milestone is eligible for PASS only when setup plus the ordered action stream reproduces
full state at checked prefixes and at terminal state, codec compatibility is proven, fingerprints
detect deliberate drift, snapshots and forks preserve continuation semantics, externally represented
player decisions replay exactly, and Commander/combat/LKI regressions pass.

The milestone is PARTIAL_PENDING_A4 when those core replay gates pass but A4 has not yet merged and
the observation bridge therefore has only baseline-contract evidence.

The milestone is BLOCKED if any replay recipe diverges, a recorded choice is silently replaced,
entity IDs or RNG invalidate replay, codec data is lost, snapshot restore changes continuation,
fork mutates the parent, the v3 fingerprint misses deliberate semantic drift, FrozenBaseline changes,
or the work expands into A4, A5, B2, or a second state model.

No implementation begins until this specification is reviewed and accepted. After acceptance, the
implementation plan must preserve the v3 compatibility decision above.
