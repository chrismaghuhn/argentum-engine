# C1_05 Bootstrap Label Materializer V1

## Status and hard boundary

```text
TASK=C1_05_BOOTSTRAP_LABEL_MATERIALIZER_V1_DESIGN
DATE=2026-09-14
STATUS=IMPLEMENTATION-READY DESIGN ONLY
BASE=f1766919524da248f17a35a853465f3d21b19aff
ORIGIN_MAIN_VERIFIED=YES
UPSTREAM_ROLE=REFERENCE_ONLY
WRITABLE_REPOSITORY=chrismaghuhn/argentum-engine

PRODUCTION_CODE_CHANGED=NO
TEACHER_CHANGED=NO
TRAJECTORY_V1_CHANGED=NO
PLAYER_OBSERVATION_V1_CHANGED=NO
COMPLETE_LEGAL_DOMAIN_CHANGED=NO
SELECTION_V2_CHANGED=NO
POLICY_TIE_RNG_CHANGED=NO
DATASET_SPLIT_CHANGED=NO
CHECKPOINT_CHANGED=NO
MODEL_IMPLEMENTED=NO
LABELS_MATERIALIZED=0
TRAINING_STARTED=NO
SMALL_LEARNER_SMOKE_STARTED=NO
RL_STARTED=NO
SELF_PLAY_STARTED=NO
LARGE_CORPUS_GENERATION_STARTED=NO
PR_CREATED=NO
```

This document specifies the next derived-data boundary only. It does not authorize
implementation, a full historical run, Teacher execution, training, a learner smoke,
RL, self-play, or a pull request. A separate review of this document is required
before C1_05 implementation is authorized.

The design consumes the accepted public-observation Teacher selection. It does not
make a strategic decision. In particular:

```text
Teacher / Selection V2 / PolicyTieRng
    choose one exact semantic source candidate

C1_05 materializer
    validate, bind, account, and serialize that already chosen value
```

The word `target` in this document means an admitted supervised imitation target,
not an optimal action, game-theoretic proof, reward, value estimate, or Rules
authority.

## 1. Authority map

| Authority | Owns | C1_05 may consume | C1_05 must not do |
| --- | --- | --- | --- |
| Argentum Rules/Gym and accepted `TrajectoryV1` | Public observation, complete legal domain, factual source choice, replay and source admission | The already admitted derived learner view | Reconstruct `GameState`, re-derive legality, repair a domain, or replace factual source data |
| C1_00 derived learner view | Lossless model-facing input, complete source binding, source reference, split projection | `manifest.json`, `samples.ndjson`, `ValidatedDerivedSample` | Rewrite C1_00 rows or turn the existing factual `target` into a Teacher target in place |
| `argentum-ml-dataset-split@v1` | Episode-level `TRAIN`/`VALIDATION`/`TEST` membership | The source row's frozen partition and the deterministic partition function | Rehash by Teacher, split by decision row, balance partitions, or use TEST for tuning |
| Admitted public-observation Teacher | The selected semantic action/response or typed `NO_LABEL` result | `PublicObservationTeacherRequestV1`, `SelectedTeacherResultV1`, `NoLabelTeacherResultV1`, and admitted identity/configuration | Score, rank, break ties, choose a candidate, complete a payload, AutoPay, choose a target/mode/X, or fabricate a structured label |
| `argentum-ml-supervised-policy-target@v1` | Meaning and shape of a supervised policy target | The existing C0 target semantics | Define a competing target meaning, regress Teacher scores, or use a candidate slot as label identity |
| C1_05 label artifact manifest | Provenance, content identity, accounting and join contract | A separate derived artifact identity | Become source-data trust, legal-domain, replay, or Teacher authority |
| Future learner | Physical batching, scoring and loss consumption | C1_00 `input` plus a joined C1_05 target | Receive Teacher diagnostics, binding metadata, source IDs, raw private data, or score vectors as features |

The resulting authority chain is:

```text
accepted source dataset and C1_00 derived sample
    -> C0 frozen episode split
    -> admitted Teacher request
    -> admitted Teacher result
    -> exact source-binding membership proof
    -> C1_05 supervised policy target sidecar
    -> later learner join
```

No arrow gives the materializer a new policy or legal-domain authority.

## 2. Repository audit and frozen dependencies

The audit was performed at the exact accepted fork main named in the task. The
relevant repository contracts are:

| Repository evidence | Finding used by this design |
| --- | --- |
| `docs/ml/c0-teacher-bootstrap-and-value-reward-boundary-contract-v1.md` | `argentum-ml-supervised-policy-target@v1` exists as a frozen documentation-level identity. A policy target is an exact admitted semantic source/Teacher choice, separate from factual source choice and separate from model input. |
| `docs/ml/c0-model-facing-sample-and-candidate-scoring-contract-v1.md` | `chosenSemanticAction XOR chosenSemanticResponse` is the durable semantic target. Complete domains are required; `PHYSICAL_BATCH_INDEX=DERIVED_ONLY`; flat roots and folded options have different target forms. |
| `docs/ml/c0-split-and-frozen-evaluation-contract-v1.md` and `ml/src/argentum_ml/data/split.py` | Split unit is `semanticEpisodeId`; mapping is SHA-256 namespace `argentum-ml-dataset-split@v1`, `0..79=TRAIN`, `80..89=VALIDATION`, `90..99=TEST`. |
| `ml/src/argentum_ml/data/derived_reader.py` | `manifest.json` plus `samples.ndjson` is strict, canonical, digest-bound, version-gated, and issues the non-constructible `ValidatedDerivedSample` token. It rejects duplicate `(trajectoryId, decisionIndex)` source references and validates target membership against the complete source domain. |
| `ml/src/argentum_ml/selection/selection_v2.py` | `ExactSemanticSourceBinding` carries exactly one action or response and an optional ordinal audit. It is canonical JSON-compatible and immutable. Selection V2 returns a source binding and audit ordinal, not a physical slot preference. |
| `ml/src/argentum_ml/teacher/request.py` | `PublicObservationTeacherRequestV1` can only be issued from a reader-issued `InferenceRequest`; candidate permutation is transport-only and cannot truncate or replace source authority. |
| `ml/src/argentum_ml/teacher/contracts.py` and `public_observation_teacher.py` | `SelectedTeacherResultV1` and `NoLabelTeacherResultV1` are the accepted result types. The admitted flat families are exactly `ACTION_CANDIDATES` and `FOLDED_DECISION_OPTIONS`; structured decisions return typed `NO_LABEL`. |
| `ml/src/argentum_ml/teacher/scoring.py` | The scorer receives only model input and candidate features. It has no target, source binding, ordinal, or RNG channel. Its score vector is not a C1_05 target. |
| `ml/src/argentum_ml/contracts/canonical_json.py` | A3-compatible canonical JSON, UTF-8 bytes and SHA-256 helpers already exist and must be reused. |
| `ml/src/argentum_ml/checkpoint/manifest.py` and the C1_04 design/evidence | Physical PyTorch/Safetensors/Trackio tooling is replaceable and is not semantic data or label authority. `teacherBootstrapProvenance` remains reserved/null in the checkpoint manifest. |
| C1_03/C1_03A/C1_03B/C1_03C evidence | The current admitted Teacher result is `ADMITTED_LIMITED_FLAT_REFERENCE_BOOTSTRAP`; the accepted historical selection counts are 59,211 total, 57,062 action labels and 2,149 folded labels, with zero ownership/trust failures. Structured rows remain explicit `NO_LABEL`. |
| Issues #124, #137 and #119 | C1 bootstrap precedes RL/self-play; learner tooling is optional physical infrastructure; small learner validation precedes any corpus scaling. None authorizes training or large data work. |

The current Python package does not yet expose a `SupervisedPolicyTargetV1`
validator or a label-artifact reader. That is an executable representation gap,
not a semantic contract gap. The later implementation must add only the smallest
validator/materializer/reader needed by this document; it must not redefine the
C0 target contract.

## 3. Teacher identity seam decision

`PublicObservationTeacherIdentityV1` currently declares:

```python
label_materializer_identity: None = None
```

`PublicObservationTeacherIdentityV1.from_config()` leaves it `None`, and the
current Teacher tests assert that it is `None`. This is a deliberate frozen V1
shape, not a populated extension point.

### Decision

Do not populate or repurpose `label_materializer_identity` in C1_05. The label
materializer identity and configuration digest live in the separate label-artifact
manifest. The Teacher identity remains exactly the accepted Teacher identity.

This avoids changing the frozen Teacher contract and makes the provenance roles
explicit:

```text
Teacher identity
    = who selected the semantic value

Label artifact provenance
    = from which source, with which Teacher/config, and by which materializer
      the selected value was serialized
```

If a later task requires the Teacher runtime identity itself to carry a
materializer identity, that task must introduce a new Teacher identity contract
version and update its admission/evidence. C1_05 does not do that.

## 4. Chosen architecture and rejected alternatives

### Recommended: separate source-bound label sidecar

C1_05 creates a new derived artifact containing:

```text
manifest.json
labels.ndjson
```

The sidecar references the immutable C1_00 derived artifact by its
`derivedArtifactId` and joins each label to a C1_00 sample by a stable source
decision key. It does not copy `input`, the complete domain, or raw source
bindings. A verifier reopens the source artifact and proves membership without
running the Teacher again.

This is the smallest reusable shape that preserves the C1_00 separation:

```text
C1_00 source artifact: input + factual source target + binding + provenance
C1_05 sidecar:         admitted Teacher target + join/binding/provenance
future learner:        source input + sidecar target
```

### Rejected: mutate or extend C1_00 `samples.ndjson`

This would change the accepted C1_00 content digest and derived artifact identity,
mix factual source choice with a later Teacher interpretation, and make an input
reader responsible for a new Teacher provenance channel. It would also make the
existing C1_00 artifact no longer the exact source for later comparisons.

### Rejected: copy full model-facing samples into a new artifact

This duplicates the potentially large input/domain payload, creates a second
source-view serialization path, and increases the risk of leaking Teacher or
binding metadata into the learner input. A stable join to the accepted C1_00
artifact is sufficient.

The sidecar is a new *physical derived-artifact schema*, not a competing
supervised-policy meaning. Its target is exactly the C0 target contract.

## 5. Input contract

The future implementation accepts a validated, already admitted source row and a
result from the admitted Teacher boundary.

### Required inputs

1. A reader-issued `ValidatedDerivedSample` from the exact C1_00 source artifact.
2. Its exact `InferenceRequest`, created only through
   `InferenceRequest.from_validated_sample(...)`.
3. Its exact `PublicObservationTeacherRequestV1`, created only through
   `PublicObservationTeacherRequestV1.from_inference_request(...)`.
4. Exactly one typed result from the admitted Teacher:
   `SelectedTeacherResultV1` or `NoLabelTeacherResultV1`.
5. The admitted `PublicObservationTeacherIdentityV1` and
   `PublicObservationTeacherConfigV1`, with exact expected identities/digest.
6. The source artifact manifest and the C1_05 materializer configuration.

The materializer API must not accept a raw JSON dictionary in place of any of
these authority-preserving objects. A bare, deserialized Teacher result without
the exact paired request is not a materializable input.

### Request/result coupling

`SelectedTeacherResultV1` does not currently carry a source decision ID of its
own. C1_05 therefore proves request membership structurally and at the call
boundary, without changing the frozen Teacher result contract:

- the result is passed together with the exact request object used for the
  Teacher call;
- the result diagnostics must match that request's family and candidate count;
- the result configuration digest must equal the admitted configuration digest;
- the result ordinal must resolve in that request's source bindings;
- the result's exact binding must equal the request binding at that ordinal; and
- the selected semantic value must resolve to exactly one member of the
  request's complete domain, and that resolved member must be executable.
  Other executable candidates may exist.

A future transport wrapper may carry the source decision key next to the result,
but it is an invocation envelope, not a second semantic binding model and not a
change to `SelectedTeacherResultV1`. The implementation must reject a result that
cannot be coupled to the exact request by these checks.

### Source artifact checks before row processing

Before any Teacher result is consumed, the materializer must fail closed unless
all of the following match the configured expected values:

```text
sourceDatasetId
sourceManifestContentDigest
sourceDerivedArtifactId
sourceDerivedViewSchemaIdentity=argentum-ml-derived-learner-view@v1
trajectorySchemaIdentity=argentum-trajectory@v1
modelFacingContractIdentity=argentum-ml-model-facing-decision-sample@v1
splitContractIdentity=argentum-ml-dataset-split@v1
```

The authoritative C1_05 entry point requires non-optional expected values for
`sourceDatasetId`, `sourceManifestContentDigest`, `sourceDerivedArtifactId`,
and the admitted Teacher source commit. A null or omitted expected identity is
not a valid V1 invocation. The historical C1_03 admission supplies the exact
dataset/manifest/derived-artifact/source-commit tuple; a synthetic fixture may
provide a different explicit tuple only in a test-scoped fixture, never through
a production `None` bypass.

The source manifest's own content and derived-artifact digests must be verified
using the existing canonical JSON rules. A changed source file, even when a row
appears structurally usable, is not a valid input.

## 6. Canonical supervised policy target

### Q1 — What is the canonical target?

The canonical target is the existing C0 target object in its exact
source-binding representation:

```json
{
  "chosenSemanticAction": <chosen-action object or null>,
  "chosenSemanticResponse": <chosen-response object or null>
}
```

Exactly one field is non-null. The object is copied from
`result.exact_source_binding.exact_action` or
`result.exact_source_binding.exact_response` only after the result has been
proved equal to `request.source_bindings.exact_binding_for(ordinal)`.
`decisionFamily` is a separate row field used to dispatch validation; it is not a
second target meaning.

The public feature view remains in the source artifact's `input.domain` channel.
It is used to locate and validate the exact source candidate, but it is not the
canonical C1_05 target:

```text
RAW/EXACT SOURCE-BINDING TARGET != PUBLIC MODEL-FACING FEATURE VIEW
```

The label row is therefore a derived version of the C0 target semantics, not a
new `TeacherScoreTarget`, dense score vector, confidence target, value target or
reward target.

### ACTION_CANDIDATES

For an admitted `ACTION_CANDIDATES` result:

```json
{
  "decisionFamily": "ACTION_CANDIDATES",
  "target": {
    "chosenSemanticAction": {
      "type": "chosen-action",
      "candidate": <result.exact_source_binding.exact_action.candidate>,
      "choicePayload": <the exact source-authorized payload>
    },
    "chosenSemanticResponse": null
  },
  "binding": {
    "sourceBindingOrdinal": <Teacher result ordinal>
  }
}
```

The exact action is copied from the Teacher result after the materializer proves
that it equals the request's original source binding at the validated ordinal.
The public model-facing candidate at that ordinal is used only as the
source-authorized transport counterpart for membership and executable-support
checks. The materializer never maps a raw runtime action ID by parsing or
guessing. The payload is copied only if it is already source-authorized and
passes the existing C0/S7 validation; an absent required payload is a rejected
source binding, not an invitation to fill defaults. The accepted C1_03 flat
bootstrap scope has empty action payloads for its admitted selected roots.

### FOLDED_DECISION_OPTIONS

For an admitted `FOLDED_DECISION_OPTIONS` result:

```json
{
  "decisionFamily": "FOLDED_DECISION_OPTIONS",
  "target": {
    "chosenSemanticAction": null,
    "chosenSemanticResponse": {
      "type": "chosen-response",
      "response": <result.exact_source_binding.exact_response.response>
    }
  },
  "binding": {
    "sourceBindingOrdinal": <Teacher result ordinal>
  }
}
```

The exact response is copied from the Teacher result after equality with the
request's original source binding has been proved. The public folded candidate
at that ordinal is used only to prove complete-domain membership. The complete
response-local semantics, including source-authorized optional metadata, are
retained exactly. A folded response is not converted into a live action, an
action index, or a guessed structured subdecision.

### Q2/Q3 — Exact binding and membership

The target is bound to one exact source decision through the row's full
`sourceReference`, the exact `selectedExactSourceBinding`, and the
`sourceBindingOrdinal`. The ordinal is only an audit address. Membership is
proven by resolving that ordinal through the exact request source binding and
public candidate tuple, then validating the exact target against the complete
source domain. The verifier must establish:

```text
source key matches the source sample
decision family matches the source domain and request
complete domain was retained without truncation
ordinal is a valid source ordinal
Teacher exact binding == request exact binding at that ordinal
target exact value == Teacher result exact value
Teacher result exact value == request exact binding at that ordinal
target's public counterpart == request candidate/response at that ordinal
candidate is present and executable
target has exactly one unique domain match
```

The existing `DerivedArtifactReader` target-membership rules remain the semantic
membership authority for the source-domain check. C1_05 must call/reuse their
exact shared membership helper with the Teacher exact binding as the selected
binding, or make that helper accept an explicit selected binding without changing
its semantics. It must not pass the C1_00 factual source target as the Teacher
selected binding, and it must not implement a weaker parallel membership
algorithm.

### Q4 — Representation of the two flat families

Both families use the one-target object above. The distinction is explicit and
closed:

```text
ACTION_CANDIDATES       -> chosenSemanticAction
FOLDED_DECISION_OPTIONS -> chosenSemanticResponse
STRUCTURED_DECISION     -> no target in C1_05 V1
```

The target contains the complete semantic candidate/response required by C0, not
an ordinal or a physical candidate slot.

### Q6/Q7 — Candidate index and permutation

Candidate index is not a canonical label. A batch-local integer may be derived
after joining the semantic target to the current complete candidate ordering, but
it is never persisted as the target identity.

`sourceBindingOrdinal` is retained only for source binding and audit. It is not a
preference signal, tie rule or target identity. Under a permitted physical
candidate permutation, the candidate and its ordinal move together; the semantic
target remains the same. A learner adapter may produce a different batch slot,
but the joined target must resolve to the same semantic value.

## 7. Structured `NO_LABEL` and outcome accounting

### Q5 — Structured decisions

For `STRUCTURED_DECISION`, the expected result is `NoLabelTeacherResultV1`. The
materializer emits no label row and does not:

- flatten the typed domain into fake candidates;
- choose the first option or collection-order option;
- AutoPay;
- choose a target, mode, X, order, distribution, replacement, combat edge or
  other nested choice;
- invoke a heuristic fallback.

`STRUCTURED_DOMAIN_NOT_SCOREABLE` and
`UNSUPPORTED_DOMAIN_VERSION` remain explicitly countable reasons. An expected
structured `NO_LABEL` is a successful, auditable outcome, not an error.

### Accounting model

The label manifest carries exact zero-filled maps for every permitted partition,
flat family, and `NoLabelReason` value. At minimum it contains:

```text
sourceRowsByPartition
processedRowsByPartition
testRowsConsumed
teacherCallsByPartition
materializedLabelsByPartition
materializedLabelsByDecisionFamily
expectedNoLabelByPartitionAndReason
rejectedInvalidSourceBindingByPartition
rejectedInvalidSelectedLabelByPartition
rejectedSplitByPartition
rejectedProvenanceByPartition
duplicateDecisionKeyCount
conflictingLabelCount
otherFailClosedMaterializerErrorCount
```

The successful-artifact invariant for each processed `TRAIN` or `VALIDATION`
partition is:

```text
processed rows
= materialized labels
 + expected NO_LABEL rows
 + rejected invalid source-binding rows
 + rejected invalid selected-label rows
```

Global provenance, duplicate, conflict, digest and schema failures do not get
silently turned into rows. They abort publication and leave no authoritative
partial artifact; their deterministic error code is returned by the run. The
manifest counters for these classes must be zero in every published artifact.

This preserves the distinction:

```text
EXPECTED_NO_LABEL
    typed Teacher result with an explicit NoLabelReason

INVALID_SELECTED_LABEL
    a SelectedTeacherResultV1 exists but its binding, ordinal, family,
    membership or provenance is invalid; no target is emitted

MATERIALIZER_FAILURE
    the operation cannot prove source/artifact/provenance/digest integrity;
    publication is aborted
```

The label artifact never hides a source row merely because it was not
materialized. A later acceptance report must publish the accounting maps, not
only `labelCount`.

## 8. Label artifact shape and canonical bytes

### 8.1 Artifact files

The future implementation publishes only after both files are complete and
verified:

```text
manifest.json       one canonical UTF-8 JSON object, no BOM, no trailing LF
labels.ndjson       one canonical UTF-8 JSON object per materialized label,
                    each terminated by exactly one LF byte
```

No path, filename, mtime, hostname, PID, worker ID, wall-clock timestamp,
Trackio run ID or GitHub job ID participates in semantic identity.

The label artifact is a reference artifact, not a replacement for the C1_00
derived learner view. Its rows contain no `input` member. A future learner joins
`labels.ndjson` to the source `samples.ndjson` using the source key and verifies
the source-derived artifact ID before using the target.

### 8.2 Label row schema

Every row has exactly these top-level fields:

```json
{
  "version": 1,
  "partition": "TRAIN",
  "decisionFamily": "ACTION_CANDIDATES",
  "sourceReference": {
    "datasetId": "...",
    "sourceManifestContentDigest": "...",
    "trajectoryId": "...",
    "semanticEpisodeId": "...",
    "collectionJobId": "...",
    "decisionIndex": 0,
    "replayActionIndex": 0,
    "replayFrameIndex": 0,
    "semanticDecisionId": {
      "version": 1,
      "schemaIdentity": "argentum-trajectory-semantic-decision@v1",
      "value": "..."
    },
    "perspectivePlayerId": "..."
  },
  "target": {
    "chosenSemanticAction": "... or null",
    "chosenSemanticResponse": "... or null"
  },
  "binding": {
    "selectedExactSourceBinding": <exact Teacher result binding>,
    "sourceBindingOrdinal": 0
  },
  "provenance": {
    "teacherResultSchemaIdentity": "argentum-ml-public-observation-teacher-result@v1",
    "teacherConfigDigest": "...",
    "selectionContractIdentity": "argentum-ml-policy-selection@v2",
    "policyRngIdentity": "argentum-ml-policy-tie-rng@v1",
    "teacherSeatIndex": 0,
    "candidateCount": 1,
    "rngDrawCount": 0,
    "cursorBefore": 0,
    "cursorAfter": 0,
    "tieOccurred": false,
    "policyTieRngWordsConsumed": 0
  }
}
```

The example is schematic for values, but the field set is closed in the future
reader. The actual `sourceReference` is copied from the validated C1_00 sample;
the target and `selectedExactSourceBinding` are the exact public-source semantic
objects described in Section 6. The row does not duplicate the complete legal
domain or the raw inverse binding map. It does retain the one exact selected
binding required to prove `target == selectedExactSourceBinding` without using
the C1_00 factual source target. Score vectors, source factual target, private
provenance, and unrelated raw engine data are not copied.

`provenance` is audit-only. It is never passed to a model provider. The row does
not store `rng_state.stream_key`, raw score values, internal object references,
or any hidden information.

### 8.3 Manifest field set

The label manifest is versioned and exact. Its required semantic fields are:

```text
version=1
labelArtifactSchemaIdentity=argentum-ml-supervised-policy-label-artifact@v1
labelArtifactIdentitySchema=argentum-ml-supervised-policy-label-artifact-id@v1
labelArtifactId
supervisedPolicyTargetContractIdentity=argentum-ml-supervised-policy-target@v1

sourceDatasetId
sourceManifestContentDigest
sourceDerivedArtifactId
sourceDerivedViewSchemaIdentity=argentum-ml-derived-learner-view@v1
trajectorySchemaIdentity=argentum-trajectory@v1
modelFacingContractIdentity=argentum-ml-model-facing-decision-sample@v1
splitContractIdentity=argentum-ml-dataset-split@v1

teacherProvenance {
  teacherContractIdentity
  teacherPolicyIdentity
  teacherSourceIdentity
  teacherSourceCommit
  teacherConfigSchemaIdentity
  teacherConfigDigest
  scorerIdentity
  selectionContractIdentity
  policyRngIdentity
  teacherPolicyTieScheduleIdentity
  teacherPolicyTieSeed
  initialPolicyTieCursor
  teacherTieStateScope
  legacyA9PolicySeedReused
  teacherExecutionConfigDigest
  teacherAdmissionPurposeIdentity
  teacherAdmissionResult
  teacherAdmissionPlanIdentity
  teacherAdmissionPlanDigest
}

labelMaterializerImplementationIdentity { implementation, sourceCommit }
labelMaterializerConfigDigest
allowedPartitions=[TRAIN, VALIDATION]
sourceDecisionKeyIdentity=argentum-ml-source-decision-key@v1
labelsContentReference=labels.ndjson
labelsContentDigest
labelsByteCount
labelCount
sourceRowsByPartition { TRAIN, VALIDATION, TEST }
processedRowsByPartition { TRAIN, VALIDATION, TEST }
teacherCallsByPartition { TRAIN, VALIDATION, TEST }
labelCountsByPartition { TRAIN, VALIDATION, TEST }
labelCountsByDecisionFamily { ACTION_CANDIDATES, FOLDED_DECISION_OPTIONS }
expectedNoLabelByPartitionAndReason
rejectedInvalidSourceBindingByPartition
rejectedInvalidSelectedLabelByPartition
rejectedSplitByPartition
rejectedProvenanceByPartition
duplicateDecisionKeyCount
conflictingLabelCount
otherFailClosedMaterializerErrorCount
testRowsConsumed
manifestContentDigest
```

All digest fields are lowercase SHA-256 hex. All identity strings are exact
versioned identities. The nested accounting maps are closed, deterministic and
zero-filled; an implementation must not omit a zero-valued reason.

`sourceRowsByPartition` is copied from the verified C1_00 manifest. It does not
claim that TEST rows were semantically consumed. `testRowsConsumed` and
`teacherCallsByPartition.TEST` must be zero.

The new `labelArtifactSchemaIdentity` and `labelArtifactIdentitySchema` describe
only the physical derived sidecar. They do not introduce a second target
semantics. The semantic target identity remains the C0 identity above.

## 9. Artifact identity and provenance

### Q8/Q9 — Label artifact identity

The content identity is:

```text
labelsContentDigest =
  SHA-256(raw bytes of labels.ndjson)

manifestContentDigest =
  SHA-256(UTF-8(A3CanonicalJson(manifest with manifestContentDigest omitted)))

labelArtifactId =
  SHA-256(UTF-8(A3CanonicalJson(labelArtifactIdentityPayload)))
```

The identity payload contains exactly the non-operational fields that can alter
the meaning or membership of the label artifact:

```text
labelArtifactIdentitySchema
labelArtifactSchemaIdentity
supervisedPolicyTargetContractIdentity
sourceDatasetId
sourceManifestContentDigest
sourceDerivedArtifactId
sourceDerivedViewSchemaIdentity
trajectorySchemaIdentity
modelFacingContractIdentity
splitContractIdentity
sourceDecisionKeyIdentity
teacherProvenance (all fields, including config/source/selection/RNG,
                    execution-schedule and admission identities)
labelMaterializerImplementationIdentity
labelMaterializerConfigDigest
allowedPartitions
labelsContentDigest
sourceRowsByPartition
processedRowsByPartition
teacherCallsByPartition
labelCountsByPartition
labelCountsByDecisionFamily
expectedNoLabelByPartitionAndReason
rejectedInvalidSourceBindingByPartition
rejectedInvalidSelectedLabelByPartition
rejectedSplitByPartition
rejectedProvenanceByPartition
duplicateDecisionKeyCount
conflictingLabelCount
otherFailClosedMaterializerErrorCount
testRowsConsumed
```

The identity payload excludes `labelArtifactId`, `manifestContentDigest`,
`labelsContentReference`, file paths, byte count, timestamps, and operational
locations. The labels content digest binds the exact bytes; the manifest digest
binds the complete metadata.

### Required provenance fields

At minimum, every published artifact must bind:

```text
source dataset identity
source DatasetManifest content digest
source C1_00 derived artifact identity
source derived-view schema identity
TrajectoryV1 schema identity
model-facing sample contract identity
dataset split contract identity

Teacher bootstrap contract identity
Teacher policy identity
Teacher source identity
Teacher source commit
Teacher configuration schema identity
Teacher configuration digest
Teacher scorer identity
Selection V2 identity
PolicyTieRng V1 identity
teacherPolicyTieScheduleIdentity
teacherPolicyTieSeed
initialPolicyTieCursor
teacherTieStateScope
legacyA9PolicySeedReused
teacherExecutionConfigDigest
teacherAdmissionPurposeIdentity
teacherAdmissionResult
teacherAdmissionPlanIdentity
teacherAdmissionPlanDigest

supervised policy target contract identity
source-decision-key identity
label materializer implementation identity/source commit
label materializer configuration digest
```

For C1_05 V1 these execution and admission fields are mandatory, not optional.
The accepted flat-bootstrap Teacher execution binding is:

```text
teacherPolicyTieScheduleIdentity=argentum-ml-c1-03-teacher-policy-tie-schedule@v1
teacherPolicyTieSeed=0
initialPolicyTieCursor=0
teacherTieStateScope=semanticEpisodeId|teacherPolicyIdentity|seatIndex
legacyA9PolicySeedReused=false

teacherAdmissionPurposeIdentity=argentum-ml-flat-reference-bootstrap@v1
teacherAdmissionResult=ADMITTED_LIMITED_FLAT_REFERENCE_BOOTSTRAP
teacherAdmissionPlanIdentity=argentum-ml-c1-03-teacher-quality-and-admission@v1
teacherAdmissionPlanDigest=bc186eb4df9830039fb1b0e474700afdb82e1cca033483afcad6bfd8d24fee79
```

The execution configuration digest is not a free-form label. Its exact V1
preimage is:

```json
{
  "initialPolicyTieCursor": 0,
  "legacyA9PolicySeedReused": false,
  "schema": "argentum-ml-c1-05-teacher-execution-config@v1",
  "teacherPolicyTieScheduleIdentity": "argentum-ml-c1-03-teacher-policy-tie-schedule@v1",
  "teacherPolicyTieSeed": 0,
  "teacherTieStateScope": "semanticEpisodeId|teacherPolicyIdentity|seatIndex",
  "version": 1
}
```

`teacherExecutionConfigDigest` is SHA-256 over the UTF-8 A3 canonical JSON of
that preimage. The state is created once per
`semanticEpisodeId|teacherPolicyIdentity|seatIndex` at the declared initial
cursor and is carried across that instance's decisions. It is never recreated
per row. The per-result `cursorBefore`, `cursorAfter`, and draw-count evidence
must agree with this stateful execution. A different schedule, seed, cursor,
scope, legacy-seed policy, admission purpose/result, or plan digest produces a
different provenance identity and is not C1_05 V1.

The admission fields bind the purpose and accepted result, not only the existence
of a Teacher configuration. `TEACHER_PROVENANCE_VALID` and
`TEACHER_BOOTSTRAP_ADMITTED` remain separate claims; the sidecar records both
the exact admitted purpose/result and the immutable plan identity/digest that
established the admission.

### Teacher execution seat authority

`seatIndex` is derived exclusively from the validated source sample. The exact
rule is:

```text
sourceReference.perspectivePlayerId
    -> exactly one provenance.environmentIdentity.roster[].playerId match
    -> use that roster entry's seatIndex
```

The matching roster entry's `seatIndex` is the only PolicyTieRng seat index. The
materializer must reuse the existing C1_03 seat-authority helper or extract that
same logic into a generic shared helper; it must not invent a second roster
mapping. The derived value is recorded as row-level `provenance.teacherSeatIndex`
and is used to obtain the stateful schedule entry keyed by
`semanticEpisodeId|teacherPolicyIdentity|seatIndex`.

Missing, duplicate, malformed, or inconsistent roster ownership fails closed.
The matched `seatIndex` must be a nonnegative integer. Physical row order, player
role text, batch index, candidate ordinal, and caller-supplied seat values are
forbidden substitutes. A row cannot consume Teacher RNG or a precomputed Teacher
result until this seat authority has passed.

The following are explicitly forbidden as semantic identity:

```text
filesystem path, filename, hostname, PID, worker number, wall-clock time,
completion order, Trackio run ID, GitHub Actions job ID, mutable `latest`/`best`,
runtime EntityId, candidate slot, raw action ID, or raw decision ID
```

### Q13 — Verification without rerunning the Teacher

Yes. The authoritative label reader requires both the exact C1_00 source
artifact and an expected source-identity tuple
`(sourceDatasetId, sourceManifestContentDigest, sourceDerivedArtifactId)`.
It can verify a published sidecar without calling the Teacher:

1. validate canonical bytes, exact schema/version and all manifest digests;
2. recompute `labelArtifactId` and `manifestContentDigest`;
3. open the exact `sourceDerivedArtifactId` through the existing strict source
   reader;
4. join each label by the source decision key;
5. verify source reference, partition, family, complete-domain count and ordinal;
6. resolve the source candidate/response at the ordinal, compare the label target
   exactly to `selectedExactSourceBinding`, and validate that exact binding's
   public counterpart against the complete source domain;
7. verify exactly one semantic domain match and that the matched member is
   executable; other executable candidates may exist; then verify all accounting totals; and
8. verify that no TEST row has a label or Teacher call count.

The verifier must not recompute Teacher scores or rerun Selection V2. The
Teacher result evidence in each label row is audit metadata, not a prerequisite
for semantic re-selection.

## 10. Stable source decision key and duplicates

### Q19 — Authoritative key

The row key is the existing source identity, not a new UUID:

```text
sourceDecisionKeyV1 =
  (trajectoryId, decisionIndex, semanticDecisionId.value)
```

The source dataset and source manifest digest are artifact-level key context.
`semanticEpisodeId` and `collectionJobId` remain in the copied source reference
for audit and split checks. `trajectoryId + decisionIndex` matches the existing
C1_00 duplicate rule; the semantic decision ID is an additional integrity check.

`(semanticEpisodeId, decisionIndex)` alone is insufficient because C0 permits
distinct collection/policy jobs for one environment group. No UUID is generated.

### Duplicate behavior

- A duplicate source key in the source artifact is a source-reader failure.
- A duplicate source key in one materializer invocation is
  `DUPLICATE_DECISION_KEY`; publication aborts.
- Two different targets for one source key are
  `CONFLICTING_LABEL_FOR_SOURCE_DECISION`; publication aborts.
- Running the same source with a different Teacher configuration or materializer
  configuration is not a merge. It produces a different label artifact identity.
- A label sidecar whose manifest provenance differs from the configured Teacher
  provenance is rejected; it is never silently overwritten or deduplicated.

The output is therefore a function of the exact source artifact, exact admitted
Teacher/configuration, exact materializer identity/configuration and exact
materialized label bytes.

## 11. Split and TEST boundary

### Q10 — TRAIN and VALIDATION

The materializer inherits the source partition exactly. It does not compute a
Teacher-specific split and does not split decision rows.

```text
TRAIN       -> materialization permitted
VALIDATION  -> materialization permitted
TEST        -> no label, no Teacher request, no Teacher result consumed
```

For every row, recompute `assign_partition(sourceReference.semanticEpisodeId)`
and require equality with the source row's `partition`. A mismatch aborts the
operation as a source/split failure.

### Q11 — TEST protection

The materializer must establish the source manifest and partition metadata before
calling the Teacher. The TEST branch is an immediate non-authoritative skip:

```text
TEST row encountered
    -> increment source/test accounting from the verified envelope
    -> do not build a Teacher request
    -> do not call the Teacher
    -> do not inspect target/choice/outcome for label purposes
    -> do not emit a label row
```

The published artifact must satisfy:

```text
testRowsConsumed=0
teacherCallsByPartition.TEST=0
labelCountsByPartition.TEST=0
```

The current `DerivedArtifactReader.open()` performs whole-file byte/digest
validation before iteration. If that strict validation physically scans TEST
bytes, the implementation must report that separately as source-artifact
integrity I/O; it must not call those rows semantically consumed and must not
submit them to the Teacher. A future split-aware source iterator may strengthen
the physical boundary, but C1_05 must not weaken the semantic boundary to fit the
current reader API.

Any precomputed Teacher result for TEST is a fail-closed
`TEST_TEACHER_RESULT_PRESENT` error, not an ignored result.

## 12. Exact materialization algorithm

The later implementation must follow this order. The order is normative because
it prevents a malformed result from being normalized into a label.

### Phase A — Validate global inputs

1. Validate the exact source artifact manifest and all source content digests
   through the existing reader.
2. Require the exact source dataset, source manifest digest, source derived
   artifact ID, source/view/model/split identities and target contract identity.
3. Validate the admitted Teacher identity, source commit, config schema, config
   digest, scorer, Selection V2 and PolicyTieRng V1 identities, plus the exact
   C1_03 tie schedule, seed, initial cursor, state scope, legacy-seed policy,
   admission purpose/result, and accepted admission plan identity/digest.
4. Validate the C1_05 materializer implementation identity and configuration
   digest. Unknown materializer versions fail closed.
5. Validate that `allowedPartitions` is exactly `[TRAIN, VALIDATION]` in canonical
   order. A caller may not opt into TEST in V1.

Any failure in Phase A aborts the operation before any Teacher authority is
consumed.

### Phase B — Read and classify a source row

For each reader-issued source sample:

1. Read the source reference envelope and verify the source row's dataset and
   source-manifest digest match the source manifest.
2. Recompute the frozen split from `semanticEpisodeId` and require exact
   partition equality.
3. If the partition is TEST, execute only the TEST skip branch in Section 11.
4. For TRAIN/VALIDATION, derive `seatIndex` exclusively by matching
   `sourceReference.perspectivePlayerId` to exactly one
   `provenance.environmentIdentity.roster[].playerId`, as specified in the
   Teacher execution seat authority. Require the matched `seatIndex` to be a
   nonnegative integer. Use no caller-supplied or physical-order substitute.
5. Issue the exact `InferenceRequest` from the
   `ValidatedDerivedSample`. Do not construct an input from a raw dictionary.
6. Issue `PublicObservationTeacherRequestV1` from that request with no
   permutation for the canonical run. A test-only permutation may be used to
   prove equivariance, but it must retain all candidates and source ordinals.
7. Require a flat family. A structured request is allowed to proceed only to the
   admitted Teacher's typed `NO_LABEL` path; it is never flattened.
8. If the request cannot be issued because the source flat binding is not
   complete, increment `rejectedInvalidSourceBindingByPartition` and emit no
   label. Do not call the Teacher for that row.

### Phase C — Validate the typed Teacher result

For `NoLabelTeacherResultV1`:

1. Require the result diagnostics config digest, family and candidate count to
   match the request/configuration.
2. Require that the stateful Teacher execution context used the derived
   `seatIndex` and the declared schedule key for this source episode/policy
   instance.
3. Require `support=NO_LABEL` and an explicit `NoLabelReason`.
4. Increment the exact partition/reason counter.
5. Emit no label row. Structured reasons are expected; they are not failures.

For `SelectedTeacherResultV1`:

1. Require `support=SUPPORTED`, no `no_label_reason`, matching config digest,
   matching decision family and matching candidate count. Require the execution
   context's derived `seatIndex` to be the seat used by the stateful schedule.
2. Require `source_binding_ordinal` to be a non-negative integer in the request's
   complete source ordinal set.
3. Resolve `request.source_bindings.exact_binding_for(ordinal)` and compare its
   canonical action/response value with `result.exact_source_binding`.
4. Require the result's ordinal audit, when present, to equal the result ordinal.
5. Resolve the request candidate at that ordinal. Require `present=true` and
   `executable_support=true`.
6. Set the canonical target to the exact action/response from
   `result.exact_source_binding`; do not replace it with `feature_view`.
7. Validate that exact target and selected binding against the complete source
   domain using the shared C0 membership helper, with the Teacher exact binding
   as the selected binding. The public candidate/response at that ordinal is the
   alias/projection counterpart used for executable-support and semantic-match
   checks. Exactly one semantic domain match is required; only that matched
   member must be executable. Other executable candidates may exist.
8. For an action, validate the full choice payload against the existing source
   domain. For a folded response, preserve the complete source-authorized
   response-local semantics.
9. Do not read or use `scoreVector`; no such field is accepted by the result
   materialization schema.
10. Copy only the bounded Teacher result evidence listed in the row schema; do
   not copy the raw RNG stream key or any private/debug value.

Any selected-result failure is `INVALID_SELECTED_LABEL` and is counted as a
row-local rejection only if the source decision key and partition remain
unambiguous. A malformed global result/provenance envelope aborts publication.
The implementation must never silently turn a selected result into `NO_LABEL`.

### Phase D — Build and publish a label row

1. Copy the source reference exactly from the validated C1_00 sample.
2. Copy the exact action/response from `result.exact_source_binding` into the
   C0 target channel. Do not serialize the public `feature_view` as the target.
3. Store the same exact Teacher binding under
   `binding.selectedExactSourceBinding` and retain the source ordinal only as a
   binding/audit address.
4. Store the derived `teacherSeatIndex` and bounded result provenance only under
   the provenance channel.
5. Canonicalize the row and append it to a staging `labels.ndjson`.
6. Record counters without consulting map iteration order or worker completion
   order.

At the end, verify all accounting equations, compute the label digest, compute the
non-self-referential artifact ID and manifest digest, verify the staged bytes, and
atomically rename the complete staging directory into a previously absent output
path. An existing output directory is rejected, so a crash cannot expose one
file without the other and a retry cannot silently reuse a partial publication.

## 13. Failure semantics

The future reader/materializer uses exact, closed error classes. The following
conditions are mandatory fail-closed cases:

| Failure | Required behavior |
| --- | --- |
| Wrong source dataset identity | Abort; no label artifact |
| Wrong source manifest digest | Abort; no label artifact |
| Wrong source C1_00 derived artifact ID or view schema | Abort; no label artifact |
| Wrong model-facing or split contract | Abort; no label artifact |
| Unknown source/target/label-artifact version | Abort; no compatibility guess |
| Non-canonical manifest or label line | Abort; no repair |
| Source or label digest mismatch | Abort; no partial publication |
| Unsupported partition or split mismatch | Abort; do not reassign |
| TEST Teacher call/result | Abort; do not use the result |
| Wrong Teacher contract/policy/source/source commit | Abort; no label |
| Wrong Teacher config schema or digest | Abort; no label |
| Wrong scorer, Selection V2 or PolicyTieRng identity | Abort; no label |
| Wrong Teacher tie schedule, seed, initial cursor or state scope | Abort; no label |
| Legacy A9 seed reuse is not exactly `false` | Abort; no label |
| Wrong Teacher admission purpose/result/plan identity/digest | Abort; no label |
| Missing, duplicate, malformed or inconsistent roster seat authority | Abort; no label |
| Result family/candidate-count/config mismatch | Reject selected row or abort if envelope-wide |
| Invalid source-binding ordinal | Reject selected row; count explicitly |
| Exact source binding differs from request binding | Reject selected row; count explicitly |
| Selected value absent from complete domain | Reject selected row; count explicitly |
| Selected value not executable | Reject selected row; count explicitly |
| Structured result with a fabricated flat target | Abort; structured rows remain `NO_LABEL` |
| Duplicate source decision key | Abort; `DUPLICATE_DECISION_KEY` |
| Conflicting target for one source key | Abort; `CONFLICTING_LABEL_FOR_SOURCE_DECISION` |
| Materializer identity/config mismatch | Abort; no artifact |
| Raw/private/debug field in label target or model join | Abort; no artifact |
| Unknown future accounting or row field | Abort; no best-effort read |

There is no automatic repair, first-match behavior, lexicographic fallback,
collection-order fallback, random retry, AutoPay, target default, mode default,
X default, hidden target selection, or source-choice substitution.

## 14. Privacy and input/target separation

### Q14/Q15 — What reaches the future model?

Only the existing C1_00 `input` channel reaches the model, after the learner's
later physical batching/encoding:

```text
decisionContext
public observation
complete currently supplied candidate/domain feature view
source-authorized structural masks and relations
```

The learner joins exactly one C1_05 target for the loss channel. It must not pass
any of the following to the model as an input feature:

```text
Teacher score vector
Teacher diagnostics or tie class
PolicyTieRng cursor or stream key
selected action/response
sourceBindingOrdinal
sourceReference / semantic IDs / trajectory IDs
raw exact source binding
Teacher identity/configuration/provenance
NO_LABEL reason
source factual target
outcome, closure, replay tail or future record
```

The label sidecar must not add any new information to the learner's observation
set. It may retain the exact source-bound semantic target and its exact selected
binding, because both are already admitted source-binding channels; it may also
retain the bounded provenance needed for validation/audit. These channels remain
outside model input. The sidecar must never add:

```text
raw GameState
opponent hidden hand/library/exile contents
face-down private identity
debug reveal fields
internal object references
unstable engine IDs as features
```

Source IDs may appear in the exact target, binding, and provenance/join channels
because C1_00 already defines those source-authority channels, but they are not
model features. C1_05 does not add hidden IDs or private facts. The C1_05 target
and binding channels are never passed to the model as input.

## 15. Determinism and permutation requirements

### Q16 — Same-input reproducibility

The exact reproducibility claim is:

```text
same accepted C1_00 source artifact
+ same admitted Teacher identity/configuration/execution binding
+ same C1_05 materializer identity/configuration
    -> byte-identical labels.ndjson and manifest.json
```

This requires:

- A3 canonical JSON and UTF-8 byte emission;
- exactly one LF after each label line and no manifest newline;
- source row order normalized by a deterministic key, not arrival order;
- canonical row order `(partition rank, semanticEpisodeId, trajectoryId,
  decisionIndex, semanticDecisionId.value)`; duplicate keys abort;
- no filesystem enumeration, map/hash iteration, process hash seed, wall clock,
  host, PID, worker, device availability or completion order in output;
- one accepted Teacher tie-RNG state/schedule per declared Teacher execution
  scope, never a hidden per-row reset; and
- no materializer-generated randomness.

The source artifact is already deterministic, but the label writer still sorts or
uses an explicitly verified canonical source order so that the sidecar does not
inherit an accidental transport order.

### Q17 — Candidate permutation

For semantically identical supplied candidate sets in different physical order:

```text
canonical semantic target = unchanged
sourceBindingOrdinal       = unchanged audit address
batch-local candidate slot  = may change
exact selected binding      = unchanged
label bytes                 = unchanged when the source key and exact source
                               binding are unchanged
```

The request permutation transports candidate features and ordinals together. The
materializer never computes a target from the post-permutation index or rewrites
the exact target from a feature projection. A candidate permutation that drops,
duplicates or changes a source candidate is a transport failure, not a new label.

## 16. Planned implementation tests (RED before implementation)

No tests are added or run by this design task. The later implementation starts
with these focused RED cases before production code is written:

| Test | Initial RED assertion | Required green behavior |
| --- | --- | --- |
| `action_candidates_selected_result_materializes_one_target` | label target type/validator is absent | One exact `chosenSemanticAction` target resolves to the selected supplied candidate |
| `folded_options_selected_result_materializes_one_target` | folded target path is absent | One exact `chosenSemanticResponse` target resolves to the selected response candidate |
| `same_source_and_config_are_byte_identical` | sidecar writer/identity is absent | Two runs produce identical label and manifest bytes/IDs |
| `semantic_target_resolves_to_exactly_one_supplied_candidate` | join/unique-membership validator is absent | Zero or multiple matches reject; exactly one passes |
| `structured_result_is_expected_no_label` | structured accounting is absent | `STRUCTURED_DOMAIN_NOT_SCOREABLE` is counted; no label is fabricated |
| `test_partition_is_skipped_before_teacher` | split-aware materializer boundary is absent | No request, Teacher call, target inspection or label for TEST; `testRowsConsumed=0` |
| `wrong_teacher_config_digest_rejects` | provenance validator is absent | Exact digest mismatch aborts/rejects fail closed |
| `wrong_teacher_policy_identity_rejects` | policy identity gate is absent | Policy mismatch is not accepted as the same Teacher |
| `wrong_teacher_execution_schedule_or_admission_rejects` | execution/admission provenance gate is absent | Schedule, seed, cursor, scope, admission purpose/result or plan digest mismatch fails closed |
| `missing_or_ambiguous_perspective_roster_seat_rejects` | source-seat authority gate is absent | Missing, duplicate, malformed or inconsistent `perspectivePlayerId` roster ownership fails before Teacher RNG/result use |
| `wrong_materializer_identity_or_version_rejects` | label manifest version gate is absent | Unknown/mismatched materializer identity fails closed |
| `wrong_source_binding_ordinal_rejects` | ordinal membership gate is absent | Negative, unknown or mismatched ordinal is rejected |
| `exact_source_action_mismatch_rejects` | exact binding comparison is absent | A different action at the same-looking slot is rejected |
| `selected_candidate_absent_from_domain_rejects` | executable membership gate is absent | Missing/unauthorized candidate cannot become a label |
| `duplicate_source_decision_rejects` | label-key uniqueness is absent | Duplicate key aborts publication |
| `conflicting_label_for_one_source_decision_rejects` | conflict detection is absent | Different semantic targets for one key abort |
| `unknown_label_schema_version_rejects` | strict version gate is absent | Unknown future artifact version fails closed |
| `canonical_and_digest_tampering_rejects` | byte/digest verifier is absent | Changed line, manifest or digest is rejected |
| `candidate_permutation_preserves_semantic_target` | permutation equivalence is absent | Physical slot may change; canonical semantic target does not |
| `model_input_has_no_label_or_teacher_fields` | input/target isolation is absent | C1_00 input contains no label, score, diagnostics, IDs or private fields |
| `accounting_reconciles_source_rows_and_labels` | accounting equations are absent | Labels, expected NO_LABEL and invalid-source counts reconcile exactly |
| `bare_teacher_result_without_exact_request_rejects` | request/result coupling is absent | A result without the exact request boundary is not materializable |

The test fixtures must use small synthetic source samples and existing C1_00
fixtures. They must not open the historical multi-gigabyte artifact.

## 17. Future full-artifact acceptance procedure

This section is a plan only. It is not run by C1_05 design.

### Exact historical inputs

The later run may use the exact accepted artifact only when these identities still
match:

```text
SOURCE_ARTIFACT_ID=be545edcb7809a34d78ab9be03476b3cd29ab42e54e6c7f7e20b25b5bcc05a4a
SOURCE_DATASET_ID=69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03
SOURCE_MANIFEST_CONTENT_DIGEST=de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2
SOURCE_DERIVED_VIEW_SCHEMA_IDENTITY=argentum-ml-derived-learner-view@v1
MODEL_FACING_CONTRACT_IDENTITY=argentum-ml-model-facing-decision-sample@v1
SPLIT_CONTRACT_IDENTITY=argentum-ml-dataset-split@v1
TEACHER_ADMISSION_RESULT=ADMITTED_LIMITED_FLAT_REFERENCE_BOOTSTRAP
TEACHER_POLICY_IDENTITY=argentum-ml-public-observation-bootstrap-teacher@v1
TEACHER_CONTRACT_IDENTITY=argentum-ml-teacher-bootstrap@v1
TEACHER_CONFIG_DIGEST=fa358597c09ce466e1be1823de485e0accd84be622184fa1d6505806aff0d7d8
SELECTION_CONTRACT_IDENTITY=argentum-ml-policy-selection@v2
POLICY_RNG_IDENTITY=argentum-ml-policy-tie-rng@v1
TEACHER_POLICY_TIE_SCHEDULE_IDENTITY=argentum-ml-c1-03-teacher-policy-tie-schedule@v1
TEACHER_POLICY_TIE_SEED=0
INITIAL_POLICY_TIE_CURSOR=0
TEACHER_TIE_STATE_SCOPE=semanticEpisodeId|teacherPolicyIdentity|seatIndex
LEGACY_A9_POLICY_SEED_REUSED=false
TEACHER_ADMISSION_PURPOSE_IDENTITY=argentum-ml-flat-reference-bootstrap@v1
TEACHER_ADMISSION_PLAN_IDENTITY=argentum-ml-c1-03-teacher-quality-and-admission@v1
TEACHER_ADMISSION_PLAN_DIGEST=bc186eb4df9830039fb1b0e474700afdb82e1cca033483afcad6bfd8d24fee79
```

If any source or accepted Teacher identity differs, the run must stop and declare
a new source/materialization identity. It must not silently substitute a new
artifact or rebless the historical count.

### Required future checks

1. Verify the exact source artifact manifest and all source digests.
2. Verify the admitted Teacher/configuration, its execution/tie-schedule
   binding, and the source-derived roster seat authority without changing the
   Teacher.
3. Run only the C1_05 materialization boundary; do not train or create a model.
4. Verify the sidecar without rerunning the Teacher and join every label back to
   its source sample.
5. Require:

```text
EXPECTED_SELECTED_LABELS=59211
EXPECTED_ACTION_LABELS=57062
EXPECTED_FOLDED_LABELS=2149
OWNERSHIP_FAILURES=0
TRUST_FAILURES=0
SEAT_AUTHORITY_FAILURES=0
TEST_ROWS_CONSUMED=0
TEST_TEACHER_CALLS=0
TEST_LABELS_MATERIALIZED=0
```

6. Reconcile `TRAIN`/`VALIDATION` source counts with materialized labels,
   expected `NO_LABEL` reasons and rejected invalid source bindings. The known
   accepted characterization provides a diagnostic expectation of 54,556
   C1_00-unbindable flat rows and 246 structured `NO_LABEL` rows; any difference
   must be investigated at the first divergent source key.
7. Confirm the family totals 57,062 + 2,149 = 59,211 and that no label is a
   structured response.
8. Compare the produced sidecar identity/digests with the run manifest. Do not
   compare only a count.

This acceptance procedure does not require a second full historical run. The
byte-identical two-run property is proven first by the focused fixture and
synthetic permutation tests; a second full run requires a separate explicit
resource authorization.

## 18. Explicit non-goals

C1_05 V1 does not:

- implement or modify production Python/Kotlin Teacher code;
- modify Teacher behavior, scoring, Selection V2 or PolicyTieRng V1;
- change `TrajectoryV1`, `PlayerObservationV1`, `CompleteLegalDomain`, source
  replay, or split semantics;
- materialize the historical 59,211 labels during this design task;
- run the historical approximately 8 GB artifact;
- train a neural network, create a PyTorch model, add an optimizer/loss, or run
  a 1k–10k learner smoke;
- introduce HF Datasets, Arrow, Accelerate, `huggingface_hub`, upload, or public
  artifact transport;
- use Teacher score vectors as dense regression targets;
- flatten structured domains or create a structured label head;
- choose payment sources, targets, modes, X, ordering, distribution or any other
  subdecision implicitly;
- change model-facing input semantics or leak label/provenance into input;
- generate trajectories, a larger corpus, RL data or self-play data;
- start C1_06 or any later learner/RL milestone;
- create a PR or claim implementation authorization.

The first later learner smoke remains a separate task and must prove finite loss
and gradients, tiny overfit, variable-size candidate batching, checkpoint
round-trip, train/inference agreement, deterministic inference and Trackio
observability. None of those behaviors is implemented or tested here.

## 19. Implementation gate and blockers

### Q17 — Does implementation require changing a frozen C0/C1 contract?

No. The target semantics, source binding, split, Selection V2, PolicyTieRng,
model-facing input and Teacher identity remain unchanged. The implementation
needs:

- a minimal executable `SupervisedPolicyTargetV1` validation representation that
  consumes the frozen C0 shape;
- a strict C1_05 sidecar manifest/row reader and identity calculator;
- a materializer adapter over existing reader/request/result primitives; and
- focused tests listed above.

Adding these is implementation of the already frozen boundary, not a contract
reinterpretation. Populating `label_materializer_identity` would be a contract
change and is explicitly out of scope.

### Q18 — What is RED before implementation?

The test list in Section 16 is the required RED inventory. At minimum, the first
RED commit must fail for the absent target validator, absent label artifact
identity, absent request/result coupling, and absent membership/permutation/
TEST/privacy/accounting gates. The historical source must not be used to create
the RED state.

### Q19 — What later evidence authorizes the learner smoke?

C1_05 acceptance alone is not learner authorization. A later authorization must
have, at minimum:

```text
C1_05_DESIGN_REVIEW_PASS=YES
C1_05_IMPLEMENTATION_REVIEW_PASS=YES
C1_05_LOCAL_CONTRACT_TESTS_PASS=YES
C1_05_ARTIFACT_READER_TESTS_PASS=YES
C1_05_FULL_ARTIFACT_ACCEPTANCE_PASS=YES
SOURCE_ARTIFACT_ID_AND_LABEL_ARTIFACT_ID_EXACT=YES
TEST_ROWS_CONSUMED=0
TEST_TEACHER_CALLS=0
PRIVACY_BOUNDARY_PASS=YES
DETERMINISM_AND_PERMUTATION_PASS=YES
```

Only a separate C1_06 task can authorize the bounded learner smoke. C1_05 does
not authorize training merely because a sidecar is readable or a label count is
nonzero.

### Q20 — What blocks C1_05 implementation?

Implementation is blocked if any of these conditions holds:

1. `origin/main` no longer equals the accepted base without a new exact-SHA
   review.
2. The source dataset, C1_00 derived artifact, source manifest digest or Teacher
   admission identity differs from the frozen inputs without a new artifact
   identity.
3. C0 target semantics, source-key meaning, split membership, Selection V2,
   PolicyTieRng or model-facing privacy rules are contradictory or missing.
4. The exact C1_03 tie schedule/seed/cursor/scope, the source-derived roster
   seat authority, or the admitted purpose/result/plan identity/digest is missing
   or contradictory.
5. A result cannot be coupled to its exact request and exact source binding
   without changing the frozen Teacher result contract.
6. The implementation would need to choose or repair a structured subdecision,
   infer a missing payload, truncate a domain, use a candidate slot as identity,
   or consult hidden engine state.
7. The source reader cannot verify the source artifact and report the TEST
   semantic boundary honestly.
8. A digest, canonical-byte, duplicate, conflict, privacy or unknown-version
   failure is handled best-effort rather than fail-closed.
9. A P1 or P2 independent review finding remains unresolved.

## 20. Explicit question matrix

| Question | Answer |
| --- | --- |
| Q1 canonical target | Exact Teacher `ExactSemanticSourceBinding` action/response, not the public feature view |
| Q2 exact source binding | Source key + request ordinal + exact `ExactSemanticSourceBinding` equality, retained in the sidecar binding channel |
| Q3 domain membership | Existing strict validation: exactly one semantic complete-domain match, and that matched member is executable; other executable members may exist |
| Q4 flat families | Action target for `ACTION_CANDIDATES`; response target for `FOLDED_DECISION_OPTIONS` |
| Q5 structured outcomes | Typed `NO_LABEL`, counted by reason, no fabricated row |
| Q6 candidate index | Forbidden as canonical identity; ordinal is binding/audit only |
| Q7 permutation | Semantic target unchanged; only a derived batch slot may change |
| Q8 artifact identity | Content-addressed label sidecar `labelArtifactId` |
| Q9 identity inputs | Exact source, target, Teacher, Selection/RNG, C1_03 execution schedule, admission purpose/result/plan, materializer, configuration, content and accounting identities |
| Q10 split | Inherit episode-level C0 split; materialize TRAIN and VALIDATION only |
| Q11 TEST | No semantic row consumption, Teacher call or label; integrity I/O reported separately if unavoidable |
| Q12 duplicates | Duplicate/conflicting source key aborts; different config means different artifact |
| Q13 no Teacher rerun | Yes; source join, target membership, bytes and manifest prove the artifact |
| Q14 model fields | C1_00 input only, plus target only through the loss channel |
| Q15 private fields | Binding/provenance remain outside model input; no raw/private additions |
| Q16 Teacher seam | Reserved `None` seam is insufficient and remains unchanged; sidecar manifest owns provenance |
| Q17 frozen contracts | No semantic contract change required; only executable representation/sidecar implementation later |
| Q18 RED tests | Section 16, including positive, negative, permutation, privacy, accounting and TEST gates |
| Q19 learner authorization | Separate C1_06 after implementation/full-artifact acceptance evidence |
| Q20 blockers | Section 19: exact-SHA drift, identity drift, missing binding, leakage, heuristics, weak failure handling or unresolved P1/P2 |

## 21. Independent self-review result

The final design was reviewed against the requested hazards:

```text
PARALLEL_CONTRACT_INVENTION=NO
CANDIDATE_INDEX_SEMANTIC_LEAKAGE=NO
TEST_LEAKAGE=NO
TEACHER_AUTHORITY_DRIFT=NO
BEHAVIOR_POLICY_LEAKAGE=NO
STRUCTURED_LABEL_INVENTION=NO
SOURCE_BINDING_WEAKNESS=NO
EXACT_TARGET_IS_SOURCE_BINDING=YES
TEACHER_EXECUTION_AND_ADMISSION_PROVENANCE_COMPLETE=YES
UNSTABLE_IDENTITY_USE=NO
DUPLICATE_HANDLING_MISSING=NO
PROVENANCE_MISSING=NO
RAW_PRIVATE_FIELD_LEAKAGE=NO
AUTOPAY_OR_HEURISTIC_COMPLETION=NO
FUTURE_LEARNER_COUPLING=NO
HF_ARROW_ACCELERATE_SCOPE=NO

P1=0
P2=0
P3=0
```

The only current implementation gap is explicit and bounded: the frozen C0
target identity has no runtime Python validator, and the label-artifact reader
does not yet exist. This design specifies their smallest later boundary without
changing frozen semantics. It is not a reason to modify the Teacher identity or
to run materialization now.

## 22. Design conclusion

```text
C1_05_DESIGN_PASS=YES
C1_05_IMPLEMENTATION_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
C1_06_AUTHORIZED=NO
RL_AUTHORIZED=NO
SELF_PLAY_AUTHORIZED=NO
```

The accepted design proves the intended chain at the contract level:

```text
trusted source decision
    -> admitted public-information Teacher selection
    -> exact selected source candidate/response
    -> deterministic, source-bound supervised target sidecar
    -> later learner join
```

The design stops here for exact-SHA review.
