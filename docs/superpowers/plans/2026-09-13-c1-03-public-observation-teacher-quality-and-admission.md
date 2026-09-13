# C1_03 PublicObservationTeacher Quality and Admission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Each step is tracked with a checkbox.

**Goal:** Implement a bounded, authority-preserving characterization harness for the accepted PublicObservationTeacherV1, regenerate only the disposable C1_00 derived view from the exact accepted DatasetManifestV1, produce the required evidence, and make one dataset-bound admission decision without changing production policy or creating labels.

**Architecture:** A test-only Kotlin runner calls the existing C1LearnerArtifactMaterializer over the exact accepted source dataset and writes to an explicitly supplied disposable directory. A dependency-free Python characterization package opens that derived artifact only through DerivedArtifactReader, constructs reader-issued inference requests, invokes the frozen Teacher, and writes a small summary plus Markdown report. Gameplay is attempted only through an existing public execution seam; if that seam is absent, the report records BLOCKED without adding a bridge.

**Tech Stack:** Kotlin/JDK 21, Kotest, existing TrajectoryV1Reader and C1LearnerArtifactMaterializer, Python 3.13 standard library, existing DerivedArtifactReader, PublicObservationTeacherV1, Selection V2, PolicyTieRng V1, canonical JSON/SHA-256 identities, Markdown and JSON evidence.

---

## Frozen decisions before implementation

The following values are written into the implementation and report before any
quality result is inspected. Changing any one after the first result creates a
new characterization identity and requires a new reviewed task.

~~~
C1_03_CHARACTERIZATION_PLAN_IDENTITY=
  argentum-ml-c1-03-teacher-quality-and-admission@v1

TEACHER_ADMISSION_PURPOSE_IDENTITY=
  argentum-ml-flat-reference-bootstrap@v1
TEACHER_ADMISSION_SCOPE=FLAT_REFERENCE_BOOTSTRAP

SOURCE_DATASET_ID=
  69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03
SOURCE_MANIFEST_CONTENT_DIGEST=
  de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2
SOURCE_EPISODES=64
SOURCE_DECISIONS=125471

BASE_SHA=
  b9eb8da182390095d73b9c06d9bbe20049156e9b
ACCEPTED_C1_02_PR_HEAD=
  8cad4845dc59192dde86849c8ba4ceacc1bb6331
TEACHER_SOURCE_COMMIT=
  8cad4845dc59192dde86849c8ba4ceacc1bb6331
TEACHER_POLICY_IDENTITY=
  argentum-ml-public-observation-bootstrap-teacher@v1
TEACHER_CONTRACT_IDENTITY=argentum-ml-teacher-bootstrap@v1
TEACHER_CONFIG_DIGEST=
  fa358597c09ce466e1be1823de485e0accd84be622184fa1d6505806aff0d7d8
SELECTION_CONTRACT_IDENTITY=argentum-ml-policy-selection@v2
POLICY_RNG_IDENTITY=argentum-ml-policy-tie-rng@v1

DERIVED_VIEW_SCHEMA_IDENTITY=argentum-ml-derived-learner-view@v1
MATERIALIZER_IMPLEMENTATION_IDENTITY=c1-materializer@v1
MATERIALIZER_SOURCE_COMMIT=
  b9eb8da182390095d73b9c06d9bbe20049156e9b
MATERIALIZER_CONFIG_DIGEST=
  7d5fbfd0bfe71844fefbd25d3fcce7beac3de8de281d9a2f02cf225aef27364c

TEACHER_POLICY_TIE_SCHEDULE_IDENTITY=
  argentum-ml-c1-03-teacher-policy-tie-schedule@v1
SOURCE_POLICY_RNG_IDENTITY=explicit-seed/kotlin-policy-state-v1
C1_03_TEACHER_POLICY_TIE_SEED=0
C1_03_INITIAL_POLICY_TIE_CURSOR=0
LEGACY_A9_POLICY_SEED_REUSED=NO

ALLOWED_OFFLINE_PARTITIONS=[TRAIN,VALIDATION]
TEST_ROWS_SUBMITTED_TO_TEACHER=0
FIRST_DIVERGENCE_LIMIT=32
FIRST_DIVERGENCE_PER_EPISODE=1

GAMEPLAY_EVALUATION_CONTRACT_ID=argentum-ml-gameplay-evaluation@v1
POLICY_A_IDENTITY=C1_03_EVAL_COMPOSITE_V1
POLICY_B_IDENTITY=b2-a9-deterministic-external-policy@v1
FIXED_OPPONENT_IDENTITY=b2-a9-deterministic-external-policy@v1
STRUCTURED_COMPLETION_POLICY_IDENTITY=
  argentum-ml-c1-03-a9-structured-completion@v1
PAIRING_KEYS=16
PAIRING_KEYS_PER_CELL=4
POLICY_A_EXECUTIONS=16
POLICY_B_EXECUTIONS=16
TOTAL_GAME_EXECUTIONS=32
~~~

The admission rule is operational and purpose-specific. It has no fabricated
strategic-strength threshold because the C0 authorities do not define one for
an initial reference baseline. The implementation must admit only if every
hard trust/ownership condition passes, both supported families have eligible
evidence in TRAIN and VALIDATION, every C1_00 exact-bindable supported-flat row
receives a label, and the Action-family selected candidate has no structured
payload. C1_00-unbindable rows remain in the useful-coverage denominator.
Behavior agreement, tie coarseness, and gameplay remain diagnostic unless they
reveal an already-defined hard failure. Missing evidence produces DEFERRED;
unexpected C1_00 authority failure produces BLOCKED; other observed Teacher
trust or ownership failure produces REJECTED.

The source A9 policySeed and legacy policyRngIdentity are provenance-only.
Offline Teacher tie selection uses the frozen exogenous seed 0 with PolicyTieRng
V1. One state is created at cursor zero per semanticEpisodeId, Teacher policy
identity, and roster seat, then carried across that instance's decisions. The
state is never recreated per decision. Unique maxima and semantic-discriminator
ties consume zero words; unresolved exact ties consume exactly the words
returned by Selection V2.

## File map

Create the following focused files:

- ml/src/argentum_ml/characterization/__init__.py — package boundary for C1_03 analysis helpers.
- ml/src/argentum_ml/characterization/c1_03.py — frozen plan, validated-sample adapter, streaming metrics, privacy-safe divergence capture, admission decision, JSON summary, Markdown renderer, and CLI.
- ml/tests/test_c1_03_characterization.py — small-fixture RED/GREEN coverage for every analysis boundary; no large data and no TEST label inspection.
- gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1_03DerivedViewMaterializationTest.kt — opt-in manual runner around the existing C1_00 materializer; disabled when explicit paths are absent so ordinary CI never touches the multi-gigabyte source.

Create these evidence files only after the frozen plan and characterization
run complete:

- docs/ml/c1-03-public-observation-teacher-quality-and-admission.md — human-readable report with exact identities, raw/stratified counts, diagnostics, gameplay status, admission result, and final status fields.
- docs/ml/c1-03-public-observation-teacher-quality-and-admission.json — small machine-readable summary containing no local paths, shards, labels, raw GameState, private information, or large domains.

Do not modify PublicObservationTeacherV1, its config/scorer, Selection V2,
PolicyTieRng V1, C1_00 contracts, TrajectoryV1, Rules, Gym semantics, locked
decks, or replay code.

## Task 1: Add the C1_03 characterization package boundary and frozen plan

**Files:**

- Create: ml/src/argentum_ml/characterization/__init__.py
- Create: ml/src/argentum_ml/characterization/c1_03.py
- Test: ml/tests/test_c1_03_characterization.py

- [ ] **Step 1: Write RED tests for the frozen plan contract.**

Add tests that import the future module and assert the exact public constants
and immutable plan shape. Every test in this file is a method of the
discoverable `C1_03CharacterizationTests(unittest.TestCase)` class; no
top-level pytest-style test function is used:

~~~
import dataclasses
import unittest

from argentum_ml.characterization.c1_03 import (
    ALLOWED_OFFLINE_PARTITIONS,
    C1_03_CHARACTERIZATION_PLAN_IDENTITY,
    C1_03PlanV1,
    FIRST_DIVERGENCE_LIMIT,
    SOURCE_DATASET_ID,
    TEACHER_ADMISSION_PURPOSE_IDENTITY,
)


class C1_03CharacterizationTests(unittest.TestCase):
    def test_plan_is_exactly_dataset_bound_and_uses_no_test_partition(self):
        plan = C1_03PlanV1.reference()
        self.assertEqual(plan.admission_purpose_identity, TEACHER_ADMISSION_PURPOSE_IDENTITY)
        self.assertEqual(plan.source_dataset_id, SOURCE_DATASET_ID)
        self.assertEqual(plan.allowed_partitions, ("TRAIN", "VALIDATION"))
        self.assertEqual(plan.test_rows_submitted_to_teacher, 0)
        self.assertEqual(plan.first_divergence_limit, FIRST_DIVERGENCE_LIMIT)
        self.assertEqual(plan.teacher_policy_tie_seed, 0)
        self.assertEqual(plan.initial_policy_tie_cursor, 0)
        self.assertFalse(plan.legacy_a9_policy_seed_reused)
        self.assertEqual(plan.first_divergence_per_episode, 1)
        self.assertEqual(plan.policy_a_executions, 16)
        self.assertEqual(plan.policy_b_executions, 16)
        self.assertEqual(plan.plan_identity, C1_03_CHARACTERIZATION_PLAN_IDENTITY)


    def test_plan_serialization_is_canonical_and_immutable(self):
        plan = C1_03PlanV1.reference()
        exported = plan.to_dict()
        self.assertEqual(plan.digest, C1_03PlanV1.from_dict(exported).digest)
        with self.assertRaises(dataclasses.FrozenInstanceError):
            plan.source_dataset_id = "different"
~~~

The test must also assert the exact admission rule: behavior agreement and
gameplay have no strategic threshold, while hard trust/ownership counters are
zero requirements.

- [ ] **Step 2: Run the focused test and confirm RED.**

Run from the repository root:

~~~
cd ml
py -3.13 -m unittest discover -s tests -p 'test_c1_03_characterization.py' -v
~~~

Expected result: collection fails with ModuleNotFoundError because the new
characterization package does not yet exist. Do not create a fallback module
just to make collection pass.

After implementation the same command must report `Ran N tests` with
`N > 0`; a zero-test green exit is a verification failure.

- [ ] **Step 3: Implement the immutable plan and identity constants.**

Create __init__.py with only the package docstring and implement the exact
module-level constants in c1_03.py. The plan must have this public shape:

~~~
@dataclass(frozen=True)
class C1_03PlanV1:
    plan_identity: str
    admission_purpose_identity: str
    source_dataset_id: str
    source_manifest_content_digest: str
    split_contract_identity: str
    teacher_policy_identity: str
    teacher_contract_identity: str
    teacher_source_commit: str
    teacher_config_digest: str
    selection_contract_identity: str
    policy_rng_identity: str
    derived_view_schema_identity: str
    materializer_implementation_identity: str
    materializer_source_commit: str
    materializer_config_digest: str
    teacher_policy_tie_schedule_identity: str
    teacher_policy_tie_seed: int
    initial_policy_tie_cursor: int
    legacy_a9_policy_seed_reused: bool
    allowed_partitions: tuple[str, ...]
    test_rows_submitted_to_teacher: int
    first_divergence_limit: int
    first_divergence_per_episode: int
    gameplay_evaluation_contract_id: str
    policy_a_identity: str
    policy_b_identity: str
    fixed_opponent_identity: str
    structured_completion_policy_identity: str
    pairing_keys: int
    pairing_keys_per_cell: int
    policy_a_executions: int
    policy_b_executions: int
    total_game_executions: int

    @classmethod
    def reference(cls) -> "C1_03PlanV1": ...

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "C1_03PlanV1": ...

    def to_dict(self) -> dict[str, Any]: ...

    @property
    def digest(self) -> str: ...
~~~

reference() must return the exact constants above. from_dict() must require
exact keys and exact identity values. Use the existing canonical_bytes and
sha256_hex helpers. The plan digest is a characterization identity only; it is
not a Teacher configuration digest.

- [ ] **Step 4: Run the focused tests and confirm GREEN.**

Run the same focused command. Expected result: all plan-contract tests pass.

- [ ] **Step 5: Commit the frozen analysis contract.**

Verify only the new package and focused test are changed, then commit:

~~~
git diff --check
git status --short
git add ml/src/argentum_ml/characterization ml/tests/test_c1_03_characterization.py
git commit -m "test: freeze C1-03 characterization identity"
~~~

## Task 2: Implement C1_00 exact-bindable flat classification and request construction

**Files:**

- Modify: ml/src/argentum_ml/characterization/c1_03.py
- Modify: ml/tests/test_c1_03_characterization.py

- [ ] **Step 1: Add RED fixture tests for every eligibility class.**

Use the existing small derived-artifact fixture builders from
ml/tests/test_derived_reader.py and ml/tests/test_public_observation_teacher.py.
Do not read the accepted 6.13-GB source in unit tests. Cover these exact cases:

~~~
C1_03CharacterizationTests.test_action_candidate_with_any_required_payload_is_c1_00_unbindable
C1_03CharacterizationTests.test_action_domain_with_empty_required_payloads_is_exact_bindable
C1_03CharacterizationTests.test_folded_option_domain_is_exact_bindable_as_complete_response
C1_03CharacterizationTests.test_structured_domain_is_not_a_flat_teacher_row
C1_03CharacterizationTests.test_unbindable_row_is_not_counted_as_teacher_no_label
C1_03CharacterizationTests.test_unexpected_action_binding_error_is_authority_failure
C1_03CharacterizationTests.test_folded_binding_error_is_authority_failure_not_unbindable
~~~

The action test must set a nonempty requiredPayloadFields on one candidate and
assert C1_00_UNBINDABLE, not a Teacher failure. The folded test must retain
each concrete response in actionSemantics and assert it is bindable.

- [ ] **Step 2: Run the focused tests and confirm RED.**

Run:

~~~
cd ml
py -3.13 -m unittest discover -s tests -p 'test_c1_03_characterization.py' -v
~~~

Expected result: the eligibility helpers are missing.

- [ ] **Step 3: Implement the authority-preserving adapter.**

Implement these immutable result types and functions:

~~~
class FlatEligibility(str, Enum):
    EXACT_BINDABLE = "EXACT_BINDABLE"
    EXPECTED_C1_00_UNBINDABLE = "EXPECTED_C1_00_UNBINDABLE"


class C1_00AuthorityFailure(ValueError):
    """Unexpected C1_00 transport or source-binding failure; blocks the run."""


@dataclass(frozen=True)
class SampleViewV1:
    partition: str
    decision_family: str
    structured_family: str | None
    semantic_episode_id: str
    decision_index: int
    validated_sample: ValidatedDerivedSample
    eligibility: FlatEligibility | None


def sample_view(
    validated: ValidatedDerivedSample,
    *,
    plan: C1_03PlanV1,
) -> SampleViewV1: ...


def teacher_request(
    validated: ValidatedDerivedSample,
) -> PublicObservationTeacherRequestV1: ...
~~~

sample_view() may inspect the partition and public input.domain shape. It must
not inspect target, provenance, or binding values for a TEST sample. The only
expected C1_00 unbindable class is an ACTION_CANDIDATES domain with one or more
source candidates whose requiredPayloadFields list is nonempty. That condition
is classified before request construction as EXPECTED_C1_00_UNBINDABLE. It is
not a Teacher NO_LABEL and not a Teacher failure.

For every other allowed flat sample, use the actual
InferenceRequest.from_validated_sample path as the final authority. A binding
mismatch, affordable-mask mismatch, invalid source ordinal, projection
mismatch, malformed required field, or malformed folded response is classified
as C1_00_AUTHORITY_FAILURE, increments TRUST_FAILURE_COUNT, and blocks the
characterization. A FOLDED_DECISION_OPTIONS factory error is never converted to
ordinary unbindable coverage.

Construct the request only through this chain:

~~~
reader-issued ValidatedDerivedSample
    -> CandidateFeature / VariableDomainItem
    -> InferenceRequest.from_validated_sample(validated, item)
    -> PublicObservationTeacherRequestV1.from_inference_request(request)
~~~

For flat rows, set CandidateFeature.executable_support from the
source-authoritative affordable flag and preserve every model-facing candidate
in order. Derive target_binding_ordinal by matching the selected source binding
to the source domain; do not use a first/sorted candidate. For structured rows,
construct the empty-candidate VariableDomainItem with its validated structuredType
and do not call the Teacher scorer.

teacher_request() must raise the existing C1_00 error for an expected
unbindable sample and the caller must distinguish that condition from every
other InferenceError. Only EXPECTED_C1_00_UNBINDABLE increments
C1_00_UNBINDABLE_FLAT_ROWS. C1_00_AUTHORITY_FAILURE is a trust failure and
blocks; it is never hidden as a coverage gap.

- [ ] **Step 4: Add a TEST-exclusion spy test.**

Build a fixture containing one TRAIN, one VALIDATION, and one TEST sample. Run
the future partition iterator with a recording Teacher and assert the Teacher
receives exactly two samples, while the TEST sample contributes no target,
agreement, selection, or outcome field to the accumulator:

The discoverable method is
`C1_03CharacterizationTests.test_test_sample_is_not_submitted_or_used_for_admission_metrics`.

The method body must be:

~~~
class C1_03CharacterizationTests(unittest.TestCase):
    def test_test_sample_is_not_submitted_or_used_for_admission_metrics(self):
        summary = run_offline(fixture, teacher=recording_teacher())
        self.assertEqual(summary.test_rows_submitted_to_teacher, 0)
        self.assertEqual(summary.teacher_invocations, 2)
        self.assertEqual(summary.test_quality_metrics_inspected, 0)
~~~

- [ ] **Step 5: Run the focused tests and confirm GREEN.**

Run the focused unittest command again. All eligibility and TEST-protection
tests must pass.

## Task 3: Implement score/tie diagnostics and raw stratified counters

**Files:**

- Modify: ml/src/argentum_ml/characterization/c1_03.py
- Modify: ml/tests/test_c1_03_characterization.py

- [ ] **Step 1: Write RED tests for count semantics and tie classes.**

Add fixture tests for:

~~~
C1_03CharacterizationTests.test_flat_and_overall_yield_use_different_explicit_denominators
C1_03CharacterizationTests.test_structured_no_label_is_expected_and_separate_from_flat_failure
C1_03CharacterizationTests.test_unique_maximum_consumes_zero_rng_words
C1_03CharacterizationTests.test_semantic_discriminator_tie_consumes_zero_rng_words
C1_03CharacterizationTests.test_unresolved_exact_tie_reports_policy_rng_words
C1_03CharacterizationTests.test_same_kind_cross_kind_and_large_ties_are_distinct
C1_03CharacterizationTests.test_candidate_count_and_executable_count_buckets_are_raw_counts
C1_03CharacterizationTests.test_offline_teacher_uses_exogenous_zero_seed_not_source_a9_seed
C1_03CharacterizationTests.test_policy_tie_rng_state_is_carried_per_episode_and_seat
C1_03CharacterizationTests.test_unique_and_semantic_ties_leave_cursor_unchanged
C1_03CharacterizationTests.test_unresolved_tie_advances_carried_cursor_exactly
~~~

Use the existing Teacher reference config and PolicyTieRngStateV1 test helpers.
The focused test count must remain positive and is recorded as
`FOCUSED_TEST_COUNT` in the final evidence.
Assert that the same fixture result contains both counts and rates, not a rate
rounded without its numerator/denominator.

- [ ] **Step 2: Run the focused tests and confirm RED.**

Run the focused unittest command. Expected result: the accumulator and tie
classification are not implemented.

- [ ] **Step 3: Implement deterministic metrics types and bucket functions.**

Implement the following public surface:

~~~
STRUCTURED_FAMILIES: tuple[str, ...] = (
    "targets@v2",
    "card-selection@v1",
    "mode-selection@v1",
    "distribution@v1",
    "ordering@v1",
    "split-piles@v1",
    "search-library@v1",
    "reorder-library@v1",
    "combat-resolution@v1",
    "mana-sources@v3",
    "replacement@v1",
    "budget-modal@v1",
)


def candidate_count_bucket(count: int) -> str: ...
def executable_count_bucket(count: int) -> str: ...
def turn_bucket(turn_number: int) -> str: ...


@dataclass
class C1_03AccumulatorV1:
    def observe(self, sample: SampleViewV1, teacher: PublicObservationTeacherV1) -> None: ...
    def finalize(self, plan: C1_03PlanV1) -> "C1_03OfflineSummaryV1": ...


@dataclass(frozen=True)
class C1_03OfflineSummaryV1:
    plan: C1_03PlanV1
    raw_counts: Mapping[str, int]
    rates: Mapping[str, float | None]
    stratified_counts: Mapping[str, Mapping[str, int]]
    structured_counts: Mapping[str, Mapping[str, int]]
    tie_counts: Mapping[str, int]
    failure_counts: Mapping[str, int]
    divergences: tuple[Mapping[str, Any], ...]
    admission_result: str

    def to_dict(self) -> dict[str, Any]: ...
~~~

Use the exact candidate buckets 1, 2, 3-5, 6-10, 11-20, 21+ and
turn buckets 1, 2-3, 4-6, 7-10, 11-20, 21+. Every table key must be emitted
even when its count is zero so rare families are visible.

Maintain a map of `PolicyTieRngStateV1` values keyed by
`semanticEpisodeId × TEACHER_POLICY_IDENTITY × roster seat`. On first use of
one key, create exactly one state with
`PolicyTieRngStateV1.from_policy_seed(C1_03_TEACHER_POLICY_TIE_SEED, seat_index,
policy_rng_identity=POLICY_RNG_IDENTITY)` and cursor zero. Pass the current
state to `teacher.select()` and replace the map value with the returned state.
Never pass the source A9 policySeed to this constructor, and never construct a
new state for every decision. A missing or inconsistent perspective-to-roster
seat mapping is `C1_00_AUTHORITY_FAILURE`/trust failure, not a guessed seat.

For each exact-bindable flat sample:

1. Call teacher.score_vector(request) once and retain the vector in memory only for the current row and a bounded divergence record.
2. Compute the executable maximum set from the source-authoritative executable mask.
3. Call teacher.select(request, rng_state) with the exogenous C1_03 Teacher seed `C1_03_TEACHER_POLICY_TIE_SEED` and the acting perspective's roster seat index; carry the returned state for later decisions in the same policy instance.
4. Classify the row as unique maximum, semantic-discriminator tie, or unresolved PolicyTieRng tie using maximum-set size and returned diagnostic word count.
5. Count selected kind, family, phase, turn bucket, perspective/deck role, PassPriority/non-PassPriority, candidate and executable counts, and ownership fields.

Never score only affordable candidates: every real candidate must receive one
score, while only executable candidates may win. Never sort candidates for a
policy decision.

flat_label_yield is selected exact-bindable flat rows divided by exact-bindable
flat rows. overall_useful_label_yield is selected rows divided by all permitted
TRAIN+VALIDATION policy-relevant rows, including unbindable flat rows and
structured rows. Store both rates with raw counts.

- [ ] **Step 4: Implement structured-family accounting.**

For a STRUCTURED_DECISION row, read only the public model-facing structuredType
name/version after confirming the row is TRAIN or VALIDATION. Call the Teacher
and require NoLabelTeacherResultV1 with the expected structured reason. Count
the family as natural frequency and expected structured NO_LABEL; do not count
it as a Teacher failure or a root-flat choice. Track decision, episode,
semantic-group and all-policy-relevant fractions separately.

- [ ] **Step 5: Run the focused tests and confirm GREEN.**

Run the focused unittest command. All raw-count, denominator, structured, and
tie tests must pass.

- [ ] **Step 6: Commit the offline metrics implementation.**

Run git diff --check, confirm only the characterization package/test files
changed, then commit:

~~~
git add ml/src/argentum_ml/characterization ml/tests/test_c1_03_characterization.py
git commit -m "feat: add C1-03 offline teacher characterization"
~~~

## Task 4: Implement family-specific execution ownership and privacy-safe divergence

**Files:**

- Modify: ml/src/argentum_ml/characterization/c1_03.py
- Modify: ml/tests/test_c1_03_characterization.py

- [ ] **Step 1: Write RED tests for Action/Folded ownership.**

Add tests that assert:

~~~
C1_03CharacterizationTests.test_action_selection_requires_empty_payload_for_admission_ownership
C1_03CharacterizationTests.test_folded_selection_is_complete_response_owned_without_action_payload_gate
C1_03CharacterizationTests.test_unowned_action_payload_is_reported_but_not_admitted
C1_03CharacterizationTests.test_selected_action_candidate_payload_is_not_attributed_to_teacher
~~~

The Action test must exercise all four required conditions:

~~~
requiresStructuredAction == false
requiredPayloadFields == []
selected ExactSemanticSourceBinding.choicePayload == {}
selected source candidate remains executable
~~~

The Folded test must prove that its concrete actionSemantics response is
reported as the selected semantic response without applying the Action-only
payload rule.

- [ ] **Step 2: Run the focused tests and confirm RED.**

Run the focused unittest command and confirm the ownership predicates and
divergence serializer are absent.

- [ ] **Step 3: Implement ownership predicates and counters.**

Implement:

~~~
def action_selection_is_teacher_owned(
    request: PublicObservationTeacherRequestV1,
    result: SelectedTeacherResultV1,
) -> bool: ...


def folded_selection_is_teacher_owned(
    request: PublicObservationTeacherRequestV1,
    result: SelectedTeacherResultV1,
) -> bool: ...
~~~

The Action predicate reads the selected model-facing candidate by the returned
source ordinal and checks the exact required fields above. The Folded predicate
checks that the selected binding is an exact response member of the complete
source domain. Any failed predicate increments an ownership/trust counter and
prevents the row from satisfying the admission rule.

The harness must also count every candidate's:

~~~
requiresStructuredAction
requiredPayloadFields
target domains
payment domains
repeat-count domains
attack declaration domains
block declaration domains
~~~

Use structural presence/count fields in the report, never raw payload dumps.

- [ ] **Step 4: Implement public alias-preserving source/Teacher choices.**

Find the factual source ordinal from the exact source binding, then use the
already projected input.domain.candidates[ordinal] member for the public
semantic representation. Do not traverse the raw binding to rewrite IDs. For
Action rows the public choice is the projected candidate; for Folded rows it is
the projected concrete response candidate. Store the projected domain and
projected choices exactly as emitted by C1_00, with no raw-ID binding table.

- [ ] **Step 5: Implement bounded first-divergence capture.**

Implement:

~~~
def maybe_record_first_divergence(
    accumulator: C1_03AccumulatorV1,
    sample: SampleViewV1,
    request: PublicObservationTeacherRequestV1,
    result: SelectedTeacherResultV1,
    score_vector: tuple[float, ...],
    tie_class: str,
) -> None: ...
~~~

Record no more than 32 entries and no more than one entry per
semanticEpisodeId. Each entry contains only the allowed public fields:

~~~
semanticEpisodeId
decisionIndex
decisionFamily
candidateCount
model-facing observation/domain identity where present
projected complete public domain
projected factual A9 choice
projected Teacher choice
scoreVector
tieClass
policyTieRngWordsConsumed
~~~

Before appending, recursively reject forbidden raw keys and raw entity IDs from
the source alias table. A privacy rejection increments PRIVACY_FAILURE_COUNT
and stops the run; it never silently redacts a malformed record and continues.

- [ ] **Step 6: Run the focused tests and confirm GREEN.**

Run the focused unittest command. Confirm that the tests find no raw IDs in
divergence output, preserve aliases, and enforce family-specific ownership.

## Task 5: Implement the streaming offline runner and admission decision

**Files:**

- Modify: ml/src/argentum_ml/characterization/c1_03.py
- Modify: ml/tests/test_c1_03_characterization.py

- [ ] **Step 1: Write RED tests for the complete offline flow.**

Add tests for:

~~~
C1_03CharacterizationTests.test_offline_runner_skips_test_before_target_or_teacher_access
C1_03CharacterizationTests.test_offline_runner_uses_reader_issued_samples_only
C1_03CharacterizationTests.test_flat_no_label_is_a_failure_but_structured_no_label_is_expected
C1_03CharacterizationTests.test_admission_defers_when_a_supported_family_has_no_train_or_validation_evidence
C1_03CharacterizationTests.test_admission_rejects_any_hard_trust_or_ownership_failure
C1_03CharacterizationTests.test_admission_admits_only_exact_bindable_teacher_owned_flat_rows
~~~

The test for a valid admission fixture must still assert:

~~~
STRUCTURED_BOOTSTRAP_ADMITTED=NO
BOOTSTRAP_LABEL_MATERIALIZER_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
~~~

- [ ] **Step 2: Run the focused tests and confirm RED.**

Run the focused unittest command and confirm the orchestration/decision code is
missing.

- [ ] **Step 3: Implement the strict streaming runner.**

Implement:

~~~
def run_offline(
    artifact_root: Path,
    *,
    plan: C1_03PlanV1,
    teacher: PublicObservationTeacherV1,
) -> C1_03OfflineSummaryV1: ...
~~~

Open the artifact only with DerivedArtifactReader.open(artifact_root). Iterate
reader-issued samples. Immediately inspect the partition and skip TEST before
reading target/provenance/binding fields for characterization. Process only
TRAIN and VALIDATION. For each permitted row, create a SampleViewV1, update
raw/domain counts, classify C1_00 eligibility, and invoke the Teacher only for
exact-bindable flat or expected structured requests.

The function must maintain these hard counters:

~~~
CANDIDATE_TRUNCATION_COUNT
INVALID_SELECTION_COUNT
HIDDEN_POLICY_FALLBACK_COUNT
PRIVACY_FAILURE_COUNT
TRUST_FAILURE_COUNT
TEACHER_FLAT_FAILURE_COUNT
C1_00_UNBINDABLE_FLAT_ROWS
C1_00_AUTHORITY_FAILURE_COUNT
~~~

It must not catch a malformed reader artifact as a row-level failure. Reader
errors abort the run with a blocked result and no partial admission claim.
Only ACTION_CANDIDATES domains with nonempty candidate.requiredPayloadFields
increment C1_00_UNBINDABLE_FLAT_ROWS. Any other InferenceError, including a
binding mismatch, affordable-mask mismatch, invalid source ordinal, projection
mismatch, malformed required field, or malformed folded response, increments
C1_00_AUTHORITY_FAILURE_COUNT and TRUST_FAILURE_COUNT and produces BLOCKED.
Teacher failures on supported exact-bindable flat rows are counted and produce
REJECTED.
Structured NO_LABEL is expected. C1_00-unbindable rows are counted in the
overall useful denominator and never converted to a Teacher NO_LABEL.

- [ ] **Step 4: Implement the exact admission decision.**

Implement:

~~~
def decide_admission(
    summary: C1_03OfflineSummaryV1,
    *,
    gameplay_status: str,
) -> str: ...
~~~

Return exactly one of ADMITTED_LIMITED_FLAT_REFERENCE_BOOTSTRAP, REJECTED,
DEFERRED, or BLOCKED using this order:

1. BLOCKED for missing/invalid source or derived authority, reader failure, C1_00_AUTHORITY_FAILURE, or an unavailable required evaluation dependency.
2. REJECTED for any hard trust, privacy, candidate, selection, flat Teacher, or Action-ownership failure.
3. DEFERRED when either supported family lacks exact-bindable evidence in TRAIN or VALIDATION, or the permitted characterization population is empty.
4. ADMITTED_LIMITED_FLAT_REFERENCE_BOOTSTRAP when all hard gates and evidence-sufficiency conditions pass. GAMEPLAY_CHARACTERIZATION=BLOCKED alone does not enter step 2.

Set TEACHER_QUALITY_CHARACTERIZED=YES when offline characterization completes,
including a REJECTED decision. Set it to PARTIAL only for an interrupted
run with a persisted, explicitly incomplete diagnostic; never call partial
evidence a PASS.

- [ ] **Step 5: Run the focused tests and confirm GREEN.**

Run the focused unittest command. All runner, TEST, hard-gate, and admission
state tests must pass.

## Task 6: Implement deterministic JSON/Markdown evidence output and CLI

**Files:**

- Modify: ml/src/argentum_ml/characterization/c1_03.py
- Modify: ml/tests/test_c1_03_characterization.py

- [ ] **Step 1: Write RED tests for report shape and path privacy.**

Add tests that call the renderer with a small summary and assert:

~~~
C1_03CharacterizationTests.test_json_summary_contains_exact_identity_binding
C1_03CharacterizationTests.test_markdown_report_contains_required_raw_counts_and_status_fields
C1_03CharacterizationTests.test_report_contains_no_local_absolute_paths
C1_03CharacterizationTests.test_report_contains_no_teacher_labels_or_test_outcomes
C1_03CharacterizationTests.test_report_serialization_is_deterministic
~~~

The deterministic test must render the same summary twice and compare bytes.

- [ ] **Step 2: Run the focused tests and confirm RED.**

Run the focused unittest command and confirm the report functions are absent.

- [ ] **Step 3: Implement report serialization.**

Implement:

~~~
def write_summary(summary: C1_03OfflineSummaryV1, path: Path) -> None: ...
def render_markdown(summary: C1_03OfflineSummaryV1) -> str: ...
def write_report(summary: C1_03OfflineSummaryV1, path: Path) -> None: ...
~~~

The JSON must include the exact source, split, derived, materializer, Teacher,
admission-purpose, plan, and gameplay identities. It must include raw counts
beside all rates, all twelve structured families, all candidate-count buckets,
all tie classes, all ownership counters, and the bounded divergence list.

The Markdown report must contain at least:

~~~
TASK=C1_03_PUBLIC_OBSERVATION_TEACHER_QUALITY_AND_ADMISSION
BASE=<exact>
WORKTREE_CLEAN=YES/NO

SOURCE_DATASET_ID=<exact>
MEASUREMENT_HEAD=<exact implementation commit used for characterization>
TRAIN_EPISODES=<n>
VALIDATION_EPISODES=<n>
TEST_EPISODES_USED_FOR_SELECTION=0
TRAIN_DECISIONS=<n>
VALIDATION_DECISIONS=<n>
FLAT_ACTION_DECISIONS=<n>
FOLDED_DECISION_OPTION_DECISIONS=<n>
STRUCTURED_DECISIONS=<n>
C1_00_EXACT_BINDABLE_FLAT_ROWS=<n>
C1_00_UNBINDABLE_FLAT_ROWS=<n>
TEACHER_SELECTED=<n>
TEACHER_NO_LABEL=<n>
FLAT_LABEL_YIELD=<value>
OVERALL_USEFUL_LABEL_YIELD=<value>
BEHAVIOR_AGREEMENT=<value>
BEHAVIOR_DISAGREEMENT=<value>
UNIQUE_MAX_COUNT=<n>
SEMANTIC_DISCRIMINATOR_TIE_COUNT=<n>
POLICY_TIE_RNG_COUNT=<n>
PASS_SELECTION_COUNT=<n>
NON_PASS_SELECTION_COUNT=<n>
TRUST_FAILURE_COUNT=0/<n>
FOCUSED_TEST_COUNT=<positive integer>
TEACHER_POLICY_TIE_SCHEDULE_IDENTITY=argentum-ml-c1-03-teacher-policy-tie-schedule@v1
SOURCE_POLICY_RNG_IDENTITY=explicit-seed/kotlin-policy-state-v1
C1_03_TEACHER_POLICY_TIE_SEED=0
C1_03_INITIAL_POLICY_TIE_CURSOR=0
LEGACY_A9_POLICY_SEED_REUSED=NO
~~~

The report uses `MEASUREMENT_HEAD` for the exact implementation commit used for
characterization. It does not attempt to embed a self-referential report
commit. The final evidence commit and remote branch head are verified
externally at the exact-SHA handoff.

The final status block must include all required C1_03 fields, including
STRUCTURED_BOOTSTRAP_ADMITTED=NO,
BOOTSTRAP_LABEL_MATERIALIZER_IMPLEMENTED=NO,
BOOTSTRAP_LABEL_MATERIALIZER_AUTHORIZED=NO,
TRAINING_AUTHORIZED=NO, C1_03_CODE_REVIEW_PASS=NO,
C1_03_FINAL_ACCEPTANCE_PASS=NO, and STOP_FOR_EXACT_SHA_REVIEW=YES.

It must also include the final characterization gate:

~~~
C1_03_CHARACTERIZATION_PASS=YES/NO
~~~

It must also include the hard-gate and frozen-boundary fields:

~~~
TEACHER_POLICY_FROZEN_DURING_C1_03=YES
TEACHER_CONFIG_FROZEN_DURING_C1_03=YES
TEACHER_PROVENANCE_VALID=YES/NO
TEACHER_INFORMATION_SET_VALID=YES/NO
TEACHER_DOMAIN_BINDING_VALID=YES/NO
TEACHER_RUNTIME_ID_RENAMING_SAFE=YES/NO
TEACHER_CANDIDATE_PERMUTATION_SAFE=YES/NO
SELECTION_V2_COMPATIBLE=YES/NO
POLICY_TIE_RNG_V1_COMPATIBLE=YES/NO
FINAL_TEST_USED_TO_SELECT_TEACHER=NO
FINAL_TEST_USED_TO_TUNE_TEACHER=NO
FINAL_TEST_USED_FOR_ADMISSION_THRESHOLD_SELECTION=NO
OFFLINE_CHARACTERIZATION=PASS/FAIL/BLOCKED
GAMEPLAY_CHARACTERIZATION=PASS/FAIL/BLOCKED/NOT_RUN
TEACHER_FAILURE_RATE_CHARACTERIZED=YES/NO
TEACHER_COVERAGE_CHARACTERIZED=YES/NO
TEACHER_QUALITY_CHARACTERIZED=YES/NO/PARTIAL
ACTION_CANDIDATES_BOOTSTRAP_ELIGIBLE=YES/NO
FOLDED_DECISION_OPTIONS_BOOTSTRAP_ELIGIBLE=YES/NO
STRUCTURED_BOOTSTRAP_ELIGIBLE=NO
TEACHER_ADMISSION_RESULT=ADMITTED_LIMITED_FLAT_REFERENCE_BOOTSTRAP/REJECTED/DEFERRED/BLOCKED
FIRST_C1_TEACHER_SELECTION=TEACHER_POLICY_IDENTITY/NONE
TEACHER_BOOTSTRAP_ADMITTED=YES/NO
TEACHER_ADMISSION_SCOPE=FLAT_REFERENCE_BOOTSTRAP/none
STRUCTURED_BOOTSTRAP_ADMITTED=NO
TRUST_FAILURE_COUNT=<actual>/<actual denominator>
HIDDEN_POLICY_FALLBACK_COUNT=<actual>
CANDIDATE_TRUNCATION_COUNT=<actual>
PRIVACY_FAILURE_COUNT=<actual>
P1=<actual>
P2=<actual>
~~~

Do not include the source filesystem path, derived temporary path, raw source
binding table, raw IDs, full trajectory rows, TEST choices/outcomes, or a
training-label field. The report may contain source/Teacher semantic choices
only in the bounded projected first-divergence records.

- [ ] **Step 4: Implement the module CLI.**

The CLI must require explicit artifact and output paths and never create a
source dataset or a TrajectoryV1 shard:

~~~
python -m argentum_ml.characterization.c1_03 \
  --artifact-root <disposable-derived-view> \
  --summary-out <repo>/docs/ml/c1-03-public-observation-teacher-quality-and-admission.json \
  --report-out <repo>/docs/ml/c1-03-public-observation-teacher-quality-and-admission.md
~~~

The command must instantiate only PublicObservationTeacherConfigV1.reference()
and PublicObservationTeacher(config, TEACHER_SOURCE_COMMIT). It must verify the
configuration digest before opening the artifact and refuse an unexpected
plan/identity value.

- [ ] **Step 5: Run the focused tests and confirm GREEN.**

Run the focused unittest command and confirm deterministic, path-free report
output.

## Task 7: Add the opt-in Kotlin C1_00 materialization runner

**Files:**

- Create: gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1_03DerivedViewMaterializationTest.kt

- [ ] **Step 1: Add the disabled-by-default manual test.**

Follow the existing PostB2TrajectoryReaderMeasurementTest pattern. Register the
large test only when both explicit properties are present:

~~~
class C1_03DerivedViewMaterializationTest : FunSpec({
    test("materializes the exact accepted source through C1_00")
        .config(
            enabled = sourcePath() != null && outputPath() != null,
            timeout = 2.hours,
        ) {
            val source = Path.of(requireNotNull(sourcePath())).toAbsolutePath().normalize()
            val output = Path.of(requireNotNull(outputPath())).toAbsolutePath().normalize()
            require(source != output)
            require(!output.startsWith(source))
            require(!source.startsWith(output))
            require(!Files.exists(output.resolve("manifest.json")))
            require(!Files.exists(output.resolve("samples.ndjson")))

            val manifest = C1LearnerArtifactMaterializer.materialize(
                publishedDatasetDirectory = source,
                outputDirectory = output,
                sourceCommit = ACCEPTED_MATERIALIZER_SOURCE_COMMIT,
                config = C1MaterializerConfig(
                    implementationIdentity = MATERIALIZER_IMPLEMENTATION_IDENTITY,
                    configDigest = MATERIALIZER_CONFIG_DIGEST,
                ),
            )

            manifest.sourceDatasetId shouldBe ACCEPTED_SOURCE_DATASET_ID
            manifest.sourceManifestContentDigest shouldBe ACCEPTED_SOURCE_MANIFEST_DIGEST
            manifest.derivedViewSchemaIdentity shouldBe DERIVED_VIEW_SCHEMA_IDENTITY
            manifest.materializerImplementationIdentity.implementation shouldBe
                MATERIALIZER_IMPLEMENTATION_IDENTITY
            manifest.materializerImplementationIdentity.sourceCommit shouldBe
                ACCEPTED_MATERIALIZER_SOURCE_COMMIT
            manifest.materializerConfigDigest shouldBe MATERIALIZER_CONFIG_DIGEST
            manifest.episodeCount shouldBe ACCEPTED_SOURCE_EPISODES
            manifest.sampleCount shouldBe ACCEPTED_SOURCE_DECISIONS
            manifest.recomputeDerivedArtifactId() shouldBe manifest.derivedArtifactId
            manifest.recomputeManifestContentDigest() shouldBe manifest.manifestContentDigest
        }
})
~~~

Use exact constants in the file, no source path or output path constants. The
runner must not print or persist local paths in evidence. Its normal CI-disabled
state is NOT_RUN, not PASS.

- [ ] **Step 2: Run only the existing focused C1_00 tests first.**

Run:

~~~
just test-class C1LearnerArtifactMaterializerTest
~~~

Expected: the existing C1_00 materializer suite remains green. If the Just
wrapper fails before Gradle with the known Windows WinError 193, record
JUST_WRAPPER=BLOCKED and run the separate native fallback:

~~~
gradlew.bat :gym-trainer:test --tests C1LearnerArtifactMaterializerTest --console=plain
~~~

Do not call a skipped or wrapper-blocked run PASS.

- [ ] **Step 3: Run the manual materializer only after the plan is committed.**

Before running, verify disk space and that the output is outside the source:

~~~
Get-PSDrive -Name C | Select-Object Name,Free,Used
Test-Path -LiteralPath <source>
Test-Path -LiteralPath <output>
~~~

Start exactly one explicit manual run with the accepted source path and a new
temporary output directory:

~~~powershell
$env:C1_03_DATASET=<exact-accepted-source-dataset>
$env:C1_03_OUTPUT=<new-disposable-derived-view-directory>
just test-class C1_03DerivedViewMaterializationTest --rerun-tasks -DeclCollect=true
~~~

If the Just wrapper is blocked, use the separately labelled native fallback:

~~~powershell
$env:C1_03_DATASET=<exact-accepted-source-dataset>
$env:C1_03_OUTPUT=<new-disposable-derived-view-directory>
gradlew.bat :gym-trainer:test --rerun-tasks --tests C1_03DerivedViewMaterializationTest --console=plain -DeclCollect=true
~~~

The output must be the only new data artifact. Do not generate any new A9
episodes, dataset manifest, training shard, label file, or TrajectoryV1
replacement.

- [ ] **Step 4: Verify the derived artifact with the strict Python reader.**

Before invoking the Teacher, run the reader-only verification command:

~~~
cd ml
py -3.13 -c "from pathlib import Path; from argentum_ml.data.derived_reader import DerivedArtifactReader; import sys; r=DerivedArtifactReader.open(Path(sys.argv[1])); print(r.manifest['derivedArtifactId']); print(r.manifest['sourceDatasetId']); print(r.manifest['sourceManifestContentDigest']); print(r.manifest['episodeCount']); print(r.manifest['sampleCount']); r.close()" <disposable-derived-view-directory>
~~~

Check exact source dataset/manifest binding, derived schema, materializer
identity/config digest, artifact/manifest digests, sample/episode counts, and
TRAIN/VALIDATION/TEST partition counts. This reader-only step does not inspect
Teacher quality and does not submit any sample to the Teacher.

## Task 8: Run the frozen offline characterization manually

**Files:**

- Modify: docs/ml/c1-03-public-observation-teacher-quality-and-admission.md
- Modify: docs/ml/c1-03-public-observation-teacher-quality-and-admission.json

- [ ] **Step 1: Reconfirm the no-results-before-plan gate.**

Before the first characterization invocation, verify:

~~~
git log -1 --oneline
git status --short
rg -n 'TEACHER_ADMISSION_PURPOSE_IDENTITY|FLAT_LABEL_YIELD|OVERALL_USEFUL_LABEL_YIELD|GAMEPLAY_EVALUATION_CONTRACT_ID' docs/superpowers/plans/2026-09-13-c1-03-public-observation-teacher-quality-and-admission.md
~~~

The plan commit must be present and the report outputs must not yet exist.
Do not inspect quality results before this check.

- [ ] **Step 2: Run the Python characterization over TRAIN/VALIDATION only.**

Use the regenerated disposable artifact:

~~~
cd ml
py -3.13 -m argentum_ml.characterization.c1_03 \
  --artifact-root <disposable-derived-view-directory> \
  --summary-out ..\docs\ml\c1-03-public-observation-teacher-quality-and-admission.json \
  --report-out ..\docs\ml\c1-03-public-observation-teacher-quality-and-admission.md \
  --measurement-head <implementation-head> \
  --focused-test-count <positive-focused-test-count>
~~~

The command must stop on reader/identity/privacy/trust failure. It must not
materialize labels or alter the derived view. Confirm the console summary says
TEST_ROWS_SUBMITTED_TO_TEACHER=0 before reading the quality report.

- [ ] **Step 3: Validate the report against the frozen requirements.**

Check the generated JSON and Markdown for:

~~~
exact source dataset/manifest and derived artifact identity
TRAIN/VALIDATION raw counts
TEST selection-use count = 0
C1_00 bindable/unbindable flat counts
Teacher selected/no-label counts and reasons
flat conditional yield and overall useful yield
all structured families and rates
candidate/executable distributions
tie classes, same-kind/cross-kind ties, large tied-max sets
PolicyTieRng words and cursor evidence
Pass/non-pass and all stratified tables
behavior agreement/disagreement only for selected bindable rows
bounded public first divergences
hard counters and ownership counters
single admission result
~~~

Do not add a post-hoc threshold, change a denominator, print TEST choices, or
re-run with a different Teacher/comparator because the first report looks weak.

## Task 9: Audit and, only if possible, run bounded gameplay

**Files:**

- Modify: ml/src/argentum_ml/characterization/c1_03.py only if an existing public seam can be consumed without production changes.
- Modify: docs/ml/c1-03-public-observation-teacher-quality-and-admission.md
- Modify: docs/ml/c1-03-public-observation-teacher-quality-and-admission.json

- [ ] **Step 1: Perform a read-only seam audit before selecting jobs or seeds.**

Inspect the existing public paths:

~~~
gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt
gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExternalPolicy.kt
gym/src/main/kotlin/com/wingedsheep/gym/GameEnvironment.kt
gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/defaults/RemoteHttpEvaluator.kt
~~~

The seam must accept the frozen Python Teacher as Policy A, the fixed
b2-a9-deterministic-external-policy@v1 comparator as Policy B, and an explicit
public structured completion policy. It must submit complete semantic choices
through the existing trusted environment boundary. A Kotlin duplicate of the
Python Teacher, raw GameState access, AutoPay, automatic targeting, hidden
completion, or fallback from a flat Teacher failure is not an acceptable seam.

- [ ] **Step 2: If the seam is absent, record BLOCKED and do not add a bridge.**

Write exactly:

~~~
GAMEPLAY_CHARACTERIZATION=BLOCKED
GAMEPLAY_BLOCK_REASON=MISSING_EXISTING_PUBLIC_EXECUTION_SEAM
~~~

This does not reject admission. Do not generate gameplay jobs or seeds when
the seam is absent.

- [ ] **Step 3: If the seam exists, generate the frozen 16-pair job manifest.**

Generate four pairing keys per required cell:

~~~
Policy A controls Akiri, Akiri starts
Policy A controls Akiri, Chevill starts
Policy A controls Chevill, Akiri starts
Policy A controls Chevill, Chevill starts
~~~

Derive each engine seed before running either policy from:

~~~
payload = {
    "schema": "argentum-ml-c1-03-gameplay-seed@v1",
    "gameplayContractId": "argentum-ml-gameplay-evaluation@v1",
    "engineCommit": "b9eb8da182390095d73b9c06d9bbe20049156e9b",
    "cell": cell_identity,
    "pairIndex": pair_index,
}
seed_bytes = hashlib.sha256(canonical_bytes(payload)).digest()
actual_engine_seed = int.from_bytes(seed_bytes[:8], "big", signed=True)
~~~

Audit every seed against all source trajectory actualEngineSeed values, semantic
episode IDs, and replay identities. If the audit cannot be completed, set
GAMEPLAY_CHARACTERIZATION=BLOCKED and report the exact missing audit dependency.
Do not guess independence from numeric seed inequality.

- [ ] **Step 4: Execute exactly 32 paired games when the seam and seed audit pass.**

Run 16 Policy A and 16 Policy B executions, retaining the exact pairing key.
Use identical environment, engine commit, locked decks, roster/orientation,
starter, engine seed, opponent, metric contract, and RNG declaration for each
A/B pair. Count terminal wins/losses/draws only from factual GAME_TERMINAL
closures. Keep interrupted, failed, trust-failed, flat-Teacher-failed, and
structured-completion-failed counts separate.

Report each cell before any aggregate:

~~~
job count
terminal/interrupted/failed count
wins/losses/draws
termination rate
decision count
game length
Teacher flat selections
structured completions
Teacher failures
trust failures
~~~

Report paired transitions such as A win -> B loss and
A terminal -> B interrupted; do not manufacture independent confidence
intervals from numeric seeds or dependent decision rows.

- [ ] **Step 5: Update the admission decision only through the frozen rule.**

Gameplay BLOCKED leaves the admission decision unchanged. An executed trust or
ownership failure rejects admission. A weak win rate, source disagreement, or
high symmetry rate is reported diagnostically and does not create a new
post-hoc veto threshold.

## Task 10: Verify, self-review, commit evidence, push, and stop

**Files:**

- All files created or modified by Tasks 1–9

- [ ] **Step 1: Run all focused Python tests and compile checks.**

Run:

~~~
cd ml
py -3.13 -m unittest discover -s tests -v
py -3.13 -m compileall -q src tests
cd ..
just ml-test
just ml-check
~~~

Do not call a skipped/unavailable test PASS. If the Just wrapper is blocked,
report the exact wrapper status and retain the native Python results separately.

- [ ] **Step 2: Run focused Kotlin verification.**

Run the existing C1_00 tests and the new disabled-by-default runner in its
normal no-property state. Record that the large test was NOT_RUN in ordinary
verification. If Kotlin/Gym code was touched, run the focused native tests and
surrounding module suite through just; use gradlew.bat only as separately
labeled Windows fallback evidence when the wrapper is blocked.

- [ ] **Step 3: Perform the required scope audit.**

Use the exact base and include untracked files in the audit:

~~~
git status --short
git diff --check
git diff --name-only b9eb8da182390095d73b9c06d9bbe20049156e9b...HEAD
git ls-files --others --exclude-standard
~~~

Confirm:

~~~
RULES_CHANGED=NO
GYM_SEMANTICS_CHANGED=NO
TRAJECTORY_V1_MUTATED=NO
SOURCE_DATASET_MUTATED=NO
LOCKED_DECKS_CHANGED=NO
C1_00_CONTRACT_CHANGED=NO
C1_02_TEACHER_POLICY_CHANGED=NO
TRAINING_CODE_ADDED=NO
BOOTSTRAP_LABELS_CREATED=NO
~~~

If any unrelated change appears, preserve it and stop for user direction; do
not reset, stash, restore, or delete another agent's work.

- [ ] **Step 4: Run an independent self-review.**

Review the complete diff and generated report against every section of the
approved design spec and this plan. Classify findings as P1/P2; resolve all
blocking findings in this branch. Verify that the report still says
C1_03_CODE_REVIEW_PASS=NO because independent acceptance review has not
occurred. Do not interpret local self-review as hosted CI or final acceptance.

- [ ] **Step 5: Commit implementation and evidence separately from the design commit.**

After fresh verification, commit the implementation/tests first:

~~~
git add ml/src/argentum_ml/characterization ml/tests/test_c1_03_characterization.py gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1_03DerivedViewMaterializationTest.kt
git commit -m "feat: characterize C1-03 public observation teacher"
~~~

Then commit the generated report and summary:

~~~
git add docs/ml/c1-03-public-observation-teacher-quality-and-admission.md docs/ml/c1-03-public-observation-teacher-quality-and-admission.json
git commit -m "docs: record C1-03 teacher admission evidence"
~~~

The disposable derived view and temporary logs must not be staged.

- [ ] **Step 6: Push only the dedicated writable-fork branch.**

Verify the configured remote before pushing:

~~~
git remote get-url origin
~~~

It must be exactly:

~~~
https://github.com/chrismaghuhn/argentum-engine.git
~~~

Push the current branch to the writable fork only:

~~~
git push origin HEAD:chris/c1-03-teacher-quality-admission-20260913
~~~

Fetch/read back the branch SHA and record it in the report if the report was
generated before the final commits. Do not amend/rebase silently, push
upstream, create a PR, merge, begin C1_04, materialize labels, or start
training.

- [ ] **Step 7: Stop at exact-SHA review.**

Final state must say:

~~~
C1_03_READY_FOR_ACCEPTANCE=YES
C1_03_FINAL_ACCEPTANCE_PASS=NO
BOOTSTRAP_LABEL_MATERIALIZER_IMPLEMENTED=NO
BOOTSTRAP_LABEL_MATERIALIZER_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
NEXT_TASK_STARTED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
~~~

No PR is created automatically.

## Plan self-review checklist

Before handing this plan to execution, verify:

- [x] The exact source dataset and manifest identities are fixed before measurement.
- [x] C1_00-unbindable flat rows are separated from Teacher NO_LABEL and remain in the overall useful-yield denominator.
- [x] Action and Folded ownership rules are distinct.
- [x] Admission is dataset-bound and structured bootstrap remains NO.
- [x] TEST is physically validated only as part of the derived artifact; its rows are not submitted or used for characterization/admission.
- [x] Tie classes, RNG consumption, raw counts, rates, and stratified tables are required.
- [x] First divergences use C1_00 public aliases without a second alias-normalization implementation.
- [x] Gameplay has an explicit BLOCKED outcome and a frozen 16-pair/32-execution matrix if feasible.
- [x] Large materialization is manually opt-in and excluded from ordinary CI.
- [x] No source generation, label materialization, training, Teacher tuning, PR, or merge is authorized.
- [x] Every code step has a concrete file, test, command, and expected state.
- [x] The final verification distinguishes local pass, wrapper block, hosted checks, code review, and exact-SHA acceptance.

The placeholder scan must return no matches.
