# C1_02 PublicObservationTeacherV1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement an immutable, public-observation-only Teacher for complete flat legal domains that returns an exact source binding or typed `NO_LABEL`, while preserving the C1_00 Selection V2 and PolicyTieRng V1 contracts.

**Architecture:** Keep the policy channel (`VariableDomainItem.model_input` and candidate feature views) separate from the binding channel (`ExactSemanticSourceBinding`, source ordinal, and source-validated semantic discriminator). A pure generic kind-weight scorer produces one finite score for every real candidate; a thin Teacher boundary validates completeness, joins scores to bindings, and delegates selection to existing Selection V2. Structured domains remain explicit `NO_LABEL` because C1_00 structured inference is non-total.

**Tech Stack:** Python 3.13 standard library; existing `argentum_ml.contracts.canonical_json`, `VariableDomainItem`, `ExactSemanticSourceBinding`, `SemanticTieDiscriminator`, `select_v2`, and `PolicyTieRngStateV1`; `unittest`.

---

## Fixed source and verification boundary

- Base: `4af111cad62d0f268f4d28e2548fbb23a18a9a47`.
- Branch: `chris/c1-02-public-observation-bootstrap-teacher-v1-20260913`.
- Worktree: `C:\Users\chris\.config\superpowers\worktrees\argentum-engine\c1-02-public-observation-bootstrap-teacher-v1-20260913`.
- The root checkout's unrelated `rules-engine/.../StackResolver.kt` modification stays untouched.
- The existing baseline has 76 Python tests, with 75 passing and one pre-existing
  `test_accepts_kotlin_materialized_cross_language_golden` `CR/BOM` error. That fixture failure is
  recorded separately and is not repaired in this slice.
- No Kotlin/Gradle, Gym, Rules, A9, TEST, training, corpus, materializer, or quality/admission work
  is in scope.

## File map

- Create `ml/src/argentum_ml/teacher/__init__.py`: public package exports only.
- Create `ml/src/argentum_ml/teacher/contracts.py`: frozen identities, strict config, result union,
  no-label reasons, diagnostics, and runtime provenance identity.
- Create `ml/src/argentum_ml/teacher/request.py`: immutable binding metadata and the request boundary
  that couples a C1_00 `VariableDomainItem` to exact source bindings without exposing them to scoring.
- Create `ml/src/argentum_ml/teacher/scoring.py`: pure generic public candidate scorer and scorer
  protocol; it accepts only the public model input and feature views.
- Create `ml/src/argentum_ml/teacher/public_observation_teacher.py`: validation, score-map
  production, Selection V2 adaptation, PolicyTieRng handling, and `SELECTED`/`NO_LABEL` results.
- Create `ml/tests/test_public_observation_teacher.py`: synthetic public fixtures and focused RED,
  GREEN, and regression tests; no source dataset or engine state.
- Create `docs/ml/c1-02-public-observation-bootstrap-teacher-v1.md`: implementation identity,
  supported/no-label families, invariants, and exact verification record.

## Task 1: Write the focused RED contract tests

**Files:**

- Create: `ml/tests/test_public_observation_teacher.py`

- [ ] **Step 1: Add canonical synthetic fixtures and the wished-for API.**

  Define helpers that construct:

  ```python
  CandidateFeature(feature_view={"kind": kind, "targetAliases": aliases},
                  source_binding_ordinal=ordinal,
                  present=True,
                  executable_support=executable)
  VariableDomainItem(model_input={
      "decisionContext": {"domainKind": "ACTION_CANDIDATES"},
      "observation": {"turnNumber": 1},
      "domain": {"kind": "ACTION_CANDIDATES", "candidates": feature_views},
  }, candidates=candidates, structured_domain=None,
  target_binding_ordinal=target_ordinal)
  ```

  Build `TeacherSourceBindingV1` values with exact action/response bindings containing raw target
  identifiers only in the binding channel. Keep `source_binding_ordinal` attached to the semantic
  candidate when fixtures are permuted. Use the existing `SemanticTieDiscriminator.from_json` only
  for canonical semantic values such as `{"family":"same"}`; never derive it from aliases or raw IDs.

  The tests call these exact public names:

  ```python
  PublicObservationTeacherConfigV1.reference()
  PublicObservationTeacherV1(config, source_commit)
  PublicObservationTeacherRequestV1(item, bindings)
  teacher.score_vector(request)
  teacher.select(request, rng_state)
  ```

- [ ] **Step 2: Add one focused test for each required behavior.**

  Cover:

  ```text
  runtime-ID/opaque-alias rename leaves score_vector unchanged
  physical candidate permutation leaves semantic score map unchanged
  source-binding ordinal changes leave score values unchanged
  recorded exact target changes leave score values unchanged
  scorer receives no binding, target, provenance, row, index, or outcome channel
  one finite score exists for every real candidate, including unaffordable candidates
  complete-domain mismatch/truncation is rejected before scoring
  unique exact maximum selects its candidate and consumes zero RNG words
  unresolved exact symmetry uses PolicyTieRng V1 and advances only through Selection V2
  same input/config/RNG state reproduces score vector, semantic selection, and cursor
  non-finite injected scorer output returns NO_LABEL
  empty executable domain returns NO_LABEL without substituting pass/first legal
  unknown config version/fields are rejected
  structured known and unknown versions return NO_LABEL without scorer/RNG use
  alias lexical changes never create a preference
  exact source binding is returned unchanged for the selected ordinal
  ```

  Include a custom recording scorer with the exact protocol `score(model_input, candidate_features)`
  to prove that binding metadata is not available to the scorer. Compare score vectors before
  comparing selected ordinals; this keeps `SCORER_INVARIANCE` independent from
  `SELECTION_INVARIANCE`.

- [ ] **Step 3: Run the focused test file and prove the intended RED state.**

  Run from `ml`:

  ```powershell
  py -3.13 -m unittest tests.test_public_observation_teacher -v
  ```

  Expected: collection fails with `ModuleNotFoundError` for the not-yet-created `argentum_ml.teacher`
  package. If the test file has a syntax or fixture-construction error instead, fix only the test
  until the failure is the missing production contract.

- [ ] **Step 4: Commit only the RED tests.**

  ```powershell
  git add ml/tests/test_public_observation_teacher.py
  git commit -m "test: define public observation teacher red contract"
  ```

## Task 2: Implement immutable identities, configuration, result, and request contracts

**Files:**

- Create: `ml/src/argentum_ml/teacher/contracts.py`
- Create: `ml/src/argentum_ml/teacher/request.py`
- Create: `ml/src/argentum_ml/teacher/__init__.py`
- Test: `ml/tests/test_public_observation_teacher.py`

- [ ] **Step 1: Define the exact versioned identities and closed reason set.**

  Use:

  ```python
  PUBLIC_OBSERVATION_TEACHER_ID = "argentum-ml-public-observation-bootstrap-teacher@v1"
  PUBLIC_OBSERVATION_TEACHER_CONFIG_ID = "argentum-ml-public-observation-teacher-config@v1"
  PUBLIC_OBSERVATION_TEACHER_RESULT_ID = "argentum-ml-public-observation-teacher-result@v1"
  SUPPORTED_FLAT_DECISION_FAMILIES = ("ACTION_CANDIDATES", "FOLDED_DECISION_OPTIONS")
  ```

  Define typed exceptions for invalid configuration and invalid request contracts. Define
  `NoLabelReason` as a string enum containing the task's fail-closed classes, including
  `UNSUPPORTED_DECISION_FAMILY`, `UNSUPPORTED_DOMAIN_VERSION`, `INCOMPLETE_PUBLIC_DOMAIN`,
  `NO_EXECUTABLE_CANDIDATE`, `INVALID_CANDIDATE_BINDING`, `NON_FINITE_SCORE`,
  `SELECTION_CONTRACT_FAILURE`, `POLICY_RNG_FAILURE`, `STRUCTURED_DOMAIN_NOT_SCOREABLE`, and
  `TEACHER_INPUT_CONTRACT_VIOLATION`.

- [ ] **Step 2: Implement strict immutable `PublicObservationTeacherConfigV1`.**

  Store only scalar values and tuples. Its exact wire shape is:

  ```python
  {
      "version": 1,
      "schemaIdentity": PUBLIC_OBSERVATION_TEACHER_CONFIG_ID,
      "teacherPolicyIdentity": PUBLIC_OBSERVATION_TEACHER_ID,
      "selectionContractIdentity": SELECTION_V2_IDENTITY,
      "policyRngContractIdentity": POLICY_TIE_RNG_IDENTITY,
      "scorerIdentity": "argentum-ml-public-observation-generic-kind-scorer@v1",
      "scoringConfiguration": {
          "defaultScore": float,
          "kindScores": {"generic-kind": float},
      },
      "supportedDecisionFamilies": ["ACTION_CANDIDATES", "FOLDED_DECISION_OPTIONS"],
      "unsupportedDecisionPolicy": "NO_LABEL",
      "structuredDecisionPolicyIdentity": "argentum-ml-structured-no-label@v1",
  }
  ```

  `from_dict` must reject unknown/missing fields, future versions, wrong identities, duplicate or
  empty kind keys, boolean/non-finite numeric values, unsupported family changes, and any policy
  other than `NO_LABEL`. `reference()` returns the reviewed generic weights; every weight is in the
  serialized config and therefore in the digest. `digest` is `sha256_hex(canonical_bytes(to_dict()))`.

- [ ] **Step 3: Implement immutable runtime identity, diagnostics, and result union.**

  `PublicObservationTeacherIdentityV1` validates the exact contract identities and an explicit
  40-hex source commit. It exposes `teacher_configuration_identity_or_digest`, Selection V2 and
  PolicyTieRng identities, and `label_materializer_identity=None`.

  `TeacherDiagnosticsV1` stores only identity/config digest, domain family, candidate count,
  support classification, no-label reason, tie flag, and RNG words consumed. It never stores raw
  runtime IDs, hidden state, outcomes, or target provenance.

  `SelectedTeacherResultV1` stores the exact `ExactSemanticSourceBinding`, selected ordinal,
  resulting RNG state, and diagnostics. `NoLabelTeacherResultV1` stores a typed reason, the input
  RNG state when available, and diagnostics. Both are frozen dataclasses.

- [ ] **Step 4: Implement `TeacherSourceBindingV1` and
  `PublicObservationTeacherRequestV1`.**

  The binding record contains only:

  ```python
  source_binding_ordinal: int
  exact_source_binding: ExactSemanticSourceBinding
  deterministic_semantic_tie_discriminator: SemanticTieDiscriminator | None
  ```

  The request contains exactly one `VariableDomainItem` and a tuple of binding records. Validate
  that flat candidate ordinals and binding ordinals are bijective, binding audit ordinals match,
  exact source alternatives are injective, and no binding metadata is substituted for a feature
  view. Allow bindings in a different physical sequence so `VariableDomainItem.permute_candidates`
  can move candidate records without changing semantic identity. Structured requests must carry no
  flat candidates and are valid only as an explicit no-label input.

- [ ] **Step 5: Run the contract-focused tests.**

  ```powershell
  py -3.13 -m unittest tests.test_public_observation_teacher.PublicObservationTeacherContractTests -v
  ```

  Expected: configuration, identity, immutable result, complete-domain, and binding validation
  tests pass; scorer/selection tests still fail because the scorer and Teacher implementation do
  not exist.

- [ ] **Step 6: Commit the contract layer.**

  ```powershell
  git add ml/src/argentum_ml/teacher
  git commit -m "feat: add public observation teacher contracts"
  ```

## Task 3: Implement the policy-only scorer and score-map evidence

**Files:**

- Create: `ml/src/argentum_ml/teacher/scoring.py`
- Modify: `ml/src/argentum_ml/teacher/__init__.py`
- Test: `ml/tests/test_public_observation_teacher.py`

- [ ] **Step 1: Define the scorer protocol with no binding channel.**

  The only scoring method is:

  ```python
  score(
      model_input: Mapping[str, Any],
      candidate_features: Sequence[Mapping[str, Any]],
  ) -> Sequence[Real]
  ```

  Do not add request, source binding, target, ordinal, row, candidate index, RNG, or outcome
  parameters. Freeze/copy inputs before storing them in test instrumentation.

- [ ] **Step 2: Implement the generic kind scorer.**

  Validate that `model_input.domain.candidates` exists and has exactly the same length and canonical
  feature content as `candidate_features`. Read only each candidate's string `kind`, look up the
  configured weight, and use the configured default for an unknown generic kind. Return one score
  per candidate, including candidates whose `executable_support` mask is false. Do not read alias
  strings, alias order, raw IDs, target arrays, physical position, or any other binding field.

- [ ] **Step 3: Add score-vector and score-map assertions.**

  Add tests showing:

  ```text
  same feature semantics + runtime-ID/alias rename -> same score vector
  physical permutation -> scores move with semantic candidates, not rows
  changed source ordinal -> same score for the same feature view
  changed recorded exact target -> same score vector
  unaffordable candidate -> still receives one finite score
  feature count mismatch -> TeacherInputError before scorer completion
  ```

  For the permutation assertion, compare `(candidate semantic fixture key, score)` pairs rather
  than the physical tuple alone. This proves the scorer invariant before Selection V2 is called.

- [ ] **Step 4: Run the scorer tests and inspect the failure reason.**

  ```powershell
  py -3.13 -m unittest tests.test_public_observation_teacher.PublicObservationTeacherScoringTests -v
  ```

  Expected: all scorer tests pass, while Teacher-selection tests remain red for the missing selector.

- [ ] **Step 5: Commit the scorer.**

  ```powershell
  git add ml/src/argentum_ml/teacher/scoring.py ml/src/argentum_ml/teacher/__init__.py ml/tests/test_public_observation_teacher.py
  git commit -m "feat: score public teacher candidates without bindings"
  ```

## Task 4: Implement PublicObservationTeacherV1 with Selection V2

**Files:**

- Create: `ml/src/argentum_ml/teacher/public_observation_teacher.py`
- Modify: `ml/src/argentum_ml/teacher/__init__.py`
- Test: `ml/tests/test_public_observation_teacher.py`

- [ ] **Step 1: Add the policy-only score-vector boundary.**

  `PublicObservationTeacherV1.score_vector(request)` passes only
  `request.item.model_input` and the tuple of `CandidateFeature.feature_view` values to the scorer.
  It verifies an exact finite score count and returns a tuple aligned to the current physical
  candidate records. It never passes request bindings to the scorer.

- [ ] **Step 2: Add explicit flat/structured domain classification.**

  `select(request, rng_state)` must classify before scoring:

  ```text
  ACTION_CANDIDATES/FOLDED_DECISION_OPTIONS -> score every candidate
  STRUCTURED_DECISION                    -> NO_LABEL(STRUCTURED_DOMAIN_NOT_SCOREABLE)
  unknown domain kind                     -> NO_LABEL(UNSUPPORTED_DECISION_FAMILY)
  malformed/unknown structured version    -> NO_LABEL(UNSUPPORTED_DOMAIN_VERSION)
  ```

  Structured no-label paths call neither scorer nor RNG and preserve the incoming cursor. A flat
  domain with no executable present candidate returns `NO_LABEL(NO_EXECUTABLE_CANDIDATE)` and never
  substitutes pass, first legal, AutoPay, or a random source action.

- [ ] **Step 3: Join scores to binding-only SelectionCandidate values.**

  For each physical candidate, find its binding by source ordinal and construct the existing
  `SelectionCandidate` with the exact source binding, score, presence/executable masks, and
  source-validated semantic discriminator. Do not derive a discriminator from aliases, ordinals,
  row positions, runtime IDs, or target values.

- [ ] **Step 4: Delegate and normalize Selection V2 results.**

  Call `select_v2(selection_candidates, rng_state)` without sorting candidates in the Teacher.
  Convert `SelectionError` to `NO_LABEL(SELECTION_CONTRACT_FAILURE)` and `PolicyTieRngError` to
  `NO_LABEL(POLICY_RNG_FAILURE)`. Preserve Selection V2's exact behavior: unique maximum and valid
  semantic-discriminator ties consume zero words; only unresolved exact symmetry invokes the
  existing unbiased sampler. A successful result returns the exact selected source binding and
  its ordinal.

- [ ] **Step 5: Verify the selection and leakage tests.**

  ```powershell
  py -3.13 -m unittest tests.test_public_observation_teacher.PublicObservationTeacherSelectionTests -v
  py -3.13 -m unittest tests.test_public_observation_teacher.PublicObservationTeacherLeakageTests -v
  ```

  Expected: unique maxima have `cursor_after == cursor_before`; unresolved ties reproduce the same
  selected semantic ordinal and cursor from the same seed/seat/cursor; permutations and alias/ID
  renames preserve score-vector and semantic-selection invariants; source target mutations do not
  change scores; structured and malformed inputs produce typed `NO_LABEL` results.

- [ ] **Step 6: Commit the Teacher integration.**

  ```powershell
  git add ml/src/argentum_ml/teacher ml/tests/test_public_observation_teacher.py
  git commit -m "feat: implement public observation teacher selection"
  ```

## Task 5: Add C1_02 implementation evidence and run package gates

**Files:**

- Create: `docs/ml/c1-02-public-observation-bootstrap-teacher-v1.md`
- Modify: `ml/README.md`

- [ ] **Step 1: Document exact identities and support classification.**

  Record the actual config digest generated by the implementation, source commit identity, exact
  supported flat families, all structured families classified `NO_LABEL`, alias policy, no-label
  reasons, and the explicit statement that `BOOTSTRAP_LABEL_MATERIALIZER_IMPLEMENTED=NO`.

- [ ] **Step 2: Record the RED and focused evidence.**

  Include the initial missing-package RED result, the final focused test count, score-map versus
  selection invariant results, unique-max/tie RNG cursor evidence, and the pre-existing golden
  fixture baseline failure without relabeling it as a new failure.

- [ ] **Step 3: Run the focused suite and compile check.**

  ```powershell
  cd ml
  py -3.13 -m pip install --no-deps .
  py -3.13 -m unittest tests.test_public_observation_teacher -v
  py -3.13 -m compileall -q src tests
  ```

  Expected: every focused Teacher test passes and `compileall` is silent. The focused run must not
  access source datasets, invoke Kotlin, train, or use TEST data.

- [ ] **Step 4: Run the full Python C1 suite and record the baseline distinction.**

  ```powershell
  py -3.13 -m unittest discover -s tests -v
  ```

  Expected: all existing tests pass except the already observed cross-language golden fixture
  `CR/BOM` error, unless that unrelated environment state changes. Do not call the suite fully
  green while that error remains.

- [ ] **Step 5: Run repository wrappers separately if useful.**

  ```powershell
  cd ..
  just ml-test
  just ml-check
  ```

  Record wrapper results separately. If either wrapper fails with `WinError 193` before Gradle,
  record `JUST_WRAPPER=BLOCKED`; do not convert the failure into a PASS. Native Python commands
  remain the authoritative local evidence for this Python-only scope.

- [ ] **Step 6: Commit evidence documentation.**

  ```powershell
  git add docs/ml/c1-02-public-observation-bootstrap-teacher-v1.md ml/README.md
  git commit -m "docs: record c1-02 teacher conformance"
  ```

## Task 6: Final scope audit, self-review, and exact-branch handoff

**Files:**

- No new files; inspect the complete branch diff.

- [ ] **Step 1: Search the final diff for forbidden behavior.**

  ```powershell
  rg -n -i "EntityId|sourceEntityId|targetEntityId|rowIndex|candidateIndex|first\(|sort\(|random|Random|GameRng|AutoPay|PyTorch|numpy|torch|training|top.?k|truncate|materializer" ml/src/argentum_ml/teacher ml/tests/test_public_observation_teacher.py
  ```

  Review each match. Generic test fixture names and existing imported Selection V2 sorting are
  acceptable only when they cannot affect scorer preference; no Teacher scorer may use a forbidden
  value.

- [ ] **Step 2: Run the final verification commands.**

  ```powershell
  git diff --check
  git status --short
  git diff --name-only 4af111cad62d0f268f4d28e2548fbb23a18a9a47...HEAD
  git log --oneline --decorate 4af111cad62d0f268f4d28e2548fbb23a18a9a47..HEAD
  ```

  Confirm that changed paths are limited to `ml/`, `docs/ml/`, and the approved
  `docs/superpowers/` design/plan files. Confirm there are no changes to `rules-engine`, `gym`
  production code, `game-server`, `mtg-sdk`, `mtg-sets`, `TrajectoryV1`, C0 contracts, C1_00
  contracts, locked decks, or A9.

- [ ] **Step 3: Perform the self-review checklist.**

  Confirm explicitly:

  ```text
  TEACHER_RUNTIME_ID_RENAMING_SAFE=YES
  TEACHER_CANDIDATE_PERMUTATION_SAFE=YES
  TEACHER_SOURCE_BINDING_ORDINAL_PREFERENCE=NO
  TEACHER_ALIAS_VALUE_PREFERENCE=NO
  UNIQUE_MAX_RNG_WORDS=0
  NON_FINITE_SCORE_FAIL_CLOSED=YES
  TEACHER_NO_LABEL_IMPLEMENTED=YES
  TEACHER_CONFIGURATION_IDENTITY_COMPLETE=YES
  TEACHER_RUNTIME_IDENTITY_COMPLETE=YES
  BOOTSTRAP_LABEL_MATERIALIZER_IMPLEMENTED=NO
  TRAJECTORY_V1_MUTATED=NO
  C0_CONTRACT_CHANGED=NO
  C1_00_CONTRACT_CHANGED=NO
  TEACHER_QUALITY_CHARACTERIZED=NO
  FIRST_C1_TEACHER_SELECTION=NONE
  TEACHER_BOOTSTRAP_ADMITTED=NO
  ```

  Leave `PUBLIC_OBSERVATION_TEACHER_V1_CODE_REVIEW_PASS=NO`,
  `C1_02_FINAL_ACCEPTANCE_PASS=NO`, and `STOP_FOR_EXACT_SHA_REVIEW=YES` in the report.

- [ ] **Step 4: Create the final standalone implementation commit if any audit-only edits remain.**

  ```powershell
  git status --short
  git add ml docs/ml docs/superpowers
  git commit -m "feat: add public observation bootstrap teacher v1"
  ```

  If the working tree is already clean after the evidence commit, do not create an empty commit.

- [ ] **Step 5: Push the exact branch and stop.**

  ```powershell
  git push --set-upstream origin chris/c1-02-public-observation-bootstrap-teacher-v1-20260913
  git rev-parse HEAD
  git ls-remote origin refs/heads/chris/c1-02-public-observation-bootstrap-teacher-v1-20260913
  ```

  Verify the pushed remote SHA equals local `HEAD`. Do not create a PR, merge, start C1_03, run
  training, materialize labels, or characterize teacher quality. Report the exact final template
  required by the C1_02 task, with blocked/skipped gates kept distinct from PASS.
