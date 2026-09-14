# C1_03 PublicObservationTeacher Quality and Admission Design

> **Status:** Approved design, pending implementation-plan execution

## Goal

Characterize the accepted `PublicObservationTeacherV1` over the exact accepted
Trajectory V1 source dataset and decide whether it is admissible as a
dataset-bound, limited `FLAT_REFERENCE_BOOTSTRAP` reference-label process.

This slice measures coverage, failure behavior, source-behavior agreement,
score coarseness, execution ownership, and bounded gameplay behavior. It does
not modify the Teacher, create source data, materialize bootstrap labels, or
authorize training.

## Authority and immutable inputs

The work starts from the accepted fork merge:

```text
BASE=origin/main
BASE_SHA=b9eb8da182390095d73b9c06d9bbe20049156e9b
ACCEPTED_C1_02_PR_HEAD=8cad4845dc59192dde86849c8ba4ceacc1bb6331
UPSTREAM_ROLE=REFERENCE_ONLY
```

The current root checkout is not used for edits because it contains unrelated
working-tree changes. Work takes place in a dedicated worktree and branch.

The source authority is the existing accepted DatasetManifestV1 and its 64
referenced shards:

```text
SOURCE_DATASET_ID=69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03
SOURCE_MANIFEST_CONTENT_DIGEST=de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2
SOURCE_EPISODES=64
SOURCE_DECISIONS=125471
```

The source path is an operational input only. It is never persisted in a
committed report or used as an identity alias. The source is opened through
`TrajectoryV1Reader.openPublishedDataset`; Python never walks source shards.

The accepted Teacher is frozen exactly as implemented by C1_02:

```text
TEACHER_POLICY_IDENTITY=argentum-ml-public-observation-bootstrap-teacher@v1
TEACHER_CONTRACT_IDENTITY=argentum-ml-teacher-bootstrap@v1
TEACHER_CONFIG_SCHEMA_IDENTITY=argentum-ml-public-observation-teacher-config@v1
TEACHER_CONFIG_DIGEST=fa358597c09ce466e1be1823de485e0accd84be622184fa1d6505806aff0d7d8
SELECTION_CONTRACT_IDENTITY=argentum-ml-policy-selection@v2
POLICY_RNG_IDENTITY=argentum-ml-policy-tie-rng@v1
TEACHER_SOURCE_COMMIT=8cad4845dc59192dde86849c8ba4ceacc1bb6331
```

The materializer uses the accepted merged source at `BASE_SHA`:

```text
DERIVED_VIEW_SCHEMA_IDENTITY=argentum-ml-derived-learner-view@v1
MATERIALIZER_IMPLEMENTATION_IDENTITY=c1-materializer@v1
MATERIALIZER_SOURCE_COMMIT=b9eb8da182390095d73b9c06d9bbe20049156e9b
MATERIALIZER_CONFIG_PREIMAGE={"derivedViewSchemaIdentity":"argentum-ml-derived-learner-view@v1","materializerImplementationIdentity":"c1-materializer@v1","purpose":"C1_03_CHARACTERIZATION_ONLY","version":1}
MATERIALIZER_CONFIG_DIGEST=7d5fbfd0bfe71844fefbd25d3fcce7beac3de8de281d9a2f02cf225aef27364c
```

The config digest is SHA-256 over the UTF-8 C0/A3-compatible canonical JSON
preimage shown above. It identifies the characterization use of the existing
materializer; it does not change projection semantics.

The offline Teacher has its own exogenous tie-RNG schedule. The A9 source
policy seed remains provenance only because its declared RNG identity is the
legacy `explicit-seed/kotlin-policy-state-v1`:

```text
C1_03_TEACHER_POLICY_TIE_SCHEDULE_IDENTITY=
  argentum-ml-c1-03-teacher-policy-tie-schedule@v1
SOURCE_POLICY_RNG_IDENTITY=explicit-seed/kotlin-policy-state-v1
C1_03_TEACHER_POLICY_TIE_SEED=0
C1_03_INITIAL_POLICY_TIE_CURSOR=0
LEGACY_A9_POLICY_SEED_REUSED=NO
POLICY_TIE_RNG_IDENTITY=argentum-ml-policy-tie-rng@v1
FIRST_DIVERGENCE_PER_EPISODE=1
POLICY_A_EXECUTIONS=16
POLICY_B_EXECUTIONS=16
```

For each offline Teacher policy instance and seat, the harness creates one
`PolicyTieRngStateV1` from the fixed seed and seat at cursor zero. The state is
keyed by semantic episode, Teacher policy identity, and roster seat, then
carried across that instance's decisions. It is never recreated per decision.
The source `policySeed` is not passed to `from_policy_seed()` and the source
legacy `policyRngIdentity` is never reinterpreted. Unique maxima and semantic
discriminator ties leave the cursor unchanged; unresolved exact ties advance
it exactly as Selection V2 reports.

The schedule values are part of the C1_03 plan digest. An equivalent plan
therefore has the same Teacher tie seed, initial cursor, divergence bound, and
planned A/B execution counts.

## Admission purpose and frozen decision rule

The exact purpose identity is:

```text
TEACHER_ADMISSION_PURPOSE_IDENTITY=argentum-ml-flat-reference-bootstrap@v1
TEACHER_ADMISSION_SCOPE=FLAT_REFERENCE_BOOTSTRAP
```

The purpose is dataset-bound. An admission result applies only to the exact
source dataset, source manifest digest, split identity, Teacher identity and
Teacher configuration digest recorded by this evaluation. It does not admit
future datasets, all Commander decisions, expert labels, or strategically
strong play.

The predeclared admission rule is:

```text
ADMIT_LIMITED_FLAT_REFERENCE_BOOTSTRAP iff:
  1. Source and derived artifact identities and bindings are exact.
  2. Teacher, model-facing, Selection V2 and PolicyTieRng identities match.
  3. C1_02 runtime-ID and candidate-permutation safety remains valid.
  4. No raw GameState, hidden information, source target or outcome reaches
     the Teacher scorer.
  5. Candidate truncation, invalid selection, hidden fallback, privacy and
     trust failure counts are all zero.
  6. Both supported flat families occur in TRAIN and VALIDATION.
  7. Every C1_00 exact-bindable supported flat row receives a Teacher label.
  8. Every selected ACTION_CANDIDATES candidate is fully Teacher-owned:
     requiresStructuredAction=false, requiredPayloadFields=[], and its exact
     choicePayload is {}.
  9. FOLDED_DECISION_OPTIONS are treated as complete semantic responses and
     are not subjected to the ACTION_CANDIDATES payload rule.
 10. Structured rows remain explicit NO_LABEL and are never counted as flat
     coverage.
```

The rule deliberately has no fabricated strategic-strength threshold. The C0
authorities state that source agreement is not gameplay strength and do not
define a numeric threshold for this initial reference purpose. Agreement, tie
rate, and gameplay are therefore diagnostic evidence. A gameplay trust or
execution-ownership failure still fails the corresponding hard gate. Missing
evidence (for example, no observed family in an allowed partition) produces
`DEFERRED`, not an affirmative admission. An unexpected C1_00 authority
failure produces `BLOCKED`; other observed Teacher trust or ownership failures
produce `REJECTED`.

The decision states are mutually exclusive:

```text
ADMITTED_LIMITED_FLAT_REFERENCE_BOOTSTRAP
REJECTED
DEFERRED
BLOCKED
```

`TEACHER_QUALITY_CHARACTERIZED=YES` means that the declared characterization
completed. It does not imply admission or strategic quality.

## Flat eligibility and ownership

The report distinguishes the C1_00 transport boundary from Teacher outcomes:

```text
FLAT_FAMILY_ROWS_TOTAL
C1_00_EXACT_BINDABLE_FLAT_ROWS
C1_00_UNBINDABLE_FLAT_ROWS
TEACHER_SELECTED_ROWS
TEACHER_NO_LABEL_ROWS
```

The yield denominators are explicit:

```text
FLAT_LABEL_YIELD=
  TEACHER_SELECTED_ROWS / C1_00_EXACT_BINDABLE_FLAT_ROWS

OVERALL_USEFUL_LABEL_YIELD=
  TEACHER_SELECTED_ROWS /
  (all permitted TRAIN+VALIDATION policy-relevant rows)
```

The overall denominator therefore includes C1_00-unbindable flat rows and
structured rows. The report also gives the raw all-flat denominator and the
bindable-flat yield separately; it never presents only the conditional yield
as total practical coverage.

An action-domain row is C1_00 exact-bindable only when the existing
`InferenceRequest.from_validated_sample` and source-binding factory accept the
complete source domain. In particular, a source Action candidate with a
nonempty `requiredPayloadFields` can make the action domain unbindable before
the Teacher runs. Such a row is not a Teacher `NO_LABEL` and not a Teacher
runtime failure. It remains in the overall useful-coverage denominator.

The only expected C1_00 flat-unbindable class in this characterization is:

```text
EXPECTED_C1_00_UNBINDABLE=
  ACTION_CANDIDATES with one or more nonempty candidate.requiredPayloadFields
```

Any other request or binding error is an authority failure, including a
binding mismatch, affordable-mask mismatch, invalid source ordinal, model
projection mismatch, or malformed folded response. Such an error is recorded
as `C1_00_AUTHORITY_FAILURE`, increments trust failure, and blocks the
characterization. A `FOLDED_DECISION_OPTIONS` factory error is never converted
to ordinary unbindable coverage.

For an exact-bindable `ACTION_CANDIDATES` row, the selected candidate is
admission-owned only when all of these are true:

```text
selected.requiresStructuredAction=false
selected.requiredPayloadFields=[]
selected ExactSemanticSourceBinding.choicePayload={}
```

An exact-bindable `FOLDED_DECISION_OPTIONS` row is a concrete semantic response
alternative. Its exact response binding is owned by the flat Teacher channel;
the Action-specific payload rule does not apply.

The harness reports root-choice ownership, payload ownership, and subdecision
ownership separately. A source payload is never attributed to the Teacher just
because the source binding contains it.

## C1_00 derived-view regeneration

The full source is materialized only through the existing Kotlin
`C1LearnerArtifactMaterializer.materialize(...)` path. A test-only runner
accepts explicit operational source and output paths and checks that the output
is outside the source directory. It does not contain a Python projection or a
source-shard walker.

The large run is a manual characterization step:

```text
LARGE_SOURCE_MATERIALIZATION_AUTO_CI=NO
C1_03_FULL_MATERIALIZATION=MANUAL_CHARACTERIZATION_STEP
DERIVED_VIEW_REGENERATED=YES
DERIVED_VIEW_PURPOSE=C1_03_CHARACTERIZATION_ONLY
```

The output is disposable and outside the canonical source dataset. The runner
does not overwrite an existing output. Its result is verified by the strict
Python `DerivedArtifactReader`, including:

- source dataset and manifest bindings;
- derived artifact and manifest content digests;
- sample/episode counts and all C0_02 partition counts;
- canonical bytes, LF framing and complete source bindings;
- target membership and structured-domain versions.

The output is never treated as a new DatasetManifestV1, source authority,
training shard, or label artifact.

## Offline characterization

The Python harness opens the regenerated artifact through
`DerivedArtifactReader` and constructs each inference request only through
reader-issued `ValidatedDerivedSample`, `VariableDomainItem`,
`InferenceRequest.from_validated_sample`, and
`PublicObservationTeacherRequestV1.from_inference_request`.

Only TRAIN and VALIDATION samples are submitted to the Teacher. TEST may be
validated as part of the immutable derived artifact, but its target, choice,
agreement, label, and outcome fields are not inspected by the characterization
logic. The report records `TEST_EPISODES_USED_FOR_SELECTION=0` and does not
publish TEST labels or outcomes.

For each permitted partition, the report includes raw counts beside rates for:

- total, Action, Folded, and Structured decisions;
- C1_00 exact-bindable versus unbindable flat rows;
- Teacher Selected versus Teacher `NO_LABEL` and every `NO_LABEL` reason;
- flat and overall label yield;
- structured-family frequency for all twelve current families;
- total and executable candidate-count distributions;
- selected candidate kind, decision family, phase, turn bucket, seat and deck role;
- PassPriority and non-PassPriority selections;
- required payload, target, payment, repeat-count, attack and blocker domains.

Candidate-count buckets are exactly:

```text
1, 2, 3-5, 6-10, 11-20, 21+
```

Turn buckets are frozen as:

```text
1, 2-3, 4-6, 7-10, 11-20, 21+
```

## Selection and score-coarseness evidence

For every exact-bindable flat row the harness computes the frozen Teacher score
vector and classifies the executable maximum set:

```text
UNIQUE_MAX_COUNT
SEMANTIC_DISCRIMINATOR_TIE_COUNT
POLICY_TIE_RNG_COUNT
```

It also reports:

```text
SAME_KIND_MAX_TIE_COUNT
DIFFERENT_KIND_MAX_TIE_COUNT
LARGE_TIED_MAX_COUNT
POLICY_TIE_RNG_WORDS_CONSUMED
```

The semantic-discriminator class is a tied maximum with unique valid
Selection-V2 discriminator values and zero RNG words. The PolicyTieRng class
is an unresolved exact maximum tie with positive RNG consumption. A large tie
set means at least three executable maximum candidates. A high tie rate is
reported as coarseness; it is not silently converted into a rejection
threshold.

The scorer is invoked only with model input and candidate feature views. The
diagnostic score vector is stored only in bounded first-divergence evidence,
never in a label artifact.

## Behavior agreement and first divergence

For selected exact-bindable rows, the normalized semantic value selected by the
Teacher is compared with the recorded factual A9 source choice. The metric is
named `BEHAVIOR_AGREEMENT`; it is never called expert, ground-truth, optimal,
or accuracy evidence.

Agreement is reported by partition, family, candidate-count bucket, selected
action kind/response kind, seat/deck role and phase/turn bucket. It is
diagnostic only and never changes the frozen decision rule.

The harness records at most 32 disagreements, no more than one first
divergence per semantic episode. Each record contains only:

```text
semantic episode/group identity
decision index
model-facing observation identity where available
decision family
candidate count
complete public model-facing legal domain
A9 semantic choice in the existing public alias representation
Teacher semantic choice in the existing public alias representation
Teacher score vector
tie class and PolicyTieRng evidence
```

The existing C1_00 alias projection is used unchanged. The harness omits the
raw-ID binding table and does not implement a second alias-normalization
algorithm. It records no raw GameState, private hand/library state, future
replay tail, future outcome, or hidden engine state.

## Structured coverage

The following exact structured families remain expected `NO_LABEL` outcomes:

```text
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
```

For each family and partition, the report gives decision count, episode count,
semantic-group count where available, and fraction of all policy-relevant
decisions. A root flat choice does not own a later structured decision.

## Gameplay characterization

Gameplay is attempted only if existing public interfaces can connect the
accepted Teacher, the fixed comparator, explicit public structured completion,
and the trusted environment without production-semantics changes. No new
bridge, hidden fallback, or duplicated Teacher is allowed.

The frozen evaluation identities are:

```text
GAMEPLAY_EVALUATION_CONTRACT_ID=argentum-ml-gameplay-evaluation@v1
POLICY_A_IDENTITY=C1_03_EVAL_COMPOSITE_V1
POLICY_B_IDENTITY=b2-a9-deterministic-external-policy@v1
FIXED_OPPONENT_IDENTITY=b2-a9-deterministic-external-policy@v1
STRUCTURED_COMPLETION_POLICY_IDENTITY=argentum-ml-c1-03-a9-structured-completion@v1
```

Policy A uses the accepted Teacher for flat families and an isolated,
evaluation-only public-observation structured completion component. That
component is not Teacher label authority. Policy B is the fixed comparator.
Only the policy-under-test identity changes between paired executions.

The matrix has 16 pairing keys, four per cell, and 32 total executions:

```text
PAIRING_KEYS=16
PAIRING_KEYS_PER_CELL=4
POLICY_A_EXECUTIONS=16
POLICY_B_EXECUTIONS=16
TOTAL_GAME_EXECUTIONS=32
```

The four cells are:

```text
Policy A controls Akiri, Akiri starts
Policy A controls Akiri, Chevill starts
Policy A controls Chevill, Akiri starts
Policy A controls Chevill, Chevill starts
```

The same environment, roster/orientation, locked decks, starter, engine seed,
opponent, metric contract and RNG declaration are used for the paired Policy A
and Policy B executions. The exact pairing key is retained.

Seeds are generated before gameplay outcomes are inspected from the canonical
preimage:

```text
schema=argentum-ml-c1-03-gameplay-seed@v1
gameplayContractId
engineCommit
cell
pairIndex
```

The first eight digest bytes are interpreted as a signed Kotlin Long. Every
seed is audited against all accepted source trajectory `actualEngineSeed`
values, semantic episode IDs and replay identities. Any overlap or incomplete
audit blocks gameplay.

Only factual terminal closures count as wins, losses or draws. Interrupted,
failed, trust-failed, flat-Teacher-failed and structured-completion-failed
results are reported separately and never converted into losses or draws.

If no existing public execution seam can run the composite, the exact outcome
is:

```text
GAMEPLAY_CHARACTERIZATION=BLOCKED
GAMEPLAY_BLOCK_REASON=MISSING_EXISTING_PUBLIC_EXECUTION_SEAM
```

This is not an automatic admission rejection. An executed gameplay trust or
ownership failure is an admission rejection because it violates an existing
hard gate.

## Error handling and persistence

The evaluator is fail-closed. It stops on a malformed or mismatched derived
artifact, source-binding mismatch, untrusted transport, scorer/selection
failure, privacy failure, candidate truncation, or hidden fallback. It never
retries a failed flat decision with A9 and never silently completes a
structured choice.

The evaluator writes only:

```text
docs/ml/c1-03-public-observation-teacher-quality-and-admission.md
docs/ml/c1-03-public-observation-teacher-quality-and-admission.json
```

The JSON summary contains the exact source/derived/Teacher/split identities,
raw aggregate and stratified counters, admission result, and bounded public
divergences. It contains no local absolute paths, large domains, trajectory
shards, replay dumps, Teacher labels, or training targets.

The derived view and any temporary source-audit/evaluation output remain
outside the repository and are disposable.

The persistent report records `MEASUREMENT_HEAD` as the exact implementation
commit used for characterization. It does not attempt to embed a
self-referential report commit. The final evidence commit and remote branch
head are verified externally at the exact-SHA handoff.

## Verification and scope gates

Focused unit tests use small controlled fixtures only. Every C1_03 test is a
method of an actual `unittest.TestCase` class, and `unittest discover` must
report a positive executed-test count. A collection/import success with zero
executed C1_03 tests is not a verification pass. The tests cover:

- exact-bindable versus C1_00-unbindable flat rows;
- structured `NO_LABEL` accounting;
- TEST exclusion from Teacher selection and admission metrics;
- all tie classes and RNG word accounting;
- the exogenous Teacher tie seed, per-episode/seat state carry, and legacy-A9
  seed non-reuse;
- expected C1_00 Action unbindability versus unexpected C1_00 authority
  failure, including folded-response failures;
- family-specific execution ownership;
- existing alias representation in first-divergence output;
- source/derived identity binding and no label persistence;
- bounded report determinism and privacy-safe fields.

The full source materialization is never part of CI. Verification after
implementation is:

```text
py -3.13 -m unittest discover -s ml/tests -v
py -3.13 -m compileall -q ml/src ml/tests
just ml-test
just ml-check
focused Kotlin C1_00/materializer tests through just
native Gradle fallback only if the Windows wrapper is blocked
git diff --check
scope audit including tracked and untracked files
```

The final report must state separately which local gates ran, which were
blocked, and which hosted/independent gates were not run. The report ends with
`C1_03_CODE_REVIEW_PASS=NO`, `C1_03_FINAL_ACCEPTANCE_PASS=NO`, and
`STOP_FOR_EXACT_SHA_REVIEW=YES`.

## Explicit non-goals

```text
PUBLIC_OBSERVATION_TEACHER_V1_SEMANTICS_CHANGED=NO
TEACHER_CONFIG_CHANGED=NO
SELECTION_V2_CHANGED=NO
POLICY_TIE_RNG_CHANGED=NO
C1_00_CONTRACT_CHANGED=NO
TRAJECTORY_V1_MUTATED=NO
SOURCE_DATASET_REGENERATED=NO
RULES_CHANGED=NO
GYM_SEMANTICS_CHANGED=NO
LOCKED_DECKS_CHANGED=NO
BOOTSTRAP_LABEL_MATERIALIZER_IMPLEMENTED=NO
BOOTSTRAP_LABEL_MATERIALIZER_AUTHORIZED=NO
TRAINING_DATA_CREATED=NO
TRAINING_AUTHORIZED=NO
C1_04_STARTED=NO
PR_CREATED=NO
MERGE_PERFORMED=NO
```

## Execution amendment after the accepted C1_00 producer repair

The design was originally frozen against `BASE_SHA`. The accepted C1_00
producer repair changed the model-facing producer bytes without changing any
schema identity or semantic contract. The disposable artifact used for the
subsequent characterization is therefore bound to the accepted repaired-main
commit:

```text
BASE_SHA=b9eb8da182390095d73b9c06d9bbe20049156e9b
ACCEPTED_C1_00_PRODUCER_FIX_MAIN_SHA=4eb71de7893395c4abc965d3ce705d623bca8a92
MATERIALIZER_SOURCE_COMMIT=4eb71de7893395c4abc965d3ce705d623bca8a92
```

This amendment changes provenance binding only. It does not change the
source dataset, TrajectoryV1, C1_00 schema identity, Teacher, config, RNG
schedule, admission semantics, gameplay semantics, or any authorization
boundary.
