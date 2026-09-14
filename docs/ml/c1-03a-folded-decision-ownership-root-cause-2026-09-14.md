# C1_03A Folded Decision Ownership Root-Cause Characterization

Date: 2026-09-14

This is a test-only/root-cause characterization. It does not reopen the accepted C1_03 result and does not implement a fix.

## Frozen identities and accepted population

```text
TASK=C1_03A_FOLDED_DECISION_OWNERSHIP_ROOT_CAUSE_CHARACTERIZATION
BASE_SHA=86f24b719a0a39ec4f8f3cafc55c5fa9c54012a3
ORIGIN_MAIN_AT_START=86f24b719a0a39ec4f8f3cafc55c5fa9c54012a3
UPSTREAM_MAIN_REFERENCE=3f46367d87c88bcf156a843a9e69fd29e1693872
C1_03_ACCEPTED_MERGE=86f24b719a0a39ec4f8f3cafc55c5fa9c54012a3

DERIVED_ARTIFACT_ID=be545edcb7809a34d78ab9be03476b3cd29ab42e54e6c7f7e20b25b5bcc05a4a
SAMPLES_CONTENT_DIGEST=da034c0910fb3ebfc29929d1a8bfc4ce95e4d548873870684d37789ed5c8ae8b
PLAN_DIGEST=bc186eb4df9830039fb1b0e474700afdb82e1cca033483afcad6bfd8d24fee79
SOURCE_DATASET_ID=69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03
SOURCE_MANIFEST_CONTENT_DIGEST=de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2

ACCEPTED_FOLDED_DECISIONS=2149
ACCEPTED_FOLDED_OWNERSHIP_FAILURES=1925
C1_03_FINAL_ACCEPTANCE_PASS=YES
C1_03_HISTORICAL_RESULT_REOPENED=NO
```

The repository's original C1_03 measurement report is the pre-acceptance evidence artifact and remains unchanged. The accepted PR #189 merge and acceptance result above are the supplied task authority for this follow-up.

## Audit scope and method

No accepted 8-GB derived artifact was opened, rematerialized, or rescanned. The investigation used one tiny disposable synthetic artifact only for the focused fixture, plus:

- static inspection of the Python runtime, Teacher, Selection V2, reader, variable-domain transport, and C1_03 predicate;
- static inspection of the Kotlin C1 learner projection and artifact materializer;
- the committed C1_03 aggregate and bounded divergence evidence;
- a three-test synthetic fixture through `DerivedArtifactReader`, `InferenceRequest.from_validated_sample()`, `PublicObservationTeacherRequestV1`, `PublicObservationTeacherV1.select()`, and Selection V2.

The focused test is [test_c1_03a_folded_ownership_characterization.py](../../ml/tests/test_c1_03a_folded_ownership_characterization.py). It uses only synthetic entity IDs. It does not construct a normal `SelectedTeacherResultV1` by hand; the third test is explicitly a negative boundary for the separate forged-result question.

## Static binding audit

| Stage | Value | Raw source IDs? | Public aliases? | Authoritative? | Model-visible? |
| --- | --- | ---: | ---: | --- | ---: |
| CompleteLegalDomain candidate | `binding.completeLegalDomain.candidates[N].actionSemantics` | YES for entity-bearing responses | NO | YES, source-domain authority | NO |
| Model-facing candidate | `input.domain.candidates[N].actionSemantics` | NO | YES | Derived by the accepted projection | YES |
| ExactSemanticSourceBinding | `{"type":"chosen-response","response": completeLegalDomain.candidates[N].actionSemantics}` | YES | NO | YES, factory-issued binding authority | NO |
| CandidateFeature / VariableDomainItem | `feature_view=input.domain.candidates[N]`, `source_binding_ordinal=N` | NO | YES | Ordinal is a source-binding address; feature is transport | YES |
| PublicObservationTeacherRequestV1 | Reader-issued `VariableDomainItem` plus `SourceSelectionBindings` | Raw binding is retained outside the scorer | YES in item | Factory-validated wrapper | YES through item features |
| Selection V2 result | `source_binding_ordinal=N` plus `exact_source_binding=request.source_bindings.exact_binding_for(N)` | YES in exact binding | Not exposed to scorer | YES for normal Selection V2 output | NO |
| Current ownership comparison | `canonical_json(exact_response.response) == canonical_json(candidate.actionSemantics)` | YES on left | YES on right | Diagnostic/admission gate, not source authority | NO |

### Exact code path

1. `C1ModelFacingProjectionV1.project()` writes the raw `completeLegalDomain`, the selected raw source binding, sequential `sourceBindingOrdinals`, and `entityAliasBindings` together. See `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/C1ModelFacingProjectionV1.kt:107-206`. The materializer calls this projection once per decision and writes the canonical sample; see `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/C1LearnerArtifactMaterializer.kt:82-101`.
2. The same projection creates model-facing candidates through `projectCandidate(..., FeatureProjectionMode.MODEL, relations)`, including `actionSemantics`; see `C1ModelFacingProjectionV1.kt:394-425` and `:645-710`.
3. Folded response types are explicitly routed through `projectFoldedResponseSemantics`; see `C1ModelFacingProjectionV1.kt:946-965` and `:1109-1134`. Identity fields/lists are projected through the relation table and become aliases in model mode; see `C1ModelFacingProjectionV1.kt:1186-1290` and `:1352-1380`.
4. The Python reader validates the source domain, alias bindings, model input, and source ordinals as separate channels; see `ml/src/argentum_ml/data/derived_reader.py:501-508`. Model-facing Folded responses are validated as alias-bearing public values by `ml/src/argentum_ml/contracts/model_facing.py:310-365` and `:434-461`; they are not required to be raw-JSON-equal to the source response.
5. `SourceSelectionBindings.from_derived_binding_channel()` enumerates the authoritative source candidates and derives one exact response binding per source ordinal; see `ml/src/argentum_ml/inference/runtime.py:124-237` and `:431-462`.
6. `InferenceRequest.from_validated_sample()` requires the model input and candidate ordinals/order to match the reader-issued sample and joins each candidate to the source binding at the same ordinal; see `ml/src/argentum_ml/inference/runtime.py:247-315`. Its direct field check intentionally covers source candidate fields in `_DIRECT_SOURCE_CANDIDATE_KEYS` but does not raw-compare `actionSemantics` across representation layers; see `runtime.py:35-54` and `:464-477`.
7. `PublicObservationTeacherV1.select()` builds Selection V2 candidates from `request.source_bindings.exact_binding_for(ordinal)` and returns the selected exact binding and ordinal; see `ml/src/argentum_ml/teacher/public_observation_teacher.py:106-229`. Selection V2 preserves that exact binding; see `ml/src/argentum_ml/selection/selection_v2.py:64-120` and `:137-199`.
8. `folded_selection_is_teacher_owned()` resolves the public candidate by the returned ordinal, extracts the exact source response, and compares their canonical JSON directly; see `ml/src/argentum_ml/characterization/c1_03.py:506-525`. That final comparison is the crossed-representation boundary.

## Minimal characterization results

### Case A: entity-free Folded response

Synthetic source and model response:

```json
{"optionIndex":0,"type":"OptionChosenResponse"}
```

The normal reader and Teacher factories produced `source_binding_ordinal=0`, an exact source response binding for ordinal 0, and a `SelectedTeacherResultV1` from Selection V2. The public response and exact source response are byte-equivalent in this case.

```text
DOMAIN_MEMBERSHIP_VALID=YES
SOURCE_BINDING_VALID=YES
PUBLIC_PROJECTION_VALID=YES
CURRENT_OWNERSHIP_CHECK_VALID=YES
MINIMAL_ENTITY_FREE_CASE=PASS
```

### Case B: identity-bearing Folded response

The smallest accepted identity-bearing fixture uses one synthetic card:

```text
source entity ID = synthetic-folded-card
entityAliasBindings = entity-2 -> synthetic-folded-card
source actionSemantics = {"selectedCards":["synthetic-folded-card"],"type":"CardsSelectedResponse"}
model actionSemantics = {"selectedCards":["entity-2"],"type":"CardsSelectedResponse"}
sourceBindingOrdinal = 0
```

The reader accepts the sample, the source ordinal is present in the complete Folded binding channel, and the normal Teacher returns the exact factory-issued source binding for ordinal 0. The public candidate is the alias projection associated with that same source entity in the fixture's authoritative `entityAliasBindings`.

The exact values at the failing comparison are therefore:

```text
exact_source_binding.exact_response.response = {"selectedCards":["synthetic-folded-card"],"type":"CardsSelectedResponse"}
request.item.candidates[0].feature_view.actionSemantics = {"selectedCards":["entity-2"],"type":"CardsSelectedResponse"}
canonical_json(left) == canonical_json(right) = False
folded_selection_is_teacher_owned(request, result) = False
```

This is the requested RED reproduction: the source ordinal, exact binding, and public projection are all valid, while the current ownership predicate rejects the selection because it compares two representations of one alternative as if they were the same JSON representation.

```text
DOMAIN_MEMBERSHIP_VALID=YES
SOURCE_BINDING_VALID=YES
PUBLIC_PROJECTION_VALID=YES
CURRENT_OWNERSHIP_CHECK_VALID=NO
MINIMAL_IDENTITY_BEARING_CASE=RED_REPRODUCED
```

### Separate negative boundary: forged result binding

The focused test also replaces the normal result's exact binding with a separately constructed `ExactSemanticSourceBinding` carrying the public alias response while retaining ordinal 0. The current predicate returns `True` for that substituted result because it checks the ordinal-selected public candidate and JSON equality, but does not rejoin `result.exact_source_binding` to `request.source_bindings.exact_binding_for(result.source_binding_ordinal)`.

This is a separate `FOLDED_DOMAIN_CONTRACT_GAP` / future authority-hardening finding. It is not attributed to the accepted 1,925 false negatives, which occur on normal factory-issued results before any such substitution.

## Four-question authority result

### A. Domain membership

YES for both minimal fixtures. The reader validates Folded response membership against `completeLegalDomain`; the projection emits sequential source ordinals; and the normal result selects ordinal 0 from that source-addressed domain.

### B. Source binding

YES for both minimal fixtures. `SourceSelectionBindings` derives the exact response from the source candidate at ordinal 0. Selection V2 receives that object and the normal Teacher result returns the same factory-issued binding with its ordinal audit set to 0.

### C. Public projection

YES for both minimal fixtures. The entity-free response is unchanged. The identity-bearing response is the accepted relation-table projection from `synthetic-folded-card` to `entity-2`; the test takes that relation from the fixture's `entityAliasBindings` rather than implementing a second aliasing algorithm.

### D. Current C1_03 ownership check

MIXED by representation: YES for entity-free Folded responses and NO for the identity-bearing response. The current predicate's direct canonical-JSON equality is not a valid cross-layer equivalence relation for entity-bearing Folded responses.

## Does the ordinal already provide the required authority?

The answer is split by meaning:

- `InferenceRequest.from_validated_sample()` already proves the transport address/provenance relationship: the reader-issued model input must match the validated sample, candidate ordinals must be complete and ordered exactly like the source ordinals, and each transport candidate is joined to the source candidate at that ordinal. The exact source binding is then derived from that source candidate.
- It does not prove raw JSON equality of Folded `actionSemantics`; that would contradict the accepted source/public projection contract. The reader-issued sample and Kotlin projection are the authority for the raw-to-public relation, not a byte comparison between the two layers.
- Normal Selection V2 cannot choose an arbitrary response: `PublicObservationTeacherV1.select()` constructs each `SelectionCandidate` from the request's factory-issued binding, and Selection V2 returns the winner's binding. A caller or buggy Teacher can nevertheless construct/replace the public dataclass result with a different `ExactSemanticSourceBinding` at the same ordinal; the negative-boundary test shows the current ownership predicate does not independently reject that substitution.

A candidate future invariant, not implemented here, is:

```text
selected source ordinal
+ exact factory-issued source binding for that ordinal
+ reader-issued/validated public candidate at that ordinal
+ complete semantic response alternative
```

The future ownership check would need to validate binding identity/provenance and the accepted source/public projection relation. It must not require raw source JSON to equal public alias JSON. This is a follow-up contract direction only.

## What the accepted 1,925 evidence proves

The committed C1_03 evidence proves:

- `FOLDED_DECISION_OPTIONS_DECISIONS=2149`;
- `FOLDED_OWNERSHIP_FAILURE_COUNT=1925`;
- `ACTION_OWNERSHIP_FAILURE_COUNT=0`;
- `C1_00_AUTHORITY_FAILURE_COUNT=0`;
- `TEACHER_FLAT_FAILURE_COUNT=0`;
- `PRIVACY_FAILURE_COUNT=0`;
- `INVALID_SELECTION_COUNT=0`;
- `HIDDEN_POLICY_FALLBACK_COUNT=0`;
- `CANDIDATE_TRUNCATION_COUNT=0`;
- all 1,925 failures were counted after normal Teacher selection, not as reader or Selection V2 failures;
- five bounded Folded divergence records already persisted public `CardsSelectedResponse` alias shapes, but they do not persist exact raw source bindings or a per-row ordinal-to-source comparison.

The aggregate and bounded records do not logically prove that every one of the 1,925 rows has the exact same identity-bearing shape. They establish the structural failure and provide compatible public examples, while the minimal fixture proves the exact representation mismatch mechanism. Reading the source rows again would be required to classify every failure individually, and that scan is outside this task.

```text
STRUCTURAL_ROOT_CAUSE_REPRODUCED=YES
ACTUAL_FAILURE_POPULATION_FULLY_CLASSIFIED=NO
FULL_POPULATION_SCAN_REQUIRED=YES (only for exhaustive per-row classification)
FULL_POPULATION_SCAN_STARTED=NO
UNANSWERED_FULL_SCAN_QUESTION=Which of the 1,925 rows are identity-bearing versus entity-free, and does any row have a different source/public projection defect?
```

## Classification

```text
ROOT_CAUSE_CLASSIFICATION=OWNERSHIP_CHECK_REPRESENTATION_MISMATCH
```

Evidence-backed interpretation: the minimal normal-factory identity-bearing case has valid domain membership, valid exact source binding, valid public alias projection, and a false current ownership result caused by the direct canonical JSON comparison. No exact-source-binding defect, model-projection defect, or Teacher-selection defect was reproduced. The separate forged-result observation is a non-causal `FOLDED_DOMAIN_CONTRACT_GAP` for a future authority check; it does not explain the 1,925 normal selections.

## Scope and authorization closure

```text
FULL_ARTIFACT_RESCAN_STARTED=NO
ARTIFACT_REMATERIALIZED=NO

PRODUCTION_CODE_CHANGED=NO
TEACHER_CHANGED=NO
C1_00_CHANGED=NO
SELECTION_V2_CHANGED=NO
POLICY_TIE_RNG_CHANGED=NO

RULES_CHANGED=NO
GYM_SEMANTICS_CHANGED=NO
TRAJECTORY_V1_CHANGED=NO
DATASET_CHANGED=NO
DERIVED_ARTIFACT_CHANGED=NO
INFERENCE_RUNTIME_PRODUCTION_CHANGED=NO
C1_03_HISTORICAL_EVIDENCE_CHANGED=NO
TRAINING_CODE_ADDED=NO
LABELS_CREATED=NO

FIX_IMPLEMENTATION_AUTHORIZED=NO
TEACHER_READMISSION_AUTHORIZED=NO
LABEL_MATERIALIZATION_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
C1_04_AUTHORIZED=NO
```

## Verification

The exact command results are recorded here after the repository gates complete.

```text
FOCUSED_TESTS=3 tests, PASS
FULL_ML_TESTS=154 tests, PASS
PYTHON_COMPILEALL=PASS
ML_TEST=PASS (154 tests)
ML_CHECK=PASS
P1=0
P2=0
P3=1 (non-causal factory-binding identity guard gap, deferred by authorization boundary)
```

## Recommended next slice

Keep C1_03's historical acceptance and rejected admission unchanged. If separately authorized, design and test a narrow ownership-contract fix that joins the selected ordinal back to the factory-issued exact binding and validates the accepted raw/public projection relation without comparing incompatible raw and alias JSON. Re-evaluate Teacher admission from fresh evidence only after that fix; do not materialize labels or train from this characterization.
