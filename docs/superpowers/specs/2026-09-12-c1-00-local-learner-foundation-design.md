# C1_00 Local Learner Foundation Design

## Status and boundary

This design is pending independent spec review. It is not the C1_00
implementation itself, and it does not authorize an implementation plan yet.

```text
TASK=C1_00_LOCAL_LEARNER_FOUNDATION_AND_CONTRACT_IMPLEMENTATION
BASE=ce9f779bd3bd8375b6b83b9c5668b1b94ced924a
BRANCH=chris/c1-00-local-learner-foundation-20260912
CURRENT_PHASE=C1
C1_AUTHORIZED=YES
C1_IMPLEMENTATION_AUTHORIZED=YES
STATUS=PENDING_INDEPENDENT_SPEC_REVIEW
SPEC_APPROVED=NO
IMPLEMENTATION_PLAN_AUTHORIZED=NO
C1_00_IMPLEMENTATION_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
SMALL_LEARNER_SMOKE_AUTHORIZED=NO
```

The original checkout's unrelated `StackResolver.kt` change is outside this
worktree and outside this design. This slice does not change Rules semantics,
Gym semantics, TrajectoryV1, replay semantics, locked decks, or source-dataset
membership.

## Goal

Build the smallest executable local C1 contract foundation that carries an
accepted, published TrajectoryV1 dataset through the A7 reader into a
deterministic, regenerable, non-authoritative learner artifact and then through
a model-independent inference seam:

```text
TrajectoryV1Reader.openPublishedDataset
    -> deterministic Kotlin materializer
    -> derived JSONL + strict derived manifest
    -> Python strict derived-artifact reader
    -> variable-size domain/batch view
    -> injected finite score provider
    -> Selection V2
    -> PolicyTieRng V1 for unresolved exact symmetry
    -> exact semantic source binding
```

Checkpoint manifests are implemented as strict identity/validation primitives
with fixture weight bytes only. No neural model, optimizer, loss, training run,
Teacher admission, or gameplay experiment is part of this slice.

## Authority and data flow

### Kotlin source boundary

The Kotlin bridge is the only source-data entry point. A materializer in
`gym-trainer` calls `TrajectoryV1Reader.openPublishedDataset(...)` and consumes
the returned `ValidatedTrajectoryDatasetV1.streamEpisodes()` sequence. It does
not glob directories, repair malformed JSON, revalidate source trust in Python,
or construct a second source-membership policy.

The reader remains responsible for finalized manifest membership, A7 preflight,
shard validation, trajectory validation, duplicate collection-job rejection, and
quarantine exclusion. The materializer only derives a view from the accepted
episodes it receives.

### Derived artifact

The materializer writes a disposable artifact consisting of:

```text
manifest.json       canonical strict derived-manifest JSON
samples.ndjson      one canonical UTF-8 JSON object per learner sample
```

`manifest.json` binds the derived-view schema, all C0 contract identities, the
source dataset ID and manifest digest, the TrajectoryV1 schema identity, the
materializer implementation/source-commit identity, the fixed
`episode-ordinal-ascending` enumeration policy, and both episode and sample
counts:

```text
episodeCount
episodeCountsByPartition { TRAIN, VALIDATION, TEST }
sampleCount
sampleCountsByPartition { TRAIN, VALIDATION, TEST }
```

It also binds the exact sample-file digest and byte/record counts. An accepted
source episode with zero materialized policy samples remains visible in the
episode counts. It never uses paths, mtimes, hostnames, PIDs, worker IDs,
wall-clock values, or completion order as semantic identity.

The physical bytes are frozen independently of the host platform:

```text
manifest.json = UTF8(A3SemanticJson.canonicalJson(manifest))
manifest.json has no BOM and no trailing newline

samples.ndjson = for each sample in deterministic order:
    UTF8(A3SemanticJson.canonicalJson(sample)) || 0x0A

FINAL_RECORD_HAS_LF=YES
PLATFORM_NEWLINE_API=FORBIDDEN
```

The materializer writes bytes directly; it does not use platform text-mode
newline conversion.

Each sample has physically separate top-level channels:

```text
sourceReference
input
target
binding
provenance
```

`input` contains only the C0-01-admitted observation and complete current-domain
feature/structural view. `target`, source references, raw inverse bindings, and
episode/dataset provenance cannot be nested inside it. The binding channel
retains enough exact source information to recover the chosen semantic action or
response without using a physical batch row as meaning.

The derived artifact has an explicit immutable identity separate from its
manifest's physical location:

```text
DERIVED_VIEW_SCHEMA_IDENTITY=argentum-ml-derived-learner-view@v1
DERIVED_ARTIFACT_IDENTITY_CONTRACT_ID=argentum-ml-derived-artifact-id@v1

derivedArtifactIdentityPayload = {
    "schema": "argentum-ml-derived-artifact-id@v1",
    "derivedViewSchemaIdentity": "argentum-ml-derived-learner-view@v1",
    "sourceDatasetId": ...,
    "sourceManifestContentDigest": ...,
    "trajectorySchemaIdentity": "argentum-trajectory@v1",
    "modelFacingContractIdentity": "argentum-ml-model-facing-decision-sample@v1",
    "splitContractIdentity": "argentum-ml-dataset-split@v1",
    "materializerImplementationIdentity": {
        "implementation": ...,
        "sourceCommit": ...
    },
    "materializerConfigDigest": ...,
    "samplesContentDigest": ...,
    "episodeCountsByPartition": { "TRAIN": ..., "VALIDATION": ..., "TEST": ... },
    "sampleCountsByPartition": { "TRAIN": ..., "VALIDATION": ..., "TEST": ... }
}

derivedArtifactId =
    SHA-256(UTF8(A3SemanticJson.canonicalJson(derivedArtifactIdentityPayload)))
```

`derivedArtifactId` excludes `manifestContentDigest`, `derivedArtifactId`
itself, physical file references, paths, and other operational metadata. The
manifest integrity field is independently defined as:

```text
manifestContentDigest =
    SHA-256(UTF8(A3SemanticJson.canonicalJson(
        manifest with manifestContentDigest omitted
    )))
```

This removes self-reference while still detecting manifest-byte changes.

The materializer preserves every source candidate and every typed structured
domain, including unaffordable/non-executable placeholders and intentional
multiplicity. It emits explicit presence and executable-support masks rather
than truncating, top-k filtering, flattening structured decisions, or inventing
fixed-width Magic action IDs. Interrupted accepted episodes may contribute
policy-prefix samples, but no synthetic terminal/value target is created;
failed/quarantined episodes produce no ordinary learner samples.

### Python boundary

`ml/` is a standard installable Python package targeting CPython 3.13.15. It
accepts only the derived manifest/JSONL artifact. It never opens a raw
TrajectoryV1 directory and never decides whether a source trajectory is trusted.
Runtime dependencies are standard-library only; no PyTorch, HF Datasets/Arrow,
Transformers, Accelerate, Hub client, TRL, PEFT, Ray, Lightning, Hydra,
Safetensors, or Trackio is introduced here.

## Components

### Kotlin materializer

Planned focused production units under
`gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/`:

- `C1LearnerContractsV1.kt` — derived schema identities, partition enum,
  source/reference envelopes, separated sample channels, and strict manifest
  data types.
- `C1DatasetSplitV1.kt` — exact semanticEpisodeId hash preimage, unsigned
  big-endian extraction and modulo-100 mapping; expected KAT values live in
  focused tests, not in production source.
- `C1ModelFacingProjectionV1.kt` — explicit C0-01 observation/domain admission,
  perspective normalization, sample-local relation aliases, complete binding,
  and fail-closed handling of unsupported fields.
- `C1LearnerArtifactMaterializer.kt` — A7 reader composition, deterministic
  episode/decision traversal, canonical NDJSON writing, digest/count accounting,
  and derived-manifest construction.

These units use existing A3 canonical JSON and existing typed source contracts.
They do not alter `TrajectoryV1`, `PlayerObservationV1`,
`CompleteLegalDomainV1`, or any Rules/Gym producer.

### Python package

The package is intentionally small and split by responsibility:

```text
ml/
  pyproject.toml
  .python-version
  README.md
  src/argentum_ml/
    __init__.py
    contracts/
      canonical_json.py
      identities.py
      model_facing.py
    data/
      derived_reader.py
      split.py
      variable_batch.py
    selection/
      policy_tie_rng.py
      selection_v2.py
    checkpoint/
      manifest.py
    inference/
      provider.py
      runtime.py
  tests/
```

The Python contracts are strict: unknown versions, unknown fields, missing
required fields, malformed digests, invalid masks, non-finite scores, score
shape mismatches, incomplete inverse bindings, and incompatible selection/RNG
pairs fail closed.

`ScoreProvider` receives only the model input and the currently supplied
scoreable candidate/prefix feature views. It cannot receive the chosen target,
source outcome, Teacher answer, provenance IDs, raw engine state, or raw
runtime IDs. Its output must contain exactly one finite score for each actual
scoreable entry.

`variable_batch.py` transports item boundaries, domain boundaries, presence and
selectability masks, typed structured domains, padding masks when used, and
exact source-binding references. Padding is never gameplay content and no
candidate truncation operation exists.

`selection_v2.py` implements unique argmax, deterministic semantic tie
resolution, and unresolved exact-max sampling only through `PolicyTieRng V1`.
No first-row fallback, physical-row tie break, global/engine/training RNG,
softmax, temperature, epsilon-greedy, AutoPay, or implicit structured completion
is available.

The deterministic-tie boundary is explicit. Selection accepts an optional
`deterministicSemanticTieDiscriminator` supplied by the C0-04-authorized source
binding; it is not generated from a physical row or from a model feature. It
must be invariant under candidate permutation and uniquely order the exact
maximal candidates. If it is absent, invalid, or non-unique, Selection V2 goes
directly to unresolved PolicyTieRng sampling.

```text
SOURCE_BINDING_ORDINAL_AS_MODEL_FEATURE=NO
SOURCE_BINDING_ORDINAL_AS_DETERMINISTIC_PREFERENCE=NO
SOURCE_BINDING_ORDINAL_AS_UNIFORM_SAMPLE_ADDRESS=YES

FORBIDDEN_TIE_DISCRIMINATORS=
    raw EntityId, row index, source ordinal, actionId, decisionId,
    allocation order, batch slot, hash-map iteration, or runtime artifact order
```

The sample-local source-binding ordinal is only the inverse address used after
the uniform sampler returns an unbiased member address. It never affects which
member is considered semantically first.

`policy_tie_rng.py` implements the accepted stream-key payload, signed-Long
bit-pattern conversion, SHA-256 raw-word preimage, cursor/exhaustion rules,
rejection-sampling `uniformBelow`, snapshot/restore, and by-value fork.

Policy provenance is gated before any seed bits are consumed:

```text
POLICY_SEED_REUSE_ALLOWED_ONLY_WHEN=
    policyRngIdentity == argentum-ml-policy-tie-rng@v1

CURRENT_A9_POLICY_SEED_AS_POLICY_TIE_RNG_SEED=NO
POLICY_TIE_RNG_STATE_SCOPE=one semantic episode x one policy instance
```

The legacy `explicit-seed/kotlin-policy-state-v1` identity is never
retroactively interpreted as PolicyTieRng V1. C1_00 KATs use explicit
PolicyTieRng test provenance or a directly defined `PolicyTieRngStateV1`.
Each episode/policy instance receives a fresh state object; the stream-key
payload still excludes `semanticEpisodeId`, `actualEngineSeed`, and hidden-world
identity exactly as required by C0-04B.

`checkpoint/manifest.py` implements strict
`argentum-ml-checkpoint-manifest@v1` parsing, accepted artifact kinds,
selection/RNG compatibility pairs, exact weight-byte digest binding, canonical
identity recomputation, and rejection of unknown fields/versions/kinds. Its
strict manifest model mirrors the complete C0-04 field set:

```text
manifest contract/version
policyArtifactKind
modelImplementationIdentity
modelArchitectureIdentity
modelConfigDigest
modelFacingContractIdentity
candidateScoringContractIdentity
splitContractIdentity
sourceDatasetIdentity
recurrentSequenceContractIdentity
vocabularyIdentity
weightArtifactIdentity
weightContentDigest
inferenceContractIdentity
selectionContractIdentity
requiredNumericProfileClass
policyRngContractIdentity
trainingRecipeIdentity
trainingRunIdentity
parentCheckpointIdentity
teacherBootstrapProvenance
```

Only `FEED_FORWARD_POLICY` and `RECURRENT_POLICY` are recognized artifact kinds;
unknown kinds fail closed. It does not load model tensors or claim a usable
checkpoint.

`inference/runtime.py` composes a model-facing sample, an injected
`ScoreProvider`, a strict `NumericExecutionProfileIdentity`, Selection V2, and
the policy RNG into one exact semantic source binding. It does not produce a
live engine action ID or duplicate Rules legality.

### Numeric execution profile boundary

The model-independent seam validates the C0-04 profile identity without
selecting a framework, dtype, device, kernel, or backend:

```text
NUMERIC_EXECUTION_PROFILE_CONTRACT_ID=
    argentum-ml-numeric-execution-profile@v1
C1_00_NUMERIC_PROFILE_IDENTITY_BINDING=YES
C1_00_NUMERIC_BACKEND_CERTIFICATION=NO
```

`requiredNumericProfileClass` is a strict checkpoint/inference compatibility
field and is part of the checkpoint semantic identity. Cross-backend
equivalence, PyTorch behavior, CUDA behavior, and numeric certification remain
deferred to a later scorer/checkpoint slice.

## Design-review follow-up state

The seven local P2 findings from the independent spec review are resolved in
this follow-up without changing the architecture or reopening C0:

```text
P1=0
P2=0
P2_1=RESOLVED  # explicit pending-review status and no plan authorization
P2_2=RESOLVED  # exact manifest/NDJSON UTF-8 and LF framing
P2_3=RESOLVED  # derivedArtifactId and non-self-referential identity payload
P2_4=RESOLVED  # explicit C0 semantic tie-discriminator boundary
P2_5=RESOLVED  # exact PolicyTieRng provenance gate and state scope
P2_6=RESOLVED  # separate episode and sample partition counts
P2_7=RESOLVED  # numeric execution profile identity binding

C0_CONTRACT_REOPEN_REQUIRED=NO
C1_AUTHORIZED=YES
C1_IMPLEMENTATION_AUTHORIZED=YES
SPEC_APPROVED=NO
IMPLEMENTATION_PLAN_AUTHORIZED=NO
C1_00_IMPLEMENTATION_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
SMALL_LEARNER_SMOKE_AUTHORIZED=NO
```

This document is ready for written spec re-review, not for implementation.

## Determinism and identity

All Kotlin-derived bytes use the existing A3 canonical JSON convention and
UTF-8. Record order is manifest-owned episode order followed by source decision
order. Split membership is inherited from the exact C0-02 formula:

```text
SHA-256(UTF-8("argentum-ml-dataset-split@v1\n" + lowercase semanticEpisodeId))
first eight digest bytes as unsigned big-endian uint64
modulo 100
0..79 TRAIN, 80..89 VALIDATION, 90..99 TEST
```

Python canonical JSON has committed cross-language fixtures against A3. It does
not rely on dictionary insertion order, filesystem order, process identity, or
Python object representation.

Checkpoint identity is the SHA-256 of the accepted canonical semantic identity
payload, including contract bindings, model/config identity, lineage fields,
selection/RNG pair, weight-artifact identity, and exact weight-content digest.
Filename, path, mtime, `latest`, `best`, provider URL, or run number cannot define
identity.

The C0-04 identity payload also binds `requiredNumericProfileClass` and the
`argentum-ml-numeric-execution-profile@v1` contract identity. The runtime must
validate that field before Selection V2 is reachable.

## Error handling

The materializer rejects the whole source sample or operation with a typed,
reviewable error when an admitted C0 field cannot be represented without
inventing semantics. It never drops the field, uses `toString()`, chooses the
first value, repairs a domain, adds hidden identity, or silently replaces an
unsupported response.

The Python reader rejects unknown derived schema versions and unknown fields,
manifest/file digest mismatches, partition-count mismatches, duplicate source
references, target-outside-domain bindings, privacy-channel violations, and
malformed structured-domain relations. Failed source episodes are not converted
to ordinary records. Interrupted records never receive a value of zero as a
synthetic terminal label.

Selection failures consume no RNG word before validation. Rejected
uniform-sampler words consume their cursor positions. Cursor exhaustion is a
hard failure with no wraparound.

## Verification plan

Implementation proceeds test-first, with each primitive following RED, focused
GREEN, and refactor-only-after-green:

1. Split KATs and episode/group leakage tests.
2. Derived manifest strictness, deterministic regeneration, source-order
   preservation, and A7 reader-seam tests.
3. Input/target separation, privacy exclusions, raw-ID exclusion, complete
   candidate/domain preservation, structured-domain preservation, and
   symmetric-feature equivalence tests.
4. Variable-domain batch boundary, mask, permutation, and binding-transport
   tests.
5. PolicyTieRng stream/raw-word/uniform/rejection/cursor/seat/fork/restore KATs.
6. Selection V2 unique-max, semantic-tie, unresolved-tie, finite-score,
   empty/mismatch/unselectable, permutation, and batch-invariance tests.
7. Checkpoint manifest exact identity, weight digest, strict schema, unknown
   field/version, compatibility-pair, and path/filename-independence tests.
8. Model-independent inference seam tests proving only source semantic bindings
   emerge from valid scores.

All Python tests are offline and use small committed fixtures. Kotlin tests use
the existing `gym-trainer` test conventions and do not generate a large corpus.

## CI and developer surface

The implementation adds a focused `ml-contracts` pull-request job that installs
the local package with no runtime dependencies and runs the offline Python
contract suite under Python 3.13.15. The existing `backend` aggregate continues
to keep its current check name and additionally requires the focused ML job.

Developer commands are added only if they do not conflict with the existing
`justfile`, with separate commands for Python contract tests, Python checks, and
the focused Kotlin materializer tests. Heavy Kotlin work continues through the
repository's `just`/Gradle-lock discipline.

Documentation added by the implementation will explain A7 trust flow,
materialization/regeneration, local ML tests, Selection V2/PolicyTieRng use,
checkpoint identity, and the still-unauthorized training/Teacher/model gates.

## Explicit non-goals and acceptance boundary

This design does not authorize or implement:

```text
neural model, optimizer, gradient update, loss, Behavior Cloning, learner smoke,
value head, recurrence, RL, self-play, search, MCTS, world model, Teacher
selection/admission, large corpus, performance campaign, deck/rules/Gym changes,
TrajectoryV1 schema changes, or final checkpoint/model-pipeline acceptance
```

The expected implementation result is:

```text
C1_00_IMPLEMENTATION_PASS=YES
C1_00_READY_FOR_ACCEPTANCE=YES
C1_00_FINAL_ACCEPTANCE_PASS=NO
LOCAL_MINI_MODEL_PIPELINE_PASS=NO
FIRST_CHECKPOINT_PASS=NO
TEACHER_BOOTSTRAP_ADMITTED=NO
TRAINING_AUTHORIZED=NO
SMALL_LEARNER_SMOKE_AUTHORIZED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
```
