# C1_07 — Live Policy Execution and Structured Inference Totality

~~~text
TASK=C1_07_LIVE_POLICY_EXECUTION_AND_STRUCTURED_INFERENCE_TOTALITY_DESIGN
DATE=2026-09-15
CLASSIFICATION=DESIGN_AND_ARCHITECTURE_AUDIT_ONLY
REPOSITORY=chrismaghuhn/argentum-engine
DESIGN_BRANCH=chris/c1-07-live-policy-execution-design-20260915
BASE_ORIGIN_MAIN=03bce91dea0502d74c7af2a1bab5acc2e43f1fa2
UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872
ORIGIN_URL=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM_URL=https://github.com/wingedsheep/argentum-engine.git
~~~

This is the reviewed design artifact for C1_07. It does not implement
production gameplay, an ML controller, a Python worker, a new GameSession, a
new gameplay protocol, training, trajectory generation, RL, self-play, or an
Arena button. The design branch stops after its exact-SHA review handoff. Its
final commit and pushed remote SHA are reported outside this document because
this document is itself the committed artifact.

## 1. Decision summary

The smallest trustworthy future topology is:

~~~text
server-owned fixed Arena profile
  -> existing locked Akiri/Chevill TournamentLobby
  -> existing TournamentMatchHandler/GameSession
  -> one coherent perspective-safe live Gym/C1 snapshot
  -> alias-only C1 model-facing request
  -> long-lived local Python runtime
  -> Python ScoreProvider + Selection V2 + PolicyTieRng V1
  -> selected source-binding ordinal
  -> JVM revalidation against the exact current domain
  -> existing GameSession action/DecisionResponse path
  -> existing broadcast/spectator/replay lifecycle
~~~

The repository already supplies most semantic ingredients:

* ObservationBuilder builds TrainingObservation from GameState and applies the
  existing perspective and projected-state rules.
* PlayerObservationV1 removes legal-domain transport from that observation.
* CompleteLegalDomainV1 retains action candidates, folded decision options, or
  a typed structured domain.
* C1ModelFacingProjectionV1 performs the accepted alias and role projection
  for offline C1 samples.
* GameSession.executeAction is the existing authoritative transition path.
* ChosenSemanticActionV1 and ChosenSemanticResponseV1 validate semantic
  membership without making the model authoritative for legality.
* Python already provides ScoreProvider, InferenceContext, InferenceRuntime,
  Selection V2, PolicyTieRng V1, strict checkpoint manifests, and Safetensors
  verification.

The current end-to-end live seat is nevertheless not executable safely:

~~~text
LIVE_MODEL_INPUT_PARITY=PARTIAL
C1_00_STRUCTURED_INFERENCE_TOTALITY=NO
FLAT_EXECUTION_TOTALITY=NO
STRUCTURED_EXECUTION_TOTALITY=NO
ML_POLICY_SEAT_IMPLEMENTATION_AUTHORIZED=NO
~~~

FLAT_EXECUTION_TOTALITY=NO means end-to-end live Arena execution. The Python
flat selector is total within its validated exact-bindable request shape; the
repository has no live GameSession adapter or runtime bridge that can supply
that shape and commit its result.

## 2. Verified repository status

### 2.1 Git and remote verification

The required checks were run before design work:

~~~text
git fetch origin       = PASS
git fetch upstream     = PASS
origin                 = https://github.com/chrismaghuhn/argentum-engine.git
upstream               = https://github.com/wingedsheep/argentum-engine.git
root checkout branch   = main
root checkout status   = DIRTY, unrelated user changes preserved
isolated worktree      = CLEAN before this document
~~~

The root checkout contained:

~~~text
M  rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/stack/StackResolver.kt
?? .superpowers/
~~~

Those paths were not touched. The isolated worktree is:

~~~text
C:\Users\chris\.config\superpowers\worktrees\argentum-engine\c1-07-live-policy-execution-design-20260915
~~~

It was created from origin/main after the fetch. The expected prompt SHA
6a4f0a1eb306ab3af8d5111a43e2547246be4b60 is present as the first parent of
current origin/main; origin/main advanced by merged PR #199.

~~~text
origin/main = 03bce91dea0502d74c7af2a1bab5acc2e43f1fa2
origin/main subject = Merge pull request #199 from chrismaghuhn/chris/c1-06-feed-forward-gpu-smoke-20260915
origin/main parents = 6a4f0a1eb306ab3af8d5111a43e2547246be4b60 6a0d682931913862022d577088dd691f31738d88
upstream/main = 3f46367d87c88bcf156a843a9e69fd29e1693872
PR #200 merge ancestor check = PASS
~~~

### 2.2 C1_00 through C1_06

The current roadmap status was checked against repository history, accepted
evidence, and the current #124 status sync. Implementation PRs are on
origin/main; acceptance is not inferred from a green local test alone.

| slice | current source / PR | verified state |
| --- | --- | --- |
| C1_00 | PR #184, merge dfdc10c3e5f86523fb79737f57ddfee3f0e466a6 | Final acceptance recorded as YES. Supplies the local C1 contract foundation; training remains NO. |
| C1_01 | PR #185, merge 4af111cad62d0f268f4d28e2548fbb23a18a9a47 | Characterization/admission slice accepted. No production Teacher was admitted by the negative bootstrap characterization. |
| C1_02 | PR #186, merge b9eb8da182390095d73b9c06d9bbe20049156e9b | Public-observation Teacher implementation accepted as a bounded contract; structured inference remains NO. |
| C1_03/A/B/C | PRs #187, #189, #190, #191, #193; readmission merge 9cd9314dcf62497014b930ef6bf50c1db78be5bf | Readmission and limited flat reference bootstrap accepted. ACTION_CANDIDATES and FOLDED_DECISION_OPTIONS are admitted; structured families are not. |
| C1_04 | PR #194, merge f1766919524da248f17a35a853465f3d21b19aff | Local PyTorch/Safetensors/Trackio tooling boundary accepted. Manifest remains semantic authority. |
| C1_05 | PR #197, head 0786fc1fdec8ce36e72cdae79b62455e777a5f7c, merge 888dca8d8e413d041cd4384f2d80e3d33faecf76 | Source-bound label sidecar and accepted C1_05 artifact are recorded as accepted. |
| C1_06 | PR #199, head 6a0d682931913862022d577088dd691f31738d88, merge 03bce91dea0502d74c7af2a1bab5acc2e43f1fa2 | Merged; all listed Hosted CI jobs pass; coverage job skipped. Independent exact-SHA/code-review acceptance is not established. |

The current #124 status sync explicitly records:

~~~text
C1_00_FINAL_ACCEPTANCE_PASS=YES
C1_01_FINAL_ACCEPTANCE_PASS=YES
C1_02_FINAL_ACCEPTANCE_PASS=YES
C1_03_C1_03A_C1_03B_C1_03C_ACCEPTED=YES
C1_04_FINAL_ACCEPTANCE_PASS=YES
C1_05_FINAL_ACCEPTANCE_PASS=YES
C1_06_CODE_REVIEW_PASS=NO
C1_06_FINAL_ACCEPTANCE_PASS=NO
~~~

Source: [issue #124](https://github.com/chrismaghuhn/argentum-engine/issues/124)
status sync from 2026-09-15. Issues #124, #137, and #188 are still OPEN.

### 2.3 C1_06 checkpoint authority

The C1_06 report and PR #199 provide a read-only contract reference:

~~~text
CHECKPOINT_ID=f4b191d99734af66a5643c988ce3c2f2198a2957be24e2a717287006a43124c5
CHECKPOINT_SOURCE_COMMIT=943338abbaf47f289cfe606acd50caf0a2b15ef5
CHECKPOINT_WEIGHT_CONTENT_DIGEST=02027b495f609a268b2d6d169250a66cdaab5f8658c4794d1e2669b484ea0168
CHECKPOINT_MANIFEST_CONTENT_DIGEST=973231cc16f8de28f618889b94cabe6ac12da67485a246207db16c8c902ed9a3
MODEL_ARCHITECTURE_ID=argentum-ml-c1-06-feed-forward-candidate-scorer@v1
MODEL_CONFIG_DIGEST=542b74694061b07c8adc397e27ea3a57b71edaaa33af24b05b99099d95d3c966
REQUIRED_NUMERIC_PROFILE_CLASS=C1_REFERENCE_NUMERIC_PROFILE
SELECTION_CONTRACT=argentum-ml-policy-selection@v2
POLICY_RNG_CONTRACT=argentum-ml-policy-tie-rng@v1
~~~

The physical checkpoint path in the report is local temporary storage, not a
repository artifact. No weights.safetensors is committed. Hosted CI is evidence
for the merged implementation, not authorization for live gameplay.

### 2.4 Baseline verification

The clean design worktree was checked with:

~~~text
just ml-check = PASS
Python 3.13 compileall for ml/src and ml/tests = PASS
~~~

No model was executed, no checkpoint was loaded, no trajectory was generated,
and no training or data materialization was started.

## 3. Scope, invariants, and vocabulary

The target is one server-owned first Research Arena mode:

~~~text
Akiri seat    = accepted ML Policy profile/checkpoint
Chevill seat  = existing forced Engine AI
observer      = spectator only
decks         = existing exact locked 100-card Commander sources
rules         = existing normal GameSession + Rules path
stats         = recordDurableStats=false
~~~

Not in this task:

* Human-vs-ML, ML-vs-ML, or reverse orientation.
* A second rules engine, action generator, GameSession, observation schema, or
  WebSocket gameplay protocol.
* A Python inference server exposed over the Internet.
* A heuristic, random, first-choice, or Engine-AI fallback for an ML failure.
* Recurrent training, RL, self-play, planning, pacing, or new trajectories.
* Changes to Commander semantics, locked deck bytes, replay semantics, or
  GameSession code.
* A client-selected checkpoint, model architecture, model path, or controller.

The codebase-design terms used below are deliberate:

* The live model-facing adapter is a module with a small interface and a deep
  implementation.
* The integration point is a seam between server-owned state and the model
  runtime.
* The Python process and JVM wrapper are adapters at that seam.
* A candidate ordinal is an address in one request, never semantic identity.
* An exact source action/response is the semantic binding that only Argentum
  may execute.

## 4. Current authority graph

### 4.1 Offline C1 graph

~~~text
TrajectoryV1 / A7 trust
  -> gym-trainer C1LearnerArtifactMaterializer.materialize()
  -> C1ModelFacingProjectionV1.project()
  -> immutable manifest.json + samples.ndjson
  -> Python DerivedArtifactReader.open()
  -> ValidatedDerivedSample
  -> VariableDomainItem / VariableDomainBatch
  -> InferenceRequest.from_validated_sample()
  -> InferenceRuntime.select()
  -> ScoreProvider.score()
  -> Selection V2
  -> PolicyTieRng V1 for unresolved exact ties
  -> ExactSemanticSourceBinding
~~~

Concrete ownership:

| owner / file | method or type | input | output | lifecycle / failure behavior |
| --- | --- | --- | --- | --- |
| gym-trainer/.../C1LearnerArtifactMaterializer.kt | materialize | published TrajectoryV1 dataset, output directory, source commit, materializer config | immutable derived artifact | A7 validates source; staged canonical bytes are atomically published; duplicate output and validation failures abort. |
| gym-trainer/.../C1ModelFacingProjectionV1.kt | project | owned TrajectoryV1, owned DecisionRecordV1, projection context, partition | C1DerivedSampleV1 | Requires non-terminal/non-truncated observation and exactly one chosen semantic action/response; creates aliases, input, binding, target, provenance. |
| gym/.../ObservationBuilder.kt | build | GameState, perspective player, Rules LegalAction list | ObservationResult containing TrainingObservation, registry, diagnostics | Applies visibility and projected-state rules; unsupported action/structured domains become diagnostics. |
| gym/.../CandidateDomainDigest.kt | CompleteLegalDomainV1.from | TrainingObservation | complete action/folded/structured domain | Rejects inconsistent shape, unsupported domain version, duplicate semantics, and missing structured domains. |
| ml/.../derived_reader.py | DerivedArtifactReader.open, iter_validated_samples_for_inference | fixed manifest and NDJSON files | reader-issued ValidatedDerivedSample | Strict canonical bytes, counts, digests, cross-field membership, privacy, and version checks; failures close the path. |
| ml/.../variable_batch.py | VariableDomainItem, VariableDomainBatch | immutable model input and candidate feature views | variable-width transport with masks | Preserves all supplied candidates; no truncation/top-k; structured items cannot carry flat candidates. |
| ml/.../inference/runtime.py | InferenceRequest.from_validated_sample | ValidatedDerivedSample and VariableDomainItem | reader-bound InferenceRequest | Rejects transport drift, wrong order, missing source fields, and structured input without approved selector. |
| ml/.../inference/runtime.py | InferenceRuntime.select | request, ScoreProvider, PolicyTieRngStateV1 | SelectionResult with exact source binding | Requires matching checkpoint/profile; rejects structured input, provider errors, bad score count, non-finite scores, and Selection errors. |
| ml/.../selection/selection_v2.py | select_v2 | exact candidates, scores, masks, semantic discriminator, RNG state | exact binding and RNG cursor result | Unique max and valid semantic tie use no RNG; unresolved exact tie uses only PolicyTieRng V1. |

### 4.2 Existing live Arena graph

~~~text
AiTournamentController
  -> LobbyHandler.createAiTournamentFromCurriculumPreset()
  -> CurriculumPresetService.loadValidated()
  -> server-owned CurriculumAiTournamentPreset
  -> TournamentLobby
  -> TournamentMatchHandler.startSingleMatch()
  -> normal GameSession.startGame()
  -> GamePlayHandler.broadcastStateUpdate()
  -> AiGameManager.createController()/wireAiForGame()
  -> AiWebSocketSession
  -> ClientStateTransformer + LegalActionInfo + raw PendingDecision
  -> EngineAiPlayerController
  -> GamePlayHandler.handleAiAction()
  -> GameSession.executeAction()
  -> existing spectator/replay/stats lifecycle
~~~

Concrete live owners:

| owner / file | method or type | input | output | lifecycle / persistence behavior |
| --- | --- | --- | --- | --- |
| game-server/.../curriculum/CurriculumDeckSource.kt | CurriculumAiTournamentPreset, CurriculumDeckSourceLoader.load | fixed request identity and repository-relative source paths | validated exact source and digest | Source paths are code-owned; traversal, malformed rows, and wrong Commander declarations fail. |
| game-server/.../curriculum/CurriculumPresetService.kt | loadValidated | fixed preset | two validated Commander sources plus provenance | Deck validation happens before lobby/AI identity creation. |
| game-server/.../handler/LobbyHandler.kt | createAiTournamentFromCurriculumPreset | validated preset | public fixed-deck TournamentLobby | Sets Commander, exact decks, immutableFixedDeckSource=true, recordDurableStats=false, engineAiOnly=true. |
| game-server/.../handler/TournamentMatchHandler.kt | startSingleMatch | lobby, tournament, round, match | normal GameSession | Removes one commander copy at the Rules boundary, persists seat markers, wires AI seats, and invokes normal startGame. |
| game-server/.../session/GameSession.kt | startGame, executeAction, createStateUpdate | player sessions/decks or GameAction | authoritative state transition and client update | stateLock protects transitions; successful actions are replay-recorded and paused actions expose PendingDecision. |
| game-server/.../ai/AiGameManager.kt | createController, wireAiForGame | AI id, optional LLM override, optional forceEngine | Engine or LLM controller plus AiWebSocketSession | Current factory is global-mode/force-engine driven and creates an Engine fallback for LLM. |
| game-server/.../ai/AiWebSocketSession.kt | handleStateUpdate, handleActionsOnlyFallback | server messages, ClientGameState, LegalActionInfo, raw pending decisions | callback carrying GameAction or SubmitDecision | Asynchronous virtual WebSocket; delta cache misses and pending decisions have fallback behavior. |
| game-server/.../handler/GamePlayHandler.kt | handleAiAction, safeFallbackActions, processAutoPassLoop | AI callback action and GameSession | broadcast or fallback transition | Current rejected-AI path tries cancel/pass/empty combat declarations; forbidden for ML. |
| game-server/.../handler/SpectatingHandler.kt and SpectatorAdmissionPolicy.kt | handleSpectateGame, restoreSpectating, canSpectate | authenticated identity and game id | spectator registration/state | Public flag or authoritative lobby/member relation controls admission; session id is not a bearer capability. |

The current all-engine curriculum uses engineAiOnly=true and is correct for
ARENA_01. It cannot be copied for ML-vs-Engine because
TournamentMatchHandler.startSingleMatch currently combines lobby.engineAiOnly
with every player's forceEngine marker. A mixed profile must be per-seat and
must leave the existing all-engine path unchanged.

## 5. Live observation and legal-domain authority graph

### 5.1 Reusable primitives

The existing Gym path is the correct source of model-facing semantics:

~~~text
GameState + acting seat
  -> ObservationBuilder.build()
      -> projected characteristics + Visibility
      -> TrainingObservation
      -> PlayerObservationV1.from()
      -> CompleteLegalDomainV1.from()
      -> CandidateDomainDigestV1.from()
      -> C1 alias/role projection
~~~

Important details:

* ObservationBuilder.build reads entity characteristics from
  state.projectedState, not base card components, and uses the single Gym
  visibility implementation.
* It supplies only the actor's legal actions. A non-acting perspective gets
  no legal action list.
* It maps current pending decisions into a typed structured domain when the
  builder knows how to do so.
* It rejects unsupported action target, attack, and blocker projections
  through diagnostics rather than silently shortening the domain.
* CompleteLegalDomainV1.from binds the candidate list or typed structured
  domain to the observation and rejects shape drift.
* C1ModelFacingProjectionV1 maps runtime entity ids to contiguous entity-N
  aliases and maps player ids to SELF/OPPONENT. Its model input does not
  contain raw engine ids.

### 5.2 Why the current live path is only partial

GameSession owns the correct LegalActionEnumerator, LegalActionEnricher,
EngineServices, GameState, and stateLock, but its public live methods expose
only:

* getClientState(playerId) -> ClientGameState, produced by
  ClientStateTransformer;
* getLegalActions(playerId) -> List<LegalActionInfo>, a presentation view;
* createStateUpdate, which sends a client message with raw decision
  serialization and client-oriented fields.

AiWebSocketSession therefore does not receive TrainingObservation,
PlayerObservationV1, CompleteLegalDomainV1, or a C1 alias projection.
LegalActionInfo retains a raw GameAction, opaque presentation fields, and the
client enrichment shape. Reconstructing C1 input from it would duplicate the
authority graph and could disagree with ObservationBuilder.

GameSession.getStateSnapshot() is an existing raw GameState getter used by the
Engine AI. It is not a sufficient live policy interface because it is not a
coherent state-plus-legal-domain certificate, is not the model-facing privacy
boundary, and can race a transition if paired with independent enumeration.

The smallest correct generic primitive is a server-owned,
lock-coherent LivePolicyDecisionSnapshot. Its model-facing portion should
contain only:

~~~text
observationDigest
PlayerObservationV1 or its C1 alias projection
CompleteLegalDomainV1 / model-facing domain
CandidateDomainDigestV1
stable policy seat index
~~~

The internal portion may retain the exact current LegalAction/registry or
DecisionResponse object needed to execute the selected semantic binding, but
that portion must never cross the Python seam. The snapshot is built from the
same GameState, LegalAction list, and generation under the GameSession lock.
Inference happens after releasing the lock; the selected result is revalidated
against a fresh locked snapshot before execution.

This extraction must be shared by offline and live paths where possible. The
current model-facing input projection is in gym-trainer, while game-server
depends on gym and must not depend on gym-trainer. The generic alias/model-input
projection should therefore be extracted to a dependency-safe gym seam;
trajectory source/target/provenance materialization stays in gym-trainer.

~~~text
LIVE_MODEL_INPUT_PARITY=PARTIAL
classification=LIVE_MODEL_INPUT_PRIMITIVE_MISSING
required_property=exact same projection semantics offline and live
forbidden_fix=GameSession raw state -> ad hoc ML JSON
~~~

## 6. Current C1 inference graph

### 6.1 Python contract modules

| module | concrete method/type | input | output | current limitation for live use |
| --- | --- | --- | --- | --- |
| ml/src/argentum_ml/checkpoint/manifest.py | ArgentumCheckpointManifestV1.from_path, validate_weight_file | regular manifest/weight files | immutable validated manifest / digest check | No server-owned profile registry or live process loader. |
| ml/src/argentum_ml/inference/provider.py | ScoreProvider | immutable model input and candidate feature views | one score per supplied candidate | C1_06 raw Torch module is not yet an adapter implementing it. |
| ml/src/argentum_ml/inference/runtime.py | InferenceContext.from_checkpoint | validated manifest and numeric profile | checkpoint-bound context | Requires manifest compatibility with Selection V2 and PolicyTieRng V1. |
| ml/src/argentum_ml/inference/runtime.py | InferenceRequest.from_validated_sample | ValidatedDerivedSample and VariableDomainItem | reader-issued request | Factory is offline-reader-issued; it cannot consume a live snapshot. |
| ml/src/argentum_ml/inference/runtime.py | InferenceRuntime.select | request, provider, RNG state | exact SelectionResult | Rejects every structured_domain with C1_00_STRUCTURED_INFERENCE_TOTALITY=NO. |
| ml/src/argentum_ml/selection/selection_v2.py | select_v2 | exact source bindings and scores | exact binding, ordinal audit, RNG cursor | Correctly avoids physical-row preference; no JVM equivalent exists. |
| ml/src/argentum_ml/selection/policy_tie_rng.py | PolicyTieRngStateV1 | 32-byte stream key and cursor | immutable next state | Correct state exists only in Python; live persistence/carriage is absent. |
| ml/src/argentum_ml/data/variable_batch.py | VariableDomainItem | model input, flat or structured transport | immutable transport | Structured transport is retained but not selectable by current runtime. |
| ml/src/argentum_ml/contracts/model_facing.py | require_model_input and structured validators | model-facing JSON | validated model-facing shape | Accepts twelve structured versions but does not make scoreable alternatives. |

SourceSelectionBindings.from_derived_binding_channel also proves that
non-structured source bindings are injective, ordinal-complete, and tied to
producer data. Its exact-source factory rejects an action candidate with
non-empty requiredPayloadFields; a structured action template is not an
executable semantic action.

### 6.2 C1_06 model

ml/src/argentum_ml/learner/c1_06.py contains:

~~~text
C1_06ModelConfigV1.reference()
  observation_width=32
  candidate_width=32
  hidden_width=128
  hidden_layers=2
  dtype=float32

FeedForwardCandidateScorer
  stateless shared MLP
  one scalar per supplied candidate

tensorize_samples
  hashes canonical model input and candidate feature JSON
  uses candidate mask from candidate.get("affordable", true)

require_cuda_device
  requires CUDA and returns cuda:0
  does not fall back to CPU
~~~

The model can technically hash any canonical JSON candidate feature, but that
is not execution support for an unvalidated structured response. The accepted
C1_06 run used only the bounded TRAIN/VALIDATION subset of the accepted flat
C1_05 label sidecar:

~~~text
TRAIN_DECISIONS_USED=2048
VALIDATION_DECISIONS_USED=512
TRAINING_EXAMPLES_PROCESSED=6400
TEST_ROWS_CONSUMED=0
TEACHER_CALLS=0
NEW_LABELS_GENERATED=0
~~~

No C1_06 ScoreProvider wrapper, live request decoder, selection worker, or
JVM bridge exists in the repository.

## 7. Structured-decision totality audit

### 7.1 Current typed domain inventory

StructuredDecisionDomain.kt and ObservationBuilder.buildPendingDecision
currently publish:

~~~text
targets@v2
card-selection@v1
mode-selection@v1
distribution@v1
ordering@v1
split-piles@v1
search-library@v1
reorder-library@v1
combat-resolution@v1
mana-sources@v3
replacement@v1
budget-modal@v1
~~~

ChosenSemanticResponseV1 has membership validators for all twelve. That is a
valuable execution validator, not a policy selector. It proves that a complete
response can be checked against a complete domain after a future selector
produces one.

The current ObservationBuilder decision split is:

~~~text
flat/folded action registry:
  YesNoDecision
  BatchYesNoDecision (folded to yes/no whole-run responses)
  ChooseNumberDecision
  single-mode ChooseModeDecision
  ChooseColorDecision
  ChooseOptionDecision
  single-select unordered SelectCardsDecision

typed structured domain:
  multi-mode ChooseModeDecision
  ChooseReplacementDecision
  multi-select SelectCardsDecision
  ChooseTargetsDecision
  DistributeDecision
  OrderObjectsDecision
  SplitPilesDecision
  SearchLibraryDecision
  ReorderLibraryDecision
  AssignDamageDecision (structured flag but no typed domain)
  CombatResolutionDecision
  SelectManaSourcesDecision (only for a subset of payment shapes)
  BudgetModalDecision
~~~

Two concrete gaps are already visible:

1. AssignDamageDecision is marked structured=true but structuredDomain is
   omitted. CompleteLegalDomainV1.from cannot create a complete structured
   domain for it.
2. SelectManaSourcesDecision.manaSourcesDomain returns null for non-declinable
   payments, Waterbend selections, and composite Ward costs. Pending-payment
   publication is deliberately partial.

BatchYesNoResponse is handled by the Gym MCTS fold and ObservationBuilder, but
is not in the six-type foldedResponseTypes allowlist in
C1ModelFacingProjectionV1. It was not present in the accepted Akiri/Chevill C1
artifact. If it becomes reachable, it must receive an explicit versioned
projection or fail closed; it must not be treated as an ordinary YesNoResponse.

### 7.2 Action-level structured payloads

Many policy choices are fields on a Rules-enumerated action template, not a
PendingDecision. ActionPayloadRequirements publishes this stable field order:

~~~text
targets
xValue
paymentStrategy
additionalCostPayment
costPayment
alternativePayment
manaColorChoice
damageDistribution
crewCreatures
saddleCreatures
repeatCount
graveyardLifeCost
chosenModes
modeTargetsOrdered
attackers
bands
blockers
orderedBlockers
~~~

The action candidate is legal, but it is not executable by itself when one of
these fields is required. GameGymEnv.step(actionId, actionPayload) is the
existing strict external adapter for this shape: it copies the registered
template, overlays explicit payload, verifies target/attack/blocker/mana/
payment/candidate membership, then executes through
GameEnvironment.stepFromCandidateStrict.

This is reusable validation knowledge, but not a live GameSession adapter or a
C1 selector. The current C1 source-binding factory rejects these templates
because requiredPayloadFields is non-empty. Historical Akiri/Chevill data:

~~~text
C1_00_EXACT_BINDABLE_FLAT_ROWS=59211
C1_00_UNBINDABLE_FLAT_ROWS=54556
CANDIDATES_REQUIRING_STRUCTURED_ACTION=71643
CANDIDATES_WITH_TARGET_DOMAIN=1406247
CANDIDATES_WITH_PAYMENT_DOMAIN=9166
CANDIDATES_WITH_REPEAT_COUNT_DOMAIN=1438
CANDIDATES_WITH_ATTACK_DOMAIN=352
CANDIDATES_WITH_BLOCKER_DOMAIN=0
~~~

Therefore a model score for a base CastSpell or ActivateAbility template
cannot be submitted as the chosen spell, target, payment, or combat declaration.

### 7.3 No giant global action space

The accepted direction is not one Cartesian product of:

~~~text
spell x modes x X x targets x sacrifices x payment sources x production
  x allocation x combat maps x orderings
~~~

The preferred future shape is:

~~~text
existing explicit engine decision boundary
  -> publish complete legal choices
  -> score choices without adding hidden legality
  -> submit one semantic choice/response
  -> Rules continuation advances
  -> publish the next boundary
~~~

Where the engine exposes one atomic action template with several caller-filled
fields, a generic staged structured-choice module is required only if it can
prove every partial prefix is Rules-valid and perspective-safe, every complete
response is reachable exactly once, no payload is silently defaulted, response
order cannot alter semantic score association, the final source binding is
injective, and the staged representation preserves the action's meaning.

Until that proof exists, the action is unsupported and the ML seat stops. The
existing RandomStructuredResolver in gym-trainer/search/AlphaZeroSearch.kt is a
search/test resolver that creates one forced edge. It is not a candidate
scoring contract and cannot be reused as an ML fallback.

## 8. Reachable decision-family matrix

The historical C1_03/C1_05 artifact is the only accepted Akiri/Chevill runtime
evidence available without generating new trajectories. It proves observed
families, not all future branches. Static curriculum counts are potential only.
No unobserved family is promoted to supported by name alone.

| family | reachability evidence in current sources | training label coverage | model input encoding | complete current domain | exact source binding | Selection V2 totality | live execution today |
| --- | --- | --- | --- | --- | --- | --- | --- |
| targetless ACTION_CANDIDATES | Observed extensively in accepted artifact | YES for admitted exact-bindable flat rows | YES, C1 model-facing candidate JSON | YES | YES when no required payload | YES in Python | NO, live snapshot/runtime missing |
| FOLDED_DECISION_OPTIONS | 2,149 decisions in accepted artifact | YES for admitted folded rows | YES | YES for current folded types | YES through ChosenSemanticResponseV1 | YES in Python | NO, live snapshot/runtime missing |
| action template with target/payment/combat payload | 71,643 structured-required candidates; target/payment/attack fields observed | NO; unbindable rows excluded from C1_05 labels | Template/domain encoding exists, not a complete chosen response | Domain fields exist; exact combinations are not a selector | NO in current C1 binding factory | NO | NO |
| targets@v2 pending response | 61 decisions, all typed NO_LABEL | NO | YES typed domain projection | YES for current TargetsDomain | Validator YES; alternative enumeration NO | NO | NO |
| card-selection@v1 multi-select | 185 decisions, all typed NO_LABEL | NO | YES typed domain projection | YES for current CardSelectionDomain | Validator YES; alternative enumeration NO | NO | NO |
| mode-selection@v1 multi-mode | Static Akiri potential count 2; no accepted runtime row | NO | YES typed domain projection | YES for current domain | Validator YES; selector NO | NO | UNPROVEN |
| distribution@v1 | Static potential count 0; no accepted runtime row | NO | YES typed domain projection | YES for current domain | Validator YES; selector NO | NO | UNPROVEN |
| ordering@v1 / trigger order | Static intrinsic count 0; dynamic trigger ordering not proven | NO | YES typed domain projection | YES for current ordering domain | Validator YES with semantic ordering references | NO | UNPROVEN |
| split-piles@v1 | Static potential count 0; no accepted runtime row | NO | YES typed domain projection | YES for current domain | Validator YES; selector NO | NO | UNPROVEN |
| search-library@v1 | Static potential count 4 Akiri / 6 Chevill; no accepted runtime row | NO | YES typed domain projection | YES for current search domain | Validator YES; selector NO | NO | UNPROVEN |
| reorder-library@v1 | Static potential count 0; no accepted runtime row | NO | YES typed domain projection | YES for current reorder domain | Validator YES; selector NO | NO | UNPROVEN |
| combat-resolution@v1 | Static damage potential; accepted artifact count 0 | NO | YES typed domain projection | YES when domain is published | Validator YES; selector NO | NO | UNPROVEN |
| mana-sources@v3 pending payment | Static potential count 43; accepted artifact count 0 | NO | YES for published payment domain | PARTIAL by explicit builder guards | Validator YES for V3 plan | NO | NO |
| replacement@v1 | Static potential count 1; no accepted runtime row | NO | YES typed domain projection | YES for current relation | Validator YES; selector NO | NO | UNPROVEN |
| budget-modal@v1 | Static potential count 0; no accepted runtime row | NO | YES typed domain projection | YES for current domain | Validator YES; selector NO | NO | UNPROVEN |
| AssignDamageDecision | No exact-pair card evidence; engine family exists | NO | NO complete structured domain | NO; builder omits domain | NO complete binding | NO | NO |
| BatchYesNoDecision | Not observed in accepted locked-pair artifact | NO separate C1 coverage | PARTIAL; folded projection allowlist omits response type | Folded whole-run options exist in Gym | PARTIAL | NO separate proof | UNPROVEN; fail closed if reached |
| X, commander-zone, confirmation/may | X has zero static pair count; confirmation maps to folded YesNo/ChooseOption when emitted; no dedicated commander domain | Only folded admitted types | Folded yes; action-level X no live binding | Folded yes; action payload varies | Folded yes; action payload no | Folded yes; payload no | UNPROVEN beyond observed rows |

The definite observed structured families are:

~~~text
PENDING_TARGETS
PENDING_CARD_SELECTION
ACTION_TARGET_PAYLOAD
ACTION_PAYMENT_PAYLOAD
ACTION_REPEAT_COUNT_PAYLOAD
ACTION_ATTACK_PAYLOAD
~~~

Mode, X, block, damage, combat resolution, mana sources, search, reorder,
ordering, replacement, confirmation, and commander-zone replacement remain
potential or branch-dependent and are not promoted to execution support.

This is the required separation:

~~~text
MODEL_WAS_TRAINED_ON_FAMILY
  != MODEL_CAN_SCORE_A_JSON_FEATURE
  != DOMAIN_HAS_TRUSTED_COMPLETE_RUNTIME_ENCODING
  != LIVE_EXECUTION_IS_SUPPORTED
~~~

## 9. Controller ownership analysis

### 9.1 Current fields

| current field | current meaning | why it is insufficient for ML |
| --- | --- | --- |
| PlayerIdentity.isAi | broad non-human/AI marker used by lobby, UI, stats, recovery | Cannot distinguish Engine AI from ML Policy. Useful as compatibility projection, not semantic authority. |
| PlayerIdentity.forceEngine | force built-in Engine AI despite global LLM configuration | False does not mean ML Policy; current false path selects global LLM. |
| PlayerIdentity.aiModelOverride | mutable per-seat LLM model override | Model name is not checkpoint identity; arbitrary name must never select ML runtime. |
| TournamentLobby.engineAiOnly | all AI seats in locked ARENA_01 use Engine AI | Lobby-wide and cannot represent one ML seat plus one Engine seat. |
| AiGameManager.createController | chooses Engine or LLM from global config/force marker | Creates an Engine fallback for LLM and has no ML branch. |
| AiWebSocketSession | virtual WebSocket carrying client-state prompts to AiPlayerController | Raw client DTOs and explicit heuristic/pass fallbacks are unsuitable for ML. |

### 9.2 Recommended minimal controller seam

Introduce a small server-owned, versioned seat descriptor after C1_06 final
acceptance:

~~~text
ControllerKind
  HUMAN
  ENGINE_AI
  ML_POLICY
~~~

The source of truth is ControllerKind. isAi remains a compatibility projection:

~~~text
isAi = (controllerKind != HUMAN)
~~~

For old persisted rows without the new field:

~~~text
isAi=false                       -> HUMAN
isAi=true and forceEngine=true  -> ENGINE_AI
isAi=true and forceEngine=false -> existing legacy AI/LLM path, never ML by inference
~~~

There is no migration from an LLM model name to ML_POLICY. A future ML profile
is created only through a server-owned profile factory.

The new runtime interface should not extend AiPlayerController. That interface
is coupled to ClientGameState, raw PendingDecision, mulligan messages, and
draft operations, while ai cannot depend on gym without creating the wrong
dependency direction. A separate deep module at the game-server/Gym seam should
own:

~~~text
PolicySeatRuntime
  start / health
  decide(LivePolicyDecisionSnapshot)
  commit(selection, exact current snapshot)
  fail(reason)
  shutdown
~~~

Engine AI and LLM keep their current AiWebSocketSession path. ML Policy gets an
adapter that consumes only the safe live snapshot and returns a selected
source-binding address, never a fabricated raw GameAction from Python.

ControllerKind must be consulted by:

* TournamentMatchHandler when wiring seats;
* GamePlayHandler when handling an automated response;
* processAutoPassLoop, so ML is never silently auto-passed before policy sees
  the complete domain;
* recovery, cleanup, status, and statistics compatibility code.

For ENGINE_AI, current fallback and auto-pass behavior remains unchanged. For
ML_POLICY, every unsupported or failed decision freezes/fails the research
match; it never enters safeFallbackActions, handleActionsOnlyFallback, or an
LLM Engine fallback.

## 10. Python/JVM runtime options

| option | authority | latency/startup | verification | failure/lifecycle | portability/security/testability | decision |
| --- | --- | --- | --- | --- | --- | --- |
| Python process per decision | Python owns one call | high process startup/import cost | repeated checkpoint load or unsafe cache | many crash/timeout races; no stable runtime state | easy prototype, poor determinism and portability | REJECT |
| long-lived local Python subprocess with framed stdin/stdout | JVM owns launch/profile; Python owns model scoring/selection | one startup; low per-call framing cost; bounded queue | verify manifest/weights once, then match profile on every request | explicit health, timeout, shutdown, crash, in-flight correlation | no network surface, fixed command, deterministic test double, Windows/Linux portable | RECOMMENDED |
| localhost-only inference HTTP service | JVM/operator owns a separate service | one startup; HTTP overhead and deployment coordination | service repeats exact manifest/profile checks | service discovery/restart/port ambiguity | debuggable, but larger network/config surface | DEFERRED ALTERNATIVE |
| JVM-native Torch/Safetensors implementation | JVM owns everything | potentially low after load | new loader and numeric-equivalence proof | JVM failure is server failure | no Python dependency, but no current loader and high risk | REJECT for first live seat |
| cloud/remote API | external provider owns execution | network latency/availability | cannot bind accepted local checkpoint semantics | remote failure and privacy exposure | violates local-only first-seat boundary | REJECT |

### Recommended runtime

Use one long-lived local Python worker per server-owned ML profile, or one
profile worker with a bounded serialized queue for the first single-match mode.
Start it through a fixed ProcessBuilder argument vector without a shell. The
command is server configuration, never client input. The worker loads the exact
profile checkpoint once and reports typed health.

The worker must import optional learner dependencies only inside the worker,
load a validated ArgentumCheckpointManifestV1, verify manifest and weight
digests, instantiate the exact C1_06 architecture/config, wrap it in a
ScoreProvider, run InferenceRuntime/Selection V2/PolicyTieRng V1, accept only
versioned framed requests, and never open TrajectoryV1, C1_05 data, raw
GameState, or source directories. It has no Internet inference path.

The JVM adapter owns request correlation, current-domain revalidation, timeout,
process health, match failure closure, and controller persistence. Python owns
score computation and accepted selection.

## 11. Live inference request and response contract

The exact wire schema must be a new versioned live contract. This is the minimum
safe shape; model features, selection binding, and operational envelope remain
separate.

### 11.1 Request

~~~json
{
  "version": 1,
  "schemaIdentity": "argentum-ml-live-policy-request@v1",
  "requestId": "transport-correlation-only",
  "profileIdentity": "server-owned-profile-id",
  "checkpointId": "f4b191d99734af66a5643c988ce3c2f2198a2957be24e2a717287006a43124c5",
  "inferenceContractIdentity": "argentum-ml-inference@v1",
  "selectionContractIdentity": "argentum-ml-policy-selection@v2",
  "policyRngContractIdentity": "argentum-ml-policy-tie-rng@v1",
  "numericProfileClass": "C1_REFERENCE_NUMERIC_PROFILE",
  "policyRngState": {
    "streamKeyHex": "32-byte-key-as-64-lowercase-hex",
    "cursor": 0
  },
  "observationDigest": "current-observation-digest",
  "candidateDomainDigest": "current-domain-digest",
  "modelInput": {
    "decisionContext": {},
    "observation": {},
    "domain": {}
  },
  "candidates": [
    {
      "sourceBindingOrdinal": 0,
      "featureView": {},
      "present": true,
      "executableSupport": true
    }
  ],
  "selectionBindingChannel": {
    "kind": "alias-only-source-binding-certificate",
    "completeLegalDomain": {},
    "sourceBindingOrdinals": [0],
    "semanticTieDiscriminators": {}
  }
}
~~~

Rules:

* requestId is correlation only, not semantic decision id.
* profile/checkpoint/contracts/numeric profile/RNG state are envelope authority,
  not model features.
* modelInput is the exact C1 model-facing
  decisionContext/observation/domain projection with roles and aliases, not
  raw EntityId values.
* candidates are transport records. The worker passes only featureView to
  ScoreProvider; ordinal, presence, executable support, and bindings remain
  control data.
* the binding channel is alias-only and may be used by Python Selection V2 for
  validation and tie handling. It cannot contain raw GameState, raw EntityId
  values, object references, paths, PIDs, or debug reveal fields.
* the JVM keeps the raw exact source mapping locally. Python need not return a
  raw action.
* the server supplies the complete current domain unchanged. The worker cannot
  add, delete, reorder, or repair candidates.

### 11.2 Response

~~~json
{
  "version": 1,
  "schemaIdentity": "argentum-ml-live-policy-response@v1",
  "requestId": "same-transport-correlation-only",
  "profileIdentity": "server-owned-profile-id",
  "checkpointId": "f4b191d99734af66a5643c988ce3c2f2198a2957be24e2a717287006a43124c5",
  "selectedSourceBindingOrdinal": 0,
  "scoredCandidateCount": 1,
  "scoreVectorDigest": "optional-internal-diagnostic-digest",
  "rngCursorBefore": 0,
  "rngCursorAfter": 0,
  "rngDrawCount": 0
}
~~~

The local response may carry a score vector in test diagnostics, but the server
must not expose raw scores or model features to spectators. Production needs
only selected binding address, count, checkpoint identity, and RNG evidence.
It never contains a fabricated GameAction, client action id, raw object id, or
filesystem path.

### 11.3 JVM commit sequence

~~~text
1. Build one lock-coherent LivePolicyDecisionSnapshot.
2. Compute observation/domain digests and alias-only model input.
3. Send one request outside GameSession.stateLock.
4. Validate response envelope and requestId.
5. Re-enter the GameSession-authoritative seam.
6. Recompute or compare exact current observation/domain digest.
7. Reject a stale response; do not retry it as a different action.
8. Require selected ordinal in the exact current source domain.
9. Map ordinal to the JVM-held exact LegalAction/DecisionResponse.
10. Validate semantic membership and required payload completeness.
11. Execute through existing GameSession.executeAction().
12. Commit returned PolicyTieRng cursor only with the successful action.
13. Persist controller state and broadcast through existing lifecycle code.
~~~

Selection V2 keeps one owner while Argentum remains the only transition
authority. A valid ordinal with a stale digest is rejected.

## 12. Checkpoint provisioning and verification

### 12.1 Server-owned profile

The first profile should be code-owned and immutable at the semantic level:

~~~text
profileIdentity
  argentum-mtg-ml-akiri-vs-engine-chevill@v1

binds:
  controllerKind=ML_POLICY
  logicalSeat=AKIRI
  expectedCheckpointId=f4b191d99734af66a5643c988ce3c2f2198a2957be24e2a717287006a43124c5
  expectedManifestContentDigest=973231cc16f8de28f618889b94cabe6ac12da67485a246207db16c8c902ed9a3
  expectedWeightContentDigest=02027b495f609a268b2d6d169250a66cdaab5f8658c4794d1e2669b484ea0168
  expectedArchitecture=argentum-ml-c1-06-feed-forward-candidate-scorer@v1
  expectedConfigDigest=542b74694061b07c8adc397e27ea3a57b71edaaa33af24b05b99099d95d3c966
  expectedInferenceContract=argentum-ml-inference@v1
  expectedSelection=argentum-ml-policy-selection@v2
  expectedPolicyRng=argentum-ml-policy-tie-rng@v1
  expectedNumericProfile=C1_REFERENCE_NUMERIC_PROFILE
  expectedC1_06SourceCommit=943338abbaf47f289cfe606acd50caf0a2b15ef5
~~~

The profile name is illustrative until its follow-up freezes the identity; the
authority rules are not optional.

### 12.2 Physical artifact

The operator supplies the physical artifact through a configured local
checkpoint directory or content-addressed artifact cache:

~~~text
configured artifact locator
  -> regular manifest.json
  -> regular weights.safetensors
  -> expected profile identity
~~~

The locator is operational configuration, not controller identity. It is not
stored in lobby/game semantic provenance. The loader rejects symlinks and unsafe
parents, resolves only below the configured root, parses canonical manifest
bytes, validates the semantic checkpoint id, checks manifest and weight digests,
and validates architecture, config, implementation/source commit, source
dataset, training recipe/run, inference, selection, RNG, and numeric profile.
It refuses latest aliases, arbitrary client ids, URLs, and unpinned aliases.

Missing or mismatched artifacts fail before a live lobby is created. The profile
does not silently select another checkpoint.

## 13. Numeric execution profile

Current C1_06 evidence establishes:

~~~text
training device=cuda:0
dtype=float32
CUDA kernel probe=PASS
all model/tensor locations=cuda:0
CPU fallback used=NO
~~~

The model can be instantiated with CPU tensors in dependency-safe tests, but
no accepted cross-device score-equivalence or CPU gameplay certification
exists. The strict manifest binds a profile class, not a complete device
certificate.

Therefore:

~~~text
CPU_GAMEPLAY_INFERENCE_AUTHORIZED=NO
FIRST_LIVE_RUNTIME_PROFILE=C1_REFERENCE_NUMERIC_PROFILE on certified cuda:0
cross-device deterministic claim=NO
~~~

The future runtime must report and validate numeric profile contract identity,
profile class, model dtype, Torch implementation/version, CUDA runtime/version,
device class/index, and deterministic algorithm setting. Hardware/device strings
are operational provenance, not controller id, checkpoint id, or RNG seed.

## 14. PolicyTieRng and determinism

Selection V2 and PolicyTieRng V1 remain Python-owned. The server adds no second
argmax or tie breaker.

For a live policy instance:

~~~text
policySeed = explicit persisted live-policy seed
seatIndex  = fixed match roster index, not runtime EntityId or active-player order
stream     = PolicyTieRng V1 derivation from policySeed + seatIndex
state      = streamKey + cursor, carried across policy decisions
~~~

The first implementation should use a versioned explicit seed derivation. The
least surprising source is the authoritative per-game replay setup seed already
returned by GameInitializer and stored in ReplaySetup, combined with the
server-owned profile identity and fixed roster seat index. The derivation
identity must be frozen before implementation and must not copy legacy A9
policy-seed semantics implicitly.

The same checkpoint/profile, public model-facing input sequence, complete legal
domains, fixed seat index, PolicyTieRng identity/state, and numeric profile must
reproduce the same selected semantic choices under the declared same-profile
determinism claim.

Selection handling:

* unique maximum: cursor unchanged;
* valid semantic discriminator tie: cursor unchanged;
* unresolved exact tie: only Selection V2 consumes words and returns the
  post-draw cursor;
* stale/rejected action: cursor is not committed as a successful choice;
* unknown in-flight response after process failure: match fails closed rather
  than guessing whether the draw/action committed.

## 15. Persistence and restart authority

### 15.1 Current gap

PlayerIdentity, GameSession.PlayerPersistenceInfo, PersistentPlayerInfo, and
PersistentLobbyPlayer currently persist only:

~~~text
isAi
aiModelOverride
forceEngine
~~~

PersistentGameSession has no policy profile/checkpoint/RNG state. Current
recovery calls AiGameManager.rehydrateAiIdentity and later
GamePlayHandler.rewireAiForRecoveredGame; that is correct for Engine/LLM AI but
can restore only broad AI markers.

RedisGameRepository.save catches persistence exceptions and continues with its
in-memory cache. That best-effort behavior is unsafe if an ML cursor or
controller descriptor changed but was not durably stored.

### 15.2 Required persisted authority

Add one versioned ControllerAuthorityV1 value per automated seat, carried in
both lobby and game persistence:

~~~text
controllerKind
profileIdentity              null for HUMAN/ENGINE_AI
checkpointId                 required for ML_POLICY
inferenceContractIdentity   required for ML_POLICY
numericProfileClass          required for ML_POLICY
policyRngContractIdentity    required for ML_POLICY
policySeedBitsHex             required once game starts
policySeatIndex               required once game starts
policyRngState { streamKeyHex, cursor }  required for ML_POLICY
~~~

Exact invariants:

~~~text
HUMAN:
  all automated-controller fields null

ENGINE_AI:
  no ML checkpoint/profile/RNG fields
  legacy forceEngine may remain only for compatibility

ML_POLICY:
  profile/checkpoint/contracts/numeric profile/seat/RNG state all present
  profile registry resolves to same expected checkpoint
~~~

Do not persist filesystem paths, PIDs, CUDA ordinal, mutable latest aliases, or
runtime process ids as semantic identity.

### 15.3 Atomic commit requirement

The future GameSession seam must commit successful exact policy selection,
successful authoritative transition, new policy RNG state, and controller
runtime revision as one persistence unit. A repository save failure becomes a
typed ML policy persistence failure and blocks further ML actions.

The policy cursor is not added to ReplaySetup or the semantic replay action
contract. Replay remains an exact input/state-transition record. Policy
provenance is a separate controller-runtime persistence channel.

On restart:

1. decode and validate the controller descriptor;
2. validate the server-owned profile against the exact checkpoint;
3. restore the exact RNG stream/cursor;
4. verify the persisted game/lobby seat agrees;
5. start the fixed worker runtime;
6. rewire the ML seat as ML_POLICY;
7. never infer ML from isAi=false, forceEngine=false, or a model name;
8. if authority is missing, preserve the seat kind and enter typed
   failed/blocked state rather than rewiring as Engine AI.

## 16. Failure and fallback policy

The first research mode is fail-closed. Failure does not produce a game result,
does not concede the ML seat, and does not ask Engine AI to act.

| failure | required behavior | RNG/action consequence |
| --- | --- | --- |
| checkpoint directory/file missing | reject profile launch before lobby/game creation; show typed operator error | no request, no RNG |
| manifest malformed, symlinked, wrong version, wrong profile, wrong checkpoint id | reject runtime/profile; no match start | no request, no RNG |
| manifest content or weight digest mismatch | reject runtime and preserve evidence | no request, no RNG |
| architecture/config/source-commit/contract mismatch | reject runtime; do not load another model | no request, no RNG |
| numeric profile/device mismatch | reject runtime; no CPU fallback | no request, no RNG |
| Python runtime unavailable | mark runtime unavailable and fail research match visibly | no Engine fallback |
| worker startup/import/model-load failure | typed ML_RUNTIME_STARTUP_FAILURE; no gameplay start | no action |
| request timeout | typed ML_INFERENCE_TIMEOUT; freeze/fail match | no semantic retry/fallback |
| malformed frame/response or wrong request id | typed ML_RESPONSE_MALFORMED; freeze/fail match | no action/cursor commit |
| wrong checkpoint/profile/contract in response | authority mismatch; freeze/fail match | no action/cursor commit |
| wrong score count or non-finite score | Python runtime rejects; JVM receives typed failure | no action/cursor commit |
| selected ordinal absent from exact current domain | JVM rejects response | no action/cursor commit |
| observation/domain digest stale | reject old binding and enter explicit failure/re-evaluation policy | no old cursor commit |
| structured family unsupported/incomplete | typed STRUCTURED_DOMAIN_UNSUPPORTED; freeze/fail match | no fallback |
| policy process crash before response is known | fail current research match; operator starts a new match | no ambiguous retry |
| policy process restart before a new match | allowed only after exact profile/RNG health validation | no semantic response replay |
| persistence write failure after transition | typed persistence failure; stop further ML actions | never continue with unknown cursor |
| GameSession rejects selected action | surface authoritative failure; no safe fallback; fail if no current policy decision can continue | no successful cursor commit |

The visible research status may be:

~~~text
RUNNING
WAITING_FOR_POLICY
POLICY_FAILED(reasonCode)
COMPLETE
~~~

POLICY_FAILED is a research/lifecycle closure, not a Magic winner/loss claim.
The integration must not call safeFallbackActions, submit
CancelDecisionResponse automatically, submit empty combat declarations,
auto-pay, use the first option, or call Engine AI for an ML seat.

## 17. Research Arena ML mode

### 17.1 Launch

Reuse the accepted ARENA_01/ARENA_02 topology:

~~~text
ResearchArenaPage
  -> fixed server-owned ML Arena command
  -> server resolves exactly one ML-vs-Engine profile
  -> CurriculumPresetService validates the same two source files
  -> TournamentLobby(PREMADE_DECKS, Commander, public, one game, stats false)
  -> TournamentMatchHandler.startSingleMatch
~~~

The client sends no checkpoint, model architecture, weight name, file path,
controller override, deck map, or seat map. A no-payload fixed-profile
operation is preferable. If the existing REST endpoint is reused, its
server-owned request value resolves to one immutable profile and all caller
overrides are rejected as in the current locked-preset path.

Mixed lobby:

~~~text
Akiri:
  ControllerKind.ML_POLICY
  server-owned accepted profile

Chevill:
  ControllerKind.ENGINE_AI
  existing EngineAiPlayerController

lobby.engineAiOnly:
  false for this mixed profile
~~~

The existing all-engine engineAiOnly=true preset remains unchanged.

### 17.2 Match and transition

TournamentMatchHandler still owns the match and creates one normal GameSession.
GamePlayHandler still owns broadcast, auto-pass coordination, game-over/replay/
stats lifecycle. The ML adapter only supplies an exact current action/response
to GameSession.executeAction.

The ML adapter must not use a second GameSession, ML-specific rules, a
ML-specific WebSocket, frontend legality, or raw GameState JSON.

The server may expose sanitized status:

~~~text
controller=ML_POLICY
profileIdentity
checkpointId
modelArchitectureIdentity
policyRuntimeStatus
~~~

It must not expose local artifact paths, worker PIDs, hidden observations,
candidate feature views, source bindings, or model diagnostics.

### 17.3 Spectator and replay

Reuse SpectatorAdmissionPolicy, SpectatingHandler, SpectatorStateBuilder,
SpectatorGameBoard, and the current spectator protocol. The spectator receives
the same perspective-safe board and masked hands as any other public Arena
match. Model input is never serialized into the spectator feed.

Replay semantics remain unchanged. The first ML mode does not publish training
trajectories as a side effect. Replay may retain its ordinary action log and
engine provenance; controller/checkpoint provenance is a separate sanitized
research status channel.

### 17.4 Statistics

Carry recordDurableStats=false through the existing lobby/game lifecycle. The
accepted MatchResultSink and TournamentResultSink guards already support
Research Arena suppression. A mixed ML-vs-Engine match must not create ordinary
account/tournament rows, including after recovery or policy failure closure.

## 18. Security and privacy review

No production code changes in this task, so no new implementation leak is
introduced. Future gates are explicit:

| risk | control |
| --- | --- |
| raw GameState reaches Python | request schema has no raw state field; live adapter uses Gym projection only |
| opponent hidden hand/library reaches Python | ObservationBuilder/Visibility and C1 projection are the only source; hidden cards remain omitted |
| hidden exile/debug reveal reaches Python | no debug transformer or raw PendingDecision crosses ML seam |
| spectator sees model input | spectator path remains SpectatorStateBuilder only; status is sanitized |
| checkpoint path leaks | path is operator configuration and never persisted/returned |
| arbitrary local file loading | profile-owned root, regular-file/no-symlink checks, no client path |
| arbitrary subprocess command | fixed server-owned argument vector, no shell, no client command |
| remote inference/network | first profile local-only; worker has no remote inference path |
| client forges controller | launch command has no controller fields; server profile owns kind |
| client forges checkpoint | profile and response checkpoint are server-bound |
| action id becomes semantic identity | ids remain routing handles; selected binding is revalidated |
| raw source ids enter model features | C1 alias/role projection removes runtime ids from modelInput |

Counters:

~~~text
P1=0
P2=0
P3=1
P3 detail=pre-existing spectator opaque-library handles from ARENA_01A;
        outside model request and not introduced by C1_07
~~~

P1/P2 are zero because no new security/privacy violation is introduced or
authorized. The existing P3 is retained as an explicit unrelated spectator
note, not silently promoted into model input.

## 19. Proposed RED / acceptance matrix

No row below is claimed as an existing ML live test. Existing evidence is named
only where a located test or accepted contract covers the same underlying
behavior. Every new RED must fail before its future implementation.

| case | existing evidence | new RED needed | blocker / acceptance condition |
| --- | --- | --- | --- |
| ML_SEAT_01 server-owned profile binds Akiri to ML Policy | No ML seat test | Yes: mixed fixed profile launch | ControllerKind + profile factory; C1_06 final acceptance |
| ML_SEAT_02 Chevill remains forced Engine AI | Arena01EngineOnlyPresetWithoutLlmKeyTest, ArenaHuman01HumanVsEngineAiTest | Yes: mixed orientation assertion | Per-seat controller routing; engineAiOnly must not force Akiri |
| ML_SEAT_03 client cannot supply checkpoint/model/path/controller | Locked preset override rejection in Arena01CommanderFixedMatchRedTest | Yes: body/message forgery matrix | No-payload/fixed-profile endpoint |
| ML_SEAT_04 exact manifest + weight digest required | test_checkpoint_manifest.py and C1_06 strict reload tests | Yes: worker startup digest gate | Checkpoint runtime primitive |
| ML_SEAT_05 wrong checkpoint identity fails closed | Offline manifest/runtime tests | Yes: live profile mismatch | No fallback checkpoint |
| ML_SEAT_06 wrong weight digest fails closed | validate_weight_file and C1_06 loader tests | Yes: worker artifact mismatch | No model load |
| ML_SEAT_07 acting-player perspective-safe input only | Gym ObservationPrivacy tests and C1 projection tests | Yes: live snapshot golden | Shared live model-input primitive |
| ML_SEAT_08 opponent hidden hand/library absent | ObservationPrivacyTest and visibility tests | Yes: request wire inspection | No raw client/pending DTO |
| ML_SEAT_09 complete current legal flat domain reaches inference unchanged | CompleteLegalDomain/variable transport tests are offline | Yes: live domain digest/candidate equality | Coherent GameSession snapshot |
| ML_SEAT_10 one finite score per supplied candidate | Python InferenceRuntime and C1_06 score tests | Yes: worker/provider count and finite gate | ScoreProvider adapter |
| ML_SEAT_11 selected binding belongs to exact current domain | ChosenSemanticInput and Gym strict membership tests | Yes: stale/missing ordinal live test | JVM current-domain revalidation |
| ML_SEAT_12 candidate permutation preserves semantic association | C1_06 real-model permutation and Selection V2 tests | Yes: live framed request permutation | Ordinal remains non-feature metadata |
| ML_SEAT_13 PolicyTieRng reproduces from exact state | test_policy_tie_rng.py and Selection V2 tests | Yes: worker restart/cursor golden | Persisted RNG state and Python ownership |
| ML_SEAT_14 every reachable structured family has explicit policy control | Typed domain/response validator tests only | Yes: locked-pair family inventory | Structured inference primitive |
| ML_SEAT_15 unsupported structured family fails closed | InferenceRuntime and Gym diagnostic fail-closed tests | Yes: AssignDamage/partial mana cases | No random/first/Engine fallback |
| ML_SEAT_16 runtime crash/timeout does not invoke Engine AI | No ML failure path | Yes: callback spy + status | Separate ML handler; fail research match |
| ML_SEAT_17 recovery restores ML kind/checkpoint | Existing AI recovery restores isAi/forceEngine only | Yes: persistent authority round trip | ControllerAuthorityV1 |
| ML_SEAT_18 recovery never rewires ML as Engine AI | No current ML test | Yes: engineAiOnly=false mixed recovery | Per-seat rewire logic |
| ML_SEAT_19 ML semantic action uses GamePlayHandler/GameSession path | GameSession.executeAction and Human/Engine integration tests | Yes: selected binding/action log | Existing transition authority |
| ML_SEAT_20 Engine AI responds through existing Chevill controller | Existing Engine AI Arena tests | Yes: mixed action log | Existing AiWebSocketSession unchanged |
| ML_SEAT_21 spectator sees existing safe view | SpectatingHandlerAdmissionTest, GameSessionSpectatorTest | Yes: mixed public match spectator | No model request/diagnostic fields |
| ML_SEAT_22 Research Arena durable stats suppressed | MatchResultSinkTest, CurriculumLobbyPersistenceTest | Yes: ML completion/failure | recordDurableStats=false survives recovery |
| ML_SEAT_23 existing AI-vs-AI Arena unchanged | ARENA_02 and AI tournament tests | Yes: regression after ML seam | Existing endpoint/controller unchanged |
| ML_SEAT_24 existing Human-Akiri-vs-Engine-Chevill unchanged | ArenaHuman01HumanVsEngineAiTest and Playwright flow | Yes: regression after controller refactor | No ML path in human profile |
| ML_SEAT_25 no second GameSession/rules/observation/protocol | Current module/dependency graph | Yes: source/dependency guard | game-server depends gym, not gym-trainer |

Required additional REDs:

| additional case | reason |
| --- | --- |
| ML_SEAT_26 coherent snapshot rejects action committed after snapshot creation | GameSession reads are currently separate |
| ML_SEAT_27 AssignDamageDecision cannot be advertised complete until typed domain exists | Builder sets structured flag without domain |
| ML_SEAT_28 partial pending mana domain fails closed for Waterbend/composite Ward | Builder intentionally returns null for these shapes |
| ML_SEAT_29 BatchYesNoResponse is not silently projected as ordinary Yes/No | C1 folded allowlist excludes it |
| ML_SEAT_30 persistence failure after policy transition blocks next ML action | Redis save catches/logs persistence failures |
| ML_SEAT_31 in-flight worker response after restart cannot be committed | Request correlation is new |
| ML_SEAT_32 no processAutoPassLoop transition occurs on ML seat before policy request | Existing loop can auto-pass by priority settings |
| ML_SEAT_33 profile status reveals checkpoint id but not path/PID/hidden payload | Public status is a new sanitized surface |

## 20. Dependency classification

| missing or existing piece | classification | design disposition |
| --- | --- | --- |
| ObservationBuilder, projected state, Visibility, PlayerObservationV1 | NO_GAP | Reuse as the only observation authority. |
| CompleteLegalDomainV1 and CandidateDomainDigestV1 | NO_GAP | Reuse; extend only through versioned generic contract. |
| ChosenSemanticActionV1 / ChosenSemanticResponseV1 validators | NO_GAP | Reuse for final membership; not a selector. |
| GameSession.executeAction / SubmitDecision path | NO_GAP | Final Rules transition seam remains unchanged. |
| TournamentLobby, fixed curriculum loader, match handler | NO_GAP | Reuse existing Arena launch/lifecycle. |
| spectator admission/board/protocol | NO_GAP | Reuse unchanged; only sanitized status may be additive. |
| GameSession-to-coherent Gym/C1 snapshot | LIVE_MODEL_INPUT_PRIMITIVE_MISSING | Extract small lock-coherent snapshot; no raw state JSON. |
| offline C1ModelFacingProjection input usable by game-server | LIVE_MODEL_INPUT_PRIMITIVE_MISSING | Extract generic alias/model projection to gym; keep trajectory materializer in gym-trainer. |
| action-template target/payment/combat payload selector | STRUCTURED_INFERENCE_PRIMITIVE_MISSING | Define staged or complete-response contract; no giant Cartesian space. |
| typed AssignDamageDecision domain | STRUCTURED_INFERENCE_PRIMITIVE_MISSING | Add only through generic domain follow-up; otherwise fail closed. |
| complete pending mana domain for guarded shapes | STRUCTURED_INFERENCE_PRIMITIVE_MISSING | Preserve current guards; no solver/autopay fallback. |
| ControllerKind and per-seat authority | GENERIC_CONTROLLER_PRIMITIVE_MISSING | Add versioned seat descriptor; legacy fields remain compatibility projections. |
| ML runtime and ScoreProvider wrapper around C1_06 | CHECKPOINT_RUNTIME_PRIMITIVE_MISSING | Long-lived fixed local Python worker, strict load/health contract. |
| persisted profile/checkpoint/numeric/RNG state and atomic commit | PERSISTENCE_PRIMITIVE_MISSING | Add ControllerAuthorityV1 and game-scoped policy runtime state. |
| Selection V2 / PolicyTieRng implementation | NO_GAP | Keep Python-owned; do not duplicate in Kotlin. |
| current Engine AI identity/session plumbing | NO_GAP for Engine AI | Preserve existing AiGameManager/AiWebSocketSession path. |
| ML adapter into GamePlayHandler and match failure closure | ADAPTER_MISSING | New ML-only callback/handler with no safe fallback. |

## 21. Proposed implementation decomposition

The following is the smallest safe sequence after independent review. None is
authorized by this design.

### C1_07A — structured inference totality and live semantic contract

Scope:

* extract generic model-facing input projection into gym;
* define LivePolicyDecisionSnapshot and exact digest/binding invariants;
* characterize locked Akiri/Chevill branches using existing tests/artifacts,
  without generating a new trajectory;
* define versioned structured-choice alternatives/prefix semantics only where
  complete and injective;
* add AssignDamage/partial mana to the explicit unsupported matrix;
* define live request/response and Selection V2 ownership;
* add ML_SEAT_09 through ML_SEAT_15 and ML_SEAT_26 through ML_SEAT_29 REDs.

Gate:

~~~text
STRUCTURED_CONTRACT_REVIEW_PASS=YES
LIVE_MODEL_INPUT_PARITY=YES
or an explicit bounded profile matrix with unsupported families proven
unreachable by the accepted locked pair
~~~

No checkpoint process or Arena launch is added here.

### C1_07B — checkpoint-backed local inference runtime

Scope:

* implement fixed local long-lived Python worker;
* wrap C1_06 FeedForwardCandidateScorer as ScoreProvider;
* validate exact manifest/weight/profile/numeric contracts at startup;
* implement framed request/response, correlation, timeout, health, crash, and
  no-network behavior;
* keep Selection V2 and PolicyTieRng in Python;
* add ML_SEAT_04 through ML_SEAT_06, ML_SEAT_10 through ML_SEAT_13,
  ML_SEAT_16, and ML_SEAT_30/31 runtime tests.

Prerequisites:

~~~text
C1_06_FINAL_ACCEPTANCE_PASS=YES
C1_06 accepted code/checkpoint contract on implementation baseline
C1_07A structured/live contract accepted
CPU_GAMEPLAY_INFERENCE_AUTHORIZED=NO unless a new profile is accepted
~~~

No Arena wiring is added until runtime proves its own profile health.

### C1_07C — generic ML controller lifecycle and persistence

Scope:

* add ControllerKind/ControllerAuthorityV1;
* route ML and Engine seats separately in AiGameManager/match wiring;
* add lock-coherent snapshot/execute commit seam;
* persist profile/checkpoint/numeric/RNG state in lobby and game DTOs;
* make recovery preserve ML kind or fail closed;
* make ML failures typed and non-fallback;
* prevent auto-pass from bypassing an ML boundary;
* add ML_SEAT_01 through ML_SEAT_03, ML_SEAT_07/08, ML_SEAT_17/18,
  ML_SEAT_25/26/30/32.

No client-visible Arena action is added until lifecycle REDs are green.

### ARENA_ML_01 — server-owned ML Akiri vs Engine Chevill

Scope:

* fixed no-payload launch command/profile;
* reuse exact curriculum source/lobby/match/session;
* set Akiri to ML_POLICY and Chevill to existing ENGINE_AI;
* preserve recordDurableStats=false;
* reuse spectator route/board/protocol;
* add only sanitized profile/checkpoint/runtime status;
* add ML_SEAT_19 through ML_SEAT_24 and ML_SEAT_33.

Explicitly not included:

~~~text
Human-vs-ML
ML-vs-ML
reverse orientation
new board
new spectator protocol
replay semantic changes
trajectory publication
training/RL/self-play
~~~

## 22. Blockers and non-goals

Blocking conditions:

1. C1_06_FINAL_ACCEPTANCE_PASS=NO. Merged PR and Hosted CI are not a
   substitute for independent exact-SHA/code-review acceptance.
2. No accepted live GameSession-to-C1 model-facing snapshot exists.
3. Current C1 runtime rejects structured input and current source binding
   rejects action templates with required payloads.
4. No generic structured-choice selector exists for observed target,
   card-selection, payment, attack, and repeat surfaces.
5. Current controller/persistence fields cannot distinguish ML Policy from
   Engine AI or carry exact checkpoint/RNG authority.
6. No checkpoint artifact is physically provisioned in the repository/server
   runtime, and C1_06 does not certify CPU gameplay inference.
7. Existing Engine-AI fallbacks would violate ML fail-closed behavior if reused
   without a ControllerKind branch.

These blockers are characterized, not worked around. This design does not lower
C1 acceptance criteria, use a fake LLM model name, treat forceEngine=false as
ML, choose first/random/heuristic structured options, or auto-concede ML.

## 23. Final design gate

~~~text
TASK=C1_07_LIVE_POLICY_EXECUTION_AND_STRUCTURED_INFERENCE_TOTALITY_DESIGN

C1_06_STATE=PR_199_MERGED_HOSTED_CI_PASS_INDEPENDENT_EXACT_SHA_REVIEW_NOT_ESTABLISHED
C1_06_FINAL_ACCEPTANCE_PASS=NO
C1_06_IMPLEMENTATION_USED=MAIN

LIVE_MODEL_INPUT_PARITY=PARTIAL
C1_00_STRUCTURED_INFERENCE_TOTALITY=NO

REACHABLE_STRUCTURED_FAMILIES=
  PENDING_TARGETS,
  PENDING_CARD_SELECTION,
  ACTION_TARGET_PAYLOAD,
  ACTION_PAYMENT_PAYLOAD,
  ACTION_REPEAT_COUNT_PAYLOAD,
  ACTION_ATTACK_PAYLOAD

FLAT_EXECUTION_TOTALITY=NO
STRUCTURED_EXECUTION_TOTALITY=NO

RECOMMENDED_POLICY_RUNTIME=
  one long-lived fixed local Python subprocess with framed stdin/stdout;
  Python owns C1_06 ScoreProvider, Selection V2, and PolicyTieRng V1;
  JVM owns profile launch, current-domain revalidation, transition, and failure closure

RECOMMENDED_CONTROLLER_SEAM=
  versioned per-seat ControllerKind plus deep ML PolicySeatRuntime;
  existing Engine AI/LLM AiWebSocketSession remains unchanged

RECOMMENDED_CHECKPOINT_PROVISIONING=
  server-owned immutable profile binds exact manifest/checkpoint/weight/config/numeric ids;
  operator supplies regular files under configured local artifact root;
  path is locator only and never semantic identity

RAW_GAMESTATE_TO_MODEL=NO
HIDDEN_INFO_TO_MODEL=NO
ENGINE_AI_FALLBACK_FOR_ML=NO
RANDOM_FALLBACK_FOR_ML=NO
FIRST_CHOICE_FALLBACK_FOR_ML=NO

ML_POLICY_SEAT_IMPLEMENTATION_AUTHORIZED=NO

P1=0
P2=0
P3=1

DESIGN_PASS=YES
PR_CREATED=NO
NEXT_TASK_STARTED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
~~~

The design stops here. No C1_07A/B/C implementation, worker, Arena launch,
training, or next task is started by this branch.
