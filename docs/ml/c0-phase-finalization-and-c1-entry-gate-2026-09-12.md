# C0 phase finalization and C1 entry gate

## 1. Status and exact authority

    TASK=C0_PHASE_FINALIZATION_AND_C1_ENTRY_GATE
    DATE=2026-09-12
    STATUS=DRAFT_PHASE_GATE_PENDING_INDEPENDENT_EXACT_SHA_REVIEW
    AUDIT_BASE=73126ba90af7d5690a9f4a27e1970a4f9cc0e76f
    ORIGIN_MAIN_AT_AUDIT=73126ba90af7d5690a9f4a27e1970a4f9cc0e76f
    UPSTREAM_MAIN_AT_AUDIT=3f46367d87c88bcf156a843a9e69fd29e1693872
    ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
    UPSTREAM=https://github.com/wingedsheep/argentum-engine.git
    UPSTREAM_INTEGRATED=NO
    WRITABLE_REPOSITORY=chrismaghuhn/argentum-engine

    PHASE_GATE_SCOPE=DOCUMENTATION_AUDIT_AUTHORIZATION_ONLY
    PRODUCTION_CODE_CHANGED=NO
    RULES_CODE_CHANGED=NO
    GYM_SEMANTICS_CHANGED=NO
    TRAJECTORY_SCHEMA_CHANGED=NO
    MODEL_CODE_CHANGED=NO
    TRAINING_CODE_CHANGED=NO
    C1_IMPLEMENTATION_CHANGED=NO
    C1_STARTED=NO
    TRAINING_STARTED=NO
    SELF_PLAY_STARTED=NO
    LARGE_CORPUS_GENERATION_AUTHORIZED=NO

Origin main was fetched and equals the required exact base. Upstream is recorded for provenance only
and is not integrated. A mismatch of origin main would block this gate and require explicit
retarget authorization; none is inferred here.

## 2. Accepted predecessor sequence and status reconciliation

The live merged sequence is:

| Slice | Reviewed head | Merge commit | Live state |
| --- | --- | --- | --- |
| C0_01 / PR #175 | 6a879c5da7955c62b6ad2b3efb3f8854631dce45 | 2b12c6ac3b2c4e27f5de2781d932266b532bdd6d | MERGED |
| C0_02 / PR #176 | dd77f07540cfb0ad00bd3409f380065e13a48f66 | bf425d40c7a22710d0b3d9a52330661b68275bd4 | MERGED |
| C0_03 / PR #177 | 00ea654c3a36a02fdbf9c488e5b49a8b1176a713 | 57a22d64711c760c274b7f366a58ac56bdda7b09 | MERGED |
| C0_04 / PR #178 | 3c8104eff4835494db2eadc49e712c28b1f6b7ce | 5b4c1ab741e2c098febd83003b32770743c34954 | MERGED |
| C0_04A / PR #179 | cb677f76aa72085e2935b37df0afffcf7005309e | 51a01e1d9baa4f33e5bfc049443e95d00d2291c3 | MERGED |
| C0_04B / PR #180 | 5af00e2547044908f95bda840a1a4b1034cab118 | b483d322f67dc22eadc3b2b249767fd2b68d451b | MERGED |
| C0_04 finalization / PR #181 | b6c896fbbe8c81b642963bf7a3c51003110b790e | b1fb517446b16f4fbab612a2f290a99d0b383ae8 | MERGED |
| C0_05 / PR #182 | 9ba0f94da1db0a8da3ac6daab925294976e3a32d | 73126ba90af7d5690a9f4a27e1970a4f9cc0e76f | MERGED |

The merge parents of the current audit base are:

    PARENT_1=b1fb517446b16f4fbab612a2f290a99d0b383ae8
    PARENT_2=9ba0f94da1db0a8da3ac6daab925294976e3a32d

Several accepted C0 documents retain status blocks from their own pre-review Draft PR. Those
historical blocks are evidence of the delivery checkpoint at that time, not live current-main
authority. The live PR merge state and this phase reconciliation are the current acceptance
authority. No earlier document is rewritten in this task.

The live roadmap/tooling issues were also audited. Issue #124 remains the active C0 tracker with
C1 and training unauthorized. Issue #119 is OPEN and explicitly treats further characterization as
measurement-driven rather than an automatic C0 exit blocker. Issue #137 keeps physical learner
tooling replaceable and non-authoritative. Issue #1 and parts of Issue #137 contain older
CURRENT_MAIN fields and future authorization examples; those are historical/planning text, not live
SHA or authorization evidence.

## 3. Accepted project state before this gate

    COMMANDER_ENVIRONMENT_V1_COMPLETE=YES
    PHASE_A_FINAL_ACCEPTANCE_PASS=YES
    B0_FINAL_ACCEPTANCE_PASS=YES
    B1_FINAL_ACCEPTANCE_PASS=YES
    B2_FINAL_ACCEPTANCE_PASS=YES
    DATA_TRUSTED=YES
    C0_01_FINAL_ACCEPTANCE_PASS=YES
    C0_02_FINAL_ACCEPTANCE_PASS=YES
    C0_03_FINAL_ACCEPTANCE_PASS=YES
    C0_04_FINAL_ACCEPTANCE_PASS=YES
    C0_05_FINAL_ACCEPTANCE_PASS=YES
    C0_AUTHORIZED=YES
    CURRENT_PHASE=C0
    C1_AUTHORIZED=NO
    TRAINING_AUTHORIZED=NO
    SELF_PLAY_AUTHORIZED=NO
    LARGE_CORPUS_GENERATION_AUTHORIZED=NO
    PRE_C1_PERFORMANCE_GOOD_ENOUGH=YES
    PERFORMANCE_WORK=ON_DEMAND_ONLY

This gate asks whether those accepted contracts are mutually compatible and complete enough for
narrow C1 implementation authorization. It does not itself implement or run C1.

## 4. C0_01 model-facing sample and candidate scoring

Source: docs/ml/c0-model-facing-sample-and-candidate-scoring-contract-v1.md.

The accepted policy sample remains:

    PlayerObservationV1
    + complete legal candidate/domain
    + chosen semantic action/response

The source contract keeps Argentum authoritative for legality, completeness, privacy, semantic
binding and transitions. The learner scores supplied candidates; it does not become a Rules engine.

Audited C0_01 closure:

    MODEL_FACING_SAMPLE_CONTRACT_ID=argentum-ml-model-facing-decision-sample@v1
    C0_01_MODEL_FACING_SAMPLE_CONTRACT=PASS
    C0_01_CANDIDATE_SCORING_CONTRACT=PASS
    RAW_GAME_STATE_AS_MODEL_INPUT=NO
    COMPLETE_LEGAL_DOMAIN_REQUIRED=YES
    TOP_K_DOMAIN_TRUNCATION=NO
    CURRENT_CHOSEN_LABEL_AS_INPUT=NO
    STRUCTURED_DECISIONS_FIRST_CLASS=YES
    RAW_RUNTIME_ID_AS_POLICY_PREFERENCE=NO
    CANDIDATE_PRESENCE_NE_EXECUTABLE_SUPPORT=YES
    SOURCE_SEMANTIC_BINDING_REQUIRED=YES

The source explicitly permits model-equivalent but source-distinct candidates. Candidate presence
is separate from executable support. Complete domains, exact source binding, structured ownership
and inverse physical mappings remain required. Runtime IDs, batch rows and current chosen labels
do not become durable policy preference or model input.

This is a semantic closure, not implementation evidence. No model-facing materializer, batching
implementation or learner exists as a result of this gate.

## 5. C0_02 split and frozen evaluation

Source: docs/ml/c0-split-and-frozen-evaluation-contract-v1.md.

    SPLIT_CONTRACT_ID=argentum-ml-dataset-split@v1
    C0_02_SPLIT_CONTRACT=PASS
    C0_02_FROZEN_EVALUATION_CONTRACT=PASS
    SPLIT_KEY=semanticEpisodeId
    SPLIT_MAPPING=80/10/10 episode-level mapping
    DECISION_ROW_RANDOM_SPLIT=NO
    EPISODE_SPLIT_CROSSING=NO
    FINAL_TEST_FROZEN=YES
    FINAL_TEST_USED_FOR_TRAINING=NO
    FINAL_TEST_USED_FOR_HYPERPARAMETER_SELECTION=NO
    FINAL_TEST_USED_TO_SELECT_TEACHER=NO

The split belongs to the source episode. Derived C1 views and Teacher/policy/value labels inherit
the source episode split and cannot rehash or reshuffle individual decisions across boundaries.
The final TEST partition is frozen before Teacher or model selection. The frozen gameplay
evaluation matrix remains an evaluation authority, not a training source.

## 6. C0_03 sequence, reset, and recurrent derived view

Source: docs/ml/c0-sequence-reset-and-recurrent-derived-view-contract-v1.md.

    RECURRENT_SEQUENCE_CONTRACT_ID=argentum-ml-recurrent-sequence@v1
    C0_03_SEQUENCE_RESET_CONTRACT=PASS
    C0_03_RECURRENT_DERIVED_VIEW_CONTRACT=PASS
    EPISODE_BOUNDARY_RESET=YES
    PLAYER_BOUNDARY_RESET=YES
    TURN_BOUNDARY_RESET=NO
    PHASE_BOUNDARY_RESET=NO
    CROSS_TRAJECTORY_STATE_CARRY=NO
    SHARED_HIDDEN_STATE_BETWEEN_PLAYERS=FORBIDDEN
    BATCH_SLOT_RECURRENT_LEAKAGE=FORBIDDEN
    PREVIOUS_CHOICE_SCOPE=SAME_POLICY_INSTANCE
    CURRENT_TARGET_AS_CURRENT_INPUT=NO
    FULL_STREAM_IS_SEQUENCE_AUTHORITY=YES
    WINDOW_IS_DERIVED_VIEW=YES
    REFERENCE_RECURRENT_SEMANTICS=EXACT_EPISODE_PREFIX
    OFFLINE_RECURRENT_EVALUATION_MODE=SOURCE_TEACHER_FORCED
    ENVIRONMENT_RESTORE != POLICY_STATE_RESTORE

C0_03 owns episode/player reset, causality, previous-choice history and derived windows. C1 may
implement recurrent views later, but their absence is not a C0 blocker. C0_05 Teacher disagreement
or relabeling cannot overwrite source-teacher-forced history.

## 7. C0_04 checkpoint, inference, selection, and PolicyTieRng

Sources:

- docs/ml/c0-checkpoint-identity-and-deterministic-inference-contract-v1.md
- docs/ml/c0-environment-v1-tie-totality-characterization-2026-09-12.md
- docs/ml/c0-policy-rng-and-symmetry-resolution-contract-v1.md

    CHECKPOINT_MANIFEST_CONTRACT_ID=argentum-ml-checkpoint-manifest@v1
    INFERENCE_CONTRACT_ID=argentum-ml-inference@v1
    NUMERIC_EXECUTION_PROFILE_CONTRACT_ID=argentum-ml-numeric-execution-profile@v1
    SELECTION_CONTRACT_ID=argentum-ml-policy-selection@v2
    POLICY_RNG_CONTRACT_ID=argentum-ml-policy-tie-rng@v1
    C0_04_CHECKPOINT_IDENTITY_CONTRACT=PASS
    C0_04_INFERENCE_CONTRACT=PASS
    C0_04_NUMERIC_PROFILE_CONTRACT=PASS
    C0_04_SELECTION_V2_CONTRACT=PASS
    C0_04_POLICY_RNG_CONTRACT=PASS

C0_04A remains historical evidence:

    DETERMINISTIC_POLICY_TOTALITY=NO
    C0_04_DETERMINISTIC_BASELINE_READY=NO
    C0_04_REPRODUCIBLE_POLICY_BASELINE_READY=YES
    TOTAL_POLICY_SELECTION_WITH_DECLARED_RNG=YES
    TIE_BREAK_IDENTITY_GAP=CLOSED_BY_POLICY_RNG_CONTRACT

C0_04B supplies reproducible total selection only for unresolved exact symmetry:

    GENERAL_STOCHASTIC_POLICY_SAMPLING_SUPPORTED=NO
    STOCHASTIC_SYMMETRY_RESOLUTION_SUPPORTED=YES
    ENGINE_RNG != POLICY_TIE_RNG != TRAINING_RNG
    ENGINE_SEED_AS_POLICY_RNG_INPUT=NO
    HIDDEN_WORLD_IDENTITY_AS_POLICY_RNG_INPUT=NO
    SEMANTIC_EPISODE_ID_AS_POLICY_RNG_INPUT=NO

Checkpoint loading remains strict and fail-closed. The checkpoint binds supported selection and
PolicyTieRng algorithms, while concrete policy seed/state remains evaluation provenance. Selection
V1 remains a historical deterministic-only contract; Selection V2 plus PolicyTieRng V1 is the
accepted complete Environment V1 baseline. No deterministic-totality claim is made.

The absence of a checkpoint writer, loader, Selection V2 runtime or PolicyTieRng runtime is
implementation work, not an undefined C0 semantic owner.

## 8. C0_05 Teacher/bootstrap and value/reward boundaries

Source: docs/ml/c0-teacher-bootstrap-and-value-reward-boundary-contract-v1.md.

    TEACHER_BOOTSTRAP_CONTRACT_ID=argentum-ml-teacher-bootstrap@v1
    SUPERVISED_POLICY_TARGET_CONTRACT_ID=argentum-ml-supervised-policy-target@v1
    SUPERVISED_VALUE_TARGET_CONTRACT_ID=argentum-ml-supervised-value-target@v1
    RL_REWARD_CONTRACT_ID=argentum-ml-rl-reward@v1
    C0_05_TEACHER_BOOTSTRAP_CONTRACT=PASS
    C0_05_SUPERVISED_POLICY_TARGET_CONTRACT=PASS
    C0_05_SUPERVISED_VALUE_TARGET_CONTRACT=PASS
    C0_05_RL_REWARD_BOUNDARY_CONTRACT=PASS

Trust and quality remain separate:

    SOURCE_DATA_TRUST != TEACHER_QUALITY
    TEACHER_LABEL_IS_GROUND_TRUTH_OPTIMAL_ACTION=NO
    TRAJECTORY_V1_IS_AUTHORITATIVE_SOURCE=YES
    BOOTSTRAP_DATASET_IS_AUTHORITATIVE_SOURCE=NO
    TEACHER_RELABEL_REWRITES_TRAJECTORY_V1=NO
    TEACHER_INPUT_INFORMATION_SET=ACTING_PLAYER_LEGAL_INFORMATION_SET
    RAW_GAME_STATE_AS_TEACHER_INPUT=NO
    PERFECT_INFORMATION_SEARCH_TEACHER_FOR_BOOTSTRAP=NOT_AUTHORIZED
    FIRST_C1_TEACHER_SELECTION=DEFERRED_TO_C1_CHARACTERIZATION

The A9 policy has source-backed public-observation provenance but no separately characterized
strategic quality. That is a C1 execution prerequisite, not a missing C0 semantic contract.

Value and reward remain distinct:

    SUPERVISED_VALUE_TARGET != RL_REWARD_CONTRACT
    RL_REWARD_CONTRACT != LEARNED_VALUE
    RL_REWARD != DISCOUNTED_RETURN
    SUPERVISED_VALUE_TARGET_SOURCE=FACTUAL_EPISODE_CLOSURE
    VALUE_TARGET_PERSPECTIVE=DecisionRecordV1.perspectivePlayerId
    INTERRUPTED_SUPERVISED_VALUE_TARGET=ABSENT_OR_MASKED
    REWARD_SHAPING=NONE
    LEARNED_VALUE_IS_AUTHORITY=NO

The exact closure mapping is compatible with C0_01 through C0_04:

    resolved winner from perspective -> +1 / -1
    explicit factual DRAW -> 0
    winner-null non-DRAW or null-reason terminal -> absent/masked
    INTERRUPTED -> absent/masked
    FAILED -> no trusted target/reward

No source trajectory, closure, split, policy provenance or chosen action is rewritten.

## 9. Integrated C0 authority graph

    Argentum Rules / Gym
        ↓
    perspective-safe PlayerObservationV1
    + complete legal domain
        ↓
    TrajectoryV1 factual source
        ↓
    C0_01 model-facing derived sample
        ↓
    C0_02 deterministic episode split
        ↓
    C0_03 recurrent derived history where applicable
        ↓
    model scores
        ↓
    C0_04 Selection V2
        ↓
    PolicyTieRng V1 only for unresolved exact symmetry
        ↓
    exact semantic source choice
        ↓
    Argentum validation / transition

For training:

    TrajectoryV1 factual source
        ↓
    C0_05 admitted policy/value target derivation
        ↓
    C1 learner

No layer redefines a lower authority. C0_05 targets and C1 artifacts are derived interpretations;
they do not become Rules, legal-domain, source-trajectory or episode-closure authority.

## 10. Cross-contract compatibility matrix

Each pair was checked against the current merged documents and the accepted source boundaries.
PASS means a concrete compatible handoff, not vague similarity.

| Pair | Result | Concrete compatibility |
| --- | --- | --- |
| C0_01 ↔ C0_02 | PASS | C0_02 splits accepted TrajectoryV1 episodes by semanticEpisodeId; C0_01 derived samples do not create a decision-row split. |
| C0_01 ↔ C0_03 | PASS | C0_03 adds recurrent prefix/history as derived state while C0_01 keeps current observation/domain and excludes current target input. |
| C0_01 ↔ C0_04 | PASS | C0_04 scores the complete C0_01 domain and binds Selection V2; symmetric source alternatives remain distinct and are resolved by C0_04B. |
| C0_01 ↔ C0_05 | PASS | C0_05 policy labels bind exact C0_01 semantic candidates and do not turn labels or future outcomes into model input. |
| C0_02 ↔ C0_03 | PASS | Recurrent streams inherit episode split and reset at episode/player boundaries; no state crosses trajectories or split partitions. |
| C0_02 ↔ C0_04 | PASS | Frozen evaluation binds checkpoint/profile/selection/RNG provenance without changing episode-level split identity. |
| C0_02 ↔ C0_05 | PASS | Bootstrap artifacts inherit semanticEpisodeId split and preserve frozen TEST exclusion from training and Teacher selection. |
| C0_03 ↔ C0_04 | PASS | Checkpoint/inference binds recurrent contract when applicable; PolicyTieRng state is separate from recurrent state and restore semantics. |
| C0_03 ↔ C0_05 | PASS | Teacher labels do not rewrite previousChoice or source-teacher-forced offline history. |
| C0_04 ↔ C0_05 | PASS | C0_04 reserves immutable Teacher/bootstrap provenance; C0_05 defines it without changing checkpoint identity, and keeps PolicyTieRng separate from training RNG. |

No pair has a concrete semantic contradiction. Missing implementations are classified separately
from missing contracts.

## 11. C0 integrated gate table

| Contract gate | Result | Evidence |
| --- | --- | --- |
| C0_01_MODEL_FACING_SAMPLE_CONTRACT | PASS | C0_01 identity, complete sample, privacy and source-label boundaries. |
| C0_01_CANDIDATE_SCORING_CONTRACT | PASS | Complete supplied domains, exact semantic scoring and no truncation. |
| C0_02_SPLIT_CONTRACT | PASS | Episode-level semanticEpisodeId split and 80/10/10 mapping. |
| C0_02_FROZEN_EVALUATION_CONTRACT | PASS | Frozen TEST and gameplay evaluation matrix. |
| C0_03_SEQUENCE_RESET_CONTRACT | PASS | Episode/player reset, causality and restore boundary. |
| C0_03_RECURRENT_DERIVED_VIEW_CONTRACT | PASS | Exact episode-prefix authority and source-teacher-forced offline view. |
| C0_04_CHECKPOINT_IDENTITY_CONTRACT | PASS | Strict manifest, identity preimage, weight integrity and compatibility. |
| C0_04_INFERENCE_CONTRACT | PASS | Exact input/profile/selection provenance and fail-closed loading. |
| C0_04_NUMERIC_PROFILE_CONTRACT | PASS | Declared profile and cross-backend certification boundary. |
| C0_04_SELECTION_V2_CONTRACT | PASS | Deterministic subset plus exact unresolved-tie resolution. |
| C0_04_POLICY_RNG_CONTRACT | PASS | PolicyTieRng V1 algorithm, privacy, lifecycle and source binding. |
| C0_05_TEACHER_BOOTSTRAP_CONTRACT | PASS | Teacher identity, information set, domain, failure and admission semantics. |
| C0_05_SUPERVISED_POLICY_TARGET_CONTRACT | PASS | Derived exact source/Teacher semantic policy target. |
| C0_05_SUPERVISED_VALUE_TARGET_CONTRACT | PASS | Factual closure-only, perspective-relative, undiscounted V1 target. |
| C0_05_RL_REWARD_BOUNDARY_CONTRACT | PASS | Sparse factual terminal reward, no shaping, separate return/value semantics. |

## 12. Cross-contract gate table

    C0_CROSS_CONTRACT_MODEL_DATA_COMPATIBILITY=PASS
    C0_CROSS_CONTRACT_SPLIT_COMPATIBILITY=PASS
    C0_CROSS_CONTRACT_RECURRENT_COMPATIBILITY=PASS
    C0_CROSS_CONTRACT_CHECKPOINT_COMPATIBILITY=PASS
    C0_CROSS_CONTRACT_POLICY_RNG_COMPATIBILITY=PASS
    C0_CROSS_CONTRACT_TEACHER_COMPATIBILITY=PASS
    C0_CROSS_CONTRACT_VALUE_REWARD_COMPATIBILITY=PASS
    C0_CROSS_CONTRACT_EVALUATION_COMPATIBILITY=PASS
    C0_CROSS_CONTRACT_PRIVACY_COMPATIBILITY=PASS
    C0_CROSS_CONTRACT_PROVENANCE_COMPATIBILITY=PASS

The compatibility evidence is the matrix in Section 10. No new C0 semantic contract is required.

## 13. C1 entry prerequisites

    C1_ENTRY_DATA_TRUST=PASS
    C1_ENTRY_MODEL_FACING_CONTRACTS=PASS
    C1_ENTRY_SPLIT_EVALUATION=PASS
    C1_ENTRY_SEQUENCE_CONTRACT=PASS
    C1_ENTRY_CHECKPOINT_INFERENCE=PASS
    C1_ENTRY_SELECTION_RNG=PASS
    C1_ENTRY_TEACHER_BOUNDARY=PASS
    C1_ENTRY_VALUE_REWARD_BOUNDARY=PASS
    C1_ENTRY_TOOLING_BOUNDARY=PASS
    C1_ENTRY_PERFORMANCE_STATE=PASS

Teacher quality is intentionally not an entry contract blocker:

    FIRST_C1_TEACHER_SELECTION=DEFERRED_TO_C1_CHARACTERIZATION
    FIRST_C1_TEACHER_DEFERRAL_CLASSIFICATION=C1_EXECUTION_PREREQUISITE

The accepted prerequisites are semantic. C1 still must characterize and admit a concrete Teacher
before strategically meaningful bootstrap training.

## 14. Issue #137 tooling boundary

Issue #137 remains compatible with C0 because physical tooling is replaceable and non-authoritative:

    ARGENTUM_DATA_TRUST_AUTHORITY=UNCHANGED
    ARGENTUM_CHECKPOINT_AUTHORITY=UNCHANGED
    SAFETENSORS=PREFERRED_EARLY_C1_WEIGHT_CONTAINER
    TRACKIO=PREFERRED_EARLY_C1_OBSERVABILITY_CANDIDATE
    HF_DATASETS_ARROW=CONDITIONAL_ON_MEASURED_NEED
    ACCELERATE=DEFER_UNTIL_LOCAL_MINI_PIPELINE_PASS
    HUGGINGFACE_HUB=OPTIONAL_TRANSPORT_ONLY
    TRANSFORMERS_TRAINER=NOT_ADOPTED
    TRL=NOT_ADOPTED
    PEFT=NOT_ADOPTED

No package version, adapter, checkpoint loader, learner or transport is selected or implemented
here. Tooling cannot redefine C0 data trust, legal domains, semantic identity or evaluation.

## 15. Issue #119 performance and data boundary

    PRE_C1_PERFORMANCE_GOOD_ENOUGH=YES
    PERFORMANCE_WORK=ON_DEMAND_ONLY
    POST_B2_CHARACTERIZATION_REQUIRED_FOR_C1_ENTRY=NO
    HF_DATASETS_ARROW_REQUIRED_FOR_C1_ENTRY=NO
    C1_PERFORMANCE_AUDIT_STARTED=NO
    LARGE_CORPUS_GENERATION_AUTHORIZED=NO

Issue #119 remains an open measurement tracker, but its own current contract says further profiling
is triggered by a measured need from real C1 actor/learner/data workloads. It is not a semantic C0
exit blocker and does not authorize the optional large corpus.

## 16. Narrow C1 authorization semantics

Before independent review and merge of this phase PR:

    CURRENT_PHASE=C0
    C0_FINAL_ACCEPTANCE_PASS=NO
    C1_ENTRY_GATE_PASS=NO
    C1_AUTHORIZED=NO
    C1_IMPLEMENTATION_AUTHORIZED=NO
    TRAINING_AUTHORIZED=NO
    SMALL_LEARNER_SMOKE_AUTHORIZED=NO

If this exact phase document is independently accepted and merged, C1 authorization means only
that separately reviewed implementation slices may begin:

    C1_IMPLEMENTATION_AUTHORIZED=YES

It does not authorize strategically meaningful Behavior Cloning, large-data training, recurrent
training, value training, RL, self-play, search or curriculum expansion.

The future training hierarchy is:

    C1 implementation slices
    -> LOCAL_MINI_MODEL_PIPELINE_PASS
    -> Teacher/source-label purpose admitted
    -> SMALL_LEARNER_SMOKE_AUTHORIZED
    -> first checkpoint and frozen evaluation
    -> performance characterization
    -> later strategically meaningful training authorization

No later PASS is claimed in this phase gate.

## 17. Explicit unauthorized work

    C2_AUTHORIZED=NO
    RL_AUTHORIZED=NO
    C3_AUTHORIZED=NO
    SELF_PLAY_AUTHORIZED=NO
    SEARCH_IMPLEMENTATION_AUTHORIZED=NO
    WORLD_MODEL_IMPLEMENTATION_AUTHORIZED=NO
    PERFECT_INFORMATION_SEARCH_TEACHER_AUTHORIZED=NO
    AKIRI_CHEVILL_CURRICULUM_LOCKED=YES
    LARGE_CORPUS_GENERATION_AUTHORIZED=NO
    C1_PERFORMANCE_AUDIT_STARTED=NO

No C0_06 contract is invented. No model, learner, checkpoint loader, PolicyTieRng runtime,
Selection V2 runtime, dataset adapter, training run, gradient update, value head, RL, self-play,
search, world model, large corpus or deck change is part of this task.

## 18. C0 phase finalization result

    C0_PHASE_FINALIZATION_SPECIFICATION_PASS=YES
    C0_PHASE_FINALIZATION_READY_FOR_ACCEPTANCE=YES
    C0_FINAL_ACCEPTANCE_PASS=NO
    C1_ENTRY_GATE_PASS=NO
    OPEN_C0_BLOCKERS=NONE
    SELF_REVIEW_P1=NONE
    SELF_REVIEW_P2=NONE

The remaining NO values are deliberately acceptance-state values for this Draft PR, not semantic
contract blockers. The only remaining gate is independent Exact-SHA review followed by merge
verification.

## 19. Verification and delivery boundary

This is documentation/audit/authorization only:

    GIT_DIFF_CHECK=PASS
    FULL_GYM_TEST=NOT_REQUIRED
    FULL_RULES_TEST=NOT_REQUIRED
    HOSTED_CI=RUNNING
    COVERAGE=SKIPPED

No soak, generation, training or performance benchmark is run here. Unrun implementation tests are
not reported as PASS.

The original checkout's unrelated StackResolver.kt modification was not staged, reset, stashed,
cleaned, edited, reformatted or included:

    UNRELATED_STACKRESOLVER_CHANGE_TOUCHED=NO

## 20. Expected post-merge state

Only after independent Exact-SHA review PASS, Hosted CI PASS on that exact reviewed head, merge and
merge-SHA/parent/origin-main verification:

    C0_FINAL_ACCEPTANCE_PASS=YES
    C0_PHASE_COMPLETE=YES
    C1_ENTRY_GATE_PASS=YES
    CURRENT_PHASE=C1
    C1_AUTHORIZED=YES
    C1_IMPLEMENTATION_AUTHORIZED=YES
    TRAINING_AUTHORIZED=NO
    SMALL_LEARNER_SMOKE_AUTHORIZED=NO
    C2_AUTHORIZED=NO
    RL_AUTHORIZED=NO
    C3_AUTHORIZED=NO
    SELF_PLAY_AUTHORIZED=NO
    SEARCH_IMPLEMENTATION_AUTHORIZED=NO
    WORLD_MODEL_IMPLEMENTATION_AUTHORIZED=NO
    LARGE_CORPUS_GENERATION_AUTHORIZED=NO
    PERFORMANCE_WORK=ON_DEMAND_ONLY
    NEXT_REQUIRED=C1_00_LOCAL_LEARNER_FOUNDATION_AND_CONTRACT_IMPLEMENTATION

## 21. Required final status

    CURRENT_PHASE=C0
    C0_FINAL_ACCEPTANCE_PASS=NO
    C1_ENTRY_GATE_PASS=NO
    C1_AUTHORIZED=NO
    C1_IMPLEMENTATION_AUTHORIZED=NO
    TRAINING_AUTHORIZED=NO
    NEXT_REQUIRED=C1_00_LOCAL_LEARNER_FOUNDATION_AND_CONTRACT_IMPLEMENTATION
    NEXT_TASK_STARTED=NO
    STOP_FOR_EXACT_SHA_REVIEW=YES
