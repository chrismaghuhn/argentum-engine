# C0 checkpoint identity and deterministic inference contract V1

## 1. Status and authority

```text
TASK=C0_04_CHECKPOINT_IDENTITY_AND_DETERMINISTIC_INFERENCE_CONTRACT
DATE=2026-09-12
STATUS=DRAFT_SPECIFICATION_PENDING_INDEPENDENT_EXACT_SHA_REVIEW
AUDIT_BASE=57a22d64711c760c274b7f366a58ac56bdda7b09
ORIGIN_MAIN_AT_AUDIT=57a22d64711c760c274b7f366a58ac56bdda7b09
UPSTREAM_MAIN_AT_AUDIT=3f46367d87c88bcf156a843a9e69fd29e1693872
ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM=https://github.com/wingedsheep/argentum-engine.git
UPSTREAM_INTEGRATED=NO
WRITABLE_REPOSITORY=chrismaghuhn/argentum-engine

C0_04_IMPLEMENTATION_AUTHORIZED=YES
C0_04_SCOPE=DOCUMENTATION_ONLY
PRODUCTION_CODE_CHANGED=NO
RULES_CODE_CHANGED=NO
GYM_SEMANTICS_CHANGED=NO
TRAJECTORY_SCHEMA_CHANGED=NO
MODEL_CODE_CHANGED=NO
CHECKPOINTS_CREATED=NO
TRAINING_STARTED=NO
C1_STARTED=NO

CHECKPOINT_MANIFEST_CONTRACT_ID=argentum-ml-checkpoint-manifest@v1
INFERENCE_CONTRACT_ID=argentum-ml-inference@v1
NUMERIC_EXECUTION_PROFILE_CONTRACT_ID=argentum-ml-numeric-execution-profile@v1
SELECTION_CONTRACT_ID=argentum-ml-policy-selection@v1
MODEL_FACING_CONTRACT_ID=argentum-ml-model-facing-decision-sample@v1
RECURRENT_SEQUENCE_CONTRACT_ID=argentum-ml-recurrent-sequence@v1
SPLIT_CONTRACT_ID=argentum-ml-dataset-split@v1
```

The audit base is the verified merge commit of C0-03 / PR #177:

```text
C0_03_BASE=bf425d40c7a22710d0b3d9a52330661b68275bd4
C0_03_HEAD=00ea654c3a36a02fdbf9c488e5b49a8b1176a713
C0_03_MERGE_COMMIT=57a22d64711c760c274b7f366a58ac56bdda7b09
```

`origin/main` was fetched and equals `AUDIT_BASE`. PRs #175, #176 and #177 are merged. Upstream is
recorded for provenance only and was not integrated.

The accepted state is:

```text
COMMANDER_ENVIRONMENT_V1_COMPLETE=YES
PHASE_A_FINAL_ACCEPTANCE_PASS=YES
B0_FINAL_ACCEPTANCE_PASS=YES
B1_FINAL_ACCEPTANCE_PASS=YES
B2_FINAL_ACCEPTANCE_PASS=YES
DATA_TRUSTED=YES
C0_01_FINAL_ACCEPTANCE_PASS=YES
C0_02_FINAL_ACCEPTANCE_PASS=YES
C0_03_FINAL_ACCEPTANCE_PASS=YES
CURRENT_PHASE=C0
C0_AUTHORIZED=YES
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
SELF_PLAY_AUTHORIZED=NO
LARGE_CORPUS_GENERATION_AUTHORIZED=NO
PRE_C1_PERFORMANCE_GOOD_ENOUGH=YES
PERFORMANCE_WORK=ON_DEMAND_ONLY
```

This document freezes checkpoint, inference, numeric-profile, selection and policy-RNG boundaries.
It does not implement any of them. Its specification gates are source-backed design decisions;
final acceptance remains pending independent exact-SHA review.

## 2. Scope and ownership

C0-04 makes a future policy artifact reproducible without allowing the model or its checkpoint to
become a second Rules engine:

```text
ARGENTUM
  rules
  legal domain
  perspective-safe observation
  environment transition
  replay and data trust

MODEL
  scores / structured policy output

INFERENCE CONTRACT
  converts valid model output into one semantic choice

CHECKPOINT MANIFEST
  identifies the exact policy artifact and its compatible contracts
```

The frozen dependency chain is:

```text
immutable checkpoint identity
  + accepted C0-01 policy input
  + accepted C0-03 recurrent state/history when applicable
  + declared numeric execution profile
  + explicit selection mode
  + declared policy RNG state when stochastic
  -> reproducible semantic chosen action/response
```

This task does not implement model architecture, model weights, checkpoint serialization/loading,
training, Behavior Cloning, RL, self-play, C1, Teacher selection, value/reward semantics, PyTorch,
JAX, Safetensors, HF Hub, HF Datasets, or a production DTO.

## 3. Accepted C0 dependencies

The checkpoint must consume, without weakening:

| Contract | Required use |
| --- | --- |
| [`c0-model-facing-sample-and-candidate-scoring-contract-v1.md`](c0-model-facing-sample-and-candidate-scoring-contract-v1.md) | Exact input feature admission, complete legal domain, candidate scoring, structured choice and semantic label binding. |
| [`c0-split-and-frozen-evaluation-contract-v1.md`](c0-split-and-frozen-evaluation-contract-v1.md) | Exact dataset/split provenance, immutable offline test and frozen gameplay jobs. |
| [`c0-sequence-reset-and-recurrent-derived-view-contract-v1.md`](c0-sequence-reset-and-recurrent-derived-view-contract-v1.md) | Recurrent stream identity, state ownership, reset, previous-choice, causality, window and burn-in semantics. |
| `TrajectoryV1` | Authoritative accepted source episode and policy/data provenance. |
| `A3SemanticJson` | Existing strict canonical JSON and SHA-256 convention for conceptual identity preimages. |
| current replay contracts | Factual game/replay proof only; replay checkpoints are not model checkpoints. |

C0-04 does not redefine:

```text
model-facing feature admission
candidate/domain semantics
structured decisions
semantic chosen-label binding
80/10/10 split mapping
final-test identity
gameplay evaluation cells
recurrent stream ownership
per-perspective state isolation
reset semantics
previous-choice semantics
window/burn-in semantics
source-teacher-forced offline recurrent evaluation
```

## 4. Source audit

The exact source authority includes:

| Source | Audited boundary |
| --- | --- |
| [`TrajectoryV1.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1.kt) | `EnvironmentIdentityV1`, `RosterSeatV1`, `PolicyProvenanceV1`, `EpisodeMetadataV1`, `DecisionRecordV1`, `TrajectoryV1`, `DatasetMetadataV1`, `DatasetManifestV1`, IDs and validation. |
| [`TrajectoryV1Manifest.kt`](../../gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Manifest.kt) | Canonical dataset identity/content digest; not a model checkpoint identity. |
| [`A3SemanticJson.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/A3SemanticJson.kt) | Strict JSON, recursive key sorting, explicit canonical bytes and SHA-256 helper. |
| [`SchemaHash.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt) | Named current Gym contract identity; it is not a computed schema digest. |
| [`CompactReplay.kt`](../../game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/CompactReplay.kt) | Game replay setup, action input, yields and replay checkpoints. |
| [`ReplayFingerprint.kt`](../../game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayFingerprint.kt) | Rules-state/replay fingerprint; it is not a model-weight identity. |
| [`ApprenticeArtifact.kt`](../../ai/src/main/kotlin/com/wingedsheep/ai/training/ApprenticeArtifact.kt) | Existing small heuristic artifact; lacks C0 contract bindings and content identity, so it is not C0 checkpoint authority. |
| [`c0-model-facing-sample-and-candidate-scoring-contract-v1.md`](c0-model-facing-sample-and-candidate-scoring-contract-v1.md) | Accepted model-facing contract and deferred C0-04 decisions. |
| [`c0-split-and-frozen-evaluation-contract-v1.md`](c0-split-and-frozen-evaluation-contract-v1.md) | Accepted split/evaluation contract. |
| [`c0-sequence-reset-and-recurrent-derived-view-contract-v1.md`](c0-sequence-reset-and-recurrent-derived-view-contract-v1.md) | Accepted recurrent state/history contract. |
| [`docs/ai/training-data.md`](../../docs/ai/training-data.md) | Legacy Phase-9/ECL artifact boundary; not C0 model/checkpoint authority. |
| Issue [#124](https://github.com/chrismaghuhn/argentum-engine/issues/124) | C0/C1 roadmap authority. |
| Issue [#137](https://github.com/chrismaghuhn/argentum-engine/issues/137) | Replaceable physical learner/artifact tooling boundary. |
| Issue [#119](https://github.com/chrismaghuhn/argentum-engine/issues/119) | Dataset/storage provenance and scaling context only where relevant. |

### 4.1 Current identities and meanings

| Current field/identity | Current meaning | C0-04 treatment |
| --- | --- | --- |
| `EnvironmentIdentityV1.engineCommit` | Exact engine source bound to the episode environment. | Environment provenance; checkpoint compatibility input, never a model feature. |
| `cardDefinitionIdentity` | Exact card-definition corpus identity. | Compatibility/provenance; changes require a new checkpoint identity when bound. |
| `akiriDeckIdentity` / `chevillDeckIdentity` | Exact locked curriculum decks. | Compatibility/provenance; no deck-name inference. |
| ordered `RosterSeatV1` | Seat, player, role, deck and commander binding. | Compatibility/provenance; no arbitrary seat-number feature. |
| `startingPlayer` / `actualEngineSeed` | Reproducible environment setup. | Environment binding; not policy RNG and not model input. |
| `PolicyProvenanceV1.behaviorPolicyIdentity` | Collection behavior-policy identity. | Source provenance; not a C0 checkpoint identity by itself. |
| `PolicyProvenanceV1.opponentPolicyIdentity` | Collection opponent-policy identity. | Source provenance; evaluation opponent binding remains C0-02 authority. |
| `behaviorPolicyRole` / `opponentPolicyRole` | Episode-level role strings. | Preserve exactly; do not infer per-seat checkpoint ownership from names. |
| `policyRngIdentity` / `policySeed` | Current collection-policy RNG/state provenance. | Not automatically future model-inference RNG; C0-04 defines a separate boundary. |
| `policySourceIdentity` | Collection-policy source identity. | Provenance; current source is not a model implementation identity. |
| `semanticEpisodeId` | Environment-derived semantic episode identity. | C0-02 split group; not a checkpoint or live policy identity. |
| `collectionJobId` | Semantic episode plus policy-provenance collection identity. | Source job provenance; not model feature or split authority. |
| `trajectoryId` | Exact trajectory identity from episode/job/decisions/closure. | Recurrent stream source binding; not a model feature. |
| `DecisionRecordV1.decisionIndex` | Global source chronology. | Input/history provenance; not a model feature. |
| `observationBefore` | Perspective-safe current observation. | C0-01 model input authority. |
| `completeLegalDomain` | Complete current legal action/decision domain. | C0-01 selection/mask authority. |
| `chosenSemanticAction` / `chosenSemanticResponse` | Accepted source semantic choice. | Label or prior accepted choice; never current-step hidden input. |
| `CompactReplayLinkV1` / replay checkpoints | Replay content/range and Rules-state proof linkage. | Factual replay provenance; never model-weight identity. |
| `DatasetManifestV1.datasetId` / content digest | Exact source dataset population. | Training/evaluation provenance; not a model feature. |
| `ApprenticeArtifact.modelId` | Existing heuristic artifact label. | Not sufficient as immutable C0 checkpoint identity. |
| `ApprenticeArtifact` coefficients | Existing linear heuristic weights. | Legacy artifact surface; no C0 input/selection/profile binding. |

The existing `ApprenticeArtifactLoader` uses a legacy set-scoped JSON artifact and fallback-to-existing-
evaluator behavior. That is intentionally different from C0 strict checkpoint loading. The existing
AI `EvaluationWeights`/`ApprenticeArtifact` values are not a source-backed C0 model-facing policy,
checkpoint, numeric profile or selection contract.

The current accepted schema anchors audited for compatibility are:

```text
PLAYER_OBSERVATION_V1_SCHEMA_IDENTITY=argentum-gym-player-observation@v1
COMPLETE_LEGAL_DOMAIN_SCHEMA_IDENTITY=argentum-gym-action-domain@v2
CANDIDATE_DOMAIN_DIGEST_SCHEMA_IDENTITY=argentum-gym-candidate-domain-digest@v1
GYM_WIRE_SCHEMA_IDENTITY=argentum-gym-contract@v1.26-repeat-count-domain
TRAJECTORY_V1_SCHEMA_IDENTITY=argentum-trajectory@v1
COMPACT_REPLAY_SCHEMA_IDENTITY=argentum-compact-replay@v6 for current new recordings
```

Historical replay schema identities remain source-dispatched compatibility cases; they do not
change the model-checkpoint identity rules. Existing replay checkpoints/fingerprints are state-proof
coordinates and are not weight or policy-artifact identities.

## 5. Checkpoint manifest concept

Define the documentation-level manifest concept:

```text
ArgentumCheckpointManifestV1
CHECKPOINT_MANIFEST_CONTRACT_ID=argentum-ml-checkpoint-manifest@v1
```

It is a semantic manifest, not a production DTO in this task. It identifies an exact policy artifact,
its inference-compatible semantic contracts and its lineage. The manifest must be strict: unknown
versions, unknown kinds, missing required bindings and incompatible optional-field shapes fail closed.

The manifest distinguishes:

```text
checkpointId
  exact lineage-bound inference artifact identity

weightArtifactContentDigest
  SHA-256 of exact physical weight artifact bytes

trainingRunIdentity
  immutable training lineage/provenance reference when trained

live recurrent state
  ephemeral state for one C0-03 stream, never checkpoint content
```

### 5.1 Policy artifact kinds

V1 recognizes only:

```text
FEED_FORWARD_POLICY
RECURRENT_POLICY
```

Future `POLICY_VALUE`, `WORLD_MODEL` or other kinds are not interpreted as a policy checkpoint
under this contract. Unknown kinds fail closed. C0-04 adds no value or reward semantics.

Feed-forward compatibility uses an explicit sentinel:

```text
recurrentSequenceContractIdentity=NONE_FOR_FEED_FORWARD
```

Recurrent compatibility requires:

```text
recurrentSequenceContractIdentity=argentum-ml-recurrent-sequence@v1
```

plus the C0-03 reset/history semantics. An architecture name cannot substitute for this binding.

## 6. Checkpoint semantic fields and identity preimage

The conceptual manifest binds the following fields. `required for inference` means required to load
and execute the policy under an accepted runtime; `provenance only` means it cannot enter model
features, even when it is included in the immutable checkpoint identity.

| Field | Semantic meaning | Required for inference? | In `checkpointId` preimage? | Provenance only? | Model feature? | Change requires new checkpoint identity? |
| --- | --- | --- | --- | --- | --- | --- |
| manifest contract identity/version | Which manifest grammar is being interpreted. | YES | YES | NO | NO | YES |
| `checkpointId` | Stored digest of the complete accepted checkpoint identity payload. | YES as verified output | NO, self-excluded | NO | NO | N/A |
| `policyArtifactKind` | `FEED_FORWARD_POLICY` or `RECURRENT_POLICY`. | YES | YES | NO | NO | YES |
| model implementation/source identity | Exact model implementation identity plus immutable source commit. | YES | YES | NO | NO | YES |
| model architecture identity | Exact compatible architecture family/config identity. | YES | YES | NO | NO | YES |
| model configuration identity/digest | Canonical semantic model configuration. | YES | YES | NO | NO | YES |
| model-facing contract identity | C0-01 input/feature contract. | YES | YES | NO | NO | YES |
| candidate-scoring contract identity | Candidate/domain scoring semantics. | YES | YES | NO | NO | YES |
| split contract identity | C0-02 split/provenance contract. | NO for raw execution | YES | YES | NO | YES |
| source dataset identity | Exact trusted source population used where trained. | NO for raw execution | YES | YES | NO | YES |
| recurrent sequence contract identity | C0-03 stream/reset/history semantics, or explicit feed-forward sentinel. | CONDITIONAL | YES | NO | NO | YES |
| vocabulary/tokenizer identity | Exact semantic category/token processing mapping. | CONDITIONAL | YES | NO | NO | YES |
| weight artifact identity/container identity | Physical artifact/container semantics. | YES | YES | NO | NO | YES |
| weight content digest | SHA-256 of exact artifact bytes. | YES | YES | NO | NO | YES |
| inference contract identity | Numeric/output execution semantics. | YES | YES | NO | NO | YES |
| selection contract identity | Argmax/tie/structured-selection semantics. | YES | YES | NO | NO | YES |
| required numeric profile class | Minimum certified numeric execution class. | YES | YES | NO | NO | YES |
| policy RNG contract identity | Exact stochastic selection algorithm, or deterministic sentinel. | CONDITIONAL | YES | NO | NO | YES |
| training recipe/config identity | Semantic training recipe used to produce the artifact. | NO for execution | YES | YES | NO | YES |
| training run identity | Immutable lineage identity for the training run. | NO for execution | YES | YES | NO | YES |
| parent checkpoint identity | Explicit parent lineage, or null for a root. | NO for execution | YES | YES | NO | YES |
| Teacher/bootstrap provenance | Reserved C0-05/C1 provenance reference, or null when absent. | NO for execution | YES when declared | YES | NO | YES when declared |
| logical parameter-set identity | Container-independent tensor identity. | NO in v1 | NO | NO | NO | New contract only |
| optimizer/scheduler/RNG resume state | Training continuation state. | NO | NO | YES for resume only | NO | NO to inference identity |
| local path/mtime/alias/provider/PID/GPU UUID | Operational locator or human convenience. | NO | NO | YES | NO | NO |

The v1 choice is deliberately conservative: declared training lineage is part of the exact
lineage-bound checkpoint identity. Two byte-identical artifacts with different declared training
run, parent, dataset or recipe provenance are distinct checkpoint identities even if their behavior
is expected to be equivalent. A separate lineage record may still reference the same physical bytes.
This prevents a model-comparison report from silently collapsing distinct provenance.

## 7. Model configuration and architecture identity

The architecture identity must be sufficient to locate compatible inference code. It is not merely
`neural network` or `LSTM model`. Conceptually it binds:

```text
architecture family identity
encoder configuration
candidate scorer configuration
recurrent core configuration when recurrent
hidden-state shape/configuration when recurrent
output/structured-decoder configuration
initial recurrent-state semantics
feature/vocabulary bundle identity
```

C0-04 does not choose LSTM, GRU, Transformer, RWKV, Mamba, attention-only memory or any other
architecture, nor hidden dimension, layer count, memory length, heads or dtype. These are model
configuration values supplied by a future authorized artifact.

The model configuration digest is:

```text
MODEL_CONFIG_DIGEST=
  SHA-256(UTF-8(A3SemanticJson.canonicalJson(modelConfigPayload)))
```

`modelConfigPayload` is a versioned semantic JSON object with explicit fields and explicit nulls for
not-applicable optional values. `A3SemanticJson`'s strict JSON, recursive key ordering, compact
canonical representation and UTF-8 digest convention are reused. `toString()`, map iteration,
framework reprs and Python dict reprs are not preimages. Unknown model-config versions fail closed.

The architecture/config payload includes the initial-state mode:

```text
fixed zero initial state
or
learned initial-state parameter
```

No choice between those modes is made here. If learned, the parameter is part of the exact weight
artifact; if fixed, the fixed semantics are part of model configuration.

## 8. Exact checkpoint identity and physical weight integrity

The v1 logical parameter-set identity is not defined because the repository has no generic tensor
canonicalization or model implementation. The exact physical artifact is the authority:

```text
LOGICAL_PARAMETER_SET_IDENTITY=DEFERRED
WEIGHT_ARTIFACT_CONTENT_DIGEST_REQUIRED=YES
WEIGHT_ARTIFACT_CONTENT_DIGEST=SHA-256(exact physical artifact bytes)
```

The physical container remains replaceable and is owned by #137. `WEIGHT_CONTAINER` is not
`CHECKPOINT_SEMANTIC_IDENTITY`; the manifest records both the exact container/artifact identity and
the exact byte digest. Re-serialization can therefore create a new exact checkpoint identity even
when policy behavior is expected to be equivalent.

The conceptual checkpoint identity preimage is:

```text
checkpointIdentityPayload = {
  "schema": "argentum-ml-checkpoint-id@v1",
  "manifestContractIdentity": "argentum-ml-checkpoint-manifest@v1",
  "policyArtifactKind": "FEED_FORWARD_POLICY" | "RECURRENT_POLICY",
  "modelImplementationIdentity": { "sourceCommit": ..., "implementation": ... },
  "modelArchitectureIdentity": ...,
  "modelConfigDigest": ...,
  "modelFacingContractIdentity": "argentum-ml-model-facing-decision-sample@v1",
  "candidateScoringContractIdentity": ...,
  "splitContractIdentity": "argentum-ml-dataset-split@v1",
  "sourceDatasetIdentity": ...,
  "recurrentSequenceContractIdentity": "NONE_FOR_FEED_FORWARD" | "argentum-ml-recurrent-sequence@v1",
  "vocabularyIdentity": ... | null,
  "weightArtifactIdentity": { "container": ..., "artifact": ... },
  "weightContentDigest": ...,
  "inferenceContractIdentity": "argentum-ml-inference@v1",
  "selectionContractIdentity": "argentum-ml-policy-selection@v1",
  "requiredNumericProfileClass": ...,
  "policyRngContractIdentity": "NONE_FOR_DETERMINISTIC_MODE" | ...,
  "trainingRecipeIdentity": ... | null,
  "trainingRunIdentity": ... | null,
  "parentCheckpointIdentity": ... | null,
  "teacherBootstrapProvenance": ... | null
}

checkpointId =
  SHA-256(UTF-8(A3SemanticJson.canonicalJson(checkpointIdentityPayload)))
```

The `checkpointId` field is not present in its own preimage. The payload uses exact field names,
strict types, explicit absence values and no unknown fields. Local path, mtime, creation/upload time,
hostname, PID, GPU UUID, provider run number, Hub URL and human alias are excluded. A mutable alias
may resolve to this ID but cannot replace it. The `...` markers above stand for the exact typed
field values supplied by a future concrete manifest; they do not mean that fields may be omitted or
left unspecified. Optional non-applicable values are explicit `null`, while feed-forward and
deterministic-RNG cases use the declared sentinel strings.

## 9. Checkpoint provenance versus model input

Checkpoint provenance includes source commit, dataset/split, training recipe, parent and Teacher
references. None is a model feature:

```text
CHECKPOINT_PROVENANCE_AS_MODEL_INPUT=NO
```

The C0-01 input remains only the accepted perspective-safe observation/domain/choice boundary. A
checkpoint cannot gain hidden state, opponent cards, future replay or Rules authority by carrying
more provenance.

## 10. Inference checkpoint versus training-resume snapshot

```text
INFERENCE_CHECKPOINT != TRAINING_RESUME_SNAPSHOT
```

An inference checkpoint contains the exact policy artifact and semantic/provenance bindings needed
for inference. A training-resume snapshot may additionally contain:

```text
optimizer state
scheduler state
gradient-scaler state
training step
sampler state
training RNG states
```

Those fields are not automatically inference checkpoint semantics. If a future resume artifact is
used for inference, it must expose an explicit inference-checkpoint view and undergo the same strict
identity/compatibility checks.

Live per-stream recurrent state is never checkpoint content:

```text
LIVE_RECURRENT_STATE_IN_CHECKPOINT=NO
```

## 11. Strict compatibility validation

Before inference, a future loader validates:

```text
manifest contract/version
checkpoint ID recomputation
policy artifact kind
model implementation/source identity
architecture/config identity and digest
model-facing contract identity
candidate-scoring contract identity
vocabulary/tokenizer identity
split/source dataset provenance where required
recurrent sequence contract for recurrent artifacts
weight artifact content digest
required tensor presence
absence of unexpected incompatible tensors
tensor shape
dtype/profile compatibility
inference contract identity
selection contract identity
required numeric profile certification
policy RNG contract when stochastic
```

The loader is strict by default:

```text
CHECKPOINT_LOAD_STRICT=YES
UNKNOWN_CHECKPOINT_VERSION=FAIL_CLOSED
```

Unknown manifest/config versions, missing or unexpected tensors, digest mismatch, shape mismatch,
incompatible dtype/profile, unsupported recurrent contract or unsupported selection mode fail closed.
No `strict=false`, missing-key ignore, automatic reshape, implicit migration or best-effort load is
accepted.

## 12. Deterministic inference contract

```text
INFERENCE_CONTRACT_ID=argentum-ml-inference@v1
```

The semantic requirement is:

```text
same exact checkpoint
  + same C0-01 model-facing input
  + same C0-03 recurrent input state/history when recurrent
  + same accepted numeric execution profile
  + same selection mode
  + same policy RNG state when stochastic
  -> same semantic chosen action/response
```

This is semantic policy selection, not a promise that arbitrary providers, kernels or floating-point
backends produce identical bytes. The required target is:

```text
SEMANTIC_SELECTION_REPRODUCIBILITY=REQUIRED_WITHIN_DECLARED_PROFILE
```

Score bytes are diagnostic rather than Rules authority:

```text
SCORE_BITWISE_REPRODUCIBILITY=
  NOT_REQUIRED_WITH_REASON: semantic selection is the contract target;
  exact-profile runs may report bitwise score equality as additional evidence
```

Within one exact certified profile, any score divergence that changes semantic selection is a
failure of that profile's certification. Across profiles, equal choices are evidence only after a
conformance campaign.

## 13. Numeric execution profile

The conceptual profile contract is:

```text
NUMERIC_EXECUTION_PROFILE_CONTRACT_ID=
  argentum-ml-numeric-execution-profile@v1
```

Concrete package versions and physical tool choices are not frozen. A future profile must classify
each property as a semantic execution requirement, certification provenance or operational-only
fact.

| Runtime property | Classification | Exact match? | Possible selection impact | Contract treatment |
| --- | --- | --- | --- | --- |
| Framework identity/version | Certification provenance | YES within exact profile | Operator/kernel and reduction behavior. | Bind to profile; do not assume framework equivalence. |
| Runtime language/JVM/Python version where material | Certification provenance | YES within exact profile | Serialization and numeric library behavior. | Bind when evidence shows material impact. |
| CPU/GPU backend | Semantic execution requirement plus certification provenance | YES for exact profile; cross-profile conditional | Floating-point and kernel differences. | `CROSS_BACKEND_EQUIVALENCE=NOT_ASSUMED`. |
| dtype | Semantic execution requirement | YES | Rounding and representable scores. | No silent conversion. |
| mixed precision/autocast | Semantic execution requirement | YES | Dynamic casts and reductions. | Explicit profile field. |
| TF32 state where relevant | Semantic execution requirement | YES | Matrix-product rounding. | Explicit on/off profile value. |
| matmul precision | Semantic execution requirement | YES | Matrix-product result and ties. | Explicit profile value. |
| deterministic-algorithm mode | Semantic execution requirement | YES | Kernel choice and repeatability. | Explicit profile value. |
| compiler/graph-compile mode | Certification provenance; semantic if it changes results | CONDITIONAL | Fusion/reordering/kernel choice. | Certify; never assume. |
| kernel/library versions | Certification provenance | CONDITIONAL | Backend implementation and reductions. | Bind where material. |
| thread/reduction configuration | Certification provenance; semantic if result changes | CONDITIONAL | Reduction order and score drift. | Certify; no hidden global setting. |
| inference/evaluation mode | Semantic execution requirement | YES | Dropout/training-only randomness. | Must be evaluation-equivalent. |
| device capability class | Certification provenance | CONDITIONAL | Kernel availability/precision. | Bind for certified profiles. |
| batch composition/order | Semantic invariance requirement | NO specific value | Must not change chosen semantics. | `BATCH_COMPOSITION_AS_POLICY_SEMANTICS=NO`. |
| provider/cloud/local label | Operational only | NO | None semantically. | `PROVIDER_AS_POLICY_FEATURE=NO`; profile still binds actual backend. |
| hostname/PID/GPU UUID/path | Operational only | NO | None semantically. | Never checkpoint or policy identity. |

## 14. Profile identity and backend certification

A concrete runtime profile may be content-addressed from its declared versioned properties using the
same strict canonical JSON convention. It is distinct from the checkpoint identity:

```text
checkpoint identity
  -> artifact/config/lineage identity

numeric profile identity
  -> execution conditions used by one run
```

```text
CROSS_BACKEND_EQUIVALENCE=NOT_ASSUMED
```

Conceptual `CheckpointInferenceCertificationV1` evidence may bind:

```text
checkpointId
inferenceContractId
numericExecutionProfileId
conformance corpus identity
semantic selection equivalence result
```

It is not implemented here. `EXACT_PROFILE_REPRODUCTION` and `CROSS_PROFILE_SEMANTIC_COMPATIBILITY`
remain distinct claims. Equal choices on a conformance corpus do not establish universal
cross-profile equivalence.

## 15. Batch and candidate invariance

Unrelated batch companions are not policy semantics:

```text
BATCH_COMPOSITION_AS_POLICY_SEMANTICS=NO
BATCH_INVARIANT_SEMANTIC_SELECTION=YES
```

For the same checkpoint, sample and recurrent state, semantic selection is invariant under unrelated
batch membership, batch order and environment-worker order. A profile that cannot provide this
invariance is not certified. Batch slot, worker ID and physical tensor offset are never model
features or tie-break identities.

For semantically unordered C0-01 candidate domains:

```text
physical candidate permutation
  -> scores permute correspondingly
  -> selected semantic choice remains equivalent
```

```text
CANDIDATE_PERMUTATION_INVARIANCE=YES
ARBITRARY_ROW_ORDER_AS_TIE_BREAK=NO
TIE_BREAK_BY_PHYSICAL_ROW_ORDER=NO
```

Rules-significant ordered domains preserve their source-defined order. Candidate presence and
executable support remain separate: all real supplied candidates are retained, while the source
support mask determines which can be selected.

## 16. Inference output validity

At every flat candidate boundary, the model must provide exactly one finite score for every actual
supplied candidate. Padding is excluded by the source/mask contract. The output may not:

```text
omit a real candidate
add an invented candidate
truncate the complete domain
change candidate meaning
attach a score to the wrong semantic candidate
```

Structured inference must provide equivalent typed completeness for every policy-owned component.
The current legal domain, source executable-support mask and C0-01 semantic binding are the
authority. An unaffordable placeholder never becomes executable because its score is high.

## 17. Non-finite scores and shape mismatch

```text
NON_FINITE_POLICY_SCORE=FAIL_CLOSED
```

`NaN`, `+Inf` and `-Inf` are invalid policy outputs. They are not clamped, replaced with zero,
treated as negative infinity or repaired with a first candidate.

Fail closed on:

```text
missing candidate score
extra candidate score
candidate/mask count mismatch
candidate mask mismatch
wrong structured output family
unsupported decoder state
score attached to the wrong semantic candidate
invalid executable-support mask
```

No positional repair, domain clipping or hidden fallback is allowed.

## 18. Selection modes

The selection contract is:

```text
SELECTION_CONTRACT_ID=argentum-ml-policy-selection@v1
SELECTION_MODE_INITIAL=DETERMINISTIC_ARGMAX
```

The initial deterministic mode selects the highest finite score among source-authorized executable
choices. It consumes no policy RNG:

```text
DETERMINISTIC_ARGMAX_CONSUMES_POLICY_RNG=NO
DETERMINISTIC_POLICY_INFERENCE_SUPPORTED=YES
```

Scores are finite comparable policy scores within one decision boundary. They are not automatically
probabilities, calibrated utilities, Q-values or value estimates.

## 19. Exact ties and semantic tie keys

The default tie definition is exact equality under the declared score representation:

```text
TIE=exact equality under the accepted numeric profile's score representation
```

No fuzzy epsilon may be introduced or tuned from evaluation outcomes. For an exact tie, deterministic
mode may use a source-authoritative semantic discriminator only when it is unique and invariant to
allowed physical row permutation and runtime-ID renaming. A conceptual tie key is:

```text
canonical source-semantic candidate or typed-alternative key
```

The key is selection machinery, not a model feature. It cannot be a live `actionId`, batch index,
worker order, runtime allocation number, raw object handle or unbound `EntityId`. If a unique
source-semantic discriminator is unavailable, deterministic selection fails closed:

```text
UNRESOLVED_DETERMINISTIC_TIE=FAIL_CLOSED
```

It never silently uses the first row or becomes stochastic. A future stochastic selection contract
may resolve it only with an explicitly supported policy RNG.

For structured decisions, tie keys are typed semantic options/prefixes under the source domain.
There is no global integer-option tie vocabulary. If a structured prefix has no unique accepted
semantic discriminator, the decoder fails closed rather than heuristically completing it.

### Deterministic baseline totality

The deterministic selection mode is defined, but its totality over the policy-owned Environment V1
decision surface is not established by this source audit. C0-01 explicitly permits distinct
semantic choices to have identical feature representations, including perfectly symmetric choices
that no invariant deterministic selector can distinguish.

Therefore:

```text
C0_TIE_BREAK_CONTRACT=PASS
  failure behavior is defined and remains fail-closed

C0_DETERMINISTIC_SELECTION_CONTRACT=CHANGES_REQUIRED_FOR_TOTAL_POLICY
C0_DETERMINISTIC_INFERENCE_CONTRACT=CHANGES_REQUIRED_FOR_TOTAL_POLICY
C0_04_DETERMINISTIC_BASELINE_READY=NO
OPEN_C0_BLOCKERS=TIE_BREAK_IDENTITY_GAP
```

`C0_04A_ENVIRONMENT_V1_TIE_TOTALITY_CHARACTERIZATION` must determine whether every reachable
policy-owned tie has a permitted invariant semantic discriminator or whether a separately accepted
policy-RNG/symmetry-resolution contract is required. Physical row order, raw `EntityId`, candidate
JSON containing runtime IDs and batch indexes remain forbidden shortcuts.

## 20. Structured decoding

Flat candidates, folded decision options and structured choices use the same authority split:

| Domain | Score source | Mask authority | Deterministic selection | RNG use |
| --- | --- | --- | --- | --- |
| Flat action candidates | Model candidate scorer, one score per supplied candidate. | C0-01 complete domain and executable-support mask. | Finite argmax, semantic tie key or fail closed. | None in v1. |
| Folded decision options | Model scorer over supplied semantic decision options. | C0-01 folded domain and executable support. | Finite argmax, semantic tie key or fail closed. | None in v1. |
| Structured choices | Model-owned typed component/prefix or complete-response scorer. | Exact source typed domain and completion predicate. | Finite typed argmax, semantic tie key or fail closed. | None in v1. |

Structured decoding must preserve full payment, target, combat, ordering and other C0-01 semantics.
It may not deterministically score a root and then choose payment, target or order with AutoPay,
cheapest-source, first-legal, lexicographic-row or random fallback. A dead-end prefix or invalid
semantic response is a visible failure.

## 21. Policy RNG boundary

The three RNG domains remain separate:

```text
ENGINE_RNG != POLICY_INFERENCE_RNG != TRAINING_RNG
```

The engine RNG is Argentum environment authority. `PolicyProvenanceV1.policySeed` currently records
collection-policy provenance; it is not silently promoted to a future model-inference RNG. Training
initialization, minibatch shuffle, dropout, augmentation and optimizer randomness are training
provenance, not policy selection randomness.

No exact reusable stochastic policy RNG algorithm is currently defined by the repository or the
accepted C0 contracts. V1 deliberately supports deterministic selection only:

```text
POLICY_RNG_CONTRACT_ID=NONE_FOR_DETERMINISTIC_MODE
STOCHASTIC_POLICY_INFERENCE_SUPPORTED=NO
STOCHASTIC_UNSUPPORTED_BEHAVIOR=FAIL_CLOSED
```

If a future stochastic mode is accepted, its versioned contract must freeze:

```text
exact RNG algorithm/version
seed representation
stream identity
initialization/derivation
cursor/state semantics
draw consumption
reset semantics
sampling algorithm
probability conversion
```

The future scope is:

```text
POLICY_RNG_SCOPE=one environment episode x one policy instance
```

Each player gets an independent RNG state. There is no cross-player or cross-episode cursor,
batch-slot ownership or initialization from wall clock, PID, thread, Python/NumPy/framework/CUDA
global state or filesystem path. The evaluation job supplies the concrete RNG seed/state; the
checkpoint supplies only a supported RNG contract.

## 22. Inference mode and hidden framework randomness

Checkpoint execution must declare an inference/evaluation mode equivalent to framework evaluation
mode, with training-only stochastic components disabled. Accepted deterministic inference must not
silently depend on:

```text
dropout active in inference
random augmentation
unseeded sampling
randomized kernels
Python random
NumPy global RNG
framework global RNG
CUDA RNG
```

Any intentional stochastic model internals require separately owned explicit RNG streams. No such
architecture is selected by C0-04.

## 23. Contract bindings for inference

Checkpoint compatibility requires the exact accepted identities:

```text
MODEL_FACING_CONTRACT_ID=argentum-ml-model-facing-decision-sample@v1
SPLIT_CONTRACT_ID=argentum-ml-dataset-split@v1
RECURRENT_SEQUENCE_CONTRACT_ID=argentum-ml-recurrent-sequence@v1 when recurrent
```

A feed-forward checkpoint uses `NONE_FOR_FEED_FORWARD` for recurrent sequence identity. A recurrent
checkpoint missing the C0-03 sequence/reset/history identity is invalid. A checkpoint built for a
different model-facing, candidate-scoring, split, vocabulary or recurrent contract cannot run
silently.

Vocabulary/tokenizer identity is required whenever categorical or free-text processing is used:

```text
INFERENCE_VOCAB_RECONSTRUCTION_FROM_DATA=NO
VOCABULARY_IDENTITY_REQUIRED=YES_WHEN_APPLICABLE
```

Environment-declared vocabulary and explicit unknown-visible semantics remain C0-01/C0-02
authority. Unknown schema/version is fail-closed, never a generic unknown card/category.

## 24. Evaluation provenance

C0-02 frozen offline/gameplay evaluation binds at minimum:

```text
exact checkpointId
inference contract identity
numeric execution profile identity
selection mode
policy RNG contract identity
policy RNG seed/state when stochastic
```

For recurrent evaluation, C0-03 additionally remains authoritative:

```text
exact episode-start reset
per-policy-instance hidden state
source-teacher-forced offline recurrent evaluation
model-executed history during real gameplay
```

Inference decision evidence may retain:

```text
checkpoint identity
inference/profile/selection identities
policy RNG cursor/state reference when stochastic
model-facing source decision identity
complete legal-domain identity/digest
chosen semantic action/response
```

Scores are optional derived diagnostics, not Rules authority or canonical Trajectory source data:

```text
MODEL_SCORE_TRACE=OPTIONAL_DERIVED_DIAGNOSTIC
GAMEPLAY_REPLAY != MODEL_INFERENCE_REPLAY
```

A replay can reproduce factual game transitions from engine setup and semantic choices without
rerunning the model. Reproducing why a model selected a choice requires checkpoint, inference and
numeric-profile provenance.

## 25. A/B comparison binding

For a model-only A/B comparison, hold constant:

```text
frozen evaluation jobs
environment and opponent
C0-01/C0-02/C0-03 input contracts
numeric execution profile
selection mode
policy RNG contract and job seeds where relevant
```

Change only:

```text
policy-under-test checkpoint identity
```

If backend/profile, selection mode, RNG seed, opponent or any contract changes, the result is not a
model-only causal comparison without separate characterization:

```text
MODEL_ONLY_CAUSAL_CLAIM=NO
```

## 26. Compatibility with the current environment

The checkpoint binds the accepted generic model-facing and domain contracts, not a single engine
seed or starting-player outcome. It must nevertheless declare compatibility with the current
environment/curriculum identity whenever model vocabulary, card definitions, candidate/domain
schemas or observation semantics depend on them. An incompatible observation schema, card corpus,
candidate/domain version or curriculum is rejected unless an explicit compatibility contract exists.

The checkpoint must not hard-code an `Akiri` or `Chevill` output neuron. A checkpoint may be certified
only for the locked curriculum while retaining generic artifact/contract terminology. Curriculum
expansion requires an explicit compatibility/certification change.

## 27. Dtype, quantization and optimized execution

Loading a checkpoint into another dtype is not silent compatibility:

```text
AUTOMATIC_DTYPE_CONVERSION=FORBIDDEN
```

A cast is allowed only when the target numeric profile explicitly declares it and the profile has
the required semantic-selection certification. A profile change must be visible in evaluation
provenance.

Quantized artifacts are not the same exact checkpoint as their source precision by default:

```text
FP32 artifact != INT8 artifact
QUANTIZATION_CHANGE_REQUIRES_EXPLICIT_IDENTITY=YES
```

Quantization implementation is deferred. Compilation and optimized kernels such as graph compilation,
XLA-like execution or custom kernels may alter numeric results. They are profile provenance and need
conformance evidence before accepted deterministic evaluation. A near-tie difference that changes
the selected semantic choice means the profiles are not semantically equivalent for that input set;
it is not repaired with a broad post-hoc epsilon.

## 28. Replay boundary and artifact transport

Existing `CompactReplay`/`ReplayFingerprint` checkpoints prove reconstructed game state at replay
coordinates. They are not ML model checkpoints and cannot replace `checkpointId`, weight digests or
inference provenance:

```text
GAMEPLAY_REPLAY != MODEL_INFERENCE_REPLAY
```

A physical cloud object, Hub URL, filesystem path or other transport locator may carry a weight
artifact only through:

```text
checkpointId + exact content digest
  -> transport
  -> retrieve
  -> verify digest and manifest
  -> strict load
```

Transport never becomes identity or trust authority. An unsafe container capable of arbitrary code
execution during load requires a separate security review; #137 owns physical container selection.

## 29. Future conformance obligations

These obligations belong to a future implementation and are not reported as executed by C0-04:

```text
same checkpoint/input/profile repeated -> same semantic selection
different batch composition -> same semantic selection
unordered candidate permutation -> equivalent semantic selection
same recurrent history replay -> same semantic selection
weight/config corruption -> rejection
unknown checkpoint/profile version -> rejection
NaN/Inf -> rejection
candidate-count/mask mismatch -> rejection
exact tie -> semantic tie result or fail closed
hidden framework RNG -> detected/forbidden
```

If stochastic inference is ever separately supported, also prove:

```text
same policy RNG seed/state -> same samples
different players -> independent RNG state
new episode -> declared RNG reset/derivation
no draw-consumption drift
batch order -> no RNG stream reassignment
unsupported RNG version -> fail closed
```

No conformance corpus, checkpoint, tensor, learner or model implementation is created here.

## 30. Deterministic inference examples

### 30.1 Flat candidates

Suppose a source complete domain supplies three candidates in physical order `[C, A, B]`:

```text
candidate C: score=0.92, executable=false
candidate A: score=0.81, executable=true,  semanticTieKey=K_A
candidate B: score=0.81, executable=true,  semanticTieKey=K_B
```

`C` is not selectable despite its larger score because executable support is source authority. `A`
and `B` tie exactly; deterministic selection compares their unique source-semantic tie keys. If
`K_A` is lexicographically before `K_B`, the chosen semantic candidate is `A`, independent of the
physical row order. The first array row is never an allowed tie-break.

### 30.2 Exact tie without a discriminator

```text
A score=1.0
B score=1.0
```

If no accepted unique source-semantic discriminator exists:

```text
UNRESOLVED_DETERMINISTIC_TIE=FAIL_CLOSED
```

No first-row choice and no random fallback is allowed.

### 30.3 Backend profiles

The same checkpoint and model-facing input run under a certified CPU profile and an uncertified GPU
profile. Equal output choices do not make the profiles equivalent automatically:

```text
same checkpoint + same input
  + CPU profile -> choice A
  + GPU profile -> choice A

cross-profile certification = NOT_ESTABLISHED without a conformance campaign
```

If a declared conformance corpus proves equal semantic choices, that is evidence for the exact
profile pair only; it is not an assumption about all devices, kernels or inputs.

### 30.4 Recurrent input

```text
same checkpoint
same current observation
```

is insufficient to reproduce a recurrent decision without the same accepted prior C0-03 input
history or an exact checkpoint-bound hidden-state cache. C0-03 owns that history/state semantics;
C0-04 does not serialize the state into `TrajectoryV1`.

### 30.5 Unsupported stochastic selection

```text
STOCHASTIC_SAMPLE requested
no accepted policy RNG algorithm
  -> FAIL_CLOSED
```

The deterministic mode is specified without stochastic inference, but total baseline readiness
remains blocked by the unresolved Environment V1 tie-totality question:

```text
C0_04_DETERMINISTIC_MODE_DEFINED=YES
C0_04_DETERMINISTIC_BASELINE_READY=NO
C0_04_ALL_FUTURE_STOCHASTIC_MODES_READY=NO
```

## 31. Versioning

A new checkpoint or inference-contract version is required when semantic meaning changes in:

```text
checkpoint manifest fields
checkpoint identity preimage
model/config identity rules
weight integrity semantics
vocabulary binding
candidate selection semantics
tie-breaking semantics
non-finite score handling
numeric profile compatibility
policy RNG semantics
structured decoder semantics
```

Physical relocation alone does not create a new semantic contract. Changing actual weights, model
configuration, implementation source, declared inference contracts, lineage or exact physical
artifact bytes creates a new lineage-bound checkpoint identity under this v1 rule.

## 32. Failure behavior and blocker taxonomy

Future checkpoint loading/inference fails closed on:

```text
unknown checkpoint manifest version
unknown policy artifact kind
checkpoint ID mismatch
weight content digest mismatch
missing/incompatible model config
missing tensor
unexpected incompatible tensor
tensor shape mismatch
dtype/profile mismatch
vocabulary/tokenizer mismatch
model-facing contract mismatch
candidate/domain contract mismatch
recurrent sequence contract mismatch
unsupported numeric profile
unsupported selection mode
NaN/Inf output
candidate-count or executable-mask mismatch
unresolved deterministic tie
unsupported stochastic RNG contract
missing policy RNG state when stochastic
structured decoder dead end
invalid semantic response
```

No hidden fallback, partial load, automatic reshape/cast, candidate truncation, AutoPay, first legal
choice, random retry or heuristic structured completion is permitted. A loaded but uncertified
numeric profile is:

```text
TRUSTED_EVALUATION=BLOCKED
```

The focused blocker classes are:

```text
CHECKPOINT_IDENTITY_GAP
MODEL_CONFIG_IDENTITY_GAP
WEIGHT_IDENTITY_GAP
VOCABULARY_IDENTITY_GAP
INFERENCE_CONTRACT_GAP
NUMERIC_EXECUTION_PROFILE_GAP
TIE_BREAK_IDENTITY_GAP
POLICY_RNG_ALGORITHM_GAP
STRUCTURED_INFERENCE_SELECTION_GAP
CHECKPOINT_COMPATIBILITY_GAP
```

The absence of a stochastic RNG algorithm is not a deterministic-baseline blocker because
`STOCHASTIC_POLICY_INFERENCE_SUPPORTED=NO` is explicit and deterministic argmax fails closed on
unsupported requests.

## 33. Identity dependency graph

```text
C0-01 model-facing contract
  + model architecture/config identity
  + vocabulary/tokenizer identity
  + C0-03 recurrent contract when applicable
  + exact physical parameter artifact
  + checkpoint lineage/provenance
  -> immutable lineage-bound checkpointId
```

```text
checkpointId
  + inference contract
  + accepted numeric execution profile
  + selection mode
  + policy RNG state when stochastic
  -> reproducible policy execution
```

No checkpoint identity node grants Rules, legality, observation visibility or replay authority.

## 34. Evaluation dependency graph

```text
C0-02 frozen evaluation job
  + immutable checkpoint identity
  + accepted inference profile
  + selection contract
  + policy RNG seed/state where relevant
  + C0-03 stream/reset/history semantics when recurrent
  -> policy run
  -> semantic choices
  -> factual Argentum transitions
  -> frozen structural/behavior/gameplay report
```

No layer redefines lower-layer authority. `MODEL_SCORE_TRACE` remains optional derived diagnostics;
the engine remains the authority for accepted actions and transitions.

## 35. Deferred work

```text
DEFERRED_TO_C0_05=
  Teacher/bootstrap identity and quality,
  training objective provenance,
  supervised value target and RL reward

DEFERRED_TO_ISSUE_137_AND_C1=
  PyTorch vs JAX,
  Safetensors implementation,
  Trackio,
  HF Datasets/Arrow,
  Accelerate,
  physical checkpoint writer/loader,
  learner/training code,
  optimizer/scheduler state and training-loop RNG
```

No model weights, checkpoint, learner, training run, C1 or C0-05 work is started here.

## 36. C0-04 exit gates

`PASS` means the source-backed specification is resolved. It does not mean that a future loader,
model or inference runner has executed.

| Gate | Result | Evidence |
| --- | --- | --- |
| `C0_CHECKPOINT_SOURCE_AUTHORITY` | `PASS` | Sections 3-4; no existing ML checkpoint authority is reused, and replay/legacy artifacts are separated. |
| `C0_CHECKPOINT_MANIFEST_CONTRACT` | `PASS` | Sections 5-6; `ArgentumCheckpointManifestV1` is documentation-level and field-complete. |
| `C0_CHECKPOINT_IDENTITY_PREIMAGE` | `PASS` | Section 8; canonical payload, self-exclusion and operational exclusions are frozen. |
| `C0_MODEL_CONFIG_IDENTITY` | `PASS` | Section 7; strict canonical JSON/SHA-256 model-config identity. |
| `C0_WEIGHT_CONTENT_INTEGRITY` | `PASS` | Section 8; exact physical-byte SHA-256 required. |
| `C0_CHECKPOINT_COMPATIBILITY` | `PASS` | Sections 11-12 and 26; strict contract/version/tensor/profile validation. |
| `C0_INFERENCE_VS_RESUME_SNAPSHOT_BOUNDARY` | `PASS` | Section 10; inference artifact and training continuation state are separate. |
| `C0_DETERMINISTIC_INFERENCE_CONTRACT` | `CHANGES_REQUIRED_FOR_TOTAL_POLICY` | Sections 12 and 19; execution semantics are defined, but Environment V1 tie totality is unestablished. |
| `C0_NUMERIC_EXECUTION_PROFILE` | `PASS` | Sections 13-14; semantic, certification and operational classes are separated. |
| `C0_BATCH_COMPOSITION_INVARIANCE` | `PASS` | Section 15; unrelated batch companions cannot affect semantic selection. |
| `C0_CANDIDATE_PERMUTATION_INVARIANCE` | `PASS` | Section 15; unordered physical permutations preserve semantic choice. |
| `C0_NONFINITE_OUTPUT_POLICY` | `PASS` | Section 17; NaN/Inf fail closed. |
| `C0_DETERMINISTIC_SELECTION_CONTRACT` | `CHANGES_REQUIRED_FOR_TOTAL_POLICY` | Sections 18-19; argmax/fail-closed semantics are defined, but unresolved ties prevent a total policy path. |
| `C0_TIE_BREAK_CONTRACT` | `PASS` | Section 19; semantic discriminator or fail closed, never row order. |
| `C0_POLICY_RNG_BOUNDARY` | `PASS` | Section 21; engine, inference and training RNG are separate; stochastic mode is unsupported. |
| `C0_STRUCTURED_INFERENCE_CONTRACT` | `PASS` | Section 20; typed complete-domain selection without heuristic fallback. |
| `C0_EVALUATION_INFERENCE_PROVENANCE` | `PASS` | Section 24; exact checkpoint/profile/selection/RNG bindings required. |
| `C0_CROSS_BACKEND_CERTIFICATION_BOUNDARY` | `PASS` | Section 14; cross-backend equivalence is not assumed. |
| `C0_UNKNOWN_VERSION_FAIL_CLOSED` | `PASS` | Sections 11 and 32; unknown versions/kinds/profiles reject. |

```text
C0_04_DETERMINISTIC_BASELINE_READY=NO
STOCHASTIC_POLICY_INFERENCE_SUPPORTED=NO
OPEN_C0_BLOCKERS=TIE_BREAK_IDENTITY_GAP
SELF_REVIEW_P1=NONE
SELF_REVIEW_P2=NONE
C0_04_SPECIFICATION_GATES=PARTIAL; TIE_BREAK_TOTALITY_UNRESOLVED
C0_04_SPECIFICATION_PASS=NO
C0_04_FINAL_ACCEPTANCE_PASS=NO
INDEPENDENT_EXACT_SHA_REVIEW=PENDING
STOP_FOR_EXACT_SHA_REVIEW=YES
```

## 37. Required decision summary

```text
CHECKPOINT_MANIFEST_CONTRACT_ID=argentum-ml-checkpoint-manifest@v1
INFERENCE_CONTRACT_ID=argentum-ml-inference@v1
NUMERIC_EXECUTION_PROFILE_CONTRACT_ID=argentum-ml-numeric-execution-profile@v1
SELECTION_CONTRACT_ID=argentum-ml-policy-selection@v1

CHECKPOINT_IDENTITY_FORM=
  lineage-bound semantic manifest digest plus exact physical weight-artifact content digest

CHECKPOINT_ID_PREIMAGE=
  SHA-256(UTF-8(A3SemanticJson.canonicalJson(checkpointIdentityPayload)))
  where checkpointId itself is excluded and operational locators are excluded

MODEL_CONFIG_IDENTITY_FORM=
  SHA-256(UTF-8(A3SemanticJson.canonicalJson(versioned semantic modelConfigPayload)))

WEIGHT_ARTIFACT_CONTENT_DIGEST=SHA-256(exact physical artifact bytes)
LOGICAL_PARAMETER_SET_IDENTITY=DEFERRED
CHECKPOINT_FILENAME_AS_IDENTITY=NO
MUTABLE_ALIAS_AS_IDENTITY=NO
CHECKPOINT_LOAD_STRICT=YES
INFERENCE_CHECKPOINT_EQUALS_TRAINING_RESUME_SNAPSHOT=NO
LIVE_RECURRENT_STATE_IN_CHECKPOINT=NO

MODEL_FACING_CONTRACT_BINDING=argentum-ml-model-facing-decision-sample@v1
SPLIT_CONTRACT_BINDING=argentum-ml-dataset-split@v1
RECURRENT_CONTRACT_BINDING=argentum-ml-recurrent-sequence@v1 when recurrent; NONE_FOR_FEED_FORWARD otherwise
VOCABULARY_IDENTITY_REQUIRED=YES_WHEN_APPLICABLE

SEMANTIC_SELECTION_REPRODUCIBILITY=REQUIRED_WITHIN_DECLARED_PROFILE
SCORE_BITWISE_REPRODUCIBILITY=NOT_REQUIRED_WITH_REASON
CROSS_BACKEND_EQUIVALENCE=NOT_ASSUMED
BATCH_COMPOSITION_AS_POLICY_SEMANTICS=NO
BATCH_INVARIANT_SEMANTIC_SELECTION=YES
CANDIDATE_PERMUTATION_INVARIANCE=YES
NON_FINITE_POLICY_SCORE=FAIL_CLOSED
SELECTION_MODE_INITIAL=DETERMINISTIC_ARGMAX
DETERMINISTIC_ARGMAX_CONSUMES_POLICY_RNG=NO
TIE_BREAK_BY_PHYSICAL_ROW_ORDER=NO
DETERMINISTIC_TIE_BREAK=unique invariant source-semantic discriminator; otherwise fail closed
C0_TIE_BREAK_CONTRACT=PASS
C0_DETERMINISTIC_SELECTION_CONTRACT=CHANGES_REQUIRED_FOR_TOTAL_POLICY
C0_DETERMINISTIC_INFERENCE_CONTRACT=CHANGES_REQUIRED_FOR_TOTAL_POLICY
C0_04_DETERMINISTIC_BASELINE_READY=NO

ENGINE_RNG_EQUALS_POLICY_RNG=NO
POLICY_INFERENCE_RNG_EQUALS_TRAINING_RNG=NO
POLICY_RNG_CONTRACT_ID=NONE_FOR_DETERMINISTIC_MODE
DETERMINISTIC_POLICY_INFERENCE_SUPPORTED=YES
STOCHASTIC_POLICY_INFERENCE_SUPPORTED=NO
STOCHASTIC_UNSUPPORTED_BEHAVIOR=FAIL_CLOSED
POLICY_RNG_SCOPE=one environment episode x one policy instance when later supported
HIDDEN_FRAMEWORK_RNG_ALLOWED=NO
STRUCTURED_INFERENCE_HIDDEN_FALLBACK=NO

EVALUATION_BINDS_EXACT_CHECKPOINT=YES
EVALUATION_BINDS_NUMERIC_PROFILE=YES
EVALUATION_BINDS_SELECTION_MODE=YES
EVALUATION_BINDS_POLICY_RNG_WHEN_STOCHASTIC=YES
```

```text
NEXT_REQUIRED=C0_04A_ENVIRONMENT_V1_TIE_TOTALITY_CHARACTERIZATION
NEXT_TASK_STARTED=NO
```

## 38. Verification and stop condition

This is a documentation-only task:

```text
FULL_GYM_TEST=NOT_REQUIRED
FULL_RULES_TEST=NOT_REQUIRED
```

The required local gate is `git diff --check`; it is reported separately from hosted CI,
independent review and final acceptance. No unexecuted test is reported as `PASS`.

The requested delivery state is:

```text
one documentation commit
Draft PR against chrismaghuhn/argentum-engine:main
PR state remains DRAFT
no Ready-for-review transition
no merge
no C0_05/C1/training start
```

The Draft PR must state that C0-01, C0-02 and C0-03 are preserved, no model/checkpoint/learner or
schema/production work was performed, and the unrelated `StackResolver.kt` change in the original
checkout was not staged, reset, stashed, cleaned, edited, reformatted or included.
