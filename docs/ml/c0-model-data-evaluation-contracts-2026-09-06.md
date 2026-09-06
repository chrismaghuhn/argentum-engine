# C0 model, data, and evaluation contracts

Task: C0_CONTRACT_FREEZE_RESEARCH_01
Date: 2026-09-06
Repository: chrismaghuhn/argentum-engine
Scope: research and design only

This report freezes the conceptual contracts that a later C1 learner may consume. It does not
implement a model, training loop, dataset generator, recurrent runtime, search, value learner,
RL algorithm, or self-play system. It does not change TrajectoryV1, PlayerObservationV1,
CompleteLegalDomain, Rules, Gym, replay, the locked decks, or the accepted source sample.

## Audit identity and accepted boundary

The source audit was performed in a dedicated worktree created from the exact requested ref.

~~~text
BASE=2a030fa8aa6eb86a1b468f1c7a9ec7f5a10cda89
ORIGIN_MAIN=2a030fa8aa6eb86a1b468f1c7a9ec7f5a10cda89
UPSTREAM_MAIN=5021faf88093a93091e4de7914fbe0f411499d58
BRANCH=chris/c0-contract-freeze-research-20260906
WORKTREE=C:/Users/chris/.config/superpowers/worktrees/argentum-engine/c0-contract-freeze-research-20260906
ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM=https://github.com/wingedsheep/argentum-engine.git
~~~

Origin and upstream were fetched before the audit. The task authorization is the accepted
baseline for this report:

~~~text
COMMANDER_ENVIRONMENT_V1_COMPLETE=YES
PHASE_A_FINAL_ACCEPTANCE_PASS=YES
B0_FINAL_ACCEPTANCE_PASS=YES
B1_FINAL_ACCEPTANCE_PASS=YES
B2_FINAL_ACCEPTANCE_PASS=YES
DATA_TRUSTED=YES
CURRENT_PHASE=C0
C0_AUTHORIZED=YES
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
RL_AUTHORIZED=NO
SELF_PLAY_AUTHORIZED=NO
LARGE_CORPUS_GENERATION_AUTHORIZED=NO
~~~

Some historical B2 reports retain their earlier pre-acceptance status. They are implementation
history, not the accepted baseline for this task.

| Status | Meaning |
| --- | --- |
| READY_TO_FREEZE | The semantic boundary is defined without changing an accepted source contract. |
| NEEDS_CHARACTERIZATION | A safe design exists, but a source-owned fact or measurement is not characterized. |
| BLOCKED_ON_#119 | Only physical scaling, I/O, storage, or memory evidence is missing. |
| BLOCKED_ON_ENGINE_DEPENDENCY | The current public source cannot provide the requested fact without a future versioned change. |
| DEFER_TO_C1 | Deliberately not frozen as an implementation choice in C0. |

Direct source locations audited at BASE include:

| Area | Source locations |
| --- | --- |
| Observation and public domain | gym/src/main/kotlin/com/wingedsheep/gym/contract/PlayerObservationV1.kt; TrainingObservation.kt; ObservationCanonicalizer.kt; CandidateDomainDigest.kt; StructuredDecisionDomain.kt; ActionTargetDomain.kt; AttackDeclarationDomain.kt; BlockerDeclarationDomain.kt; PaymentDomain.kt; TargetPaymentDomain.kt; RepeatCountDomain.kt |
| Chosen semantic inputs | gym/src/main/kotlin/com/wingedsheep/gym/contract/ChosenSemanticInput.kt; ReplayChosenInputBinding.kt; ReplayVerificationBinding.kt; VerifiedReplayFrame.kt |
| Episode and dataset contracts | gym/src/main/kotlin/com/wingedsheep/gym/EpisodeClosure.kt; gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1.kt; SemanticReplayInput.kt; SemanticDecisionIdentity.kt |
| Reader/storage/replay linkage | gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Reader.kt; TrajectoryV1ManifestPreflight.kt; TrajectoryV1ShardValidator.kt; TrajectoryV1StorageFrames.kt; TrajectoryV1Manifest.kt; TrajectoryV1Writer.kt |
| Rules response/action vocabulary | rules-engine/src/main/kotlin/com/wingedsheep/engine/core/GameAction.kt; PendingDecision.kt; CombatResolution.kt; rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/LegalAction.kt |

## 1. Current accepted source contracts

The authoritative sample remains:

~~~text
PlayerObservationV1
+ complete currently legal domain
+ chosen semantic action or response
~~~

The model-facing adapter is downstream of these values. It never reads GameState, a Rules
component, a live action registry, a replay routing handle, or hidden card identity.

| Contract | Current source shape | Authority and boundary | C0 status |
| --- | --- | --- | --- |
| PlayerObservationV1 | Version 1, identity argentum-gym-player-observation@v1; perspective-safe players, zones, visible entities, stack, pending semantic context, timing, terminal flags, and observation digest. | Projection of privacy-safe TrainingObservation; legal actions and structured domains are separate. | READY_TO_FREEZE as source; full encoder has dependencies in §3. |
| CompleteLegalDomainV1 | Kotlin type name remains V1; current explicit pair is version 2, identity argentum-gym-action-domain@v2. Shape is ACTION_CANDIDATES, FOLDED_DECISION_OPTIONS, or STRUCTURED_DECISION. | Sole durable public legal-domain representation. Flat candidates are semantic LegalActionView fingerprints; structured decisions retain typed StructuredDecisionDomain. | READY_TO_FREEZE. |
| CandidateDomainDigestV1 | Version 1, identity argentum-gym-candidate-domain-digest@v1; SHA-256 over a prefixed canonical complete-domain representation. | Binds the exact complete domain, including producer-owned candidate order and domain version. | READY_TO_FREEZE. |
| ChosenSemanticActionV1 | Version 1, identity argentum-trajectory-chosen-action@v1; full stored candidate plus exactly required semantic choice payload. | Membership is checked against the stored domain; routing IDs and live handles are excluded; unaffordable candidates are rejected. | READY_TO_FREEZE. |
| ChosenSemanticResponseV1 | Version 1, identity argentum-trajectory-chosen-response@v1; transport-free response JSON, including normalized ordering references and explicit PaymentPlanV3 where required. | Typed membership and relation checks; AutoPay and opaque trigger handles are rejected. | READY_TO_FREEZE. |
| SemanticDecisionIdentityV1 | Version 1, identity argentum-trajectory-semantic-decision@v1; episode, replay prefix, coordinate, perspective, family, observation digest, and domain digest. | Durable semantic identity, not a feature, label, or routing ID. | READY_TO_FREEZE. |
| DecisionRecordV1 | Decision/replay coordinates, perspective, kind, semantic ID, observation, complete domain, domain digest, and exactly one chosen value. | One authoritative public decision boundary; validator requires a nonterminal actor-owned observation. | READY_TO_FREEZE. |
| EpisodeMetadataV1 | Semantic episode ID, collection-job ID, EnvironmentIdentityV1, PolicyProvenanceV1, CompactReplayLinkV1, and EpisodeClosureV1. | Episode-level binding; separates semantic environment identity from collection provenance and factual closure. | READY_TO_FREEZE. |
| EnvironmentIdentityV1 | Engine/card/deck identities, format/configuration, roster/roles, start player, actual seed, and schema identities. | Reproducibility and semantic episode identity; not a policy feature. | READY_TO_FREEZE as provenance. |
| PolicyProvenanceV1 | Behavior/opponent policy identities and roles, policy RNG identity/seed, policy source identity. | Collection provenance only; excluded from semantic episode/decision identity. | READY_TO_FREEZE as provenance. |
| CompactReplayLinkV1 and replay bindings | New linkage is CompactReplay v6; v5 remains supported historical pair. | Content identity and complete-range proof bind replay evidence; not model input. | READY_TO_FREEZE as audit/provenance. |
| EpisodeClosureV1 | GAME_TERMINAL, INTERRUPTED, or FAILED. | Factual lifecycle closure, not reward. | READY_TO_FREEZE. |
| TrajectoryV1 | Version 1, identity argentum-trajectory@v1; episode metadata plus ordered decisions. | Immutable semantic source; C0 consumes and does not alter it. | READY_TO_FREEZE. |
| DatasetManifestV1 and TrajectoryV1Reader | Content-addressed manifest, manifest-owned shards, canonical UTF-8/LF NDJSON, bounded strict streaming, fail-closed preflight. | Trusted reader boundary; future learner view is derived after it. | READY_TO_FREEZE semantically; physical scaling is §16. |

Verified replay frames, verification, trajectory bindings, and chosen-input bindings are proof and
provenance artifacts, not model features.

## 2. Model-facing decision sample contract

### 2.1 Conceptual ModelDecisionSampleV1

The following is a derived learner-view contract, not a request to add this type in C0.

~~~text
ModelDecisionSampleV1 {
    modelSampleVersion: 1
    modelSampleSchemaIdentity: "argentum-model-decision-sample@v1"

    sourceTrajectoryId
    semanticEpisodeId
    decisionIndex
    replayActionIndex
    perspectivePlayerId
    decisionKind

    observation: PlayerObservationV1
    domain: CompleteLegalDomainV1
    candidateDomainDigest: CandidateDomainDigestV1

    target:
        chosenCandidateIndex?       // flat or folded domain
        chosenSemanticAction?        // complete action target
        chosenSemanticResponse?      // typed structured target

    sequenceBoundary:
        streamKey
        streamDecisionOrdinal
        resetBefore
        windowCoordinate?            // adapter metadata, never a model feature
}
~~~

Source/replay identities remain for traceability but are not tensor inputs. The raw perspective ID
is retained for binding; the encoder receives only relative public role/reference features.

For flat/folded domains, chosenCandidateIndex is derived by exact canonical membership in the stored
candidate list. For structured domains, the primary target is the complete chosen semantic response;
family adapters may add component targets but may not replace the response with a partial label.

### 2.2 Field-by-field authority table

| Source field | Classification | Model-facing rule |
| --- | --- | --- |
| Trajectory/record version and schema | PROVENANCE_ONLY | Gate the adapter; never embed schema strings. |
| trajectoryId, semanticEpisodeId | PROVENANCE_ONLY | Trace/group/deduplicate; never learned identities. |
| collectionJobId | AUDIT_ONLY | Collection provenance only; never feature or target. |
| engine/card/deck/roster/config identities | PROVENANCE_ONLY or AUDIT_ONLY | Bind source and evaluation cells; not tensor features. |
| actualEngineSeed | AUDIT_ONLY | Split/group/evaluation key only after characterization; never feature. |
| all PolicyProvenanceV1 fields | PROVENANCE_ONLY | Collection policy/RNG/source metadata; never input or teacher-quality claim. |
| CompactReplayLinkV1 and replay bindings | AUDIT_ONLY | Verify source range/content; never feature. |
| EpisodeClosureV1 | AUDIT_ONLY for policy; TARGET only through separate value contract | Future outcome never enters current input. |
| decisionIndex and replay coordinates | AUDIT_ONLY | Preserve chronology/window identity; do not embed absolute position. |
| perspectivePlayerId | MODEL_INPUT only as derived relative role/reference | Raw EntityId is an opaque binding key, not a numeric/string feature. |
| decisionKind | MODEL_INPUT | Routes the family adapter and supplies causal context. |
| observationBefore | MODEL_INPUT | Encode only the public projection in §3. |
| completeLegalDomain | MODEL_INPUT | Encode every current candidate/domain member; never use only digest or reconstruct. |
| candidateDomainDigest | PROVENANCE_ONLY | Recompute and compare; do not embed. |
| semanticDecisionId | AUDIT_ONLY | Verify identity/deduplication; never feature or target. |
| chosenSemanticAction | TARGET | Full candidate plus exactly advertised payload fields. |
| chosenSemanticResponse | TARGET | Full typed response, including selected members/amounts/payment program. |
| manifest IDs/counts/shards/digests | PROVENANCE_ONLY | Bind dataset/reader result; never feature. |

Current input must not contain winner/closure/return, future observations/domains/choices, hidden
card/entity information, raw GameState, routing handles/nonces, or dataset/replay/policy/checkpoint/
host/path/time/framework IDs. A recurrent prior chosen input is allowed only from the same actor
stream and only after it occurred.

For EpisodeMetadataV1, the exact classification is: semanticEpisodeId and EnvironmentIdentityV1
are provenance used to bind the reproducible environment; collectionJobId and PolicyProvenanceV1
are collection provenance/audit only; CompactReplayLinkV1 is replay audit/provenance; and
EpisodeClosureV1 is audit-only for policy input and becomes a target only through the separate
SupervisedValueTargetV1 boundary. None of these fields is a current policy feature.

## 3. Observation encoding contract

### 3.1 Source audit

PlayerObservationV1 contains version/schema anchors, wire schema hash, perspective and actor IDs,
turn/phase/step, active and priority IDs, players, zones, stack, pending semantic context,
terminal flags, winner, and observation digest. It is built from privacy-safe
TrainingObservation; legalActions and structured pending domain are not duplicated there.

The encoder is a public projection of this value. It does not infer omitted facts from card names,
zone sizes, entity IDs, or card definitions.

### 3.2 Feature representation

| Source area | Type | Encoding rule |
| --- | --- | --- |
| Schema envelope | categorical/digest | Compatibility and audit only. Reject mismatches; do not embed. |
| Perspective/actor context | stable references and optional values | Convert public IDs to relative role tokens and relation keys. Missing is an explicit mask. |
| Turn context | scalar and categorical | Preserve exact turn number, Phase, and Step. Normalize only with declared encoder identity. |
| Players | entity collection, scalar, boolean | Use declared roster order. Encode life, hand/library/graveyard/exile sizes, six public mana buckets, and perspective/active/priority/lost flags. Names are presentation and excluded. |
| Zones | collection plus relations | Encode zone type, relative owner, hidden flag, total size, and only cards present in the source projection. |
| Entity/card features | categorical, text, set, optional scalar, boolean, relation | Use cardDefinitionId/name/oracleText only when exposed. Sets are multi-hot or ragged tokens with deterministic vocabulary. Optional numbers carry presence bits. |
| Stable references | opaque reference | Entity IDs, owner/controller IDs, attachment IDs, and targets are graph keys only; never raw string/UUID features. |
| Counters | typed set of scalars | Encode sorted public counter-type/count pairs with presence masks. |
| Attachments/equipment | directed relation graph | Emit typed attachedTo and attachment edges. |
| Stack | ordered collection plus graph edges | Preserve bottom-to-top order and public source/target relations. |
| Pending decision | categorical, relation, scalar/optional | Encode family, public source/trigger relations, structured flag, and shape. Exclude decisionId, prompt, sourceName, and effectHint. |
| Terminal fields | boolean/optional reference | Decision samples must be nonterminal. Retain for validation only; never expose future closure. |
| Public history | absent from PlayerObservationV1 | Do not synthesize an event/action log. Recurrent history is a derived same-perspective view only. |

All variable collections use ragged/set/sequence structures with presence masks. Padding is a
batching representation, never an entity/candidate. A fixed maximum is not a C0 contract.

oracleText is printed/base source text, not guaranteed effective projected rules text. It may be used
as an auxiliary public feature but is not authoritative Rules semantics.

### 3.3 Commander and cross-step identity findings

The current Gym observation does not expose dedicated commander features. The engine/client layer
has commander identity and commander-damage data, but TrainingObservation and PlayerObservationV1
do not carry commander damage tallies, commander tax/cast count, or a commander-specific public
zone/choice surface. Generic visible card features and life totals may be used when supplied;
commander state must not be reconstructed from GameState or a card name.

~~~text
CURRENT_SAFE_ENCODING=generic public observation only
COMMANDER_SPECIFIC_FEATURES=NOT_AVAILABLE_IN_PLAYER_OBSERVATION_V1
FUTURE_REQUIREMENT=versioned public observation/environment-contract change
STATUS=BLOCKED_ON_ENGINE_DEPENDENCY
~~~

The same rule applies to persistent cross-step entity aliases. Current EntityId values are safe
relation keys, but no separate model-owned stable alias contract exists. Feed-forward encoding is
safe; C1 memory must not assume raw IDs are learned identity across time.

### 3.4 Hidden-information and optional-field policy

ZoneView.hidden, omitted cards, null definition IDs, face-down flags, and missing optional fields
are source facts. The encoder represents them with masks and values. It does not use a special
unknown-card embedding that distinguishes hidden cards, and it does not replace null with a card
registry lookup.

OBSERVATION_ENCODING_CONTRACT is NEEDS_CHARACTERIZATION for the requested Commander/history
surface; the safe current public subset is ready to freeze.

## 4. Variable-size candidate representation and scoring

### 4.1 Conceptual candidate encoding

A lossless typed CandidateEncodingV1 is:

~~~text
CandidateEncodingV1 {
    family: categorical
    affordable: boolean
    sourceReference?: public entity relation
    targetReferences: ragged public entity relations
    targetDomain?: ordered requirement records
    attackDomain?: ordered attacker/defender relation records
    blockerDomain?: ordered blocker/attacker relation records
    paymentDomain?: variable source/production/pool/allocation capability graph
    targetPaymentDomain?: target -> payment-domain records
    scalarBounds: X/target/repeat/sacrifice bounds with presence masks
    colorDomain?: ordered public colors
    requiredPayloadFields: canonical field-set encoding
    actionSemantics: typed routing-free semantic payload
    decisionOption: boolean
}
~~~

The encoding is lossless with respect to the accepted semantic candidate. It may use embeddings,
sets, relations, or text internally, but may not drop a field for batching convenience.

### 4.2 Current candidate fields

| Candidate component | Encoding | Selection/mask rule |
| --- | --- | --- |
| kind | categorical family token | Unknown current family fails closed. |
| affordable | boolean | All candidates receive scores; only affordable candidates may be selected. |
| sourceEntityId | public relation key | Relate to observation graph; never embed raw string. |
| targetEntityIds | ragged public references | Preserve exact payload and producer-canonical nested order. |
| targetDomain | ordered requirements with candidate sets/constraints | Encode every requirement/candidate; do not regenerate from visible cards. |
| attackDeclarationDomain | V2 attacker order, defender relations, mandatory/co-attacker/band constraints | Preserve Rules-owned order and relations; attackers/bands remain explicit even when empty. |
| blockerDeclarationDomain | V1 blocker/attacker order, relations, cardinality, repeated requirements | Preserve duplicate Rules requirement instances; never deduplicate. |
| manaCost and X/target bounds | categorical/scalar with masks | Encode exact published bounds; never infer from text. |
| paymentDomain | V5 source/production/initial-pool/outer-cost capability domain | Encode every source option, production choice, cost order, unit, pool bucket, and life bound. No solver. |
| targetPaymentDomain | target bindings, each with complete V5 capability | Do not flatten target-dependent payment to target ID. |
| repeatCountDomain | present flag plus min/max | Use published Rules-owned range; never reconstruct from action semantics. |
| sacrifice/damage fields | bounds, flags, public entity pools | Preserve all current public pools; unsupported channels remain fail-closed. |
| availableManaColors | ordered enum set/list | Null, explicit empty, and nonempty producer order are distinct. |
| requiresStructuredAction/requiredPayloadFields | boolean plus canonical field list | Missing/unknown field invalidates candidate; model may not guess. |
| actionSemantics | routing-free typed object | Preserve semantic payload and stable ability keys; reject routing IDs. |
| isDecisionOption | boolean | Identifies folded decisions; it does not turn a response into GameAction. |

### 4.3 Score cardinality and order

For flat and folded domains:

~~~text
NUMBER_OF_MODEL_SCORES == NUMBER_OF_CURRENT_LEGAL_CANDIDATES
~~~

The candidate list is the exact CompleteLegalDomainV1.candidates list after strict validation. The
adapter must not sort, deduplicate, top-k, sample, or truncate it. Use ragged offsets or packed
rows for batching.

Candidate ordinal is a binding coordinate, not a learned feature. Set-like objects are encoded
permutation-safely. Where order is semantic, such as attacker, stack, or library order, that order
is nested semantic data. Never replace producer order with sorted raw IDs.

~~~text
rawScores = CandidateScorer(observationEncoding, candidateEncodings)
executionMask[i] = candidate[i].affordable && candidate[i] is structurally supported
policyDistribution = softmax(rawScores over executionMask)
~~~

Raw scores still exist for every candidate. A target outside the execution mask is invalid, not
repaired. All candidates masked means fail closed. CompleteLegalDomain and ChosenSemantic values
remain final authority.

### 4.4 Accepted Environment V1 family audit

Current serialized action candidates:

~~~text
ActivateAbility, CastSpell, CastSpellMode, CastWithFlashback, CastWithKicker,
CycleCard, DECISION, DeclareAttackers, PassPriority, PlayLand
~~~

Reachable or explicitly covered public/domain families:

~~~text
CHOOSE_TARGETS, SELECT_CARDS, YES_NO, CHOOSE_COLOR,
ORDER_OBJECTS, CHOOSE_OPTION, REORDER_LIBRARY,
COMBAT_RESOLUTION, SELECT_MANA_SOURCES
~~~

The typed catalog also contains fail-closed generic shapes DISTRIBUTE, SPLIT_PILES,
CHOOSE_REPLACEMENT, SEARCH_LIBRARY, BUDGET_MODAL, and legacy ASSIGN_DAMAGE. Catalog presence is
not reachability evidence; unknown or unsupported versions still fail closed.

Required payload coverage:

~~~text
targets
paymentStrategy
additionalCostPayment
costPayment
alternativePayment
repeatCount
manaColorChoice
damageDistribution
chosenModes
modeTargetsOrdered
attackers
bands
blockers
orderedBlockers
crewCreatures
saddleCreatures
~~~

The list is candidate-declared. An empty explicit choice differs from an omitted choice.

CANDIDATE_SCORING_CONTRACT=READY_TO_FREEZE for current flat/folded domains and typed-domain inputs.

## 5. Structured decision representation

### 5.1 Alternatives

| Choice | Meaning | Main risk |
| --- | --- | --- |
| A. One candidate list for every boundary | Enumerate every legal response and use one softmax. | Current structured domains publish constraints/relations, not all combinations. Enumeration can truncate or become a second legality engine. |
| B. Decision-family/domain adapter | Keep CompleteLegalDomainV1 typed; score supplied members/slots and produce a complete semantic response target. | More adapters and family metrics; adapter must not become legality authority. |

### 5.2 C0 decision

~~~text
STRUCTURED_DECISION_MODEL=B
~~~

C0 preserves typed adapters:

- flat actions and folded options receive one score per supplied candidate;
- target, card, mode, color, number, option, and replacement choices score only published members;
- ordering/reorder adapters score published objects and output required permutations;
- distribution/combat adapters score published targets/edges and output complete validated responses;
- SELECT_MANA_SOURCES consumes complete V5 capability and targets explicit PaymentPlanV3, never
  AutoPay or inferred source/production/allocation;
- all outputs are checked by ChosenSemanticResponseV1 and, at execution, by server/Rules.

This is required by the accepted source shape, not implementation convenience. A universal candidate
list requires a future versioned enumerated response contract. C0 does not add it.

## 6. Feed-forward reference contract

~~~text
x_t = ObservationEncoderV1(O_t)
c_i = CandidateEncoderV1(A_i)
s_i = CandidateScorerV1(x_t, c_i)
pi_i = softmax(s_i over the supplied valid candidate set)
~~~

| Area | Contract |
| --- | --- |
| Input | Current public observation plus complete current domain; no history, closure, outcome, replay prefix, provenance, or hidden state. |
| Score output | One finite scalar per flat/folded candidate in exact order; structured adapters emit finite scores for every supplied option/slot. |
| Masking | Mask only source-declared non-executable candidates; never remove rows for batching. Unknown family/domain/version fails closed. |
| Target | Exact chosen index for flat/folded; complete chosen response and family components for structured. |
| Loss candidate | Categorical NLL/cross-entropy over supplied valid flat/folded candidates; structured losses are separately reported. |
| Inference | §12 deterministic contract; no native AI, heuristic, solver, or fallback. |
| Sampling | Future stochastic boundary with its own RNG identity; deterministic evaluation consumes none. |

This is a causal reference, not a final architecture.

## 7. Recurrent sequence contract

### 7.1 Derived SequenceViewV1

Derived after trusted TrajectoryV1 reading:

~~~text
TrajectoryV1
  -> ordered DecisionRecordV1 by replayActionIndex
  -> one actor-perspective stream per (semanticEpisodeId, perspectivePlayerId)
  -> deterministic recurrent windows
~~~

Each row retains its source record plus:

~~~text
SequenceRowV1 {
    semanticEpisodeId
    perspectivePlayerId
    globalReplayActionIndex
    actorStreamDecisionOrdinal
    currentObservation
    currentCompleteDomain
    previousSameStreamSemanticInput?
    currentTarget
    resetBefore
}
~~~

Global/stream ordinals are audit/window metadata, not tensor features. Previous input is the last
chosen semantic action/response from the same actor perspective. Opponent rows are not fed into
that actor's private recurrent state; public consequences are in the next current observation.

### 7.2 Reset and windows

- Sort only by validated replayActionIndex, never filesystem order.
- Fresh hidden state at every semanticEpisodeId and every actor stream.
- Reset on episode, dataset/sample, policy-role, and failed/quarantined boundaries. Never carry
  across episodes, shards, seeds, players, or collection jobs.
- Use one state per acting policy/player perspective, not one global game-state RNN. Even shared
  weights use separate seat state. This avoids combining distinct private information sets and
  prevents model memory from becoming global Rules state.
- Burn-in updates state with loss mask 0; learning rows have loss mask 1.
- Padding is structural with validStepMask 0; never a legal candidate and never a loss contribution.
- Candidate rows remain ragged/packed. Candidate padding never changes actual score count.
- Do not expose isLast, closure, future episode length, winner, or a window position revealing future.
- First row has explicit BOS/no-previous-input; it does not borrow another episode/player.

The source has no public event history or model-owned stable cross-step alias. The recurrent wrapper
may summarize prior same-perspective samples but may not reconstruct missing history or use raw
EntityIds as memory keys. Richer history/identity is BLOCKED_ON_ENGINE_DEPENDENCY.

RECURRENT_SEQUENCE_CONTRACT=READY_TO_FREEZE for reset/order/window semantics.

## 8. Feed-forward versus recurrent causal experiment

Compare exactly:

~~~text
FF:  ObservationEncoderV1 + CandidateEncoderV1 + CandidateScorerV1
RNN: same encoders/scorer wrapped by SequenceViewV1 recurrent state
~~~

Use the same trusted dataset manifest, frozen split, episode grouping, encoders, adapters,
optimizer, training budget, numeric precision, matched RNG identities where supported, validation-
only selection, fixed gameplay matrix, and no fallback. Only recurrence, reset, burn-in, and
windows change.

Report exact top-1/NLL, structured exact response/components, family/candidate-count/sequence-gap
bins, calibration/entropy, rare families, clustered uncertainty, fixed-cell gameplay, first
divergence, and systems cost. Do not assume partial observability implies a recurrent win.

## 9. Dataset split contract

### 9.1 Conceptual SplitContractV1

~~~text
SplitContractV1 {
    version: 1
    schemaIdentity: "argentum-dataset-split@v1"
    datasetManifestId
    groupingRuleIdentity
    groupingInputDigest
    assignmentHashIdentity
    trainEpisodeIds[]
    validationEpisodeIds[]
    testEpisodeIds[]
    testFrozen: boolean
    frozenAtSourceCommit?
}
~~~

The split identity hashes membership, grouping input, assignment rule, and dataset manifest identity.
Every derived row/window inherits episode membership. A row/window cannot appear in more than one
member set.

### 9.2 Unit and grouping

- The minimum unit is a complete episode; never randomize decision rows.
- All recurrent windows from one episode remain in one split.
- Replay frames, chosen-input bindings, reanalysis, and derived compact rows inherit membership.
- Related generated variants must be grouped when generation establishes one statistical unit.
  For four orientation/start variants sharing one numeric engine seed, do not infer counterfactual
  equivalence from seed equality. Require an explicit schedule/variant-family identity or a
  source-owned seed-coupling characterization.
- Until characterized, the split is not final. Conservative grouping may include the full declared
  variant family, but records external grouping input rather than inventing a TrajectoryV1 field.

Recommended deterministic assignment:

~~~text
digest = SHA-256("argentum-dataset-split@v1\n" + canonical(groupKey))
bucket = first_8_bytes_big_endian(digest) mod 1000
train: bucket < 800
validation: 800 <= bucket < 900
test: 900 <= bucket < 1000
~~~

These thresholds are a C0 default, not a final statistical claim. Freeze test membership before
model/hyperparameter selection. A new dataset/grouping/schema means a new split identity.

SPLIT_CONTRACT=NEEDS_CHARACTERIZATION. Physical split materialization is BLOCKED_ON_#119 only where
large-scale I/O/footprint evidence is needed.

## 10. Frozen evaluation contract

### 10.1 Offline policy quality

Report exact top-1 agreement, NLL/cross-entropy over full supplied domains, family-level metrics,
candidate-count and perspective strata, structured exact response plus separately labeled component
metrics, calibration/entropy, rare-family metrics, long/short stream bins, and episode-clustered
uncertainty. Interrupted rows may be used for policy imitation metrics but cannot create an outcome
label. Failed/quarantined records are not trusted evaluation samples.

### 10.2 Gameplay quality

Bind each result to environment/source commit, locked decks/roster, replay/schema identity,
checkpoint semantic identity, inference contract, seed/start cell, fallback status, and replay
verification.

Initial fixed cells:

~~~text
Akiri vs Chevill, starting player 0
Akiri vs Chevill, starting player 1
Chevill vs Akiri, starting player 0
Chevill vs Akiri, starting player 1
~~~

Report each cell separately with wins/losses/draws, interruptions, completed games, and replay
verification. Use paired fixed seeds and clustered uncertainty. Never promote one noisy aggregate
win rate.

First-divergence analysis compares policies from the same verified initial condition and records the
first semantic decision difference, family, domain digest, candidate count, semantic difference,
legality, and replay result. It does not infer a winner from divergence alone.

### 10.3 Systems performance

Measure model encode/score latency by entity/candidate count, decision latency, memory peak,
ragged batching, reader throughput, shard I/O, compression bytes, and large-corpus footprint.
Reader/storage/large-corpus measurements belong to #119 and must not cause a semantic TrajectoryV1
rewrite.

EVALUATION_CONTRACT=READY_TO_FREEZE semantically.
EVALUATION_SYSTEMS_MEASUREMENT=BLOCKED_ON_#119.

## 11. Checkpoint identity and provenance contract

### 11.1 Conceptual ArgentumCheckpointManifestV1

~~~text
ArgentumCheckpointManifestV1 {
    version: 1
    schemaIdentity: "argentum-checkpoint-manifest@v1"
    semanticCheckpointId

    architectureIdentity
    modelConfigDigest
    observationEncoderIdentity
    candidateEncoderIdentity
    structuredDecisionAdapterIdentity
    sequenceContractIdentity
    datasetManifestIdentity
    splitIdentity
    trainingConfigDigest
    sourceCommit
    parentCheckpointId?
    rngIdentities {
        initialization
        dataOrder
        dropoutOrModelSampling?
        evaluationSampling?
    }
    frameworkRuntimeProvenance
    weightsContentDigest
    weightArtifactFormat
    physicalArtifacts[]
}
~~~

semanticCheckpointId is a content digest over canonical manifest fields with the self-reference,
mutable aliases, local paths, hostnames, and timestamps excluded. It includes weightsContentDigest
and every contract/config/source identity. Dataset and split identities validate before evaluation.

| Thing | Meaning | Can change without semantic identity changing? |
| --- | --- | --- |
| Semantic checkpoint ID | Immutable Argentum identity of exact model/weights/contracts/provenance. | No. |
| Physical weight file | Bytes at a location, identified by content digest and format. | Path may move; bytes may not change under same identity. |
| Human alias | latest, best, run ID, external run ID, or local path. | Yes; never an identity. |

Framework/runtime provenance records resolved framework, libraries, compiler, device, precision, and
runtime. Hostname/timestamp are not identities. A parent checkpoint is an identity link, not a path.

CHECKPOINT_IDENTITY_CONTRACT=READY_TO_FREEZE conceptually; implementation is deferred.

## 12. Deterministic inference contract

For deterministic evaluation:

~~~text
same checkpoint semantic identity
+ same model input
+ same complete candidate/domain order
+ same declared numeric/runtime contract
=> same finite score vector
=> same selected semantic candidate/response
~~~

The inference manifest declares tie epsilon. Scores within epsilon are a tie; the lowest supplied
candidate ordinal wins. It does not sort by card name or raw ID.

| Case | Required behavior |
| --- | --- |
| Score count differs from candidate count | Reject; no truncation or padding-as-action. |
| NaN or infinity | Reject; do not replace or renormalize. |
| Empty flat/folded domain | Reject; do not invent Pass Priority or call native AI. |
| Structured domain lacks valid published response surface | Reject; do not enumerate from GameState or heuristics. |
| Unknown family/nested version | Reject before output is used. |
| Schema/encoder/checkpoint/split mismatch | Reject before inference. |
| Target outside stored domain | Reject through semantic membership validation. |
| Model failure/timeout | Fail evaluation cell; no fallback action. |

A structured response may contain multiple values, but every value must originate in the published
domain and pass ChosenSemanticResponseV1. The model is not a legality authority.

Distinguish semantic decision reproducibility, numerical score reproducibility within declared
tolerance, and bitwise tensor reproducibility under a pinned runtime/device/precision contract.
Future stochastic sampling has a separate RNG identity, seed, candidate order, and draw protocol.

DETERMINISTIC_INFERENCE_CONTRACT=READY_TO_FREEZE.

## 13. Factual outcome, supervised value target, and RL reward

### 13.1 Factual closure

| Closure | Factual meaning | Outcome label |
| --- | --- | --- |
| GAME_TERMINAL with known winner | Natural Rules terminal state. | Perspective-relative win/loss. |
| GAME_TERMINAL with winner null and reason DRAW | Factual draw. | Perspective-relative zero/draw. |
| INTERRUPTED | Controlled horizon/cancellation before terminal. | No winner and no terminal label. |
| FAILED | Integrity/engine/contract failure and quarantine boundary. | Not a trusted target. |

If terminal closure has no winner and no explicit draw reason, the value builder fails closed; it
does not infer draw.

### 13.2 SupervisedValueTargetV1

~~~text
SupervisedValueTargetV1 {
    version: 1
    schemaIdentity: "argentum-supervised-value-target@v1"
    sourceTrajectoryId
    semanticEpisodeId
    decisionIndex
    perspectivePlayerId
    outcomeKind: WIN | LOSS | DRAW | UNAVAILABLE
    value: +1.0 | 0.0 | -1.0 | null
    targetMask: 1 | 0
    perspectiveConvention: "current-actor"
    discountConvention: "undiscounted-terminal"
    bootstrapConvention: "none"
}
~~~

For GAME_TERMINAL, use +1/0/-1 from actor perspective and mask 1. For INTERRUPTED, use
UNAVAILABLE, null, and mask 0. No bootstrapped or discounted alternative is frozen. A learned value
never overrides closure, Rules, legal domains, or replay.

SUPERVISED_VALUE_TARGET_BOUNDARY=READY_TO_FREEZE.

### 13.3 RL reward

~~~text
RL_REWARD_CONTRACT=DEFER_TO_C1
~~~

C0 does not define shaping, discounting, bootstrap, clipping, advantage estimation, or an RL
algorithm. PPO, R2D2, IMPALA, APPO, AWR, ExIt, MuZero, MCTS, world model, hierarchy, league
self-play, and large-corpus generation remain unauthorized.

## 14. Teacher/bootstrap provenance

Collection behavior and teacher supervision are separate. The current deterministic A9 policy is
collection provenance, not a strategically strong teacher.

A future TeacherDecisionProvenanceV1 must bind:

~~~text
teacherPolicyIdentity
teacherCheckpointIdentity?       // required when learned
teacherSearchIdentity?           // required when search-generated
teacherRngIdentity?
teacherConfigIdentity
sourceDatasetManifestIdentity
sourceTrajectoryId
semanticEpisodeId
decisionIndex / semanticDecisionId
teacherOutputDigest
~~~

Teacher output must be a complete legal semantic action/response bound to the supplied domain. This
provenance is audit-only and never model input. A collection policy may supply behavior labels, but
no strategic-quality claim is made without separately bound teacher identity and evaluation.

TEACHER_PROVENANCE_CONTRACT=READY_TO_FREEZE.

## 15. Small learner pipeline smoke specification

This is a future pipeline gate, not training authorization. It uses about 1,000–10,000 trusted
decisions from an accepted published dataset and does not generate a new large corpus.

~~~text
strict TrajectoryV1Reader
  -> deterministic model-facing adapter
  -> full variable-candidate/ragged batching
  -> observation + candidate scorer/family adapters
  -> chosen-candidate/structured target
  -> finite loss and gradients
  -> tiny overfit on a tiny train fixture
  -> checkpoint manifest and weight save/load
  -> deterministic inference replay
~~~

Required future acceptance:

~~~text
TRUSTED_DATA_ONLY=YES
VARIABLE_CANDIDATE_BATCHING=PASS
NO_CANDIDATE_TRUNCATION=PASS
CHOSEN_TARGET_IN_DOMAIN=PASS
FINITE_LOSS_GRADIENTS=PASS
TINY_OVERFIT=PASS
CHECKPOINT_ROUNDTRIP=PASS
INFERENCE_CONTRACT=PASS
PROVENANCE=PASS
GAMEPLAY_STRENGTH_CLAIMED=NO
~~~

The fixture must include flat action, folded decision, structured target/card selection, payment
domain, repeat-count payload, and an interrupted episode with masked value target. A passing smoke
proves wiring only; it does not authorize Behavior Cloning, recurrence, RL, gameplay claims, or
large-corpus generation.

SMALL_LEARNER_SMOKE_SPEC=DEFER_TO_C1; design recorded, TRAINING_AUTHORIZED=NO.

## 16. Interaction with measurement tracker #119

Semantic C0 work does not wait on physical measurements that cannot change authority. The allowed
future pattern is:

~~~text
TrajectoryV1
  -> trusted strict reader
  -> derived learner view (possible compact/columnar form)
~~~

The derived view binds source dataset manifest, split, learner-view schema, and content digest. It
cannot replace the source trajectory or repair malformed records.

Defer to #119: NDJSON/compression efficiency, shard/reader/batch I/O throughput, memory footprint,
and large-corpus feasibility. Do not defer privacy, domain completeness, decision identity,
chronology/reset, closure/reward separation, or checkpoint identity.

~~~text
SEMANTIC_C0_DEPENDENCY_ON_#119=NO
PHYSICAL_SCALING_DEPENDENCY_ON_#119=YES
TRAJECTORY_V1_CHANGE_AUTHORIZED=NO
~~~

## 17. Architecture options

No final neural architecture is locked.

| Option | Variable entities/candidates | Relations | Cost/memory | Partial observability | C0 assessment |
| --- | --- | --- | --- | --- | --- |
| A. Pooled entity encoder + candidate scorer | Natural ragged pooling and candidate scoring. | Needs explicit relation summaries; weak with only mean pooling. | Lowest; good reference. | Current public snapshot only. | Required stateless reference. |
| B. Attention/set-based observation encoder + candidate scorer | Natural variable entity sets and candidate cross-attention. | Stronger public entity/attachment/stack relations. | Moderate; masking must be explicit. | Summarizes current public information without inventing history. | Preferred first expressive experiment. |
| C. Transformer-style entity/candidate attention | Rich variable interactions. | Strongest expressiveness. | Highest compute/memory and audit complexity. | Still cannot recover omitted history/hidden state. | Later hypothesis. |
| D. Recurrent wrapper around shared encoder | Variable input handled by shared encoder; state is external. | Depends on A/B/C and reset rules. | Adds state/burn-in/latency and leakage risk. | May help only if history adds signal. | Compare after FF baseline. |

Recommended order is A as lowest-variance reference, B as first expressive scorer, then D as a
paired recurrent wrapper. C remains a later hypothesis.

FINAL_MODEL_ARCHITECTURE_LOCKED=NO.

## 18. Open questions and dependencies

| Question/dependency | Why it matters | Boundary |
| --- | --- | --- |
| Commander damage/tax/commander metadata absent from PlayerObservationV1. | No authoritative Commander feature can be learned from the current public source. | Future versioned environment/observation contract. BLOCKED_ON_ENGINE_DEPENDENCY. |
| Public action/event history absent from observation. | Recurrent adapter cannot claim a full public event log. | Future environment-owned history contract. BLOCKED_ON_ENGINE_DEPENDENCY. |
| Stable cross-step entity aliases not separately specified. | Raw UUID/string IDs must not become learned identity features. | C0 uses per-step relation keys; persistent memory needs characterization/new contract. NEEDS_CHARACTERIZATION. |
| Four orientation/start variants reuse numeric seeds. | Seed equality does not prove statistical coupling. | Explicit schedule/variant characterization before split freeze. NEEDS_CHARACTERIZATION. |
| Structured responses are typed constraints, not universally enumerated candidates. | Universal softmax risks truncation or a second legality engine. | Preserve family adapters; enumeration requires future source contract. READY_TO_FREEZE for B. |
| Printed oracleText can differ from effective copied text. | It is a public feature, not complete Rules semantics. | Encode source text only. READY_TO_FREEZE with limitation. |
| Physical storage/reader/batch scaling. | A derived layout may be needed without semantic drift. | #119. BLOCKED_ON_#119 for physical choices only. |
| Teacher quality and RL reward. | Collection provenance is not teacher strength; facts are not rewards. | Separate C1 decisions. DEFER_TO_C1. |

## 19. Recommended C0 implementation sequence

These are future implementation gates, not changes authorized by this report:

1. Freeze a read-only model-view specification and field-level privacy/authority fixtures against
   TrajectoryV1Reader; do not alter source contracts.
2. Add deterministic adapter fixtures for every accepted flat/folded and reachable structured
   family. Assert exact cardinality, target membership, required fields, and no routing/hidden
   fields.
3. Characterize seed-family grouping and create content-addressed SplitContractV1; freeze test
   membership before model selection.
4. Build offline metrics reporting family, candidate-count, perspective, episode, and uncertainty
   strata. Keep systems measurements separate.
5. Freeze the conceptual checkpoint manifest and round-trip it against immutable weight artifacts;
   aliases and paths remain outside semantic identity.
6. After separate training authorization, run only the small learner smoke and make no gameplay claim.
7. Use the feed-forward reference before any recurrent experiment. Keep value, RL, search, and
   self-play decisions separate.

No step authorizes changes to Rules, Gym/replay semantics, TrajectoryV1, locked decks, or privacy.

## 20. Independent design review

| Review axis | Result | Evidence |
| --- | --- | --- |
| Privacy/perspective | PASS | §§2–3; no hidden reconstruction or raw state. |
| Future leakage | PASS | §§2, 7, 13; outcome/closure is not current input. |
| Candidate completeness | PASS for flat/folded | §4; exact score cardinality and no truncation. |
| Decision-family completeness | PASS for accepted Environment V1 inventory | §§4–5; unknown families fail closed. |
| Structured-domain authority | PASS | §5; typed adapters preserve the domain. |
| Sequence reset semantics | PASS | §7; no cross-episode/player state carry. |
| Split leakage | NEEDS_CHARACTERIZATION | §9; seed-family coupling is not inferred. |
| Checkpoint provenance | PASS as design | §11; semantic ID, artifact, alias are distinct. |
| Replay/data authority | PASS | §§1–2 and §16; reader/replay stay upstream. |
| Value/reward separation | PASS | §13; INTERRUPTED has no terminal target and RL is deferred. |
| Algorithm neutrality | PASS | §§6, 8, 17; no final architecture/RL algorithm. |
| Commander/history source gaps | BLOCKED_ON_ENGINE_DEPENDENCY | §§3.3 and 18; no invented feature. |

## 21. Required decision table

~~~text
MODEL_FACING_SAMPLE_CONTRACT=READY_TO_FREEZE
OBSERVATION_ENCODING_CONTRACT=NEEDS_CHARACTERIZATION
CANDIDATE_SCORING_CONTRACT=READY_TO_FREEZE
STRUCTURED_DECISION_MODEL=B
STRUCTURED_DECISION_STATUS=READY_TO_FREEZE
FEED_FORWARD_REFERENCE=READY_TO_FREEZE
RECURRENT_SEQUENCE_CONTRACT=READY_TO_FREEZE
SPLIT_CONTRACT=NEEDS_CHARACTERIZATION
EVALUATION_CONTRACT=READY_TO_FREEZE
EVALUATION_SYSTEMS_MEASUREMENT=BLOCKED_ON_#119
CHECKPOINT_IDENTITY_CONTRACT=READY_TO_FREEZE
DETERMINISTIC_INFERENCE_CONTRACT=READY_TO_FREEZE
SUPERVISED_VALUE_TARGET_BOUNDARY=READY_TO_FREEZE
RL_REWARD_CONTRACT=DEFER_TO_C1
TEACHER_PROVENANCE_CONTRACT=READY_TO_FREEZE
SMALL_LEARNER_SMOKE_SPEC=DEFER_TO_C1

FINAL_MODEL_ARCHITECTURE_LOCKED=NO
FINAL_RL_ALGORITHM_LOCKED=NO
TRAINING_STARTED=NO
~~~

## 22. Scope and completion status

~~~text
TASK=C0_CONTRACT_FREEZE_RESEARCH_01
PRODUCTION_CODE_CHANGED=NO
TRAINING_CODE_CHANGED=NO
TRAINING_RUN=NO
DATASET_GENERATION_RUN=NO
ISSUE_STATE_CHANGED=NO
PR_CREATED=NO
C0_CONTRACT_RESEARCH_IMPLEMENTATION_PASS=YES (documentation-only design artifact)
C0_CODE_REVIEW_PASS=NO
C0_FINAL_ACCEPTANCE_PASS=NO
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
~~~
