# B2 — Trajectory V1 Identity, Reuse Audit, and Implementation Plan

Status: audit, design, and implementation plan only. This document does not implement Trajectory
V1, does not start ML training, and does not change Rules, card definitions, locked decks,
observation semantics, decision semantics, or replay semantics.

The target is the accepted Argentum Environment V1 boundary described by
[Issue #100](https://github.com/chrismaghuhn/argentum-engine/issues/100):

~~~text
PlayerObservation
+ complete currently legal candidate/domain
+ chosen semantic action/response
~~~

## Source and decision boundary

~~~text
BASE_ORIGIN_MAIN=57319425e64ffeaf27e4621b4d4e890edf4e5427
UPSTREAM_MAIN=66ed8e0e7781756415c25928679cc6f17c2ebd08
AUDIT_SOURCE_HEAD=57319425e64ffeaf27e4621b4d4e890edf4e5427
ORIGIN_URL=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM_URL=https://github.com/wingedsheep/argentum-engine.git

B0_FINAL_PASS=YES (accepted prerequisite supplied by the task)
B1_FINAL_PASS=YES (accepted prerequisite supplied by the task)
DATA_TRUSTED=NO
B2_IMPLEMENTATION_AUTHORIZED=NO
FINAL_HEAD=reported by delivery evidence; not embedded self-referentially
~~~

Origin and upstream were fetched before the audit. The existing checkout at
C:\argentum-engine was left untouched. The audit was performed in a clean dedicated worktree
created from origin/main at the source head above.

The B1 evidence used for storage and lifecycle decisions is the accepted repository evidence in
docs/ml/b1-scaling-final-measurement.md and the supporting B1 audit/replay reports. Its relevant
measurements are not silently re-run or relabeled here: the accepted scaling workload covered
1/2/4/8 environments, 16,000 external transitions, 211,318 public legal candidates, and 47
structured-decision observations. The measured external throughput was 204.772, 319.020, 493.952,
and 742.022 transitions per second for 1, 2, 4, and 8 environments. The current residual hotspot
is the semantic JSON/tree/UTF-8/digest pipeline, with roughly 5,116,869 allocated bytes per
external transition in the current profiler boundary. This supports a staged, streamable writer
that does not add synchronous database or per-transition replay serialization work to the hot
step path.

## Audit scope and evidence map

The following live source surfaces were inspected at AUDIT_SOURCE_HEAD:

| Surface | Current authority found |
| --- | --- |
| Public observation | gym/.../TrainingObservation.kt, ObservationBuilder.kt, ObservationCanonicalizer.kt, StateDigest.kt, SchemaHash.kt |
| Public action/domain | LegalActionView, ActionRegistry, ActionPayloadRequirements, ActionTargetDomainV1, AttackDeclarationDomainV2, BlockerDeclarationDomainV1, PaymentDomainV5, TargetPaymentDomainV1, and StructuredDecisionDomain |
| Engine decisions | rules-engine/.../PendingDecision.kt, DecisionValidators.kt, SubmitDecisionHandler.kt, and GameAction.kt |
| Gym lifecycle | GameEnvironment.kt, GameGymEnv.kt, GymEnv.kt, MultiEnvService.kt, SnapshotCodec.kt, and EpisodeDiagnostics.kt |
| Server lifecycle | GameSession.kt, ReplayCheckpointFlusher.kt, ReplayStore.kt, and ReplayService.kt |
| Replay | CompactReplay.kt, ReplayCodec.kt, ReplayFingerprint.kt, TransitionSemanticGameStateCanonicalizer.kt, ReplayReconstructor.kt, and replay tests |
| Existing training data | ai/.../DecisionTrainingRecord.kt, DecisionRecordFactory.kt, TrainingCorpusValidator.kt, TrainingCorpusFiles.kt, and docs/ai/training-data.md |
| Existing trainer sink | gym-trainer/.../SelfPlaySink.kt, JsonlSelfPlaySink.kt, SelfPlayLoop.kt, and TrainerContext.kt |
| B0 closure evidence | gym/.../EnvironmentV1ExactPairAcceptanceTest.kt, EnvironmentV1ExternalPolicy.kt, and the accepted B0 prerequisite supplied by the task |
| B1 evidence | docs/ml/b1-scaling-final-measurement.md, b1-deep-performance-audit.md, and the B1 replay/canonicalization reports |

No current source type satisfies all of the following at once: a perspective-safe observation,
complete domain, chosen semantic response, durable semantic decision identity, typed closure,
replay linkage, and transactional shard publication. B2 therefore needs a small number of generic
projections and validators, not a second environment.

## Module boundary decision

The later implementation keeps one environment and one public-contract authority:

- Gym remains the owner of TrainingObservation, public domain DTOs, transport-free semantic
  projection, and the small VerifiedReplayFrame output contract.
- gym-trainer remains the owner of the canonical Trajectory V1 contracts, per-episode writer,
  dataset publisher/manifest, reader, and quarantine because that module already owns training-data
  format choices.
- game-server remains the owner of CompactReplay and ReplayReconstructor, and adds one adapter
  implementation of the neutral VerifiedReplayFrameSource and ReplayVerificationBindingSource
  contracts in :gym. The game-server main source takes a narrow implementation dependency on :gym;
  it does not depend on :gym-trainer, and the replay adapter does not emit GameState.
- :gym owns VerifiedReplayFrame, the unchanged VerifiedReplayVerification V1 evidence contract,
  ReplayContentIdentityV1, ReplayVerificationBindingV1, and their neutral source contracts. The
  source instance is bound to one CompactReplay by the composition root and exposes only verified
  public frames, content identity metadata, and proof evidence.
- A future B2 generation/acceptance harness is the only composition point that knows the concrete
  game-server source and the gym-trainer writer/publisher. For each CompactReplay it constructs a
  bound GymReplayFrameSource, obtains the neutral replay-verification binding, passes it to
  TrajectoryV1Writer.finishEpisode, and appends the finalized episode to the
  multi-episode publisher. This integration-only harness does not add a production dependency in
  either direction.

This is a dependency seam, not a second control path. The existing strict Gym transition remains
the only capture path, and the existing server replay fold remains the only replay authority. The
writer never imports or calls ReplayReconstructor directly; the harness-supplied source result is
the explicit replay-verification integration seam.

## IDENTITY_ROLE_AUDIT=

The audit uses four roles:

- SEMANTIC: changes the pinned environment, public information set, legal domain, semantic
  action, or replayed game history.
- FRESHNESS_ROUTING: only locates or protects a current live request, registry, generation, or
  continuation.
- PROVENANCE: identifies a build, policy, run, artifact, or operational origin.
- UNSAFE_OR_MIXED: the current surface combines roles, includes internal state, or has multiple
  producers with different determinism guarantees. It requires an explicit projection before use.

Deterministic below means deterministic across equivalent replay after the required environment
inputs are pinned. Perspective-safe means safe to expose to the acting player's information set,
not merely safe to hash.

### Public observation and domain identities

| Current type/field | Producer and consumer | Deterministic across equivalent replay | Perspective-safe | Durable trajectory use | Role and treatment |
| --- | --- | --- | --- | --- | --- |
| TrainingObservation.stateDigest | StateDigest.compute over ObservationCanonicalizer.semanticJson; consumed by MCTS, B1 traces, and clients | Yes, subject to the current canonicalizer contract | Yes | Yes as an observation binding and verification witness | SEMANTIC. Reuse as the public observation digest, but do not mistake it for a domain-only digest. |
| TrainingObservation.schemaHash | SchemaHash.CURRENT; compared by external clients | Yes for a pinned source | Yes | Yes in schema metadata; not a learner feature | PROVENANCE. Current value is argentum-gym-contract@v1.25-target-payment-domain. A durable projection also needs its own version because JsonObject action semantics can change without changing the Kotlin DTO shape. |
| TrainingObservation.perspectivePlayerId, agentToAct, turn/phase/step, public players, visible zones, stack, terminal flags, and winner | ObservationBuilder; consumed by the external policy and public clients | Yes when seed, roster, and card definitions are pinned | Yes when the builder returns with no diagnostics | Yes through a transport-free PlayerObservationV1 projection | SEMANTIC. This is the model-facing public information set. Raw GameState is never substituted. |
| LegalActionView.actionId | ActionRegistry and GameGymEnv.nextActionId; consumed by step routing | No; it is regenerated and monotonically allocated per adapter lifetime | It is not gameplay information, but it is an operational handle | No | FRESHNESS_ROUTING. Omit from durable observation, domain identity, chosen identity, and replay equivalence. |
| LegalActionView.kind, affordable, source/target/cost fields, required payload fields, and versioned nested domains | ObservationBuilder.legalActionToView; consumed by external policies and strict submission validators | Yes when the Rules producer and public ordering contract are pinned | Yes if ObservationBuilder reports no diagnostic | Yes as the complete action candidate projection | SEMANTIC. Preserve complete producer-owned order where order is meaningful. |
| LegalActionView.actionSemantics | ObservationBuilder.actionSemantic; consumed by the external policy and ObservationCanonicalizer.semanticActionFingerprint | Yes for resolved printed/granted/intrinsic ability provenance; unresolved ability provenance is rejected | Yes when built from the public observation boundary | Yes as the source of candidate and chosen action semantics | SEMANTIC after validation. It is presentation-free and removes ActivateAbility.abilityId, replacing it with the stable ability key. Unknown/unresolved semantic ability provenance is not repairable in the trajectory layer. |
| LegalActionView.description | ObservationBuilder and UI-facing policy helpers | Not a durable gameplay identity; wording may change | Public text, but not a rule contract | No for identity or digest | PROVENANCE/presentation. Exclude from V1 semantic equivalence. |
| PendingDecisionView.decisionId | PendingDecision.id copied by ObservationBuilder; consumed by GameGymEnv.submitDecision | No; many engine producers mint UUIDs and the replay path rebinds it | It is shown only to the owning perspective; still not gameplay meaning | No | FRESHNESS_ROUTING. Omit from the durable player observation and chosen response. |
| PendingDecisionView.kind, playerId, sourceEntityId, triggeringEntityId, shape, and structuredDomain | ObservationBuilder.buildPendingDecision; consumed by the external policy | Yes when the decision producer and domain version are pinned | Yes for the owner; non-owners receive an intentionally generic, action-free view | The semantic context is retained in PlayerObservationV1 and the domain portion in CompleteLegalDomainV1 | SEMANTIC. kind, playerId, sourceEntityId, triggeringEntityId, and shape are part of the current semantic observation. A policy-relevant structured decision with no structured domain is a hard failure. |
| PendingDecisionView.prompt, sourceName, effectHint | Engine decision context; consumed for display | Wording is not stable identity | Public only where the decision is exposed | No for identity or digest | PROVENANCE/presentation. Current replay and observation canonicalizers intentionally exclude these fields from semantic identity. |
| StructuredDecisionDomain.version and payload | Typed Gym projections for targets, cards, modes, distribution, ordering, piles, search, reorder, combat resolution, mana sources, replacement, and budget modal | Yes only for a supported version and a valid producer-owned order | Yes when the mapper returns no diagnostic | Yes; this is the stored domain, never only its digest | SEMANTIC. Unknown versions, duplicate members, missing required relations, and unsupported variants fail closed. |
| ActionRegistry entries | ObservationBuilder creates builder-local mappings; GameGymEnv remaps them to environment handles | No across generations; the mapping is intentionally ephemeral | Not a model surface | No | FRESHNESS_ROUTING. Retain only in memory long enough to execute the current submission. |
| LegalAction | Rules LegalActionEnumerator; consumed by Gym mapping and Rules callers | Conditional; raw action may contain runtime handles and internal choices | No; it is an engine-level object, not the public projection | No | UNSAFE_OR_MIXED. Use only as the source for a public LegalActionView; never serialize it as a trajectory candidate. |

### Engine decision, action, and continuation identities

| Current type/field | Producer and consumer | Deterministic across equivalent replay | Perspective-safe | Durable trajectory use | Role and treatment |
| --- | --- | --- | --- | --- | --- |
| PendingDecision.id | Every decision producer; DecisionResponse matching and continuation resumers | No. Some producers use UUIDs, others use stable-looking strings, but all are routing references in the current contract | Not a model feature | No | FRESHNESS_ROUTING. The explicit DecisionResponse.withDecisionId contract calls it a routing nonce and is the existing proof that replay may retarget it without changing the choice payload. |
| DecisionResponse.decisionId | Client/external policy submission; SubmitDecisionHandler | No | It is not gameplay meaning | No | FRESHNESS_ROUTING. Store only the response subtype and semantic payload after removing this field. |
| DecisionContext.abilityIdentity | Rules decision producers; persistent-yield and batch-decision logic | Definition-scoped and semantically useful, but its nested AbilityId may be a generated handle | Not a public observation field today | Only in canonical replay-yield provenance after generated handles are normalized | SEMANTIC intent with UNSAFE_OR_MIXED representation. Do not add it to learner input merely because it exists internally. |
| ContinuationFrame.decisionId, cached decision-shape IDs, and continuation references | Rules continuation stack; decision resumers; replay canonicalizer | The surrounding state is replayable, but the IDs are not | No; raw continuation state is internal | Only through replay proof | FRESHNESS_ROUTING. TransitionSemanticGameStateCanonicalizer already aliases the typed decision-reference slots; B2 must reuse that behavior and must not generalize it to arbitrary strings. |
| GameAction.playerId and semantic choice fields | External controller or server; ActionProcessor | Yes only after the action is normalized against the public candidate and pinned state | The action itself is not a public observation | Chosen action only through a transport-free semantic projection | UNSAFE_OR_MIXED. ActivateAbility.abilityId, SubmitDecision.response.decisionId, and internal resume markers make raw serialized action JSON unsuitable as a durable ML label. |
| GameAction action class discriminator | kotlinx.serialization polymorphic action serializer | Yes for a supported action carrier | Not by itself | Yes inside a versioned chosen-action projection | SEMANTIC when paired with all public choice fields. It is not sufficient without the complete candidate/domain. |
| CombatDamageEdgeDomain.id | CombatDamageManager.edgeId(sourceId, targetId) and ObservationBuilder | Yes in the current producer: it is derived from source and target IDs | Yes when the edge is in the public combat domain | Yes as a response key, after uniqueness validation | SEMANTIC for the current contract. Future code must preserve or explicitly version this relation; B2 must not assume arbitrary edge strings are stable. |
| PaymentDomainV5 sourceActivationOptions, initial buckets, cost units, and PaymentPlanV3 response | Rules-owned payment discovery, Gym mapper, strict payment validator | Yes for the pinned V5 contract and producer order | Yes when the public domain is complete | Yes; source choices and allocation are semantic | SEMANTIC. AutoPay, FromPool, legacy source-ID-only plans, and native fallback are not valid trusted policy labels. |
| AbilityId and generated forms such as ability_123 | SDK definition construction and runtime ability generation | Raw numeric suffix is allocation-order dependent | Not a public feature | No in raw form; use stable ability key/alias only | UNSAFE_OR_MIXED. Existing ObservationBuilder.stableAbilityKey and replay AbilityIdAliasTable are the reusable hardening points. |

### Gym, session, and operational identities

| Current type/field | Producer and consumer | Deterministic across equivalent replay | Perspective-safe | Durable trajectory use | Role and treatment |
| --- | --- | --- | --- | --- | --- |
| EntityId | Explicit PlayerConfig.playerId, GameState.newEntity() (e0, e1, ...), or EntityId.generate() UUIDs | Conditional. State-threaded IDs and pinned explicit seat IDs are stable; UUID-generated direct callers are not | Public entity references are safe only when the observation builder has certified addressability | Yes for public entity references within a pinned episode; no global identity claim | UNSAFE_OR_MIXED. The V1 environment identity must pin ordered seat IDs and the writer must reject a trajectory whose public entity references cannot be reproduced. |
| GameConfig.seed / InitializationResult.seed | GameInitializer; replay setup and B0/B1 workload | Yes when the actual resolved seed is stored | Not a learner feature | Yes in environment/episode provenance | SEMANTIC environment input. A null live seed is resolved once and must be recorded; wall-clock entropy is never an identity source. |
| GameEnvironment.stepCount and replay action index | Gym lifecycle and replay linkage | Yes as a position coordinate for one recorded action stream | Not a feature by itself | Yes as replayActionIndex, transition count, and closure count | SEMANTIC coordinate, not a standalone identity. It must remain aligned with the recorded external action stream. |
| GameEnvironment.projectionGeneration | GameEnvironment, EpisodeDiagnostics, GameGymEnv cache/diagnostic boundary | No across reset/restore/fork lifetimes | No | No | FRESHNESS_ROUTING. It is a mutable observation-generation guard and is excluded from all semantic hashes. |
| GameGymEnv.nextActionId | Environment-local opaque handle allocator | No across equivalent sessions | No | No | FRESHNESS_ROUTING. It intentionally survives reset/restore within the adapter but has no gameplay meaning. |
| EnvId | MultiEnvService UUID allocation and HTTP routes | No | No | Optional lookup metadata only, never trajectory identity | FRESHNESS_ROUTING. Exclude from all model and equivalence data. |
| SnapshotHandle.Slot.slotId | In-process SnapshotCodec AtomicLong slots | No | No | No | FRESHNESS_ROUTING. Snapshot state may support MCTS, but the slot handle is not durable replay identity. |
| GameSession.sessionId / CompactReplay.gameId | Server session UUID; replay store and viewer routes | No; it is a server lookup key | No | Retain only as optional operational replay linkage | PROVENANCE/routing. semanticEpisodeId, collectionJobId, and trajectoryId must not be derived from it. |
| WebSocket messageId, GameSession.lastProcessedMessageId, and server state-update version | Transport idempotency and missed-message detection | No | No | No | FRESHNESS_ROUTING. They are deliberately outside the Rules input stream. |
| GameSession.recordingRevision | Coherent replay flush cursor; changes on action/yield mutation and undo truncation | No; it is a mutable recording cursor | No | No | FRESHNESS_ROUTING/provenance. It prevents flush races but must not identify a gameplay position. |
| EpisodeDiagnostics.events and DiagnosticSignal.code | Gym observation/execution boundaries | Code is deterministic; runtime occurrence is episode evidence | Codes contain no private IDs by design | Yes in failure/quarantine metadata; no learner feature | PROVENANCE. A non-empty diagnostic is a trust failure for the affected episode, not a reason to publish a smaller domain. |
| GameEnvironmentMode.TRUSTED and strict submission path | MultiEnvService creates trusted GameGymEnv; strict path uses stepStrict | Yes as a policy boundary | N/A | Yes as provenance/assertion | PROVENANCE. B2 must use the strict external path and must never call the legacy quiet-state/native-policy path for trusted labels. |

### Replay and existing data identities

| Current type/field | Producer and consumer | Deterministic across equivalent replay | Perspective-safe | Durable trajectory use | Role and treatment |
| --- | --- | --- | --- | --- | --- |
| ReplaySetup seed, format, attack mode, hand/mulligan settings, starting player, teams, ordered seats, decks, and commanders | GameSession.startGame; ReplayReconstructor.initialState | Yes when all fields and actual seed are pinned | It includes setup, not a learner observation | Yes as environment identity/provenance | SEMANTIC. This is the primary reuse source for episode reproducibility. |
| CompactReplay.version | Replay recorder/codec/reconstructor | Yes for supported versions | N/A | Yes as replay-schema provenance | PROVENANCE with semantic consequences. Trusted B2 bootstrap accepts the known V5 carrier; future versions fail closed. |
| CompactReplay.actions | GameSession.recordAction; ReplayReconstructor | The state outcome is replayable after typed nonce rebinding; raw JSON is not fully semantic | No; it can contain internal choices | Yes only through a canonical replay-input projection | UNSAFE_OR_MIXED. Preserve the exact execution stream for replay, but derive ML identity from normalized public semantic actions. |
| CompactReplay.yields | GameSession.recordYield; ReplayReconstructor.applyYields | Yes when reapplied at the same action count and ability identity | Not a model surface | Yes in replay linkage/history digest | SEMANTIC replay input. Generated ability references require the existing alias/stable-identity treatment. |
| ReplayCheckpoint.afterActionCount and fingerprint | GameSession.stampCheckpointIfDue; ReplayReconstructor.verifyCheckpoint | Yes for the pinned replay fingerprint version | No; the fingerprint is a full transition state proof | Yes as replay-proof linkage | SEMANTIC proof, but not a public identity. Never expose the full-state fingerprint as a learner feature or use it as the candidate-domain digest. |
| ReplayFingerprint and TransitionSemanticGameStateCanonicalizer | Replay recorder/reconstructor | Yes for the selected CompactReplay version; v5 uses the existing v4 semantics | No; it canonicalizes full GameState | Yes only to establish replay fidelity | UNSAFE_OR_MIXED for ML. Reuse the proof and typed alias rules, not the full-state bytes as model input. |
| ReplayFidelity.EXACT, UNVERIFIED, DIVERGED | ReplayReconstructor and replay viewer | Yes as reconstruction result | N/A | EXACT is required for trusted replay-backed data; the others are not trusted | PROVENANCE/closure proof. ReconstructedReplay.isComplete is not sufficient because it is true for UNVERIFIED; B2 must inspect fidelity. |
| ReplayCodec gzip/base64 and version guard | Server durable replay store | Codec round-trip is deterministic for supported records; storage is not a stream of public samples | N/A | Reuse version rejection and typed replay decode; not the V1 shard encoding | PROVENANCE/storage. The existing database-oriented whole-blob format does not provide B2 shard enumeration or content manifests. |
| DecisionIdentity(runId, gameId, decisionIndex) | ai.training offline collector | Unique by contract, but runId/gameId are caller-assigned and the record lacks public-domain identity | DecisionTrainingRecord is a separate masked model | Not as V1 identity | UNSAFE_OR_MIXED. Reuse the index and manifest concepts only after replacing the identity preimage and observation/domain contract. |
| TrainingGameMetadata and TrainingCorpusValidator | ai.training ECL collector | Metadata is deterministic only when the caller pins its run IDs and action log | MaskedObservation is a different projection | Reuse selected validation/provenance ideas | HARDEN_EXISTING. It has completion strings and exceptional-game rejection, but not the typed GAME_TERMINAL / INTERRUPTED / FAILED taxonomy or replay-backed complete-domain proof. |
| TrainingRecordEncoding.actionDigest / CandidateDescriptor | ai.training collector | JSON is repeatable for a given raw action, but decision nonces and generated ability IDs can differ | Not a public observation boundary | No | UNSAFE_OR_MIXED. It is not a durable semantic action identity. |
| B1 TrajectoryAccumulator, choiceFingerprint, and reference traces | Test-only B1 measurements | Useful for comparison within a fixed test harness | Public frame traces are safe; choice formatting is not a versioned contract | No | HARDEN_EXISTING as evidence pattern only. Promote canonical bytes and complete records, not the test-local string format. |
| TrainerContext.state, StructuredDecisionResolver, DecisionResponder, and GameEnvironment.step | gym-trainer/legacy AI and MCTS | They may use raw state, quiet-state simulation, heuristics, or random policy | No | No | DO_NOT_REUSE. Trusted B2 data must be generated from TrainingObservation and complete public domains through the strict external controller. |
| DynamicSlotActionFeaturizer and JsonlSelfPlaySink | Default trainer plumbing | Slot hashes can collide; rows are back-patched and appended | The default feature path uses raw GameState context | No | DO_NOT_REUSE for canonical V1. Their module location and JSONL idea are reusable only as a reminder that B2 needs a separate, explicit contract. |

## REUSE_AUDIT=

~~~text
REUSE_AUDIT=COMPLETE
~~~

### REUSE_AS_IS

- TrainingObservation and ObservationBuilder remain the only source of player-visible game
  information.
- LegalActionView, StructuredDecisionDomain, ActionPayloadRequirements, and the current
  Rules-owned target, combat, payment, and target-payment certificates remain the only public
  domain producers.
- StateDigest remains the public observation semantic digest.
- DecisionValidators, ActionProcessor, and strict GameGymEnv submission checks remain the
  final Rules authority for an action or response.
- CompactReplay V6, ReplaySetup, ReplayCodec version rejection, ReplayFingerprint, checkpoints,
  typed nonce rebinding, and pinned-card overlay remain the replay authority.
- B1's separate environment/deck/definition identity fields are reused for semanticEpisodeId;
  policy identity and RNG fields are reused for collectionJobId. These are provenance contracts,
  not a new job scheduler.

### HARDEN_EXISTING

- Promote the current internal ObservationCanonicalizer semantic projection into a versioned,
  testable durable projection without changing existing StateDigest bytes accidentally.
- Add a domain-only canonicalization/digest operation next to the existing semantic action
  fingerprint. It must reject duplicates and unsupported versions rather than making a
  nondeterministic producer look stable.
- Add a typed episode-closure result beside the existing terminated, truncated, and
  EpisodeDiagnostics surfaces.
- Add a replay cursor/adapter around the existing ReplayReconstructor so one verified pass emits
  every decision boundary through the same public ObservationBuilder.
- Add VerifiedReplayFrameSource in :gym; keep the concrete replay adapter in game-server and pass
  its neutral verification result to the gym-trainer writer through the future integration harness.
- Reuse the atomic temporary-file and move discipline from TrainingCorpusFiles, but replace its
  whole-corpus append format with immutable bounded shards and a manifest.
- Reuse the B0 public-policy trace shape as characterization input only; do not make the policy
  depend on GameState, ActionRegistry, EpisodeDiagnostics, or native heuristics.

### SMALL_GENERIC_PRIMITIVE_REQUIRED

- EpisodeClosureV1: a typed, additive environment result distinguishing natural game terminal,
  controlled interruption, and semantic/integrity failure.
- CandidateDomainDigestV1: a canonical digest over the complete public domain, independent of
  the observation's other fields.
- ReplayContentIdentityV1 and a neutral ReplayVerificationBindingV1 that binds the logical replay
  content identity to unchanged A4 proof evidence.
- ChosenSemanticActionV1 / ChosenSemanticResponseV1 and a DTO-level membership validator.
- VerifiedReplayFrameSource: a reusable replay-to-public-observation cursor with complete-range
  verification, not a second ML environment; its harness seam must carry a full
  ReplayVerificationBindingV1 before finalization.

These are generic boundary primitives. If the closure primitive or verified replay source cannot be
accepted without changing Rules/Gym semantics, B2 stops at that follow-up and does not hide the
gap in a trajectory writer.

### DO_NOT_REUSE

- Raw GameState, raw PendingDecision, raw LegalAction, raw GameAction JSON, continuation
  stacks, or full-state replay fingerprints as model input.
- actionId, decisionId, PendingDecision.id, EnvId, snapshot slots, generation counters,
  message IDs, worker IDs, PIDs, wall time, completion order, or UUIDs as durable semantic labels.
- AutoPay, DecisionResponder, native AI, StructuredDecisionResolver, quiet-state simulations,
  DynamicSlotActionFeaturizer, or any fallback that chooses an omitted public choice.
- JsonlSelfPlaySink as the trusted writer: it buffers a game, back-patches an outcome label, uses
  caller-supplied features, and appends to one mutable file without a manifest/checksum gate.
- The old ai.training schema as a drop-in replacement: it captures quiet roots and candidate
  post-state observations, not the current complete public decision/domain boundary.

## PROPOSED_TRAJECTORY_SCHEMA=

The canonical V1 artifact is a complete episode plus its decision records. It is not a raw replay
and it is not a reward-labeled MCTS buffer.

~~~json
{
  "trajectorySchemaVersion": 1,
  "observationSchemaIdentity": {
    "wireSchemaHash": "argentum-gym-contract@v1.25-target-payment-domain",
    "durableProjection": "argentum-gym-player-observation@v1"
  },
  "actionDomainSchemaIdentity": "argentum-gym-action-domain@v1",
  "candidateDomainDigestSchemaIdentity": "argentum-gym-candidate-domain-digest@v1",
  "semanticDecisionIdentitySchema": "argentum-trajectory-semantic-decision@v1",
  "trajectoryId": "TRAJECTORY_CONTENT_SHA256",
  "semanticEpisodeId": "SEMANTIC_EPISODE_CONTENT_SHA256",
  "collectionJobId": "COLLECTION_JOB_CONTENT_SHA256",
  "episodeMetadata": {
    "environmentIdentity": {},
    "policyProvenance": {},
    "compactReplayLink": {},
    "closure": {}
  },
  "decisions": [
    {
      "decisionIndex": 0,
      "replayFrameIndex": 0,
      "replayActionIndex": 0,
      "perspectivePlayerId": "public-seat-id",
      "decisionKind": "PRIORITY",
      "semanticDecisionId": "DECISION_PREIMAGE_SHA256",
      "observationBefore": {
        "playerObservation": {},
        "observationDigest": "STATE_DIGEST_SHA256"
      },
      "completeLegalDomain": {},
      "candidateDomainDigest": "DOMAIN_PREIMAGE_SHA256",
      "chosenSemanticActionOrResponse": {}
    }
  ]
}
~~~

The on-disk event framing is described in SHARD_FORMAT= below. The JSON above describes the
semantic episode object reconstructed by the reader; the event framing avoids requiring a writer to
hold an entire long episode in memory.

### Episode metadata

environmentIdentity contains only reproducibility inputs and public contract identities:

- exact pinned engineCommit;
- the sorted locked-card definition identity and the two ordered locked deck hashes;
- format, attack mode, starting hand size, mulligan/smoothing settings, team partition, and
  starting-player index;
- ordered seat role, public seat/entity ID, commander definition identity, and deck identity;
- the actual engine seed;
- observationSchemaIdentity, actionDomainSchemaIdentity, and replay version.

The environment identity is a digest over a canonical, explicitly ordered preimage. It never uses
host, provider, PID, worker slot, wall-clock time, or completion order. A B0 schedule index may be
included only when it is an explicit semantic workload input, not because it happened to finish
first.

semanticEpisodeId is a SHA-256 content address of the canonical environment/setup/actual-engine-seed
identity and its versioned public contract identities. It contains no behavior policy, opponent policy,
policy RNG, collection-job, worker, or transport fields. It is not GameSession.sessionId or EnvId.

collectionJobId is a separate SHA-256 content address of semanticEpisodeId plus the behavior and
opponent policy identities, policy RNG/seed identities, and other collection provenance. It identifies
which policy run produced an artifact; it is not part of semantic decision identity. The same
semanticEpisodeId may therefore occur in multiple collection jobs.

trajectoryId is a SHA-256 content address of semanticEpisodeId, collectionJobId, the ordered
semantic decision records, and factual closure metadata. A duplicate
(collectionJobId, semanticEpisodeId) with conflicting content is a conflict; the reader/merge
operation rejects it instead of choosing an arrival-order winner.

The identity chain is per episode:

~~~text
semanticEpisodeId
    -> collectionJobId
    -> trajectoryId
~~~

collectionJobId remains per-episode because semanticEpisodeId contains the actual engine seed.
It is not a collection directory or a multi-episode batch identity.

DatasetManifestV1 is the outer multi-episode batch contract. datasetId is a SHA-256 content
address of the finalized manifest preimage: version identities, deterministic episode index,
ordered shard entries, counts, and every referenced shard digest. The preimage omits the
datasetId and manifestContentDigest fields themselves to avoid circularity. The manifest then
stores both datasetId and its manifestContentDigest, where the latter is computed over the
manifest with only its own digest field removed. One dataset may contain many trajectories with
distinct semanticEpisodeId and collectionJobId values. datasetId is a storage/artifact identity,
not a semantic environment or decision identity.

DatasetMetadataV1 is the batch-level input to the publisher: schema identities, shard/episode
bounds, and deterministic enumeration policy. It does not replace per-episode environment or
policy metadata, and its datasetId is derived only when the complete manifest preimage is ready.

## OBSERVATION_SCHEMA_IDENTITY=

The source observation is the current TrainingObservation produced by ObservationBuilder.
The durable PlayerObservationV1 is a transport-free projection of that DTO, not a second
environment or a second visibility model.

It contains the current semantic public fields:

- perspective and acting seat;
- public turn/phase/step and active/priority players;
- public player summaries;
- visible zone/card features and stack projection;
- pending decision kind, playerId, sourceEntityId, triggeringEntityId, requiresStructuredResponse,
  and shape;
- terminal/truncation flags and winner only where the current public observation defines them;
- schemaHash and observationDigest, which binds the source TrainingObservation.stateDigest.

It omits:

- TrainingObservation.legalActions and PendingDecisionView.structuredDomain. These are normalized
  once into CompleteLegalDomainV1, which is the sole durable legal-domain authority;
- every actionId;
- every PendingDecisionView.decisionId;
- every DecisionResponse.decisionId;
- prompts, descriptions, source names, effect hints, renderer-only fields, and auto-pay suggestions;
- raw GameState, raw continuation state, diagnostics containing implementation detail, and hidden
  zone/card identities.

The omission of presentation fields follows the existing ObservationCanonicalizer contract.
Adding presentation text later requires an explicit V1 extension; it must not silently alter the
semantic projection. The capture adapter receives one TrainingObservation and derives the
observation-only projection plus the one CompleteLegalDomainV1. It does not persist a second
legalActions or structuredDomain copy.

The existing TrainingObservation.stateDigest remains the reusable public observation digest. At
capture, the source semantic preimage is computed before the split. At read/verify time, the
canonical source observation is reassembled from PlayerObservationV1 and CompleteLegalDomainV1 in
the producer-declared order, and the same ObservationCanonicalizer semantic bytes are recomputed.
The resulting observationDigest is therefore the source stateDigest binding, while the
candidateDomainDigest remains a separate digest of the stored domain. PlayerObservationV1 alone
is not presented as a complete domain-bearing observation.

SchemaHash.CURRENT is reusable as the wire contract anchor. A separate durable-projection
identity is required because the current hash does not version the semantic-field exclusion list or
the nested JsonObject action-semantics vocabulary.

## ACTION_DOMAIN_SCHEMA_IDENTITY=

~~~text
ACTION_DOMAIN_SCHEMA_IDENTITY=argentum-gym-action-domain@v1
SUPPORTED_COMPONENTS=
  ActionTargetDomainV1
  AttackDeclarationDomainV2
  BlockerDeclarationDomainV1
  PaymentDomainV5
  TargetPaymentDomainV1
  StructuredDecisionDomain version 1
  ManaSourcesDomain version 3
  TargetsDomain version 2
  ActionPayloadRequirements canonical field order
~~~

CompleteLegalDomainV1 has one of three semantic shapes:

1. ACTION_CANDIDATES: every current public LegalActionView candidate for a priority/action
   boundary, represented without its transport handle.
2. FOLDED_DECISION_OPTIONS: every folded response candidate for a simple pending decision,
   represented by the transport-free response semantics in each LegalActionView.
3. STRUCTURED_DECISION: the pending decision kind and shape plus the complete typed
   StructuredDecisionDomain.

A structured boundary may not carry an empty placeholder domain. AssignDamageDecision currently
has a requiresStructuredResponse marker but no structuredDomain branch in ObservationBuilder; an
actor-facing occurrence is therefore a typed unsupported path, not an empty domain that B2 may
repair.

For a priority/action boundary, every candidate remains in the stored domain, including an
unaffordable public candidate when the current observation exposes it. The policy may select only
the choices the existing strict submission contract accepts. B2 does not replace the complete
domain with an affordable-only subset.

CompleteLegalDomainV1 is the single canonical stored representation of the current legal domain.
For an action boundary it is normalized from TrainingObservation.legalActions; for a structured
pending decision it is normalized from PendingDecisionView.structuredDomain. PlayerObservationV1
contains the pending semantic context but neither domain copy. The observation digest binds the
observation-only projection to this one stored domain.

## SEMANTIC_DECISION_IDENTITY=

~~~text
SEMANTIC_DECISION_IDENTITY=argentum-trajectory-semantic-decision@v1
ALGORITHM=lowercase SHA-256 over UTF-8 canonical preimage
~~~

The preimage is the canonical object:

~~~json
{
  "schema": "argentum-trajectory-semantic-decision@v1",
  "semanticEpisodeId": "SEMANTIC_EPISODE_CONTENT_SHA256",
  "replayPrefixDigest": "REPLAY_PREFIX_SHA256",
  "replayActionIndex": 0,
  "perspectivePlayerId": "public-seat-id",
  "decisionKind": "PRIORITY",
  "observationDigest": "STATE_DIGEST_SHA256",
  "candidateDomainDigest": "DOMAIN_PREIMAGE_SHA256"
}
~~~

This is deliberately scoped by semantic episode and ordered semantic history. semanticEpisodeId
contains only the pinned environment/setup/actual-engine-seed identity. collectionJobId, datasetId,
behavior/opponent policy identity, policy RNG/seed, trajectoryId, GameSession.sessionId, EnvId,
actionId, decisionId, PendingDecision.id, nonce, projectionGeneration, recordingRevision, ability
allocation order, UUID, wall time, PID, and worker placement are not in this preimage.

This separation is intentional: two policies may reach the same semantic environment position and
replay prefix, and must receive the same semanticDecisionId when the public observation and complete
domain are the same. Their collectionJobId and trajectory content may differ, but policy provenance
must never change the semantic decision identity.

replayPrefixDigest is the hash of the ordered semantic replay actions/responses before the current
boundary, after the existing typed decision-reference rebinding and generated-ability aliasing.
Policy labels, policy RNG state, collectionJobId, and freshness/routing handles are excluded; a
policy can influence the prefix only by selecting a different semantic action, which is semantic
history rather than provenance.

The required invariant is:

~~~text
same pinned environment
+ same semantic episode inputs
+ same replayed semantic history
+ same current public decision/domain
=> same semanticDecisionId
~~~

The chosen value is a separate ChosenSemanticActionV1 or ChosenSemanticResponseV1:

- action records contain the candidate's transport-free semantic action and a complete canonical
  public choice payload;
- folded responses contain the response discriminator and all response fields except decisionId;
- structured responses contain the response discriminator and all semantic selection fields,
  including explicit empty selections, source choices, payment allocation, ordering, or damage
  amounts;
- ActivateAbility records use the stable abilityKey already produced by ObservationBuilder,
  never the raw runtime AbilityId;
- unknown payload fields, omitted required fields, duplicate semantic candidates, and partial
  choices fail closed.

The chosen object is stored in full. A derived chosen-action digest is allowed as a check, but it
never replaces the chosen semantic payload.

## CANDIDATE_DOMAIN_DIGEST=

~~~text
CANDIDATE_DOMAIN_DIGEST=argentum-gym-candidate-domain-digest@v1
ALGORITHM=lowercase SHA-256 over UTF-8 canonical domain JSON
PREIMAGE_PREFIX=argentum-gym-candidate-domain-digest@v1\n
~~~

The digest is calculated from the complete CompleteLegalDomainV1, not from the observation's
whole stateDigest. The stored domain remains mandatory.

Canonicalization rules:

- JSON object keys are ordered by code point.
- The domain kind, version, decision kind, shape, and every versioned nested domain are included.
- Candidate-list order is never chosen by the trajectory layer. Rules-significant order is
  preserved exactly. For an unordered candidate set, the public Gym/domain producer must establish
  a deterministic canonical order before capture; CompleteLegalDomainV1 stores that exact order.
- Target requirements, attackerOrder, blockerOrder, attackerToDefenders, payment source
  options, payment cost units, reorder-library card order, combat edge order, and any other
  Rules-significant sequence retain the producer-owned order.
- Set-shaped colors, types, subtypes, keywords, visible attachments, target candidate sets, and
  other fields already declared unordered by ObservationCanonicalizer must likewise be put into
  their producer-established canonical order before storage. The trajectory digest does not sort
  arbitrary arrays after the boundary.
- The canonicalizer must reject duplicate semantic members. It must not deduplicate equal
  blocker requirement instances, because the current blocker contract intentionally retains
  requirement multiplicity.
- Action-level and pending-payment AutoPay are not legal alternatives to explicit payment. Pending
  `ManaSourcesDomain` V3 publishes `PaymentDomainV5`, and its durable response carries the same
  complete `PaymentPlanV3` used by action payment.
- Presentation-only descriptions, prompts, labels, image URIs, renderer flags, and human-facing
  copy are excluded as they are in the current semantic canonicalizer.
- Unknown component versions, unknown domain kinds, unresolved generated ability provenance, and
  malformed ordering/relations fail closed. Sorting a nondeterministic producer is not a repair.
- JSON object-key ordering is a digest encoding rule only; it does not authorize reordering any
  candidate or semantic sequence.

Required negative evidence changes the digest or rejects the record:

1. candidate membership changes;
2. semantic candidate payload changes;
3. a Rules-significant producer order changes;
4. equivalent unordered source inputs are canonicalized by the public producer to the same stored
   order, while a raw noncanonical producer order is rejected rather than sorted by this digest;
5. a duplicate candidate or malformed relation appears;
6. a runtime action/decision handle changes with the semantic payload unchanged.

The current StateDigest and ObservationCanonicalizer.semanticActionFingerprint are the starting
implementation source. The existing whole-observation canonicalizer may sort its historical
semantic action-fingerprint array for StateDigest compatibility, but that behavior is not reused
to repair producer order in CandidateDomainDigestV1. They do not currently expose a standalone
domain digest or a duplicate malformation gate, so this is a small generic hardening task rather
than a proof that an equivalent primitive already exists.

## POLICY_PROVENANCE=

The identity split is normative:

~~~text
semanticEpisodeId = SHA-256(canonical environment/setup/actual-engine-seed identity)
collectionJobId = SHA-256(semanticEpisodeId + canonical PolicyProvenanceV1)
semanticDecisionId = SHA-256(semanticEpisodeId + replayPrefixDigest + replayActionIndex
                             + perspective + decisionKind + observationDigest
                             + candidateDomainDigest)
~~~

PolicyProvenanceV1 is episode metadata plus a per-seat role binding:

~~~json
{
  "behaviorPolicyIdentity": "argentum-b0-deterministic-external-policy@1",
  "opponentPolicyIdentity": "argentum-b0-deterministic-external-policy@1",
  "behaviorPolicyRole": "EXTERNAL_CONTROLLER",
  "opponentPolicyRole": "EXTERNAL_CONTROLLER",
  "policyRngIdentity": "explicit-seed/kotlin-policy-state-v1",
  "policySeed": 4259905,
  "policySourceIdentity": "POLICY_CONTRACT_SHA256"
}
~~~

The exact B0 bootstrap policy may use the same concrete implementation in both seats; the two
roles remain separate fields so a later self-play or league run cannot collapse behavior and
opponent provenance into one vague checkpoint. The B0 policySeed and choiceOrdinal are collection
job provenance inputs, not semanticEpisodeId inputs, model features, or Rules state. They affect
which trajectory is collected, but must not affect semanticDecisionId.

V1 does not add behavior log-probabilities, value estimates, importance weights, recurrent burn-in,
MCTS visits, or shaped rewards. Such fields require a separately versioned extension selected by
C0. The canonical trajectory contains factual policy provenance only.

## CLOSURE_TAXONOMY=

~~~text
GAME_TERMINAL
  The pinned Rules state reached gameOver. Store only an authoritative winner, loser, or draw,
  the Rules/game-over reason when available, public terminal metadata, and exact counts.

INTERRUPTED
  A valid semantic prefix ended under an explicit controlled reason such as the configured
  external horizon or caller cancellation. Store no fabricated winner, draw, or reward. Eligibility
  of an interrupted prefix for a future algorithm remains a C0/training-policy decision.

FAILED
  A semantic or integrity trust failure occurred: unsupported path, public-choice rejection,
  exception, missing domain, replay divergence, incomplete observation, schema mismatch, or
  privacy/integrity failure. Retain diagnostic/quarantine evidence if useful, but never publish the
  episode as trusted training data.
~~~

The current GameEnvironment exposes only state.gameOver, maxSteps-derived truncated, and
winnerId; StepResult.reward also supplies a convenient terminal label. Those are not enough for
V1 because they do not distinguish controlled interruption from an exception or post-action
observation failure. EpisodeDiagnostics supplies typed failure evidence but not a closure value.
The smallest reusable prerequisite is therefore a typed EpisodeClosureV1 additive result. The
existing booleans remain unchanged for current Gym clients.

Canonical V1 stores no reward. For INTERRUPTED and FAILED, a missing reward is intentional. For
GAME_TERMINAL, later consumers may derive a reward view from factual outcome metadata.

## WRITER_DESIGN=

The episode writer and the dataset publisher have separate responsibilities. The per-episode writer
accepts only the public boundary:

~~~text
beginEpisode(EpisodeMetadataV1)
recordDecision(PlayerObservationV1, CompleteLegalDomainV1, chosenSemanticActionOrResponse,
               replay coordinates)
finishEpisode(EpisodeClosureV1, CompactReplayLinkV1, ReplayVerificationBindingV1)
~~~

The capture adapter may receive one current TrainingObservation, but it derives the two arguments
once and the writer stores them exactly once: PlayerObservationV1 has no legalActions or pending
structuredDomain copy, and CompleteLegalDomainV1 is the sole stored domain authority. The writer
never accepts GameState, PendingDecision, LegalAction, ActionRegistry, an opaque action handle,
or a native policy object.

The dataset-level publisher aggregates already-verified episode results:

~~~text
beginDataset(DatasetMetadataV1)
appendFinalizedEpisode(ValidatedEpisodeV1)
finalizeDataset()
~~~

The integration-only B2GenerationHarness is the replay integration seam:

~~~text
for each CompactReplay:
  source = game-server GymReplayFrameSource(compactReplay)
  binding = source.verifyBinding()
  episode = writer.finishEpisode(closure, replayLink, binding)
  publisher.appendFinalizedEpisode(episode)
publisher.finalizeDataset()
~~~

The harness passes only the neutral ReplayVerificationBindingV1 across the module boundary. The
writer and publisher reject a missing result, a non-EXACT result, an incomplete range, or a frame
mismatch. The publisher aggregates many episodes, each with its own collectionJobId, into one
DatasetManifestV1. The writer and publisher do not import or call game-server ReplayReconstructor;
the harness is the only caller of the concrete replay source. This plan deliberately chooses the
harness composition path rather than adding a game-server source parameter to the publisher.

The binding-aware writer call shown above is a future composition contract. This prerequisite adds
the neutral binding source only; it does not implement or change the writer or publisher.

At recordDecision it must:

1. require a nonterminal acting observation with the acting perspective equal to the decision
   owner;
2. require an empty diagnostic ledger and no unsupported/fallback signal;
3. accept the transport-free PlayerObservationV1 from the public capture boundary;
4. accept the complete domain from that same boundary and require its schema/version;
5. recompute the source observationDigest and candidateDomainDigest;
6. reject duplicate semantic candidates and malformed producer order;
7. require every declared payload field, including explicit empty maps/lists;
8. prove the chosen semantic action/response is inside the stored complete domain using the current
   public submission validators plus a DTO-level structural validator;
9. append the decision to the semantic replay-prefix accumulator with its exact
   replayFrameIndex and replayActionIndex;
10. retain no raw internal state after validation.

At finishEpisode it must:

- reject a second finish, an empty decision range where the environment requires a policy decision,
  an unclosed pending response, or an action/decision/frame count mismatch;
- require the supplied binding's verification to cover the complete declared replay range,
  including initial, intermediate, and tail frames, with ReplayFidelity.EXACT;
- require binding.replayContentIdentity to match CompactReplayLinkV1 and its verification replay
  version to match the bound identity;
- require GAME_TERMINAL to agree with the replayed Rules state;
- require INTERRUPTED to be explicit and nonterminal;
- route FAILED to quarantine and never to a published shard;
- verify semanticEpisodeId and collectionJobId from canonical episode metadata, then compute
  trajectoryId and episode content digests from canonical bytes.

At finalizeDataset the publisher must:

- accept only complete episode results from the per-episode writer;
- place complete episodes into bounded shards without splitting an episode;
- build the deterministic DatasetManifestV1 and derive datasetId from its non-self-referential
  canonical preimage;
- publish only through the atomic whole-directory operation described in ATOMIC_PUBLICATION.

The writer does not repair a missing domain, choose an omitted target/payment/mode, infer a
winner, convert interruption into terminal, or convert failure into a usable prefix. A failed
episode's valid prefix may be retained in quarantine for diagnosis only.

## READER_DESIGN=

The reader has two layers:

1. a strict manifest/shard integrity reader;
2. a semantic episode/decision validator.

The strict reader:

- accepts only the supported DatasetManifestV1/trajectory manifest and component identities;
- rejects unknown future dataset-manifest, trajectory, observation, domain, digest, or
  decision-identity versions before decoding a sample;
- validates the final dataset directory name against DatasetManifestV1.datasetId and validates
  the manifestContentDigest before yielding a sample;
- enumerates exactly the shard paths listed by the manifest in manifest order; it never trusts
  filesystem enumeration order;
- validates byte length, record count, UTF-8/LF framing, shard SHA-256, and manifest content digest
  before yielding any sample;
- validates episode-start/decision/episode-end framing and complete episode digests;
- streams valid decision records after the integrity pass, without loading the whole dataset;
- rejects conflicting duplicate (collectionJobId, semanticEpisodeId) or trajectoryId entries within
  one dataset; a distinct datasetId is a separate immutable snapshot and is not silently merged;
  the same semanticEpisodeId in a different collectionJobId is allowed only when its metadata
  carries the corresponding distinct policy provenance;
- exposes policy, engine, deck, replay, closure, quarantine, and count metadata;
- preserves variable-size observations and complete domains;
- exposes semanticDecisionId and the full chosen semantic payload;
- never exposes raw GameState through the training-reader API.

The semantic validator:

- recomputes observation and domain canonical bytes;
- recomputes all digests and semantic decision IDs;
- proves chosen membership and required-payload completeness;
- rejects FAILED episodes and any episode with unsupported/fallback diagnostics;
- returns INTERRUPTED explicitly without a terminal label;
- treats ReplayFidelity.UNVERIFIED and ReplayFidelity.DIVERGED as non-trusted;
- does not infer a reward or a winner.

An invalid shard or episode is moved or copied to quarantine evidence with a typed reason. It is
never silently skipped while the reader continues to report a trusted dataset.

## SHARD_FORMAT=

V1 uses canonical uncompressed NDJSON/JSONL:

~~~text
dataset-root/
  dataset-DATASET_ID/
    manifest.json
    shard-000000.ndjson
    shard-000001.ndjson
  .staging/
  quarantine/
~~~

The choice is based on the current repository, not on a future distributed system:

- Kotlin serialization and canonical JSON already exist;
- there is no current Arrow/Parquet/HDF5 dependency or reader;
- NDJSON is portable to local JVM, Python, Kaggle, Colab, and cloud learners;
- uncompressed UTF-8 makes content digests, bounded byte sizes, and streaming simple and
  reproducible;
- compression can be added as an explicit shardEncoding extension after measuring actual V1
  serialized sizes. It is not needed to prove the contract.

The directory is dataset-level and may contain many complete episodes. Each episode retains its
own semanticEpisodeId, collectionJobId, and trajectoryId in the event stream and manifest index;
collectionJobId is never used as the dataset directory identity.

Each shard contains complete episode event sequences. An episode begins with episode-start,
contains one decision line per decision, and ends with episode-end:

~~~json
{"recordType":"episode-start","trajectorySchemaVersion":1,"semanticEpisodeId":"...","collectionJobId":"...","episodeOrdinal":7,"episodeMetadata":{}}
{"recordType":"decision","trajectorySchemaVersion":1,"semanticEpisodeId":"...","collectionJobId":"...","decision":{}}
{"recordType":"episode-end","trajectorySchemaVersion":1,"semanticEpisodeId":"...","collectionJobId":"...","trajectoryId":"...","decisionCount":42,"episodeContentDigest":"...","closure":{}}
~~~

Every line is canonical JSON, UTF-8, no BOM, one LF terminator, and no insignificant whitespace.
The semantic JSON writer is shared with the digest implementation so the writer does not hash
unstable object toString output.

Shard boundaries occur only between complete episodes. The manifest records maximum shard bytes,
actual bytes, episode ordinal range, episode count, record count, and SHA-256. A configured maximum
episode size prevents one oversized episode from violating the shard bound; an oversized episode
is failed/quarantined rather than split or truncated. The B1 measurement contract supplies the
input for selecting the initial byte bound after a bounded serialization characterization; no
distributed/cloud sizing is designed in B2.

The manifest is canonical JSON with:

- DatasetManifestV1 schema identity and datasetId;
- trajectory/component schema identities;
- per-episode environment/policy/deck/definition identity in the episode index;
- a deterministic episode index containing each episodeOrdinal, semanticEpisodeId,
  collectionJobId, trajectoryId, closure, and shard membership;
- deterministic ordered shard entries;
- counts and closure counts;
- each shard's byte length, record count, and content digest;
- manifestContentDigest computed over the manifest with its own digest field removed.

The reader accepts no unlisted shard and no shard with a duplicate ordinal or conflicting digest.

## ATOMIC_PUBLICATION=

Publication state is:

~~~text
WRITING -> VALIDATING -> VALIDATED -> PUBLISHED
                         \-> QUARANTINED
~~~

Implementation rules:

- create a unique staging dataset directory under dataset-root/.staging. The operational write
  token used in that directory name is not a semantic or provenance field in the artifact;
- keep the final dataset-DATASET_ID directory absent or unlisted while writing;
- close and flush each shard, validate its complete event framing, and compute its digest;
- write the manifest last inside the staging dataset directory, including the complete episode
  index, all finalized shards, counts, datasetId, and manifestContentDigest;
- close/fsync finalized files and the staging directory where the host filesystem supports it, then
  atomically rename the entire staging dataset directory to dataset-DATASET_ID with ATOMIC_MOVE
  on the same filesystem. The manifest and every referenced shard therefore arrive at their final
  relative paths in one publication operation;
- never replace an existing published dataset directory, manifest, or shard;
- if the filesystem cannot provide the required atomic directory move, fail publication and retain
  staging/quarantine evidence instead of silently falling back to an overwrite or a manifest-only
  move;
- a reader ignores .staging and accepts only a final dataset directory with a valid manifest and
  all listed immutable shards; absence of the final directory or a digest mismatch is not
  published;
- a repeated semantic episode ordinal or (collectionJobId, semanticEpisodeId) is a conflict, not
  an invitation to select the first or newest artifact.

The existing TrainingCorpusFiles temporary-file/atomic-move pattern is the implementation model.
Its whole-corpus REPLACE_EXISTING and append behavior are not copied into V1.

## QUARANTINE=

Quarantine is a separate non-trusted namespace with a typed reason and enough provenance to
reproduce the failure without copying raw hidden state:

~~~text
FAILED_EPISODE
ENGINE_EXCEPTION
UNSUPPORTED_DIAGNOSTIC
NATIVE_POLICY_FALLBACK
PUBLIC_CHOICE_REJECTED
INCOMPLETE_EPISODE
REPLAY_DIVERGENCE
REPLAY_UNVERIFIED
SCHEMA_MISMATCH
UNKNOWN_VERSION
CHECKSUM_MISMATCH
CONTENT_DIGEST_MISMATCH
CHOSEN_NOT_IN_DOMAIN
CANDIDATE_DOMAIN_DIGEST_MISMATCH
SEMANTIC_DECISION_IDENTITY_MISMATCH
PRIVACY_FAILURE
DUPLICATE_OR_CONFLICTING_EPISODE
SHARD_SIZE_EXCEEDED
~~~

Quarantine metadata contains the source engine/definition/policy/replay identities, episode
ordinal, last complete frame/action coordinate, closure/failure code, and a bounded diagnostic
message. It does not contain GameState, hidden card data, raw continuation stacks, or credentials.
The failure record is evidence for debugging, never a trusted sample.

## REPLAY_VERIFICATION=

The required pipeline is:

~~~text
CompactReplay
  -> ReplayCodec.decode (known version and payload)
  -> ReplayReconstructor / VerifiedReplayFrameSource
  -> verified initial state and checkpoint at action count 0
  -> decision boundary at replay action i
  -> ObservationPerspective + LegalActionEnumerator
  -> ObservationBuilder
  -> PlayerObservationV1
  -> complete public domain
  -> candidateDomainDigest
  -> semanticDecisionId
  -> chosen semantic action/response
  -> compare with stored Trajectory V1
  -> apply the recorded semantic action through the existing replay fold
  -> continue through action i + 1
  -> exact final closure and tail verification
~~~

The cross-module contracts are:

~~~text
interface VerifiedReplayFrameSource {
  fun verify(): VerifiedReplayVerification
}

interface ReplayVerificationBindingSource {
  fun verifyBinding(): ReplayVerificationBindingV1
}
~~~

The interfaces and all neutral result types live in :gym. `VerifiedReplayVerification` remains the
accepted V1 frame/proof evidence contract; `ReplayVerificationBindingV1` adds the independently
versioned logical replay-content identity without changing that V1 meaning. A game-server
`GymReplayFrameSource` instance is constructed with one `CompactReplay` and its binding API is
upcast to the neutral interface at the composition root. `B2GenerationHarness` calls
`verifyBinding()` before `TrajectoryV1Writer.finishEpisode` and before any dataset shard is finalized.
It then passes the identity plus unchanged proof evidence to the gym-trainer publisher. Thus
gym-trainer has no game-server dependency, game-server has no gym-trainer dependency, and the
future integration/acceptance harness is the only place that wires both concrete modules.

The verifier must use one forward cursor, not call reconstructStateAt independently for every
frame. The current reconstructStateAt proves that a requested action prefix can be applied, but
it does not by itself validate every checkpoint or emit the public Gym observation stream.

The declared replay range is complete and inclusive at both observation boundaries:

~~~text
frame 0 before replay action 0
-> frame 1 after replay action 0 / before replay action 1
-> ...
-> frame N at the tail after all replay actions, where N = CompactReplay.actions.size
~~~

For N > 0, frame N follows replay action N - 1; for N = 0, frame 0 is both the initial and tail
frame. The verifier must compare frame 0, every intermediate frame, and the final tail frame. It must
consume exactly every declared replay action, update replayPrefixDigest from the semantic replay
prefix after each applied action, and reject an early end, an omitted tail, an extra action, or a
decision coordinate that does not match the cursor. Internal transitions without a public
decision boundary still consume their replay coordinate; they may not be skipped to make the
public frame count appear adjacent.

For each replayActionIndex:

1. Use the replayed pre-action state only inside the verifier to determine the current actor and
   call the same public LegalActionEnumerator and ObservationBuilder used by Gym.
2. Require the stored decision's replayFrameIndex and decisionIndex to match the cursor.
3. Compare the complete transport-free observation, the complete domain, the domain digest, and
   semantic decision ID.
4. Prove the stored chosen semantic payload against the replayed public domain. Use current
   target, combat, color, payment, target-payment, and DecisionValidators checks where state is
   required. These checks validate an externally supplied choice; they do not construct a missing
   choice.
5. Compare the stored chosen semantic payload with the action/response being replayed, after only
   the existing typed decision-ID rebind and generated-ability semantic aliasing.
6. Apply the recorded action through the existing ReplayReconstructor fold. Any exception,
   unsupported diagnostic, action rejection, or checkpoint mismatch ends trust immediately.
7. Capture the next frame and continue. The final frame is compared even when it is terminal or
   interrupted.

For the B0 strict external path, the required cardinality is:

~~~text
verifiedFrames = decisions + 1
replayActions = decisions
~~~

If a server replay includes an action that has no policy-facing boundary, the adapter must expose a
typed internal-transition coordinate or reject the record. It must not silently skip that action or
pretend the next public observation is adjacent.

Trusted replay acceptance requires CompactReplay.version == 5 for the initial V1 bootstrap,
ReplayFidelity.EXACT, a valid initial and tail checkpoint, no checkpoint outside the applied
range, and a public semantic comparison for every frame. Historical V1–V4 replays may remain
viewable through existing replay behavior, but they are not silently reinterpreted as Trajectory V1.

### Equivalence exclusions

The public trajectory comparison excludes only fields with an explicit current contract:

- LegalActionView.actionId;
- PendingDecisionView.decisionId, PendingDecision.id, DecisionResponse.decisionId;
- typed continuation decision-reference slots;
- generated numeric AbilityId handles where the existing stable ability key/alias preserves the
  ability's origin, ordinal, and structural payload;
- EnvId, snapshot slot, message ID, state-update version, generation, recording revision, worker
  placement, PID, wall time, and completion order;
- presentation-only prompts, descriptions, labels, image URIs, and auto-pay suggestions;
- the computed observationDigest/source stateDigest field while recomputing it from the
  reassembled semantic observation and complete domain.

The comparison retains all public semantic characteristics, legal-domain membership and
Rules-significant order, visible entity references, source/target/payment relations, structured
response values, replay action order, seed/setup inputs, factual closure, and version identities.
In particular, PendingDecisionView.sourceEntityId and PendingDecisionView.triggeringEntityId are
retained semantic context; same-domain YES_NO decisions that differ in either reference are not
equivalent. The full-state ReplayFingerprint remains a separate replay-proof input and is never
converted into a model feature.

## DECISION_FAMILY_CLOSURE=

The closure decision is the intersection of four inventories:

1. current static exact-pair definition-derived families;
2. current Rules-to-public-domain producers;
3. B0 dynamic observations;
4. B2 serialized, replay-verified behavioral records.

The historical B0 dynamic snapshot embedded in the exact-pair acceptance source records:

~~~text
ACTION_KINDS=
  ActivateAbility=48,522
  CastSpell=690
  CastSpellMode=76
  CastWithKicker=6
  CycleCard=44
  DECISION=2,697
  DeclareAttackers=410
  PassPriority=85,006
  PlayLand=2,435

DECISION_FAMILIES=
  CHOOSE_COLOR=112
  CHOOSE_TARGETS=74
  PRIORITY=137,189
  SELECT_CARDS=2,759
  YES_NO=104

REQUIRED_PUBLIC_FIELDS=
  paymentStrategy, xValue, targets, manaColorChoice,
  additionalCostPayment, costPayment, attackers, bands

TOTAL_TRANSITIONS=140,238
~~~

These are historical source-bound evidence, not a new run in this audit. The current static closure
source additionally derives or checks static-only families, and the exact-pair tests explicitly
prove that CastWithFlashback, REORDER_LIBRARY, and CHOOSE_MODE can be derived from current
definitions even when absent from the historical telemetry.

Closure evidence rule:

- Every family that is reachable under the pinned Environment V1 input must receive a targeted
  legal behavioral witness and replay-verified serialized record.
- A family may instead receive PROVEN_UNREACHABLE_FOR_PINNED_ENVIRONMENT_V1 only from an explicit
  proof over the pinned Environment V1 rules, deck, and configuration inputs.
- A fixed policy not visiting a family, an empty attack chosen by policy, or absence from
  historical telemetry is never an unreachability proof.
- If neither a targeted witness nor the exact pinned-input proof exists, the family remains
  BLOCKED; the writer cannot publish a claim of closure.

| Family | Current public producer | B0 dynamic evidence | B2 disposition |
| --- | --- | --- | --- |
| PRIORITY / PassPriority | LegalActionEnumerator and TrainingObservation.legalActions | Observed | Store complete action candidate domain and behavioral records. |
| ActivateAbility | LegalActionEnumerator, actionSemantics stable ability key, payment domains | Observed | Store complete candidate and selected target/cost/payment/color/X payload. |
| CastSpell / CastWithKicker / CastWithFlashback | Cast enumerators and public action semantics | CastSpell and CastWithKicker observed; CastWithFlashback static-only | Require behavioral records for observed families; CastWithFlashback needs a targeted legal witness or an exact pinned Environment/Deck/Config proof. Policy non-visit is not proof. |
| CastSpellMode | Cast enumerator emits a chosen-mode action; LegalActionView carries semantic action fields | Observed | Verify each selected mode. A multi-mode template using LegalAction.modalEnumeration currently lacks a corresponding LegalActionView field; if reachable, stop for a generic public-domain follow-up. |
| CycleCard | Cycling enumerator and V5 payment domain | Observed | Store complete action/payment domain and response. |
| PlayLand | Land enumerator | Observed | Store complete action domain. |
| DeclareAttackers | Rules AttackDeclarationDomain, mapped to AttackDeclarationDomainV2 with producer-owned attacker order | Observed | Store and compare ordered attacker/defender/band constraints; never use flat legacy hints. |
| DeclareBlockers | Rules BlockerDeclarationDomain, mapped to BlockerDeclarationDomainV1 | Not in historical dynamic snapshot; the accepted B0 policy chooses empty attacks in its public policy path | The empty-attack policy path is not evidence of unreachability. Require a targeted legal nonempty-attack/blocker witness or an exact pinned Environment/Deck/Config proof; otherwise BLOCKED. The static closure allowlist alone is insufficient. |
| DECISION folded options | PendingDecision simple variants become LegalActionView response candidates | Observed | Store the full option candidate set; chosen response excludes only its nonce. |
| YES_NO including commander-zone yes/no and batch yes/no | YesNoDecision and BatchYesNoDecision | Observed | Preserve response subtype and semantic fields; do not use prompt text to identify commander behavior. |
| CHOOSE_COLOR | ChooseColorDecision or action-level mana-color domain | Observed | Store explicit WUBRG/certified public color domain and selected color. |
| CHOOSE_TARGETS | TargetsDomain and action-level fixed target domain | Observed | Store every requirement, relation, cardinality, and candidate; validate selected IDs and constraints. |
| SELECT_CARDS | CardSelectionDomain | Observed | Store options, selection bounds, order flag, constraints, and selected cards. |
| CHOOSE_MODE | ModeSelectionDomain for multi-mode pending decisions | Static-only in current telemetry | Require a targeted legal behavioral witness or an exact pinned Environment/Deck/Config proof; no enum-only or telemetry-absence coverage claim. |
| REORDER_LIBRARY | ReorderLibraryDomain | Static-only in current telemetry | Require a targeted legal behavioral witness or an exact pinned Environment/Deck/Config proof; stored order is semantic and must not be sorted away. |
| DISTRIBUTE | DistributionDomain | Not observed | Require a targeted legal witness plus structured response record, or an exact pinned Environment/Deck/Config proof, before trusted publication. |
| ORDER_OBJECTS / trigger ordering | OrderingDomain, including generated trigger-order handles | Not observed | Require a targeted legal witness or an exact pinned Environment/Deck/Config proof; validate handle-free object semantics and use the existing alias rules only. |
| SPLIT_PILES | SplitPilesDomain | Not observed | Require a targeted legal witness or an exact pinned Environment/Deck/Config proof; preserve pile membership and response order. |
| CHOOSE_OPTION | Folded option candidates and OptionMetadata | Not observed | Require a targeted legal witness or an exact pinned Environment/Deck/Config proof; option index is semantic, display metadata is not. |
| CHOOSE_REPLACEMENT | ReplacementDomain | Not observed | Require a targeted legal witness or an exact pinned Environment/Deck/Config proof; validate the from/to relation. |
| SEARCH_LIBRARY | SearchLibraryDomain | Not observed | Require a targeted legal witness or an exact pinned Environment/Deck/Config proof; preserve visible authorized card information only. |
| BUDGET_MODAL | BudgetModalDomain | Not observed | Require a targeted legal witness or an exact pinned Environment/Deck/Config proof; preserve repeated mode selections and budget constraints. |
| ASSIGN_DAMAGE | Current PendingDecision producer, but ObservationBuilder emits no structured domain | Not observed | Unsupported public producer. Do not serialize it by echoing raw state; add a generic public-domain follow-up or establish an exact pinned Environment/Deck/Config proof of unreachability. |
| COMBAT_RESOLUTION | CombatResolutionDomain and CombatResolutionResponse | Not observed as a pending family in the snapshot | Require a targeted legal witness or an exact pinned Environment/Deck/Config proof; compare all edges, ownership, defaults, and final response amounts. |
| SELECT_MANA_SOURCES | ManaSourcesDomain V3: PaymentDomainV5 plus explicit PaymentPlanV3 response | A8 reachability evidence remains separate from this generic contract fix | Require a targeted legal witness or an exact pinned Environment/Deck/Config proof. Source-only selection and AutoPay are not trusted labels. Unsupported V5 shapes, Waterbend reductions, composite Ward remainder costs, and the targeted legacy continuation without an unpaid response fail closed. |
| MULLIGAN | Rules actions exist, but no MulliganDecision public pending family or Gym legal-action branch is present | No public family under the pinned EnvConfig.skipMulligans=true input | PROVEN_UNREACHABLE_FOR_PINNED_ENVIRONMENT_V1 is valid here only because skipMulligans=true is an explicit pinned environment configuration. Non-skipped mulligan is not required for B2, and policy behavior is not evidence. |

Every row must end in one of:

~~~text
BEHAVIORALLY_REPRESENTED_AND_REPLAY_VERIFIED
PROVEN_UNREACHABLE_FOR_PINNED_ENVIRONMENT_V1
BLOCKED_BY_MISSING_GENERIC_PUBLIC_DOMAIN
~~~

An enum value, static card mention, or absence from historical telemetry is not behavioral
coverage. An unknown/new family causes the writer and acceptance harness to fail closed.

## GENERIC_GAPS_FOUND=

~~~text
GENERIC_GAPS_FOUND=BLOCKING_PREREQUISITES_IDENTIFIED
~~~

1. The Gym lifecycle has terminated and truncated booleans but no typed closure value or
   controlled interruption reason. Exceptions and diagnostics are not a durable closure contract.
2. The current B0 replay bridge is test-only and reflective. It can compare
   ObservationCanonicalizer.semanticJson frame strings, but no production reusable cursor
   connects CompactReplay/ReplayReconstructor to the public Gym observation boundary.
3. StateDigest is a complete public observation digest, not a candidate-domain digest. There is no
   current standalone domain digest with duplicate rejection and an independently versioned
   preimage.
4. The current public wire observation contains ephemeral action and decision handles. The current
   semantic canonicalizer excludes them, but no durable transport-free PlayerObservationV1
   contract is exposed.
5. There is no generic chosen semantic action/response contract that merges a public action template
   with a complete external payload while retaining stable ability provenance and excluding raw
   handles.
6. PendingDecision/DecisionResponse validation is state-backed Rules validation, not a
   validator over the stored public domain. B2 needs both: current Rules validation at capture/replay
   time and a storage-level structural membership check.
7. The exact-pair static closure source does not itself enumerate every generic public producer:
   blocker declaration, AssignDamage, and several structured families need a targeted behavioral
   witness or an exact pinned Environment/Deck/Config proof. The pinned skipMulligans=true
   configuration is a scoped proof for non-skipped mulligan only; policy non-visit is never proof.
8. TrainingCorpusFiles is whole-corpus, non-streaming persistence; JsonlSelfPlaySink is a
   mutable append sink with outcome back-patching. Neither provides immutable bounded shards,
   deterministic manifest enumeration, or checksum-gated reads.

None of these gaps authorizes a Rules, card, deck, observation, decision, or replay semantic change
in this task. The first two are the smallest reusable follow-ups that must be accepted before
Trajectory V1 implementation can start; the replay-source harness wiring is a contract, not a
second replay implementation.

## PRODUCTION_CHANGES_REQUIRED=

Only the later implementation plan may make the following additive changes:

- add EpisodeClosureV1 metadata at the Gym lifecycle boundary;
- expose a versioned, transport-free public observation projection and domain canonicalizer without
  changing current TrainingObservation wire or StateDigest semantics;
- add a semantic action/response projection and domain membership validator;
- add a replay verification cursor/adapter around the existing V5 replay path;
- add Trajectory V1 contracts, per-episode writer, dataset publisher/manifest, reader, and
  quarantine under the existing trainer/data boundary;
- add a neutral VerifiedReplayFrameSource interface in :gym; compose the game-server implementation
  and gym-trainer writer/publisher only in a later integration/acceptance harness;
- add exact-pair closure/acceptance tests and a bounded generation harness that consumes the
  accepted external public policy.

The implementation must not:

- serialize or expose raw GameState;
- use native AI, AutoPay, first/sorted candidate selection, hidden state, or missing-domain repair;
- change LegalActionEnumerator, card definitions, locked decks, Rules handlers, public observation
  semantics, PendingDecision semantics, CompactReplay reconstruction semantics, or existing
  replay versions;
- introduce a fixed gigantic action space;
- select PPO/R2D2/AlphaZero or any final ML objective;
- make distributed/cloud execution a B2 dependency.

If EpisodeClosureV1, the replay cursor, or any reachable family requires one of those changes,
implementation stops at a separately reviewed generic follow-up.

## IMPLEMENTATION_TASKS=

All tasks below are future work. No task was implemented by this audit.

### Task A0 — Add the generic episode-closure boundary

Status=NOT_STARTED

RED characterization/tests:

- Add EpisodeClosureContractTest with terminal, draw, horizon interruption, explicit caller
  cancellation, unsupported diagnostic, public-choice rejection, and post-action observation
  failure cases.
- The RED assertion must show that current terminated/truncated plus exception text cannot
  distinguish INTERRUPTED from FAILED, and that a committed Rules transition followed by a
  failed observation is not falsely published as a clean terminal step.

Files/contracts affected:

- Create gym/src/main/kotlin/com/wingedsheep/gym/EpisodeClosure.kt.
- Modify additively gym/src/main/kotlin/com/wingedsheep/gym/GameEnvironment.kt,
  GameGymEnv.kt, StepResult.kt, and EpisodeDiagnostics.kt.
- Test gym/src/test/kotlin/com/wingedsheep/gym/GameEnvironmentTest.kt,
  GameGymEnvStrictExecutionTest.kt, and the new closure contract test.

Regressions:

TrainingObservation.terminated, truncated, winnerId, StateDigest, strict action
membership, B0 diagnostic counters, and legacy GameEnvironment.step behavior must remain
unchanged.

Acceptance:

A typed closure is available without raw exception/card/private-state leakage; terminal facts come
from Rules, interruption has an explicit reason and no reward, failure is non-publishable, and the
accepted B0 harness can classify post-action observation failures without guessing.

This is a prerequisite gate. If it cannot land without changing existing semantics, stop B2.

### Task A1 — Version the transport-free public observation projection

Status=NOT_STARTED

RED characterization/tests:

- Add TrajectoryObservationProjectionTest.
- Change action handles and pending decision nonces while keeping semantic fields fixed; the
  transport-free projection and its digest must remain equal.
- Change a visible semantic field, candidate payload, or schema identity; the projection/digest
  must change.
- Keep the same YES_NO domain and all other fields fixed; changing
  pendingDecision.sourceEntityId or pendingDecision.triggeringEntityId must change
  PlayerObservationV1 and semanticDecisionId, while changing prompt, sourceName, or effectHint
  must not.
- Reuse the existing hidden-hand, hidden-library, face-down, and authorized-reveal fixtures to
  assert that the durable bytes contain no hidden entity/card identity.
- Decode an unknown durable projection version and assert fail-closed behavior.

Files/contracts affected:

- Create gym/src/main/kotlin/com/wingedsheep/gym/contract/PlayerObservationV1.kt.
- Harden ObservationCanonicalizer.kt with an explicit projection version and a public
  boundary-level operation; preserve StateDigest.kt output unless a separately versioned change
  is approved.
- Test ObservationCanonicalizationTest.kt, StateDigestTest.kt, ObservationPrivacyTest.kt,
  TrainingObservationTest.kt, and the new projection test.

Regressions:

Current wire round-trips, schema hash behavior, public privacy, generated ability-key equality,
structured-domain equality, and B1 semantic trajectory hashes.

Acceptance:

PlayerObservationV1 is derived only from TrainingObservation, has no transport IDs, raw state,
legalActions, or pending structuredDomain copy, retains all public semantic fields required by
current B0 including sourceEntityId and triggeringEntityId, and has an explicit fail-closed schema
identity. Presentation-only prompt/sourceName/effectHint changes do not alter it.

### Task A2 — Add the complete-domain canonicalizer and digest

Status=NOT_STARTED

RED characterization/tests:

- Add CandidateDomainDigestTest.
- Prove membership, semantic payload, and Rules-significant order mutations change the digest.
- Prove equivalent unordered source inputs converge only through the public producer's canonical
  order before storage, and a raw noncanonical producer order is rejected rather than sorted by
  the trajectory digest.
- Prove duplicate semantic candidates, duplicate relation members, malformed blocker
  multiplicity, missing required maps, unknown domain kinds, and future component versions reject.
- Cover target, attack, blocker, V5 payment, target-payment, color, X, modes, card selection,
  reorder, combat resolution, and replacement fixtures.

Files/contracts affected:

- Create gym/src/main/kotlin/com/wingedsheep/gym/contract/CandidateDomainDigest.kt.
- Extract or harden semantic action/domain helpers in
  gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationCanonicalizer.kt.
- Test the existing contract tests plus the new digest test.

Regressions:

StateDigest, public action order, V2 attack order, blocker requirement multiplicity, PaymentDomain
V5 source/bucket order, target-payment binding order, and privacy semantics.

Acceptance:

The complete domain is always stored beside its 64-hex digest; digest computation uses no
actionId, decisionId, runtime ability handle, unstable toString, or hidden state, and an
unsupported producer cannot be made trusted by sorting or truncating it.

### Task A3 — Add semantic replay-prefix and chosen-action identity

Status=NOT_STARTED

RED characterization/tests:

- Add SemanticDecisionIdentityTest.
- Replay the same public trace with fresh action IDs, decision IDs, continuation nonces, generated
  ability suffixes, and session IDs; semantic decision IDs and chosen semantic payloads must match.
- Keep semanticEpisodeId, replay prefix, public observation, and complete domain fixed while
  changing policy provenance and collectionJobId; semanticDecisionId must remain equal, while the
  collection provenance remains distinct.
- Change prefix history, actor, decision kind, domain membership/order, schema, or public state;
  the correct semantic identity must change or the record must reject.
- Submit a partial structured payload, raw AutoPay, an unknown field, or an action not in the
  stored domain; the validator must fail before publication.

Files/contracts affected:

- Create gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/SemanticReplayInput.kt
  and gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/SemanticDecisionIdentity.kt.
- Reuse gym/src/main/kotlin/com/wingedsheep/gym/contract/VerifiedReplayFrame.kt for the
  transport-free replay output; do not add a second replay frame model.
- Reuse the public TrainingObservation, LegalActionView, ActionPayloadRequirements, and
  existing strict submission validators.
- Test the new identity contract and current action/domain tests.

Regressions:

ReplayDecisionNonceCanonicalizationTest, ReplayFingerprintV3Test, exact-pair policy
transport-independence, payment-plan strictness, combat declaration strictness, and B1 choice
trajectory comparisons.

Acceptance:

Every durable decision has a stable semantic ID and a full chosen semantic payload. Freshness
tokens remain live-only and never become an ML label.

### Task A4 — Expose one verified replay-to-public-observation cursor

Status=NOT_STARTED

RED characterization/tests:

- Add ReplayTrajectoryVerificationTest against the two accepted V5 exact-pair replay cases.
- Demonstrate that current ReplayReconstructor.reconstructStateAt/viewer reconstruction alone
  does not prove every public decision boundary and complete domain.
- Mutate the initial checkpoint, a middle checkpoint, the tail checkpoint, one action, one
  structured response, and one public domain; each must stop verification at the first failure.
- Remove the tail or use an unknown replay version; no partial-success result may be returned.

Files/contracts affected:

- Create gym/src/main/kotlin/com/wingedsheep/gym/contract/VerifiedReplayFrame.kt with the
  transport-free frame, VerifiedReplayVerification, and VerifiedReplayFrameSource contracts
  shared by Gym consumers.
- Harden game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayReconstructor.kt
  with a forward verified-frame callback or adapter; do not change CompactReplay V5 semantics.
- Add game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/GymReplayFrameSource.kt
  implementing the :gym source interface and the narrow implementation dependency on :gym. The
  adapter is bound to one CompactReplay and calls the existing ObservationBuilder and public
  ObservationPerspective.
- Add an integration-only construction test showing that a bound GymReplayFrameSource is passed
  through B2GenerationHarness to the writer/publisher without a game-server to gym-trainer
  production dependency.
- Do not add a dependency from :game-server to :gym-trainer.
- Keep the adapter boundary free of raw GameState once it emits a public frame.

Regressions:

CompactReplayReconstructionTest, ReplayPrefixDeterminismTest,
ReplayDecisionNonceCanonicalizationTest, ReplayFutureVersionFallbackTest,
ReplayDurabilityTest, all V5 payment replay tests, and the exact-pair replay/privacy gates.

Acceptance:

One forward pass proves ReplayFidelity.EXACT, initial/middle/tail checkpoints, every public frame,
every complete domain, every semantic decision identity, and the final closure. UNVERIFIED and
DIVERGED never feed the trusted writer. The source is supplied through the neutral :gym interface;
B2GenerationHarness invokes it before the writer can finalize an episode or the publisher can
finalize a dataset.

This is the second prerequisite gate. If the only implementation requires a second environment or
raw-state persistence, stop and review the generic replay boundary.

### Task A5 — Define typed Trajectory V1 contracts and pure validation

Status=NOT_STARTED

RED characterization/tests:

- Add TrajectoryV1ContractTest.
- Round-trip one decision, one terminal episode, one interrupted episode, and a failed/quarantined
  episode through the typed contract.
- Cover variable flat domains, structured pending domains, payment plans, attack declarations,
  blocker declarations, X, modes, card selection, ordering, replacement, and combat resolution.
- Reject raw GameState fields, unknown versions, missing complete domains, missing chosen payloads,
  duplicate decisions, and terminal flags that disagree with closure.

Files/contracts affected:

- Create gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1.kt.
- Create typed EpisodeMetadataV1, PolicyProvenanceV1, CompactReplayLinkV1,
  CompleteLegalDomainV1, DecisionRecordV1, ChosenSemanticActionV1, ChosenSemanticResponseV1,
  ValidatedEpisodeV1, DatasetMetadataV1, DatasetManifestV1, and TrajectoryValidationResult there.
- Reuse gym/src/main/kotlin/com/wingedsheep/gym/EpisodeClosure.kt for EpisodeClosureV1; do not
  define a second closure type in gym-trainer.
- Use the public Gym serializers and no Rules GameState serializer.

Regressions:

Existing ai.training serialization tests remain green and are not relabeled as B2 acceptance.
Current Gym wire DTOs and game-server replay DTOs remain backward compatible.

Acceptance:

The contract contains every required semantic field, no raw internal state, no live freshness
label, complete domains rather than hashes, factual closure, explicit policy provenance, and
fail-closed future-version behavior.

### Task A6 — Implement transactional bounded-shard writer and quarantine

Status=NOT_STARTED

RED characterization/tests:

- Add TrajectoryV1WriterTest.
- Interrupt while writing an episode before episode-end; reader must reject it.
- Corrupt one byte, alter one line ending, change one manifest count, exceed the shard/episode
  bound, duplicate an episode ordinal, and write conflicting episode content; publication must fail.
- Drive B2GenerationHarness with a fake source that returns UNVERIFIED, DIVERGED, an incomplete
  range, or a mismatching public frame; the harness must not call a trusted writer finalization
  path and must quarantine the episode.
- Interrupt between staging validation and final publication; no final dataset directory may
  appear. Simulate a filesystem without atomic directory move support; publication must fail
  closed rather than moving only the manifest or overwriting a prior collection.
- Fail a decision with an unsupported diagnostic, public-choice rejection, missing domain, or replay
  divergence; only quarantine evidence may remain.
- Repeat the same collection job with the same content and verify deterministic bytes; repeat it with
  a conflicting payload and verify conflict rejection.
- Publish multiple complete episodes with distinct semanticEpisodeId and collectionJobId values;
  the single DatasetManifestV1 and datasetId must contain all of them without conflating their
  per-episode identities.

Files/contracts affected:

- Create gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Writer.kt,
  TrajectoryV1Publisher.kt, TrajectoryV1Manifest.kt, and TrajectoryV1Quarantine.kt.
- Reuse the atomic staging discipline from ai/.../TrainingCorpusFiles.kt without whole-file
  append or replacement.
- Add writer/publisher tests under gym-trainer/src/test/kotlin/.../trajectory/.
- Use a fake :gym VerifiedReplayFrameSource in harness/publisher orchestration tests and reserve
  the concrete game-server GymReplayFrameSource wiring for the integration/acceptance harness.

Regressions:

No calls to the writer from the strict transition hot path may change B1 step latency or semantic
trajectory hashes. Existing JsonlSelfPlaySink behavior remains unchanged and is not promoted.

Acceptance:

Only VALIDATED complete shards enter a deterministic DatasetManifestV1. The harness supplies the
replay-verification binding before the writer finalizes an episode; the writer and publisher never
claim replay verification without the supplied ReplayVerificationBindingV1. Shards are immutable,
bounded, checksum-addressed, LF-canonical, and safely published. Failed/partial/conflicting
artifacts are quarantined and never listed.

### Task A7 — Implement strict streaming reader and manifest validator

Status=NOT_STARTED

RED characterization/tests:

- Add TrajectoryV1ReaderTest.
- Reject unknown dataset-manifest, trajectory, and component versions before yielding records.
- Reject missing, extra, reordered, duplicated, truncated, or checksum-mismatched shards.
- Verify deterministic manifest enumeration independent of filesystem order.
- Verify the final dataset-DATASET_ID directory, DatasetManifestV1.datasetId,
  manifestContentDigest, and the per-episode semanticEpisodeId/collectionJobId/trajectoryId
  index.
- Stream multiple variable-size episodes and preserve complete domains and closure kinds.
- Reject a duplicate or conflicting semantic episode identity.

Files/contracts affected:

- Create gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Reader.kt
  and TrajectoryV1ManifestValidator.kt for DatasetManifestV1 as well as episode records.
- Add reader/quarantine tests under the same trajectory test package.

Regressions:

Existing JSON serialization defaults, current replay codec version handling, and the Python-facing
portable JSON expectation must remain compatible.

Acceptance:

The reader validates the manifest and every shard before yielding any sample, streams without
loading the corpus, exposes full domains and semantic identities, and distinguishes terminal,
interrupted, failed, and quarantined outcomes.

### Task A8 — Close decision families with behavioral evidence

Status=NOT_STARTED

RED characterization/tests:

- Add a Trajectory V1 closure test that combines the current static definition scan, public-domain
  producer inventory, accepted B0 dynamic telemetry, and B2 serialized family counts.
- Make the test fail when a statically reachable or public-producer family has neither a behavioral
  record from a targeted legal witness nor an exact pinned Environment/Deck/Config proof;
  policy non-visit and historical telemetry absence must fail the test.
- Add explicit probes for CastWithFlashback, CHOOSE_MODE, REORDER_LIBRARY,
  DeclareBlockers, DISTRIBUTE, ORDER_OBJECTS, SPLIT_PILES, CHOOSE_REPLACEMENT,
  BUDGET_MODAL, COMBAT_RESOLUTION, SELECT_MANA_SOURCES, and ASSIGN_DAMAGE, plus an explicit
  pinned skipMulligans=true proof for non-skipped mulligan. No non-skipped mulligan behavioral
  probe is required for the pinned B2 environment.
- Add an unknown-family fixture that fails closed.

Files/contracts affected:

- Add the closure test/harness under gym/src/test/kotlin/com/wingedsheep/gym/ or the approved
  trajectory test package.
- Harden only the generic public-domain inventory/trajectory validator; do not silently extend the
  B0 policy or alter current Rules producers in this task.
- Keep the accepted EnvironmentV1ExactPairAcceptanceTest.kt as prerequisite evidence and record
  B2 additions separately.

Regressions:

The exact-pair static closure gate, public policy no-engine-state boundary, payment/combat domain
contracts, and B0 first-gap stop behavior.

Acceptance:

Every reachable family has a behavioral record from a targeted legal witness that is replay-verified
or an explicit pinned Environment/Deck/Config unreachable proof. No family is considered covered by
an enum, source-name match, policy non-visit, or telemetry absence alone.

### Task A9 — Run the bounded B2 generation and final gate

Status=NOT_STARTED

RED characterization/tests:

- Add a bounded TrajectoryV1AcceptanceTest using the accepted B0 external policy and locked
  Akiri/Chevill input contract.
- Cover both roster orientations, both starting-player positions, the accepted seed/policy
  identity axes, all reachable B0 families, true terminal episodes, explicit interrupted
  episodes, and a deliberately failed/quarantined episode.
- Replay every trusted episode across its complete declared range and compare every stored public
  observation/domain/semantic decision/chosen payload.
- Mutate a domain, choice, nonce, action handle, replay tail, checksum, and hidden identity and
  assert the correct exact failure/quarantine reason.

Files/contracts affected:

- Create the bounded B2 acceptance test and report artifact under the existing Gym/game-server
  acceptance boundaries.
- Use the approved trajectory writer/reader and replay adapter; do not start a trainer or fit a
  model.
- Add the integration-only B2GenerationHarness in that acceptance source set or a thin equivalent
  composition root; it may depend on :gym-trainer and :game-server while neither production
  source set depends on the other.
- Wire the concrete game-server GymReplayFrameSource to the gym-trainer writer/publisher through
  the neutral ReplayVerificationBindingSource result in the integration-only B2GenerationHarness.
  The harness may depend on both modules; neither module's production source set may depend on the
  other.
- Update this design report only in a later acceptance task; this audit commit remains
  documentation-only.

Regressions:

All B0 trust invariants, all B1 performance/semantic trajectory contracts, current Gym/replay
regressions, hosted CI, and independent final-diff review.

Acceptance:

Only after every B2 final criterion is independently green may a later roadmap task change the
trust status. This task itself must leave DATA_TRUSTED=NO.

## TEST_MATRIX=

The implementation plan must retain the following matrix. NOT_RUN is the correct status for all
rows in this audit-only commit.

| # | Proof | Required negative/positive case | Primary future test |
| ---: | --- | --- | --- |
| 1 | Identity-role audit | Current IDs classified and raw mixed identities rejected | IdentityRoleAuditTest |
| 2 | Single decision round-trip | One priority and one folded decision | TrajectoryV1ContractTest |
| 3 | Complete episode round-trip | Terminal episode with all metadata | TrajectoryV1ContractTest |
| 4 | Variable domain shapes | Flat, target, payment, combat, structured, and empty-valid choices | CandidateDomainDigestTest |
| 5 | Structured pending decisions | Targets, cards, modes, distribution, ordering, replacement, budget, combat | TrajectoryV1ContractTest |
| 6 | Payment action/response | V5 source, production, pool, allocation, and target-bound payment | SemanticDecisionIdentityTest |
| 7 | Attack declaration | Ordered attacker/defender/band domain and chosen map | AttackDeclarationDomainContractTest plus B2 closure test |
| 8 | True terminal | Rules gameOver and factual winner/draw | EpisodeClosureContractTest |
| 9 | Interrupted | Horizon/cancellation with no winner/reward | EpisodeClosureContractTest |
| 10 | Failed/quarantined | Diagnostic, exception, public rejection, replay divergence | TrajectoryV1WriterTest |
| 11 | Policy provenance separation | Distinct behavior/opponent roles and policy RNG identity affect collectionJobId but not semanticEpisodeId or semanticDecisionId | TrajectoryV1ContractTest and SemanticDecisionIdentityTest |
| 12 | Semantic decision stability | Same replay with fresh nonces/handles | SemanticDecisionIdentityTest |
| 13 | Freshness independence | Action/decision/session/generation changes do not alter durable identity | SemanticDecisionIdentityTest |
| 14 | Domain digest recomputation | Stored complete domain recomputes exactly | CandidateDomainDigestTest |
| 15 | Domain mutation | Membership, semantic payload, and Rules order mutation | CandidateDomainDigestTest |
| 16 | Corrupt/incomplete shard | Truncated line, missing footer, partial manifest | TrajectoryV1ReaderTest |
| 17 | Unknown future schema | Future trajectory/domain/replay/component version | TrajectoryV1ReaderTest |
| 18 | Checksum/content digest | Shard, manifest, episode, observation, and domain mismatch | TrajectoryV1ReaderTest |
| 19 | Chosen-not-in-domain | Unknown candidate, invalid target/order/payment/response | TrajectoryV1ContractTest |
| 20 | Unsupported/fallback quarantine | Typed diagnostics and native fallback | TrajectoryV1WriterTest |
| 21 | Privacy | Hidden hand/library/face-down IDs and names absent from stored bytes and digests | TrajectoryPrivacyTest |
| 22 | Same replay | Same pinned replay reconstructs the same semantic trajectory | ReplayTrajectoryVerificationTest |
| 23 | Complete range | Initial frame, every action boundary, final tail; no skipped tail | ReplayTrajectoryVerificationTest |
| 24 | Deterministic shards | Same episode ordinals and bytes independent of filesystem order | TrajectoryV1WriterTest and TrajectoryV1ReaderTest |
| 25 | Duplicate conflict | Same collectionJobId with conflicting payload rejected; the same semanticEpisodeId across distinct policy jobs is not conflated | TrajectoryV1ReaderTest |
| 26 | Pending semantic context | Same YES_NO domain plus different sourceEntityId or triggeringEntityId changes PlayerObservationV1 and semanticDecisionId; prompt/sourceName/effectHint changes do not | TrajectoryObservationProjectionTest and SemanticDecisionIdentityTest |
| 27 | Episode versus dataset identity | Multiple episodes have distinct semanticEpisodeId/collectionJobId/trajectoryId values but one deterministic DatasetManifestV1 and datasetId | TrajectoryV1WriterTest and TrajectoryV1ReaderTest |
| 28 | Replay integration seam | Harness passes a complete EXACT verification inside a ReplayVerificationBindingV1 from the game-server source before writer finalization, with no production cross-dependency | ReplayTrajectoryVerificationTest and TrajectoryV1WriterTest |

The surrounding regression set must include:

~~~text
gym:
  ObservationCanonicalizationTest
  StateDigestTest
  ObservationPrivacyTest
  TrainingObservationTest
  GameGymEnvStrictExecutionTest
  payment/target/attack/blocker domain contract tests
  EnvironmentV1ExactPairAcceptanceTest prerequisite gates

game-server:
  CompactReplayReconstructionTest
  ReplayPrefixDeterminismTest
  ReplayDecisionNonceCanonicalizationTest
  ReplayFingerprintV3Test
  ReplayCodec/replay-version/durability tests
  V5 payment and combat replay tests

existing offline data:
  DecisionTrainingRecordTest
  TrainingCorpusValidatorTest
  EclTrainingInfrastructureTest
~~~

## Final status

~~~text
IDENTITY_ROLE_AUDIT=PASS (audit complete; no implementation gate run)
REUSE_AUDIT=PASS (reuse decisions documented; no code reuse change made)
SEMANTIC_DECISION_IDENTITY=SMALL_GENERIC_PRIMITIVE_REQUIRED
FRESHNESS_TOKEN_SEPARATION=DESIGN_ONLY
CANDIDATE_DOMAIN_DIGEST=SMALL_GENERIC_PRIMITIVE_REQUIRED
TRAJECTORY_SCHEMA_VERSIONED=DESIGN_ONLY
WRITER_ATOMICITY=DESIGN_ONLY
READER_VALIDATION=DESIGN_ONLY
IMMUTABLE_FINALIZED_SHARDS=DESIGN_ONLY
CHECKSUM_INTEGRITY=DESIGN_ONLY
UNKNOWN_VERSION_FAIL_CLOSED=DESIGN_ONLY
PLAYER_OBSERVATION_ONLY=DESIGN_ONLY
PRIVACY_TRAJECTORY_AUDIT=NOT_RUN
COMPLETE_LEGAL_DOMAIN_STORED=DESIGN_ONLY
CHOSEN_IN_DOMAIN=DESIGN_ONLY
POLICY_PROVENANCE=DESIGN_ONLY
DATASET_MANIFEST_IDENTITY=DESIGN_ONLY
REPLAY_VERIFIER_INTEGRATION=HARNESS_SEAM_DESIGN_ONLY
CLOSURE_TAXONOMY_PRESERVED=DESIGN_ONLY
REPLAY_TO_TRAJECTORY_RECONSTRUCTION=BLOCKED_UNTIL_A4
COMPLETE_REPLAY_VERIFICATION=NOT_RUN
DECISION_FAMILY_CLOSURE=NOT_RUN
QUARANTINE=DESIGN_ONLY
B0_TRUST_INVARIANTS_PRESERVED=YES (no production change)
B1_PERFORMANCE_CONTRACT_PRESERVED=YES (no production change)
B2_FINAL_ACCEPTANCE=NOT_RUN
PLAN_REVIEW=CHANGES_APPLIED_PENDING_INDEPENDENT_DELTA_REVIEW

B2_IMPLEMENTATION_AUTHORIZED=NO
DATA_TRUSTED=NO
~~~

This commit is intentionally the standalone audit/design artifact. It does not open C0, does not
generate a trusted dataset, and does not implement Task A0 or any later task.
