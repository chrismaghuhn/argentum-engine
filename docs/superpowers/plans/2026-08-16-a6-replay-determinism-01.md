# A6 Replay Determinism Implementation Plan
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the reviewed A6 replay determinism contract: CompactReplay v3 with a complete transition-semantic GameState fingerprint, typed decision-nonce aliasing, a mandatory verified v3 tail checkpoint, explicit v1/v2 legacy dispatch, controlled future-version fallback to archived presentation, and Gym snapshot restoration that preserves episode step count.

**Architecture:** Keep the deterministic rules engine and existing replay input log authoritative. Version selection belongs at the replay boundary, not inside the engine action processor. v1 and v2 retain the current short fingerprint semantics; v3 uses a canonical transition-semantic GameState representation with stable ordering and narrowly scoped routing-ID aliases. Replay reconstruction remains a fold over actions and out-of-band yields, while service/API code decides whether an exact reconstruction, an unverified reconstruction, or the durable presentation may be exposed. A4 observation and digest code remains the only ML-observation boundary and is not modified.

**Tech Stack:** Kotlin, kotlinx.serialization, SHA-256, Spring Boot/Spring Data JDBC, Kotest, Gradle through `just`, Git Bash fallback on Windows, and the existing Gym `GameEnvironment`/`GameGymEnv`/`SnapshotCodec` stack.

---

## Scope and non-negotiable invariants

- The design authority is [`docs/superpowers/specs/2026-08-16-a6-replay-determinism-01-design.md`](../../specs/2026-08-16-a6-replay-determinism-01-design.md). If implementation discovery contradicts that spec, stop at the affected task and update the spec before changing behavior.
- New recordings and every v3 in-progress flush use `CompactReplay.CURRENT_VERSION = 3` and contain one checkpoint at `afterActionCount == actions.size`. A checkpoint already present at that count is replaced/retained rather than duplicated.
- v1/v2 records never receive a synthetic tail checkpoint and continue to use the old 16-hex legacy fingerprint and legacy fidelity rules.
- v3 `EXACT` means: every recorded action applied; every checkpoint encountered and matched; the checkpoint at the exact action-stream tail exists and matched; and the verified tail count equals `actions.size`.
- v3 canonicalization represents the complete transition-semantic `GameState`, including RNG, next entity ID, ordered zones/stack, pending decision semantics, continuations, yields already applied to the state, commander/combat/replacement/resume state, and all other constructor fields unless an audit proves a field is derived and transition-independent.
- `PendingDecision.id` and typed continuation decision references are routing identities only. They are normalized through one state-local alias table; arbitrary entity IDs, card IDs, ability identities, and unrelated strings are never normalized.
- `prompt` and `effectHint` remain an explicit audit point. The current source audit finds only UI/log/error/event-construction/observation uses, not action validation or continuation semantics, so they are expected to be excluded from v3. If the implementation audit finds a transition read, include the field and update the spec/tests before proceeding.
- `GameState.yieldsByPlayer` is fingerprint input. `CompactReplay.yields` is an out-of-band replay input log and is only reflected in a fingerprint after its `afterActionCount` mutation has been applied.
- A future codec version is rejected before replay reconstruction. The persistence path retains row metadata and independently decodable presentation as an unsupported marker; it never fabricates a partial `CompactReplay`.
- `TrainingObservation.kt`, `ObservationBuilder.kt`, `StateDigest.kt`, and `SchemaHash.kt` remain untouched. A6 tests call the existing `ObservationBuilder` rather than introducing a second observation projection.
- B2 trajectory export, raw replay export, privacy proof, and broad ML schema work remain out of scope.

## Task 1: Establish characterization fixtures and freeze the semantic audit

**Files:**

- Create `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/ReplayFingerprintV3Test.kt`.
- Create `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/ReplayVersionCompatibilityTest.kt`.
- Extend `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/ReplayDurabilityTest.kt` only where existing recorder/service fixtures need to state the v3 tail contract.
- Do not edit the four A4 files listed above.

**Steps:**

- [ ] Before production edits, rerun the semantic field audit from the isolated worktree:

  ```powershell
  rg -n "prompt|effectHint" rules-engine/src/main/kotlin game-server/src/main/kotlin gym/src/main/kotlin
  rg -n "pendingDecision|continuationStack|yieldsByPlayer|floatingEffects|delayedTriggers|replacement|commanderDamage|combat|rng|nextEntityId" rules-engine/src/main/kotlin/com/wingedsheep/engine/state rules-engine/src/main/kotlin/com/wingedsheep/engine/core
  ```

  Record in the test documentation/comments that `prompt` and `effectHint` currently feed `ObservationBuilder`, AI logging, error text, and event/decision presentation construction, but are not read by `ActionProcessor`, decision validators, continuation resumers, or replay rebinding. The v3 test must fail if a future implementation accidentally makes either field part of the hash without an explicit contract change.

- [ ] Build deterministic `GameState` fixtures from the existing replay/session test helpers rather than inventing a second engine state model. Cover the state constructor fields that the spec names explicitly: turn/phase/step, active/priority player, `rng`, `nextEntityId`, entity/component maps, every zone including library order, stack order, pending decision, continuation stack, floating/delayed/trigger/replacement state, yields, commander damage, combat state, and game-over/winner state.

- [ ] Add RED tests for the v3 fingerprint contract:
  - the result is exactly 64 lower-case hexadecimal characters;
  - changing RNG state, next entity ID, an entity component, a zone member, library order, stack order, continuation semantics, commander damage, combat state, replacement state, or `yieldsByPlayer` changes the fingerprint;
  - reordering a known unordered set/map does not change the fingerprint;
  - reordering a semantically ordered library or stack does change it;
  - changing only `prompt` or `effectHint` leaves the fingerprint unchanged after the audit confirms presentation-only use;
  - changing decision kind, acting player, source, targets, options, min/max, mode shape, constraints, payment requirements, effect semantics, or continuation relationship changes it;
  - a pending decision and every typed continuation/yield reference to the same runtime nonce normalize to one alias, while independent typed decision identities receive distinct deterministic aliases;
  - changing only the runtime nonce (`abc` versus `xyz`) produces the same fingerprint, while changing decision semantics with either nonce produces a different fingerprint;
  - the existing legacy fixture still produces the exact pre-v3 16-hex value for both replay versions 1 and 2.

- [ ] Add a test proving that derived `GameState.projectedState`/other caches do not enter the canonical input, while no constructor field is silently omitted. The test should serialize a baseline state and assert that the canonical field inventory contains every serialized constructor property except the explicitly audited presentation-only fields and routing-ID fields.

- [ ] Add a test fixture for `GameState.yieldsByPlayer` versus `CompactReplay.yields`: changing a future `ReplayYieldEntry` must not alter a fingerprint of the earlier state; applying that entry at its recorded action count must alter the resulting state fingerprint when the yield changes future behavior.

- [ ] Run the new tests once to capture the expected RED state, then commit only the characterization tests if the project’s normal TDD workflow permits a red test commit. Do not weaken assertions to make the current implementation pass.

**Expected result:** The missing v3 API and behavior are expressed as executable tests, the legacy value is protected, and the transition/presentation boundary is documented before implementation.

## Task 2: Implement versioned canonical fingerprints and typed nonce aliasing

**Files:**

- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayFingerprint.kt`.
- Add the two narrowly scoped helpers `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/TransitionSemanticGameStateCanonicalizer.kt` and `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/DecisionNonceAliasTable.kt`; do not put replay-specific logic into `rules-engine` or the A4 observation package.
- Update all production call sites found by `rg -n "ReplayFingerprint" game-server/src/main/kotlin`.
- Complete the tests in `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/ReplayFingerprintV3Test.kt`.

**Steps:**

- [ ] Preserve the current algorithm byte-for-byte under a named legacy path used by versions 1 and 2. It must continue emitting the current 16-hex SHA-256 prefix and must not inherit any v3 field or normalization change.

- [ ] Expose one explicit dispatch API for all callers, with the following behavior:

  ```kotlin
  fun of(state: GameState, replayVersion: Int): String
  // 1, 2 -> legacy algorithm
  // 3    -> complete v3 canonical algorithm
  // anything else -> UnsupportedReplayVersionException
  ```

  Keep any convenience `of(state)` call explicitly current-v3 only, or remove it and update call sites; do not leave an unqualified call that can silently use the wrong historical algorithm.

- [ ] Implement v3 canonicalization with the domain separator `argentum-engine/replay-fingerprint/v3`, stable UTF-8 encoding, and the complete 64-hex SHA-256 digest. Use the existing `persistenceJson` serializers for the typed state data only where that preserves the semantic distinction between ordered and unordered collections; otherwise write the typed canonical visitor over `GameState` and its serialized components.

- [ ] Canonicalize JSON/object map keys and known unordered sets by stable canonical element bytes. Preserve list order by default, explicitly preserving library order and stack order. Do not sort every JSON array, because that would erase transition semantics.

- [ ] Build a `DecisionNonceAliasTable` from typed decision-bearing state fields in deterministic traversal order. The first pending semantic decision is `D0`; a continuation/reference that points to the same nonce uses `D0`; independent typed routing identities receive the next deterministic aliases. Normalize only `PendingDecision.id`, `ContinuationFrame.decisionId`, and any additional decision-reference fields proven by the audit to be the same routing identity. Never replace arbitrary strings or unrelated IDs.

- [ ] Strip only the audited presentation-only decision fields (`prompt`, `effectHint`, and any equivalent UI label/description fields) from the v3 canonical form. Keep semantic options, targets, constraints, payment shape, mode shape, source, acting player, and continuation/effect semantics. Add a code comment pointing to the audit test and the A6 spec table so future additions cannot silently become presentation or semantic.

- [ ] Ensure the v3 serializer includes the applied `GameState.yieldsByPlayer` map but never reaches into `CompactReplay.yields`; replay inputs remain the reconstructor’s responsibility.

- [ ] Update live call sites so v3 hashing occurs only at the existing checkpoint/snapshot/resume boundaries. In particular, do not call the full canonicalizer from `ActionProcessor`, legal-action enumeration, or a per-action state observer.

- [ ] Run the fingerprint-focused test class and the existing replay unit tests. Commit as `fix: add versioned complete replay fingerprints` after the tests are green.

**Expected result:** v1/v2 fingerprints are unchanged, v3 is transition-complete and nonce-stable, ordered collections remain ordered, and the full-state canonicalizer is not on the live per-action hot path.

## Task 3: Move the codec to CompactReplay v3 with explicit future-version rejection

**Files:**

- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/CompactReplay.kt`.
- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayCodec.kt`.
- Add the public/package-private `UnsupportedReplayVersionException` in the replay package if it does not belong in `ReplayCodec.kt`.
- Complete `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/ReplayVersionCompatibilityTest.kt` and adjust existing codec fixtures in `CompactReplayReconstructionTest.kt`, `ReplayLegacyKickerDecodeTest.kt`, and `ReplayDurabilityTest.kt`.

**Steps:**

- [ ] Set `CompactReplay.CURRENT_VERSION` to `3` and update comments to say: v1 is the original format, v2 has legacy fingerprint/checkpoint semantics, v3 has the complete canonical fingerprint and tail-checkpoint contract. Remove the current claim that unknown future records are deliberately tolerated.

- [ ] Add a codec version probe that decodes only the top-level JSON version after gzip/base64 decoding. Missing `version` means v1. Versions 1, 2, and 3 follow their defined decode paths. A version greater than `CURRENT_VERSION` throws `UnsupportedReplayVersionException` carrying the encountered and supported versions before `CompactReplay.serializer()` can construct a value. Invalid versions below the historical range remain a controlled format error.

- [ ] Keep the existing `wasKicked` migration for supported historical records. Make sure the version probe and future-version rejection happen before any migration can turn a future payload into a partially decoded current record.

- [ ] Add tests for:
  - v1 JSON with omitted version decodes as version 1;
  - v2 round-trips and uses legacy fingerprint semantics;
  - v3 round-trips with a 64-hex checkpoint fingerprint;
  - a payload whose version is 4 or higher deterministically throws `UnsupportedReplayVersionException`;
  - unknown fields on supported versions remain tolerated as before;
  - the legacy kicker migration still passes without changing v1/v2 behavior.

- [ ] Commit as `fix: reject unsupported compact replay versions` once the codec tests pass. Do not yet change the JDBC/service fallback in this commit unless keeping the codec exception and service boundary together is required for compilation; if combined, keep the fallback in the next focused commit.

**Expected result:** Codec behavior is deterministic and fail-closed for future versions while supported historical records retain their existing decode path.

## Task 4: Add v3 tail checkpoints and strict reconstruction fidelity

**Files:**

- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/CompactReplay.kt` or add `ReplayCheckpointPolicy.kt` for one shared `ensureTail` helper.
- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/session/GameSession.kt`.
- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayCheckpointFlusher.kt`.
- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/handler/GamePlayHandler.kt`.
- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayReconstructor.kt`.
- Complete `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/ReplayDurabilityTest.kt` and `CompactReplayReconstructionTest.kt`; add `ReplayTailCheckpointTest.kt` if keeping the matrix separate makes the cases clearer.

**Steps:**

- [ ] Implement `ensureV3Tail(checkpoints, actionCount, fingerprint)` so it produces a unique checkpoint at `actionCount`, updates an existing same-count entry without duplication, and leaves v1/v2 lists untouched. Use it in every v3 writer path, not only at game over.

- [ ] Change `GameSession.stampCheckpointIfDue()` and `replayRecordingSnapshot()` to use the explicit current-v3 fingerprint. The snapshot’s fingerprint must describe exactly the state represented by its copied actions/yields, including applied `GameState.yieldsByPlayer`.

- [ ] Change `ReplayCheckpointFlusher` to create an in-progress v3 replay from one coherent `ReplayRecordingSnapshot` and add the tail checkpoint using `snapshot.fingerprint`. This makes the current persisted prefix verifiable even when the action count is not a multiple of 20.

- [ ] Change the game-over construction in `GamePlayHandler` to use one coherent final recording snapshot and add the final tail checkpoint. The resulting 0-action v3 record has a checkpoint at 0; a 43-action record has checkpoints at 20, 40, and 43; a 20-action record has one checkpoint at 20 rather than two.

- [ ] In `ReplayReconstructor`, dispatch every checkpoint hash through `ReplayFingerprint.of(state, replay.version)`. Verify an initial checkpoint at action count 0 against `initialState()` before folding actions. During the fold, hash only when a checkpoint exists at the resulting action count, including the tail.

- [ ] Keep v1/v2 fidelity behavior unchanged: an old record with no checkpoints remains `UNVERIFIED`, and an old record with matching legacy checkpoints can remain `EXACT` under the existing definition. For v3, return `UNVERIFIED` with an explicit missing-tail reason when all actions apply but no checkpoint exists at `actions.size`; return `DIVERGED` on any mismatch or rejected action. Do not classify a checked prefix plus unchecked suffix as `EXACT`.

- [ ] Preserve decision-response rebinding for replay execution. The recorded `SubmitDecision` still targets the fresh runtime decision ID; nonce aliasing belongs only to state fingerprint canonicalization and must not weaken response validation.

- [ ] Add the required matrix tests:
  - `VERSION3-SHORT-GAME`: fewer than 20 actions, final tail checkpoint exists, reconstruction can be `EXACT`;
  - `VERSION3-NON-MULTIPLE`: 43 actions produce exactly counts 20, 40, 43 and are `EXACT` only when count 43 matches;
  - a changed action 41–43 with checkpoints through 40 does not qualify as `EXACT`;
  - a manually constructed v3 record without a tail is `UNVERIFIED` with a missing-tail reason;
  - a zero-action v3 replay verifies its checkpoint at 0;
  - v1/v2 fixtures do not gain a retroactive tail.

- [ ] Commit as `fix: enforce v3 replay tail fidelity` after focused reconstruction/durability tests pass.

**Expected result:** `EXACT` is a statement about the complete recorded action stream, not merely about the last sparse checkpoint before it.

## Task 5: Preserve the archived presentation fallback for unsupported future versions

**Files:**

- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayStore.kt`.
- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/replay/ReplayService.kt`.
- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/controller/PublicReplayController.kt`.
- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/controller/PlayerReplayController.kt` to authorize `ReplayRead.UnsupportedVersion` using its retained seat IDs.
- Modify `game-server/src/main/kotlin/com/wingedsheep/gameserver/repository/RedisGameRepository.kt` to resume only `ReplayRead.Decoded` values and skip unsupported markers.
- Do not add a database migration: `GameReplayRow` already has the metadata, player roster, compact data, and separate presentation columns required for an in-memory unsupported marker.
- Add `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/ReplayFutureVersionFallbackTest.kt` and extend `ReplayStorageTest.kt` for JDBC/read-path characterization.

**Steps:**

- [ ] Introduce this explicit read result in `ReplayStore.kt`:

  ```kotlin
  sealed interface ReplayRead {
      data class Decoded(val stored: StoredReplay) : ReplayRead
      data class UnsupportedVersion(
          val gameId: String,
          val version: Int,
          val status: ReplayStatus,
          val playerIds: List<String>,
          val playerNames: List<String>,
          val startedAt: String,
          val endedAt: String,
          val winnerName: String?,
          val frameCount: Int,
          val tournamentName: String?,
          val tournamentRound: Int?,
          val engineVersion: String?,
          val presentation: String?,
      ) : ReplayRead
  }
  ```

  Change `ReplayStore.find(gameId)` and `ReplayService.findStored(gameId)` to return `ReplayRead?`; keep `findInProgress()` as `List<StoredReplay>` and filter unsupported rows before that boundary. The unsupported variant must never contain a synthetic or partial `CompactReplay`.

- [ ] Change `ReplayStore` reads so `JdbcReplayStore` catches `UnsupportedReplayVersionException` separately from ordinary corruption. It returns the unsupported marker with row metadata and presentation; it logs and drops only genuinely malformed/corrupt records as before. `findInProgress()` must not hand unsupported records to `GameSession.restoreReplayRecording`; it should skip them with a controlled warning. In-memory records remain decoded read results.

- [ ] Keep save/resume/finalize paths fail-closed: an unsupported marker cannot be overwritten by an in-progress flush, resumed, or converted into a fabricated partial replay. Normal decoded records retain all existing behavior.

- [ ] Add a `ReplayService` branch that handles the marker without calling `ReplayReconstructor`. If archived presentation exists, return it as `ReplayViewerPayload` with `ReplayFidelity.DIVERGED`, `stateReproducible = false`, and the stable reason `Unsupported CompactReplay version; showing the archived presentation.` If no archive exists, return the existing controlled unavailable/not-found outcome; do not expose a stack trace, raw compact payload, or fabricated `EXACT`/`UNVERIFIED` result.

- [ ] Update public and player replay endpoints to use the read result/metadata projection. Public viewing can render the archived presentation metadata without a decoded compact record. Player authorization must use the retained persisted seat IDs, not a name-only comparison. The full-state frame endpoint must return not-found/unavailable for an unsupported marker because no trusted `GameState` can be reconstructed.

- [ ] Add `VERSION-03A`: a future JSON version is rejected deterministically by `ReplayCodec.decode`.

- [ ] Add `VERSION-03B`: a stored future-version row with an archived presentation reaches the service/controller fallback without invoking reconstruction, returns degraded fidelity and `stateReproducible=false`, and never returns HTTP 500; the same row without presentation returns a controlled not-found/unavailable result. Also verify an unsupported in-progress row is not resumed or overwritten.

- [ ] Run replay storage/service/controller tests and commit as `fix: degrade unsupported replays to archived presentation`.

**Expected result:** Rolling deployments remain safe without reinterpreting a future compact stream and without making an archived replay disappear merely because its input version is newer.

## Task 6: Restore Gym snapshot episode semantics and fork equivalence

**Files:**

- Modify `gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt` so `snapshot()` passes `environment.stepCount` to `SnapshotCodec.save`.
- Extend `gym/src/test/kotlin/com/wingedsheep/gym/service/MultiEnvServiceTest.kt` for service-level snapshot behavior, or add `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvSnapshotTest.kt` if direct access to the underlying environment gives clearer assertions.
- Extend `gym/src/test/kotlin/com/wingedsheep/gym/GameEnvironmentTest.kt` only for direct `stepCount`/horizon assertions; do not edit A4 contract files.

**Steps:**

- [ ] Add the exact round-trip test: create an environment, advance it to a known step count `N`, take a snapshot, advance the original further, restore the snapshot, and assert `environment.stepCount == N`, state/observation digest equality, and independent continuation from the restored point.

- [ ] Add the horizon test with `maxSteps = 100`: advance to step 73, snapshot, advance or restore, then call the existing `runUntilTerminal(100)` path and assert the restored episode stops/truncates after 27 further steps rather than receiving another 100-step budget. Use the existing deterministic two-player registry/deck fixture so this test does not depend on random sealed-deck ordering.

- [ ] Preserve and strengthen fork equivalence: a fork copies state, player IDs, and `stepCount`; stepping a child does not mutate the source or sibling. Compare the full immutable state and step count where the existing tests already compare the digest.

- [ ] Run `GameEnvironmentTest` and `MultiEnvServiceTest`; commit as `fix: preserve Gym snapshot step counts`.

**Expected result:** Snapshot/restore preserves both the game state and the episode’s remaining horizon, and forked environments remain equivalent at the fork boundary.

## Task 7: Prove replay prefix determinism and the existing observation boundary

**Files:**

- Create `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/ReplayPrefixDeterminismTest.kt`.
- Create `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/ReplayDecisionContinuationTest.kt` if decision-specific cases do not fit cleanly in the prefix test.
- Reuse existing `SpectatorStateBuilder`, `ReplayReconstructor`, `GameSession` fixtures, and `gym`’s existing `ObservationBuilder`; do not modify `gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt`, `ObservationBuilder.kt`, `StateDigest.kt`, or `SchemaHash.kt`.

**Steps:**

- [ ] Drive one deterministic live session through representative prefixes, capturing the live immutable state and ordered actions after each applied action. Build a v3 replay from the same setup and coherent recording snapshot, including its tail. For early, mid-game, decision, and terminal prefixes, reconstruct the same frame and compare `ReplayFingerprint.of(liveState, 3)` with the reconstructed state fingerprint.

- [ ] Include at least one pending structured decision and its `SubmitDecision` action. Assert that replay execution rebinds the response to the fresh runtime decision ID, while v3 fingerprints remain equal when only the nonce changes. Assert that changing the answer/options/targets/acting player causes a fingerprint mismatch and never becomes `EXACT`.

- [ ] Include a continuation chain whose same decision ID appears in the pending decision and continuation state. Assert that the canonical alias is shared consistently; include an independent decision identity to prove aliases are not one global “ignore all IDs” operation.

- [ ] Compare reconstructed and live perspective observations by invoking the same existing `ObservationBuilder`. Normalize only the known volatile decision routing ID inside the test comparison; compare the semantic decision shape, visible zones, legal actions, and state digest inputs. Assert that hidden opponent information remains masked according to the normal builder. The test demonstrates reuse of the observation boundary; it must not claim that A4 privacy has been fully proved.

- [ ] Add focused state-coverage cases for RNG/next ID, ordered library/stack, yields, delayed/trigger queues, replacement/resume state, commander state, combat state, and last-known-information components. Use immutable `GameState.copy` fixtures where a full live card scenario is unnecessary, and one real replay scenario where the field is created by the engine.

- [ ] Add a source-level performance sanity gate alongside the tests: `rg -n "ReplayFingerprint"` must show calls only at live checkpoint/snapshot/resume boundaries and replay checkpoint/tail verification, with no call from `ActionProcessor`, legal-action enumeration, or ordinary per-action observation. Record the fixed fixture result as `PERF_SANITY = PASS`; do not add a B1 wall-clock benchmark.

- [ ] Commit as `test: prove replay prefixes and observation equivalence` after the focused suite is green.

**Expected result:** Replay reconstruction matches live transition semantics at representative prefixes, decisions remain safely rebound, Gym/replay observations use the existing perspective projection, and the full-state hash is not accidentally moved into the live action loop.

## Task 8: Verification, baseline protection, and scope audit

**Files:**

- No new production files in this task. Inspect all changed files, especially the four A4 paths and the spec/plan.

**Steps:**

- [ ] Run focused gates through `just` first, using the project semaphore. At minimum run:

  ```powershell
  just test-class ReplayFingerprintV3Test
  just test-class ReplayVersionCompatibilityTest
  just test-class ReplayTailCheckpointTest
  just test-class ReplayFutureVersionFallbackTest
  just test-class ReplayPrefixDeterminismTest
  just test-class GameEnvironmentTest
  just test-class MultiEnvServiceTest
  ```

- [ ] If PowerShell reports the known extensionless-helper `WinError 193`, use the documented Git Bash fallback and record it separately from a passing test result:

  ```powershell
  & 'C:\Program Files\Git\bin\bash.exe' -lc './scripts/gradle-locked :game-server:test --tests com.wingedsheep.gameserver.replay.ReplayFingerprintV3Test --tests com.wingedsheep.gameserver.replay.ReplayVersionCompatibilityTest'
  & 'C:\Program Files\Git\bin\bash.exe' -lc './scripts/gradle-locked :gym:test --tests com.wingedsheep.gym.GameEnvironmentTest --tests com.wingedsheep.gym.service.MultiEnvServiceTest'
  ```

  Do not describe the fallback as a `just` pass. If the lock helper is unavailable under Git Bash, state that limitation and use the repository’s direct Gradle wrapper only as a clearly labeled fallback.

- [ ] Run the replay and Gym module suites, then the repository’s required broader gates through `just`: `:game-server:test`, `:gym:test`, `:gym-server:test`, `:gym-trainer:test`, `:rules-engine:test`, `:mtg-sdk:test`, the relevant `:mtg-sets:<era>:test`/scenario gates, `:ai:test`, and `:oracle-assay:test`. No web-client files are in A6 scope, so no frontend change or frontend snapshot update is authorized by this plan.

- [ ] Run the frozen baseline test `com.wingedsheep.ai.evaluation.FrozenBaselineTest` and require the existing digest `6ff9ded1403d59ac`; do not re-bless snapshots. If an unrelated pre-existing failure appears, preserve the user’s changes, report it with the passing gates, and stop rather than fixing adjacent code.

- [ ] Run `git diff --check`, scan for conflict markers, inspect `git status --short`, and verify with `git diff --name-status origin/main...HEAD` that only the A6 spec, plan, replay/Gym implementation, and targeted tests changed. Specifically verify no diff in:

  ```text
  gym/src/main/kotlin/com/wingedsheep/gym/contract/TrainingObservation.kt
  gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt
  gym/src/main/kotlin/com/wingedsheep/gym/contract/StateDigest.kt
  gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt
  ```

- [ ] Before any publication, fetch `origin` and `upstream`, confirm `origin` is `https://github.com/chrismaghuhn/argentum-engine.git`, and synchronize normally with the requested `origin/main` checkout if it moved. Do not rebase, force-push, or silently merge unrelated work.

**Expected result:** The implementation has evidence from focused and broad tests, the frozen baseline is unchanged, Windows execution limitations are transparent, and the A4/scope boundary is mechanically verified.

## Task 9: Commit, hand off, and publish only after explicit authorization

**Steps:**

- [ ] Keep commits focused and reviewable. The intended sequence is:
  1. `test: characterize A6 replay state and version boundaries`;
  2. `fix: add versioned complete replay fingerprints`;
  3. `fix: reject unsupported compact replay versions`;
  4. `fix: enforce v3 replay tail fidelity`;
  5. `fix: degrade unsupported replays to archived presentation`;
  6. `fix: preserve Gym snapshot step counts`;
  7. `test: prove replay prefixes and observation equivalence`.
  The already reviewed spec and this plan remain separate documentation commits.

- [ ] Review the final diff and verification evidence before claiming completion. If all required gates pass and the user’s original publication authorization is still in force, push `agent/a6-replay-determinism-01` to the configured `origin` and open exactly one draft PR targeting `chrismaghuhn/argentum-engine`. Do not open a PR to the upstream repository and do not publish before the verification task is complete.

- [ ] If implementation stops before publication, hand off the current commit, test evidence, known blockers, and the exact next unchecked task. Never present a plan-only commit as an implemented feature.

## Completion checklist

- [ ] Spec and plan committed in `docs/superpowers/`.
- [ ] v1/v2 legacy decode and legacy fingerprint semantics verified.
- [ ] v3 64-hex transition-semantic fingerprint verified, including RNG, IDs, ordered zones/stack, decisions, continuations, yields, commander/combat/replacement state.
- [ ] Typed nonce aliasing passes positive and negative tests and does not normalize arbitrary IDs.
- [ ] Every new v3 replay has a unique tail checkpoint; v3 `EXACT` requires its match.
- [ ] Future versions reject deterministically and use the archived-presentation fallback where available.
- [ ] Gym snapshot restore preserves `stepCount` and remaining horizon.
- [ ] Existing `ObservationBuilder` is reused and all A4 files are untouched.
- [ ] `PERF_SANITY = PASS`; no full-state hash in the per-action engine loop.
- [ ] Focused suites, broad gates, FrozenBaseline, diff hygiene, and publication-target checks are evidenced.
