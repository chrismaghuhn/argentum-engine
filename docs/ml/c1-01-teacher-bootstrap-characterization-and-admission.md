# C1_01 Teacher Bootstrap Characterization and Admission

```text
TASK=C1_01_TEACHER_BOOTSTRAP_CHARACTERIZATION_AND_ADMISSION
DATE=2026-09-13
STATUS=CHARACTERIZATION_COMPLETE_PENDING_INDEPENDENT_EXACT_SHA_REVIEW
BASE=dfdc10c3e5f86523fb79737f57ddfee3f0e466a6
BRANCH=chris/c1-01-teacher-bootstrap-characterization-20260913
ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM=https://github.com/wingedsheep/argentum-engine.git
UPSTREAM_INTEGRATED=NO
CURRENT_PHASE=C1
C1_01_DESIGN_REVIEW=PASS
C1_01_IMPLEMENTATION_AUTHORIZED=YES
PRODUCTION_TEACHER_IMPLEMENTATION_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
SMALL_LEARNER_SMOKE_AUTHORIZED=NO
LIVE_LARGE_GENERATION_AUTHORIZED=NO
```

This is a characterization and admission decision. It does not train a model, implement a
Teacher, materialize a bootstrap-label artifact, change `TrajectoryV1`, or change Rules/Gym
semantics. The accepted semantic authorities remain the C0 contracts and the C1_00 local learner
boundary.

## 1. Decision

The existing A9 public-observation policy is not admitted as the first C1 bootstrap Teacher.
The focused characterization found that its selected source alternative can change when only a
runtime `EntityId` is renamed in a feature-identical, source-distinct candidate situation. That is
an admission-relevant violation of the accepted model-facing identity boundary and is incompatible
with Selection V2's symmetry mechanism.

```text
C1_01_CHARACTERIZATION_PASS=YES
C1_01_ADMISSION_RESULT=NO_EXISTING_CANDIDATE_QUALIFIES
FIRST_C1_TEACHER_SELECTION=NONE
TEACHER_BOOTSTRAP_ADMITTED=NO
TEACHER_ADMISSION_PURPOSE=NONE
TEACHER_QUALITY_CHARACTERIZED=NO_FOR_A9_STRATEGIC_QUALITY
A9_SELECTION_V2_BOOTSTRAP_SUITABILITY=FAIL
```

This is Outcome B. The result is successful because the hard admission gate was answered without
relaxing the accepted information, domain, identity, split or failure contracts. No gameplay
strength, expert, optimality or near-perfect claim is made about A9.

## 2. Authority consumed

The audit consumed the current-main versions of:

```text
docs/ml/c0-model-facing-sample-and-candidate-scoring-contract-v1.md
docs/ml/c0-split-and-frozen-evaluation-contract-v1.md
docs/ml/c0-sequence-reset-and-recurrent-derived-view-contract-v1.md
docs/ml/c0-checkpoint-identity-and-deterministic-inference-contract-v1.md
docs/ml/c0-policy-rng-and-symmetry-resolution-contract-v1.md
docs/ml/c0-teacher-bootstrap-and-value-reward-boundary-contract-v1.md
docs/ml/c1-00-local-learner-foundation.md
docs/ml/c0-phase-finalization-and-c1-entry-gate-2026-09-12.md
```

The governing rules used here are:

```text
TEACHER_INPUT_INFORMATION_SET=ACTING_PLAYER_LEGAL_INFORMATION_SET
TEACHER_OUTPUT=ONE_SOURCE_AUTHORIZED_SEMANTIC_ACTION_OR_RESPONSE
TEACHER_FAILURE_LABEL=NO_LABEL
TEACHER_MAY_INVENT_ACTION=NO
TEACHER_MAY_REPAIR_DOMAIN=NO
TEACHER_MAY_TRUNCATE_DOMAIN=NO
TEACHER_MAY_AUTOPAY=NO
TEACHER_MAY_USE_FIRST_LEGAL_FALLBACK=NO
FEATURE_IDENTICAL_SYMMETRY_ALLOWED=YES
SOURCE_BINDING_ORDINAL_AS_MODEL_FEATURE=NO
SOURCE_BINDING_ORDINAL_AS_DETERMINISTIC_PREFERENCE=NO
SOURCE_BINDING_ORDINAL_AS_UNIFORM_SAMPLE_ADDRESS=YES
SELECTION_CONTRACT_ID=argentum-ml-policy-selection@v2
POLICY_RNG_CONTRACT_ID=argentum-ml-policy-tie-rng@v1
FINAL_TEST_USED_TO_SELECT_TEACHER=NO
TRAJECTORY_V1_MUTATED=NO
```

The legacy A9 policy state identity is not reinterpreted as the C0_04B PolicyTieRng identity.
Selection V2 resolves an unresolved exact tie by a declared PolicyTieRng stream over the complete
source binding; it does not permit a raw runtime ID to become a deterministic preference.

## 3. Candidate inventory and classification

Candidates were classified before any quality comparison. A candidate with a hard information,
domain, provenance or identity failure cannot win on gameplay or source-agreement numbers.

| Candidate | Existing input/output | Classification | Evidence and disposition |
| --- | --- | --- | --- |
| `DeterministicExternalPolicy` / `b2-a9-deterministic-external-policy@v1` | `TrainingObservation` + `DeterministicPolicyState` -> `SemanticChoice` | `ELIGIBLE_FOR_CHARACTERIZATION` -> `REJECT_RUNTIME_ID_DEPENDENCE`, `REJECT_MISSING_PROVENANCE` | Test-only policy has no `GameState`/registry dependency (`gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt`); focused probes show raw-ID selection; no immutable Teacher configuration or label-materializer identity exists. |
| `EngineAiPlayerController` / `AIPlayer` / `Strategist` | `GameState` -> engine `GameAction`/`DecisionResponse` | `REJECT_RAW_GAME_STATE`, `REJECT_HIDDEN_INFORMATION`, `REJECT_HIDDEN_POLICY` | `EngineAiPlayerController` requires an unmasked `GameState` provider and delegates to `AIPlayer`; `AIPlayer`/`Strategist` simulate and evaluate `GameState`, including determinization/rollouts. |
| `PlayoutPolicy`, `PlayoutEngine`, rollout evaluators | `GameState` plus simulated candidate states -> scores/actions | `REJECT_RAW_GAME_STATE`, `REJECT_HIDDEN_INFORMATION`, `REJECT_HIDDEN_POLICY` | Rollout code is a Rules-state simulation path, not an acting-player observation-only Teacher. Perfect-information search is not authorized by C0_05. |
| `LlmAiPlayerController` and decision handlers | `ClientGameState`, logs and LLM response -> `ActionResponse` | `REJECT_RAW_GAME_STATE`, `REJECT_HIDDEN_POLICY`, `REJECT_UNCONTROLLED_STRUCTURED_CHOICES` | `GameStateFormatter` constructs a full state prompt; the controller retries parse failures and may delegate to an engine-AI fallback or first action. It has no C0_05 label/provenance boundary. |
| `ApprenticeArtifact` / `ApprenticeArtifactLoader` / legacy evaluation weights | legacy coefficients -> board evaluation | `REJECT_MISSING_PROVENANCE`, `REJECT_UNSUPPORTED_DECISION_COVERAGE` | Legacy set-scoped artifact has no C0 checkpoint/Teacher identity, model-facing domain binding or complete structured-response output. Its loader is explicitly a legacy fallback surface. |
| Draft/deck-build advisors (`DraftAdvisor`, `DeckBuildAdvisor`, heuristic/draftsim advisors) | card pool/deck context -> pick/build suggestions | `REJECT_UNSUPPORTED_DECISION_COVERAGE` | These are not Environment V1 action/response policies and do not own complete legal domains or structured continuations. |
| Focused Gym test policies (`B0HarnessTimeoutPolicy`, payment/diagnostic policies) | narrow test fixtures -> one local test decision | `REJECT_UNSUPPORTED_DECISION_COVERAGE` | Test-only witnesses characterize individual boundaries; none is a complete public-observation Teacher for the locked curriculum. |
| `EnvironmentV1TrustedGenerationTest` and `A9DecisionFamilyClosureAudit` | generation/audit harnesses | `DEFER_REQUIRES_NEW_GENERIC_TEACHER` | They prove source/publication properties or inspect accepted serialized records; they are not label-producing policies. |

Therefore A9 is the only existing candidate with a plausible observation-only input boundary, and it
is the only candidate that reached focused Teacher characterization. The remaining candidates are
not eligible for quality comparison.

## 4. A9 provenance

The following is the exact provenance of the characterized existing source policy. Operational
paths, hostnames, PIDs, worker slots and wall time are intentionally not identity fields.

```text
teacherContractIdentity=argentum-ml-teacher-bootstrap@v1
teacherKind=EXISTING_PUBLIC_OBSERVATION_BEHAVIOR_POLICY
teacherPolicyIdentity=b2-a9-deterministic-external-policy@v1
teacherSourceIdentity=EnvironmentV1ExternalPolicy.kt@sha256:72F5D98588CF6815E70E7B9982A028DCD337B3EDC337521EA1826E31EADB6B8F
teacherSourceDigestEncoding=canonical UTF-8 bytes after CRLF-to-LF normalization, uppercase spelling as stored by the A9 producer
teacherSourceIdentityDisplayLowercase=EnvironmentV1ExternalPolicy.kt@sha256:72f5d98588cf6815e70e7b9982a028dcd337b3edc337521ea1826e31eadb6b8f
sourceCommit=0aa9444c6872db6a6527ea479061eb2efafea705
sourceCommitMeaning=A9 source-generation head; the source file is unchanged through the current audit base
sourceFileLastImplementationCommit=66b0e8206e45bcf2da8101a6b8dc13b2bec72d5c
policyRngIdentity=explicit-seed/kotlin-policy-state-v1
policyStateContract=DeterministicPolicyState(policySeed, choiceOrdinal)
policySeedFormula=spec.seed*1000003 + startingPlayerIndex*97409 + rosterCode*65537
sourceDatasetIdentity=69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03
sourceManifestContentDigest=de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2
sourceTrajectoryOrDecisionIdentity=accepted TrajectoryV1 / DecisionRecordV1 identities from the manifest-bound A9 dataset
labelMaterializerIdentity=NONE_EXISTING
teacherConfigurationIdentityOrDigest=NONE_EXISTING
selectionContractFor_future_model=argentum-ml-policy-selection@v2
policyTieRngContractFor_future_model=argentum-ml-policy-tie-rng@v1
```

`teacherConfigurationIdentityOrDigest=NONE_EXISTING` and `labelMaterializerIdentity=NONE_EXISTING`
are admission blockers, not permission to use a path, filename or mutable alias as a substitute.
The accepted A9 matrix is retained as evidence of source provenance, not promoted to an immutable
Teacher configuration identity.

## 5. Inherited source-trust evidence

The A9 run artifacts are the source/trust evidence referenced by the current acceptance authority
`docs/ml/c0-phase-finalization-and-c1-entry-gate-2026-09-12.md:66-85`, which records
`B2_FINAL_ACCEPTANCE_PASS=YES` and `DATA_TRUSTED=YES` before C1. The original A9 run report's
older local `A9_FINAL_ACCEPTANCE_PASS=NO` / `DATA_TRUSTED=NO` values are historical run status, not
the current project state. They do not prove Teacher quality.
No new live episode campaign was run in C1_01, because the hard identity gate failed first.

```text
A9_SOURCE_EPISODES=64
A9_SOURCE_EPISODES_TRUSTED=64
A9_SOURCE_DECISIONS=125471
A9_SOURCE_FAILED=0
A9_SOURCE_QUARANTINED=0
A9_SOURCE_UNSUPPORTED_DIAGNOSTICS=FAIL_FAST_PROVEN_ZERO
A9_SOURCE_PUBLIC_CHOICE_REJECTIONS=FAIL_FAST_PROVEN_ZERO
A9_SOURCE_CHOSEN_NOT_IN_DOMAIN=FAIL_FAST_PROVEN_ZERO
A9_SOURCE_REPLAY_EXACT=64
A9_SOURCE_REPLAY_DIVERGED=0
A9_SOURCE_REPLAY_INCOMPLETE=0
A9_SOURCE_A5_VALID=64
A9_SOURCE_A6_ADMITTED=64
A9_SOURCE_A7_PREFLIGHT=PASS
A9_SOURCE_A7_STREAM_EPISODES=64
A9_SOURCE_DETERMINISM_SPOTCHECK=4/4
A9_SOURCE_GAME_TERMINAL=5
A9_SOURCE_INTERRUPTED=59
```

The A9 zero counters for unsupported diagnostics and public-choice/domain rejection are retained
as `FAIL_FAST_PROVEN_ZERO`, matching the A9 closure-audit wording; they are not a denominator-based
Teacher failure or `NO_LABEL` rate.

The frozen source matrix was 16 episodes in each of four cells: Akiri seat 0 vs Chevill seat 1
starting with each player, and the reverse roster orientation starting with each player. The source
report contains no gameplay-quality comparison against an independently declared Teacher, and its
closure distribution is not a strategic score.

### Serialized decision-family profile

These counts are the existing reader-derived A9/A8 closure evidence. They describe which source
surfaces were serialized and chosen; they do not mean that an unimplemented C1 label materializer
has been total over every family.

```text
SERIALIZED_CANDIDATE_ACTION_KINDS={ActivateAbility=1405807, CastSpell=7714, CastSpellMode=1275, CastWithFlashback=1278, CastWithKicker=1438, CycleCard=844, DECISION=17887, DeclareAttackers=388, PassPriority=122444, PlayLand=2778}
SERIALIZED_CHOSEN_ACTION_KINDS={ActivateAbility=43017, CastSpell=655, CastSpellMode=66, CastWithKicker=10, CycleCard=41, DeclareAttackers=388, PassPriority=76523, PlayLand=2132}
SERIALIZED_PENDING_DECISION_FAMILIES={CHOOSE_COLOR=129, CHOOSE_TARGETS=68, SELECT_CARDS=2336, YES_NO=106}
SERIALIZED_REQUIRED_PAYLOAD_FIELDS={additionalCostPayment=27, attackers=388, bands=388, costPayment=488, manaColorChoice=1082, paymentStrategy=4235, repeatCount=1560, targets=1955}
SERIALIZED_A8_UNCLASSIFIED=[]
SERIALIZED_A8_REAL_BLOCKERS=[]
```

The A8 closure audit separately covered absent but reachable families through accepted targeted/static
witnesses: `ORDER_OBJECTS`, `CHOOSE_OPTION`, `REORDER_LIBRARY`, `COMBAT_RESOLUTION`,
`SELECT_MANA_SOURCES`, folded `DECISION`, `CastWithFlashback` and `DeclareBlockers`. The complete
current family list was therefore classified, but no new quality metric is inferred from a family
that was not naturally observed in the A9 source run.

## 6. Hard conformance gates

The result below distinguishes inherited A9 source-trust evidence from C1_01 Teacher admission.
`YES_WITHIN_A9_HARNESS` is not a universal Teacher claim.

| Gate | Result | Evidence / limitation |
| --- | --- | --- |
| `TEACHER_PROVENANCE_VALID` | `YES_WITHIN_A9_HARNESS` | C0_05 and A9 retain exact policy/source/dataset identities; immutable Teacher configuration and materializer identities are absent. |
| `TEACHER_INFORMATION_SET_VALID` | `YES_FOR_PUBLIC_OBSERVATION_INPUT` | `DeterministicExternalPolicy.choose` accepts `TrainingObservation` and explicit policy state; no `GameState`, registry or diagnostic ledger is a dependency. |
| `TEACHER_DOMAIN_BINDING_VALID` | `YES_FOR_ADMITTED_A9_RECORDS` | A9 source evidence reports complete-domain membership and zero chosen-outside-domain records; the future label materializer is not present. |
| `TEACHER_FAILURE_SEMANTICS_VALID` | `NO` | The policy has `SemanticChoice.Gap`, but the existing A9 harness aborts on a gap rather than emitting a versioned derived `NO_LABEL` artifact. |
| `TEACHER_OUTPUT_IN_COMPLETE_DOMAIN` | `YES_FOR_ADMITTED_A9_RECORDS` | Accepted source records crossed A5/A6/A7 with exact chosen-input binding. |
| `TEACHER_STRUCTURED_DECISION_OWNERSHIP_VALID` | `NO_FOR_ADMISSION` | A9's public-only harness still auto-selects policy-relevant subchoices (sorted/first targets and cards, greedy distributions, default replacement options, explicit payment construction and sorted combat edges); no separately identified Teacher owns those components. |
| `TEACHER_NO_HIDDEN_AUTOCOMPLETION` | `NO_FOR_ADMISSION` | No hidden `GameState` or native AutoPay path is used, but A9's heuristic/first-item structured completion is still disallowed as admitted Teacher totality. |
| `TEACHER_NO_DOMAIN_TRUNCATION` | `YES_WITHIN_A9_HARNESS` | The source harness retains and audits the complete public domain; no top-K source list or hidden candidate repair was introduced. |
| `TEACHER_NO_FIRST_LEGAL_FALLBACK` | `YES_FOR_FAILURE_PATH` | An all-unaffordable domain returns `Gap`; A9's ordinary first-after-policy-order choice is behavior policy logic, not a failure repair. Its raw-ID tie ordering is nevertheless disqualifying. |
| `TEACHER_NO_SOURCE_SUBSTITUTION_ON_FAILURE` | `YES_FOR_FOCUSED_GAP_PATH` | Unsupported payload/incomplete structured domain produces `Gap` and no source action. No source choice is substituted. |
| `TEACHER_DETERMINISM_OR_DECLARED_RNG_VALID` | `YES_WITH_LEGACY_DECLARATION` | The source declares `DeterministicPolicyState`; existing source regeneration is `4/4`, and the focused repeated-input test is stable. |
| `TEACHER_RUNTIME_ID_RENAMING_SAFETY` | `NO` | Three feature-identical source-distinct probes changed selected semantic labels under source/target runtime-ID renaming. |
| `TEACHER_CANDIDATE_PERMUTATION_SAFETY` | `NO` | Two valid focused permutations with distinct IDs are stable, but a third focused pair with equal A9 ordering keys changes selection with physical list order. |
| `TEACHER_REPRODUCIBILITY_VALID` | `YES_FOR_FOCUSED_AND_EXISTING_EVIDENCE` | Same declared observation/state was stable; the accepted A9 source has four deterministic regeneration spot checks. |
| `TEACHER_SPLIT_DISCIPLINE_VALID` | `YES_BY_PROCEDURE` | The accepted C0_02 `semanticEpisodeId` split remains authoritative; no Teacher-specific rehash or row split was used. No TEST outcome was used to select a Teacher. |
| `TEACHER_LABEL_MATERIALIZER_IDENTITY_COMPLETE` | `NO` | No current C1_01 label materializer identity exists. |
| `TEACHER_CONFIGURATION_IDENTITY_COMPLETE` | `NO` | No immutable digest exists for the A9 matrix/policy configuration as a Teacher identity. |

The first hard admission failures are `TEACHER_RUNTIME_ID_RENAMING_SAFETY`,
`TEACHER_STRUCTURED_DECISION_OWNERSHIP_VALID`, `TEACHER_NO_HIDDEN_AUTOCOMPLETION`,
`TEACHER_FAILURE_SEMANTICS_VALID`, `TEACHER_LABEL_MATERIALIZER_IDENTITY_COMPLETE` and
`TEACHER_CONFIGURATION_IDENTITY_COMPLETE`. Quality comparison stops at that boundary.

### Structured-choice disposition

The A9 functions are valid source-harness mechanics for crossing the bounded A9 run, but they are
not evidence of an admitted Teacher controlling every subsequent policy-relevant choice. The
current implementation uses public-domain procedures including:

```text
targetRequirementChoices / public target selection -> sorted EntityId + first/minimum choices
selectCards -> distinct/sorted options + greedy constraint fill
DistributionDomain -> sorted targets + greedy round-robin allocation
ReplacementDomain -> default/zero FROM + first allowed TO
BudgetModalDomain -> first affordable mode
CombatResolutionDomain -> sorted edge IDs + supplied/default amounts
paymentPlanV3FromPublic -> public plan construction rather than a separately identified Teacher label
```

Those procedures do not read hidden state, but they are automatic heuristic completion inside the
source policy. C0_05 requires the admitted Teacher/model path to own the root and every subsequent
structured component, with unsupported or incomplete components producing `NO_LABEL`. The A9
harness instead aborts on `SemanticChoice.Gap` and has no versioned label materializer, so these
source-harness completions remain a hard admission failure.

## 7. Required symmetry characterization

The probes use only public `TrainingObservation` data. The two alternatives in each case have
identical admitted semantic fields while their source-bound runtime handles differ. The stable
alternative label exists only in the test fixture so the effect of a bijective handle rename can
be observed; it is not a model feature.

```text
FEATURE_IDENTICAL_SYMMETRY_COUNT=3
SOURCE_DISTINCT_SYMMETRY_COUNT=3
FOCUSED_RUNTIME_ID_RENAME_PROBES=3
FOCUSED_RUNTIME_ID_RENAME_SELECTION_CHANGED=3
TEACHER_SELECTION_STABLE_UNDER_RUNTIME_ID_RENAME=NO
FOCUSED_CANDIDATE_PERMUTATION_PROBES=3
FOCUSED_CANDIDATE_PERMUTATION_SELECTION_CHANGED=1
TEACHER_SELECTION_STABLE_UNDER_PHYSICAL_PERMUTATION=NO_FOR_EQUAL_A9_ORDERING_KEYS
FEATURE_IDENTICAL_SYMMETRY_FREQUENCY_IN_NATURAL_A9_DATASET=NOT_MEASURED
RUNTIME_ID_SENSITIVE_NATURAL_A9_CHOICE_FREQUENCY=NOT_MEASURED
```

The three probes are:

1. flat alternatives tied on `kind` and `actionSemantics`, differing in `sourceEntityId`;
2. flat alternatives tied on the same semantic fields, differing in `targetEntityIds`; and
3. a `TargetsDomain` with one target requirement and two source-distinct candidates.

The permutation probes include two valid source-distinct cases whose raw IDs distinguish the
comparator and one semantic-candidate pair that shares every A9 ordering key while differing in a
non-ordering domain field. The latter changes with physical list order, so candidate permutation
safety is not promoted to a general PASS. Duplicate complete-domain candidates remain rejected by
the source validator; this probe characterizes the broader equal-policy-order-key gap without
changing that validator.

The source implementation makes this dependency explicit in the root ordering at
`EnvironmentV1ExternalPolicy.kt:176-182`, and repeats raw-ID ordering in structured target/card/
distribution/search branches. The focused test is
`gym/src/test/kotlin/com/wingedsheep/gym/C1TeacherBootstrapCharacterizationTest.kt`.

This is not hidden feature engineering. The correct C1 response is to reject or resolve the
symmetry through the accepted Selection V2 / PolicyTieRng V1 mechanism in a separately identified
Teacher, not to leak `EntityId`, row index, source ordinal or allocation order into model input.

## 8. Quality characterization boundary

The following axes were reported where existing evidence permits. Strategic quality comparison was
not run after the hard gate failed.

| Axis | Result | Interpretation |
| --- | --- | --- |
| failure rate | `0` source failures in `64` accepted A9 episodes | Source-harness trust evidence only; not a Teacher `NO_LABEL` rate. |
| unsupported-decision rate | `FAIL_FAST_PROVEN_ZERO` in the accepted A9 run; focused unsupported shapes return `Gap` | This is not a measured rate: the A9 harness aborts before accumulating a derived label population. |
| `NO_LABEL` rate | `NOT_CHARACTERIZED` | A9 harness aborts on `Gap`; no derived label artifact exists. |
| decision-family coverage | Serialized natural families plus A8 targeted/static closure listed above | Coverage is source-surface evidence, not proof of a total admitted Teacher. |
| domain-size distribution | Existing A9 artifacts do not provide a Teacher-selection report by size | `NOT_RUN`; no new scan was started after the hard failure. |
| structured-choice coverage | A9 source branches and A8 closure are present; focused target branch exercised | `NOT_ADMISSION_TOTAL`; full label materializer is absent. |
| source-choice agreement | `NOT_RUN` | There is no second Teacher or independent quality reference; source choice remains factual TrajectoryV1 data. |
| action/pass distribution | Existing serialized action/chosen-kind counts above | Diagnostic source behavior only. |
| episode closure/game length | `5 GAME_TERMINAL`, `59 INTERRUPTED`; per-episode length not used | Closure is factual lifecycle evidence, not strategic strength. |
| seat/roster/start cells | `16` source episodes per each of four A9 cells | No cell-specific strategic metric was used for selection. |
| deterministic regeneration | Existing `4/4`; focused same-input test stable | Reproducibility does not establish quality. |
| feature-identical symmetry frequency | Synthetic focused count `3`; natural frequency not measured | The hard gate is established without a large campaign. |
| gameplay strength | `NOT_RUN` | No win-rate/expert/optimality claim is authorized or made. |

These limitations are deliberate. C0_05 says source trust is not Teacher quality, and C0_02 says
behavior agreement is not gameplay strength. A9 therefore cannot be promoted based on its trusted
trajectory count or a source-policy self-agreement number.

## 9. Focused characterization tests

The new test class is test-only and uses no engine state:

```text
gym/src/test/kotlin/com/wingedsheep/gym/C1TeacherBootstrapCharacterizationTest.kt
```

It covers:

```text
raw sourceEntityId ordering finding
runtime-ID rename finding for flat source alternatives
flat candidate permutation behavior
equal-policy-order-key permutation finding
raw targetEntityIds ordering finding
structured target runtime-ID rename finding
structured target candidate permutation behavior
same observation/state reproducibility
unsupported required payload -> Gap / no source substitution
incomplete structured domain -> Gap
all-unaffordable domain -> Gap / no first-legal failure fallback
```

The expected runtime-ID-sensitive outcomes are asserted as characterization findings so a later
review cannot accidentally treat them as an admission PASS. The test does not modify the policy or
adapt it silently.

## 10. Split, TEST and immutability discipline

```text
FINAL_TEST_USED_TO_SELECT_TEACHER=NO
FINAL_TEST_USED_FOR_TRAINING=NO
FINAL_TEST_USED_FOR_HYPERPARAMETER_SELECTION=NO
NO_TEACHER_SPECIFIC_REHASH=YES
NO_DECISION_ROW_RANDOM_SPLIT=YES
TRAJECTORY_V1_MUTATED=NO
SOURCE_CHOSEN_SEMANTIC_VALUES_REWRITTEN=NO
EPISODE_CLOSURE_REWRITTEN=NO
POLICY_PROVENANCE_REWRITTEN=NO
```

The existing manifest identity was used only as source-provenance context. No held-out TEST
outcome, decision label or TEST-derived quality metric selected the admission result. The process
stopped on a public synthetic hard-gate reproducer before any natural-partition quality selection.

## 11. Follow-up dependency, not started

The smallest generic follow-up is a separately authorized
`PUBLIC_OBSERVATION_TEACHER_LABEL_MATERIALIZER_V1` implementation slice. It must:

```text
consume only ACTING_PLAYER_LEGAL_INFORMATION_SET
retain the complete source domain and exact source binding
own every supported structured response component
emit NO_LABEL on unsupported/failing/incomplete cases
carry an immutable configuration identity/digest
carry an immutable label-materializer identity
avoid raw runtime-ID deterministic preference
use Selection V2 / PolicyTieRng V1-compatible symmetry handling where needed
preserve TrajectoryV1 and the C0_02 split unchanged
```

That follow-up must have its own RED -> implementation -> independent-review boundary. It was not
implemented, and no A9 policy identity is silently changed to claim that it already exists.

## 12. Required C1_01 status

```text
A9_PROVENANCE_VALID=YES
A9_INFORMATION_SET_VALID=YES
A9_DOMAIN_BINDING_VALID=YES
A9_FAILURE_RATE_CHARACTERIZED=YES
A9_QUALITY_CHARACTERIZED=NO
A9_RUNTIME_ID_RENAMING_SAFE=NO
A9_CANDIDATE_PERMUTATION_SAFE=NO
A9_SELECTION_V2_BOOTSTRAP_SUITABILITY=FAIL

FIRST_C1_TEACHER_SELECTION=NONE
TEACHER_BOOTSTRAP_ADMITTED=NO
TEACHER_ADMISSION_PURPOSE=NONE

FINAL_TEST_USED_TO_SELECT_TEACHER=NO
TRAJECTORY_V1_MUTATED=NO

P1=0
P2=0

C1_01_IMPLEMENTATION_PASS=YES
C1_01_READY_FOR_ACCEPTANCE=YES
C1_01_FINAL_ACCEPTANCE_PASS=NO

TRAINING_AUTHORIZED=NO
SMALL_LEARNER_SMOKE_AUTHORIZED=NO
RL_AUTHORIZED=NO
SELF_PLAY_AUTHORIZED=NO
SEARCH_IMPLEMENTATION_AUTHORIZED=NO
WORLD_MODEL_IMPLEMENTATION_AUTHORIZED=NO
LARGE_CORPUS_GENERATION_AUTHORIZED=NO

NEXT_TASK_STARTED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
```

The final acceptance field remains `NO` until the pushed exact commit is independently reviewed.
This report does not authorize training or the next C1 slice.

## 13. Verification record

```text
GIT_DIFF_CHECK=PASS
JUST_WRAPPER=BLOCKED
JUST_WRAPPER_REASON=WinError 193 before Gradle in scripts/gradle-locked
NATIVE_GRADLE_FALLBACK=PASS
NATIVE_FOCUSED_TEST_CLASS=C1TeacherBootstrapCharacterizationTest
NATIVE_FOCUSED_TESTS=11
NATIVE_FOCUSED_TEST_FAILURES=0
NATIVE_FOCUSED_TEST_RUN_WAS_FRESH=YES
NATIVE_GAME_SERVER_RECOVERY_COMPILE=PASS
NATIVE_FORCED_REBUILD_ATTEMPT=FAILED_UNRELATED_TRANSIENT_MISSING_SDK_CLASS
FULL_GYM_SUITE=NOT_RUN
FULL_RULES_SUITE=NOT_RUN
A9_GENERATION=NOT_RUN
TRAINING=NOT_RUN
```

The forced rebuild failure occurred in untouched `game-server`/dependency compilation before the
focused test and was recovered by a successful `:game-server:compileKotlin` invocation; the final
fresh focused run then passed. No source or build file was changed to work around it. The focused
Gym test is the only execution gate justified by this test-only characterization scope.
