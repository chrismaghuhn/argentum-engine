# C1_05 Bootstrap Label Materializer V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans (recommended inline here). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the reviewed C1_05 source-bound supervised-policy label sidecar without changing Teacher, trajectory, model-input, split, Selection V2, or PolicyTieRng semantics.

**Architecture:** Add strict label contracts and a manifest.json plus labels.ndjson sidecar under argentum_ml.data. Reuse reader-issued ValidatedDerivedSample, exact source bindings, the existing C1_00 request construction, and the admitted Teacher result. Extract C1_03 roster-seat authority into a shared helper so the stateful PolicyTieRng stream is keyed by the validated source perspective.

**Tech Stack:** Python 3.13, standard library only, existing A3 canonical JSON/SHA-256 helpers, unittest, existing C1 fixtures, no PyTorch/HF/Arrow/Accelerate.

---

## Task 1: RED coverage for the reviewed contract

**Files:**
- Create: ml/tests/test_label_materializer.py

- [ ] Write failing tests before production code. Use a synthetic source candidate whose raw actionSemantics.playerId differs from model-facing actionSemantics.actorRole. Cover exact action target, exact folded response target, multiple executable candidates, missing/ambiguous roster seat, structured NO_LABEL, and wrong execution/admission provenance.

The tests must import these wished-for APIs:

~~~python
materialize_selected_label
teacher_seat_index
TeacherExecutionBindingV1
TeacherExecutionError
LabelMaterializerError
~~~

- [ ] Run the focused file:

~~~powershell
cd ml
py -3.13 -m unittest tests.test_label_materializer -v
~~~

Expected: a targeted missing-implementation failure, not a fixture error.

## Task 2: Shared Teacher execution and request authority

**Files:**
- Create: ml/src/argentum_ml/teacher/execution.py
- Create: ml/src/argentum_ml/teacher/request_factory.py
- Modify: ml/src/argentum_ml/characterization/c1_03.py
- Modify: ml/src/argentum_ml/teacher/__init__.py
- Test: ml/tests/test_label_materializer.py

- [ ] Implement TeacherExecutionError, teacher_seat_index(sample), and frozen TeacherExecutionBindingV1. The seat helper must match sourceReference.perspectivePlayerId to exactly one provenance.environmentIdentity.roster[].playerId, require a nonnegative seatIndex, and reject malformed/duplicate/missing ownership.

TeacherExecutionBindingV1.reference() binds the accepted C1_03 schedule identity, seed 0, initial cursor 0, state scope semanticEpisodeId|teacherPolicyIdentity|seatIndex, legacyA9PolicySeedReused=false, flat-reference admission purpose/result, and the accepted C1_03 plan identity/digest. Its config_digest hashes the reviewed canonical preimage with schema argentum-ml-c1-05-teacher-execution-config@v1.

- [ ] Route C1_03 private _seat_index through the shared helper, translating TeacherExecutionError to C1_00AuthorityFailure. Preserve current behavior.

- [ ] Extract C1_03 teacher_request, _selected_source_ordinal, _required_payload_fields, and _affordable into teacher/request_factory.py as teacher_request_from_validated_sample. Keep c1_03.teacher_request as a compatibility wrapper and preserve expected Action unbindability, exact ordinals, and structured transport.

- [ ] Run:

~~~powershell
cd ml
py -3.13 -m unittest tests.test_label_materializer tests.test_c1_03_characterization -v
~~~

## Task 3: Exact target and source-membership contracts

**Files:**
- Modify: ml/src/argentum_ml/contracts/identities.py
- Modify: ml/src/argentum_ml/contracts/__init__.py
- Modify: ml/src/argentum_ml/data/derived_reader.py
- Create: ml/src/argentum_ml/data/label_contracts.py
- Modify: ml/src/argentum_ml/data/__init__.py
- Test: ml/tests/test_label_materializer.py

- [ ] Add closed C1_05 identities for the supervised target, label artifact schema/id, source decision key, and Teacher execution config.

- [ ] Implement deeply immutable SupervisedPolicyTargetV1. Serialize exactly one chosenSemanticAction or chosenSemanticResponse from SelectedTeacherResultV1.exact_source_binding. Never construct the target from feature_view.

- [ ] Add validate_exact_source_binding_membership(target, selected_exact_source_binding, complete_legal_domain) as a narrow public wrapper over the existing strict reader membership logic. Preserve exactly one semantic match plus executability of the matched member; allow other executable members.

- [ ] Run the focused target tests and confirm action/folded targets, multiple executable candidates, and source membership are green.

## Task 4: Strict label artifact reader and writer

**Files:**
- Create: ml/src/argentum_ml/data/label_artifact.py
- Modify: ml/src/argentum_ml/data/__init__.py
- Create: ml/tests/test_label_artifact.py

- [ ] Write RED tests for exact fields, canonical manifest/NDJSON bytes, labelArtifactId, manifest/content digests, source bindings, target/binding equality, duplicate/conflicting keys, unknown versions, tampering, partition counts, and zero TEST labels.

- [ ] Implement LabelArtifactError, LabelArtifactManifestV1, and LabelArtifactReader with existing canonical JSON/SHA-256 helpers. Reject unknown fields/versions, noncanonical lines, symlinks, digest mismatch, duplicate keys, and TEST labels. Keep the exact selected binding in the sidecar binding channel and exclude model input.

- [ ] Implement staging plus atomic publication of the complete label-artifact directory containing manifest.json and labels.ndjson. Verify bytes, digests, and counts before publication.

- [ ] Run:

~~~powershell
cd ml
py -3.13 -m unittest tests.test_label_artifact tests.test_label_materializer -v
cd ..
just ml-test
just ml-check
~~~

## Task 5: Source-bound materializer and accounting

**Files:**
- Create: ml/src/argentum_ml/data/label_materializer.py
- Modify: ml/src/argentum_ml/data/__init__.py
- Test: ml/tests/test_label_materializer.py

- [ ] Implement materialize_selected_label(validated_sample, request, result, execution). Validate source key, family/count/config, ordinal, exact binding equality, derived seat, complete-domain membership, and matched-member executability. Emit exact target, exact selected binding, ordinal audit, teacherSeatIndex, and bounded result provenance. Never call the scorer or use score vectors.

- [ ] Implement immutable outcomes for MATERIALIZED, EXPECTED_NO_LABEL, INVALID_SELECTED_LABEL, and global fail-closed errors. Count every NoLabelReason per partition and keep invalid selected labels distinct.

- [ ] Implement bounded source-artifact materialization. Open the existing strict reader; skip TEST before request/Teacher use; derive seat authority; obtain and commit one stateful schedule state per episode/policy/seat; construct requests through the extracted factory; classify rows; reconcile accounting; and publish the sidecar.

- [ ] Add negative/privacy tests for wrong source/Teacher/execution/admission identity, TEST result, invalid ordinal, binding mismatch, absent candidate, structured flattening, duplicate/conflicting keys, canonical/digest tampering, private input fields, and missing/ambiguous roster seat.

- [ ] Run:

~~~powershell
cd ml
py -3.13 -m unittest tests.test_label_materializer tests.test_label_artifact -v
~~~

No Historical artifact may be opened.

## Task 6: Full regression and code-review stop

- [ ] Run the full local gates:

~~~powershell
just ml-test
just ml-check
~~~

- [ ] Verify:

~~~powershell
git status --short --branch
git diff --check origin/main..HEAD
git diff --name-only origin/main..HEAD
~~~

No generated labels, model, training output, raw source data, or Historical artifact output may exist.

- [ ] Commit and push only after fresh gates pass:

~~~powershell
git add ml/src ml/tests docs/superpowers/plans/2026-09-14-c1-05-bootstrap-label-materializer-v1.md
git commit -m "feat: implement C1-05 bootstrap label materializer"
git push origin chris/c1-05-bootstrap-label-materializer-design-20260914
~~~

- [ ] Stop for exact-SHA code review. Report new HEAD/remote HEAD/base, changed-file count, production scope, fresh test results, FULL_ARTIFACT_MATERIALIZATION_STARTED=0, LABELS_MATERIALIZED=0, and stop. Do not start the approximately 8 GB run, merge, create a PR, or start C1_06.

## Self-review checklist

- [ ] Exact source target is never replaced by feature_view.
- [ ] Multiple executable candidates are accepted when the selected value has one complete-domain match and that match is executable.
- [ ] Seat index comes only from the exact source perspective/roster match.
- [ ] Schedule state carries across decisions and never resets per row.
- [ ] Execution/admission provenance is exact and content-addressed.
- [ ] TEST receives no semantic Teacher request/result/label.
- [ ] Structured decisions remain typed NO_LABEL.
- [ ] Candidate slots never become target identity.
- [ ] No scores, RNG stream keys, raw GameState, hidden data, or Teacher metadata enter model input.
- [ ] Duplicate/conflicting source keys fail closed.
- [ ] No Historical artifact, training run, learner smoke, RL, self-play, HF/Arrow/Accelerate, or PR was started.
