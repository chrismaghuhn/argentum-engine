# B2 A6 Transactional Trusted Trajectory Writer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admit only A5-valid trajectories whose complete semantic replay evidence matches, then publish deterministic, privacy-safe, bounded immutable NDJSON shards and an existing `DatasetManifestV1` atomically.

**Architecture:** Keep replay reconstruction in the neutral `:gym` binding supplied by the caller. Add a pure A6 admission gate in `:gym-trainer`, a quarantine metadata writer, and a publisher that owns producer-order enforcement, current-shard buffering, byte/digest verification, manifest construction, and staging-directory publication. Emit the current V1 design's canonical episode-start/decision/episode-end NDJSON frames, use `A3SemanticJson`, preserve arrays, and never enumerate the output directory for semantic ordering.

**Tech Stack:** Kotlin/JVM 21, kotlinx.serialization, `java.nio.file.Files`/`FileChannel`, Kotest, existing `TrajectoryV1`, `ReplayTrajectoryBindingV1`, `DatasetManifestV1`, and `A3SemanticJson` contracts.

---

### Task 1: Add the RED A6 admission and storage tests

**Files:**
- Create: `gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1WriterTest.kt`

- [ ] **Step 1: Add a fixture that produces one A5-valid trajectory and matching exact binding evidence.** Reuse the existing `TrajectoryV1ContractTest` construction pattern; build a `VerifiedReplayVerification` with initial/intermediate/tail frames, exact closure, and a `ReplayChosenInputBindingV1` whose choice matches the trajectory record.
- [ ] **Step 2: Add failing admission assertions.** Cover successful admission, A5 invalidity, failed closure, identity/version mismatch, non-EXACT and incomplete replay proof, count/coordinate/perspective/observation/domain/digest/chosen-input mismatches, and exact closure mismatch. Assert typed A6 quarantine reasons and that no admitted value is returned.
- [ ] **Step 3: Add failing writer/publisher assertions.** Cover mandatory positive bounds, explicit ordinal order, episode-count and byte rollovers, exact-fit and oversized lines, canonical map-key output, preserved array order, exact shard byte digest/count, immutable no-overwrite behavior, manifest-last behavior, deterministic manifest independent of unrelated files, duplicate collection-job rejection, and failed-state rejection.
- [ ] **Step 4: Add failing privacy/quarantine assertions.** Ensure an invalid trajectory is absent from trusted shards and manifest, and persisted quarantine metadata contains only safe typed metadata—not observation, domain, chosen payload, raw state, exception text, or operational IDs.
- [ ] **Step 5: Run the new class to verify RED.**

Run: `& .\\gradlew.bat :gym-trainer:test --tests "com.wingedsheep.gym.trainer.trajectory.TrajectoryV1WriterTest" --console=plain`

Expected: compilation/test failure because the A6 admission, quarantine, and publisher APIs do not yet exist.

### Task 2: Implement pure replay-backed A6 admission

**Files:**
- Create: `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Writer.kt`

- [ ] **Step 1: Define the closed typed admission result and quarantine reason vocabulary.** Keep `ReplayAdmittedEpisodeV1` privately constructible with an internal gate factory; expose only immutable trajectory/canonical-line evidence.
- [ ] **Step 2: Implement `TrajectoryV1Admission.admit(trajectory, binding, episodeOrdinal)`.** Reuse `TrajectoryV1Validator.validate`, compare the linked replay identity/version/counts, require exact complete-range A4 evidence, compare every decision to the same-index frame and chosen input, require exact closure equality, then canonicalize the trajectory once. Return a typed quarantine result on every mismatch; never call `GameState`, `CompactReplay`, or `ReplayReconstructor`.
- [ ] **Step 3: Implement canonical storage encoding.** Encode the typed trajectory and canonical episode-start/decision/episode-end frames with the strict existing serializer, recursively sort object keys with `A3SemanticJson`, preserve array order, append one UTF-8 LF terminator per frame, and reject storage-boundary privacy keys structurally before admission.
- [ ] **Step 4: Run the admission tests to verify GREEN.**

Run: `& .\\gradlew.bat :gym-trainer:test --tests "com.wingedsheep.gym.trainer.trajectory.TrajectoryV1WriterTest" --console=plain`

Expected: admission and canonicalization assertions pass; publisher assertions remain the only failing tests until Task 3.

### Task 3: Implement privacy-safe quarantine and bounded immutable publication

**Files:**
- Create: `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Quarantine.kt`
- Create: `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Manifest.kt`
- Create: `gym-trainer/src/main/kotlin/com/wingedsheep/gym/trainer/trajectory/TrajectoryV1Publisher.kt`

- [ ] **Step 1: Implement versioned quarantine metadata and atomic CREATE_NEW persistence.** Store only safe IDs when syntactically valid, ordinal/index, typed reason, and typed A5 detail; never serialize the rejected trajectory or exception text.
- [ ] **Step 2: Implement the publisher state machine.** Require finite positive metadata bounds, create a non-membership staging directory, reject ordinal gaps/duplicates and duplicate collection jobs, and poison the writer after storage-integrity/publication failure.
- [ ] **Step 3: Implement shard rotation and verification.** Buffer only the current bounded shard, rotate before overflow, reject an oversized single episode, write exact LF-canonical bytes through an atomic move with no replacement, recompute SHA-256 over the emitted bytes, and construct `DatasetShardMetadataV1` from the verified result.
- [ ] **Step 4: Implement manifest construction and last publication.** Build counts/indexes from admitted episodes only, set `failedCount` to zero rather than counting quarantine attempts, recompute the accepted V1 `datasetId` and `manifestContentDigest`, write `manifest.json` last in staging, and atomically move the complete dataset directory without a non-atomic fallback.
- [ ] **Step 5: Wire `TrajectoryV1Writer.appendEpisode`.** Enforce every producer ordinal including quarantined attempts, persist quarantine metadata for admission/size failures, delegate admitted values to the publisher, and expose `finalizeDataset()` without adding any reader or generation harness.
- [ ] **Step 6: Run the complete new test class to verify GREEN.**

Run: `& .\\gradlew.bat :gym-trainer:test --tests "com.wingedsheep.gym.trainer.trajectory.TrajectoryV1WriterTest" --console=plain`

Expected: all A6 tests pass.

### Task 4: Regression verification and scope review

**Files:**
- Modify only the A6 files above if a regression fix is needed.

- [ ] **Step 1: Run the required existing trajectory and replay binding regressions.**

Run: `& .\\gradlew.bat :gym-trainer:test --tests "com.wingedsheep.gym.trainer.trajectory.TrajectoryV1ContractTest" --tests "com.wingedsheep.gym.trainer.trajectory.SemanticDecisionIdentityTest" --tests "com.wingedsheep.gym.trainer.trajectory.SemanticReplayPrefixAccumulatorTest" --tests "com.wingedsheep.gym.trainer.trajectory.ReplayChosenInputBindingV1Test" --console=plain`

Expected: every selected existing test passes; no A5/A4 wire contract changes appear in the diff.

- [ ] **Step 2: Run the required module gates.** Run `just test-class TrajectoryV1WriterTest`, `just test-class TrajectoryV1ContractTest`, `just test-class ReplayChosenInputBindingV1Test`, then the native `gradlew.bat :gym-trainer:test` fallback if the wrapper is blocked.
- [ ] **Step 3: Inspect the final diff and verify scope.** Confirm no `:gym`/`:game-server` production dependency changes, no reader, generation, ML, deck, rules, card, or accepted-contract changes; run `git diff --check`.
- [ ] **Step 4: Commit only after fresh verification.** Use `[B2 A6] Add transactional trusted trajectory writer` and retain `CODE_REVIEW_PASS=NO`/`FINAL_ACCEPTANCE_PASS=NO` until independent review.
