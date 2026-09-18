# KAGGLE_ACTOR_06_TRANSPORTED_REPLAY_02 — Production Offline Replay Verifier + OfflineAdmission Wiring

Task: **KAGGLE_ACTOR_06_TRANSPORTED_REPLAY_02**
Status: **IMPLEMENTATION_PASS = YES** (independent review pending)
Predecessor: KAGGLE_ACTOR_06_TRANSPORTED_REPLAY_01 (PR #211, merge `f22d2fc46871d96ff6d175bfcfce110594d9ec1a`)

## Revision history

- `da9d83a1058e3485289118db154ffcda488a58e4` — original `_02` delivery. The independent review
  found P1 (environment identity repository authority defined but never invoked) and five P2
  hardening findings plus one P3 wording issue; verdict `IMPLEMENTATION_PASS = NO`.
- `f9971fe762`…`5beae6d7eb` — **KA06_02_REMEDIATION_01**: all findings closed in three commits
  (sealed authority flow + hardening; test-layer fixes; closure-aware horizon derivation).
  Focused suite 14/14, KA06 gate 5/5, regressions 851/851 at `5beae6d7eb`. This document describes
  the remediated state; original-review findings are addressed inline below.

## KA06_02_REMEDIATION_01 (P1 + 5×P2 + P3 closure)

1. **P1 — environment authority now enforced, and the seam is sealed.** `reconstruct()` is
   reachable only through `TransportedReplayReconstructorV1.authenticate(...)`, which performs the
   same-revision source-bootstrap doctrine and returns an `AuthenticatedReconstructorV1` bound to
   that proof. Reconstruction then re-derives the repository-owned environment fields (card
   definition digest, both deck digests via the real curriculum loader) and requires exact
   equality with the claimant identity BEFORE any execution; `ENVIRONMENT_IDENTITY_MISMATCH` is a
   live production failure code again. Negative control with real curriculum authority included.
2. **P2 — self-validating result boundary.** Before `Verified`, the launcher additionally
   requires binding fidelity `EXACT`, `completeRangeVerified`, and internal binding identities
   (verification + chosen-input content identity, both action counts) equal to the outer verified
   values. Negative controls: non-EXACT binding and wire-forged internal mismatch, both rejected.
3. **P2 — replay horizon is an explicit limit contract.** Derived closure-aware from the durable
   range: horizon-reached episodes reconstruct with exactly the durable range (the producer
   fixture ends `HORIZON_REACHED` at 40 steps — deriving plain range+headroom changed the
   truncation boundary and produced no closure; this is the exact case the review asked to derive
   from range/closure), natural terminations get fixed headroom, all under a hard `4096` ceiling.
   Ranges beyond the ceiling fail closed (`UNSUPPORTED_ENVIRONMENT`), never truncated; overrides
   may only lower the horizon.
4. **P2 — temp workspaces are cleaned in a `finally` block** on every outcome (success, timeout,
   crash, malformed response).
5. **P2 — the protocol is byte-bounded.** Explicit budgets for request (32 MiB), response
   (128 MiB), and streamed worker output (64 KiB cap); oversized messages produce typed
   fail-closed results, and the bounded output read caps memory from a misbehaving worker.
6. **P2 — production construction vs test injection separated.** The class is now `final` and its
   public constructor takes only the repository root (plus bounded timeout internals); classpath/
   executable/heap moved behind the test-only `WorkerLaunchOverridesV1` seam (internal
   constructor), and the binding seam is exposed to tests via `invokeBoundResultForTest` only.
7. **P3 — evidence wording corrected.** The focused tampered-commit control exercises the sealed
   authentication flow against a non-git probe root (bootstrap-doctrine rejection); the real
   HEAD-mismatch→`SOURCE_REVISION_MISMATCH` control remains the `_01` oracle. See the controls
   table below for the exact mapping.


## Delivery summary

```text
TASK = KAGGLE_ACTOR_06_TRANSPORTED_REPLAY_02
BASE_SHA = f22d2fc46871d96ff6d175bfcfce110594d9ec1a
CURRENT_ORIGIN_MAIN = f22d2fc46871d96ff6d175bfcfce110594d9ec1a
BRANCH = chris/kaggle-actor-06-transported-replay-02-production-verifier-20260917
PREDECESSOR_PR = 211
PREDECESSOR_MERGE_SHA = f22d2fc46871d96ff6d175bfcfce110594d9ec1a
PREDECESSOR_FINAL_ACCEPTANCE = YES
MAIN_ANCESTOR_OF_HEAD = YES

PRODUCTION_OFFLINE_REPLAY_VERIFIER = PRESENT
PRODUCTION_VERIFIER_PROCESS_ISOLATED = YES (separate worker JVM, @argfile launch)
SOURCE_REVISION_AUTHENTICATED = YES (LocalSourceBootstrapV1/GitSourceBootstrapProbeV1 before any reconstruction)
SAME_REVISION_ONLY = YES
HISTORICAL_SOURCE_PROVISIONING = NOT_IMPLEMENTED (explicitly; see §Deferral)

REQUEST_BINDING_TRAJECTORY_ID = YES
REQUEST_BINDING_SEMANTIC_EPISODE_ID = YES
REQUEST_BINDING_REPLAY_CONTENT_IDENTITY = YES
REQUEST_BINDING_REPLAY_ACTION_COUNT = YES

SEMANTIC_REBINDING = COMPLETE (against fresh CURRENT legal domains; no stored ids as authority)
FRESH_REPLAY_RECONSTRUCTION = YES
FRESH_A4_BINDING = YES (verifier-generated, from verifier replay bytes)
REPLAY_FIDELITY = EXACT
COMPLETE_RANGE_VERIFIED = YES

OFFLINE_ADMISSION_WITH_VERIFIER = VERIFIED, acceptedSources > 0
DATASET_ELIGIBLE_WITH_VERIFIER = true (only path to eligibility)
OFFLINE_ADMISSION_WITHOUT_VERIFIER = NO_INDEPENDENT_PROOF, acceptedSources = 0 (unchanged default)
DATASET_ELIGIBLE_WITHOUT_VERIFIER = false (unchanged, regression-pinned)
KAGGLE_PROVIDER_RUN = NO
```

## 1. Base and predecessor

- Base: `f22d2fc46871d96ff6d175bfcfce110594d9ec1a` — verified as actual `origin/main` at execution
  time via `git fetch origin` (the prompt-creation SHA was re-confirmed, not assumed).
- `origin/main` is an ancestor of the delivery head (`ahead 5 / behind 0` at gate time).
- PR #211 merge is an ancestor of the base; the `_01` characterization
  (`KaggleActor06TransportedReplayHarness` / `KaggleActor06TransportedReplayCharacterizationTest`
  / `kaggleActor06CharacterizationTest` gate) remains intact and passing (§33).

## 2. Production architecture

New leaf module **`:offline-replay-verifier`** owns the production verifier. The DAG direction is
strictly additive: `gym`, `gym-trainer` (the `OfflineReplayVerifierV1` seam),
`game-server` (CompactReplay / GymReplayFrameSource / curriculum / A4-fold infrastructure),
`rules-engine`, `mtg-sdk`, `mtg-sets` — nothing depends on the verifier module, so no cycle exists
and no documented seam is violated (the game-server → gym-trainer seam comment in
`game-server/build.gradle.kts` was left untouched; the leaf module composes both from above).

Responsibilities:

- **`TransportedReplayReconstructorV1`** (`com.wingedsheep.gameserver.replay.verification`) — the
  reusable reconstruction primitive extracted from the accepted `_01` harness: environment identity
  re-derivation from repository authority (deck sources, card registry, engine commit), GameConfig
  reconstruction, semantic action + structured decision rebinding against fresh CURRENT domains,
  fresh replay + replay content identity, A4 fold, fresh TrajectoryV1 rebuild. Runs **inside the
  worker only**. It is production code; the `_01` harness remains an independent oracle that must
  not import it (conformance is proven by test, not by shared code).
- **`VerifierWorkerProtocolV1`** — versioned canonical-JSON request/result protocol
  (`argentum-ml-offline-replay-verifier-worker@v1`, version 1). Request: version, schemaIdentity,
  base64 canonical claimant trajectory, repositoryRoot, requiredPinnedPaths. Result: version,
  schemaIdentity, status, typed failureCode, verified identity block, fresh binding (when verified),
  bounded diagnostics. No Java serialization, no arbitrary class loading, no shell strings, no
  opaque stdout parsing.
- **`ProductionOfflineReplayVerifierV1`** — the `OfflineReplayVerifierV1` implementation. Writes the
  canonical request to a temp file, launches a fixed worker JVM via Java **@argfile** (the raw
  classpath exceeded the Windows 32k CreateProcess limit — the @argfile launch is part of the
  production contract now), bounded timeout, and maps outcomes onto the typed taxonomy below.
  Command ownership is hard-coded (fixed main class, fixed invocation shape, classpath derived from
  the module's own runtime classpath) — no caller-supplied command (§18).
- **`VerifierWorkerMain`** — worker entrypoint. Authenticates same-revision source authority
  **before** any reconstruction, then delegates to the reconstructor, writes the result file, and
  fails closed on every internal error.

## 3. Request/result identity binding (§7/§25)

Before returning `Verified`, the verifier requires equality between its fresh reconstruction and the
exact requested trajectory:

- `trajectoryId`
- `semanticEpisodeId`
- `replayContentIdentity`
- `replayActionCount`

The returned `ReplayTrajectoryBindingV1` is the verifier's own fresh binding (fresh A4, fresh
chosen-input binding, fresh semantic decision identities) — never a cached, echoed, or producer
binding (§21/§26; no caching exists).

Audit result on additional binding fields (§7): the environment identity and closure are already
fully covered because the verifier *reconstructs* them from repository authority and the request
binding compares fresh-vs-claimant trajectory ids (which digest the environment identity + closure +
replay identity). Policy provenance is compared by `OfflineAdmissionV1` against the assigned work
item before verification is invoked; dataset source identity is the manifest/shard concern of the
envelope (strict reimport), which remains outside the replay verifier's responsibility (§9).
No extra identity fields were invented.

## 4. Source authority (§5/§14)

`VerifierWorkerMain` executes the accepted source-bootstrap doctrine
(`LocalSourceBootstrapV1` + `GitSourceBootstrapProbeV1`: `git rev-parse HEAD` equality, clean
tracked tree, required pins) against `trajectory.environmentIdentity.engineCommit` **before**
creating any `GameEnvironment`. On mismatch: typed `SOURCE_REVISION_MISMATCH` result, never a
reconstruction attempt, never a fallback to current-code replay. Doctrine failure on other grounds
(dirty tree, missing pins) → `SOURCE_REVISION_UNVERIFIED`. Historical commits are explicitly out of
scope: the verifier never checks out, downloads, clones, or provisions other revisions (§5/§27).

## 5. Failure taxonomy (§15)

`OfflineReplayFailureCodeV1` was extended (existing names preserved verbatim):

```text
NO_PROOF, REPLAY_NOT_EXACT, REPLAY_INCOMPLETE, REPLAY_DIVERGED, REPLAY_CONTENT_INVALID  (existing)
SOURCE_REVISION_MISMATCH        (new) — engineCommit != executed verifier source revision
SOURCE_REVISION_UNVERIFIED      (new) — bootstrap doctrine failed (dirty tree / pins / HEAD unavailable)
ENVIRONMENT_IDENTITY_MISMATCH   (new) — repository re-derivation diverges from claimant
SEMANTIC_REBIND_FAILED          (new) — transported choice not a member of the CURRENT legal domain
REPLAY_CONTENT_IDENTITY_MISMATCH (new) — fresh replay identity diverges from claimant
TRAJECTORY_IDENTITY_MISMATCH    (new) — request binding failed (any of the four identity fields)
UNSUPPORTED_RECONSTRUCTION      (new) — contract boundary not supported by this verifier version
INTERNAL_VERIFIER_FAILURE       (new) — worker crash / malformed result; always fail-closed
VERIFIER_TIMEOUT                (new) — bounded timeout exceeded; fail-closed, never NO_INDEPENDENT_PROOF
```

Unknown/internal failures never become `VERIFIED`; exception text is diagnostics only.

## 6. Process boundary and protocol (§16/§17/§18/§19)

- Producer process ≠ verifier process: admission runs in the caller JVM, but verification runs in a
  fresh worker JVM (`ProcessBuilder` + @argfile) that receives only the request file path. No
  producer `GameState`, `GameEnvironment`, binding, registry, RNG state, or action objects cross
  the boundary.
- Bounded I/O: request and result are canonical JSON files with version/schema validation on both
  ends; oversized or malformed results are `INTERNAL_VERIFIER_FAILURE`.
- Timeout: bounded wait; on expiry the worker process is destroyed and the result is typed
  `VERIFIER_TIMEOUT` — never `NO_INDEPENDENT_PROOF`, never `VERIFIED` (§19). A committed result
  before the deadline is never raced by the wall clock.

## 7. OfflineAdmission wiring (§22/§23/§24/§30)

`OfflineAdmissionV1` keeps its documented authority semantics, unchanged in this slice except for
the failure-code vocabulary (above) — the admission loop itself was already trajectory-level
fail-closed:

- Default (no verifier): `NO_INDEPENDENT_PROOF`, `acceptedSources = 0`, `datasetEligible = false`
  — pinned by an existing regression and re-proven on a real envelope by the new integration test.
- With verifier: each source trajectory is independently verified; `datasetEligible = true` requires
  `VERIFIED` **and** all items accepted. Verifier failure (`Unavailable`/throw) flips the
  request-global status to `BLOCKED` and kills eligibility; the ledger retains per-item membership
  of trajectories that did verify (existing contract, now exercised by a production-verifier test).
- `repackAcceptedSources` already requires `VERIFIED` + eligible and rejects otherwise — pinned by
  the new failing-claim test.
- One failing claim blocks dataset eligibility for the whole request (§24); two durable sources of
  the same deterministic episode accept once through the existing dedup contract.
- A6 layering: `TrajectoryV1Admission.admit` stays **inside** `OfflineAdmissionV1` (it consumes the
  verifier's fresh binding as evidence). The verifier itself never calls A6 — single authority
  (§22), superseding the `_01` characterization's probe-side A6 call.

## 8. Semantic rebinding coverage (§11/§12)

The reconstructor reuses the existing generic semantic replay primitives (candidate semantics,
structured decision decoding, A4 replay verification) — no second implementation. Every transported
choice is rebound against the fresh CURRENT boundary: fresh observation → `CompleteLegalDomainV1` →
canonical candidate membership → fresh registry/decision resolution → execute. Structured response
kinds follow the accepted contract's coverage (targets, cards, modes, color, number, distribution,
ordering, piles, option, replacement, budget/modal, damage assignment, payment) via the generic
decoder; unknown future kinds fail closed as `SEMANTIC_REBIND_FAILED`/`UNSUPPORTED_RECONSTRUCTION`.

## 9. Privacy review (§28)

Verifier outputs are the typed result (status, failure code, verified identity block, fresh
binding), bounded diagnostics, and worker stderr excerpts — no opponent hands, libraries, raw
`GameState`, hidden exile, face-down internals, or JVM binding tables. The verifier exposes no HTTP
endpoint; it is a process invoked by admission. Worker stderr diagnostics are bounded in length.

## 10. Determinism review (§20)

The integration test verifies the same transported trajectory **twice** through the production
verifier: both runs produce byte-identical fresh bindings (`second.replayTrajectoryBinding shouldBe
binding`). Two independently published envelopes of the same deterministic episode carry identical
trajectory identities and accept once. No temp names, PIDs, timestamps, or working-directory paths
participate in any identity.

## 11. Tests

Focused (`:offline-replay-verifier:test` — 14 tests):

- worker protocol round-trips a canonical request and result
- typed reconstruction failures map onto the admission failure taxonomy
- request binding: a worker result for a different trajectory id is rejected; every identity field
  participates
- worker exits nonzero without a response file when its output path is unwritable (crash shape)
- bounded timeout produces a typed `VERIFIER_TIMEOUT`, never `VERIFIED`
- unsupported worker protocol version is rejected by the contract
- same-revision authentication precedes reconstruction in the worker contract (sealed-flow
  rejection against a non-git probe root; see controls table)
- tampered claimant engine commit is rejected by the sealed authentication flow
- a syntactically valid but non-EXACT binding can never surface as VERIFIED (P2)
- an EXACT binding with internally mismatched identities is rejected (P2)
- environment identity repository re-derivation rejects wrong card/deck digests (P1, real
  curriculum authority; positive pole with all three repository-derived real values)
- horizon contract: horizon-reached episodes reconstruct with the exact durable range
- horizon contract: natural terminations get headroom under the ceiling
- horizon contract: overrides may only lower the horizon within the ceiling

End-to-end (inside the `:gym:kaggleActor06CharacterizationTest` gate, real published envelopes,
fresh verifier JVMs — timings from the gate run):

- production verifier independently reconstructs a transported episode and admits it (119.7s) —
  includes double-verification determinism and the no-verifier fail-closed default
- duplicate durable claims of one deterministic episode accept once (60.0s)
- one failing claim blocks dataset eligibility for the whole request (31.4s) — includes the
  `repackAcceptedSources` fail-closed pin
- `_01` characterization oracle still passes: exact independent-process reconstruction (213.2s) and
  worker-failure plumbing (0.3s)

## 12. Regression scope (§34)

```text
FOCUSED_VERIFIER_TESTS = :offline-replay-verifier:test — 8/8 PASS
KA06_01_CHARACTERIZATION = :gym:kaggleActor06CharacterizationTest — 5/5 PASS
  (gate run: BUILD SUCCESSFUL in 12m 4s at delivery head bf01a2a654, --rerun-tasks, machine-global lock)
GYM_REGRESSIONS = :gym:test PASS
GAME_SERVER_REGRESSIONS = :game-server:test PASS
GYM_TRAINER_REGRESSIONS = :gym-trainer:test PASS (185 tests; incl. admission suite)
REPLAY_REGRESSIONS = covered by :game-server:test + :offline-replay-verifier:test
TRAJECTORY_REGRESSIONS = covered by :gym-trainer:test
Combined regression run: 1087 PASSED / 0 FAILED (4m 51s)
```

No test was skipped or unavailable.

## 13. Negative controls (§4/§31/§32)

```text
NEGATIVE_ENGINE_COMMIT = REJECTED
  focused: sealed-flow bootstrap rejection (non-git probe root, real checkout HEAD unavailable)
  _01 oracle: real HEAD-mismatch control maps to SOURCE_REVISION_MISMATCH before reconstruction
NEGATIVE_ENGINE_SEED = REJECTED (in _01 oracle, control A)
NEGATIVE_SEMANTIC_CHOICE = REJECTED (in _01 oracle, control B)
NEGATIVE_TRUNCATED_RANGE = REJECTED (in _01 oracle, control C)
NEGATIVE_REPLAY_IDENTITY = REJECTED (in _01 oracle, control E; fresh-identity divergence maps to REPLAY_CONTENT_IDENTITY_MISMATCH)
NEGATIVE_REQUEST_IDENTITY = REJECTED (focused: per-field request binding)
NEGATIVE_WORKER_TIMEOUT = REJECTED (focused: typed VERIFIER_TIMEOUT)
NEGATIVE_WORKER_FAILURE = REJECTED (focused: crash + malformed → INTERNAL_VERIFIER_FAILURE; gate: worker failure surfaces as failed verifier process)
```

## 14. Explicit limitations and deferral (§36)

- **Same-revision only.** The verifier supports `artifact.engineCommit == verifier source revision`.
  Historical shards return `SOURCE_REVISION_MISMATCH` (meaning: "cannot independently verify under
  current source authority" — not "trajectory is semantically wrong") until SOURCE_PROVISIONING_01.
- **No caching, no batching, no daemon verifier** (§26/§40). Measured cost: fresh JVM worker ~
  startup included in per-verification wall time (119.7s for a full-episode verify incl. producer
  setup in the same test; focused protocol tests ~1s). Performance is deliberately not optimized.
- **Single-revision verification gate** — a later real-provider admission smoke
  (KAGGLE_ACTOR_06_TRANSPORTED_REPLAY_03) remains the next slice (4–8 episodes, not run here).

## 15. Files changed (vs base `f22d2fc468`)

```text
PRODUCTION_FILES = 6
  offline-replay-verifier/build.gradle.kts (new module, isTransitive=false game-server consumption pattern)
  settings.gradle.kts (module registration)
  offline-replay-verifier/.../TransportedReplayReconstructorV1.kt (867 lines, reconstruction primitive)
  offline-replay-verifier/.../VerifierWorkerProtocolV1.kt (250 lines, versioned canonical JSON protocol)
  offline-replay-verifier/.../ProductionOfflineReplayVerifierV1.kt (194 lines, launcher + binding)
  gym-trainer/.../ActorOfflineAdmissionV1.kt (+27: failure-code vocabulary only)
TEST_FILES = 2
  offline-replay-verifier/.../ProductionOfflineReplayVerifierTest.kt (394 lines, 8 tests)
  gym/.../KaggleActor06ProductionVerifierIntegrationTest.kt (145 lines, 3 E2E tests)
BUILD_FILES = 1
  gym/build.gradle.kts (gate task registration: + testImplementation(project(":offline-replay-verifier")), + integration spec in gate)
DOC_FILES = 1 (this report)
Total: 9 files changed, 1922 insertions(+), 3 deletions(-) at gate head; +1 doc commit
Remediation adds 3 commits (2 production/test files + report): sealed authority flow, P2
hardening (final class, overrides seam, byte bounds, temp cleanup, binding self-validation),
closure-aware horizon contract, and the expanded focused suite (14 tests).
```

## 16. Verification evidence

```text
WORKTREE_CLEAN = YES at report commit
GATE = PASS at 5beae6d7eb (5/5; BUILD SUCCESSFUL in 9m 36s, --rerun-tasks, machine-global lock)
REGRESSIONS = PASS (:offline-replay-verifier + :gym + :game-server + :gym-trainer; 851 PASSED / 0 FAILED, 5m 6s)
  (earlier evidence: GATE PASS at bf01a2a654 5/5 12m04s; combined regression run 1087 tests before
   the focused suite grew to 14)

PRODUCTION_FILES + TEST_FILES compile clean; no schema, replay-version, rules, card, deck, or ML change.
TRAJECTORY_SCHEMA_CHANGED = NO
REPLAY_SCHEMA_CHANGED = NO
RULES_CHANGED = NO
CARD_DEFINITION_CHANGED = NO
DECKS_CHANGED = NO
ML_CHANGED = NO
```

## 17. Follow-ups

1. **KAGGLE_ACTOR_06_TRANSPORTED_REPLAY_03 — REAL_PROVIDER_ADMISSION_SMOKE**: small-count (4–8
   episodes) real provider admission through the production verifier path proven here.
2. **SOURCE_PROVISIONING_01**: historical-source provisioning to lift the same-revision-only
   limitation for old shards (the verifier's `SOURCE_REVISION_MISMATCH` is designed as the typed
   seam this will plug into).
3. Optional later: measurement-driven verifier throughput work (only after `_03` decides whether
   batch admission needs it — no cache/batch/daemon now, per §26/§40).
