# C0 Teacher/bootstrap and value/reward boundary contract V1

## 1. Status, authority, and scope

    TASK=C0_05_TEACHER_BOOTSTRAP_AND_VALUE_REWARD_BOUNDARY_CONTRACT
    DATE=2026-09-12
    STATUS=DRAFT_SPECIFICATION_PENDING_INDEPENDENT_EXACT_SHA_REVIEW
    AUDIT_BASE=b1fb517446b16f4fbab612a2f290a99d0b383ae8
    ORIGIN_MAIN_AT_AUDIT=b1fb517446b16f4fbab612a2f290a99d0b383ae8
    UPSTREAM_MAIN_AT_AUDIT=3f46367d87c88bcf156a843a9e69fd29e1693872
    ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
    UPSTREAM=https://github.com/wingedsheep/argentum-engine.git
    UPSTREAM_INTEGRATED=NO
    WRITABLE_REPOSITORY=chrismaghuhn/argentum-engine

    C0_05_SCOPE=DOCUMENTATION_ONLY
    PRODUCTION_CODE_CHANGED=NO
    RULES_CODE_CHANGED=NO
    GYM_SEMANTICS_CHANGED=NO
    TRAJECTORY_SCHEMA_CHANGED=NO
    MODEL_CODE_CHANGED=NO
    TRAINING_CODE_CHANGED=NO
    TEACHER_IMPLEMENTATION_CHANGED=NO
    CHECKPOINT_IMPLEMENTATION_CHANGED=NO
    C1_STARTED=NO
    TRAINING_STARTED=NO
    SELF_PLAY_STARTED=NO
    LARGE_CORPUS_GENERATION_AUTHORIZED=NO

Origin main was fetched and equals AUDIT_BASE. The accepted C0 predecessor state is:

    C0_01_FINAL_ACCEPTANCE_PASS=YES
    C0_02_FINAL_ACCEPTANCE_PASS=YES
    C0_03_FINAL_ACCEPTANCE_PASS=YES
    C0_04_FINAL_ACCEPTANCE_PASS=YES
    C0_04_FINALIZATION_COMPLETE=YES
    COMMANDER_ENVIRONMENT_V1_COMPLETE=YES
    DATA_TRUSTED=YES
    CURRENT_PHASE=C0
    C0_AUTHORIZED=YES
    C1_AUTHORIZED=NO
    TRAINING_AUTHORIZED=NO

Issue #124 is roadmap authority. Issue #137 is the physical learner/tooling boundary. Issue #119
is the post-B2 measurement and scaling context. None authorizes implementation or training here.

## 2. Contract identities

These are documentation-level semantic identities, not production DTOs:

    TEACHER_BOOTSTRAP_CONTRACT_ID=argentum-ml-teacher-bootstrap@v1
    SUPERVISED_POLICY_TARGET_CONTRACT_ID=argentum-ml-supervised-policy-target@v1
    SUPERVISED_VALUE_TARGET_CONTRACT_ID=argentum-ml-supervised-value-target@v1
    RL_REWARD_CONTRACT_ID=argentum-ml-rl-reward@v1

Unknown versions fail closed. These contracts define provenance, admissibility and meaning for later
C1 work; they do not implement a Teacher, learner, value head, reward emitter or training loop.

The durable authority chain remains:

    PlayerObservationV1
    + complete currently legal candidate/domain
    + chosen semantic action/response
    -> factual TrajectoryV1 source
    -> separately derived Teacher/policy/value/reward interpretation

Argentum remains sole authority for Magic rules, legal domains, perspective-safe observations,
accepted semantic choices, environment transitions, replay, episode closure and dataset admission.

## 3. Accepted C0 dependencies and source audit

| Source | Boundary consumed |
| --- | --- |
| [C0 model-facing sample and candidate scoring](c0-model-facing-sample-and-candidate-scoring-contract-v1.md) | Perspective-safe feature admission, complete domains, candidate multiplicity, exact chosen-label binding and structured decision ownership. |
| [C0 split and frozen evaluation](c0-split-and-frozen-evaluation-contract-v1.md) | Episode-level split identity, frozen TEST protection, evaluation provenance and leakage controls. |
| [C0 sequence/reset/recurrent view](c0-sequence-reset-and-recurrent-derived-view-contract-v1.md) | Recurrent state ownership, reset, causality, previous-choice history and source-teacher-forced offline semantics. |
| [C0 checkpoint and inference](c0-checkpoint-identity-and-deterministic-inference-contract-v1.md) | Checkpoint identity, numeric profile, Selection V2, PolicyTieRng V1 and reserved Teacher/bootstrap provenance. |
| [C0_04A characterization](c0-environment-v1-tie-totality-characterization-2026-09-12.md) | Deterministic Environment V1 totality remains NO for reachable symmetry. |
| [C0_04B policy RNG](c0-policy-rng-and-symmetry-resolution-contract-v1.md) | Reproducible unresolved-tie symmetry resolution only; no general stochastic sampling. |
| Issue #124 | C0 to C1 sequencing, Teacher/bootstrap before RL, and later value/reward work. |
| Issue #137 | Replaceable physical ML tooling; it cannot redefine semantic authority. |
| Issue #119 | Measured data/storage/scaling context; no large corpus is authorized here. |

The source audit also covered:

| Source | Exact finding |
| --- | --- |
| gym/src/main/kotlin/com/wingedsheep/gym/EpisodeClosure.kt | EpisodeClosureV1 has GAME_TERMINAL, INTERRUPTED and FAILED. |
| gym/src/main/kotlin/com/wingedsheep/gym/GameEnvironment.kt | Failure precedence, terminal/truncated derivation and runtime closure ownership. |
| rules-engine/src/main/kotlin/com/wingedsheep/engine/core/GameEvent.kt | GameEndReason vocabulary and winner/event facts. |
| gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1.kt | Policy provenance, metadata, decision records, DatasetManifestV1 and validation/admission. |
| gym/src/test/kotlin/com/wingedsheep/gym/EpisodeClosureContractTest.kt | Winner, draw, horizon interruption and failure closure behavior. |
| gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1TrustedGenerationTest.kt | Actual bounded A9 producer, policy provenance and admission evidence. |
| gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt | Test-only public-observation policy input and deterministic source-choice behavior. |

## 4. Fundamental trust separation

    SOURCE_DATA_TRUST != TEACHER_QUALITY
    TRUSTED_TRAJECTORY != OPTIMAL_TEACHER_TRAJECTORY
    LEGAL_ACCEPTED_SOURCE_CHOICE != BEST_ACTION
    TEACHER_LABEL_IS_GROUND_TRUTH_OPTIMAL_ACTION=NO

DATA_TRUSTED=YES means that the source episode, public observations, complete domains, chosen
semantic values, replay linkage, closure metadata and admission checks satisfy their source
contracts. It says nothing about strategic strength.

Teacher quality is a separately measured property of a label-producing process. A weak but
reproducible behavior policy can produce trusted source data. A strong-looking policy cannot make
malformed, privacy-invalid or unadmitted data trusted.

## 5. Factual source choice versus derived Teacher label

TrajectoryV1 stores the factual accepted source choice:

    chosenSemanticAction
    or
    chosenSemanticResponse

That value answers what the source behavior policy actually selected. It is not automatically
optimal and is not itself a C1 learner label.

    SOURCE_CHOSEN_SEMANTIC_VALUE
    = what the behavior policy actually chose

    BOOTSTRAP_POLICY_TARGET
    = a derived supervised label admitted for a declared training purpose

For ordinary behavior cloning these may point to the same semantic value. Their authority roles
remain different: TrajectoryV1 is immutable factual source; the bootstrap target is versioned derived
data with Teacher/source and materializer provenance.

## 6. Existing PolicyProvenanceV1 and current trusted producer

PolicyProvenanceV1 is the existing source provenance container:

    version
    schemaIdentity
    behaviorPolicyIdentity
    opponentPolicyIdentity
    behaviorPolicyRole
    opponentPolicyRole
    policyRngIdentity
    policySeed
    policySourceIdentity

The source requires these values to be non-blank and includes them in collection-job identity.
Policy provenance is excluded from semantic episode and decision identity. It does not measure
Teacher quality.

The current bounded trusted A9 producer supplies:

    behaviorPolicyIdentity=b2-a9-deterministic-external-policy@v1
    opponentPolicyIdentity=b2-a9-deterministic-external-policy@v1
    behaviorPolicyRole=EXTERNAL_CONTROLLER
    opponentPolicyRole=EXTERNAL_CONTROLLER
    policyRngIdentity=explicit-seed/kotlin-policy-state-v1
    policySeed=
      spec.seed * 1_000_003
      + spec.startingPlayerIndex * 97_409
      + rosterCode * 65_537
    policySourceIdentity=
      EnvironmentV1ExternalPolicy.kt@sha256:72F5D98588CF6815E70E7B9982A028DCD337B3EDC337521EA1826E31EADB6B8F

The A9 harness uses a wire TrainingObservation and explicit DeterministicPolicyState. The
test-only policy has no environment, Rules state, registry or diagnostic-ledger dependency. The
bounded harness covers a 64-episode primary matrix, a bounded extension up to 72, replay and
serialization/privacy checks, and four deterministic regeneration spot checks.

This proves source identity, public-input use, bounded reproducibility and admission behavior:

    POLICY_PROVENANCE_PROVES_SOURCE_IDENTITY=YES
    POLICY_PROVENANCE_PROVES_TEACHER_QUALITY=NO
    A9_SOURCE_POLICY_PROVENANCE_VALID=YES
    A9_SOURCE_POLICY_QUALITY_CHARACTERIZED=NO

The legacy explicit-seed/kotlin-policy-state-v1 identity is not retroactively interpreted as the
C0_04B PolicyTieRng V1 identity.

## 7. Teacher definition and identity

A Teacher is an explicitly identified policy or label-producing process whose output may be
admitted for a declared bootstrap purpose. Teacher kind alone is never identity.

Possible future kinds include an existing deterministic heuristic, an existing provenance-bound
behavior policy, an accepted model checkpoint, a perspective-safe search Teacher, a reanalysis
policy or a separately admitted human-derived source. These are not pre-authorized.

The documentation-level Teacher provenance shape is:

    TeacherProvenanceV1 {
        teacherContractIdentity
        teacherKind
        teacherPolicyIdentity
        teacherSourceIdentity
        sourceCommit
        teacherConfigurationIdentityOrDigest
        checkpointIdentity when model-backed
        selectionContractIdentity when model-backed
        numericExecutionProfileIdentity when model-backed
        policyRngIdentity and seed/state contract where relevant
        searchOrPlannerIdentity when search-backed
        searchBudgetOrConfiguration when search-backed
        sourceDatasetIdentity
        sourceTrajectoryOrDecisionIdentity where applicable
        labelMaterializerIdentity
    }

Operational locators are not semantic identity: path, hostname, PID, worker/batch slot, Kaggle
notebook ID, HF Hub mutable alias, wall time, completion order, latest, best and filename-only
identity are forbidden.

The teacherBootstrapProvenance reserved by C0_04 references an immutable Teacher/bootstrap
artifact or manifest identity. It is not a new TrajectoryV1 field.

## 8. Teacher information-set boundary

    TEACHER_INPUT_INFORMATION_SET=
    ACTING_PLAYER_LEGAL_INFORMATION_SET

The initial trusted path consumes:

    PlayerObservationV1
    + complete current legal domain
    + permitted accepted public history

Forbidden inputs:

    RAW_GAME_STATE_AS_TEACHER_INPUT=NO
    OPPONENT_TRUE_HIDDEN_HAND_AS_TEACHER_INPUT=NO
    TRUE_HIDDEN_LIBRARY_ORDER_AS_TEACHER_INPUT=NO
    HIDDEN_EXILE_OR_FACE_DOWN_FACTS_AS_TEACHER_INPUT=NO

A Teacher must not become a hidden-state oracle. Any future information-set change requires a
separate purpose and privacy contract.

## 9. Search Teacher boundary

    PERFECT_INFORMATION_SEARCH_TEACHER_FOR_BOOTSTRAP=NOT_AUTHORIZED

Exact-engine search Teacher implementation is not authorized. A future search Teacher requires
separate evidence for snapshot/restore fidelity, fork RNG isolation, no cross-fork contamination,
replay/audit compatibility and information-set privacy. Raw hidden GameState cannot teach a
public-perspective learner.

## 10. Teacher output validity and failure

    TEACHER_MAY_INVENT_ACTION=NO
    TEACHER_MAY_REPAIR_DOMAIN=NO
    TEACHER_MAY_TRUNCATE_DOMAIN=NO
    TEACHER_MAY_AUTOPAY=NO
    TEACHER_MAY_USE_FIRST_LEGAL_FALLBACK=NO

Every admitted label is one exact source-authorized semantic action or response from the complete
supplied domain. Raw candidate row, ephemeral action ID, runtime EntityId ranking and batch
position are not durable label identity.

Teacher failure includes unsupported decision family, invalid semantic response, timeout, crash,
missing checkpoint, numeric-profile mismatch, non-finite output, selection mismatch, privacy
violation and domain mismatch:

    TEACHER_FAILURE_LABEL=NO_LABEL
    TEACHER_FAILURE_SOURCE_SUBSTITUTION=NO
    TEACHER_FAILURE_RANDOM_RETRY=NO
    TEACHER_FAILURE_FIRST_LEGAL_REPAIR=NO

No failure silently substitutes a source choice, random legal label or heuristic repair.

## 11. Teacher quality and admission

Teacher quality evidence may include frozen gameplay evaluation, decision-family behavior, offline
source-choice agreement, critical-decision analysis, seat/start orientation cells, failure rate,
unsupported-decision rate and explicitly defined uncertainty. No unsupported single scalar is
declared here.

    OFFLINE_TEACHER_AGREEMENT != GAMEPLAY_STRENGTH
    GAMEPLAY_WIN_RATE != SOURCE_DATA_TRUST

Admission statuses remain independent:

    TEACHER_PROVENANCE_VALID
    TEACHER_INFORMATION_SET_VALID
    TEACHER_DOMAIN_BINDING_VALID
    TEACHER_FAILURE_RATE_CHARACTERIZED
    TEACHER_QUALITY_CHARACTERIZED
    TEACHER_BOOTSTRAP_ADMITTED

For the existing A9 source-policy candidate:

    TEACHER_PROVENANCE_VALID=YES
    TEACHER_INFORMATION_SET_VALID=YES_FOR_PUBLIC_OBSERVATION_INPUT
    TEACHER_DOMAIN_BINDING_VALID=YES_FOR_ADMITTED_A9_RECORDS
    TEACHER_FAILURE_RATE_CHARACTERIZED=YES_WITHIN_BOUNDED_A9_HARNESS
    TEACHER_QUALITY_CHARACTERIZED=NO
    TEACHER_BOOTSTRAP_ADMITTED=NO

The first C1 Teacher remains deferred:

    FIRST_C1_TEACHER_SELECTION=DEFERRED_TO_C1_CHARACTERIZATION
    REASON=
      A9 provenance is source-backed, but strategic quality and Selection-V2-compatible
      bootstrap suitability are not separately characterized or admitted.

## 12. Bootstrap dataset authority, immutability, and split

    TRAJECTORY_V1_IS_AUTHORITATIVE_SOURCE=YES
    BOOTSTRAP_DATASET_IS_AUTHORITATIVE_SOURCE=NO

Derived bootstrap materialization binds:

    accepted DatasetManifestV1
    + C0_02 split identity
    + source trajectory/decision identity
    + Teacher or source-policy identity
    + target-contract identity
    + materializer identity
    -> immutable derived bootstrap artifact

Never rewrite chosenSemanticAction, chosenSemanticResponse, closure, policy provenance or episode
metadata because a newer Teacher disagrees:

    TEACHER_RELABEL_REWRITES_TRAJECTORY_V1=NO

Teacher derivation inherits the C0_02 episode split:

    semanticEpisodeId
    -> C0_02 deterministic split
    TEACHER_DERIVATION_CHANGES_SPLIT=NO
    NO_DECISION_ROW_RANDOM_SPLIT=YES
    NO_TEACHER_SPECIFIC_REHASH=YES

The final TEST partition remains protected:

    FINAL_TEST_USED_FOR_TRAINING=NO
    FINAL_TEST_USED_FOR_HYPERPARAMETER_SELECTION=NO
    FINAL_TEST_USED_TO_SELECT_TEACHER=NO

## 13. Supervised policy target

    SUPERVISED_POLICY_TARGET_SOURCE=
    accepted semantic source choice
    or
    separately admitted Teacher-derived semantic choice

    TARGET_IN_COMPLETE_DOMAIN=YES
    TARGET_SOURCE_BINDING_EXACT=YES
    SUPERVISED_POLICY_TARGET_AS_MODEL_INPUT=NO

    SUPERVISED_POLICY_TARGET_MEANS=
    imitate this admitted Teacher/source choice
    BEHAVIOR_CLONING_LOSS=IMITATION_OBJECTIVE

This is not an objectively optimal action, game-theoretic proof or Rules authority. Every policy
label must retain exact source decision, complete domain, semantic label, Teacher/source policy,
Teacher contract/version, split, source dataset/trajectory and materializer.

## 14. Structured bootstrap and symmetry

Policy targets preserve C0_01/C0_04 ownership for priority/actions, targets, modes, X, payments,
card selection, attackers, blockers, yes/no, ordering, damage assignment, replacement choices,
trigger ordering, search/reorder and commander-zone choices.

The model may not choose a root while an engine, AutoPay path or hidden heuristic chooses a
subdecision. A missing or unsupported typed domain produces no label.

    FEATURE_IDENTICAL_SYMMETRY_ALLOWED=YES

Distinct source alternatives may have identical admitted features. No hidden feature makes labels
unique. Selection V2 plus PolicyTieRng V1 resolves inference symmetry without making a source label
an optimality proof.

## 15. Training objective provenance

Every future training run binds:

    TRAINING_OBJECTIVE_IDENTITY
    policy target contract
    value target contract if enabled
    loss family
    mask semantics
    structured-decision loss semantics
    loss weighting where relevant

Optimizer, learning rate, batch size, PyTorch, JAX and Accelerate are not selected here.

## 16. Exact EpisodeClosureV1 authority

The current source defines:

    EpisodeClosureV1.Kind=
      GAME_TERMINAL
      INTERRUPTED
      FAILED

GameTerminal carries stepCount, nullable winnerId and nullable GameEndReason. A null reason means
the Rules state was authoritative but supplied no event reason. Interrupted carries exactly
HORIZON_REACHED or CALLER_CANCELLED. Failed carries exactly UNSUPPORTED_DIAGNOSTIC,
PUBLIC_CHOICE_REJECTED, ENGINE_EXCEPTION or OBSERVATION_FAILURE.

The current GameEndReason vocabulary is:

    LIFE_ZERO
    DECK_EMPTY
    POISON_COUNTERS
    CONCESSION
    ALTERNATIVE_WIN
    CARD_EFFECT
    DRAW
    COMMANDER_DAMAGE
    TEAM_DEFEATED
    INFINITE_LOOP
    UNKNOWN

Only reason=DRAW directly supplies factual draw semantics. A non-null winner supplies the
perspective outcome. The source validator requires DRAW to have no winner and a non-null winner to
belong to the roster, but it does not turn every winner-null terminal into a draw.

| Exact closure/source facts | Factual outcome | Supervised value | RL terminal reward | Policy-only prefix |
| --- | --- | --- | --- | --- |
| GameTerminal, winnerId equals perspective | Win | +1 | +1 | Decision records may be labeled if admitted |
| GameTerminal, non-null winner differs from perspective | Loss | -1 | -1 | Decision records may be labeled if admitted |
| GameTerminal, winnerId null and reason DRAW | Draw | 0 | 0 | Decision records may be labeled if admitted |
| GameTerminal, winnerId null and reason null or non-DRAW | Outcome unresolved | Absent or masked | Absent or masked | Only if source admission permits |
| Interrupted, HORIZON_REACHED | Not completed | Absent or masked | No synthetic terminal reward | YES when C0_02 permits |
| Interrupted, CALLER_CANCELLED | Not completed | Absent or masked | No synthetic terminal reward | YES when C0_02 permits |
| Failed, any exact failure reason | Not an admitted trusted episode | No label | No label/reward | NO |
| Runtime closure null | Episode still open | No label | No terminal reward | No persisted source artifact |

GameTerminal with reason DRAW and a non-null winner is a closure mismatch and is rejected. A
winner-null GameTerminal without explicit DRAW is not remapped to draw.

TrajectoryV1Validator quarantines Failed episodes and creates ValidatedEpisodeV1 only for
non-failed validation. EpisodeMetadataV1 requires a non-null closure. The legacy Gym numeric reward
may be zero at a horizon boundary, but that zero is not a factual draw.

## 17. Initial supervised value target V1

    SUPERVISED_VALUE_TARGET_SOURCE=TrajectoryV1.episodeMetadata.closure
    SUPERVISED_VALUE_TARGET_MODE=UNDISCOUNTED_FACTUAL_TERMINAL_RETURN
    SUPERVISED_VALUE_TARGET_PERSPECTIVE=DecisionRecordV1.perspectivePlayerId
    VALUE_TARGET_WIN=+1
    VALUE_TARGET_DRAW=0
    VALUE_TARGET_LOSS=-1
    VALUE_TARGET_DISCOUNT=1.0
    VALUE_TARGET_BOOTSTRAP=NONE
    SUPERVISED_VALUE_TARGET_AS_MODEL_INPUT=NO
    FUTURE_OUTCOME_AS_MODEL_INPUT=NO
    VALUE_TARGET_STORED_IN_TRAJECTORY_V1=NO

The target is relative to the acting perspective, never fixed to Akiri or Chevill. It is produced
only for a resolved factual terminal outcome. Unresolved terminal, interrupted and failed cases
are absent or masked.

    INTERRUPTED_SUPERVISED_VALUE_TARGET=ABSENT_OR_MASKED
    INTERRUPTED_SYNTHETIC_DRAW=NO
    INTERRUPTED_SYNTHETIC_LOSS=NO
    INTERRUPTED_SYNTHETIC_WIN=NO

## 18. RL reward V1 boundary

    RL_REWARD_CONTRACT != SUPERVISED_VALUE_TARGET
    SUPERVISED_VALUE_TARGET != LEARNED_VALUE_PREDICTION
    RL_REWARD != DISCOUNTED_RETURN

    RL_REWARD_MODE=SPARSE_FACTUAL_TERMINAL_OUTCOME
    NONTERMINAL_REWARD=0
    TERMINAL_WIN_REWARD=+1
    TERMINAL_DRAW_REWARD=0
    TERMINAL_LOSS_REWARD=-1
    UNRESOLVED_TERMINAL_REWARD=ABSENT_OR_MASKED
    INTERRUPTED_TERMINAL_REWARD=ABSENT_OR_MASKED
    FAILED_TERMINAL_REWARD=ABSENT_OR_MASKED
    REWARD_SHAPING=NONE

Terminal values apply only to resolved Section 16 cases. Interrupted and unresolved terminal
episodes receive no invented terminal reward. Discount, return construction, bootstrap,
truncation, n-step targets, GAE and V-trace belong to a future algorithm contract.

## 19. Reward shaping and authority

No V1 reward is introduced for life differential, damage, commander damage, cards drawn, board
presence, mana efficiency, equipment count, Chevill bounties, Akiri attacks, kills/removal,
Teacher score or value-model improvement.

Future shaping requires a new reward identity and causal comparison against the sparse baseline:

    REWARD_SHAPING=NONE
    REWARD_CAN_CHANGE_LEGALITY=NO
    REWARD_CAN_CHANGE_ENGINE_TRANSITION=NO
    REWARD_CAN_CHANGE_EPISODE_CLOSURE=NO

Argentum remains gameplay authority.

## 20. Learned value and Teacher score

    LEARNED_VALUE_IS_AUTHORITY=NO
    TEACHER_ACTION_SCORE != SUPERVISED_VALUE_TARGET
    TEACHER_CONFIDENCE != RL_REWARD
    TEACHER_SCORE_IS_VALUE_AUTHORITY=NO
    SEARCH_VALUE_IS_FACTUAL_OUTCOME=NO

A learned value is an estimator. A Teacher score is a ranking/diagnostic quantity unless
separately contracted. Neither marks a trajectory as won, changes reward or rewrites closure.

## 21. Reanalysis, search targets, and derived data

    accepted immutable TrajectoryV1
    + exact source DatasetManifestV1
    + exact checkpoint/Teacher/planner identity
    + exact derivation configuration
    + versioned derivation procedure
    -> new purpose-specific derived artifact

    DERIVED_REANALYSIS_ARTIFACT != TRUSTED_SOURCE_TRAJECTORY
    SOURCE_TRAJECTORY_TRUSTED=YES
    DERIVED_SEARCH_TARGET_TRUSTED=NO_UNLESS_PURPOSE_SPECIFICALLY_ADMITTED

No later Teacher, search value or learned estimate overwrites source choices, closure or policy
provenance. Source trust does not transfer automatically.

## 22. Teacher mixtures and disagreement

Multiple Teachers require:

    TEACHER_MIXTURE_IDENTITY
    member Teacher identities
    membership rule
    sampling/selection rule
    RNG contract where relevant
    weighting
    conflict policy

Labels are never silently pooled. Teacher disagreement is retained as derived evidence or resolved
by an explicit conflict contract; first, majority, highest score and random are not implicit rules.

## 23. C0_03/C0_04 and checkpoint boundaries

Teacher/bootstrap labels do not change C0_03 previousChoice or SOURCE_TEACHER_FORCED semantics.
Offline recurrent evaluation uses recorded source history; model disagreement does not rewrite it.

Teacher/model inference preserves Selection V2 plus PolicyTieRng V1 for the accepted Environment V1
baseline. Engine RNG, policy tie RNG and training RNG remain separate. C0_05 does not reopen C0_04.

C0_04 teacherBootstrapProvenance references an immutable bootstrap artifact or manifest identity.
No C0_04 checkpoint identity, TrajectoryV1 schema or production field changes here.

## 24. C1 handoff and non-goals

Later handoff context only:

    C0 FINAL ACCEPTANCE
    -> C1 entry authorization
    -> strict TrajectoryV1 reader
    -> deterministic model-facing derived view
    -> variable-size candidate batching
    -> feed-forward candidate scorer
    -> Selection V2 / PolicyTieRng runtime
    -> checkpoint and frozen evaluation

This task does not implement a model, checkpoint, training loop, Behavior Cloning run, value head,
RL, self-play, search Teacher, world model, curriculum expansion, large corpus, HF tooling,
TrajectoryV1 changes, Rules changes or Gym semantic changes. C1 and training remain unauthorized.

## 25. Failure behavior

C0_05 fails closed on unknown Teacher, policy-target, value-target or reward versions; missing
Teacher/source identity; missing source decision/domain binding; labels outside the complete domain;
hidden-information violation; domain reconstruction/truncation; invalid or non-finite output;
selection/profile mismatch; missing provenance; TrajectoryV1 mutation; value without factual
closure; interrupted/failed value target; unresolved perspective; untrusted or synthetic terminal
reward; undeclared shaping; silent Teacher mixture; split mismatch; and final-TEST leakage.

There is no default Teacher, default shaping, random label, first-legal fallback, hidden repair,
source mutation or automatic trust transfer.

## 26. Required C0_05 gates

PASS means the source-backed semantic contract is resolved; it does not mean that C1 implementation,
training or a Teacher-quality experiment has run.

| Gate | Result | Evidence |
| --- | --- | --- |
| C0_TEACHER_SOURCE_AUTHORITY | PASS | TrajectoryV1 and PolicyProvenanceV1 remain factual source authority. |
| C0_TEACHER_IDENTITY_CONTRACT | PASS | Immutable versioned Teacher identity shape is defined. |
| C0_TEACHER_PROVENANCE_CONTRACT | PASS | Source, checkpoint, config and materializer provenance are explicit. |
| C0_TEACHER_INFORMATION_SET_BOUNDARY | PASS | Acting-player information set and search restriction are explicit. |
| C0_TEACHER_DOMAIN_BINDING | PASS | Labels bind to complete source domains and semantic values. |
| C0_TEACHER_FAILURE_SEMANTICS | PASS | Failures produce NO_LABEL with no fallback. |
| C0_TEACHER_QUALITY_TRUST_SEPARATION | PASS | Quality and source trust are separate axes. |
| C0_TEACHER_ADMISSION_CONTRACT | PASS | Admission statuses remain independent. |
| C0_BOOTSTRAP_SOURCE_IMMUTABILITY | PASS | Derived artifacts never rewrite TrajectoryV1. |
| C0_BOOTSTRAP_SPLIT_INHERITANCE | PASS | C0_02 remains sole split authority. |
| C0_BOOTSTRAP_POLICY_TARGET_CONTRACT | PASS | Policy labels are derived exact semantic choices. |
| C0_STRUCTURED_BOOTSTRAP_TARGET_CONTRACT | PASS | Typed component decisions remain policy-owned. |
| C0_TRAINING_OBJECTIVE_PROVENANCE | PASS | Objective identity is required without optimizer selection. |
| C0_SUPERVISED_VALUE_SOURCE_AUTHORITY | PASS | Value derives only from factual closure. |
| C0_SUPERVISED_VALUE_PERSPECTIVE | PASS | Target uses decision perspective. |
| C0_SUPERVISED_VALUE_INTERRUPTION_POLICY | PASS | Interrupted/failed cases are absent or masked. |
| C0_SUPERVISED_VALUE_TARGET_CONTRACT | PASS | V1 mapping and discount/bootstrap semantics are frozen. |
| C0_RL_REWARD_BOUNDARY | PASS | Reward is a separate environment-facing contract. |
| C0_RL_TERMINAL_REWARD_CONTRACT | PASS | Only resolved factual terminal outcomes receive terminal reward. |
| C0_REWARD_SHAPING_BOUNDARY | PASS | Initial shaping is NONE and future shaping is versioned. |
| C0_VALUE_REWARD_SEPARATION | PASS | Value, return, reward, Teacher score and learned value are distinct. |
| C0_REANALYSIS_DERIVED_DATA_BOUNDARY | PASS | Reanalysis creates a new purpose-specific derived artifact. |
| C0_FINAL_TEST_PROTECTION | PASS | Final TEST cannot train or select a Teacher. |
| C0_UNKNOWN_VERSION_FAIL_CLOSED | PASS | Unknown semantic versions reject. |

## 27. Final status and stop condition

    SOURCE_DATA_TRUST_NE_TEACHER_QUALITY=YES
    TEACHER_LABEL_IS_GROUND_TRUTH_OPTIMAL_ACTION=NO
    POLICY_PROVENANCE_PROVES_SOURCE_IDENTITY=YES
    POLICY_PROVENANCE_PROVES_TEACHER_QUALITY=NO
    TEACHER_INPUT_INFORMATION_SET=ACTING_PLAYER_LEGAL_INFORMATION_SET
    RAW_GAME_STATE_AS_TEACHER_INPUT=NO
    PERFECT_INFORMATION_SEARCH_TEACHER_FOR_BOOTSTRAP=NOT_AUTHORIZED
    TEACHER_MAY_INVENT_ACTION=NO
    TEACHER_MAY_REPAIR_DOMAIN=NO
    TEACHER_FAILURE_LABEL=NO_LABEL
    FIRST_C1_TEACHER_SELECTION=DEFERRED_TO_C1_CHARACTERIZATION
    TRAJECTORY_V1_IS_AUTHORITATIVE_SOURCE=YES
    BOOTSTRAP_DATASET_IS_AUTHORITATIVE_SOURCE=NO
    TEACHER_RELABEL_REWRITES_TRAJECTORY_V1=NO
    TEACHER_DERIVATION_CHANGES_SPLIT=NO
    FINAL_TEST_USED_FOR_TRAINING=NO
    FINAL_TEST_USED_TO_SELECT_TEACHER=NO
    SUPERVISED_POLICY_TARGET_AS_MODEL_INPUT=NO
    SUPERVISED_VALUE_TARGET_SOURCE=FACTUAL_EPISODE_CLOSURE
    SUPERVISED_VALUE_TARGET_AS_MODEL_INPUT=NO
    INTERRUPTED_SUPERVISED_VALUE_TARGET=ABSENT_OR_MASKED
    SUPERVISED_VALUE_TARGET_NE_RL_REWARD=YES
    LEARNED_VALUE_IS_AUTHORITY=NO
    REWARD_SHAPING=NONE
    DERIVED_REANALYSIS_ARTIFACT_NE_TRUSTED_SOURCE_TRAJECTORY=YES
    C0_05_SPECIFICATION_PASS=YES
    C0_05_READY_FOR_ACCEPTANCE=YES
    C0_05_FINAL_ACCEPTANCE_PASS=NO
    OPEN_C0_05_BLOCKERS=NONE
    SELF_REVIEW_P1=NONE
    SELF_REVIEW_P2=NONE
    GIT_DIFF_CHECK=PASS
    FULL_GYM_TEST=NOT_REQUIRED
    FULL_RULES_TEST=NOT_REQUIRED
    HOSTED_CI=RUNNING
    COVERAGE=SKIPPED
    C1_AUTHORIZED=NO
    TRAINING_AUTHORIZED=NO
    C0_05_STARTED=NO
    TRAINING_STARTED=NO
    SELF_PLAY_AUTHORIZED=NO
    LARGE_CORPUS_GENERATION_AUTHORIZED=NO
    STOP_FOR_EXACT_SHA_REVIEW=YES

The Draft PR must remain unmerged and not Ready until independent Exact-SHA review authorizes it.

## 28. Expected post-acceptance state

After independent Exact-SHA review, Hosted CI on that exact head and merge verification:

    C0_05_SPECIFICATION_PASS=YES
    C0_05_FINAL_ACCEPTANCE_PASS=YES
    C0_05_FINALIZATION_COMPLETE=YES
    NEXT_RECOMMENDED_TASK=C0_PHASE_FINALIZATION_AND_C1_ENTRY_GATE
    C1_AUTHORIZED=NO
    TRAINING_AUTHORIZED=NO
