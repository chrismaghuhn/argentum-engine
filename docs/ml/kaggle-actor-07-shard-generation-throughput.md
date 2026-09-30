# KAGGLE_ACTOR_07 — Shard-Generation Throughput (Output-Preserving)

Date: 2026-09-30
Base: `origin/main` `c75284e1aa` (PR #212 merge)
Branch: `chris/ka07-shard-generation-throughput-20260930`

## Result

Pure performance change. Every output identity the actor publishes is byte-identical before and
after, verified on the accepted A9 actor schedule:

```text
OUTPUT_IDENTITIES_CHANGED = NO  (trajectoryId, replayContentIdentity, all semanticDecisionIds,
                                 live checkpoint fingerprints — episodes 0 and 1, 2,000 steps each)
TRAJECTORY_SCHEMA_CHANGED = NO
REPLAY_SCHEMA_CHANGED = NO
FINGERPRINT_SEMANTICS_CHANGED = NO
RULES_CHANGED = NO
```

| measurement (local, Ryzen 7 5800X, JDK 21, one actor thread) | before | after |
|---|---:|---:|
| episode generation, ordinal 0 (cold JVM) | 116.2 s | 37.6 s |
| episode generation, ordinal 1 (warm JVM) | 131.1 s | 22.5 s |
| generation throughput, 2 episodes | 16.2 decisions/s | 66.5 decisions/s |

Generation is ~4× faster. Local B2 admission + shard write (`TrajectoryV1Writer.appendEpisode`)
is unchanged at ~15 s per 2,000-step episode and is now roughly a third of end-to-end actor time.

For scale: KA05 measured 5.2 decisions/s end-to-end on the 4-vCPU Kaggle instance.
The local numbers above are not a Kaggle projection; a provider re-measurement is still needed.

## Profile (JFR, one 2,000-step A9 episode, before)

| phase | share of CPU samples |
|---|---:|
| state fingerprints (live checkpoints + replay `verifyCheckpoint`) | 26.7 % |
| semantic replay-prefix digest (quadratic) | 15.6 % |
| replay verification, rest | 4.7 % |
| live Gym step/observe | 4.3 % |
| local admission + shard write | 7.7 % |
| A9 test read-back + closure audit (not on the actor path) | 16.6 % |

The rules engine itself (`GameGymEnv.step`, legal-action enumeration) was a small minority.

## Changes

1. **Memoized concrete-descriptor lookup** (`ConcreteDescriptorLookup`, game-server).
   `TransitionSemanticGameStateCanonicalizer` and `ReplayContentCanonicalizerV1` each carried an
   identical depth-first search over the serializer descriptor graph and ran it for every
   polymorphic JSON object of every canonicalized state/replay. Descriptor graphs are immutable,
   so the search is a pure function of (descriptor instance, type name); it is now cached, keyed by
   descriptor identity, and shared by both canonicalizers. This alone was ~28 % of CPU.
2. **Linear semantic replay-prefix identity in the actor harness.**
   `A9TrustedGenerationHarness.buildTrajectory` rebuilt and re-digested the whole
   `SemanticReplayPrefixV1` for every decision (O(n²) in episode length). It now uses
   `SemanticReplayPrefixAccumulatorV1`, which `TrajectoryV1Validator` already uses and which
   `SemanticReplayPrefixAccumulatorTest` pins against the legacy digest. The accumulator's
   `append` and `semanticDecisionIdentity` are now public; its digest state stays internal.
3. **Benchmark** `:gym:kaggleActor07BenchmarkTest` (opt-in, `-Dka07.benchmark=true`,
   `-Dka07.episodes=N`, `-Dka07.outputFile=...`). Generates actor episodes exactly as KA05 does,
   writes them through `TrajectoryV1Writer`, and records per-episode timings plus the output
   identities above as TSV so any later performance change can be diffed for byte parity.
   `A9ActorEpisodeV1` now also exposes the live checkpoint fingerprints for that parity check.

## Verification

```text
:game-server:test --tests 'com.wingedsheep.gameserver.replay.*'   148 tests, 0 failures
:gym-trainer:test                                                 231 tests, 0 failures
:gym:test                                                         861 tests, 0 failures
  (run on the committed tree: the actor source-revision bootstrap rejects a dirty tracked tree,
   which otherwise shows up as 5 misleading KaggleActor04SmokeTest failures)
:gym:kaggleActor07BenchmarkTest (2 episodes, before vs after)     identities IDENTICAL
```

## Findings not addressed here

- **KA06 verifier has the same quadratic prefix.** `TransportedReplayReconstructorV1` (branch
  `chris/kaggle-actor-06-transported-replay-02-production-verifier-20260917`) rebuilds
  `SemanticReplayPrefixV1` per decision. Once this change is on `main` it can use the public
  accumulator the same way.
- **Admission is now the largest single block** (~15 s/episode): independent recomputation of
  observation digests, trajectory id, content digest, and a shard re-read. That independence is
  the trust contract, so it is not skipped; speeding it up means faster canonical JSON writing.
- **`:gym:environmentV1TrustedGenerationTest -Da9.episodeLimit=1` fails on unmodified `main`**:
  `A8 serialized closure audit failed; unclassified=[CastWithKicker, CycleCard]`. The A8 closure
  matrix does not yet classify two action kinds the current engine emits on episode 0. Generation,
  write and read-back all complete before the audit; the actor path (KA05) does not run it.
- **Episodes rarely finish.** KA05 closed 15/16 episodes at the 2,000-step bound; both benchmark
  episodes here did too. This is a property of the deterministic external policy, not throughput.
- **Memory.** Heap after an episode is still 3–6 GB because all 2,001 replay frames are kept as
  JSON trees until the trajectory is built. Unchanged here.
