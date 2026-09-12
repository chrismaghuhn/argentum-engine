# C0 sequence, reset and recurrent derived-view contract V1

## 1. Status and authority

```text
TASK=C0_03_SEQUENCE_RESET_AND_RECURRENT_DERIVED_VIEW_CONTRACT
DATE=2026-09-12
STATUS=DRAFT_SPECIFICATION_PENDING_INDEPENDENT_EXACT_SHA_REVIEW
AUDIT_BASE=bf425d40c7a22710d0b3d9a52330661b68275bd4
ORIGIN_MAIN_AT_AUDIT=bf425d40c7a22710d0b3d9a52330661b68275bd4
UPSTREAM_MAIN_AT_AUDIT=3f46367d87c88bcf156a843a9e69fd29e1693872
ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM=https://github.com/wingedsheep/argentum-engine.git
UPSTREAM_INTEGRATED=NO
WRITABLE_REPOSITORY=chrismaghuhn/argentum-engine

C0_03_IMPLEMENTATION_AUTHORIZED=YES
C0_03_SCOPE=DOCUMENTATION_ONLY
PRODUCTION_CODE_CHANGED=NO
RULES_CODE_CHANGED=NO
GYM_SEMANTICS_CHANGED=NO
TRAJECTORY_SCHEMA_CHANGED=NO
MODEL_CODE_CHANGED=NO
DATASET_GENERATION_STARTED=NO
TRAINING_STARTED=NO
C1_STARTED=NO

RECURRENT_SEQUENCE_CONTRACT_ID=argentum-ml-recurrent-sequence@v1
RECURRENT_WINDOW_VIEW_CONTRACT_ID=argentum-ml-recurrent-window-view@v1
MODEL_FACING_SAMPLE_CONTRACT_ID=argentum-ml-model-facing-decision-sample@v1
SPLIT_CONTRACT_ID=argentum-ml-dataset-split@v1
```

The audit base is the merged C0-02 commit from PR #176:

```text
C0_02_PARENT=2b12c6ac3b2c4e27f5de2781d932266b532bdd6d
C0_02_HEAD=dd77f07540cfb0ad00bd3409f380065e13a48f66
C0_02_MERGE_COMMIT=bf425d40c7a22710d0b3d9a52330661b68275bd4
```

The fetched `origin/main` equals `AUDIT_BASE`. Upstream is reference-only and was not integrated.
PR #175 and PR #176 are merged. The accepted entry state is:

```text
COMMANDER_ENVIRONMENT_V1_COMPLETE=YES
PHASE_A_FINAL_ACCEPTANCE_PASS=YES
B0_FINAL_ACCEPTANCE_PASS=YES
B1_FINAL_ACCEPTANCE_PASS=YES
B2_FINAL_ACCEPTANCE_PASS=YES
DATA_TRUSTED=YES
C0_01_FINAL_ACCEPTANCE_PASS=YES
C0_02_FINAL_ACCEPTANCE_PASS=YES
CURRENT_PHASE=C0
C0_AUTHORIZED=YES
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
SELF_PLAY_AUTHORIZED=NO
LARGE_CORPUS_GENERATION_AUTHORIZED=NO
PRE_C1_PERFORMANCE_GOOD_ENOUGH=YES
PERFORMANCE_WORK=ON_DEMAND_ONLY
```

This document freezes source-derived recurrent sequence semantics. Its specification gates are
source-backed decisions, not evidence that a recurrent model, window materializer or learner has
run. Final acceptance remains pending independent exact-SHA review.

## 2. Scope and ownership

Given an immutable accepted `TrajectoryV1`, C0-03 defines:

```text
which records belong to one recurrent policy stream
how streams are separated by acting perspective and policy instance
when state is initialized and reset
how accepted previous choices enter later inputs
how causality, privacy and masks are preserved
how full streams and finite windows relate
how exact and approximate hidden-state origins are distinguished
```

The canonical source remains:

```text
TrajectoryV1
  -> accepted source decisions in global decisionIndex order
  -> per-perspective recurrent streams
  -> C0-01 model-facing samples plus accepted previous-choice context
  -> optional derived windows
```

The contract does not implement an LSTM, GRU, Transformer, memory model, learner, training loop,
checkpoint, numeric inference, policy RNG, physical batcher, HF/Arrow view or production schema.
It does not modify Rules, Gym, replay, `TrajectoryV1`, C0-01, C0-02 or the locked curriculum.

## 3. Accepted C0-01 and C0-02 dependencies

The accepted model-facing boundary is
[`c0-model-facing-sample-and-candidate-scoring-contract-v1.md`](c0-model-facing-sample-and-candidate-scoring-contract-v1.md).
The accepted split/evaluation boundary is
[`c0-split-and-frozen-evaluation-contract-v1.md`](c0-split-and-frozen-evaluation-contract-v1.md).

C0-03 MUST preserve:

```text
model-facing feature admission
complete legal-domain semantics
candidate scoring semantics
structured decision semantics
semantic chosen-label binding
SELF / OPPONENT normalization
raw-ID exclusion rules
split membership and 80/10/10 mapping
final-test identity
frozen gameplay evaluation semantics
```

The recurrent view is a derived sequence representation. It cannot add hidden state, recover a
missing domain, choose a candidate, reinterpret an invalid response, or move a record between
partitions.

## 4. Source authority audit

The audit used the exact `AUDIT_BASE`. Source and accepted design evidence are:

| Key | Source | Relevant authority |
| --- | --- | --- |
| S1 | [`TrajectoryV1.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1.kt) | `RosterSeatV1`, `EnvironmentIdentityV1`, `PolicyProvenanceV1`, `EpisodeMetadataV1`, `DecisionRecordV1`, `TrajectoryV1`, closure, IDs and validation. |
| S2 | [`TrajectoryV1Reader.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Reader.kt) | `ValidatedTrajectoryDatasetV1`, manifest-owned streaming and duplicate collection-job rejection. |
| S3 | [`TrajectoryV1Manifest.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Manifest.kt) | Immutable dataset identity, deterministic producer order and accepted closure counts. |
| S4 | [`SemanticDecisionIdentity.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/SemanticDecisionIdentity.kt) | Semantic decision identity and replay-prefix chronology. |
| S5 | [`EpisodeClosure.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/EpisodeClosure.kt) | `GAME_TERMINAL`, `INTERRUPTED`, `FAILED` lifecycle semantics. |
| S6 | [`GameGymEnv.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt) | Current reset/fork/snapshot/restore behavior and optional History-C/D runtime state. |
| S7 | [`PerspectiveHistoryV1.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/PerspectiveHistoryV1.kt) | Separate perspective-history contract, semantic entries and local ordinals. |
| S8 | [`PerspectiveHistoryStateV1.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/history/PerspectiveHistoryStateV1.kt) | Episode-scoped pair of perspective histories. |
| S9 | [`PerspectiveAliasRegistryV1.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/history/PerspectiveAliasRegistryV1.kt) | Internal perspective-local alias registry namespace. |
| S10 | [`PerspectiveIncarnationRelationV1.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/history/PerspectiveIncarnationRelationV1.kt) | Internal incarnation relation type; current producer witness is incomplete. |
| E1 | [`c0-history-a-committed-perspective-event-source-2026-09-07.md`](c0-history-a-committed-perspective-event-source-2026-09-07.md) | Committed event-source boundary; not a durable Trajectory binding. |
| E2 | [`c0-history-b-known-information-ledger-2026-09-07.md`](c0-history-b-known-information-ledger-2026-09-07.md) | Rules-owned known-information boundary; semantic alias/Trajectory binding remains absent. |
| E3 | [`c0-perspective-history-known-information-characterization-2026-09-07.md`](c0-perspective-history-known-information-characterization-2026-09-07.md) | Full perspective history unavailable; known-information and stable-alias metadata gaps. |
| E4 | [`history-d-final-acceptance-contract-audit-2026-09-10.md`](history-d-final-acceptance-contract-audit-2026-09-10.md) | Ratified History-D definition, but no accepted Trajectory-V1 binding or final verification pass. |

### 4.1 Source field inventory

| Field or identity | Source type | Sequence role | Model feature? | Cross-step authority? | Reset impact | Reason |
| --- | --- | --- | --- | --- | --- | --- |
| `trajectoryId` | `TrajectoryV1` | Exact source episode identity. | `PROVENANCE` | `NO` | New trajectory resets. | Distinguishes exact source trajectories; never a learned feature. |
| `semanticEpisodeId` | `EpisodeMetadataV1` | C0-02 split group and environment identity. | `PROVENANCE` | `NO` | New semantic episode resets. | It is not sufficient for one recurrent stream because policy jobs/trajectories may share it. |
| `collectionJobId` | `EpisodeMetadataV1` | Collection provenance and duplicate authority. | `PROVENANCE` | `NO` | New job resets. | It is not split authority and is not a player-policy stream binding. |
| `decisionIndex` | `DecisionRecordV1` | Global source chronology. | `NO` | `NO` | No reset by value. | Retain as provenance; do not feed absolute index as a feature. |
| `replayActionIndex` / `replayFrameIndex` | `DecisionRecordV1` | Replay boundary aligned with the record. | `PROVENANCE` | `NO` | No reset by value. | Coordinates are not model history or a split unit. |
| `perspectivePlayerId` | `DecisionRecordV1` / observation | Acting perspective and stream selector. | `RELATIONAL` | `PARTIAL` | Player-policy boundary resets. | Current C0-01 maps it to SELF/OPPONENT for input; raw ID remains routing/provenance. |
| `decisionKind` | `DecisionRecordV1` | Current decision family. | `YES` through C0-01 | `NO` | No reset by family. | A family change is a stream step, not a state boundary. |
| `observationBefore` | `DecisionRecordV1` | Current perspective-safe policy input. | `YES` through C0-01 | `NO` | No reset by observation value. | It is the current boundary view; no raw state or future record is reconstructed. |
| `completeLegalDomain` | `DecisionRecordV1` | Complete current legal choice domain. | `YES` through C0-01 | `NO` | No reset by domain kind. | Full domain remains lossless and is never replaced by a fixed action vocabulary. |
| `chosenSemanticAction` | `DecisionRecordV1` | Accepted prior action label after its boundary. | `YES` only as shifted previous choice | `PARTIAL` | No reset by action kind. | Current-step label is excluded; prior references remain source-relative. |
| `chosenSemanticResponse` | `DecisionRecordV1` | Accepted prior structured response label. | `YES` only as shifted previous choice | `PARTIAL` | No reset by response kind. | Full typed semantics remain available; no arbitrary row-index reduction. |
| `EnvironmentIdentityV1` | Episode metadata | Environment instance binding. | `PROVENANCE` | `NO` | New environment instance resets. | Binds engine/card/deck/roster/start/seed inputs. |
| `RosterSeatV1` | Environment identity | Seat/player/role/deck mapping. | `RELATIONAL` | `PARTIAL` | Player mapping change resets. | Source mapping is exact; seat magnitude is not a feature. |
| `PolicyProvenanceV1` | Episode metadata | Collection policy identities, roles, RNG and source. | `PROVENANCE` | `NO` | Policy-instance change resets. | Current source is episode-level; unbound mixed-policy streams fail closed. |
| policy RNG identity/seed | Policy provenance | Policy-side randomness provenance. | `PROVENANCE` | `NO` | RNG/checkpoint change resets/recomputes. | C0-04 owns numeric RNG semantics. |
| `EpisodeClosureV1` | Episode metadata | Terminal/interrupted/failed stream eligibility/end. | `NO` | `NO` | Closure ends/discards stream. | Closure is not current policy input or fabricated value target. |
| `PerspectiveHistoryV1` | Separate Gym contract | Optional perspective event/history sidecar. | `NO` in C0-03 | `PARTIAL` | History lifecycle reset. | Not durably bound to `TrajectoryV1`; not recurrent source authority here. |
| History-C aliases/incarnation relations | Internal History-C | Perspective-local reference evidence. | `NO` | `PARTIAL` | Alias namespace reset. | Current alias/incarnation witness is incomplete and not Trajectory-bound. |
| known-information ledger | Rules/History-B | Perspective knowledge facts/epochs. | `NO` | `NO` | Ledger lifecycle reset. | Current ledger is not an event-time Trajectory history contract. |
| `objectIdentityStamps` | Rules internal | CR 400.7 incarnation witness. | `NO` | `NO` | Environment state lifecycle. | Internal state, not a model-facing cross-step alias. |
| raw `EntityId` | SDK/runtime | Current object address or routing identity. | `NO` | `NO` | No semantic reset inference. | No owner/zone/incarnation/visibility semantics; never a persistent feature key. |

The source establishes the following identity chain:

```text
EnvironmentIdentityV1
  -> identityDigest()
  -> semanticEpisodeId

semanticEpisodeId + PolicyProvenanceV1
  -> collectionJobId

semanticEpisodeId + collectionJobId + decisions + closure
  -> trajectoryId
```

Thus:

```text
SPLIT_GROUP_IDENTITY != RECURRENT_STREAM_IDENTITY
```

## 5. Recurrent stream identity and policy ownership

The documentation-level stream identity is:

```text
RecurrentStreamIdentityV1 =
  RECURRENT_SEQUENCE_CONTRACT_ID
  + trajectoryId
  + perspectivePlayerId
  + resolved policy-instance binding
```

The stream key is exact within the source trajectory. `streamStepIndex` is a derived physical
ordinal and never replaces `decisionIndex`.

The resolved policy-instance binding consists of the exact policy identity, policy role/source
identity and declared policy-RNG binding available from the accepted provenance. If those fields do
not identify the owner of the perspective without guessing, the stream is rejected as
`POLICY_INSTANCE_PROVENANCE_GAP`.

For the current accepted B2/A9 source, both `behaviorPolicyIdentity` and `opponentPolicyIdentity`
are the same deterministic external policy identity and both policy roles are the same external
controller role. Therefore the current two perspective streams are unambiguous as separate runtime
instances even though they use the same policy weights.

```text
POLICY_INSTANCE_MAPPING=
  UNAMBIGUOUS_FOR_CURRENT_EQUAL_POLICY_SOURCE;
  MIXED_POLICY_BINDING_REQUIRED_AND_FAILS_CLOSED_IF_UNBOUND
```

For a future trajectory with distinct policies, a stream is accepted only when the source
provenance explicitly maps the acting perspective/role to one policy identity. The adapter must not
infer ownership from seat 0, player name or deck name. Current episode-level provenance does not
authorize an unbound mid-episode policy swap:

```text
MID_EPISODE_POLICY_SWAP=OUT_OF_CONTRACT
```

The same checkpoint may control both players, but state ownership remains separate:

```text
SAME_WEIGHTS_FOR_BOTH_PLAYERS=ALLOWED
SHARED_HIDDEN_STATE_BETWEEN_PLAYERS=FORBIDDEN
RECURRENT_STATE_SCOPE=one episode x one acting perspective / policy instance
NO_CROSS_PLAYER_RECURRENT_STATE=YES
```

## 6. Per-perspective stream derivation

For one accepted trajectory, filter records by `perspectivePlayerId` and retain ascending source
`decisionIndex`. For example:

```text
global source records:
  d0 P1
  d1 P1
  d2 P2
  d3 P1
  d4 P2

P1 stream:
  d0, d1, d3

P2 stream:
  d2, d4
```

Each derived record retains:

```text
source decisionIndex
replayActionIndex / replayFrameIndex
semanticDecisionId
perspectivePlayerId
```

The derived `streamStepIndex` may be `0, 1, 2, ...` within one stream, but is:

```text
STREAM_STEP_INDEX=DERIVED_ONLY
SOURCE_DECISION_INDEX_AS_MODEL_FEATURE=NO
```

The existing C0-01 `turnNumber`, `phase` and `step` provide public game-time context when admitted.
The stream index is structural/provenance, not an arbitrary learned magnitude.

## 7. Opponent decisions and privacy

One global recurrent stream for both perspectives is forbidden:

```text
P1 private/public observation
  -> shared hidden state
P2 private/public observation
  -> same hidden state
P1 later decision
```

That path can carry P2-private information into P1's memory. The permitted tracks are:

```text
env0 / P1 -> state_env0_P1
env0 / P2 -> state_env0_P2
env1 / P1 -> state_env1_P1
env1 / P2 -> state_env1_P2
```

If P1 does not act while P2 makes decisions, P1's state is not advanced with P2's observation or
full semantic response. At P1's next decision, P1 consumes its new C0-01 perspective-safe
observation. Public consequences are included only when the source projection exposes them.

```text
OPPONENT_PRIVATE_DECISION_STREAM_INJECTION=NO
PREVIOUS_GLOBAL_CHOICE_AS_P1_FEATURE=NO
```

This is a decision-boundary-based stream, not a claim that every intervening public event is
available. A richer public event/history input requires a separately accepted and Trajectory-bound
contract; it may not be obtained from `GameState`, replay tails or another player's record.

## 8. Canonical recurrent reference semantics

The canonical policy-instance stream is evaluated as follows:

```text
h_0 = INITIAL_STATE

for each accepted record in ascending source decisionIndex:

    input_t = current C0-01 sample
              + accepted previous-choice context for this stream

    output_t = candidate scores or typed response

    acceptedChoice_t = only the semantic choice accepted by the environment

    h_(t+1) = recurrent transition from h_t,
              current input_t and acceptedChoice_t
```

The exact neural equation, tensor shape and numeric initial value are deferred. The semantic
meaning is frozen:

```text
INITIAL_STATE = no earlier accepted history for this policy instance
REFERENCE_RECURRENT_SEMANTICS=EXACT_EPISODE_PREFIX
FULL_STREAM_IS_SEQUENCE_AUTHORITY=YES
```

`EXACT_EPISODE_PREFIX` means that the state at stream step `t` is the result of the initial state
plus the entire earlier accepted same-policy stream. It does not mean that the source trajectory
stores a hidden tensor.

## 9. Initialization and reset boundaries

Initialize one independent state at the beginning of each exact stream. Reset/discard state when
any of these changes:

```text
episode
trajectory
environment instance episode
perspective / player policy instance
independent evaluation job
checkpoint identity
```

```text
EPISODE_BOUNDARY_RESET=YES
TRAJECTORY_BOUNDARY_RESET=YES
ENVIRONMENT_BOUNDARY_RESET=YES
PLAYER_BOUNDARY_RESET=YES
INDEPENDENT_POLICY_INSTANCE_RESET=YES
CHECKPOINT_CHANGE_REQUIRES_STATE_RESET_OR_RECOMPUTE=YES
CROSS_TRAJECTORY_STATE_CARRY=NO
```

Do not initialize or restore state from a previous episode, other player, other environment,
previous batch occupant, wall-clock cache or independent job. The same checkpoint identity on both
roles still produces two independent states:

```text
checkpoint X controls Akiri -> hiddenState_Akiri
checkpoint X controls Chevill -> hiddenState_Chevill
```

No reset occurs merely because a turn, phase, decision kind or structured continuation changes:

```text
TURN_BOUNDARY_RESET=NO
PHASE_BOUNDARY_RESET=NO
DECISION_KIND_CHANGE_RESET=NO
```

A physical batch slot is never a recurrent-state owner. Reusing a slot for a different stream,
episode, player or checkpoint without an explicit stream-state binding is leakage:

```text
BATCH_SLOT_RECURRENT_LEAKAGE=FORBIDDEN
```

State follows `RecurrentStreamIdentityV1`, not tensor-slot number, worker ID or processing order.

Root actions, target selections, payments, ordering and follow-up structured responses remain
separate recurrent stream steps. Consecutive same-player decisions do not create an implicit reset.

## 10. Closure, death and environment lifecycle

For accepted source closures:

```text
GAME_TERMINAL
  -> sequence ends
  -> state is discarded

INTERRUPTED
  -> valid factual prefix may end
  -> state is discarded
  -> no continuation into another episode

FAILED
  -> not admitted to a normal learner stream
```

When a player has no further source decisions, that perspective stream ends naturally. No terminal
recurrent input is fabricated. Factual closure remains provenance/target authority and is not fed
as current policy input.

Environment snapshot/restore is not policy-state restore:

```text
ENVIRONMENT_RESTORE != POLICY_STATE_RESTORE
```

A future evaluator/planner that restores an environment must restore the exact corresponding policy
state or recompute it from authoritative sequence history. A `GameEnvironment.fork()` or candidate
simulation is not an accepted recurrent source; speculative state/history must not enter a trusted
learner sequence.

## 11. Previous accepted semantic choice

For stream step `t`, define:

```text
previousChoice_t = the immediately preceding accepted semantic action/response
                   made by the same recurrent policy instance
                   within the same trajectory
```

This is not the globally previous decision and never the opponent's private choice:

```text
PREVIOUS_CHOICE_SCOPE=SAME_POLICY_INSTANCE
PREVIOUS_CHOICE_SOURCE=ACCEPTED_SEMANTIC_CHOICE_ONLY
```

The first step has an explicit structural beginning value:

```text
previousChoice = BEGIN_SEQUENCE
BEGIN_SEQUENCE_IS_STRUCTURAL=YES
```

`BEGIN_SEQUENCE` is not an ambiguous missing-data token. A zero-decision perspective stream yields
no recurrent training sample. A one-decision stream yields one real sample with
`BEGIN_SEQUENCE` and no preceding choice.

At training step `t`, teacher-forced previous-choice input may use the accepted source choice from
step `t-1`. It may not use the current label:

```text
TEACHER_FORCING_IS_SHIFTED_BY_ONE_STEP=YES
CURRENT_TARGET_AS_CURRENT_INPUT=NO
```

At real inference, the previous choice is the policy's own previously executed and engine-accepted
semantic choice. An invalid attempted output is rejected/fails under the evaluation policy and never
becomes previous accepted context. The difference between recorded teacher-forced history and
autoregressive model-generated history is a later learning-method concern, not permission to change
the environment contract.

## 12. Previous-choice projection

Raw `ChosenSemanticActionV1` or `ChosenSemanticResponseV1` JSON is not passed directly to a model
merely because it is serializable. A future derived view uses a conceptual:

```text
PreviousSemanticChoiceViewV1
```

derived from:

```text
previous C0-01 model-facing sample/domain
+ previous accepted semantic chosen binding
```

The projection obeys all C0-01 feature-admission and privacy rules. It preserves the semantic family,
action/response kind and complete typed meaning needed for a later exact source binding. It does not
turn the choice into a global integer action token, candidate batch index or presentation string.

Previous choices support:

```text
flat actions
folded decisions
structured responses
PaymentPlanV3
target and combat relations
ordered responses
```

Payments retain activation order, backward references, allocation and unit semantics. Structured
responses are not replaced by `some structured action happened`. If a future projection cannot
represent a choice safely, it fails closed as `PREVIOUS_CHOICE_PROJECTION_GAP` rather than inventing
a summary.

## 13. Cross-step identity and history boundary

The current repository contains separate History-A/B/C/D capabilities, but they are not automatically
bound to accepted `TrajectoryV1` records:

```text
PerspectiveHistoryV1 exists as a separate contract
History-C aliases are perspective-local runtime evidence
Rules objectIdentityStamps are internal incarnation witnesses
known-information state is not an event-time Trajectory ledger
Trajectory binding is not implemented in the accepted source
```

History-D has a ratified definition, but its final verification and Trajectory binding remain
separate dependencies. `GameGymEnv` can retain optional history state for a committed runtime path;
that does not make it part of the serialized `TrajectoryV1` model-facing source.

```text
CROSS_STEP_ENTITY_ALIAS_AUTHORITY=
  PARTIAL_INTERNAL_HISTORY_EVIDENCE;
  NOT_ESTABLISHED_FOR_TRAJECTORY_V1_LEARNER_FEATURES

HISTORY_SIDECAR_AS_RECURRENT_INPUT=NO
STABLE_CROSS_STEP_ALIAS_AS_TRAJECTORY_AUTHORITY=NO
RAW_ENTITY_ID_AS_CROSS_STEP_FEATURE=NO
OBJECT_IDENTITY_STAMP_AS_MODEL_FEATURE=NO
```

The current safe rule is to encode a previous choice relative to the previous sample/domain that
made it meaningful. A raw previous `EntityId` is not reinterpreted against a current observation:

```text
previous target EntityId=123
  != automatically current EntityId=123
```

No persistent embedding keyed by runtime ID is created. A future perspective-scoped,
incarnation-aware alias may be used only after a separately accepted source witness binds it to the
trajectory and information set. Unknown/unresolved cross-step alias semantics fail closed.

## 14. Public history and latent memory

The current stream is deliberately decision-boundary based:

```text
own accepted decision boundaries
+ new perspective-safe observation after intervening actions
```

It does not claim to contain every public event or every opponent action. Public consequences may be
present in the next observation; opponent-private observations and choices are never injected.

```text
CURRENT_RECURRENT_STREAM_IS_DECISION_BOUNDARY_BASED=YES
NO_OPPONENT_HIDDEN_HISTORY=YES
NO_REPLAY_AS_MODEL_INFORMATION_AUTHORITY=YES
```

The recurrent model may hold a latent belief inferred from legitimate input history, but it may not
promote that belief to an environment fact:

```text
LATENT_BELIEF_ALLOWED=YES
LATENT_BELIEF_AS_OBSERVATION_AUTHORITY=NO
```

## 15. Causal sequence and future leakage

For every step `t`:

```text
input_t may depend only on source information available at or before t
```

Forbidden dependencies include:

```text
future observation
future chosen action/response
future episode closure
future sequence length
future padding pattern
future reward/value
current-step target
opponent-private observation or choice
```

```text
FUTURE_LEAKAGE=FORBIDDEN
CAUSAL_ATTENTION_REQUIRED=YES_IF_A_FUTURE_ATTENTION_ARCHITECTURE_IS_USED
```

Physical tensors may contain later rows for backpropagation, but the computation at `t` may not
observe later rows, labels, validity masks or termination. C0-03 does not select Transformer or any
other architecture.

## 16. Canonical full stream and window derivation

The semantic sequence is first defined as:

```text
FULL_POLICY_STREAM = all accepted same-perspective decision samples
                     from one trajectory
                     in increasing source decisionIndex
```

Every full stream inherits the C0-02 partition of its source trajectory:

```text
FULL_STREAM_IS_SEQUENCE_AUTHORITY=YES
WINDOW_IS_DERIVED_VIEW=YES
SEQUENCE_MAY_NOT_CROSS_SPLIT_BOUNDARY=YES
WINDOW_CROSS_SPLIT=FORBIDDEN
WINDOW_CROSS_EPISODE=FORBIDDEN
WINDOW_CROSS_PLAYER=FORBIDDEN
BOTH_PERSPECTIVE_STREAMS_SAME_SPLIT=YES
```

Even when two episodes use the same policy, deck, checkpoint or numeric seed, they never form one
stream or one window. If one episode yields P1 and P2 streams, both inherit the same partition:

```text
P1 stream -> TRAIN
P2 stream -> TRAIN
```

is valid, while assigning those streams to different partitions is forbidden.

Window count is derived view multiplicity, not new data:

```text
WINDOW_COUNT != SOURCE_EPISODE_COUNT
```

Full-stream training remains allowed when memory and runtime permit. A finite window is an
optimization/view and cannot redefine source chronology or source membership.

## 17. Window configuration and identity

The conceptual parameterized window contract is:

```text
RecurrentWindowConfigV1
  stream identity
  windowStartStreamIndex
  burnInLength
  learningLength
  padding policy
  hidden-state origin
```

The semantic fields are frozen; no model-specific numeric window length is selected here. If a
persistent window cache is later used, its identity binds:

```text
source DatasetManifest identity
split contract identity
C0-01 model-facing contract identity
C0-03 sequence contract identity
trajectory identity
stream identity
window config identity
window start
burn-in length
learning length
content digest
```

Changing any semantic member creates a new derived-window identity. Physical batch size, GPU,
framework, tensor layout and minibatch shuffle do not change the semantic sequence contract.

For the same source dataset, sequence contract and window configuration, deterministic materializing
must produce the same window membership and internal chronological order. A stochastic window
sampler may use an RNG as training-runtime provenance, but sampling order cannot redefine windows or
source sequences.

## 18. Burn-in and learning segment

```text
BURN_IN = valid prior steps used to reconstruct/update recurrent state
           but excluded from optimization loss

LEARNING_SEGMENT = valid chronological steps whose training losses are included
```

Burn-in is real accepted sequence input. It is not padding and not discarded history. Every learning
step retains the complete C0-01 sample, complete legal domain and semantic chosen label. Windowing
never truncates the domain or changes label semantics.

Required conceptual masks are:

```text
sequencePresenceMask
burnInMask
learningLossMask
resetMask
```

If truncated burn-in is used for training efficiency:

```text
TRUNCATED_BURN_IN_ALLOWED_AS_APPROXIMATION=YES
```

Its burn-in length, window strategy and state-origin mode are training provenance. It is explicitly
not equivalent to full-history state:

```text
REFERENCE_RECURRENT_SEMANTICS=EXACT_EPISODE_PREFIX
EVALUATION_TRUNCATED_BURN_IN=NO
```

Frozen offline/gameplay evaluation starts at episode start, initializes state, streams chronologically
and does not perform an artificial mid-episode reset. A future separate evaluation contract would be
required to change that.

## 19. Padding and mask semantics

Padding is physical only:

```text
PADDING_IS_GAMEPLAY_CONTENT=NO
FUTURE_PADDING_PATTERN_AS_MODEL_FEATURE=NO
```

Padding may not update hidden state as a real choice, become previous choice, contribute loss, create
entities/candidates/relations or change chronology. A padding position has:

```text
sequencePresenceMask=false
burnInMask=false
learningLossMask=false
```

`burnInMask=true` means a real historical step used to update state with loss excluded. It does not
mean padding or future context. `learningLossMask=true` means a real accepted learning-segment step
only. Invalid, quarantined, other-player, padding and burn-in-only steps have loss mask false.

`resetMask` marks the first real step of a semantic stream. A physical window start is not a semantic
reset unless the window explicitly declares `TRUNCATED_BURN_IN_APPROXIMATION` and its state origin
resets before the bounded context:

```text
SEMANTIC_RESET != PHYSICAL_WINDOW_START
```

Optional `previousChoicePresence` is structural. All masks are controls, not gameplay features.

## 20. Hidden-state origin modes

| Mode | Meaning | Status |
| --- | --- | --- |
| `EXACT_EPISODE_PREFIX` | Start at `INITIAL_STATE` and replay the entire prior same-policy stream before the window position. | Canonical reference and evaluation mode. |
| `TRUNCATED_BURN_IN_APPROXIMATION` | Reset before a bounded prior context and process only that context. | Training approximation only; explicitly identified and measured. |
| `MODEL_DERIVED_CACHED_STATE` | Reuse a state derived from an exact checkpoint and source prefix. | Future optimization; never TrajectoryV1 authority. |

For `MODEL_DERIVED_CACHED_STATE`, provenance must bind source trajectory, stream, stream position,
checkpoint, architecture, sequence contract, numeric/runtime contract where needed and state content
digest. Checkpoint A's state is invalid for checkpoint B.

Hidden state is always derived model state:

```text
RECURRENT_HIDDEN_STATE != ENVIRONMENT_STATE
RECURRENT_HIDDEN_STATE != TRAJECTORY_SOURCE_DATA
RECURRENT_HIDDEN_STATE_IS_SOURCE_AUTHORITY=NO
CANONICAL_TRAJECTORY_MODEL_STATE=NO
```

No `hiddenState`, LSTM state, Transformer cache or burn-in tensor is added to canonical
`TrajectoryV1`. A derived cache can be discarded and regenerated.

## 21. Training, inference and evaluation semantics

The feed-forward/recurrent comparison is causal only when every other authority is held fixed:

```text
same DatasetManifest
same C0-02 split and final-test identity
same C0-01 input/domain semantics
same frozen gameplay evaluation jobs

feed-forward candidate scorer
vs.
recurrent candidate scorer
```

Recurrence is not presumed superior. It is retained only if measured benefit justifies its added
complexity. C0-03 does not change dataset membership, candidate/domain semantics or evaluation jobs.

Training with recorded previous choices is teacher-forced history. C0-03 freezes three distinct
operating modes rather than using the ambiguous phrase `real inference` for both offline and
gameplay evaluation:

```text
TRAINING
  previous choice source = recorded accepted source choice

OFFLINE_HELD_OUT_BEHAVIOR_EVALUATION
  previous choice source = recorded accepted source choice

GAMEPLAY_EVALUATION
  previous choice source = model-executed and engine-accepted choice
```

The common previous-choice semantics remain shifted by one step:

```text
TRAIN_PREVIOUS_CHOICE_SEMANTICS
= RECORDED_ACCEPTED_SOURCE_CHOICE
  for training and offline held-out behavior evaluation
```

At offline held-out step `t`, the evaluator scores the model against the source record. Regardless
of whether the model agrees, it advances the recurrent previous-choice context with the recorded
accepted source choice before consuming source step `t+1`. The source observation at `t+1` therefore
remains causally consistent with the source action/response that produced it.

```text
OFFLINE_RECURRENT_EVALUATION_MODE=SOURCE_TEACHER_FORCED
OFFLINE_HELD_OUT_PREVIOUS_CHOICE_SOURCE=RECORDED_ACCEPTED_SOURCE_CHOICE
OFFLINE_MODEL_DISAGREEMENT_CHANGES_NEXT_HISTORY=NO
COUNTERFACTUAL_MODEL_CHOICE_PLUS_SOURCE_FUTURE=FORBIDDEN_AS_CANONICAL_OFFLINE_EVAL
```

Offline behavior evaluation must not splice a model choice into the recorded future. If the source
chose `A` at `t` and the model scores/selects `B`, the evaluator reports the disagreement and then
uses source `A` as the previous choice before source step `t+1`.

Gameplay evaluation is different because the engine actually advances from the model's accepted
choice:

```text
GAMEPLAY_PREVIOUS_CHOICE_SOURCE=MODEL_EXECUTED_AND_ENGINE_ACCEPTED_CHOICE
```

There is no current-step teacher forcing. An attempted invalid model output is not accepted history
and is never fed forward as a previous choice. This preserves the C0-02 distinction:

```text
BEHAVIOR_AGREEMENT != GAMEPLAY_QUALITY
```

Autoregressive model-choice history belongs to real Argentum gameplay evaluation, not to a
canonical offline held-out sequence built from factual source observations.

## 22. Multiple environments, checkpoints and policy instances

Every active stream in a parallel workload has independent runtime state. Operational routing may
use environment slots or worker handles internally, but those values are not semantic identity or
model features:

```text
env0/P1, env0/P2, env1/P1, env1/P2 -> four independent state tracks
```

Changing a policy/checkpoint identity mid-stream requires reset or exact state recomputation. A
future self-play workload may use different checkpoints on each player, but each player-policy
instance remains a separate stream. Current episode-level provenance does not support an unbound
mid-episode policy replacement.

## 23. Deterministic equivalence and paired hidden worlds

Under the later numeric contract, the same accepted model-facing input sequence and the same initial
model state must produce semantically equivalent recurrent execution input. Differences only in:

```text
runtime IDs
host/PID/worker
physical batch position
filesystem path
```

must not change that input sequence.

If two underlying worlds differ only in information unavailable to a perspective, and produce the
same sequence of C0-01 inputs plus that perspective's accepted previous choices, then the recurrent
input sequences must remain semantically identical:

```text
PAIRED_HIDDEN_WORLD_SEQUENCE_PRIVACY=PASS_BY_CONTRACT
```

There is no hidden side channel through omitted private events, sequence length, window grouping,
raw IDs, replay metadata or another player's observation. A legitimate public reveal or
perspective-private look may affect only the histories whose audiences received it, once a future
source contract binds that fact.

Deriving two perspective streams from one episode does not create two independent episodes:

```text
RECURRENT_STREAM_COUNT != INDEPENDENT_EPISODE_COUNT
```

## 24. Concrete stream derivation example

Use this synthetic accepted chronology solely to make the stream rule explicit:

```text
d0 P1 PRIORITY
d1 P1 CHOOSE_TARGETS
d2 P2 PRIORITY
d3 P1 PRIORITY
d4 P2 SELECT_CARDS
d5 P2 PRIORITY
d6 P1 ORDER_OBJECTS
```

The derived streams are:

| Stream step | P1 source record | P1 previous choice | P2 source record | P2 previous choice |
| ---: | --- | --- | --- | --- |
| `0` | `d0 PRIORITY` | `BEGIN_SEQUENCE` | `d2 PRIORITY` | `BEGIN_SEQUENCE` |
| `1` | `d1 CHOOSE_TARGETS` | accepted choice at `d0` | `d4 SELECT_CARDS` | accepted choice at `d2` |
| `2` | `d3 PRIORITY` | accepted response at `d1` | `d5 PRIORITY` | accepted response at `d4` |
| `3` | `d6 ORDER_OBJECTS` | accepted choice at `d3` | — | — |

P1's stream never consumes P2's private choice at `d2`, `d4` or `d5`. P2's stream never consumes
P1's choice. The original `decisionIndex` values and replay coordinates remain attached as
provenance; `0..3` and `0..2` are derived stream indexes only. The first P1 and first P2 steps have
separate resets, and no reset occurs at `d2`, `d4`, `d5` merely because another player acts.

## 25. Concrete window and mask example

Let one P1 full stream contain `s0, s1, s2, s3, s4, s5`, in that order. Construct a mid-episode
window with:

```text
windowStartStreamIndex=4
burnInLength=2
learningLength=2
hiddenStateOrigin=EXACT_EPISODE_PREFIX
physicalLength=5
```

The materializer reconstructs the exact state at `s2` from the entire prefix `s0, s1`, then processes
`s2, s3` as burn-in and `s4, s5` as the learning segment. The final position is padding:

| Physical position | Content | `sequencePresenceMask` | `burnInMask` | `learningLossMask` | `resetMask` |
| ---: | --- | ---: | ---: | ---: | ---: |
| 0 | `s2` real burn-in | 1 | 1 | 0 | 0 |
| 1 | `s3` real burn-in | 1 | 1 | 0 | 0 |
| 2 | `s4` real learning | 1 | 0 | 1 | 0 |
| 3 | `s5` real learning | 1 | 0 | 1 | 0 |
| 4 | padding | 0 | 0 | 0 | 0 |

The physical window start is not a semantic reset. If the same window instead declares
`TRUNCATED_BURN_IN_APPROXIMATION`, state resets before `s2`, and that approximation is recorded in
window/training provenance. Evaluation never silently switches to that mode.

The padding row does not update state, become a previous choice, contribute loss or reveal that the
full stream ended after `s5`. The trainer may use masks to pack/loss-select rows, but future padding
structure is not a policy feature.

## 26. Concrete privacy example

Forbidden:

```text
P1 observation
  -> shared recurrent state
P2 observation/private response
  -> same recurrent state
P1 later decision
```

Permitted:

```text
P1 observation -> state_P1 -> P1 later observation/decision
P2 observation -> state_P2 -> P2 later observation/decision
```

The same checkpoint weights may be used on both tracks, but the state values and previous-choice
contexts are never shared. A P2-private hand or response therefore cannot enter P1's recurrent
memory.

## 27. Concrete previous-choice example

For one prior accepted record:

```text
previous C0-01 sample/domain
  + accepted chosen semantic action or response
  -> PreviousSemanticChoiceViewV1
```

The view can retain the prior family, typed action/response semantics, ordered payment/target/
combat structure and source-relative binding required for exact meaning. It cannot use:

```text
raw EntityId as a persistent cross-step feature
current candidate batch index
live actionId
pending decision ID / routing nonce
```

The previous target's source-local identity is not silently matched to a current entity with the same
literal ID. If a cross-step alias is not proven, it remains provenance/structural binding only.

## 28. Versioning and physical-tool boundary

```text
RECURRENT_SEQUENCE_CONTRACT_ID=argentum-ml-recurrent-sequence@v1
RECURRENT_WINDOW_VIEW_CONTRACT_ID=argentum-ml-recurrent-window-view@v1
```

These are documentation-level identities. A new recurrent-sequence version is required for changes
to:

```text
stream ownership
perspective grouping
previous-choice semantics
cross-step identity/alias semantics
reset boundaries
causality
window reset meaning
burn-in meaning
padding or mask semantics
accepted source-version set
```

Changing only batch size, GPU, framework, tensor memory layout, physical storage or minibatch order
does not change semantic version when all transformations preserve this contract. No production
schema field is added here.

## 29. Architecture and model-size boundary

C0-03 supports multiple recurrent or memory architectures and does not select:

```text
LSTM
GRU
Transformer
RWKV
Mamba
attention-only
memory-token design
```

The following remain physical/model parameters for later work:

```text
hidden dimension
layer count
memory length
attention heads
dtype
```

No architecture or hidden size may change the semantic stream, reset, causality, privacy, window or
mask contract.

## 30. Failure behavior and blocker taxonomy

Future sequence derivation fails closed on:

```text
unknown source version
source episode not admitted
invalid decision chronology
perspective/player absent from roster
ambiguous policy-role mapping
unexpected policy identity change within a stream
cross-split sequence/window
cross-episode sequence/window
cross-player sequence/window
unresolved previous-choice semantic binding
unsupported previous-choice projection
unresolved cross-step identity used as a feature
future information or label leakage
ambiguous window reset semantics
checkpoint-bound cached state mismatch
```

No silent repair, first-choice selection, global-ID fallback, hidden-state reuse, truncation or
relabeling is permitted. If a future source is insufficient, classify the smallest blocker:

```text
RECURRENT_STREAM_IDENTITY_GAP
POLICY_INSTANCE_PROVENANCE_GAP
PREVIOUS_CHOICE_PROJECTION_GAP
CROSS_STEP_ENTITY_ALIAS_GAP
HISTORY_BINDING_GAP
WINDOW_STATE_ORIGIN_GAP
CAUSAL_MASK_CONTRACT_GAP
```

No blocker in this list authorizes changing engine/schema code in C0-03.

## 31. C0-03 exit gates

`PASS` means that the specification decision is resolved from the source audit. It does not mean
that the future recurrent implementation or window materializer has executed.

| Gate | Result | Evidence |
| --- | --- | --- |
| `C0_RECURRENT_SOURCE_AUTHORITY` | `PASS` | Sections 3-4; accepted TrajectoryV1 and C0-01/C0-02 remain source authority. |
| `C0_RECURRENT_STREAM_IDENTITY` | `PASS` | Section 5; trajectory + perspective + resolved policy binding. |
| `C0_PER_PERSPECTIVE_STREAM_ISOLATION` | `PASS` | Sections 6-7; one filtered chronology per perspective. |
| `C0_POLICY_INSTANCE_MAPPING` | `PASS` | Section 5; current equal-policy source is unambiguous, mixed unbound mapping fails closed. |
| `C0_RESET_CONTRACT` | `PASS` | Sections 9-10; explicit episode/player/job/checkpoint reset boundaries. |
| `C0_PREVIOUS_CHOICE_CONTRACT` | `PASS` | Sections 11-12 and 21; same-policy accepted choice, explicit BOS, shifted teacher forcing and source-teacher-forced offline mode. |
| `C0_CROSS_STEP_IDENTITY_CONTRACT` | `PASS` | Section 13; partial internal evidence is not Trajectory learner authority. |
| `C0_NO_CROSS_PLAYER_STATE` | `PASS` | Sections 7 and 26; independent state tracks and no opponent-private injection. |
| `C0_NO_CROSS_EPISODE_STATE` | `PASS` | Sections 9, 10 and 16; state discarded at episode/trajectory boundaries. |
| `C0_SPLIT_INHERITANCE` | `PASS` | Section 16; streams/windows inherit the source episode partition. |
| `C0_CAUSAL_SEQUENCE_CONTRACT` | `PASS` | Section 15; only information available at or before each step. |
| `C0_NO_FUTURE_LEAKAGE` | `PASS` | Sections 15 and 19; future labels, length and padding are excluded. |
| `C0_FULL_STREAM_REFERENCE_SEMANTICS` | `PASS` | Section 16; exact full policy stream is semantic authority. |
| `C0_WINDOW_CONTRACT` | `PASS` | Sections 17 and 25; windows are derived views with immutable identity inputs. |
| `C0_BURN_IN_CONTRACT` | `PASS` | Section 18; real prior steps, state update, loss exclusion. |
| `C0_PADDING_MASK_CONTRACT` | `PASS` | Section 19; padding is physical and fully masked. |
| `C0_HIDDEN_STATE_ORIGIN_CONTRACT` | `PASS` | Section 20; exact prefix vs explicit approximation/cache. |
| `C0_EVALUATION_STREAMING_SEMANTICS` | `PASS` | Sections 18 and 21; episode-start reset, no artificial reset and no counterfactual source-future splicing. |
| `C0_RECURRENT_PRIVACY_BOUNDARY` | `PASS` | Sections 7, 13, 14 and 23; no hidden/private side channel. |
| `C0_DETERMINISTIC_DERIVATION` | `PASS` | Sections 6, 16, 17 and 23; source order and config bind derivation. |
| `C0_UNKNOWN_VERSION_FAIL_CLOSED` | `PASS` | Section 29; unknown source/contract versions reject. |

```text
OPEN_C0_BLOCKERS=NONE_IDENTIFIED_FOR_THE_DOCUMENTATION_CONTRACT
SELF_REVIEW_P1=NONE
SELF_REVIEW_P2=NONE
C0_03_SPECIFICATION_GATES=ALL_MANDATORY_PASS
C0_03_FINAL_ACCEPTANCE_PASS=NO
INDEPENDENT_EXACT_SHA_REVIEW=PENDING
STOP_FOR_EXACT_SHA_REVIEW=YES
```

The open History-A/B/C/D metadata and Trajectory-binding limitations are explicit contract
boundaries, not hidden blockers for the current source-relative previous-choice semantics. A future
implementation must preserve the fail-closed behavior when it cannot satisfy them.

## 32. Required decision summary

```text
RECURRENT_SEQUENCE_CONTRACT_ID=argentum-ml-recurrent-sequence@v1
RECURRENT_WINDOW_VIEW_CONTRACT_ID=argentum-ml-recurrent-window-view@v1
RECURRENT_STREAM_IDENTITY=trajectoryId + perspectivePlayerId + resolved policy-instance binding
RECURRENT_STATE_SCOPE=one episode x one acting perspective / policy instance
PER_PERSPECTIVE_STREAMS=YES
POLICY_INSTANCE_MAPPING=UNAMBIGUOUS_FOR_CURRENT_EQUAL_POLICY_SOURCE; MIXED_UNBOUND_FAILS_CLOSED
SAME_WEIGHTS_FOR_BOTH_PLAYERS=ALLOWED
SHARED_HIDDEN_STATE_BETWEEN_PLAYERS=NO
NO_CROSS_PLAYER_RECURRENT_STATE=YES
BATCH_SLOT_RECURRENT_LEAKAGE=FORBIDDEN
CROSS_TRAJECTORY_STATE_CARRY=NO
EPISODE_BOUNDARY_RESET=YES
PLAYER_BOUNDARY_RESET=YES
TURN_BOUNDARY_RESET=NO
PHASE_BOUNDARY_RESET=NO
DECISION_KIND_CHANGE_RESET=NO

PREVIOUS_CHOICE_SCOPE=SAME_POLICY_INSTANCE
PREVIOUS_CHOICE_SOURCE=ACCEPTED_SEMANTIC_CHOICE_ONLY
BEGIN_SEQUENCE_IS_STRUCTURAL=YES
CURRENT_TARGET_AS_CURRENT_INPUT=NO
TEACHER_FORCING_IS_SHIFTED_BY_ONE_STEP=YES
PREVIOUS_CHOICE_GLOBAL_ACTION_VOCABULARY=NO
OFFLINE_RECURRENT_EVALUATION_MODE=SOURCE_TEACHER_FORCED
OFFLINE_HELD_OUT_PREVIOUS_CHOICE_SOURCE=RECORDED_ACCEPTED_SOURCE_CHOICE
GAMEPLAY_PREVIOUS_CHOICE_SOURCE=MODEL_EXECUTED_AND_ENGINE_ACCEPTED_CHOICE
OFFLINE_MODEL_DISAGREEMENT_CHANGES_NEXT_HISTORY=NO
COUNTERFACTUAL_MODEL_CHOICE_PLUS_SOURCE_FUTURE=FORBIDDEN_AS_CANONICAL_OFFLINE_EVAL

CROSS_STEP_ENTITY_ALIAS_AUTHORITY=PARTIAL_INTERNAL_HISTORY_EVIDENCE; NOT_ESTABLISHED_FOR_TRAJECTORY_V1
HISTORY_SIDECAR_AS_RECURRENT_INPUT=NO
FULL_STREAM_IS_SEQUENCE_AUTHORITY=YES
WINDOW_IS_DERIVED_VIEW=YES
REFERENCE_RECURRENT_SEMANTICS=EXACT_EPISODE_PREFIX
TRUNCATED_BURN_IN_ALLOWED_AS_APPROXIMATION=YES
EVALUATION_TRUNCATED_BURN_IN=NO
PADDING_IS_GAMEPLAY_CONTENT=NO
FUTURE_PADDING_PATTERN_AS_MODEL_FEATURE=NO
SEQUENCE_MAY_NOT_CROSS_SPLIT_BOUNDARY=YES
WINDOW_CROSS_EPISODE=FORBIDDEN
WINDOW_CROSS_PLAYER=FORBIDDEN
BOTH_PERSPECTIVE_STREAMS_SAME_SPLIT=YES
RAW_ENTITY_ID_AS_CROSS_STEP_FEATURE=NO
RECURRENT_HIDDEN_STATE_IS_SOURCE_AUTHORITY=NO
CANONICAL_TRAJECTORY_MODEL_STATE=NO
```

## 33. Deferred work

```text
DEFERRED_TO_C0_04=
  checkpoint identity, deterministic numeric inference,
  tie-breaking, policy RNG and physical hidden-state shape

DEFERRED_TO_C0_05=
  Teacher/bootstrap identity and quality, teacher-forcing experiment policy,
  value target and RL reward

DEFERRED_TO_ISSUE_137=
  PyTorch/JAX, HF Datasets/Arrow, Safetensors, Trackio, Accelerate
  and physical sequence batching
```

History-A/B/C/D future event projection, known-information epochs, stable cross-step aliases and
durable Trajectory binding remain dependencies only if a later task wants a richer public-history
input. C0-03 does not attach those sidecars, alter `TrajectoryV1`, implement recurrence, generate
data or start C0-04/C1.

## 34. Verification and stop condition

This is a documentation-only task:

```text
FULL_GYM_TEST=NOT_REQUIRED
FULL_RULES_TEST=NOT_REQUIRED
```

The required local gate is `git diff --check`. It must be reported separately from hosted CI,
independent review and final acceptance. `NOT_RUN` must not be promoted to `PASS`.

The requested integration state is:

```text
one documentation commit
Draft PR against chrismaghuhn/argentum-engine:main
PR state remains DRAFT
no merge
no Ready-for-review transition
no C0-04/C1/training start
```

The unrelated `StackResolver.kt` change in the original checkout must not be staged, reset, stashed,
cleaned, edited, reformatted or included in this branch.
