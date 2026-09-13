# C1_02 PublicObservationTeacherV1

```text
TASK=C1_02_PUBLIC_OBSERVATION_BOOTSTRAP_TEACHER_V1
DATE=2026-09-13
STATUS=IMPLEMENTED_PENDING_INDEPENDENT_REVIEW
BASE=4af111cad62d0f268f4d28e2548fbb23a18a9a47
IMPLEMENTATION_HEAD_AT_EVIDENCE_CAPTURE=777a1308b40821748f0a1513ff9c9da811781f27
BRANCH=chris/c1-02-public-observation-bootstrap-teacher-v1-20260913
WORKTREE=C:\Users\chris\.config\superpowers\worktrees\argentum-engine\c1-02-public-observation-bootstrap-teacher-v1-20260913
```

## Identity and boundary

```text
TEACHER_POLICY_IDENTITY=argentum-ml-public-observation-bootstrap-teacher@v1
TEACHER_CONTRACT_IDENTITY=argentum-ml-teacher-bootstrap@v1
TEACHER_CONFIG_SCHEMA_IDENTITY=argentum-ml-public-observation-teacher-config@v1
TEACHER_CONFIG_DIGEST=fa358597c09ce466e1be1823de485e0accd84be622184fa1d6505806aff0d7d8
TEACHER_RESULT_SCHEMA_IDENTITY=argentum-ml-public-observation-teacher-result@v1
TEACHER_SOURCE_IDENTITY=argentum-ml-public-observation-teacher-source@v1
TEACHER_SOURCE_COMMIT_USED_IN_CONFORMANCE=777a1308b40821748f0a1513ff9c9da811781f27

TEACHER_INPUT_INFORMATION_SET=ACTING_PLAYER_LEGAL_INFORMATION_SET
RAW_GAME_STATE_USED=NO
SOURCE_TARGET_USED_AS_POLICY_INPUT=NO
OUTCOME_USED_AS_POLICY_INPUT=NO
```

The public policy channel contains only the immutable C1_00 model input and candidate feature
views. Sample-local aliases remain allowed as opaque public references so relationships can be
preserved. The scorer reads only the generic candidate `kind`; alias values, alias ordinals,
lexical order, hashes, physical positions, source ordinals, exact bindings, targets, and outcomes
are not preference features. Binding metadata is joined only after scoring to return the exact
source-authorized result. The Teacher request is factory-only over a reader-issued C1_00
`InferenceRequest`; its optional transport view can only reorder already-authorized candidate
records and cannot construct or replace source bindings.

## Decision-family support

```text
FLAT_DECISION_SUPPORT=PASS
SUPPORTED_FLAT_DECISION_FAMILIES=[ACTION_CANDIDATES,FOLDED_DECISION_OPTIONS]
```

All structured families are intentionally `NO_LABEL` in this slice because C1_00 structured
inference remains non-total. No structured decoder, hidden subchoice, Rules call, engine AI,
AutoPay, flattening, source-response reuse, or first-item fallback was added.

```text
STRUCTURED_DECISION_FAMILIES_NO_LABEL=[
  targets@v2,
  card-selection@v1,
  mode-selection@v1,
  distribution@v1,
  ordering@v1,
  split-piles@v1,
  search-library@v1,
  reorder-library@v1,
  combat-resolution@v1,
  mana-sources@v3,
  replacement@v1,
  budget-modal@v1
]
```

## Scoring and selection

```text
TEACHER_RUNTIME_ID_RENAMING_SAFE=YES
TEACHER_CANDIDATE_PERMUTATION_SAFE=YES
TEACHER_SOURCE_BINDING_ORDINAL_PREFERENCE=NO
TEACHER_ALIAS_VALUE_PREFERENCE=NO
ONE_SCORE_PER_REAL_CANDIDATE=YES
NO_CANDIDATE_OMISSION=YES
NO_CANDIDATE_ADDITION=YES
NO_CANDIDATE_TRUNCATION=YES

SELECTION_CONTRACT_IDENTITY=argentum-ml-policy-selection@v2
POLICY_RNG_IDENTITY=argentum-ml-policy-tie-rng@v1
UNIQUE_MAX_RNG_WORDS=0
UNRESOLVED_EXACT_TIE_POLICY=POLICY_TIE_RNG_V1
NON_FINITE_SCORE_FAIL_CLOSED=YES
NO_EXECUTABLE_CANDIDATE=NO_LABEL
```

The generic reference scorer's complete preference table is part of the immutable configuration
digest. Selection delegates to the existing Selection V2 implementation. Unique maxima and valid
semantic-discriminator ties consume zero words; only unresolved exact symmetry reaches PolicyTieRng
V1. No Python random source, engine RNG, wall clock, process identity, or candidate-list order is
used.

## Result and provenance contract

```text
TEACHER_NO_LABEL_IMPLEMENTED=YES
TEACHER_CONFIGURATION_IDENTITY_COMPLETE=YES
TEACHER_RUNTIME_IDENTITY_COMPLETE=YES
BOOTSTRAP_LABEL_MATERIALIZER_IMPLEMENTED=NO
LABEL_MATERIALIZER_IDENTITY=NONE
```

`SelectedTeacherResultV1` returns the exact `ExactSemanticSourceBinding`, selected source ordinal,
resulting PolicyTieRng state, cursor evidence, and non-sensitive diagnostics. `NoLabelTeacherResultV1`
returns a typed reason and preserves the incoming RNG state. Invalid configuration is rejected;
unsafe current decisions fail closed.

## Focused evidence

The focused test files are:

```text
ml/tests/test_public_observation_teacher.py
ml/tests/test_public_observation_teacher_review.py
```

It covers RED-first package absence, immutable config and identity, complete-domain/truncation
rejection, runtime-ID and opaque-alias renaming, physical permutation, source-ordinal and recorded
target leakage, reader-issued binding authority, unbound scorer substitution, separate contract and
policy identities, C1 model-facing feature vocabulary, one score per real candidate, unique-max/tie
RNG behavior, exact response binding, determinism, non-finite scores, empty executable domains,
unknown config and structured versions, and the explicit structured `NO_LABEL` path.

```text
RED=ModuleNotFoundError: argentum_ml.teacher
PYTHON_FOCUSED_TESTS=25/25_PASS
COMPILEALL=PASS
```

The full Python suite was run from a freshly installed local package:

```text
PYTHON_FULL_SUITE=101/101_PASS
KOTLIN_GOLDEN_FIXTURE_LINE_ENDING=LF
```

The Kotlin-materialized golden fixture is now explicitly LF-pinned by `.gitattributes`; the
reader's CR/BOM rejection remains unchanged. The repository recipes were run separately:

```text
JUST_ML_TEST=PASS
JUST_ML_CHECK=PASS
JUST_WRAPPER=NOT_BLOCKED
```

No TEST gameplay result, source dataset, A9 regeneration, training run, or corpus generation was
used.

## Scope and acceptance status

```text
TRAJECTORY_V1_MUTATED=NO
C0_CONTRACT_CHANGED=NO
C1_00_CONTRACT_CHANGED=NO
RULES_CHANGED=NO
GYM_SEMANTICS_CHANGED=NO
P1=0
P2=0
REVIEW_P1_1_UNBOUND_SCORER_SUBSTITUTION=FIXED
REVIEW_P1_2_UNTRUSTED_REQUEST_BINDING_CONSTRUCTION=FIXED
REVIEW_P2_1_CONTRACT_POLICY_IDENTITY_CONFLATION=FIXED
REVIEW_P2_2_MODEL_FACING_FILTER_OVERREACH=FIXED

PUBLIC_OBSERVATION_TEACHER_V1_IMPLEMENTATION_PASS=YES
PUBLIC_OBSERVATION_TEACHER_V1_CODE_REVIEW_PASS=NO
C1_02_READY_FOR_ACCEPTANCE=YES
C1_02_FINAL_ACCEPTANCE_PASS=NO

TEACHER_QUALITY_CHARACTERIZED=NO
FIRST_C1_TEACHER_SELECTION=NONE
TEACHER_BOOTSTRAP_ADMITTED=NO
TRAINING_AUTHORIZED=NO
SMALL_LEARNER_SMOKE_AUTHORIZED=NO
C1_03_AUTHORIZED=NO
BOOTSTRAP_LABEL_MATERIALIZER_AUTHORIZED=NO
NEXT_TASK_STARTED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
```

The cross-language golden fixture was normalized to LF and pinned with a `.gitattributes` rule;
the reader's canonical CR/BOM rejection remains unchanged. The implementation is ready for
independent exact-SHA review.
