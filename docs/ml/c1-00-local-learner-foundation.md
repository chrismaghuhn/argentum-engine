# C1_00 Local Learner Foundation

```text
CURRENT_PHASE=C1
SPEC_APPROVED=YES
IMPLEMENTATION_PLAN_AUTHORIZED=YES
C1_AUTHORIZED=YES
C1_IMPLEMENTATION_AUTHORIZED=YES
TRAINING_AUTHORIZED=NO
SMALL_LEARNER_SMOKE_AUTHORIZED=NO
```

This document records the implemented C1_00 boundary. The normative semantic contracts remain in
the C0 documents; this page describes how the repository components compose them.

## Authority and ownership

`gym-trainer` A7 remains the only source-trust authority. The Kotlin bridge opens a published
dataset through `TrajectoryV1Reader.openPublishedDataset(publishedDatasetDirectory)`, which
preflights the source manifest and shard plan before `streamEpisodes()` is consumed.

The Kotlin `C1LearnerArtifactMaterializer` projects accepted episodes and decisions into a disposable,
regenerable derived artifact. Python consumes that artifact and never revalidates TrajectoryV1 trust,
walks source directories, reads raw shards, or selects a source action.

```text
A7 TrajectoryV1 / Kotlin trust
  -> Kotlin derived projection and materializer
  -> immutable derived artifact
  -> strict Python reader
  -> immutable variable-domain transport
  -> model-independent Selection V2 inference seam
```

Relevant normative references:

- [C0 model-facing sample and candidate scoring](c0-model-facing-sample-and-candidate-scoring-contract-v1.md)
- [C0 split and frozen evaluation](c0-split-and-frozen-evaluation-contract-v1.md)
- [C0 PolicyTieRng and symmetry resolution](c0-policy-rng-and-symmetry-resolution-contract-v1.md)
- [C0 checkpoint identity and deterministic inference](c0-checkpoint-identity-and-deterministic-inference-contract-v1.md)
- [C0 recurrent reset and derived view](c0-sequence-reset-and-recurrent-derived-view-contract-v1.md)

## Derived artifact bytes and identities

The physical derived view is:

```text
derivedViewSchemaIdentity=argentum-ml-derived-learner-view@v1
samplesContentReference=samples.ndjson
```

`manifest.json` is canonical UTF-8 JSON with no BOM, no CR/LF, and no trailing newline. Its
`manifestContentDigest` is SHA-256 over canonical JSON of the manifest with that field omitted.

Each `samples.ndjson` record is:

```text
UTF8(A3CanonicalJson(sample)) || 0x0A
```

Every record ends in exactly one LF. BOM, CR, blank lines, duplicate JSON keys, non-canonical JSON,
and unreferenced or malformed content fail closed. The reader recomputes byte count, SHA-256, sample
count, partition counts, and source-reference uniqueness from the actual file.

The immutable `derivedArtifactId` preimage contains the versioned derived-view identity, source
dataset/manifest identities, model-facing and split identities, materializer identity/config digest,
sample content digest, and both episode/sample partition-count objects. Physical paths and aliases are
not identity inputs.

Episode and sample counts are intentionally separate:

```text
episodeCount
episodeCountsByPartition { TRAIN, VALIDATION, TEST }
sampleCount
sampleCountsByPartition { TRAIN, VALIDATION, TEST }
```

An admitted episode with zero policy samples remains visible in episode counts. Partition assignment
is episode-level and is recomputed from the lowercase `semanticEpisodeId` using the C0_02 split
contract; every sample from that episode carries the same partition.

## Python reader and model-facing input

`DerivedArtifactReader.open()` accepts only the fixed `manifest.json` and `samples.ndjson` files and
keeps iteration bound to the validated artifact bytes. It validates exact manifest/sample shapes,
source dataset and manifest bindings, complete source domains, alias inverse maps, target/source
containment, current structured-domain versions, canonical line bytes, and all count/digest contracts.

The model receives only the admitted `input` channels:

```text
decisionContext
observation
domain
```

Target, source-reference, binding, provenance, policy-seed, outcome, routing, and raw runtime IDs do
not enter the provider graph. Sample-local aliases and SELF/OPPONENT roles preserve joins without
turning runtime identity into a policy preference. The complete source domain and exact source target
remain in the binding/target channels for later semantic validation.

All twelve structured-domain families are retained and version-gated. C1_00 does not flatten them,
invent a missing response, or call Rules code from Python. Without an approved scoreable structured
prefix or complete-response alternative, inference fails closed before provider access and consumes no
RNG words:

```text
C1_00_STRUCTURED_INFERENCE_TOTALITY=NO
```

## Variable-size transport and inference

`VariableDomainBatch` preserves every supplied candidate. `item_offsets`, `candidate_offsets`, and
padding masks describe physical transport only; there is no truncation, top-k, or hidden candidate.
Candidate records and source ordinals travel together under permutation, and transport JSON trees are
deeply immutable.

The `ScoreProvider` seam is model-independent and receives immutable model input plus one feature view
for every real candidate, including unaffordable candidates. `candidatePresence` and
`candidateExecutableSupport` are separate. The source reader/transport boundary requires every real
flat candidate to be present and binds executable support to the source-authoritative `affordable`
flag; padding is not a real candidate.

Selection V2 performs exact argmax over executable present candidates, rejects non-finite or
count-mismatched scores, uses a source-issued semantic discriminator only when C0-authorized, and
otherwise uses PolicyTieRng V1 only for an unresolved exact tie. The RNG stream is keyed by the
explicitly declared PolicyTieRng identity and never by a legacy A9 seed identity. The result is an
`ExactSemanticSourceBinding`, not an ordinal-only action.

The inference context is created from a validated checkpoint manifest and a matching
`NumericExecutionProfileIdentity`. The provider must declare the same checkpoint ID and required
numeric-profile class before scoring.

## Checkpoint and numeric profile boundary

`ArgentumCheckpointManifestV1` is strict and deeply immutable. It binds the exact manifest contract,
artifact kind, model implementation/source commit, model-facing/scoring/split contracts, source
dataset identity, weight artifact identity and SHA-256 weight bytes, selection/RNG compatibility,
recurrent sentinel where applicable, and required numeric profile class. Checkpoint identity excludes
the stored `checkpointId` and manifest `version` from the payload and adds
`schema=argentum-ml-checkpoint-id@v1` before A3 canonicalization and SHA-256.

The numeric profile class is a compatibility input, not backend certification. C1_00 does not certify
PyTorch, CUDA, cross-device behavior, or a concrete tensor container. Those belong to a later concrete
provider/loader task.

## Local commands and CI

From the repository root:

```powershell
just ml-test
just ml-check
```

On Windows, the pinned-runtime equivalent is:

```powershell
cd ml
py -3.13 -m pip install --no-deps .
py -3.13 -m unittest discover -s tests -v
py -3.13 -m compileall -q src tests
```

The `ml-contracts` GitHub Actions job uses Python 3.13.15, installs the local package without runtime
dependencies, runs the offline test suite, and compile-checks `src` and `tests`. The existing
`backend` aggregate check remains and now requires both the Kotlin/backend matrix and `ml-contracts`.

No source materialization command is exposed from `just` or Python. The reviewed Kotlin entry point is
`C1LearnerArtifactMaterializer.materialize(publishedDatasetDirectory, outputDirectory, sourceCommit, config)`
and remains covered by its focused Kotlin tests.

## Explicitly out of scope

```text
TRAINING_AUTHORIZED=NO
SMALL_LEARNER_SMOKE_AUTHORIZED=NO
RL_AUTHORIZED=NO
SELF_PLAY_AUTHORIZED=NO
SEARCH_IMPLEMENTATION_AUTHORIZED=NO
WORLD_MODEL_IMPLEMENTATION_AUTHORIZED=NO
LARGE_CORPUS_GENERATION_AUTHORIZED=NO
```

C1_00 provides contracts, deterministic data transport, selection/RNG machinery, checkpoint identity,
and a model-independent inference seam only. Task 11 verification and independent code review remain
separate gates; no training or learner evaluation is implied by local contract-test success.
