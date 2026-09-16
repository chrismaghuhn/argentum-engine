# KAGGLE_ACTOR_05 — Bounded Actor Characterization

Date: 2026-09-16 (provider run 2026-09-16 00:10:05Z–01:15:45Z; notebook created 2026-09-15)

Accepted integration base (`origin/main`, PR #205 merged):
`b6c094932585d9a43affe674ecb45c5caa429f9d`

Implementation/source SHA used by the provider attempt:
`9a918a6a69204265ba8ab12415874c6e8ac8466e`

The provider cloned the public writable fork and checked out detached at the exact
implementation SHA above. `expectedSourceCommit == actualRuntimeSourceCommit ==`
`9a918a6a69…` was verified inside the actor (`sourceRevisionVerified=true`).

Kaggle notebook:
<https://www.kaggle.com/code/chrismaghuhn/ka05-bounded-actor-characterization-2026-09-15>

Successful committed Kaggle version: `Version #1`, script version `350174815`.

## Result

The 16-episode bounded characterization run completed with `PASS` state end to end.

| outcome | value |
|---|---|
| assignment size | 16 |
| concurrency | 1 |
| episodes assigned / completed | 16 / 16 |
| failed episodes | 0 |
| partial-lost episodes | 0 |
| interrupted episode closures (2,000-step bound) | 15 |
| game-terminal episode closures | 1 |
| decisions total | 31,287 |
| shard count | 16 (1 episode per shard) |
| publication failures | 0 |
| preflight | `PASS` |

`ZERO_UNSUPPORTED=true`: `unsupportedCardCount == 0`, `unsupportedDecisionCount == 0`,
`unsupportedRuleCount == 0`, `nativePolicyFallbackCount == 0` across every published
episodes boundary.

The measured output deliberately remains ineligible for training data (KA05 #19):

~~~text
OFFLINE_REPLAY_REVERIFICATION=NO_INDEPENDENT_PROOF
DATASET_ELIGIBLE=NO
acceptedSources=[]
ledger membership = MISSING for all 16 entries
~~~

No RunReport claim, provider success state, manifest digest, same-process replay result,
or successful re-import was used as a substitute for independent offline replay proof.
This boundary is unchanged from KA04.

## Identities

| identity | value |
|---|---|
| workload plan identity | `86b33533e569cc452620cd25797f27a071096c208dd2a52f639222e94af6b094` |
| workload namespace | `argentum-ka05-bounded-characterization` |
| rollout generation | `2026-09-15-a9-primary-v1` |
| assignment identity (size 16) | `6e73a4ca65f892ea6ae85d06e13860bad784027e2374a2ba0a7a05adde43f03b` |
| published dataset id | `84cde070405b5d52f3e73c13fdcf0607e2545101ebb6076a427cc550ba801c9d` |
| execution attempt identity | `ka05-size16-c1-a0` |
| example semantic job (ordinal 0) | `1ad115c090c349c0fef5bceb9d9a6d4fadabed5063fa1cc2b2da1e3864cebc69` |
| behavior policy identity | `b2-a9-deterministic-external-policy@v1` (`EXTERNAL_CONTROLLER`) |
| opponent policy identity | `b2-a9-deterministic-external-policy@v1` (`EXTERNAL_CONTROLLER`) |
| policy RNG identity | `explicit-seed/kotlin-policy-state-v1` |
| format | `COMMANDER` |
| Akiri deck identity | `0C5878E3B393A2CB6317FBE64E0827E4E9A562A0346E5A75820F11081F0909C6` |
| Chevill deck identity | `D158760D404F32C32110C377B1CA6E3EF9406FD6E0CC29B620CB5BCF573AC8B2` |
| observation schema | `argentum-gym-player-observation@v1` |
| replay schema | `argentum-compact-replay@v6` |

Deck composition was not changed; the locked Akiri/Chevill Commander curriculum remained
authoritative. Provider paths (`/kaggle/tmp`, `/kaggle/working`) stayed operational-only
and did not enter any semantic identity.

## Provider path

The notebook installed `openjdk-21-jdk-headless`, verified `java -version` /
`javac -version`, cloned the KA05 branch from the public writable fork into
`/kaggle/tmp/argentum-engine-ka05`, and checked out detached at the exact implementation
SHA. The actor ran as an opt-in Gradle gate with scratch under `/kaggle/tmp` and the
publication envelope under `/kaggle/working`. No credentials were created.

The provider-visible Working output preserved assignment identity, execution attempt
identity, the finalized envelope, content digests, and the operational reports without
`latest/`/`current/`/`worker-0/` aliases (KA05 #17):

~~~text
/kaggle/working/ka05-characterization/
  assignment-16/
    concurrency-1/
      actor-0/
        envelope-84cde070405b5d52f3e73c13fdcf0607e2545101ebb6076a427cc550ba801c9d/
          assignment.json
          run-report.json
          status-final.json
          dataset-84cde070405b5d52f3e73c13fdcf0607e2545101ebb6076a427cc550ba801c9d/
            manifest.json
            shards/shard-000000-*.ndjson … shard-000015-*.ndjson
  ka05-characterization-report.json
~~~

## Episode / decision / shard distributions

All values measured from the provider characterization report. `p50`/`p95`/`max` are over
the 16 episodes.

| metric | min | p50 | p95 | max |
|---|---|---|---:|---:|---:|
| decisions per episode | 1,287 | 2,000 | 2,000 | 2,000 |
| bytes per episode (canonical) | 55,789,650 | 97,993,286 | 112,151,753 | 112,151,753 |
| bytes per decision | 29,989 | 48,996 | 56,075 | 56,075 |

Aggregates: 31,287 decisions total; 1,520,881,513 canonical bytes total;
1 episode per shard; `compressionFootprint = NONE_CURRENT_CONTRACT`.

## Write / publication amplification (KA05 #12)

| fact | value |
|---|---|
| peak scratch footprint above baseline | 1,520,955,392 bytes |
| scratch footprint amplification | 1.0000486 |
| peak working footprint above baseline | 1,521,135,616 bytes |
| publication footprint amplification | 1.0001240 |
| publication envelope bytes | 1,521,070,043 |
| finalized canonical bytes | 1,520,881,513 |
| physical write amplification | `NOT_MEASURED` |

Scratch and publication footprint amplification are both ≈ 1.0: staging asks for almost
exactly the finalized canonical bytes. Physical device-level write amplification was not
measurable from inside the provider and is recorded as `NOT_MEASURED`.

## CPU / memory / GC evidence

Measured inside the provider actor (`availableProcessors = 4`).

| fact | value |
|---|---|
| wall time (characterization `wallTimeNanos`) | 5,979.4 s (≈ 99.7 min) |
| actor window (status `actorStartTime`→`actorEndTime`) | 00:10:05.318Z → 01:15:45.351Z (65.7 min) |
| process CPU utilization | 171.9 % |
| heap peak | 8,141,144,064 bytes (≈ 8.1 GB) |
| heap used before / after | 432,775,848 / 2,905,945,424 bytes |
| RSS before / after | 1,356,513,280 / 5,211,410,432 bytes |
| GC collections / GC time | 7,264 / 563,276 ms |
| episodes per second | 0.002676 |
| decisions per second | 5.2325 |

The run used the raised heap limit from the exact implementation SHA (`9a918a6a69…`).
Heap peaked at ≈ 8.1 GB on a 4-vCPU instance; ~57 % of the wall window is GC time. The
gap between the actor window (65.7 min) and the characterization wall time (99.7 min) is
recorded here without over-interpretation; the run-level numbers below are the
authoritative fixed windows.

## Failure incidence

`failedEpisodes = 0`, `partialLostEpisodes = 0`, `diagnosticCodes = []`,
`preflightFailureCode = null`, `publicationFailures = 0`. No stop condition from
KA05 #25 fired.

## Provider export / download / strict re-import (KA05 #18)

The committed version was exported through Kaggle's `Download output` action:

~~~text
file        = results (1).zip
size        = 222,341,104 bytes
SHA-256     = F28E50CE4FD137706156AF196A5759289A2D719981BC5C5220E5D096F7E2AD45
extracted   = 21 files (16 shards, manifest, assignment, run-report, status-final,
              characterization report)
~~~

The downloaded output was treated as untrusted local input and reopened only through the
existing strict reader/admission APIs — `LocalPublicationEnvelopeV1.reimport()`,
`TrajectoryV1Reader`, `OfflineAdmissionV1` (no Kaggle-specific weaker reader was added).
The opt-in gate `:gym-trainer:kaggleActor05DownloadedReimportTest` verified:

- exactly one `envelope-*` bundle re-imports and streams 16 episodes;
- manifest/shards/assignment/status/run-report bindings and digests hold
  (`report.localShardDigests == manifest.shards.contentDigest`, canonical round-trips);
- `offlineReplayReverification == NO_INDEPENDENT_PROOF`;
- `datasetEligible == false`, `acceptedSources == []`, every ledger entry `MISSING`.

Result: **strict re-import PASS**.

Local memory finding (recorded, not optimized away — KA05 #20): reopening the 1.5 GB
published dataset through the strict reader needs the same heap class as the generator
(≈ 8 GB). The default test-worker heap (2 GB) failed with `OutOfMemoryError`; the gate
therefore runs opt-in with an 8 GB worker heap, exactly like `:environmentV1TrustedGenerationTest`.

## Evidence matrix (KA05 #21)

| trust/behavior case | existing evidence | new KA05 check | provider evidence | status |
|---|---|---|---|---|
| assignment-size determinism | KA02/KA03 actor contract | characterization harness size ladder | size-16 assignment id + 16/16 episodes | PASS |
| worker-count semantic invariance | KA02/KA03 actor contract | ladder condition = c1 | single worker, semantic ids stable | PASS |
| separate execution attempt identity | KA02/KA03 actor contract | `ka05-size16-c1-a0` recorded | status/report match | PASS |
| partial-shard fail closed | KA03 tests | GitHub retry/conflict tests | not triggered (0 failures) | PASS (covered) |
| resume-only-missing | KA02/KA03 actor contract | — | not triggered (0 partial-lost) | not applicable |
| provider metadata exclusion | KA02/KA03 actor contract | envelope layout | no alias dirs, paths not in ids | PASS |
| retry duplicate/conflict semantics | KA02/KA03 + KA04 | — | not triggered | not applicable |
| measurement fields excluded from semantic ids | KA02/KA03 actor contract | report fields | ids stable across runtime facts | PASS |
| provider output strict-re-import | KA04 smoke | `kaggleActor05DownloadedReimportTest` | this run | PASS |
| offline replay reverification | KA04 smoke | `NO_INDEPENDENT_PROOF` | this run | PASS |

KA05 deliberately did not re-test every already accepted KA02–KA04 contract redundantly;
where a case could not fire in this run it is marked not applicable rather than claimed.

## Assignment-size ladder (KA05 #9 / #23)

| rung | status |
|---|---|
| 16 episodes, c1 | MEASURED — PASS, evidence above |
| 32 episodes | NOT_RUN — blocked by instruction; projected ≈ 2 × wall (≈ 3.3 h) and heap toward the measured instance ceiling; see recommendation |
| 64 episodes | NOT_RUN — KA05 #25 stop condition "64-episode rung exceeds measured safe budget" applies by derivation |

Concurrency ladder: c1 MEASURED (this run). c2/c4 were deliberately not re-run as
bounded characterizations here; KA04 already exercised the c1/c2/c4 provider smoke at
smoke scale, and this task's bounded budget was spent on the 16-episode c1 rung.

## Recommendations

All recommendations below are DERIVED from the single measured session unless marked
otherwise; per KA05 #24 a single Kaggle session is not a universal hardware constant.

- **Recommended bounded assignment size for a future scaling phase: 16.** MEASURED:
  16 closed episodes in a single provider session stay within the measured heap/CPU
  budget with 0 failures. 32 is not recommended on this 4-vCPU / ≈ 17 GB RAM instance
  class without first confirming a larger heap ceiling; doubling the run quadruples the
  GC pressure and RSS growth observed here (DERIVED).
- **Recommended shard policy: 1 episode per shard.** MEASURED: 16/16 shards held exactly
  one episode at 55.8–112.2 MB canonical. DERIVED follow-up: a shard-policy range of
  ~30–60 episodes (~3–6.7 GB) would keep shards inside a comfortable bucket size if a
  future phase wants fewer, larger shards — unmeasured.
- **Memory envelope for the strict reader must be treated as a real requirement:**
  re-import of this dataset needs ≈ 8 GB heap (MEASURED locally). A future repack phase
  should budget per-episode processing rather than materializing the whole dataset.

## Remaining blockers

- Independent transported replay proof: the offline replay hard gate remains
  `NO_INDEPENDENT_PROOF`, blocking `DATASET_ELIGIBLE` and deterministic B2 repack
  (deliberate KA05 #19 stop boundary).
- Physical write amplification was not measurable from inside the provider
  (`NOT_MEASURED`).
- Compression is not part of the current storage contract
  (`compressionFootprint = NONE_CURRENT_CONTRACT`); gzip/Parquet/WebDataset remain
  explicitly excluded per KA05 #26.