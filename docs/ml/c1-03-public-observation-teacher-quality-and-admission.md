# C1_03 PublicObservationTeacher Quality and Admission

~~~text
TASK=C1_03_PUBLIC_OBSERVATION_TEACHER_QUALITY_AND_ADMISSION
BASE=b9eb8da182390095d73b9c06d9bbe20049156e9b
REVIEWED_PLAN_HEAD=1663c13f58e65793db5f1cac4e8e0741ef2c02d0
EXECUTION_PLAN_HEAD=bf7a467b8db6de62f9dfde8d4388a84b53bcde67
MEASUREMENT_HEAD=846d743efc8a58f8067d1eaaf14ef93386e591b3
STATUS=BLOCKED
BLOCK_REASON=C1_00_MODEL_FACING_CROSS_LANGUAGE_CONTRACT_MISMATCH
PLAN_REVIEW_RESULT=PENDING_EXACT_SHA_REVIEW_OF_EXECUTION_DOC_CORRECTION
FOCUSED_TEST_COUNT=28
FOCUSED_TEST_RESULT=28/28_PASS
FULL_ML_TEST_COUNT=130
FULL_ML_TEST_RESULT=130/130_PASS
JUST_ML_CHECK=PASS
~~~

## Materialization

The disposable C1_00-derived view was regenerated from the exact accepted
TrajectoryV1 dataset through the existing Kotlin A7 reader and
`C1LearnerArtifactMaterializer`. The source was not regenerated or modified.

~~~text
DERIVED_VIEW_REGENERATED=YES
DERIVED_VIEW_PURPOSE=C1_03_CHARACTERIZATION_ONLY
SOURCE_DATASET_ID=69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03
SOURCE_MANIFEST_CONTENT_DIGEST=de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2
SOURCE_EPISODES=64
SOURCE_DECISIONS=125471
SOURCE_SHARD_COUNT=64
SOURCE_DATASET_BYTES=6131943637
DERIVED_VIEW_SCHEMA_IDENTITY=argentum-ml-derived-learner-view@v1
MATERIALIZER_IDENTITY=c1-materializer@v1
MATERIALIZER_SOURCE_COMMIT=b9eb8da182390095d73b9c06d9bbe20049156e9b
MATERIALIZER_CONFIG_DIGEST=7d5fbfd0bfe71844fefbd25d3fcce7beac3de8de281d9a2f02cf225aef27364c
DERIVED_ARTIFACT_ID=a6fb40f09a931ceffbca5364db7b145b6c8791783ca60d8294322c271c97c19d
SAMPLES_CONTENT_DIGEST=8f142ee16b2f7bb09ddc6cd5d2a9b27e547e52516b0566e886ea25a846f0f70c
DERIVED_SAMPLES_BYTE_COUNT=8083366132
DERIVED_EPISODE_COUNT=64
DERIVED_SAMPLE_COUNT=125471
TRAIN_EPISODE_COUNT=48
VALIDATION_EPISODE_COUNT=10
TEST_EPISODE_COUNT=6
TRAIN_SAMPLE_COUNT=95351
VALIDATION_SAMPLE_COUNT=18662
TEST_SAMPLE_COUNT=11458
JVM_TEST_HEAP=4g
LARGE_SOURCE_MATERIALIZATION_AUTO_CI=NO
C1_03_FULL_MATERIALIZATION=MANUAL_CHARACTERIZATION_STEP
MATERIALIZER_TEST_EXIT=0
MATERIALIZER_TEST_BODY_SECONDS=3720.908
MATERIALIZER_GRADLE_RESULT=BUILD_SUCCESSFUL
STAGING_REMAINDER=NONE
~~~

The native Kotlin test passed after using the repository's existing 4 GB
large-reader test profile. This was an execution-memory setting only; no
source, materializer, projection, Teacher, or contract code was changed for
the run.

## Strict Python reader gate

The required reader-only verification failed closed before any Teacher call.
The first incompatible row was sample line 129:

~~~text
STRICT_PYTHON_READER=FAIL
C1_03_STRICT_READER_EXIT=1
C1_00_AUTHORITY_FAILURE=YES
TRUST_FAILURE=YES
PRODUCER_FIELD=targets.requirements[].candidatesAliases
PYTHON_READER_EXPECTED_FIELD=targets.requirements[].candidateAliases
ERROR=targets.requirements[0] contains unknown fields: ['candidatesAliases']
~~~

This is a producer/reader contract mismatch in the accepted C1_00 boundary.
The Kotlin model-facing projection and its existing projection test use the
public field `candidatesAliases` for the structured target requirement. The
strict Python model-facing validator currently accepts `candidateAliases` for
that same structured requirement. The real materialized artifact therefore
cannot cross the required strict reader boundary.

The relevant C1_00 evidence is the Kotlin assertion in
`gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1ModelFacingProjectionV1Test.kt`
and the Python target validator in
`ml/src/argentum_ml/contracts/model_facing.py`. No second alias normalization,
field rewrite, filtered artifact, or reader bypass was used.

Under the frozen C1_03 plan, this is not an expected flat unbindable row. It is
an authority failure:

~~~text
EXPECTED_C1_00_UNBINDABLE=ACTION_CANDIDATES_WITH_NONEMPTY_REQUIRED_PAYLOAD_FIELDS_ONLY
C1_00_AUTHORITY_FAILURE→TRUST_FAILURE→BLOCKED
~~~

Resolving this requires a separately reviewed C1_00 cross-language contract
decision. The C1_03 plan explicitly forbids changing C1_00 contracts,
Projection, or Materializer during this characterization.

## Characterization and admission

The strict reader stopped before the offline characterization command. No
sample was submitted to `PublicObservationTeacherV1`; therefore there are no
quality, coverage, agreement, tie, or admission metrics to interpret.

~~~text
OFFLINE_CHARACTERIZATION=NOT_RUN
TEACHER_ROWS_SUBMITTED=0
TEST_ROWS_SUBMITTED_TO_TEACHER=0
FINAL_TEST_USED_TO_SELECT_TEACHER=NO
FINAL_TEST_USED_TO_TUNE_TEACHER=NO
GAMEPLAY_CHARACTERIZATION=NOT_RUN_UPSTREAM_AUTHORITY_BLOCK
TEACHER_ADMISSION_RESULT=BLOCKED
~~~

The frozen identities remain recorded for any future rerun:

~~~text
ADMISSION_PURPOSE=argentum-ml-flat-reference-bootstrap@v1
ADMISSION_SCOPE=FLAT_REFERENCE_BOOTSTRAP
TEACHER_POLICY_IDENTITY=argentum-ml-public-observation-bootstrap-teacher@v1
TEACHER_CONFIG_DIGEST=fa358597c09ce466e1be1823de485e0accd84be622184fa1d6505806aff0d7d8
SELECTION_CONTRACT_IDENTITY=argentum-ml-policy-selection@v2
POLICY_TIE_RNG_IDENTITY=argentum-ml-policy-tie-rng@v1
C1_03_TEACHER_POLICY_TIE_SEED=0
C1_03_INITIAL_POLICY_TIE_CURSOR=0
LEGACY_A9_POLICY_SEED_REUSED=NO
~~~

## Safety boundary

~~~text
TRAJECTORY_V1_MUTATED=NO
SOURCE_DATASET_MUTATED=NO
BOOTSTRAP_LABELS_CREATED=NO
TRAINING_DATASET_CREATED=NO
TRAINING_STARTED=NO
BOOTSTRAP_LABEL_MATERIALIZATION_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
SMALL_LEARNER_SMOKE_AUTHORIZED=NO
C1_04_AUTHORIZED=NO
NEW_GAMEPLAY_BRIDGE_CREATED=NO
~~~

## Handoff state

~~~text
C1_03_IMPLEMENTATION_TESTS=PASS
C1_03_MATERIALIZATION=PASS
C1_03_STRICT_READER=FAIL
C1_03_OFFLINE_MEASUREMENT=BLOCKED
C1_03_READY_FOR_ACCEPTANCE=NO
C1_03_PLAN_REVIEW_PASS=NO_PENDING_EXACT_SHA_REVIEW_OF_EXECUTION_DOC_CORRECTION
C1_03_CODE_REVIEW_PASS=NO
C1_03_FINAL_ACCEPTANCE_PASS=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
~~~

The required next decision is a separately authorized C1_00 contract repair or
compatibility review, followed by regeneration and a fresh exact-bound
characterization. C1_03 does not reinterpret the field, create labels, train,
start C1_04, create a PR, or merge.
