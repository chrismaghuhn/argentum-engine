# C1_00 Local Learner Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox syntax. Do not start implementation until the plan-to-code review explicitly authorizes it.

**Goal:** Implement a deterministic Kotlin A7-derived learner artifact and a strict, stdlib-only Python runtime for variable domains, Selection V2, PolicyTieRng V1, checkpoint identity, and model-independent semantic binding.

**Architecture:** `gym-trainer` is the only source-data boundary. `TrajectoryV1Reader.openPublishedDataset(publishedDatasetDirectory)` validates and streams accepted episodes into canonical JSONL plus a strict manifest. Python consumes only that derived artifact and never revalidates TrajectoryV1 or decides source trust.

**Tech Stack:** Kotlin/JDK 21, kotlinx.serialization, Kotest, existing `gym-trainer`, CPython 3.13.15, stdlib `json`/`hashlib`/`unittest`, setuptools 84.0.0 as build-only tooling, GitHub Actions, and existing `just`/`scripts/gradle-locked` conventions.

---

## Implementation boundary

The plan starts from `ce9f779bd3bd8375b6b83b9c5668b1b94ced924a` on branch `chris/c1-00-local-learner-foundation-20260912`.

```text
CURRENT_PHASE=C1
C1_AUTHORIZED=YES
C1_IMPLEMENTATION_AUTHORIZED=YES
C1_00_IMPLEMENTATION_AUTHORIZED=NO until plan-to-code review
TRAINING_AUTHORIZED=NO
SMALL_LEARNER_SMOKE_AUTHORIZED=NO
```

No task changes Rules, Gym semantics, TrajectoryV1, replay semantics, locked decks, source dataset membership, or existing `ai` architecture. No task adds a neural model, optimizer, loss, Trainer, Behavior Cloning, value head, recurrence, RL, self-play, search, MCTS, world model, Teacher admission, large corpus, Safetensors, Trackio, HF Datasets/Arrow, or performance work.

## File map

Kotlin production files under `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/`:

- `C1LearnerContractsV1.kt` — derived DTOs, identities, partition counts, and channel separation.
- `C1DatasetSplitV1.kt` — exact C0-02 split function.
- `C1ModelFacingProjectionV1.kt` — C0-01 admission, role normalization, local relations, and fail-closed projection.
- `C1SourceTieDiscriminatorV1.kt` — source-owned, permutation-invariant tie-key production/validation; no caller-supplied tie keys.
- `C1LearnerArtifactMaterializer.kt` — A7 traversal, exact bytes, digest accounting, and publication.

Kotlin tests under `gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/`:

- `C1DatasetSplitV1Test.kt`
- `C1ModelFacingProjectionV1Test.kt`
- `C1SourceTieDiscriminatorV1Test.kt`
- `C1DerivedManifestV1Test.kt`
- `C1LearnerArtifactMaterializerTest.kt`
- `C1LearnerFixtureSupport.kt`

Python package files under `ml/`:

```text
.python-version, pyproject.toml, README.md
src/argentum_ml/contracts/{__init__.py,canonical_json.py,identities.py,model_facing.py}
src/argentum_ml/data/{__init__.py,derived_reader.py,split.py,variable_batch.py}
src/argentum_ml/selection/{__init__.py,policy_tie_rng.py,selection_v2.py}
src/argentum_ml/checkpoint/{__init__.py,manifest.py}
src/argentum_ml/inference/{__init__.py,provider.py,runtime.py}
tests/{test_canonical_json.py,test_derived_reader.py,test_split.py,
       test_variable_batch.py,test_policy_tie_rng.py,test_selection_v2.py,
       test_checkpoint_manifest.py,test_inference_runtime.py}
tests/fixtures/{a3_canonical_kats.json,checkpoint_manifest_v1.json,
                derived_artifact_v1/manifest.json,
                derived_artifact_v1/samples.ndjson}
```

Modify only `.github/workflows/ci.yml`, `justfile`, and `.gitignore` for integration. Create `docs/ml/c1-00-local-learner-foundation.md` for focused usage documentation.

---

## Task 0: Reconfirm the protected base and baseline

**Files:** None.

- [ ] **Step 1: Verify the implementation worktree and immutable base.** Run:

```powershell
git rev-parse HEAD
git rev-parse origin/main
git merge-base --is-ancestor ce9f779bd3bd8375b6b83b9c5668b1b94ced924a HEAD
if ($LASTEXITCODE -ne 0) { throw "BASE is not an ancestor of HEAD" }
git branch --show-current
git status --short --branch
git diff --check
git diff --name-only ce9f779bd3bd8375b6b83b9c5668b1b94ced924a..HEAD
```

Expected: `origin/main=ce9f779bd3bd8375b6b83b9c5668b1b94ced924a`, merge-base check PASS, branch `chris/c1-00-local-learner-foundation-20260912`, a clean worktree, and a pre-implementation BASE..HEAD diff containing only the approved spec/plan documentation. Do not require HEAD to equal BASE; the spec and plan commits intentionally precede implementation.

- [ ] **Step 2: Verify the protected original checkout.** Run `git -C C:\argentum-engine status --short --branch`. Only the existing `StackResolver.kt` modification may be present. Never stage, stash, reset, clean, edit, or reformat it.

- [ ] **Step 3: Record `UNRELATED_STACKRESOLVER_CHANGE_TOUCHED=NO` in the final report.** Do not create a status artifact.

---

## Task 1: Add derived contract DTOs and the exact C0-02 split runtime

**Files:**

- Create `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/C1LearnerContractsV1.kt`.
- Create `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/C1DatasetSplitV1.kt`.
- Test `gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1DatasetSplitV1Test.kt`.
- Test `gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1DerivedManifestV1Test.kt`.

- [ ] **Step 1: Write independent split KATs.** Add these assertions before production code:

```kotlin
C1DatasetSplitV1.bucket("0".repeat(64)) shouldBe 75
C1DatasetSplitV1.assign("0".repeat(64)) shouldBe C1DatasetPartition.TRAIN
C1DatasetSplitV1.bucket("09".repeat(32)) shouldBe 88
C1DatasetSplitV1.assign("09".repeat(32)) shouldBe C1DatasetPartition.VALIDATION
C1DatasetSplitV1.bucket("1c".repeat(32)) shouldBe 91
C1DatasetSplitV1.assign("1c".repeat(32)) shouldBe C1DatasetPartition.TEST
```

Also test lowercase 64-hex validation and prove that trajectory ID, collection job ID, decision count, source order, and filesystem order are not inputs.

- [ ] **Step 2: Run `just test-class C1DatasetSplitV1Test` and confirm RED.** The expected failure is missing `C1DatasetSplitV1`/`C1DatasetPartition`, not a fixture typo. If the Windows wrapper fails before Gradle, record that separately and use `gradlew.bat` only as native fallback evidence.

- [ ] **Step 3: Add exact identities and immutable DTOs.** Define:

```kotlin
const val C1_DERIVED_VIEW_SCHEMA_IDENTITY = "argentum-ml-derived-learner-view@v1"
const val C1_DERIVED_ARTIFACT_IDENTITY_SCHEMA = "argentum-ml-derived-artifact-id@v1"
const val C1_SPLIT_CONTRACT_IDENTITY = "argentum-ml-dataset-split@v1"
```

Create `enum class C1DatasetPartition { TRAIN, VALIDATION, TEST }`, `C1PartitionCounts`, `C1MaterializerImplementationIdentity`, `C1DerivedSourceReference`, `C1DerivedTargetChannel`, `C1DerivedBindingChannel`, and `C1DerivedSampleV1`. Physical samples have only a format version; they do not add a second model-facing wire identity. The manifest carries the single physical `C1_DERIVED_VIEW_SCHEMA_IDENTITY` plus its format version. `modelFacingContractIdentity` remains a binding field, not a new physical sample schema. `C1DerivedBindingChannel` carries the complete domain, exact selected source binding, source-binding ordinals, and the optional map of source-produced semantic tie discriminators. `C1DerivedTargetChannel` must enforce exactly one of `chosenSemanticAction` and `chosenSemanticResponse`. `C1DerivedManifestV1` must contain source identity, derived identity, materializer identity/config digest, fixed `samples.ndjson` reference, sample digest/bytes/count, episode count, sample count, both partition-count objects, and manifest content digest.

- [ ] **Step 4: Implement `C1DatasetSplitV1.bucket` and `.assign`.** Use exactly:

```text
UTF8("argentum-ml-dataset-split@v1\n" + lowercase semanticEpisodeId)
SHA-256
unsigned big-endian uint64 from digest[0..7]
modulo 100
0..79 TRAIN, 80..89 VALIDATION, 90..99 TEST
```

Reject uppercase/non-hex/non-64-character IDs. Never accept an alternate split key or signed remainder.

- [ ] **Step 5: Add manifest RED tests.** Reject negative counts, partition-total mismatches, invalid `samples.ndjson` references, unknown version/identity, malformed digests, and missing artifact identity. Allow accepted episodes with zero samples while preserving episode counts.

- [ ] **Step 6: Run `just test-class C1DatasetSplitV1Test` and `just test-class C1DerivedManifestV1Test`; confirm GREEN.**

- [ ] **Step 7: Commit:**

```powershell
git add gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/C1LearnerContractsV1.kt gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/C1DatasetSplitV1.kt gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1DatasetSplitV1Test.kt gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1DerivedManifestV1Test.kt
git commit -m "c1: add derived learner contracts and split runtime"
```


---

## Task 2: Implement the Kotlin C0-01 model-facing projection

**Files:**

- Create: gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/C1ModelFacingProjectionV1.kt
- Create: gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/C1SourceTieDiscriminatorV1.kt
- Test: gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1ModelFacingProjectionV1Test.kt
- Test: gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1SourceTieDiscriminatorV1Test.kt

- [ ] **Step 1: Define the projection API in the tests.**

Use this API in the RED tests:

~~~kotlin
data class C1ProjectionContext(
    val datasetId: String,
    val sourceManifestContentDigest: String,
)

object C1ModelFacingProjectionV1 {
    fun project(
        trajectory: TrajectoryV1,
        record: DecisionRecordV1,
        context: C1ProjectionContext,
        partition: C1DatasetPartition,
    ): C1DerivedSampleV1
}
~~~

Construct fixtures through the existing typed PlayerObservationV1, CompleteLegalDomainV1, ChosenSemanticActionV1, and ChosenSemanticResponseV1 constructors. Do not make an invalid TrajectoryV1 fixture to simplify a test.

- [ ] **Step 2: Add RED tests for target isolation and privacy.**

Test that changing only the chosen semantic target leaves the canonical input bytes unchanged while changing the target bytes. Recursively reject gameState, rawAction, actionId, decisionId, abilityId, envId, pendingDecisionInternal, engineSeed, policySeed, source IDs, outcome, and provenance IDs from input. Assert raw EntityId strings are absent from input and candidate feature views; allow them in target/sourceReference/binding/provenance where the source contract requires them.

- [ ] **Step 3: Add RED tests for complete and structured domains.**

Cover one flat action domain, one folded domain with an unaffordable retained candidate, and all twelve existing StructuredDecisionDomain variants: targets, card selection, mode selection, distribution, ordering, split piles, search library, reorder library, combat resolution, mana sources, replacement, and budget modal. Use full membership/target assertions for the six currently Environment-V1-reachable families and one focused typed-representation test for each of the six inventoried-but-not-currently-reachable families. Assert every source member remains in binding, presence is separate from executable support, typed versions remain, ordered arrays remain ordered, duplicate requirement instances remain distinct, and the chosen value is in the original domain. Add two distinct source candidates with identical admitted feature JSON and distinct binding references.

- [ ] **Step 4: Run just test-class C1ModelFacingProjectionV1Test and confirm RED.**

Expected: compilation failure because C1ModelFacingProjectionV1 and its projection helpers do not exist.

- [ ] **Step 5: Prove trajectory/record ownership and implement explicit observation admission.**

Before projection, prove that the supplied DecisionRecordV1 belongs to the supplied TrajectoryV1 by matching its decisionIndex/replay coordinates, semanticDecisionId, perspective, and canonical record element against the trajectory's record at that index. Reject a record from another trajectory even if its DTO shape is valid. Then build input from an allowlist, never by serializing the entire PlayerObservationV1 and deleting guessed keys. Admit only the C0-01 categories: decision context; turn/phase/step; SELF/OPPONENT roles; public life/zone/mana/status; visible supplied cards and projected characteristics; stack sequence; and typed pending context. Keep raw IDs in sample-local inverse relation tables only. Preserve ABSENT, MASKED, and UNKNOWN_VISIBLE_IDENTITY. Never sort by raw ID, hash an ID into a feature, or use an ID as a tie preference.

- [ ] **Step 6: Implement exhaustive candidate/domain admission.**

For flat/folded candidates allowlist kind/action type, support mask, supplied cost/X/target/sacrifice/repeat bounds, colors, required payload fields, decision-option presence, public relations, and typed domain certificates. Use an exhaustive Kotlin when over TargetsDomain, CardSelectionDomain, ModeSelectionDomain, DistributionDomain, OrderingDomain, SplitPilesDomain, SearchLibraryDomain, ReorderLibraryDomain, CombatResolutionDomain, ManaSourcesDomain, ReplacementDomain, and BudgetModalDomain. Retain the full source domain in binding, build separate feature/structural views, and fail closed on unsupported version/type tags. Never use toString(), silently flatten, auto-complete, or encode presentation text.

- [ ] **Step 7: Add the source-authoritative tie-discriminator producer and validator.**

Implement C1SourceTieDiscriminatorV1 as a Kotlin-only producer called by the projection/binding path. It derives a canonical discriminator only from the C0-admitted semantic candidate/domain view, never from raw EntityId, source-binding ordinal, row index, actionId, decisionId, allocation order, or map iteration. It validates canonical bytes and permutation invariance before placing the optional discriminator map into the binding channel. If two source-distinct candidates have identical admitted semantic keys, both receive no discriminator. The producer does not accept an arbitrary caller-supplied key; Selection V2 receives only discriminator values transported from this validated binding. Add tests for raw-ID rejection, row/ordinal rejection, candidate permutation, distinct semantic keys, and symmetric candidates falling through to PolicyTieRng.

- [ ] **Step 8: Implement target/binding/provenance separation.**

Encode the original chosen semantic action or response only in target; encode complete domain and exact source binding only in binding; encode episode/environment/policy/replay/dataset metadata only in provenance/sourceReference. Verify the target through existing ChosenSemanticActionV1.from(domain, candidate, choicePayload) or ChosenSemanticResponseV1.from(domain, response) before constructing the sample.

- [ ] **Step 9: Run just test-class C1ModelFacingProjectionV1Test and just test-class C1SourceTieDiscriminatorV1Test; confirm GREEN.**

- [ ] **Step 10: Commit:**

~~~powershell
git add gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/C1ModelFacingProjectionV1.kt gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/C1SourceTieDiscriminatorV1.kt gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1ModelFacingProjectionV1Test.kt gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1SourceTieDiscriminatorV1Test.kt
git commit -m "c1: add model-facing learner projection"
~~~

---

## Task 3: Implement the A7-derived materializer and exact artifact bytes

**Files:**

- Create: gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/C1LearnerArtifactMaterializer.kt
- Test: gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1LearnerArtifactMaterializerTest.kt
- Test/modify: gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1DerivedManifestV1Test.kt
- Create: gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1LearnerFixtureSupport.kt

- [ ] **Step 0: Define the materializer entry point before the RED tests.**

Use this exact reusable API; it is the only production entry point that accepts a raw published TrajectoryV1 dataset path:

~~~kotlin
data class C1MaterializerConfig(
    val implementationIdentity: String,
    val configDigest: String,
)

object C1LearnerArtifactMaterializer {
    fun materialize(
        publishedDatasetDirectory: Path,
        outputDirectory: Path,
        sourceCommit: String,
        config: C1MaterializerConfig,
    ): C1DerivedManifestV1
}
~~~

- [ ] **Step 1: Write RED byte-framing tests.**

Build a tiny finalized published dataset through the existing TrajectoryV1Admission plus TrajectoryV1Publisher path. Convert each valid fixture through TrajectoryV1Admission.admit(fixture.trajectory, fixture.binding, episodeOrdinal = 0).shouldBeInstanceOf<TrajectoryAdmissionResult.Admitted>().episode, pass that ReplayAdmittedEpisodeV1 to TrajectoryV1Publisher.appendFinalizedEpisode(admitted), and call finalizeDataset(). Run materialization twice into different temporary directories and assert identical samples.ndjson and manifest.json bytes; no manifest BOM/trailing LF; every sample has exactly one LF; digest/byte/count fields match; and derivedArtifactId recomputes independently. Do not introduce a new Writer API.

- [ ] **Step 2: Run just test-class C1LearnerArtifactMaterializerTest and confirm RED.**

- [ ] **Step 3: Implement the A7-only source boundary.**

The first source operation must be TrajectoryV1Reader.openPublishedDataset(publishedDatasetDirectory). Consume only dataset.streamEpisodes(). Never glob/list/walk source shards. For each episode, assign one partition from semanticEpisodeId, increment episode counts, project decisions in source order, write samples, and increment sample counts. Bind dataset.manifest.datasetId and manifestContentDigest into source references and identity.

- [ ] **Step 4: Implement exact physical bytes.**

Write samples with a UTF-8 byte stream as A3SemanticJson.canonicalJson(sample) bytes followed by byte 0x0A; do not use platform newline APIs. Write manifest.json as canonical UTF-8 bytes with no BOM and no final newline. Publish both files only after complete generation; a failed run must not leave an accepted manifest.

- [ ] **Step 5: Implement exact identity/count formulas.**

The artifact identity payload contains exactly schema, derived-view schema, source dataset ID, source manifest digest, TrajectoryV1 schema identity, model-facing identity, split identity, materializer implementation/source commit, config digest, sample content digest, episode partition counts, and sample partition counts. Exclude manifest digest, artifact ID, paths, file references, host, PID, time, and worker identity. Compute manifestContentDigest from the canonical manifest with only its own digest omitted.

- [ ] **Step 6: Add A7/determinism RED tests.**

Cover corrupt manifest/shard rejection before accepted output, extra unreferenced files ignored, same source/config/commit producing identical bytes, output path independence, source-order preservation, same semantic episode across collection jobs sharing a split, failed/quarantined exclusion, interrupted prefix admission without synthetic value, and zero-sample episode counts.

- [ ] **Step 7: Run just test-class C1LearnerArtifactMaterializerTest and just test-class C1DerivedManifestV1Test; confirm GREEN.**

- [ ] **Step 8: Commit:**

~~~powershell
git add gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/learner/C1LearnerArtifactMaterializer.kt gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1LearnerArtifactMaterializerTest.kt gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1DerivedManifestV1Test.kt gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/learner/C1LearnerFixtureSupport.kt
git commit -m "c1: add deterministic A7 learner artifact materializer"
~~~

---

## Task 4: Create the installable Python contract package and strict derived reader

**Files:** Create the ml package/file map from the File map section, including pyproject.toml, .python-version, package modules, fixtures, and tests.

- [ ] **Step 1: Add exact package metadata.**

~~~toml
[build-system]
requires = ["setuptools==84.0.0"]
build-backend = "setuptools.build_meta"

[project]
name = "argentum-ml"
version = "0.1.0"
description = "Argentum C1 derived learner contracts"
requires-python = ">=3.13,<3.14"
dependencies = []

[tool.setuptools.packages.find]
where = ["src"]
~~~

Set .python-version to 3.13.15. setuptools is build-only; runtime dependencies remain empty.

- [ ] **Step 2: Write RED canonical/reader tests.**

Test A3 parity with fixture {"z":[3,{"b":"β","a":true}],"a":null} expected as {"a":null,"z":[3,{"a":true,"b":"β"}]}; reject NaN/infinities/non-string keys/unsupported values; import after installation; and reject missing/extra manifest fields before yielding samples.

- [ ] **Step 3: Run the clean-install RED suite.**

~~~powershell
cd ml
py -3.13 -m pip install --no-deps .
py -3.13 -m unittest discover -s tests -v
~~~

Expected: missing-module/attribute failures, not a packaging failure.

- [ ] **Step 4: Implement canonical JSON and identity constants.**

canonical_json must use json.dumps with ensure_ascii=False, allow_nan=False, separators=(",", ":"), and sort_keys=True; preserve list order; reject non-string keys and non-finite floats; and expose canonical_bytes/sha256_hex. Define the physical derived-view identity argentum-ml-derived-learner-view@v1, the artifact-identity preimage schema, and the binding identities for model-facing, split, checkpoint, inference, numeric profile, Selection V1/V2, and PolicyTieRng V1. Do not define a separate persisted sample-schema or manifest-schema identity.

- [ ] **Step 5: Implement strict DerivedArtifactReader.open(root: Path).**

Require a real non-symlink artifact directory; manifest.json itself must be a non-symlink regular file; manifest bytes must be exact canonical UTF-8 with no BOM/no LF; exact v1 keys/version and derived-view identity; fixed samples.ndjson reference; safe regular sample file; digest/byte/count matches; recomputed manifestContentDigest and derivedArtifactId; LF-terminated sample lines; and strict sample schema. Parse JSON with an object-pairs hook that rejects duplicate keys. For every raw sample line, reject BOM, CRLF/CR, blank lines, and missing LF, parse strictly, and require rawLine == canonical_bytes(parsedSample) + b"\n" before any sample is yielded. Validate that any binding discriminator map carries the exact producer contract, canonical keys, no raw-ID/ordinal/order fields, and no duplicate key among candidates that could be tied. For every parsed sample, before yield or count mutation, recompute expectedPartition = assign_partition(sample.sourceReference.semanticEpisodeId) and reject any partition mismatch. Then validate target membership: an action target must canonically match exactly one original complete-domain candidate and be executable, and canonical(target.chosenSemanticAction) must equal canonical(binding.selectedExactSourceBinding.exactAction); for a response, canonical(target.chosenSemanticResponse) must equal canonical(binding.selectedExactSourceBinding.exactResponse). Only after that full equality check perform structural containment: every referenced member/slot must occur in the original typed domain. This is structural containment, not a second Rules engine. Only after these checks may sample/partition counters be incremented. Stream lines without filesystem enumeration and fail closed on every violation.

- [ ] **Step 6: Implement Python split parity.**

assign_partition(semantic_episode_id) must use the same preimage and unsigned first-eight-byte extraction. Assert:

~~~text
0x00 repeated 64 -> bucket 75 -> TRAIN
09 repeated 32 -> bucket 88 -> VALIDATION
1c repeated 32 -> bucket 91 -> TEST
~~~

Also assert decision count, trajectory ID, collection job ID, row order, and path do not affect assignment.

- [ ] **Step 7: Run py -3.13 -m unittest tests.test_canonical_json tests.test_derived_reader tests.test_split -v; confirm GREEN.**

- [ ] **Step 8: Commit:**

~~~powershell
git add ml
git commit -m "c1: add strict derived learner Python package"
~~~


## Task 5: Add variable-size domain/batch transport

**Files:**

- Create: ml/src/argentum_ml/data/variable_batch.py
- Create: ml/tests/test_variable_batch.py

- [ ] **Step 1: Write RED tests for variable boundaries and binding transport.**

Use two fixture items with candidate counts 2 and 4, one retained unaffordable candidate, one structured-domain item, and distinct source-binding ordinals. Test exact item/candidate offsets, presence versus executable-support masks, structured-domain retention without flattening, padding semantics, target-binding transport through arbitrary permutations, and absence of any truncate/top-k/max-candidates operation.

- [ ] **Step 2: Run py -3.13 -m unittest tests.test_variable_batch -v and confirm RED.** Expected: missing VariableDomainBatch import/attribute failure.

- [ ] **Step 3: Implement immutable batch types.**

Use frozen dataclasses with fields CandidateFeature(feature_view, source_binding_ordinal, present, executable_support), VariableDomainItem(model_input, candidates, structured_domain, target_binding_ordinal), and VariableDomainBatch(items, item_offsets, candidate_offsets, padding_mask). Validate offsets, masks, unique source-binding ordinals, target membership, structured-domain presence, and exact candidate preservation. Provide permutation transport that moves candidate records and target ordinals together. Do not provide a truncating constructor or method.

- [ ] **Step 4: Run GREEN and commit.**

~~~powershell
cd ml
py -3.13 -m unittest tests.test_variable_batch -v
git add ml/src/argentum_ml/data/variable_batch.py ml/tests/test_variable_batch.py
git commit -m "c1: add variable legal-domain batch foundation"
~~~

---

## Task 6: Implement PolicyTieRng V1 with independent KATs

**Files:**

- Create: ml/src/argentum_ml/selection/__init__.py
- Create: ml/src/argentum_ml/selection/policy_tie_rng.py
- Create: ml/tests/test_policy_tie_rng.py

- [ ] **Step 1: Write all PolicyTieRng RED/KAT tests.**

Use these independent C0-04B constants:

~~~text
seed=4259905, seat=0
policySeedBitsHex=0000000000410041
streamKey=e524b34e031fbdc4f616f6725de4335c95207edadbcbfbd09bdb91952152e8d1
cursor=0 rawWord=43c8392191b2467a
cursor=1 rawWord=ca6d38cc6027f7aa
uniformBelow(10)=2 cursorAfter=1

seed=4259905, seat=1
streamKey=12788fa167a4187b53e81f24c9d6b52a3e171e57195ddb8ece312ae1408284f2
cursor=0 rawWord=17c97b72e4a765b3

seed=0, seat=3
streamKey=cb6aba6ce62b94db20e5a055e8c0a33dfec0c02e70689f764c69d6a9dee8c8e3
cursor=0 rawWord=e0e9c4649d28823b rejected
cursor=1 rawWord=59279a6a1d1881bf accepted
uniformBelow(9223372036854775809)=6424273174012658111 cursorAfter=2
~~~

Also test signed -1 renders ffffffffffffffff, Long.MIN_VALUE renders 8000000000000000, Long.MAX_VALUE renders 7fffffffffffffff, Python values below -2^63 or above 2^63-1 fail before bit interpretation, bool is rejected even though bool subclasses int, wrong policy identity rejects before seed use, engine/semantic episode/hidden-world inputs are not accepted by the stream-key API, cursor exhaustion at 2^64-1 fails before a draw, rejected words advance the cursor, fork copies by value, and restore reproduces exact state.

- [ ] **Step 2: Run py -3.13 -m unittest tests.test_policy_tie_rng -v and confirm RED.**

- [ ] **Step 3: Implement exact state and stream-key behavior.**

Use frozen PolicyTieRngStateV1(stream_key: bytes, cursor: int), UINT64_MAX = (1 << 64) - 1, and exact namespaces:

~~~text
STREAM_SCHEMA=argentum-ml-policy-tie-stream@v1
RAW_WORD_NAMESPACE=ASCII("argentum-ml-policy-tie-rng-word@v1") || 0x00
~~~

Require policy_rng_identity == argentum-ml-policy-tie-rng@v1 before consuming seed bits; reject the legacy explicit-seed/kotlin-policy-state-v1 identity and keep CURRENT_A9_POLICY_SEED_AS_POLICY_TIE_RNG_SEED=NO. The state scope is one semantic episode x one policy instance. The canonical stream payload has only schema, policySeedBitsHex, and seatIndex. Interpret signed policy_seed as policy_seed & UINT64_MAX, render 16 lowercase hex digits, hash canonical UTF-8 payload, and use the raw 32-byte digest as streamKey. Raw-word preimage is namespace bytes plus streamKey plus U64_BE(cursor). Increment exactly once after every raw word; never prefetch or wrap. Implement rejection-sampling limit = ((1 << 64) // n) * n and consume rejected words.

- [ ] **Step 4: Run GREEN and commit.**

~~~powershell
cd ml
py -3.13 -m unittest tests.test_policy_tie_rng -v
git add ml/src/argentum_ml/selection ml/tests/test_policy_tie_rng.py
git commit -m "c1: implement PolicyTieRng V1"
~~~

---

## Task 7: Implement Selection V2 and semantic source binding

**Files:**

- Create: ml/src/argentum_ml/selection/selection_v2.py
- Create: ml/tests/test_selection_v2.py

- [ ] **Step 1: Write Selection V2 RED tests.**

Test unique argmax with zero RNG draws; deterministic semantic discriminator with zero draws; missing/non-unique discriminator entering PolicyTieRng; source-binding ordinal absent from model features and deterministic preferences; ordinal used only as uniform sample address; NaN/+Inf/-Inf rejection; empty candidates; score-count mismatch; extra score binding; unselectable target; candidate permutation invariance; batch independence; all candidate-presence entries receiving finite scores; executable-support masking preventing unaffordable selection; and no structured auto-completion.

- [ ] **Step 2: Run py -3.13 -m unittest tests.test_selection_v2 -v and confirm RED.**

- [ ] **Step 3: Implement the strict Selection V2 API.**

Define immutable ExactSemanticSourceBinding(exact_action, exact_response, source_binding_ordinal_audit) with an init invariant that exactly one of exact_action and exact_response is non-null. Define SelectionCandidate(source_binding_ordinal, exact_source_binding, score, candidate_presence, candidate_executable_support, deterministic_semantic_tie_discriminator) and SelectionResult(exact_source_binding, audit_source_binding_ordinal, rng_state, rng_draw_count, cursor_before, cursor_after). Validate unique ordinals, exact source bindings, finite scores for every candidate with candidate_presence=true, and complete binding membership before computing the maximum. A unique maximum among candidate_presence && candidate_executable_support returns without a draw. A valid unique C0 discriminator chooses the lowest canonical discriminator without a draw. Otherwise order unresolved members by source-binding ordinal only as the unbiased address map, call uniform_below, and return the exact source binding plus the ordinal only as audit metadata. No method accepts engine RNG, Python random, a row index, a caller-provided tie key, or a first-candidate default.

- [ ] **Step 4: Run GREEN and commit.**

~~~powershell
cd ml
py -3.13 -m unittest tests.test_selection_v2 -v
git add ml/src/argentum_ml/selection/selection_v2.py ml/tests/test_selection_v2.py
git commit -m "c1: implement Selection V2 semantic binding"
~~~

---

## Task 8: Implement strict checkpoint manifests and Numeric Execution Profile binding

**Files:**

- Create: ml/src/argentum_ml/checkpoint/__init__.py
- Create: ml/src/argentum_ml/checkpoint/manifest.py
- Create: ml/tests/test_checkpoint_manifest.py
- Create: ml/tests/fixtures/checkpoint_manifest_v1.json

- [ ] **Step 1: Write the exact checkpoint fixture and RED tests.**

Use fixture weight bytes c1-checkpoint-fixture-weight-v1 followed by one LF byte. Its independent SHA-256 is:

~~~text
33f5b2ad62a009da8f27adec3e84b95c4be4340e6d089cd29c8464610642164c
~~~

Use this independent checkpoint identity SHA-256:

~~~text
07b88ac3f37a9270bbf1db4888da4fb047429a864f7ab0c778335a69878a4290
~~~

The fixture binds manifestContractIdentity argentum-ml-checkpoint-manifest@v1, policyArtifactKind FEED_FORWARD_POLICY, model implementation c1-fixture-score-provider@v1 at source commit ce9f779bd3bd8375b6b83b9c5668b1b94ced924a, architecture c1-fixture-feed-forward@v1, modelConfigDigest of 64 ones, model-facing and candidate-scoring identity argentum-ml-model-facing-decision-sample@v1, split identity argentum-ml-dataset-split@v1, source dataset identity of 64 twos, NONE_FOR_FEED_FORWARD, null vocabulary/lineage/Teacher fields, fixture-bytes@v1 plus fixture.weights, inference identity argentum-ml-inference@v1, Selection V2, required numeric profile C1_REFERENCE_NUMERIC_PROFILE, and PolicyTieRng V1.

Test parse/validate/canonicalize/recompute identity, weight digest mismatch, unknown version, unknown field, unknown artifact kind, missing field, invalid selection/RNG pair, wrong numeric profile identity, filename/path independence, and changed weight bytes changing both weight digest and checkpoint identity.

- [ ] **Step 2: Run py -3.13 -m unittest tests.test_checkpoint_manifest -v and confirm RED.**

- [ ] **Step 3: Implement the complete strict manifest model.**

Require every C0-04 field: manifest contract/version, policyArtifactKind, modelImplementationIdentity, modelArchitectureIdentity, modelConfigDigest, modelFacingContractIdentity, candidateScoringContractIdentity, splitContractIdentity, sourceDatasetIdentity, recurrentSequenceContractIdentity, vocabularyIdentity, weightArtifactIdentity, weightContentDigest, inferenceContractIdentity, selectionContractIdentity, requiredNumericProfileClass, policyRngContractIdentity, trainingRecipeIdentity, trainingRunIdentity, parentCheckpointIdentity, teacherBootstrapProvenance, and checkpointId. Allow only FEED_FORWARD_POLICY and RECURRENT_POLICY. Require NONE_FOR_FEED_FORWARD for feed-forward artifacts and argentum-ml-recurrent-sequence@v1 for recurrent artifacts. Require exactly Selection V1 plus NONE_FOR_DETERMINISTIC_MODE or Selection V2 plus PolicyTieRng V1. Reject unknown fields before decoding.

- [ ] **Step 4: Implement numeric profile validation without backend certification.**

Define NumericExecutionProfileIdentity(contract_identity, required_profile_class). Require contract_identity == argentum-ml-numeric-execution-profile@v1 and a non-empty profile class. Bind requiredNumericProfileClass into checkpoint identity. Do not import PyTorch, CUDA, NumPy, or another backend.

- [ ] **Step 5: Run GREEN and commit.**

~~~powershell
cd ml
py -3.13 -m unittest tests.test_checkpoint_manifest -v
git add ml/src/argentum_ml/checkpoint ml/tests/test_checkpoint_manifest.py ml/tests/fixtures/checkpoint_manifest_v1.json
git commit -m "c1: add strict checkpoint manifest identity"
~~~



---

## Task 9: Add the model-independent inference seam

**Files:**

- Create: ml/src/argentum_ml/inference/__init__.py
- Create: ml/src/argentum_ml/inference/provider.py
- Create: ml/src/argentum_ml/inference/runtime.py
- Create: ml/tests/test_inference_runtime.py

- [ ] **Step 1: Write RED tests for the provider boundary.**

Create a fake provider that records its arguments and returns finite scores. Test that it receives only an immutable model-input object and feature views for every real candidate, including unaffordable placeholders; its argument graph contains none of target, provenance, sourceReference, binding, gameState, rawAction, actionId, decisionId, abilityId, envId, policySeed, or outcome fields. Test that missing/extra score bindings fail closed and a valid provider reaches an ExactSemanticSourceBinding, not an ordinal-only result. Add a structured-domain fixture with no approved scoreable structured alternatives and assert FAIL_CLOSED, RNG_DRAWS=0, and TARGET_NOT_CONSULTED.

- [ ] **Step 2: Run py -3.13 -m unittest tests.test_inference_runtime -v and confirm RED.**

- [ ] **Step 3: Implement the provider protocol and runtime.**

Define ScoreProvider.score(model_input, candidates) -> Sequence[float] and an immutable InferenceContext containing NumericExecutionProfileIdentity, selection contract identity, and policy RNG contract identity. InferenceRuntime.select validates the numeric profile and Selection/RNG pair, sends sample.input plus one feature view for every candidatePresence=true candidate to the provider, validates SCORE_COUNT == count(candidatePresence=true), applies ARGMAX_ELIGIBLE = candidatePresence && candidateExecutableSupport, constructs SelectionCandidates only from the strict reader's source-produced discriminator map, invokes Selection V2, and returns an ExactSemanticSourceBinding containing the full action/response binding plus optional ordinal audit metadata. For a structured domain without approved scoreable prefix/complete-response alternatives, C1_00 is intentionally non-total: fail closed before provider/target access and consume zero RNG words. Set C1_00_STRUCTURED_INFERENCE_TOTALITY=NO. The public inference API accepts no discriminator argument. It never emits a live actionId, calls Rules legality, fills a structured remainder, accepts a caller-supplied tie key, or uses the recorded target to repair a score.

- [ ] **Step 4: Run GREEN and commit.**

~~~powershell
cd ml
py -3.13 -m unittest tests.test_inference_runtime -v
git add ml/src/argentum_ml/inference ml/tests/test_inference_runtime.py
git commit -m "c1: add model-independent inference seam"
~~~

---

## Task 10: Add local commands, CI, and focused documentation

**Files:**

- Modify: justfile
- Modify: .github/workflows/ci.yml
- Modify: .gitignore
- Create: ml/README.md
- Create: docs/ml/c1-00-local-learner-foundation.md

- [ ] **Step 1: Add local commands without source discovery.**

Add these recipes if their names are unused:

~~~just
[group: 'build']
ml-test:
    cd ml && python -m pip install --no-deps . && python -m unittest discover -s tests -v
[group: 'build']
ml-check:
    cd ml && python -m compileall -q src tests
~~~

Document the Windows pinned-runtime equivalent as py -3.13 -m unittest discover -s tests -v; CI uses Python 3.13.15. Do not add ml-materialize until a reviewed Kotlin CLI exists; the documented materialization entry point is C1LearnerArtifactMaterializer.materialize(publishedDatasetDirectory, outputDirectory, sourceCommit, config) and its focused Kotlin test.

- [ ] **Step 2: Add only absent Python ignore rules.**

Add these patterns only if not already present:

~~~text
__pycache__/
*.py[cod]
.venv/
.pytest_cache/
.mypy_cache/
.ruff_cache/
dist/
*.egg-info/
~~~

Do not ignore ml/tests/fixtures, ml/pyproject.toml, .python-version, or derived-artifact schema fixtures.

- [ ] **Step 3: Add the focused ML CI job and preserve the backend check name.**

Add this job to .github/workflows/ci.yml:

~~~yaml
  ml-contracts:
    name: ml-contracts
    runs-on: ubuntu-latest
    steps:
      - name: Checkout repository
        uses: actions/checkout@v6
      - name: Set up Python 3.13.15
        uses: actions/setup-python@v6
        with:
          python-version: 3.13.15
      - name: Install local ML package
        run: python -m pip install --no-deps .
        working-directory: ml
      - name: Run ML contract tests
        run: python -m unittest discover -s tests -v
        working-directory: ml
      - name: Compile-check ML package
        run: python -m compileall -q src tests
        working-directory: ml
~~~

Change backend needs from [test] to [test, ml-contracts], retain if: always(), and fail when either needs.test.result or needs.ml-contracts.result is not success. Do not rename backend.

- [ ] **Step 4: Write focused documentation.**

ml/README.md must show installation and offline test commands and state that Python consumes only derived artifacts, never decides TrajectoryV1 trust, and has no training API. docs/ml/c1-00-local-learner-foundation.md must document A7 to Kotlin to derived artifact to Python flow, exact bytes, derivedArtifactId, dual counts, complete/structured domains, Selection V2, PolicyTieRng provenance, checkpoint/numeric profile, local commands, CI, C1_00_STRUCTURED_INFERENCE_TOTALITY=NO, and all remaining NO authorization values. Link to C0 documents instead of duplicating them.

- [ ] **Step 5: Run the focused integration checks.**

~~~powershell
just ml-check
just ml-test
git diff --check
~~~

Expected: package installation without runtime dependencies, all offline Python tests pass, and no large source materialization job runs.

- [ ] **Step 6: Commit.**

~~~powershell
git add justfile .github/workflows/ci.yml .gitignore ml/README.md docs/ml/c1-00-local-learner-foundation.md
git commit -m "c1: add local learner checks and CI gate"
~~~

---

## Task 11: Full focused verification and independent self-review

**Files:** None beyond Tasks 1–10.

- [ ] **Step 1: Run the complete ML Python suite.**

~~~powershell
cd ml
py -3.13 -m pip install --no-deps .
py -3.13 -m unittest discover -s tests -v
py -3.13 -m compileall -q src tests
~~~

Record ML_PYTHON_TESTS=PASS only from this fresh output.

- [ ] **Step 2: Run focused Kotlin materializer tests through repository wrappers.**

~~~powershell
just test-class C1DatasetSplitV1Test
just test-class C1ModelFacingProjectionV1Test
just test-class C1DerivedManifestV1Test
just test-class C1LearnerArtifactMaterializerTest
~~~

Record C1_MATERIALIZER_TESTS=PASS only if all four commands exit zero. Run just test-gym-trainer if changed Kotlin production code requires module-wide verification. Report wrapper failures and native fallback evidence separately.

- [ ] **Step 3: Run relevant existing gym-trainer tests.**

~~~powershell
just test-gym-trainer
~~~

Record GYM_TRAINER_RELEVANT_TESTS=PASS only from fresh zero-exit output. Do not claim full Rules/Gym acceptance unless those suites were actually run.

- [ ] **Step 4: Audit complete change scope.**

~~~powershell
git status --short --branch
git diff --check ce9f779bd3bd8375b6b83b9c5668b1b94ced924a..HEAD
git diff --name-only ce9f779bd3bd8375b6b83b9c5668b1b94ced924a..HEAD
git diff --name-only --diff-filter=ACMRTUXB ce9f779bd3bd8375b6b83b9c5668b1b94ced924a..HEAD -- card-implementation-progress.html rules-engine gym game-server mtg-sdk mtg-sets
~~~

The last command must produce no output. Confirm StackResolver.kt is absent from the branch diff.

- [ ] **Step 5: Perform the required self-review.**

Inspect the final diff for Python raw TrajectoryV1 access; Python source-trust decisions; filesystem shard discovery; source mutation; candidate truncation; structured flattening/completion; raw IDs/seeds/outcomes/provenance in input; target leakage; row splitting; failed admission; interrupted synthetic value; first-choice/AutoPay/global/engine RNG; source ordinal as feature/preference; legacy A9 seed reuse; modulo bias/cursor wrap/prefetch; non-finite sanitization; row/batch tie dependence; path/filename checkpoint identity; permissive unknown fields/kinds; missing numeric profile; training code; and forbidden ML dependencies.\n\nRecord SELF_REVIEW_P1=NONE and SELF_REVIEW_P2=NONE only after this inspection finds no actionable P1/P2 issue.\n
- [ ] **Step 6: Request independent code review before any PR claim.** Use base ce9f779bd3bd8375b6b83b9c5668b1b94ced924a and final HEAD. The reviewer must inspect A7 trust, input/target separation, artifact identity/framing, Selection/RNG, checkpoint strictness, numeric profile, and no-training scope. Resolve every P1/P2 before delivery.

---

## Task 12: Draft PR delivery boundary after implementation review

This task belongs to the eventual implementation workflow but is not authorized by this plan-review turn until implementation and independent review are complete.

- [ ] **Step 1: Verify the PR destination.**

~~~powershell
git remote get-url origin
~~~

Expected: https://github.com/chrismaghuhn/argentum-engine.git.

- [ ] **Step 2: Push only the reviewed branch.**

~~~powershell
git push -u origin chris/c1-00-local-learner-foundation-20260912
~~~

- [ ] **Step 3: Open a Draft PR against fork main.**

~~~powershell
gh pr create --repo chrismaghuhn/argentum-engine --base main --head chris/c1-00-local-learner-foundation-20260912 --draft --title "c1: implement local learner contract foundation"
~~~

The body must state first C1 implementation slice; accepted C0 contracts; A7/TrajectoryV1 authority; derived artifact non-authority; no training/model/Teacher/RL/self-play/search/large corpus; no PyTorch requirement; and references #124, #137, #119, and #183. Do not mark Ready or merge.

---

## Verification-to-gate mapping

~~~text
C1_00_REPOSITORY_AUDIT                         Task 0
C1_00_SOURCE_AUTHORITY_PRESERVED               Tasks 2-3
C1_00_A7_READER_SEAM                           Task 3
C1_00_DERIVED_VIEW_MANIFEST                    Tasks 1,3,4
C1_00_SPLIT_RUNTIME                             Tasks 1,4
C1_00_MODEL_FACING_MATERIALIZER                Tasks 2-3
C1_00_INPUT_TARGET_SEPARATION                  Task 2
C1_00_PRIVACY_FILTER                            Task 2
C1_00_COMPLETE_DOMAIN_PRESERVATION             Tasks 2,5
C1_00_STRUCTURED_DOMAIN_PRESERVATION            Tasks 2,5
C1_00_VARIABLE_DOMAIN_BATCH_FOUNDATION          Task 5
C1_00_SELECTION_V2_RUNTIME                      Task 7
C1_00_POLICY_TIE_RNG_RUNTIME                    Task 6
C1_00_POLICY_RNG_KATS                           Task 6
C1_00_SELECTION_PERMUTATION_INVARIANCE          Task 7
C1_00_CHECKPOINT_MANIFEST_RUNTIME               Task 8
C1_00_CHECKPOINT_IDENTITY_KATS                  Task 8
C1_00_LOCAL_INFERENCE_SEAM                      Task 9
C1_00_CI_INTEGRATION                            Task 10
~~~

Final status after implementation/review must distinguish readiness from acceptance:

~~~text
C1_00_IMPLEMENTATION_PASS=YES only after fresh focused verification
C1_00_READY_FOR_ACCEPTANCE=YES only after independent code review
C1_00_FINAL_ACCEPTANCE_PASS=NO until exact-SHA/hosted acceptance
LOCAL_MINI_MODEL_PIPELINE_PASS=NO
FIRST_CHECKPOINT_PASS=NO
C1_00_STRUCTURED_INFERENCE_TOTALITY=NO
FIRST_C1_TEACHER_SELECTION=DEFERRED_TO_C1_CHARACTERIZATION
TEACHER_BOOTSTRAP_ADMITTED=NO
TRAINING_AUTHORIZED=NO
SMALL_LEARNER_SMOKE_AUTHORIZED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
~~~

## Plan self-review

The plan maps every approved requirement: A7 trust, record ownership, strict per-sample split/binding validation, full chosen-action equality, and canonical sample-byte validation (Tasks 2–4); exact artifact framing and identity (Tasks 1 and 3–4); episode-level split and dual counts (Tasks 1 and 3–4); target/privacy separation and all twelve structured-domain representations (Task 2); source-owned tie-discriminator production and validation (Task 2); variable batches (Task 5); PolicyTieRng/provenance and signed-Long range (Task 6); Selection V2 discriminator/RNG boundary, ExactSemanticSourceBinding XOR, and exact binding output (Task 7); complete checkpoint fields and numeric profile (Task 8); all-present-candidate scoring, structured fail-closed non-totality, and inference (Task 9); offline packaging/CI (Tasks 4 and 10); and verification/self-review (Task 11). All referenced types/functions are introduced before use. No task authorizes a raw-source Python reader, fixed-width action vocabulary, first-choice fallback, training loop, or C0 semantic change.



---
