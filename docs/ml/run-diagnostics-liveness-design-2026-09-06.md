# RUN_DIAGNOSTICS_D0 — Liveness and Stall Diagnostics Design

Status: D0 source audit, harness audit, failure-mode characterization, and implementation planning
only. This document does not implement a heartbeat, supervisor, watchdog termination, recovery,
training run, corpus generation, trajectory-schema change, replay change, Rules change, observation
change, or legal-domain change.

## 1. Executive decision

The repository has useful pieces, but no reusable operational liveness layer. Existing counters and
guards are workload-specific, semantic, or test-only. The central distinction for #139 is therefore:

```text
PROCESS_LIVENESS       = an OS process still exists
HEARTBEAT_LIVENESS    = a diagnostics scheduler is still being scheduled
USEFUL_PROGRESS       = an owned workload cursor or counter advanced
```

The recommended architecture is a small, dependency-light Kotlin/JDK 21 `run-diagnostics` module.
It owns an operational sidecar and an external supervisor, but has no dependency on Rules, `GameState`,
Gym observations, legal domains, replay objects, or training data. Workload adapters report only
typed scalar progress and versioned stage references.

The preferred V1 behavior after a trigger is always:

```text
capture bounded diagnostics -> classify as evidence-backed suspicion -> continue observing
```

There is no automatic termination, interruption, retry, recovery, or semantic relabeling.

## 2. Source and provenance boundary

This audit was performed from a dedicated worktree created from the current fork head after fetching
both remotes.

```text
TASK=RUN_DIAGNOSTICS_D0_CHARACTERIZATION_01
AUDIT_DATE=2026-09-06
REPOSITORY=chrismaghuhn/argentum-engine
ORIGIN_URL=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM_URL=https://github.com/wingedsheep/argentum-engine.git
BASE_ORIGIN_MAIN=2a030fa8aa6eb86a1b468f1c7a9ec7f5a10cda89
UPSTREAM_MAIN=5021faf88093a93091e4de7914fbe0f411499d58
AUDIT_SOURCE_HEAD=2a030fa8aa6eb86a1b468f1c7a9ec7f5a10cda89
AUDIT_BRANCH=chris/run-diagnostics-d0-characterization-20260906
AUDIT_WORKTREE=C:\Users\chris\.config\superpowers\worktrees\argentum-engine\run-diagnostics-d0-characterization-20260906
```

The original checkout at `C:\argentum-engine` was left on its existing local `main` and was not
modified. The separate `post-b2-bounded-characterization-20260906` worktree was not modified.

Evidence labels used below:

| Label | Meaning |
| --- | --- |
| VERIFIED | Directly observed in the exact source tree or in a bounded local tool probe. |
| INFERENCE | A consequence of verified control/data flow; not a runtime root-cause claim. |
| UNKNOWN | Not established because D0 does not run a long workload, attach to a live workload, or add instrumentation. |
| PROVISIONAL | A future configuration/example value used only to make a fixture executable; not an acceptance threshold. |

### B0/B1/A9 naming corrections

The current source must be treated as the authority, not historical labels.

- `EnvironmentV1ExactPairAcceptanceTest` currently contains a 72-episode exact-pair corpus, a
  2,000 external-step bound, and explicit 30-minute test timeout. Its loop counts transitions and
  public boundary families; it does not emit a periodic heartbeat.
- `EnvironmentV1TrustedGenerationTest` currently targets 64 episodes and may extend to 72 to obtain
  terminal/interrupted coverage. It has an eight-hour test timeout, uses `A9_MAX_STEPS = 2_000`, and
  publishes through the trajectory writer. Its `A9_BASE_SHA` is a historical hard-coded identity,
  not a current-head check; the file currently contains `0aa9444c...`, not the current `origin/main`.
- The B1 test metrics field named `engineProgress` is incremented in the same branch as the accepted
  external transition counter. It is not an engine-internal progress measurement. It must not be
  promoted to the D0 `ENGINE_INTERNAL_PROGRESS` meaning without hardening.
- B1 source-head constants and old measurement reports identify their own historical measurement
  heads. They are useful provenance, not live branch identity.

Primary source links: [`EnvironmentV1ExactPairAcceptanceTest.kt`](../../gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1ExactPairAcceptanceTest.kt#L1203-L1215),
[`EnvironmentV1TrustedGenerationTest.kt`](../../gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1TrustedGenerationTest.kt#L88-L115),
[`EnvironmentV1TrustedGenerationTest.kt`](../../gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1TrustedGenerationTest.kt#L145-L242),
[`B1PerformanceBaselineTest.kt`](../../gym/src/test/kotlin/com/wingedsheep/gym/B1PerformanceBaselineTest.kt#L305-L405).

## 3. Existing diagnostics inventory

The table deliberately includes semantic guards and operational tools that must not be conflated.
`Input -> output` describes the observable boundary. “Heavy” means it can materially block or
allocate, not that it always does so.

| Location / owner | Current purpose and input -> output | Semantic or operational | Threading and clock | Can block / allocate heavily / leak hidden state | #139 treatment |
| --- | --- | --- | --- | --- | --- |
| `rules-engine/.../GameLimits.kt:26-108`; `.../EffectExecutorRegistry.kt:115-173` / Rules | Clamp quantities, cap token/repeat materialization, and stop recursive effect execution -> state/error plus `System.err` text. | Semantic engine safety backstop. | Synchronous; count/depth based; no wall-clock source. | Token/repeat caps still allocate up to configured bounds. Error text can contain implementation detail; not sidecar-safe. | Reuse as Rules safety behavior only. Do not turn it into a supervisor or operational counter. |
| `rules-engine/.../StateBasedActionChecker.kt:44-123` / Rules | Re-run SBAs until stable, then after 1,000 iterations end the game as a draw -> `ExecutionResult` and `GameEndedEvent`. | Semantic game outcome. | Synchronous, state-threaded, iteration bound. | Accumulated event list allocates; last events are written to stderr. | Reference only. D0 must never alter or duplicate this terminal semantics. |
| `ai/.../GameSimulator.kt:115-184` / AI simulator | Auto-pass/resolve quiet states and cap one simulation's quiet-resolution loop at 100 iterations -> `SimulationResult`. | Semantic-adjacent simulation behavior. | Synchronous; no wall clock; `iterations` is local. | Each processor call can be expensive and event concatenation allocates. It may enter a caller-supplied decision resolver. | Reference only for a stage boundary. No supervisor dependency. |
| `gym/.../GameEnvironment.kt:100-186,198-210,228-417,560-655` / Gym | Hold mutable adapter fields around immutable `GameState`; reset, accept/strict-step, fork/restore, and optional `maxSteps` -> `StepResult`. | Mixed: semantic state/closure plus operational horizon. | Per-env single-owner contract, not enforced by a lock; synchronous; no wall clock. | `events = events + result.events` copies cumulative event history; `projectedState`, enumeration, simulation, and observation can be heavy. Direct `StepResult` contains full `GameState`. | Harden the public accepted-transition seam outside `GameState`; do not add status to Rules state or use cumulative events as a heartbeat. |
| `gym/.../GameGymEnv.kt:39-91,136-220,223-304,318-440` / Gym | Build perspective-safe observations and ephemeral action registry; route structured actions; classify failures -> `ObservationResult` or typed failure. | Mixed, with public semantic and freshness-routing fields. | Mutable per-env; selected fields are `@Volatile`, but the adapter is documented as single-owner. Synchronous. | Observation/domain construction and re-enumeration can be heavy; action handles and registry are operational; raw engine action/state paths are not data-safe. | Reuse as an adapter boundary. Never put `ActionRegistry`, action IDs, pending IDs, or raw observations into the status sidecar. |
| `gym/.../EpisodeDiagnostics.kt:8-57`; `rules-engine/.../DiagnosticSignal.kt:3-47`; `ExecutionResult.kt:11-69` / Rules + Gym | Carry typed unsupported-path evidence and fail closed without serializing it -> `DiagnosticSignal`, `EpisodeDiagnostics`, `UnsupportedPathFailure`. | Semantic/integrity failure ledger, not run liveness. | Immutable list/copy; environment-owned. No clock. | List growth is bounded by episode usage but can allocate. Codes intentionally avoid hidden payloads; downstream failure text may not be safe. | Reference only. Do not add heartbeat/stall events to this ledger; preserve its role in trajectory admission and episode closure. |
| `gym/.../service/EnvWorkerPool.kt:8-37`; `MultiEnvService.kt:20-139` / Gym service | Fan out independent environment calls -> ordered results; document same-env single-thread ownership. | Operational adapter around semantic work. | `ForkJoinPool`; `Future.get()` waits without a deadline; close awaits five seconds. | A single blocked task blocks the caller’s ordered result list. No automatic same-env serialization or cancellation. | Harden/reuse ownership metadata and add an outer deadline/cancellation contract later; do not hide a blocked future as healthy. |
| `gym/.../service/SnapshotCodec.kt:25-83` / Gym service | Hold immutable `GameState` references in in-process slots -> opaque snapshot handles. | Operational routing around semantic state. | `ConcurrentHashMap` and `AtomicLong`; no clock. | Retains full state until explicit disposal; handle is process-local and not durable. | Reference only for lifecycle ownership; never include snapshot handles or state in status/data. |
| `gym/src/test/.../B0HarnessTimeoutPolicy.kt:7-20`; `gym/src/test/.../io/kotest/provided/ProjectConfig.kt:7-11` / B0 test harness | 10-minute ordinary soft timeout, 3-hour `SMOKE_64` stage allowance -> Kotest timeout. | Operational test harness. | Kotest timeout; no explicit progress sampler. | Soft timeout does not reliably stop a tight CPU loop; `:gym` config does not install `TestHangGuard`. | Reuse as historical stage-policy evidence only. Not production liveness. |
| `rules-engine/src/testFixtures/.../TestHangGuard.kt:14-101`; `HangGuardedProjectConfig.kt:23-25` / test fixture | 120-second soft timeout plus 300-second daemon watchdog; dump all thread stacks and `Runtime.halt(13)` -> failed process. | Operational test harness with destructive action. | Shared daemon scheduled executor; duration-based. | Thread dump allocates/output can be large; `halt` skips shutdown/finalizers; disabled by `benchmark=true`. Stack output is privileged. | Reference only for bounded test protection and dump formatting. Its `halt` behavior is explicitly not #139 V1. |
| `.github/workflows/ci.yml:83-113` / CI | After 20 minutes, `jps` all visible JVMs, run `jstack -l`, then `kill -9` the Gradle PID -> CI failure evidence. | Operational CI watchdog. | Shell `sleep`; process discovery is runner-wide. | `jstack` can block; `jps` may include unrelated JVMs; killing only the parent is not proven to reap descendants. Output is privileged. | Reference only. Reuse the capture-before-failure shape with target identity and bounded commands; no automatic production kill. |
| `buildSrc/.../kotlin-jvm.gradle.kts:36-85`; `gradle.properties:9-28` / build | Configure JDK 21 toolchain, Gradle test JVM, heap split, benchmark/test properties, and log streams -> build/test behavior. | Operational build infrastructure. | Gradle/worker managed; no workload heartbeat. | Compiler daemon can consume 6 GB; test workers 2 GB; settings are not run diagnostics. | Reference for provenance and process attribution, not a liveness source. |
| `scripts/gradle-locked:3-81` / build tooling | Two-slot machine-global Gradle semaphore; 5-second polling and 1,800-second wait -> Gradle process or unlocked fallback. | Operational resource guard. | Shell sleep; `shlock` if installed. | Queue can wait 30 minutes; if `shlock` is unavailable it explicitly runs unlocked. | Reference only; report lock state separately from workload progress. |
| `scripts/kill-stale-daemons.sh:3-117`; `prune-worktree-builds.sh:3-117` / cleanup | Sample process age/CPU/RSS and optionally kill/reclaim stale daemon/build outputs -> dry-run or destructive cleanup. | Operational maintenance, not a watchdog. | macOS-oriented `ps`; 3-second CPU sample; `--apply` is destructive. | Process attribution is heuristic and platform-specific; cleanup can delete other worktree build outputs. | Do not reuse automatically. Manual cleanup stays separately authorized and scoped. |
| `gym/src/test/.../B1PerformanceBaselineTest.kt:94-218,305-520,775-1079` / B1 profiler | Opt-in workload timer, `System.nanoTime`, JFR recording, MXBean process CPU/allocation/heap/GC, public counters -> JSON/JFR artifacts. | Test-only performance measurement. | Synchronous workload; `jdk.jfr.Recording`; monotonic elapsed timing. | JFR stop/dump and JSON write block; MXBean allocation sampling may be expensive. The probe does not retain `GameState`; JFR is privileged. | Extract generic measurement concepts, not the test class or its labels. Keep profiler overhead outside #119 baselines when enabled. |
| `gym/src/test/.../B1ScalingMeasurementTest.kt:879-1142,1216-1370,2069-2255` / B1 profiler | Measure 1/2/4/8 env scaling, latency, reset trend, process CPU, all-thread allocation, heap, Windows RSS and JFR -> JSON/JFR. | Test-only performance measurement. | `EnvWorkerPool`; `System.nanoTime`; Windows `Get-Process` through `powershell.exe` with a five-second wait. | PowerShell sampling blocks; all-thread allocation and heap sampling allocate/perturb; only Windows RSS is implemented. | Reuse as a reference/possible metric adapter; harden into an opt-in, bounded abstraction rather than copying code. |
| `gym/src/test/.../B1ObservationProbe.kt`; `B1ObservationBytecodeInstrumentation.kt` / B1 characterization | Thread-local scalar counters and temporary ASM class-file patch/restore -> characterization JSON and restored class bytes. | Test-only measurement. | Thread-local probe; file mutation transaction with restore in `finally`. | Class rewrite/restore blocks and can damage build outputs if restoration fails; no hidden state retained by the probe itself. | Probe counters are reusable as measurement vocabulary only. Do not reuse bytecode mutation in production or D1. |
| `ai/.../budget/DecisionBudget.kt:28-39,151-196`; `Strategist.kt:183-240` / AI | Convert nominal time tiers to deterministic search counts; use `System.nanoTime` as a hard safety stop -> chosen AI action. | Semantic AI control, not operational diagnostics. | Synchronous; monotonic deadline; legacy global budget is unbounded. | Search can be expensive; changing its deadline can change semantic action selection. | Reference only. Do not let run-status thresholds affect AI choices. |
| `ai/src/test/.../arena/TableGameRunner.kt:131-175,183-339`; `PodArena.kt:93-167` / arena harness | Action/turn caps, 300-actions-per-turn wedge detector, no-progress identity check, safe fallback, optional action stream -> outcome/report. | Operational test harness around semantic simulation. | Per-game synchronous, parallel outer executor; no wall-clock per-game deadline; `take().get()` waits. | State and action callbacks can expose hidden/internal state; fallback changes benchmark behavior after a bug. | Reuse bounded fixture shapes and explicit counters only. Do not treat its wedge result as a generic root cause. |
| `gym-trainer/.../selfplay/SelfPlayLoop.kt:30-113`; `SelfPlaySink.kt:1-56`; `JsonlSelfPlaySink.kt:35-150` / trainer | MCTS `simulationsPerMove`, `maxSteps`, sink begin/step/end, buffered JSONL outcome write -> training rows. | Training semantics plus operational I/O. | One loop is single-threaded; evaluator/HTTP can block; sink buffers one full game. | `TrainerContext` carries raw `GameState`; sink features are caller-controlled and may leak hidden state; append/flush blocks and is not crash-transactional. | Reference only. Add a sidecar adapter around lifecycle calls; never use the sink as trusted status or trajectory writer. |
| `ai/src/main/.../TrainingCorpusFiles.kt:17-56` / legacy data | Validate then rewrite a whole corpus through a temp file -> atomic move if available, otherwise `REPLACE_EXISTING`; append reads the whole corpus. | Data/provenance persistence. | Synchronous; no timeout. | O(corpus) reads/serialization and large allocations; caller rows may contain hidden state. Fallback is non-atomic. | Do not reuse for trusted status/publication. It is a reference for temp-file naming only. |
| `gym-trainer/.../trajectory/TrajectoryV1Writer.kt:81-302`; `TrajectoryV1StorageFrames.kt:57-156` / A5/A6 | Validate a complete semantic trajectory, replay binding, privacy, and exact counts -> admitted immutable episode bytes or quarantine metadata. | Semantic/provenance trust boundary. | Synchronous, no internal concurrency. | Whole episode is encoded to `ByteArray`; canonical JSON/digests allocate and can block the caller. Operational keys are explicitly rejected. | Directly reuse the admission result and ownership semantics; never add run status fields to Trajectory V1. |
| `gym-trainer/.../TrajectoryV1Publisher.kt:23-347,519-554`; `TrajectoryV1Quarantine.kt:50-186` / A6 publication | Buffer bounded shards, force bytes, verify digest/counts, write manifest last, atomically publish a complete dataset directory; persist typed quarantine -> final dataset or quarantine. | Operational publication with semantic integrity. | Caller-serialized; `FileChannel.force`, reads, and atomic moves block. | Current shard holds admitted bytes; verification rereads files. Quarantine metadata is bounded and safe; exceptions carry no persisted raw payload. | Strongest atomic-publication source. Extract a generic file primitive and retain staging/manifest-last invariants; do not make it depend on diagnostics. |
| `gym-trainer/.../TrajectoryV1ManifestPreflight.kt:47-184`; `TrajectoryV1Reader.kt:9-126`; `TrajectoryV1ShardValidator.kt:59-175` / A7 | Validate canonical manifest/path/size/digest and every shard before returning a handle; revalidate each shard before yielding -> validated bounded stream. | Semantic storage-integrity boundary with operational blocking. | Synchronous, manifest-owned order; no cancellation hook. | Full manifest and one bounded shard/episode list are materialized; a large shard can block before first yield. No hidden state is exposed through the reader API. | Reuse as a reader workload adapter. Add only callback/cancellation seams later, not a parallel reader. |
| `game-server/.../CompactReplay.kt:28-196`; `ReplayFingerprint.kt`; `GymReplayFrameSource.kt:42-226`; `ReplayReconstructor.kt:89-337,469-560` / replay | Store deterministic inputs/checkpoints, reconstruct frames, verify action/checkpoint fidelity -> exact/diverged/unverified replay evidence. | Semantic replay proof and privileged developer diagnostic. | Synchronous fold; `GameSession` uses locks; replay callbacks are internal. | O(frame count) state/observation/delta allocation; raw `GameState` is available to same-module callbacks. No wall-clock deadline. | Reuse checkpoint/fidelity cursor and `replayForward` callback as an adapter, keeping raw state privileged and absent from sidecar. |
| `game-server/.../ReplayCheckpointFlusher.kt:13-168`; `ReplayService.kt:70-177`; `ReplayStore.kt:104-238`; `GameSession.kt:1354-1495,1681-1747` / server replay | Flush in-progress replay every five seconds from one coherent locked snapshot; archive asynchronously; reconcile stranded rows -> in-progress/finished replay lifecycle. | Operational durability with semantic replay safeguards. | Spring scheduled task; single daemon archiver; DB save is synchronous; shutdown archive wait is 20 seconds. | Snapshot copies actions/yields/checkpoints; DB/serialization can block; action log is not data-safe. `recordingRevision` is routing freshness, not semantic progress. | Reuse coherent snapshot and independent scheduler patterns; do not use `recordingRevision` as an engine counter or include it in Trajectory V1. |
| `gym-server/.../GymServerApplication.kt:6-24`; `MetaController.kt:11-30`; `docker-compose.yml:23-28` / transport | `/health` returns constant `{status:"ok"}`; Docker healthcheck belongs to Postgres -> transport/container liveness. | Operational process/service liveness. | HTTP request thread/container probe; no environment cursor. | Can be healthy while a Gym env or worker is stuck; no TTL/reaper/heartbeat header. | Reuse only as a coarse process probe. It cannot be the #139 useful-progress signal. |
| `game-server/.../websocket/GameWebSocketHandler.kt:68-77,154-162`; `WebSocketConfig.kt:25-37`; `AiWebSocketSession.kt:36-114,266-310` / server AI | Ping/Pong, five-minute WebSocket idle timeout, asynchronous AI message processing -> transport response/action. | Operational transport/AI control path. | Ping handler is synchronous; AI uses an IO coroutine per message; no visible per-session backpressure/serialization. | HTTP/LLM/controller calls can block; logs include prompts, action descriptions, and potentially sensitive state-derived text. Concurrent handling is an inference until runtime-tested. | Reference only. Any reuse needs explicit serialized ownership and an outer controller deadline. |
| `gym-trainer/.../RemoteHttpEvaluator.kt:45-84`; `ai/.../LlmClient.kt:33-97`; `AiConfig.kt:1-17` / external inference | Blocking Java HTTP request -> evaluator result or LLM text; evaluator default 30 seconds, LLM default 300 seconds with two retries/backoff. | Operational transport feeding semantic choices. | Calling thread blocks; `Thread.sleep` between LLM retries. | Can consume minutes; request features/prompts may contain hidden data. | Reuse transport timeout as a lower-layer signal only; do not count request completion as authoritative game progress unless the controller reports it. |
| `game-server/.../SchedulingConfig.kt:7-33`; `ZombieSessionSweeper.kt:23-94`; `SessionRegistry.kt:30-45,121-123` / server lifecycle | Daemon scheduler, one-minute cleanup, disconnect grace timers -> removed sessions/lobbies/identities. | Operational lifecycle. | Scheduled synchronous work; wall-clock `currentTimeMillis` for tournament grace. | Repository calls can block; cleanup mutates server state. No run heartbeat. | Reference for daemon scheduler and cleanup ownership; not a stall classifier. |

### Audit conclusion

The highest-value existing seams are `GameEnvironment` strict commit, the coherent replay snapshot,
`ReplayReconstructor`'s bounded callback fold, A6 admission, the A6 publisher/quarantine, and A7's
manifest-owned reader. The strongest gaps are absent useful-progress publication, unbounded
`Future.get()` at `stepBatch`, no environment TTL/heartbeat contract, no production structured
observability, and no generic bounded external process diagnostic.

## 4. Reuse matrix

| Existing component | Decision | Boundary to preserve |
| --- | --- | --- |
| `GameEnvironment.stepCount` / strict `processAndCommit` | `EXTRACT_GENERIC_SEAM` | Emit an operational callback only after a validated transition has been committed. Never add a field to `GameState`, `StepResult` wire data, or replay identity. |
| `GameGymEnv` observation/action boundary | `HARDEN_AND_REUSE` | Count accepted external operations and completed public decisions at the adapter; do not reuse action handles, registries, or raw `TrainingObservation` as status. |
| `MultiEnvService` / `EnvWorkerPool` ownership | `HARDEN_AND_REUSE` | Preserve one owner per env; add future bounded cancellation/deadline reporting around `Future.get()` rather than changing Gym semantics. |
| `EpisodeDiagnostics` / `DiagnosticSignal` | `REFERENCE_ONLY` | Keep semantic unsupported-path failure and operational stall evidence in separate stores. |
| `GameLimits`, SBA cap, `GameSimulator.maxIterations`, `StateProgress` | `DO_NOT_REUSE` for operational liveness | These decide semantic safety, AI choice, or game outcomes. They are not evidence that a process is useful. |
| B0 policy, corpus counters, and timeout policy | `REFERENCE_ONLY` plus `EXTRACT_GENERIC_SEAM` for counter definitions | Reuse explicit episode/transition ownership and bounded fixtures. Do not reuse the test timeout as a production threshold or infer progress from stdout. |
| B1 `JvmSnapshot`, MXBeans, RSS probe, JFR setup | `EXTRACT_GENERIC_SEAM` | Move to injectable bounded samplers; preserve null/unsupported values, separate profiler overhead, and never enable per-transition metrics when disabled. |
| B1 ASM class-file instrumentation | `DO_NOT_REUSE` | It mutates build outputs and is a test-only restoration transaction. |
| `TableGameRunner` / arena wedge detector | `REFERENCE_ONLY` | Its action/turn heuristic is workload-specific and can discard legitimate long work. |
| `TrajectoryV1Admission` / quarantine | `REUSE_AS_IS` at the semantic boundary | Keep exact replay, privacy, and quarantine outcomes; expose only aggregate operational counters to the sidecar. |
| `TrajectoryV1Publisher` staging, `FileChannel.force`, manifest-last, atomic directory move | `HARDEN_AND_REUSE` | Extract a generic atomic-file primitive; fail closed on unsupported atomic replacement and never fall back to torn overwrite for trusted artifacts. |
| `TrajectoryV1Reader` / A7 preflight and shard validator | `HARDEN_AND_REUSE` | Add bounded progress callbacks/cancellation only at the storage boundary; retain manifest-owned membership and no directory discovery. |
| `ReplayRecordingSnapshot` / `ReplayCheckpointFlusher` | `HARDEN_AND_REUSE` | Reuse coherent snapshot-under-lock and separate scheduler ownership. Keep `recordingRevision` out of semantic progress/identity. |
| `ReplayReconstructor.replayForward` | `HARDEN_AND_REUSE` | Count verified callbacks without persisting `GameState`; maintain exact/diverged/unverified fidelity. |
| `TrainingCorpusFiles` | `REFERENCE_ONLY` | Its non-atomic fallback and whole-corpus rewrite are unsuitable for trusted status or large sidecars. |
| CI `jps`/`jstack`, `kill-stale-daemons`, `prune-worktree-builds` | `REFERENCE_ONLY` | Reuse bounded capture and process attribution ideas only; no destructive action in the V1 supervisor. |
| `/health`, Ping/Pong, `ZombieSessionSweeper` | `REFERENCE_ONLY` | They establish service/transport/session liveness, not useful workload progress. |
| `SelfPlayLoop` / `JsonlSelfPlaySink` | `REFERENCE_ONLY` | Use lifecycle locations as future adapters; never make raw `TrainerContext` or buffered training rows the diagnostics authority. |

## 5. Operational authority boundary

The authority hierarchy is intentionally one-way:

```text
Rules / GameState / observation / legal domain / replay / trajectory
        |  reports only owned scalar events at existing boundaries
        v
RunProgressRecorder (operational, no Rules dependency)
        |  AtomicReference of bounded scalars
        +--> heartbeat scheduler updates heartbeatSequence
        +--> status publisher writes run-status.json
        +--> external supervisor reads sidecar + PID/OS metrics
                         |
                         +--> optional privileged jcmd/JFR bundle
```

The diagnostics layer is not an input to any of these semantic identities or decisions:

```text
PlayerObservationV1, CompleteLegalDomain, TrajectoryV1, DatasetManifest,
CompactReplay, StateDigest, semantic episode/decision IDs, reward, policy input,
training target, promotion result
```

The diagnostics layer may record an opaque, caller-supplied `semanticJobId` only when it is already
public-safe. It must never derive one from PID, hostname, diagnostic run ID, wall time, heartbeat,
stage, or progress counters.

## 6. Progress taxonomy

| Progress kind | Contract meaning | Value kind | Owner and current source status |
| --- | --- | --- | --- |
| `PROCESS_LIVENESS` | The target process exists and has not exited. | Gauge plus `PROCESS_EXITED` event. | External supervisor using a process reference; JDK `ProcessHandle` is sufficient for existence, subject to PID reuse. |
| `HEARTBEAT_LIVENESS` | The diagnostics scheduler was scheduled and advanced its sequence. | Monotonic counter plus timestamp. | Dedicated in-process scheduler; absent today. Never tied to a semantic decision. |
| `ENGINE_INTERNAL_PROGRESS` | A specifically owned internal engine unit completed, such as a replay frame fold or an explicitly instrumented bounded sub-operation. | Monotonic counter, optional. | No generic current source. B1's `engineProgress` is actually external-transition-aligned and must not be renamed silently. |
| `AUTHORITATIVE_TRANSITION_PROGRESS` | One externally submitted action/decision was validated and committed at the selected workload boundary. | Monotonic counter. | `GameGymEnv`/strict `GameEnvironment` adapter; current `stepCount` and B1 `transitions` are the closest sources. |
| `SEMANTIC_DECISION_PROGRESS` | A public controller boundary returned one complete semantic action/response for execution. | Monotonic counter. | Workload controller. Current B1 increments before `policy.choose`, so it is measurement-attempt progress, not yet the D0 completion contract. |
| `TRAJECTORY_PROGRESS` | A trajectory decision record was durably/factually finalized by the trajectory owner. | Monotonic counter, optional. | Future trajectory builder/A5 owner. Current A6 writer receives a complete trajectory; it has no public per-decision callback. |
| `REPLAY_VERIFICATION_PROGRESS` | One replay frame/boundary passed the verifier callback and is retained as verified evidence. | Monotonic counter, optional. | `GymReplayFrameSource`/`ReplayReconstructor` adapter. Do not use attempted actions or raw state reads. |
| `ARTIFACT_PUBLICATION_PROGRESS` | A bounded artifact unit passed its publication boundary: forced bytes, verified shard, manifest, or final dataset directory. | Monotonic counters plus publication events. | `TrajectoryV1Publisher` state machine. Current methods are mostly private but ownership is precise. |
| `EPISODES_ADMITTED` | One A6-admitted episode entered the publisher's trusted membership. | Monotonic counter. | `TrajectoryV1Writer`/publisher admission result, after `Admitted`, never after generation alone. |
| `CURRENT_STAGE` | The named operational phase currently owning the work. | Gauge plus `stageSequence` and timestamp. | Workload adapter; new stage families do not change Rules semantics. |

Rules for counters:

1. A counter increments only at the owner boundary named above, after the boundary succeeds.
2. A failed attempt, a printed line, a PID sample, a file discovery, or a collection-size query is
   not useful progress unless the owning contract explicitly says so.
3. No counter is computed by iterating an unordered collection. Ordered manifests and explicit
   producer ordinals remain the only source for ordered artifact counts.
4. `null` means “not instrumented or unavailable”; it never means zero.
5. None of these values participates in a gameplay identity or action choice.

## 7. `ProgressVectorV1` conceptual contract

The following is the minimal generic shape. It is a design contract, not Kotlin code in D0:

```text
ProgressVectorV1 {
    heartbeatSequence: Long

    stageSequence: Long
    currentStage: StageRefV1
    stageStartedMonotonicElapsedNanos: Long

    episodeOrdinal: Long?

    engineProgressCount: Long?
    authoritativeTransitionCount: Long?
    semanticDecisionCount: Long?

    trajectoryDecisionCount: Long?
    replayFramesVerified: Long?
    episodesAdmitted: Long?

    bytesSerialized: Long?
    shardsFinalized: Long?

    lastUsefulProgressMonotonicElapsedNanos: Long?
}
```

`heartbeatSequence` and `stageSequence` are counters. `currentStage` is a gauge. `episodeOrdinal`
is an explicit workload coordinate, not a count of admitted episodes. The `*Count` fields are
monotonic counters with the ownership rules in Section 6. The two monotonic timestamps are
process-relative elapsed values and are useful to an in-process reader; the supervisor computes
its own observation age between status changes because separate JVM `nanoTime` origins are not
comparable.

`bytesSerialized` means bytes produced by a bounded serialization boundary. It is not a claim that
the bytes are durable. A future `bytesDurablyWritten`/`bytesPublished` field may be added only if
its owner and force/close boundary are explicit. `shardsFinalized` increments only after the
publisher verifies the shard and moves it into the staging publication tree.

## 8. Stage model

### Options

| Option | Benefit | Cost / failure mode |
| --- | --- | --- |
| A — one giant global enum | Closed vocabulary and easy switch statements. | Every new operational workload edits a shared enum; unrelated modules coordinate; trajectory, reader, learner, and evaluation names collide. |
| B — `workloadType + stageFamily + stageName` strings | New workloads can add names without a global enum. | Without a versioned family and producer validation, typos become silent status values. |
| C — versioned typed stage families | Each workload has a closed, reviewable vocabulary and schema; new workloads add a family rather than editing a global enum. | Small amount of per-workload type/adapter boilerplate. |

### Decision

Use a C/B hybrid: `workloadType` is a stable operational label, and `StageRefV1` carries a
versioned `stageFamilySchemaIdentity` plus a producer-validated stable `stageName`. There is no
global stage enum. A trajectory producer can use `argentum-diagnostics-trajectory-stages@v1`; a
reader, learner, or evaluation worker owns a different family. The sidecar may carry an
`UNSPECIFIED` stage only when no adapter can observe a boundary; it must not invent a finer phase.

### Trajectory-generation observability

These labels describe what the current source can expose without claiming that private calls already
have hooks.

| Proposed stage | Classification in current source | Evidence / boundary |
| --- | --- | --- |
| `BOOTSTRAP` | `APPROXIMATE_STAGE` | A9 creates registry, resolver, temp output root, metadata, and writer in one harness setup. No generic runtime stage event exists. |
| `EPISODE_SETUP` | `OBSERVABLE_STAGE` at adapter boundary | `MultiEnvService.create`/`GameEnvironment.reset` are public calls. Setup internals remain included. |
| `GYM_ADVANCE` | `OBSERVABLE_STAGE` at adapter boundary | `MultiEnvService.step`/`submitDecision` returns one strict result; in A9 each loop increments `transitions` after the call. |
| `OBSERVATION_BUILD` | `NOT_SEPARATELY_OBSERVABLE` from current public caller | `GameGymEnv.buildObservation` is private and includes `legalActions`, `ObservationBuilder`, diagnostics, cache, and handle remapping. |
| `DOMAIN_BUILD` | `NOT_SEPARATELY_OBSERVABLE` | `ObservationBuilder` constructs observation/domain/action views together. B1 ASM can count calls but is test-only instrumentation, not a runtime phase boundary. |
| `POLICY_DECISION` | `OBSERVABLE_STAGE` in B0/A9 harness | `DeterministicExternalPolicy.choose` is an explicit call. A remote evaluator/LLM call is a lower-level transport stage of a different owner. |
| `TRAJECTORY_APPEND` | `OBSERVABLE_STAGE` | `TrajectoryV1Writer.appendEpisode` is public; its combined A5/A6 result is visible. |
| `A5_VALIDATE` | `NOT_SEPARATELY_OBSERVABLE` | Internal to `TrajectoryV1Admission.admit`; the public result is combined admission/quarantine. |
| `A6_ADMIT` | `APPROXIMATE_STAGE` unless an adapter reports the returned admission | Publisher append/admission is visible as a single call; only the returned `Admitted` value proves membership. |
| `REPLAY_VERIFY` | `OBSERVABLE_STAGE` as a whole; per-frame is `APPROXIMATE_STAGE` | `GymReplayFrameSource.verifyTrajectoryBinding`/replay fold is a call boundary. `ReplayReconstructor.replayForward` has an internal frame callback that can be hardened later. |
| `SERIALIZE_SHARD` | `NOT_SEPARATELY_OBSERVABLE` | Private `TrajectoryV1Publisher.writeShard` writes the complete current shard. |
| `VALIDATE_SHARD` | `NOT_SEPARATELY_OBSERVABLE` | Private `verifyShardFile`/`countEpisodeFrames` run inside finalize. |
| `FINALIZE_SHARD` | `APPROXIMATE_STAGE` | The owner is known (`finalizeCurrentShard`), but no external callback exists. |
| `FINALIZE_MANIFEST` | `NOT_SEPARATELY_OBSERVABLE` | Private `publishManifestLast` writes/forces/moves `manifest.json`. |
| `A7_PREFLIGHT` | `OBSERVABLE_STAGE` as a whole; per-shard `APPROXIMATE_STAGE` | `TrajectoryV1Reader.openPublishedDataset` preflights all shards before returning a handle. |
| `A7_STREAM_VALIDATE` | `OBSERVABLE_STAGE` as a whole; per-shard `APPROXIMATE_STAGE` | `streamEpisodes` rechecks and validates each manifest-owned shard before yielding. |
| `DONE` | `OBSERVABLE_STAGE` | Caller receives the final result. It is not inferred from a missing process. |

The design rule is that a long private phase may remain one stage. Stage age alone is a warning
context, never proof of a stall.

## 9. Heartbeat ownership

### Compared choices

- A workload thread alone is simple but fails the central case: if that thread blocks in rules
  resolution, serialization, file I/O, or a remote call, its heartbeat stops with its useful work.
- A dedicated scheduler alone distinguishes a blocked workload thread from a dead JVM, but its
  heartbeat must not be mistaken for useful work.
- An external supervisor alone can observe PID/file freshness but cannot see a precise accepted
  transition or replay-frame cursor without an in-process contract.

### Decision: hybrid

1. A dedicated daemon `ScheduledExecutorService` owns `heartbeatSequence`. Each tick performs only
   bounded scalar work and records process-relative monotonic time into an `AtomicReference`/scalar
   snapshot. It does not read `GameState`, hold a workload lock, call `legalActions`, or write a
   trajectory.
2. Workload threads call a nonblocking recorder at owned boundaries. They update counters/stage and
   `lastUsefulProgressMonotonicElapsedNanos`; they do not emit a heartbeat for merely entering a
   loop.
3. A coalescing status publisher serializes the latest bounded snapshot and writes `run-status.json`.
   It is independent from the workload thread and must not hold the workload's semantic lock.
4. The external supervisor observes sidecar sequence/cursor changes, process metrics, declared safe
   artifact sizes, and optional privileged JVM evidence.

If the workload is blocked but the JVM scheduler still runs, `heartbeatSequence` advances while
useful counters do not. If the JVM is globally stopped, CPU-starved, deadlocked around the scheduler,
or exited, heartbeat freshness also fails. A heartbeat therefore proves only scheduler scheduling,
not useful progress.

The scheduler and publisher must use daemon threads or an explicit close path. The current server's
daemon `SchedulingConfig`, `SessionRegistry` disconnect scheduler, and replay archiver are useful
precedents; the test `HardTimeoutWatchdog` is not a production lifecycle model.

## 10. Time semantics

Use `System.nanoTime()` (or an injected monotonic clock abstraction backed by it) for:

- heartbeat age;
- useful-progress age;
- stage age;
- sampling/capture cooldown;
- subprocess capture deadlines.

Record `Instant.now(UTC)` wall-clock timestamps only for human correlation, log joins, and process
start display. A wall-clock adjustment must not change a stall classification. Do not use
`GameState.timestamp`: it is a logical Rules ordering counter, and `GameState.tick()` changes it as
semantic effects/turn operations require. Do not use `currentTimeMillis` for stall age.

The recorder stores process-relative elapsed nanos, not an absolute JVM `nanoTime` value. The
supervisor cannot compare two JVMs' arbitrary monotonic origins; it records its own monotonic time
when a status sequence/counter change is observed. Arithmetic must be subtraction-based, including
overflow-safe timeout checks.

Official JDK sources: [`System.nanoTime`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html#nanoTime())
defines a high-resolution elapsed-time source unrelated to wall time and warns that different JVMs
may use different origins; [`StandardCopyOption`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/StandardCopyOption.html)
defines `ATOMIC_MOVE` and `REPLACE_EXISTING` as distinct filesystem options.

## 11. Stall classification model

### Three distinct outputs

```text
STALL_TRIGGER          = configured evidence threshold was crossed
DIAGNOSTIC_CLASSIFICATION = best bounded label from all observed signals
FINAL_ROOT_CAUSE       = later human/postmortem conclusion, not generated by V1
```

`SUSPECTED_STALL` is deliberately not `CONFIRMED_DEADLOCK`. `DEADLOCK_DETECTED` is emitted only
when JVM tooling reports an actual monitor/ownable-synchronizer cycle; even then the application
root cause remains a postmortem question.

### Signal collection

At each supervisor sample, collect what is available:

| Signal | Use | Limitation |
| --- | --- | --- |
| Process handle alive / exit status | Definitive process-exit observation. | PID can be reused; identity must be checked against process start/command. |
| Status `heartbeatSequence` and last observed change time | Scheduler liveness. | A stale file may mean writer I/O failure, not a dead JVM. |
| Status progress counters and last-useful elapsed value | Useful work cursor. | Only as trustworthy as the workload adapter's ownership contract. |
| Current stage and stage age | Context and warning policy. | Stage age alone cannot distinguish a valid expensive phase from a wedge. |
| Process CPU delta | Distinguish CPU spin from blocked wait. | CPU can be high during legitimate work; external portability varies. |
| Heap/RSS/GC counters | Support GC-pressure diagnosis and capacity interpretation. | Occupancy and RSS are noisy; absent values are unknown. |
| Thread count | Context for leaks/executor growth. | A stable count does not prove progress or deadlock. |
| Explicit safe artifact sizes | Publication/storage progress. | No growth may be normal; do not recursively scan arbitrary data roots. |
| Bounded thread-dump sequence | Stable stacks and lock evidence. | A snapshot is not a causal proof; commands can fail or perturb the target. |

### Classification pipeline

```text
1. Validate status schema and target PID identity.
2. If the target is gone, emit PROCESS_EXITED and retain exit evidence.
3. Compare current heartbeat/progress cursors to the supervisor's prior sample.
4. Apply configured heartbeat/useful-progress trigger rules.
5. If triggered, capture bounded JVM/OS/artifact evidence subject to cooldown.
6. Classify only from the combined evidence; preserve UNKNOWN/MISSING evidence.
7. Continue observing. Never terminate or relabel the workload.
```

Suggested V1 classifications:

| Classification | Required pattern | What it does not prove |
| --- | --- | --- |
| `HEALTHY` | Heartbeat is fresh and at least one owned useful cursor advanced within its configured policy. | It does not prove semantic correctness. |
| `SUSPECTED_STALL` | Process alive, useful progress exceeded its threshold, and no stronger classification is justified. | Not a deadlock or permanent failure. |
| `CPU_SPIN_SUSPECT` | Useful progress stale, process CPU materially active, and bounded dumps show a stable hot stack or repeated loop location. | High CPU can be legitimate expensive work. |
| `BLOCKED_WAIT_SUSPECT` | Useful progress stale, CPU low/flat, and dumps show stable `WAITING`/`BLOCKED`/I/O wait. | A wait may be legitimate; no deadlock claim without a cycle. |
| `GC_PRESSURE_SUSPECT` | Useful progress stale or severely delayed, with high GC/heap evidence and available metrics. | Heap pressure may coexist with valid progress. |
| `IO_STALL_SUSPECT` | Useful/artifact progress stale, status/artifact publication reports failure or I/O stacks are stable. | No artifact growth may be a normal quiet phase. |
| `DEADLOCK_DETECTED` | JVM ThreadMXBean/jcmd evidence explicitly identifies a deadlock cycle. | The business-level root cause still needs analysis. |
| `PROCESS_EXITED` | Process handle is no longer alive; capture exit status if available. | Exit reason may be unknown. |
| `UNKNOWN` | Status/metrics/JVM tooling are unavailable or contradictory. | Unknown is not healthy and not failure proof. |

If heartbeat is fresh while useful progress is stale, the expected first label is
`SUSPECTED_STALL`, not `HEALTHY`. If useful progress continues during a long stage, the expected
result is `HEALTHY` with an optional slow-stage warning. If status publication is stale but the
process has CPU and other evidence, do not fabricate the missing in-process cursor.

## 12. Threshold policy

D0 freezes no universal `NO_PROGRESS_FOR_N_MINUTES` value. The current repository has no per-stage
duration distributions for the private observation/domain/A5/A6/finalization boundaries, and the
existing 5-second replay flush, 10-minute test default, 30-minute acceptance test, and eight-hour
generation test are not stall thresholds.

Future configuration must include at least:

```text
heartbeatTimeout
usefulProgressTimeout
stageWarningThresholds[stageFamily/name]   # warning/context first, not hard failure
sampleInterval
diagnosticCaptureCooldown
maxDiagnosticBundles
threadDumpCount
threadDumpInterval
captureTimeout
maxHistorySamples
```

All values are monotonic durations. Stage-specific values are justified only after measured
per-stage distributions exist. Until then, use one workload profile with a stage-warning map that
is empty by default and preserve long-progressing stages as healthy. A D3 fixture may use
`heartbeat=30s`, `useful=10m`, `sample=5s`, `cooldown=60s`, `maxBundles=3`, and three two-second
dumps with a five-second per-command timeout solely as `PROVISIONAL` test configuration. Those
numbers are not acceptance criteria and must not be copied into a production default without D1/D4
calibration.

## 13. `run-status.json` contract

### Identity separation

| Identity | Meaning | May enter semantic/game/data identity? |
| --- | --- | --- |
| `semanticJobId` | Caller-provided stable job/workload identity, only when already public-safe. | It may already be semantic by definition, but diagnostics never computes or changes it. |
| `diagnosticRunId` | One operational observation instance, preferably random UUID or content-neutral ID. | Never. |
| `sourceCommit` | Code provenance of the running process. | Only where an existing semantic/provenance contract explicitly requires a source commit; not because status exists. |
| `processId` | OS PID. | Never; PID reuse is possible. |
| `hostName`/`hostId` | Machine or worker identity, optional operational correlation. | Never. |
| `processStartWallClock` | Human-readable process identity corroboration. | Never; wall clock is not semantic time. |

### Conceptual schema

```json
{
  "schemaVersion": 1,
  "schemaIdentity": "argentum-run-status@v1",
  "diagnosticRunId": "opaque-operational-id",
  "semanticJobId": "optional-public-safe-job-id",
  "sourceCommit": "40-lowercase-hex-or-unknown",
  "workloadType": "TRAJECTORY_GENERATION",
  "processId": 1234,
  "processStartWallClock": "2026-09-06T12:00:00Z",
  "heartbeatSequence": 17,
  "heartbeatWallClock": "2026-09-06T12:00:15Z",
  "monotonicAgeData": {
    "clockIdentity": "JVM_NANO_TIME_PROCESS_RELATIVE",
    "heartbeatElapsedNanos": 15000000000,
    "stageStartedElapsedNanos": 12000000000,
    "lastUsefulProgressElapsedNanos": 14000000000
  },
  "currentStage": {
    "stageFamilySchemaIdentity": "argentum-diagnostics-trajectory-stages@v1",
    "stageName": "REPLAY_VERIFY"
  },
  "stageSequence": 6,
  "stageStartedWallClock": "2026-09-06T12:00:03Z",
  "progress": {
    "episodeOrdinal": 4,
    "engineProgressCount": null,
    "authoritativeTransitionCount": 8000,
    "semanticDecisionCount": 8000,
    "trajectoryDecisionCount": 8000,
    "replayFramesVerified": 8001,
    "episodesAdmitted": 4,
    "bytesSerialized": 123456,
    "shardsFinalized": 4
  },
  "latestArtifactCounters": [
    {
      "artifactKind": "trajectory-staging-shard",
      "logicalName": "current",
      "bytesWritten": 123456,
      "itemsFinalized": 1
    }
  ],
  "diagnosticMode": "SIDECAR_NORMAL",
  "statusPublication": {
    "successfulPublicationSequence": 17,
    "lastFailureCode": null
  }
}
```

The example is illustrative. Optional progress fields are omitted or `null` when the workload does
not own that metric. `latestArtifactCounters` is sorted by an explicit stable logical key and is
limited to caller-declared safe artifacts; it is not a recursive directory inventory. Normal status
contains no raw `GameState`, observation, domain, action, decision nonce, card identity, hidden-zone
identity, continuation, reward, or exception text. `heartbeatWallClock` is for correlation only.

`statusPublication.successfulPublicationSequence` increments only after a complete status file is
published. A failed write cannot update the file, so the publisher also keeps a bounded in-memory
failure code and exposes it in a later successful status. Failure codes are stable enums such as
`STATUS_DIRECTORY_UNAVAILABLE`, `STATUS_WRITE_FAILED`, `STATUS_ATOMIC_REPLACE_UNAVAILABLE`, and
`STATUS_SERIALIZATION_TOO_LARGE`; no arbitrary exception message is required in the sidecar.

## 14. Atomic publication and crash behavior

### Existing helper audit

`TrajectoryV1Publisher` already creates `.staging`, writes shard temp files in the same staging
directory, calls `FileChannel.force(true)`, validates digest/counts, writes the manifest last, and
atomically moves the complete staging directory to a digest-addressed final directory. Its
`moveAtomically` is a thin `Files.move(..., ATOMIC_MOVE)` helper. `TrajectoryV1Quarantine` uses the
same force/temp/atomic-move discipline. These are the best existing sources.

The current publisher does not clean an abandoned `.staging` tree in `close()`. That is acceptable
for trust because A7 accepts only a final `dataset-<digest>` directory with a valid manifest, but it
means a future operational cleanup must treat staging as recoverable diagnostic residue, never as a
published dataset. The publisher also refuses an existing final dataset destination rather than
overwriting it.

`TrainingCorpusFiles` also stages, but catches an atomic move failure and falls back to
`REPLACE_EXISTING`; it rewrites the whole corpus. That fallback is not acceptable for a trusted
status/publisher primitive.

### Frozen requirement; implementation pending provider characterization

The D0 decision is an invariant, not a selected replacement algorithm:

```text
ATOMIC_PUBLICATION_REQUIREMENT=
    old valid status must survive failed publication
    no torn status may be accepted by a reader
    no non-atomic overwrite fallback

ATOMIC_REPLACEMENT_IMPLEMENTATION=NEEDS_D1_PROVIDER_CHARACTERIZATION
```

D1 must characterize the actual supported Windows/NTFS and Linux filesystem providers before
selecting one of the following implementations: (A) atomic replacement of `run-status.json`, (B)
immutable sequence files plus an atomic pointer/selection record, or (C) provider unsupported.
Until then, the report does not freeze option A as portable fact.

### Candidate status publication flow

```text
serialize bounded status with strict UTF-8 JSON
-> create temp file in the status directory with CREATE_NEW
-> write all bytes through FileChannel
-> force(true)
-> close
-> atomic replace/rename temp -> run-status.json on the same filesystem
-> optionally read/parse/validate the result before declaring publication successful
```

For a status update where `run-status.json` already exists, the implementation must verify the
current Windows/Linux provider's behavior for `ATOMIC_MOVE` plus replacement. Java documents that
an atomic move is provider-dependent, that other options may be ignored, and that replacement of an
existing target is implementation-specific. If atomic replacement is unavailable or ambiguous,
retain the old valid status, report a non-fatal diagnostics failure, and do not fall back to a
non-atomic overwrite. A versioned-file/pointer variant remains a D1 selection candidate, but it
must still preserve the `run-status.json` reader contract.

Expected failure behavior:

| Failure point | Required result |
| --- | --- |
| Process dies during temp write | A partial `.tmp` may remain; the previous final status remains the only accepted status. Supervisor ignores temp files. |
| Process dies during atomic rename | The provider must expose either old or new complete file. If provider cannot guarantee that, the publication is unsupported and the old file is retained. |
| Old status exists | Never truncate it in place. Use a same-directory atomic replacement or leave it untouched on failure. |
| Disk full | Close/delete temp best effort, preserve old status, record `STATUS_WRITE_FAILED`, continue workload. |
| Permissions fail | Preserve old status, record non-fatal diagnostics failure in memory/log, continue workload. |
| Diagnostics directory disappears | Do not touch semantic data. Recreate only through bounded, nonblocking retry policy; otherwise classify status unavailable. |
| Serialization exceeds bounded size | Do not write; record `STATUS_SERIALIZATION_TOO_LARGE`; no raw fallback. |

Status-writing failure is `NON_FATAL_DIAGNOSTICS_FAILURE`. It must not change `GameState`, accepted
transition count, replay, trajectory membership, shard publication, or episode closure.

## 15. Privacy and retention boundary

### Normal sidecar allowed

- schema/version identities and source commit;
- public-safe opaque job/run IDs;
- workload/stage names;
- monotonic counters and ordinals with defined ownership;
- process start wall time, PID, optional host identity;
- declared artifact logical names and byte/item counters;
- stable diagnostic error codes, availability flags, and bounded classification state.

### Normal sidecar forbidden

```text
raw GameState
opponent hand/library/exile identities
face-down identities
raw PlayerObservation unless separately justified and approved
CompleteLegalDomain unless separately justified and approved
raw GameAction / chosen hidden-sensitive internals
decision IDs, action IDs, ability IDs, continuation stacks
reward, policy logits/features, training targets
```

`GameState.timestamp`, state digests, replay fingerprints, and semantic decision IDs are not
substitutes for the operational cursors. A digest may be recorded only as an already-approved
external provenance reference, never as a gameplay payload or a progress identity.

### Privileged JVM diagnostics

Thread dumps, `jcmd` output, JFR, heap histograms, and VM flags are
`DEVELOPER_PRIVILEGED_DIAGNOSTIC` artifacts. They are not promised to be perspective-safe or
dataset-safe. They live under a separate retention/access policy, must never be fed to a model or
trusted dataset, and should be deleted/kept according to explicit operator retention rather than
trajectory admission. A heap dump is especially excluded from V1 because it can contain arbitrary
object graphs and hidden gameplay state.

## 16. External supervisor contract

### Location/language decision

The repository is a Kotlin/JDK 21 multi-project build. `:gym-trainer` is intentionally the owner of
RL/MCTS and training-data opinions, while `:gym` owns game-environment semantics. Placing generic
run diagnostics in either would couple C1 learner/evaluation work to a game or trajectory module.
The maintainable choice is therefore a new pure Kotlin/JDK `run-diagnostics` module with the Gradle
`application` plugin, analogous to the existing `oracle-assay` CLI. It should depend on the JDK and
small serialization support only, not on `rules-engine`, `gym`, `gym-trainer`, Spring, Python, or a
native metrics library.

The external supervisor is a command-line process from that module. It uses JDK APIs first and
small OS adapters second:

- portable baseline: `ProcessHandle` for PID existence, start identity, command metadata, and
  optional total CPU duration;
- Windows: a bounded process-metrics adapter around the existing `Get-Process`/PowerShell idea,
  but with fixed argument construction, culture-independent parsing, and a timeout on the helper;
- Linux/Kaggle: `/proc/<pid>` CPU/RSS/thread data when available, with namespace-aware failure;
- unsupported or permission-denied values are explicit unavailable fields, not zeroes.

The supervisor consumes only:

```text
target PID/process reference
run-status.json
explicitly declared safe artifact paths/logical names
supervisor configuration and thresholds
OS/JVM process metrics
```

It does not need `GameState`, Rules objects, `CardRegistry`, trajectory internals, or a model.

Before monitoring, it must validate PID identity using `ProcessHandle.Info.startInstant()` when
available and compare it with `processStartWallClock`, plus an optional expected command/executable
allowlist. A PID mismatch is `PROCESS_IDENTITY_MISMATCH`, not a new monitored run. It must never
blindly monitor PID 0 or all JVMs.

For distributed Linux workers, the supervisor and target must share a PID namespace or the
supervisor must run inside the target container. JDK `jcmd -l` explicitly does not list JVMs in a
separate Docker process; the OS adapter must discover the target within the relevant namespace.
Windows local development and Linux/Kaggle workers use the same status contract and classifier,
with platform-specific metric/JVM-command availability.

## 17. JVM diagnostic capabilities

### Local bounded capability probe

On 2026-09-06, without attaching to any existing workload JVM:

```text
java = OpenJDK/Temurin 21.0.12 (64-bit Server VM)
jcmd = C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\jcmd.exe
jstack = C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\jstack.exe
jfr = C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot\bin\jfr.exe
jcmd -h = PASS
jstack -h = PASS
jfr --help = PASS
jcmd -l = PASS (JVM discovery; no target command was sent)
```

`jcmd -l` happened to list unrelated concurrent Gradle/test JVMs. This audit did not send a
diagnostic command to them and did not inspect or terminate them.

### Capability matrix

| Tool/call | JDK requirement | Windows | Linux | Permissions/container | Cost and V1 policy |
| --- | --- | --- | --- | --- | --- |
| `jcmd <pid> Thread.print -l` | JDK tooling, target Java process | Available in local JDK; target attach permissions apply. | Available; namespace/ptrace/container visibility applies. | Same machine and effective user/group per JDK docs; target-specific PID identity required. | Medium impact; primary one-shot diagnostic, three bounded samples. |
| `jcmd <pid> GC.heap_info` | JDK tooling | Available if attach succeeds. | Available if attach succeeds. | May fail on permissions or target state. | Medium impact; optional best effort, not a status signal. |
| `jcmd <pid> VM.flags` | JDK tooling | Available if attach succeeds. | Available if attach succeeds. | Same attach caveats. | Low impact; optional best effort. |
| `jstack -l <pid>` | JDK tool but documented experimental/unsupported | May require `dbgeng.dll`/Windows Debugging Tools and target `jvm.dll` on `PATH`. | Historically useful but not the primary V1 dependency. | Same process/access caveats. | Fallback only; absence is `JVM_DIAGNOSTIC_UNAVAILABLE`. |
| JFR via `jdk.jfr.Recording` | JDK 21 module; already used by B1 tests | Available in current JDK. | Available in current JDK. | Programmatic recording is in-process; `jcmd JFR.*` needs attach. | Optional privileged mode. `default` profile is the low-overhead continuous option; `profile` has more data/overhead and is for short captures. |
| `jcmd GC.class_histogram` / heap dump | JDK tooling | Available only with attach and target permissions. | Same. | Can expose arbitrary heap/hidden state; container restrictions. | Excluded from normal V1; heap dump is high impact and not dataset-safe. |
| `ThreadMXBean` deadlock/CPU methods | `java.management` in target JVM | Available for platform threads if supported/enabled. | Same. | In-process only unless JMX/attach adapter is added; virtual threads are not covered by these methods. | Useful for explicit deadlock evidence; enabling metrics may cost work. |
| `ProcessHandle` | Java 9+, supplied by JDK 21 | Available locally. | Available. | OS may deny process info; PID is reusable. | Portable base for liveness/start identity; optional total CPU, no portable RSS guarantee. |

Official references: [`jcmd` JDK 21 command reference](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html)
documents same-machine/effective-user requirements, Docker visibility, `Thread.print`,
`GC.heap_info`, `VM.flags`, and command impact; [`jstack` JDK 21 reference](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jstack.html)
marks the tool experimental/unsupported and records Windows debugger caveats;
[`jdk.jfr` JDK 21 API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/package-summary.html)
documents programmatic `Recording`, event payloads, and the `shouldCommit` cost guard;
[`ThreadMXBean`](https://docs.oracle.com/en/java/javase/21/docs/api/java.management/java/lang/management/ThreadMXBean.html)
documents platform-thread CPU/deadlock support; [`ProcessHandle.Info`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/ProcessHandle.Info.html)
documents optional start/command/CPU attributes.

## 18. Thread-dump strategy

On the first trigger for a stall episode, capture three bounded dumps:

```text
dumpCount=3
dumpInterval=short configurable interval (PROVISIONAL fixture: 2s)
captureTimeout=per-command bounded timeout (PROVISIONAL fixture: 5s)
```

The supervisor captures a status/process sample before dump 0, between dumps, and after dump 2.
Stable stack positions across the samples are evidence for a spin/wait classification; they are not
the final root cause. A failed command produces a missing-evidence record. A timeout destroys only
the supervisor's `jcmd`/`jstack` child process, never the target workload. Cooldown prevents a
persistent stall from producing unbounded bundles. A later trigger may capture again only when the
configured cooldown and bundle cap permit it.

`jcmd` must target the validated PID, not `jcmd 0` or a runner-wide JVM list. The status and process
identity are copied into the bundle before privileged output is considered.

## 19. Diagnostic bundle and bounded retention

```text
diagnostics/
  <diagnostic-run-id>/
    status/
      run-status.json
    history/
      recent-progress.json
    stalls/
      <stall-id>/
        bundle.json
        summary.json
        summary.txt
        status.json
        process-metrics.json
        artifact-sizes.json
        recent-stages.json
        thread-dump-0.txt
        thread-dump-1.txt
        thread-dump-2.txt
        heap-info.txt
        vm-flags.txt
        recording.jfr
```

Required files, including explicit unavailable evidence:

- `bundle.json`: bundle schema, diagnostic run/stall IDs, trigger, classification, configuration,
  file availability, and V1 action (`CONTINUE_OBSERVING`).
- `summary.json`: the machine-readable summary; it must state every missing/failed probe.
- `status.json`: the last parseable sidecar snapshot, or a small `availability=MISSING` record.
- `process-metrics.json`: process/CPU/RSS/heap/GC/thread samples or per-field unavailable reasons.
- `artifact-sizes.json`: explicitly declared safe artifacts or `not-configured`.
- `recent-stages.json`: supervisor-observed bounded stage history or `not-observed`.

Optional/best effort:

- `summary.txt` is a human rendering of `summary.json` and may be absent if formatting fails.
- Three thread dumps, `heap-info.txt`, and `vm-flags.txt` are best effort and privileged.
- `recording.jfr` is optional, bounded, privileged, and never required for a classification.

Retention model:

- In-process recorder: fixed-size scalar ring, no raw state or action retention.
- Supervisor process samples: fixed-size ring, for example `maxHistorySamples`; discarded samples
  are not written as an unbounded JSONL stream.
- Stage transitions: fixed-size ring and one current stage.
- Stall bundles: configured `maxDiagnosticBundles` with per-file/per-bundle byte limits and a
  keep-last-N policy applied only inside the diagnostics directory. If deletion/rotation fails,
  report it and stop creating new bundles; never delete trajectory/dataset artifacts.
- JFR recording: configured `maxsize`/`maxage` when available; no heap dump in normal mode.

The summary never says a diagnostic exists merely because a file was intended. It records
`AVAILABLE`, `MISSING`, `FAILED`, `TIMED_OUT`, or `NOT_CONFIGURED` for each optional evidence source.

## 20. Synthetic failure fixtures

D3 must use injected clocks, fake progress snapshots, fake process samplers, fake command runners,
and bounded temporary directories. It must not reproduce a real multi-hour freeze.

| Fixture | Observable inputs | Expected result | Must not happen |
| --- | --- | --- | --- |
| `HEALTHY_PROGRESS` | Process alive; heartbeat sequence advances; accepted-transition/replay/artifact cursor advances; stage age ordinary or long. | `HEALTHY`; no hard stall bundle. | No termination, no threshold-based semantic change, no fake root cause. |
| `HEARTBEAT_ONLY_NO_USEFUL_PROGRESS` | Process alive; scheduler heartbeat advances; all owned useful counters remain unchanged beyond useful threshold. | `SUSPECTED_STALL`; bounded bundle. | Must not be declared healthy solely from PID/heartbeat; no kill. |
| `CPU_SPIN` | Heartbeat may advance; useful cursor stale; high process CPU; three stable hot stacks. | `CPU_SPIN_SUSPECT`. | Must not be called confirmed deadlock or terminated. |
| `BLOCKED_WAIT` | Heartbeat advances or is stale; useful cursor stale; low CPU; stable WAITING/BLOCKED/I/O stacks. | `BLOCKED_WAIT_SUSPECT`. | Must not be called deadlock without explicit cycle evidence. |
| `PROCESS_EXIT` | ProcessHandle becomes not alive with optional exit code. | `PROCESS_EXITED`; no further attach attempts. | Must not synthesize terminal win/loss/draw or retry. |
| `STATUS_WRITE_FAILURE` | Recorder progresses; injected directory/permission/disk-full write failure; old status exists. | `NON_FATAL_DIAGNOSTICS_FAILURE` internally; supervisor reports stale/unknown sidecar plus process evidence. | Must not overwrite old status, mutate game/data, or stop heartbeat scheduler. |
| `PARTIAL_TEMP_FILE` | Temp write is cut before rename; previous status is valid. | Supervisor reads old complete status; temp ignored/cleaned only by own bounded policy. | Must not parse partial JSON or treat it as a new run. |
| `MISSING_JCMD` | Command runner reports executable missing/permission/timeout. | Classification falls back to sidecar/OS signals; bundle marks JVM evidence unavailable. | Must not wait indefinitely or fabricate a thread dump. |
| `SLOW_BUT_PROGRESSING_STAGE` | Stage age exceeds provisional warning; heartbeat and internal/accepted cursor continue advancing; CPU/heap may be high. | `HEALTHY` plus slow-stage warning, no hard stall. | Must not terminate, interrupt, or call wall duration a failure. |

Fixtures also need contradictory/partial inputs: stale sidecar with live process, missing CPU/RSS,
process exit during dump 1, atomic move failure with an existing target, and a deadlock report with
no useful root-cause owner. Every missing value remains explicitly missing.

## 21. Performance and perturbation model

Diagnostics can create the problem it is meant to explain. Future measurement must separately report:

| Cost | Risk | D1/D2 requirement |
| --- | --- | --- |
| Per-transition JSON/object creation | Allocation and CPU amplification in the hot path. | Disabled path must avoid constructing status/progress objects. Enabled path updates scalars/atomics only at declared boundaries. |
| Atomic write/force/replace | Storage latency and thread blockage. | Coalesce snapshots, use a separate publisher, bound file size, and measure publication latency separately from workload timing. |
| Process metric sampling | PowerShell/subprocess and OS-query overhead. | Run in the external supervisor where possible; every helper has a timeout. |
| Thread dumps | Safepoints/locks/output and target perturbation. | Only after trigger, three bounded samples, cooldown and byte caps. |
| JFR | Continuous recording overhead and disk use. | Optional privileged mode; default profile for continuous low overhead, profile mode only short diagnostics. |
| Artifact scanning | Directory walk/storage pressure and potential privacy exposure. | Only `Files.size` on explicit safe paths; no recursive dataset scan for heartbeat. |
| History | Another unbounded log. | Fixed rings and fixed bundle/file caps. |

The disabled path is a hard design requirement:

```text
if (diagnosticsSink == null) call the original workload directly
```

No `System.nanoTime`, JSON serialization, `AtomicReference` snapshot, or status object should be
constructed for every Rules transition when diagnostics are disabled. D1/D2 must compare identical
source/deck/policy workloads with diagnostics disabled, scalar-only enabled, sidecar publication
enabled, and trigger/JVM-capture enabled as separate arms. #119 performance numbers must not be
relabelled when diagnostics are enabled.

## 22. Relationship to #119 and C1

```text
#119: where do time, memory, and storage go?
#139: is useful work progressing, and where was the run when it stopped?
```

The same process CPU/heap/RSS/JFR samples may serve both, but #119 baseline measurements must state
whether diagnostics were disabled and keep diagnostics overhead out of the KPI. #139 owns status,
progress cursors, stage transitions, and failure bundles; it does not become a performance benchmark.

Before C1-scale work, the same generic core must support:

```text
trajectory actor stuck
reader/materializer stuck
data loader stuck
GPU learner starved or spinning
checkpoint writer stalled
evaluation stalled
```

Each workload adds a versioned stage family and only the counters it can own. No C1 learner or
training dependency is required for the operational module. No data admission, promotion, reward,
observation, replay, or semantic execution rule consumes the sidecar.

### Future interrupt boundary (not implemented)

If a later task authorizes supervisor interruption, it must be administrative/quarantine behavior.
It must not create `GAME_TERMINAL`, `WIN`, `LOSS`, or `DRAW`, and it must not create a trusted
completed trajectory from a partial prefix. Partially written trusted shards remain unpublished.
The exact mapping to an existing `EpisodeClosureV1.Interrupted` reason or a separately versioned
administrative/quarantine reason must be explicitly authorized before implementation; D0 does not
change the current closure enum. Auto-recovery and retry are outside D0.

## 23. D1-D4 implementation plan

All four stages below are plans only. `D1_AUTHORIZED=NO`, `D2_AUTHORIZED=NO`,
`D3_AUTHORIZED=NO`, and `D4_AUTHORIZED=NO`.

The phase boundary is strict:

```text
D1 = generic diagnostics library only
     RunStatusV1, ProgressVectorV1, StageRefV1, clock, recorder, scheduler,
     coalescing publisher, atomic-status primitive, and synthetic generic tests

D1 MUST NOT add a gym adapter, gym-trainer adapter, replay adapter, or workload integration.

D4 = all real workload adapters and their owner-bound progress events.
```

D1 may define the typed event API and counter meanings, but it does not connect those events to
`GameEnvironment`, `GameGymEnv`, `TrajectoryV1*`, `ReplayReconstructor`, `SelfPlayLoop`, or any
other workload. Precise real-producer ownership is verified in D4, not claimed by D1.

### D1 — in-process diagnostic seam

| Item | Plan |
| --- | --- |
| Likely files/modules | Add only the standalone `run-diagnostics` JVM module and its generic sources: `RunStatusV1.kt`, `ProgressVectorV1.kt`, `StageRefV1.kt`, `MonotonicClock.kt`, `DiagnosticsRecorder.kt`, and `AtomicStatusPublisher.kt`, plus `settings.gradle.kts`/module build wiring. No `gym`, `gym-trainer`, `game-server`, Rules, replay, trajectory, learner, or evaluation adapter is part of D1. |
| Conceptual types | `RunStatusV1`, `ProgressVectorV1`, `StageRefV1`, `ArtifactCounterV1`, `DiagnosticsMode`, `StatusPublicationResult`, injectable `MonotonicClock`, and a scalar-only recorder/publisher. |
| Visibility | Status/stage schema and generic recorder API public to the future supervisor/integration modules; low-level atomic writer, ring implementation, and failure mapping internal; no type depends on `GameState` and no workload adapter is added. |
| Tests | Generic schema/version/unknown-field rejection; monotonic counter API; null-versus-zero; process-relative time; disabled-path no-op; bounded ring; no hidden-field JSON scan; atomic temp/replace fault model; provider characterization on Windows/NTFS and the supported Linux filesystem for initial publish, existing-target replacement, interruption, and unsupported atomic move. |
| Failure modes | Non-fatal status write/serialization/atomic-replace failure; publisher queue coalescing; scheduler close; directory disappearance; old-status preservation. |
| Privacy | Reject raw state/action/domain/observation fields structurally; only opaque public-safe IDs; document privileged data as a separate channel. |
| Performance | Measure disabled vs scalar-only vs status publication; no per-transition serialization in disabled mode; publication is off the workload thread. |
| Acceptance | `RunStatusV1` is versioned and bounded; the generic recorder accepts typed events without any real workload caller; atomic publication invariants are tested, but `ATOMIC_REPLACEMENT_IMPLEMENTATION=NEEDS_D1_PROVIDER_CHARACTERIZATION` remains until the provider matrix selects atomic replacement, sequence files/pointer, or unsupported; no Rules/Gym/replay/trajectory schema change; `git diff --check`; focused D1 tests. |

### D2 — external supervisor

| Item | Plan |
| --- | --- |
| Likely files/modules | `run-diagnostics/src/main/kotlin/.../SupervisorMain.kt`, configuration/parser, `ProcessSampler`, Windows/Linux samplers, bounded `JvmCommandRunner`, classifier, and bundle writer. |
| Conceptual types | `SupervisorConfigV1`, `ProcessObservationV1`, `JvmDiagnosticResult`, `StallTriggerV1`, `DiagnosticClassificationV1`, `DiagnosticBundleV1`, `EvidenceAvailability`, and `SupervisorActionV1`. |
| Visibility | CLI is public; samplers/command runners are injectable internal interfaces for D3; bundle schema public to operators. |
| Tests | PID reuse/start mismatch; status parse/schema failure; Windows/Linux unavailable metrics; process exit; target-only jcmd invocation; subprocess timeout; artifact allowlist; bundle byte/count caps; cooldown/max-bundle enforcement. |
| Failure modes | Missing JDK tools, same-user denial, container PID namespace, stale status, OS sampler failure, command timeout, disk full, bundle retention failure. |
| Security/privacy | No PID 0/all-JVM commands; command arguments are fixed; safe artifact paths are allowlisted; JVM text goes to privileged retention only; no network or credentials. |
| Performance | Supervisor sampling is outside the workload; process commands are bounded; JFR/heap options are opt-in and never required. |
| Acceptance | Correctly separates process/heartbeat/useful progress; emits `UNKNOWN` when evidence is missing; never terminates target; writes required summary with missing evidence. |

### D3 — synthetic stall/failure verification

| Item | Plan |
| --- | --- |
| Likely files/modules | Tests/fixtures in `run-diagnostics/src/test`; no real game, locked-deck, trajectory, or training fixture required. |
| Conceptual types | Fake monotonic clock, fake status source, fake process sampler, fake JVM command runner, deterministic fixture timelines, and fault-injecting atomic filesystem adapter. |
| Visibility | Test-only fixtures internal; no production test hook may expose hidden state. |
| Tests | Every matrix row in Section 20, plus contradictory signals, partial temp, old status retention, atomic move unsupported, command timeout, process exit during capture, and long-progressing stage. |
| Failure modes | Fixture itself must fail closed with explicit unavailable evidence; no sleeps longer than the bounded test interval; no multi-hour or 64-episode soak. |
| Security/privacy | Assert sidecar/bundle summary forbidden-key scans; assert privileged files are marked not dataset-safe. |
| Performance | Assert bounded status size/history and no unbounded bundle creation; optionally measure fake publication operation count. |
| Acceptance | All expected classifications and “must not happen” properties pass deterministically; no target process is killed; no semantic result is fabricated. |

### D4 — selected workload integrations

| Item | Plan |
| --- | --- |
| Likely files/modules | All real workload adapters, and only those adapters: `gym` around `GameEnvironment`/`GameGymEnv`/`MultiEnvService`; `gym-trainer` around `SelfPlayLoop`, `TrajectoryV1Writer`, `TrajectoryV1Publisher`, `TrajectoryV1Reader`; `game-server` around `ReplayReconstructor`/`ReplayCheckpointFlusher`; optional test-only B0/B1 wiring. No current source file is authorized now; D1 does not pre-implement any of these integrations. |
| Conceptual types | Workload-specific stage-family enums/validators, `GymProgressAdapterV1`, `TrajectoryPublicationAdapterV1`, `ReplayVerificationAdapterV1`, and `ReaderProgressAdapterV1`. |
| Visibility | Adapters public only where a workload owns them; raw-state callbacks remain internal/privileged; sidecar receives scalar projections. |
| Tests | One bounded smoke per selected workload; strict transition/replay/trajectory/read invariants; A6 admission and A7 yield counts; no new trusted dataset membership from diagnostics. |
| Failure modes | Adapter callback failure is non-fatal diagnostics failure; workload semantic failure remains its existing typed failure/quarantine; status cannot turn a prefix into trusted data. |
| Security/privacy | No raw `TrainerContext`, `GameState`, domains, hidden cards, replay internals, or JFR content cross into normal status. |
| Performance | Match #119 source/policy/deck boundaries; report diagnostics-enabled overhead separately; ensure D4 adapters do not add per-transition work when disabled. |
| Acceptance | Each workload reports only precise owned cursors; all real counter ownership is accepted here rather than in D1; sidecar does not change semantic bytes/digests/replay/trajectory schema; supervisor distinguishes actor/reader/writer/learner stages; no training or large corpus run is implied. |

## 24. Unresolved questions

1. Confirm the final module name (`run-diagnostics` versus another neutral name) and whether its
   status JSON is a public operator contract or an internal versioned contract.
2. Measure atomic replacement of an existing file on the supported Windows and Linux filesystem
   providers. If `ATOMIC_MOVE` replacement is not reliable, decide whether to use immutable sequence
   files plus a pointer or to declare status publication unavailable on that provider.
3. Choose the exact PID identity tolerance between `processStartWallClock` and
   `ProcessHandle.Info.startInstant()` and the command allowlist for Gradle/test/container wrappers.
4. Decide whether host identity is omitted by default for privacy and whether a deployment provides a
   stable host ID separately from hostname.
5. Calibrate heartbeat/useful-progress thresholds and stage warnings from #119/D4 measurements;
   D0 has no evidence for universal values.
6. Decide whether `ENGINE_INTERNAL_PROGRESS` can be reported generically without invasive Rules
   hooks. If not, leave it null for Gym and use only replay/storage owners that have exact cursors.
7. Decide which A7 and A6 private loops receive callbacks and how to preserve caller cancellation
   without making validation partial or changing trusted membership.
8. Define privileged diagnostic retention, access control, encryption, and scrubbing for JFR/thread
   dumps; do not treat `jfr scrub` as a proof of perspective safety.
9. Decide whether `DEADLOCK_DETECTED` requires only JVM-reported cycles or a second stable dump, and
   document the treatment of virtual threads if any future worker uses them.
10. Define the future administrative interruption/quarantine mapping before any supervisor interrupt
    mode is authorized.
11. Verify that all selected production callers honor the documented per-env single-owner rule and
    that asynchronous AI message handling has an explicit serialization/backpressure contract.
12. Determine whether D4 first integrates trajectory generation, A7 reader, replay verification, or
    learner/evaluation workers; no workload order is assumed by D0.

## 25. Final decision table

```text
DIAGNOSTICS_AUTHORITY=OPERATIONAL_DIAGNOSTICS_ONLY; no semantic dependency
STATUS_SCHEMA=RunStatusV1, argentum-run-status@v1, bounded scalar sidecar
PROGRESS_MODEL=optional owned monotonic counters plus current stage and process-relative monotonic timestamps
STAGE_MODEL=versioned per-workload stage families (C/B hybrid), no giant global enum
HEARTBEAT_OWNER=hybrid; dedicated daemon scheduler for heartbeat, workload adapters for useful progress, coalescing publisher for file I/O
MONOTONIC_TIME_SOURCE=System.nanoTime via injectable clock; process-relative elapsed values; wall clock for correlation only
ATOMIC_PUBLICATION_MODEL=REQUIREMENT_ONLY; old valid status survives failure, no torn accepted status, no non-atomic fallback
ATOMIC_REPLACEMENT_IMPLEMENTATION=NEEDS_D1_PROVIDER_CHARACTERIZATION; select atomic replacement, immutable sequence/pointer, or provider unsupported after Windows/Linux tests
EXTERNAL_SUPERVISOR_MODEL=neutral Kotlin/JDK21 application module; status/PID/OS metrics/safe artifacts only; Windows/Linux adapters
JVM_DIAGNOSTIC_MODEL=jcmd primary, bounded target-only commands; jstack fallback; optional privileged JFR; no heap dump in normal mode
STALL_CLASSIFICATION_MODEL=multi-signal pipeline: heartbeat/useful age + CPU/RSS/GC/threads/artifacts + bounded dumps; classification is not root cause
FALSE_POSITIVE_POLICY=SUSPECTED_STALL versus DEADLOCK_DETECTED; slow-but-progressing stages remain non-hard-stall
WATCHDOG_V1_ACTION=CAPTURE_DIAGNOSTICS_AND_CONTINUE_OBSERVING; no terminate, interrupt, retry, or recovery
PRIVILEGED_DIAGNOSTIC_POLICY=DEVELOPER_PRIVILEGED_DIAGNOSTIC; separate retention/access; NOT_DATASET_SAFE and not model input
BOUNDED_RETENTION_MODEL=fixed scalar/history rings, bounded files, cooldown, max bundle count/bytes, own-directory cleanup only
DISABLED_OVERHEAD_STRATEGY=null/near-zero opt-in seam; no per-transition status objects, clocks, serialization, or file I/O when disabled

D1_PLAN_READY=YES
D2_PLAN_READY=YES
D3_PLAN_READY=YES
D4_PLAN_READY=YES
D1_SCOPE=GENERIC_LIBRARY_ONLY; no gym, gym-trainer, replay, or workload adapter
D4_SCOPE=ALL_REAL_WORKLOAD_ADAPTERS_AND_OWNER_BOUND_PROGRESS_EVENTS
```

## 26. D0 completion boundary

```text
RUN_DIAGNOSTICS_D0_IMPLEMENTATION_PASS=YES
PRODUCTION_CODE_CHANGED=NO
TEST_CODE_CHANGED=NO
TRAINING_CODE_CHANGED=NO
TRAJECTORY_SCHEMA_CHANGED=NO
DATASET_SCHEMA_CHANGED=NO
D1_AUTHORIZED=NO
D2_AUTHORIZED=NO
D3_AUTHORIZED=NO
D4_AUTHORIZED=NO
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
LARGE_CORPUS_GENERATION_AUTHORIZED=NO
RUN_DIAGNOSTICS_CODE_REVIEW_PASS=NO
RUN_DIAGNOSTICS_FINAL_ACCEPTANCE_PASS=NO
```
