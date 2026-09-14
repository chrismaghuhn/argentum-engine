# C1_03B Folded Selection Ownership Authority Fix

Date: 2026-09-14

This slice implements the narrow authority fix identified by accepted C1_03A. It does not rerun readmission, scan the accepted artifact, tune the Teacher, or create training labels.

## Identity and status

```text
TASK=C1_03B_FOLDED_SELECTION_OWNERSHIP_AUTHORITY_FIX
BASE_SHA=7b4631ba039c27f225cd6fd8d114334c3096b137
IMPLEMENTATION_HEAD=d5ff90936199fd144b6ccb519184b1d1d0c214a5
FIX_COMMIT=d5ff90936199fd144b6ccb519184b1d1d0c214a5
ORIGIN_MAIN_AT_START=7b4631ba039c27f225cd6fd8d114334c3096b137
UPSTREAM_MAIN_REFERENCE=3f46367d87c88bcf156a843a9e69fd29e1693872

C1_03_FINAL_ACCEPTANCE_PASS=YES
C1_03A_FINAL_ACCEPTANCE_PASS=YES
C1_03A_HISTORICAL_EVIDENCE_CHANGED=NO

ROOT_CAUSE=OWNERSHIP_CHECK_REPRESENTATION_MISMATCH
```

The historical C1_03 and C1_03A reports were not edited. Two test-only C1_03A expectations were updated because the authorized fix changes the expected predicate result: an identity-bearing valid selection is now owned, and a non-factory binding is now rejected.

## Old and new invariants

```text
OLD_INVARIANT=RAW_SOURCE_JSON_EQUALS_PUBLIC_ALIAS_JSON
OLD_INVARIANT_VALID=NO

NEW_INVARIANT=SELECTED_ORDINAL_BOUND_TO_FACTORY_ISSUED_EXACT_BINDING_AND_VALIDATED_PUBLIC_CANDIDATE

FACTORY_BINDING_AUTHORITY_REQUIRED=YES
SOURCE_BINDING_ORDINAL_REQUIRED=YES
READER_ISSUED_PUBLIC_CANDIDATE_REQUIRED=YES
RAW_PUBLIC_JSON_EQUALITY_REQUIRED=NO
PYTHON_OBJECT_IDENTITY_REQUIRED=NO
SECOND_ALIAS_PROJECTOR_CREATED=NO
```

The production change is limited to `ml/src/argentum_ml/characterization/c1_03.py:506-535`, inside `folded_selection_is_teacher_owned()`.

The predicate now:

1. validates the selected ordinal and resolves the reader-issued public candidate at that ordinal;
2. resolves `request.source_bindings.exact_binding_for(ordinal)`;
3. requires the returned result binding to be an `ExactSemanticSourceBinding` value-equal to that expected factory binding;
4. requires a complete `chosen-response` binding with a mapping response body and matching ordinal audit; and
5. requires only that the public candidate carries a mapping `actionSemantics` value.

It no longer compares the raw source response with the public alias response and does not reconstruct aliases.

## RED characterization

Focused file: [test_c1_03b_folded_ownership_authority.py](../../ml/tests/test_c1_03b_folded_ownership_authority.py).

The focused baseline was run before the production edit against the accepted behavior. The six tests exposed the intended boundary:

| Case | Accepted behavior before fix | Expected authority result | RED status |
| --- | --- | --- | --- |
| Identity-bearing valid selection | `False` | `True` | RED |
| Forged public-looking binding at same ordinal | `True` | `False` | RED |
| Entity-free valid response | `True` | `True` | baseline green |
| Binding from another ordinal | `False` | `False` | baseline green |
| Value-equivalent binding copy | `False` | `True` | RED |
| Incomplete chosen-response binding | `False` | `False` | baseline green |

```text
C1_03B_RED_CHARACTERIZATION=PASS
```

The RED run had three failures for the intended reasons: the old raw/public comparison rejected the valid identity-bearing response and its value-equivalent copy, while it accepted the forged public-looking response.

## GREEN results

```text
ENTITY_BEARING_FALSE_NEGATIVE_FIXED=YES
FORGED_BINDING_FALSE_POSITIVE_FIXED=YES
ENTITY_FREE_REGRESSION=PASS
WRONG_ORDINAL_BINDING_REJECTED=YES
VALUE_EQUIVALENT_BINDING_COPY_ACCEPTED=YES

VALID_ENTITY_BEARING_FOLDED_RESPONSE=OWNED
VALID_ENTITY_FREE_FOLDED_RESPONSE=OWNED
FORGED_EXACT_BINDING=REJECTED
WRONG_ORDINAL_BINDING=REJECTED

ACTION_OWNERSHIP_SEMANTICS_UNCHANGED=YES
```

The normal Reader → `InferenceRequest` → Teacher → Selection V2 path remains the source of the selected ordinal and factory binding. A distinct immutable `ExactSemanticSourceBinding` with equal contents is accepted; Python allocation identity is not used.

## Verification

```text
FOCUSED_C1_03B_TESTS=6 tests, PASS
C1_03_TESTS=31 tests, PASS
C1_03A_TESTS=3 tests, PASS
FULL_ML_TESTS=160 tests, PASS
PYTHON_COMPILEALL=PASS
JUST_ML_TEST=PASS (160 tests)
JUST_ML_CHECK=PASS
```

The `ml` package was installed from this worktree before focused GREEN verification; this prevents the global package from masking the local production edit.

## Scope closure

```text
FULL_ARTIFACT_RESCAN_STARTED=NO
ARTIFACT_REMATERIALIZED=NO
TEACHER_READMISSION_RUN_STARTED=NO

TEACHER_CHANGED=NO
TEACHER_CONFIG_CHANGED=NO
SELECTION_V2_CHANGED=NO
POLICY_TIE_RNG_CHANGED=NO
C1_00_CHANGED=NO
MODEL_FACING_SCHEMA_CHANGED=NO
KOTLIN_PROJECTION_CHANGED=NO

RULES_CHANGED=NO
GYM_SEMANTICS_CHANGED=NO
TRAJECTORY_V1_CHANGED=NO
DATASET_CHANGED=NO
DERIVED_ARTIFACT_CHANGED=NO
C1_03_HISTORICAL_EVIDENCE_CHANGED=NO
C1_03A_HISTORICAL_EVIDENCE_CHANGED=NO
LABELS_CREATED=NO
TRAINING_CODE_ADDED=NO

TEACHER_READMISSION_AUTHORIZED=NO
BOOTSTRAP_LABEL_MATERIALIZER_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
C1_04_AUTHORIZED=NO
```

No claim is made that all historical 1,925 rows now pass. A fresh artifact readmission is a separate task.

## Self-review

```text
P1=0
P2=0
P3=0
LOCAL_SELF_REVIEW=PASS

C1_03B_CODE_REVIEW_PASS=NO
C1_03B_FINAL_ACCEPTANCE_PASS=NO
PR_CREATED=NO
NEXT_TASK_STARTED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
```

The remaining `NO` values mean external exact-SHA review and acceptance are still pending; they are not test failures.
