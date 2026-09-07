# Run diagnostics D4 — real workload integration

Task: `RUN_DIAGNOSTICS_D4_REAL_WORKLOAD_INTEGRATION_01`

This slice adds only one-way, operational callbacks from four existing workload owners into the
accepted `run-diagnostics` API. Diagnostics are observational; they are not a Rules, Gym, replay,
trajectory, dataset, policy, or training input.

## Provenance and scope

```text
BASE=49b4f995324f00c378efb1183f07853aa9378ed5
HEAD=the single implementation commit containing this report
PARENT=49b4f995324f00c378efb1183f07853aa9378ed5
REMOTE_HEAD=the pushed implementation commit containing this report
UPSTREAM_SHA=5021faf88093a93091e4de7914fbe0f411499d58

D1_FINAL_ACCEPTANCE_PASS=YES
D2_FINAL_ACCEPTANCE_PASS=YES
D3_FINAL_ACCEPTANCE_PASS=YES
REAL_WORKLOAD_INTEGRATION=BOUNDED_ONLY
LINUX_RUNTIME_CHARACTERIZATION=NOT_REQUIRED_FOR_D4
REAL_JCMD_ATTACH=NOT_REQUIRED
```

The final exact `HEAD` and `REMOTE_HEAD` are reported with the completion record because a commit
cannot contain its own content-dependent SHA. The starting `origin/main` and fetched upstream SHA
above are exact.

Changed production seams are limited to `gym`, `gym-trainer`, and `game-server`, plus their module
dependency declarations. No `run-diagnostics` production file was changed. No workload adapter was
added to `run-diagnostics` itself.

## Selected workload owners

| Workload | Real owner seam | Stage family | Progress reported | Deliberately not reported |
| --- | --- | --- | --- | --- |
| Gym | `GameGymEnv.commitStrict()` after `consumeCommittedTransition()` succeeds | `argentum-gym-diagnostics-stage@v1` | `authoritativeTransitionCount += 1` | semantic decisions, engine-internal count |
| Multi-env Gym | `MultiEnvService.create()` creates one optional recorder per `EnvId`; `dispose()` closes it | same Gym family | per-environment authoritative transitions | shared recorder races, deckbuild progress |
| Trajectory writer | `TrajectoryV1Writer` after `TrajectoryV1Publisher` returns `Admitted` | `argentum-trajectory-diagnostics-stage@v1` | admitted decision rows, admitted episodes | attempted/quarantined rows, byte-size inference |
| Dataset publication | `TrajectoryV1Writer.finalizeDataset()` after publisher returns, including final atomic directory move | same trajectory family | `shardsFinalized` for durably published dataset shards | temp-file intent, failed moves |
| Replay verification | `GymReplayFrameSource` after `ReplayReconstructor.replayForward()` has applied an action and the public boundary is built | `argentum-replay-diagnostics-stage@v1` | `replayFramesVerified += 1` for accepted post-action frames | raw replay input/state/action identity |
| A7 reader | `ValidatedTrajectoryDatasetV1.streamEpisodes()` after validation and successful yield | `argentum-trajectory-reader-diagnostics-stage@v1` | validated yielded decision rows | admission, bytes requested, object construction |

The replay adapter is deliberately at the existing `GymReplayFrameSource` composition boundary. It
uses the existing `ReplayReconstructor.replayForward()` fold and never makes `run-diagnostics` import
the replay implementation.

## Owner matrix and null policy

All adapters receive a nullable `DiagnosticsRecorder`. The following fields remain null unless the
listed owner is active and has an exact boundary:

```text
engineProgressCount       = null
semanticDecisionCount     = null
episodeOrdinal            = null
bytesSerialized           = null
artifactCounters          = unchanged/not inferred
```

`trajectoryDecisionCount` is local to the recorder owner: the writer reports accepted A6 decision
rows, while an A7 reader reports validated decision rows it has actually yielded. A recorder is not
shared between those workloads. `shardsFinalized` means the count of shard units in a dataset whose
final dataset directory was durably published; it is not a byte-size gauge.

No counter is derived from a timestamp, ID, digest, unordered collection, observation, action, or
GameState. A failed action, stale action, unsupported path, rejected admission, failed shard move,
or failed final publication does not advance the corresponding useful counter.

## Stage visibility

The stage values are small, workload-specific, versioned `StageRefV1` values rather than one global
enum:

```text
Gym:        INITIALIZING -> RESETTING -> RUNNING
Trajectory: INITIALIZING -> ADMITTING -> FINALIZING -> PUBLISHED
Replay:     INITIALIZING -> VERIFYING -> COMPLETE
Reader:     INITIALIZING -> PREFLIGHT -> OPEN -> STREAMING -> COMPLETE
```

These are observable owner boundaries only. The integration does not claim finer boundaries such as
domain construction, observation construction, policy selection, or serialization internals.

## Disabled and failure behavior

The default workload path supplies `null`:

```text
diagnosticsRecorder == null
    -> no recorder call
    -> no diagnostics clock read
    -> no status DTO construction
    -> no serialization
    -> no file I/O
```

The callbacks are inline nullable guards. The external status publisher remains a caller-owned
composition-root service, so status I/O is not performed on a workload transition thread. Gym
`MultiEnvService` can create one recorder per environment; forks explicitly receive no recorder.

Every callback is best effort. An exception from a recorder cannot change a Gym result, A5/A6
admission, publisher result, replay fidelity, or A7 stream. Recorder shutdown on Gym environment
disposal is also non-fatal.

## Semantic and privacy boundary

The tests compare deterministic Gym output with diagnostics disabled and enabled, and compare
published trajectory manifests/streams and replay verification results across the same boundary.
The existing trusted trajectory schema, replay schema, observation schema, domain schema, episode
closure, and admission decisions are unchanged.

For the trajectory slice, the same real A5 admission result, A6 publisher result, manifest bytes,
and A7 streamed trajectory are observed with and without diagnostics. Therefore diagnostics do not
change trusted membership or the A5/A6/A7 outcome.

Normal status is produced by the accepted `RunStatusV1` contract. The serialized status contains
only schema/version, public operational identity, workload stage, scalar progress, and process
metadata. The test scans real Gym status for:

```text
GameState, PlayerObservation, CompleteLegalDomain, GameAction, chosenAction,
hiddenHand, libraryContents, reward, cardName
```

No adapter receives or stores these values. Privileged JVM diagnostics remain a D2 concern and are
not learner/model input.

## Bounded smoke and fixture evidence

The new tests use fixed seeds, tiny decks or the existing one-episode trajectory fixture, temporary
directories, and fake monotonic clocks. They do not sleep for thresholds, run a soak, generate a
corpus, or start training.

Covered real bounded paths:

| Evidence | Expected and observed |
| --- | --- |
| Gym disabled/enabled | same `GameState`, step count, closure, and accepted transition count |
| Gym accepted/rejected | accepted strict transition increments once; stale action does not |
| Gym fork | fork transition does not change parent recorder |
| Gym sidecar | real `CoalescingStatusPublisher` writes; `StatusSidecarReader` reads the current status |
| Trajectory admission | quarantined invalid unit leaves admission counters null; admitted unit increments exact rows/episode |
| Trajectory publication | final atomic publication increments durable shard progress; failed final move does not |
| Trajectory equivalence | enabled/disabled manifests and streamed trajectories are equal |
| Replay verification | real replay fold returns the unchanged verification result; accepted post-action frames count |
| Reader | real preflight/stream yields the same trajectory; validated rows count only after yield |
| Diagnostics failures | throwing recorder does not alter Gym, writer, or reader results |
| Privacy | real status serialization has no forbidden gameplay/model fields |
| Stage visibility | all four versioned stage families are observed through their real boundaries |

The existing replay test uses the repository's bounded deterministic recorded replay fixture. It is
not a multi-hour or corpus run.

## Bounded overhead characterization

One local Windows measurement in the Gym test covers reset plus three strict transitions. It is
reported for architectural comparison only, not as a universal benchmark:

```text
disabled=11,657,800 ns  (11.66 ms)
scalar=18,036,300 ns    (18.04 ms)
sidecar=33,970,900 ns   (33.97 ms)
iterations=3
scope=bounded-local-characterization
```

The sidecar path uses the real coalescing publisher and waits for bounded idle completion. No
unrelated performance optimization is included. Historical #119/B1 numbers are not compared.

## Tests and limitations

Focused integration tests added or extended:

```text
RunDiagnosticsGymIntegrationTest
RunDiagnosticsTrajectoryIntegrationTest
RunDiagnosticsReaderIntegrationTest
ReplayTrajectoryVerificationTest (D4 replay case)
```

Verification executed:

```text
:run-diagnostics:test :run-diagnostics:check :run-diagnostics:build --rerun-tasks = PASS
:gym:test = PASS
:gym-trainer:test = PASS (one existing opt-in measurement test SKIPPED)
:game-server:test = PASS (existing environment-dependent tests remain SKIPPED)
:run-diagnostics:run --args="--help" = PASS
git diff --check = PASS
```

Required module gates for this slice are `:run-diagnostics`, `:gym`, `:gym-trainer`, and
`:game-server`; no `gym-server` production file changed, so its suite is not an affected-module
gate. Real `jcmd` attachment is not required for D4. Linux runtime characterization is not part of
this bounded integration task.

Future C1 adapters remain out of scope: self-play, MCTS, teachers, learners, evaluation, GPU
training, checkpoints, and search. No automatic kill, interrupt, retry, recovery, restart, semantic
timeout result, or dataset quarantine behavior was added.

## Contract result

```text
REAL_GYM_WORKLOAD_INTEGRATED=YES
REAL_TRAJECTORY_PIPELINE_INTEGRATED=YES
REAL_REPLAY_VERIFICATION_INTEGRATED=YES
REAL_READER_PIPELINE_INTEGRATED=YES

OWNED_PROGRESS_ONLY=YES
UNOWNED_PROGRESS_LEFT_NULL=YES
DIAGNOSTICS_FAILURE_NON_FATAL=YES
DIAGNOSTICS_DISABLED_PATH_DIRECT=YES
NORMAL_STATUS_PRIVACY=PASS

TRAJECTORY_V1_CHANGED=NO
PLAYER_OBSERVATION_V1_CHANGED=NO
COMPLETE_LEGAL_DOMAIN_CHANGED=NO
COMPACT_REPLAY_CHANGED=NO
EPISODE_CLOSURE_CHANGED=NO
DATASET_MEMBERSHIP_CHANGED_BY_DIAGNOSTICS=NO
LOCKED_DECKS_CHANGED=NO

AUTO_KILL=NO
AUTO_INTERRUPT=NO
AUTO_RETRY=NO
AUTO_RECOVERY=NO
SELF_PLAY_INTEGRATED=NO
LEARNER_INTEGRATED=NO
TRAINING_STARTED=NO
LARGE_CORPUS_GENERATED=NO

D4_AUTHORIZED=YES
D4_IMPLEMENTATION_PASS=YES
D4_CODE_REVIEW_PASS=NO
D4_HOSTED_CI_PASS=NO
D4_FINAL_ACCEPTANCE_PASS=NO
```
