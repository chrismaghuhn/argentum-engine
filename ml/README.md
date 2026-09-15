# Argentum C1 ML contracts

This directory contains the dependency-free Python contract package for C1_00. Python consumes
only the Kotlin-produced derived learner artifact. It does not open `TrajectoryV1`, decide source
trust, discover source shards, run Rules validation, or materialize datasets.

The authority chain is:

```text
gym-trainer A7 TrajectoryV1Reader
  -> Kotlin C1LearnerArtifactMaterializer
  -> immutable manifest.json + samples.ndjson
  -> strict Python reader / batch / inference contracts
```

The Kotlin materialization entry point is
`C1LearnerArtifactMaterializer.materialize(publishedDatasetDirectory, outputDirectory, sourceCommit, config)`.
There is deliberately no `ml-materialize` Python command: source trust stays on the Kotlin/A7 side.

## Installation and local verification

The package targets Python 3.13.15 and has no runtime dependencies:

```powershell
cd ml
py -3.13 -m pip install --no-deps .
py -3.13 -m unittest discover -s tests -v
py -3.13 -m compileall -q src tests
```

From the repository root the equivalent recipes are:

```powershell
just ml-test
just ml-check
```

`ml-test` installs locally with `--no-deps` and runs the contract suite without accessing source
datasets or runtime services. The PEP 517 build may resolve the pinned build-only setuptools package;
after installation the contract suite itself is offline. Neither command
trains a model, contacts a dataset service, or materializes source data.

The optional learner tooling is installed and tested explicitly:

```powershell
cd ml
py -3.13 -m pip install ".[learner]"
py -3.13 -m unittest discover -s tooling_tests -v
```

From the repository root, use `just ml-tooling-test`. This path installs only the pinned learner
extra and runs the focused tooling suite. It does not train a model or access a dataset service.

## C1_07B local policy runtime

`argentum_ml.live_policy` contains the first checkpoint-backed local policy runtime. Its
`C1_07B_POLICY_PROFILE` is immutable and binds the accepted C1_06 checkpoint, manifest/weight
digests, C1_06 architecture/config, inference/selection/RNG identities, and
`C1_REFERENCE_NUMERIC_PROFILE`. `LocalPythonPolicyRuntime.start(checkpoint_dir)` launches the fixed
`python -m argentum_ml.live_policy.worker` command without a shell; the worker performs a health
handshake, validates canonical manifest bytes and Safetensors content, loads the C1_06 model on
`cuda:0`, and then serves bounded binary-framed requests until clean shutdown.

The request is the C1_07A inner semantic payload. The outer process envelope carries only the
server-owned profile/checkpoint/contract identities and request correlation. Python receives model
features, ordinal control data, masks, semantic tie discriminators, and PolicyTieRng state; it
never receives `GameState`, exact `LegalAction`/`DecisionResponse` bindings, or `bindingDigest`.
Scores and ordinal selection are fail-closed: worker crashes, timeouts, malformed frames, profile
drift, non-finite/wrong-count scores, and RNG/correlation mismatches produce typed runtime errors;
there is no CPU, random, first-choice, auto-pass, or Engine-AI fallback.

## Core and optional learner tooling

The core contract layer remains dependency-free. It contains the canonical contracts, derived
artifact reader, variable-size candidate transport, selection, checkpoint manifest, and the
framework-independent `ScoreProvider` boundary. Importing `argentum_ml`, `contracts`, `data`,
`inference`, or `checkpoint` does not import learner packages.

The optional `learner` layer is a small physical tooling boundary:

```text
PyTorch = learner execution and runtime provenance
Safetensors = physical tensor weight container
Trackio = local scalar observability
ArgentumCheckpointManifestV1 = semantic checkpoint authority
```

The learner layer has no model architecture, encoder, optimizer, loss, training loop, Teacher-label
materialization, dataset export, RL, self-play, gameplay evaluation, or recurrent training. Its
PyTorch and Safetensors imports are lazy, and missing optional packages fail with
`TOOLING_UNAVAILABLE` rather than falling back to another implementation.

Trackio receives only the approved scalar metrics and explicit Argentum-owned semantic references.
Reference values retain the syntax defined by their owning contract; Trackio defines no identity
format and its run ID is not an Argentum identity. Trackio is forced into local mode and rejects
remote Space/server/token/webhook configuration. C1_04 does not adopt `huggingface_hub` as an
Argentum dependency or tooling boundary. A transitive Trackio dependency does not authorize Hub
imports, authentication, API use, uploads, or downloads, and no HF credentials are required for
the local smoke.

## Package boundaries

- `contracts/` contains A3-compatible canonical JSON, frozen identities, and model-facing validation.
- `data/` contains the strict derived-artifact reader, episode split, and immutable variable-size transport.
- `selection/` contains PolicyTieRng V1 and Selection V2 over exact semantic source bindings.
- `checkpoint/` contains strict checkpoint identity, weight-byte integrity, and numeric-profile binding.
- `inference/` contains the model-independent `ScoreProvider` seam and fail-closed runtime.
- `learner/` contains optional PyTorch provenance, Safetensors weight I/O, and local Trackio metrics.

The provider receives only immutable admitted model input and feature views for every real candidate.
Presence and executable support remain separate. Structured inference is intentionally non-total in
C1_00 when no approved scoreable structured alternatives exist:

```text
C1_00_STRUCTURED_INFERENCE_TOTALITY=NO
```

## C1_02 public-observation Teacher

`argentum_ml.teacher.PublicObservationTeacherV1` is a separately identified, dependency-free
conformance Teacher. It scores complete flat `ACTION_CANDIDATES` and
`FOLDED_DECISION_OPTIONS` domains from public model input and generic candidate features, then
delegates exact selection to Selection V2 and PolicyTieRng V1. Source bindings are joined only
after scoring. Sample-local aliases may remain opaque public references for joins, but alias values,
ordinals, lexical order, and hashes are never scoring features.

Teacher requests are factory-only views over reader-issued C1_00 `InferenceRequest` values. The
Teacher cannot construct source bindings directly; an optional transport permutation can move only
already-authorized candidate records and must preserve their source ordinals, feature views, and
masks.

All structured families return typed `NO_LABEL` in C1_02. The Teacher does not materialize labels,
train a learner, call Rules/AI, or claim strategic quality or bootstrap admission. Its immutable
configuration digest is:

```text
TEACHER_CONFIG_DIGEST=fa358597c09ce466e1be1823de485e0accd84be622184fa1d6505806aff0d7d8
TEACHER_CONTRACT_IDENTITY=argentum-ml-teacher-bootstrap@v1
```

In that case the runtime fails before provider access and consumes zero PolicyTieRng words.

## Scope and authorization

This package is a foundation for later inference integration. The core contract path contains no
runtime dependency on PyTorch, CUDA, NumPy, Hugging Face, training loop, RL, self-play, search
implementation, or world model. C1_04 adds only the optional physical tooling boundary described
above; it still does not start training or materialize learner data.

```text
TRAINING_AUTHORIZED=NO
SMALL_LEARNER_SMOKE_AUTHORIZED=NO
RL_AUTHORIZED=NO
SELF_PLAY_AUTHORIZED=NO
SEARCH_IMPLEMENTATION_AUTHORIZED=NO
WORLD_MODEL_IMPLEMENTATION_AUTHORIZED=NO
LARGE_CORPUS_GENERATION_AUTHORIZED=NO
```
