# C1_04 Local Learner Tooling Boundary

## Status

Approved design for implementation on `chris/c1-04-local-learner-tooling-20260914`.

Base: `9cd9314dcf62497014b930ef6bf50c1db78be5bf`

This slice establishes the local physical learner tooling boundary. It does not start training,
materialize Teacher labels, export datasets, evaluate gameplay, or alter any frozen ML contract.

## Goal

Make PyTorch the selected physical learner runtime, store only PyTorch tensor weights in a
Safetensors file bound to the existing `ArgentumCheckpointManifestV1`, and expose a local-only
Trackio metrics seam for later learner runs.

The dependency-free contract package and its existing `ScoreProvider` protocol remain usable
without importing any learner extra.

## Package boundary and versions

The existing `dependencies = []` contract remains unchanged. Add one explicit optional extra:

```toml
learner = [
    "torch==2.14.0",
    "safetensors==0.8.0",
    "trackio==0.37.1",
]
```

These versions are the current official Python-package releases checked on 2026-09-14. All
declare Python 3.13 compatibility through the package metadata or official documentation.

C1_04 does not adopt `huggingface_hub` as an Argentum learner dependency or tooling boundary. If
Trackio installs `huggingface_hub` transitively as an internal package dependency, that does not
authorize Argentum code to import, authenticate, upload, download, publish, or use Hub APIs. No
HF credentials are required for the C1_04 local smoke.

The core package initializer and core subpackage initializers do not import `torch`,
`safetensors`, `trackio`, or `huggingface_hub`. Learner modules import those packages lazily and
raise a deterministic `TOOLING_UNAVAILABLE` error naming the missing package when a tooling entry
point is used without the extra.

## Components

### PyTorch runtime boundary

Add a small `argentum_ml.learner.runtime` module. It exposes:

- a frozen `TorchRuntimeProvenance` value containing only framework name, PyTorch version,
  Python version, and CPU/CUDA availability;
- a `torch_runtime_provenance()` function that imports PyTorch lazily and returns that value;
- a deterministic optional-dependency error for missing PyTorch.

This module establishes physical runtime provenance only. It defines no model architecture,
encoder, optimizer, loss, training loop, recurrent behavior, or `ScoreProvider` implementation.
The framework-independent `ScoreProvider` protocol is unchanged.

### Safetensors weight storage

Add a reusable `argentum_ml.learner.weights` adapter with these responsibilities:

1. Accept a mapping whose values are only `torch.Tensor` objects.
2. Reject invalid tensor names, non-tensor values, sparse/non-dense or non-contiguous values,
   unsafe final paths, symlinks, directories, and non-regular output files.
3. Serialize with the official `safetensors.torch.save`/`save_file` API. `torch.save` and pickle
   are never used.
4. Read the resulting ordinary file bytes and return a small artifact value containing the path,
   SHA-256 content digest, and tensor names. The path and filename are informational only.
5. On load, read the regular file bytes, call the existing manifest's
   `validate_weight_bytes` before decoding, and decode those same verified bytes with
   `safetensors.torch.load`. This ordering prevents a changed file from being treated as the
   manifest-bound artifact and avoids a path-read/second-read time-of-check race.

The physical container identity is Argentum-owned and stable, for example
`argentum-ml-safetensors-state-dict@v1`. The adapter uses the existing manifest
`weightArtifactIdentity` and `weightContentDigest` fields; it does not add fields or change the
manifest schema/version. A valid manifest fixture binds the exact produced bytes by digest and
recomputes `checkpointId` through the existing identity algorithm. No teacher provenance field is
changed.

### Trackio observability boundary

Add a thin `argentum_ml.learner.trackio` wrapper around the public Trackio API:

- `start()` calls `trackio.init()` with a caller-supplied local project name and optional approved
  semantic-reference metadata;
- `log()` accepts only a fixed set of future learner scalar names:
  `train_loss`, `validation_loss`, `candidate_accuracy`, `samples_per_sec`, and `batches_per_sec`;
- `finish()` closes the run exactly once.

The wrapper validates finite numeric values, rejects booleans and unsupported metric names, and
permits only an explicit whitelist of Argentum-owned semantic reference fields such as
`checkpointId`, `weightContentDigest`, `sourceDatasetIdentity`, and `trainingRunIdentity` in
telemetry metadata. Each value retains the syntax defined by its owning Argentum contract; the
Trackio wrapper introduces no new identity format. It never accepts or logs raw `GameState`, hidden
opponent information, private binding channels, Teacher labels, credentials, tokens, or secrets.
The wrapper exposes no Trackio run ID as an Argentum identity.

Before initialization it rejects non-empty `TRACKIO_SPACE_ID`, `TRACKIO_SERVER_URL`,
`TRACKIO_WRITE_TOKEN`, and webhook configuration. It passes the documented local-safe settings
`auto_log_gpu=False`, `auto_log_cpu=False`, and `embed=False`, and lets tests point `TRACKIO_DIR`
to a temporary directory. The local smoke does not use HF authentication and does not contact the
Hub.

Trackio metadata is an output of the semantic identities, never an input to checkpoint ID,
weight digest, dataset identity, candidate legality, promotion, or evaluation.

## Test and command boundaries

Keep `just ml-test` and `just ml-check` dependency-free. Add `just ml-tooling-test`, which installs
the `learner` extra in the selected Python 3.13 environment and runs only the focused learner
tooling tests. The focused tests use a tiny test-only `torch.nn.Module` solely to obtain a state
dict; no accepted model architecture is introduced.

The tooling tests prove:

- core imports and the existing full ML contract suite work without learner extras;
- PyTorch import, test-only module/state-dict handling, and runtime provenance;
- Safetensors save/load, regular-file enforcement, tensor names, shapes, dtypes, and values;
- SHA-256 binding to exact bytes and rejection after tampering;
- a V1 manifest accepts the exact artifact and rejects changed bytes without weakening validation;
- no pickle or `torch.save` artifact is required;
- Trackio performs exactly one local `init`, a few scalar `log` calls, and one `finish`, while
  remote configuration is rejected and semantic IDs remain unchanged.

The existing CI `ml-contracts` job remains dependency-free. Add one isolated CPU-only
`ml-learner-tooling` job using Python 3.13.15, installing only `.[learner]`, and running the
focused tooling tests. It does not require HF credentials, GPU access, dataset services, or
network publication after package installation.

## Documentation and evidence

Extend `ml/README.md` with the core-versus-optional boundary and the authority mapping:

```text
PyTorch = learner execution
Safetensors = physical weight container
Trackio = observability
ArgentumCheckpointManifest = semantic checkpoint authority
```

Add `docs/ml/c1-04-local-learner-tooling-boundary-2026-09-14.md` after implementation. It records
the exact base/head/remote head, public package versions, Python version, core/tooling test
results, compile check, Safetensors round-trip and tamper results, and Trackio local-smoke result.
It contains no private paths, credentials, tokens, or raw game data.

## Explicit non-goals

This change does not add a model, optimizer state, loss, training loop, Behavior Cloning,
Teacher-label materialization, dataset export, Arrow/Parquet/HF Datasets, Accelerate,
Transformers, TRL, PEFT/LoRA, trajectories, RL, self-play, search, recurrent training, gameplay
evaluation, artifact publication, or C1_05 work. It does not alter Teacher scope, Selection V2,
PolicyTieRng, split semantics, variable-domain transport, model-facing contracts, or checkpoint
manifest validation.
