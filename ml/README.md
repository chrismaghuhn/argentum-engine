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

## Installation and offline verification

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

`ml-test` installs locally with `--no-deps` and runs the offline contract suite. Neither command
trains a model, contacts a dataset service, or materializes source data.

## Package boundaries

- `contracts/` contains A3-compatible canonical JSON, frozen identities, and model-facing validation.
- `data/` contains the strict derived-artifact reader, episode split, and immutable variable-size transport.
- `selection/` contains PolicyTieRng V1 and Selection V2 over exact semantic source bindings.
- `checkpoint/` contains strict checkpoint identity, weight-byte integrity, and numeric-profile binding.
- `inference/` contains the model-independent `ScoreProvider` seam and fail-closed runtime.

The provider receives only immutable admitted model input and feature views for every real candidate.
Presence and executable support remain separate. Structured inference is intentionally non-total in
C1_00 when no approved scoreable structured alternatives exist:

```text
C1_00_STRUCTURED_INFERENCE_TOTALITY=NO
```

In that case the runtime fails before provider access and consumes zero PolicyTieRng words.

## Scope and authorization

This package is a foundation for later inference integration. It contains no PyTorch, CUDA, NumPy,
Hugging Face, training loop, learner smoke, RL, self-play, search implementation, or world model.

```text
TRAINING_AUTHORIZED=NO
SMALL_LEARNER_SMOKE_AUTHORIZED=NO
RL_AUTHORIZED=NO
SELF_PLAY_AUTHORIZED=NO
SEARCH_IMPLEMENTATION_AUTHORIZED=NO
WORLD_MODEL_IMPLEMENTATION_AUTHORIZED=NO
LARGE_CORPUS_GENERATION_AUTHORIZED=NO
```
