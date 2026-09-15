# C1_06 Feed-Forward Candidate Scorer GPU Smoke Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consume the accepted C1_05 label sidecar without Teacher calls, train a small stateless candidate scorer on a real `cuda:0` device, prove tiny overfit and finite CUDA backward, write a strict Safetensors checkpoint, reload it, and prove deterministic offline inference.

**Architecture:** Reuse `LabelArtifactReader`, `DerivedArtifactReader`, `VariableDomainItem`, C0 model-facing inputs, Selection V2, `ArgentumCheckpointManifestV1`, and the C1_04 Safetensors boundary. C1_06 adds only a feed-forward reference model and a bounded offline runner. Because C0 freezes transport and semantic boundaries but not numerical MLP dimensions, the runner owns one explicit `argentum-ml-c1-06-feed-forward-candidate-scorer@v1` architecture/config identity; it does not create a competing policy-target contract.

**Tech Stack:** Python 3.13, PyTorch 2.14.0 CUDA build installed environment-only through the official PyTorch distribution mechanism, Safetensors, existing standard-library contracts, unittest, local filesystem only.

---

### Task 1: Freeze the preflight and trusted-input gate

**Files:**
- Create: `ml/tests/test_c1_06_feed_forward.py`
- Create: `ml/src/argentum_ml/learner/c1_06.py`

- [ ] **Step 1: Write RED tests for required artifact identities and split behavior.**

Add tests that use a tiny committed/synthetic fixture and assert that the C1_06 loader:

```python
load_c1_05_training_view(
    label_root=label_root,
    source_artifact_root=source_root,
    expected_source_artifact_id="be545edcb7809a34d78ab9be03476b3cd29ab42e54e6c7f7e20b25b5bcc05a4a",
    expected_label_artifact_id="22c6d18fa05301010b7057a4f524ef689a89626aa7dfb7582d73572a4f0f1c52",
)
```

uses the authoritative label reader, rejects a wrong label/source identity, emits only TRAIN/VALIDATION records, and reports zero TEST rows/labels/batches. The loader must expose the exact label/source digests to checkpoint provenance and must never call the Teacher.

- [ ] **Step 2: Run the focused RED test.**

Run:

```powershell
cd ml
py -3.13 -m unittest tests.test_c1_06_feed_forward.C1_06DataGateTests -v
```

Expected: fail because the C1_06 loader and typed records do not exist yet.

- [ ] **Step 3: Implement the smallest trusted data adapter.**

Implement a typed C1_06 sample record containing only:

```text
model_input
candidate_feature_views
selected_candidate_index  # derived from exact semantic target membership, never a stored semantic ID
partition
source_key_for_audit
```

Build the label lookup from the stable source key, resolve the selected semantic target against the current complete source domain, map it to the matching supplied model-facing candidate, and reject missing/duplicate/conflicting membership. Use a deterministic semantic-key sort for bounded selection; never use file completion order, allocation order, source ordinal as preference, TEST, or random row splitting.

- [ ] **Step 4: Run the focused GREEN test.**

Run the same command and require PASS, including wrong-identity and TEST rejection cases.

### Task 2: Add the explicit CUDA-only gate and kernel proof

**Files:**
- Modify: `ml/src/argentum_ml/learner/c1_06.py`
- Modify: `ml/tests/test_c1_06_feed_forward.py`

- [ ] **Step 1: Write RED tests for device policy.**

Add tests equivalent to:

```python
with patch("torch.cuda.is_available", return_value=False):
    with self.assertRaises(C1_06GpuGateError):
        require_cuda_device(torch)

with patch("torch.cuda.is_available", return_value=True), patch("torch.cuda.device_count", return_value=1):
    self.assertEqual(require_cuda_device(torch), torch.device("cuda:0"))
```

Also test that a requested CPU device is rejected and that training is not entered after a blocked gate.

- [ ] **Step 2: Run RED.**

Run the focused GPU-gate tests; they must fail for the missing production gate.

- [ ] **Step 3: Implement the explicit gate.**

Implement:

```python
device = torch.device("cuda:0")
if not torch.cuda.is_available() or torch.cuda.device_count() < 1:
    raise C1_06GpuGateError("GPU_GATE=BLOCKED")
```

Reject CPU fallback, run a real 2048x2048 CUDA matmul probe, call `torch.cuda.synchronize`, assert CUDA tensors and non-zero peak allocated memory, and return the actual device name/capability/runtime values. Do not add a configurable CPU fallback to the acceptance path.

- [ ] **Step 4: Run GREEN and record the environment gate.**

Run the focused tests, `nvidia-smi`, and the CUDA probe. If the official environment still exposes `torch 2.14.0+cpu`, install only the supported official CUDA wheel for the pinned `2.14.0` version without changing `pyproject.toml` or committing environment files, then rerun the probe. If no compatible official CUDA build exists, stop with `GPU_GATE=BLOCKED_TOOLING` and do not write learner code beyond tests/gates.

### Task 3: Implement the stateless variable-candidate scorer

**Files:**
- Modify: `ml/src/argentum_ml/learner/c1_06.py`
- Modify: `ml/tests/test_c1_06_feed_forward.py`

- [ ] **Step 1: Write RED tests for model shape and permutation semantics.**

Test real model code with candidate counts 1, 2, and 5. Assert one scalar per supplied candidate, no truncation, finite float32 scores, no ordinal feature, and equal semantic candidate scores when the same candidate records are permuted physically. Assert model parameters are on `cuda:0` during the real GPU path.

- [ ] **Step 2: Run RED.**

Run the model-focused tests and confirm the expected missing-symbol failure.

- [ ] **Step 3: Implement the C1_06 reference model.**

Use a shared candidate scorer:

```text
public model-facing observation encoding
        + public candidate feature encoding
        -> shared feed-forward MLP
        -> one scalar per supplied candidate
```

Use a deterministic canonical-public-feature encoder that consumes only C0 `input` and candidate `feature_view` JSON; exclude target, binding, source reference, provenance, raw/private fields, source ordinal, and physical slot. Freeze an explicit architecture/config preimage, use float32, and make the model stateless. The scorer must accept variable candidate counts without padding semantics leaking into the score.

- [ ] **Step 4: Run GREEN and refactor only after green.**

Require focused model tests to pass and confirm `scores.device == cuda:0`, finite values, and no CPU fallback.

### Task 4: Add the supervised loss, tiny overfit, and bounded smoke runner

**Files:**
- Modify: `ml/src/argentum_ml/learner/c1_06.py`
- Modify: `ml/tests/test_c1_06_feed_forward.py`

- [ ] **Step 1: Write RED tests for loss/backward/finite health.**

Assert scores, loss, gradients, and updated parameters are finite; loss and tensors are CUDA tensors; optimizer steps are real; selected target indices always exist in the supplied complete candidate domain; TEST cannot enter a batch.

- [ ] **Step 2: Run RED.**

Run the focused loss tests and verify they fail for the absent training step.

- [ ] **Step 3: Implement supervised candidate-selection loss and runner.**

Use cross-entropy over the supplied candidate scores and exact derived target index. Run a deterministic TRAIN-only tiny subset selected by stable semantic key, prove strong loss decrease and agreement increase, then run a bounded TRAIN/VALIDATION smoke without TEST. Synchronize CUDA around timing boundaries and record candidate-count summaries, loss, top-1 agreement, steps, wall time, and throughput. Reject non-finite values and do not silently skip malformed rows.

- [ ] **Step 4: Run GREEN and assert tiny-overfit evidence.**

Require `TINY_OVERFIT_PASS=YES`, finite loss/gradients, and `TEST_ROWS_CONSUMED=0`, `TEST_LABELS_CONSUMED=0`, `TEACHER_CALLS=0`, `NEW_LABELS_GENERATED=0`.

### Task 5: Implement strict Safetensors checkpoint round-trip

**Files:**
- Modify: `ml/src/argentum_ml/learner/c1_06.py`
- Modify: `ml/tests/test_c1_06_feed_forward.py`

- [ ] **Step 1: Write RED tests for checkpoint identity and reload.**

Test that a feed-forward manifest is `FEED_FORWARD_POLICY`, binds the source derived artifact, label artifact/digests, C1_06 architecture/config, Selection V2, numeric profile, training recipe/run identities, and Safetensors container. Test strict reload rejects changed bytes, wrong tensor names/shapes/dtypes, and wrong source/label identity; test pre-save and post-reload scores match deterministically.

- [ ] **Step 2: Run RED.**

Run the checkpoint-focused tests and verify missing C1_06 checkpoint construction fails.

- [ ] **Step 3: Implement checkpoint publication through existing primitives.**

Save only tensor state through `save_state_dict`; create the complete accepted manifest with a feed-forward kind and `NONE_FOR_FEED_FORWARD` recurrent identity; compute its strict checkpoint ID; reload through `ArgentumCheckpointManifestV1.from_path` and `load_state_dict`; verify tensor metadata and deterministic validation inference. Never use pickle or `torch.save`.

- [ ] **Step 4: Run GREEN.**

Require Safetensors verification, strict reload, post-reload inference, and deterministic inference to pass.

### Task 6: Add the acceptance report and run all verification gates

**Files:**
- Create: `docs/ml/c1-06-feed-forward-candidate-scorer-gpu-smoke-2026-09-15.md`
- Modify: `ml/tests/test_c1_06_feed_forward.py` only if a missing focused characterization is found

- [ ] **Step 1: Write the report from actual run evidence.**

Record exact base/head/source/label identities, artifact verification, split/Teacher counters, architecture/config, parameter count, objective/optimizer/steps, Torch/CUDA facts, kernel proof, every tensor device, CUDA memory, tiny-overfit metrics, train/validation metrics, checkpoint identities, reload/inference results, timing, limitations, and the explicit no-CPU/no-TEST/no-Teacher/no-RL boundaries.

- [ ] **Step 2: Run the focused and full gates.**

Run:

```powershell
cd ml
py -3.13 -m unittest tests.test_c1_06_feed_forward -v
cd ..
just ml-test
just ml-check
git diff --check
```

The real CUDA smoke must be run separately with explicit `cuda:0` and its output captured in the report. Do not rerun C1_05 materialization or the authoritative label Reader.

- [ ] **Step 3: Perform final diff/self-review.**

Check TEST leakage, CPU fallback, candidate truncation, ordinal-as-feature, Teacher calls, private fields, pickle/torch.save, checkpoint looseness, non-finite handling, network upload, recurrence/RL/gameplay changes, and P1/P2 counts.

- [ ] **Step 4: Commit and push the focused branch.**

Use a C1_06-specific commit, push to `origin`, verify remote HEAD and clean worktree, and stop for independent exact-SHA review. Do not create a PR, merge, start C1_07, or enable training beyond this bounded smoke.
