# Post-B2 bounded data characterization

Date: 2026-09-07 (task authorization and dataset execution began 2026-09-06)
Task: POST_B2_64_EPISODE_CHARACTERIZATION_01
Tracker: #119 — Post-B2 bounded characterization, dataset scaling, and C0 measurement contract

## Executive result

The accepted Trajectory V1 dataset is physically usable for small smoke work and approximately 100k decisions when dedicated storage is provisioned. It is heavy at 1M decisions and not a routine single-host canonical working artifact at multi-million scale.

The measured canonical NDJSON payload is observation/domain dominated:

- PlayerObservationV1: 62.477754%
- CompleteLegalDomain: 33.438763%
- Combined: 95.916517%

Gzip level 6 reduced the measured distribution archive to 558,150,197 bytes, a 10.986x canonical-to-compressed ratio. That is distribution-layer evidence only. It does not change canonical Trajectory V1 authority, schema, replay linkage, or reader semantics.

The gate classification is:

~~~text
COMPRESSION_SUFFICIENT_FOR_NEAR_TERM=NO (as the only storage decision)
STRUCTURAL_STORAGE_OPTIMIZATION_LIKELY_NEEDED=YES
DOMINANT_STORAGE_COST=MIXED (PlayerObservationV1-dominant, with CompleteLegalDomain also material)
LARGE_CORPUS_RECOMMENDATION=Do not start a multi-million-decision corpus yet; investigate physical observation/domain reduction and derived distribution views under a separate authorization.
~~~

## Provenance and isolation

~~~text
BASE=2a030fa8aa6eb86a1b468f1c7a9ec7f5a10cda89
HEAD=2a030fa8aa6eb86a1b468f1c7a9ec7f5a10cda89 (source head used for all measurements)
ORIGIN_MAIN_AT_START=2a030fa8aa6eb86a1b468f1c7a9ec7f5a10cda89
ORIGIN_MAIN_AT_REPORT=97856d97e31c0bea9301388f931bfcfcd47df090
BRANCH=chris/post-b2-bounded-characterization-20260906
WORKTREE=C:\Users\chris\.config\superpowers\worktrees\argentum-engine\post-b2-bounded-characterization-20260906
ORIGIN=https://github.com/chrismaghuhn/argentum-engine.git
UPSTREAM=https://github.com/wingedsheep/argentum-engine.git
~~~

The branch was created from the authorized origin/main SHA. origin/main advanced by unrelated merged D0/D1 work during this run; this branch was not rebased and the source head above remains the exact measurement base.

The measurement source/build provenance above is historical evidence for the accepted dataset and host run. The current integration commit is separate branch history and does not relabel or reproduce any measurement on the newer main.

## Dataset identity and trust

The exact finalized A9 dataset was found locally and selected by manifest identity, not by directory name:

~~~text
DATASET_ID=69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03
MANIFEST_CONTENT_DIGEST=de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2
DATASET_ROOT=C:\Users\chris\AppData\Local\Temp\argentum-b2-a9-pr135-7698370951593280919\dataset-69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03
EPISODES=64
DECISIONS=125471
GAME_TERMINAL=5
INTERRUPTED=59
FAILED=0
SHARDS=64
MAX_EPISODES_PER_SHARD=1
~~~

The exact dataset was not regenerated. Its .staging directory was not used. The accepted trusted baseline remains:

~~~text
DATA_TRUSTED=YES
~~~

The separate fresh 8-episode generation output was temporary and is not a new authoritative dataset.

## Runtime and hardware provenance

~~~text
OS=Microsoft Windows 11 Home 64-bit, build 26200
MACHINE=Megaport Morpheus
PHYSICAL_MEMORY=34278862848 bytes (32 GiB reported)
CPU=AMD Ryzen 7 5800X 8-Core Processor
CPU_CORES=8
CPU_LOGICAL_PROCESSORS=16
JDK=Eclipse Temurin OpenJDK 21.0.12+8-LTS
GRADLE=9.6.1
KOTLIN=2.3.21
PYTHON=C:\Python313\python.exe, Python 3.13.15
BUILD_MODE=native gradlew.bat fallback; repository just wrapper unavailable because WSL /bin/bash is missing
~~~

The required just recipes were attempted first and all three were blocked before Gradle:

~~~text
just test-gym         BLOCKED: WSL ... execvpe(/bin/bash) failed
just test-gym-trainer BLOCKED: WSL ... execvpe(/bin/bash) failed
just test-server      BLOCKED: WSL ... execvpe(/bin/bash) failed
~~~

The clean native fallback baseline for the combined :gym:test :gym-trainer:test :game-server:test run passed with exit code 0.

### Measurement commands

The original global compression scan was run with:

~~~powershell
C:\Python313\python.exe scripts/post_b2_bounded_characterization.py <dataset-root> --gzip-levels 1,6 --output <temporary-json>
~~~

This amendment reran the exact dataset with gzip level 9 and the family-compression pass:

~~~powershell
C:\Python313\python.exe scripts/post_b2_bounded_characterization.py <dataset-root> --gzip-levels 9 --output <temporary-json>
~~~

The strict reader probe was run with the exact dataset and -DeclCollect=true, which selects the repository's 4 GB test heap:

~~~powershell
$env:POST_B2_DATASET=<dataset-root>
.\gradlew.bat :gym-trainer:test --tests "*PostB2TrajectoryReaderMeasurementTest" --console=plain -DeclCollect=true
~~~

The bounded trusted path was run once with no more than eight newly generated episodes:

~~~powershell
.\gradlew.bat '-Da9.episodeLimit=8' '-Dbenchmark=true' --console=plain :gym:environmentV1TrustedGenerationTest
~~~

The dedicated generation task uses its existing 8 GB test heap. No 64-episode A9 rerun was performed.

## Canonical storage size

~~~text
TOTAL_CANONICAL_DATASET_BYTES=6131943637
CANONICAL_NDJSON_BYTES=6131894967
MANIFEST_BYTES=48670
CANONICAL_DATASET_SIZE=5.710818 GiB (6.131944 GB decimal)
CANONICAL_NDJSON_SIZE=5.710772 GiB (6.131895 GB decimal)
~~~

Canonical dataset bytes include manifest.json plus all manifest-owned shard files. Canonical NDJSON bytes include only the shard files. The manifest is excluded from the component table below so its small fixed cost does not distort decision-level shares.

Percentiles use nearest-rank selection. Means are arithmetic means. Every statistic reports the exact population used.

### BYTES_PER_EPISODE

Population: N=64 finalized episodes; one episode per shard.

| Statistic | Bytes |
|---|---:|
| min | 44,320,187 |
| p50 | 97,822,707 |
| p95 | 111,428,010 |
| p99 | 118,597,540 |
| p99.9 | 118,597,540 |
| max | 118,597,540 |
| mean | 95,810,858.859 |

The episode sample is limited (N=64); the nearest-rank p99 is the maximum and should not be read as a precise tail estimate.

### BYTES_PER_DECISION

Primary unit: the full canonical decision frame line, including frame envelope and LF delimiter. Population: N=125,471 decision frames.

| Statistic | Full frame line bytes |
|---|---:|
| min | 9,102 |
| p50 | 49,719 |
| p95 | 80,428 |
| p99 | 90,219 |
| p99.9 | 106,362 |
| max | 136,265 |
| mean | 48,869.128 |

Derived lower-level unit: the nested serialized DecisionRecordV1 JSON value only, excluding the outer frame envelope and LF.

| Statistic | DecisionRecordV1 value bytes |
|---|---:|
| min | 8,783 |
| p50 | 49,400 |
| p95 | 80,109 |
| p99 | 89,900 |
| p99.9 | 106,043 |
| max | 135,946 |
| mean | 48,550.128 |

The observed decision-frame bytes sum to 6,131,658,325 bytes. The remaining 236,642 shard bytes are episode-start/end framing and metadata.

## Component byte breakdown

This is a deterministic analytical decomposition of the actual canonical frame bytes; it does not alter the canonical serializer.

- JSON member key/value spans are assigned to the named field component.
- CandidateDomainDigest / decision identity includes both candidateDomainDigest and semanticDecisionId.
- Decision/frame metadata receives frame envelopes, scalar decision-record fields, JSON object punctuation/wrappers, and every LF delimiter.
- Episode-metadata wrappers and non-replay/non-closure metadata are assigned to Episode/provenance.
- Replay linkage is the serialized compactReplayLink member.
- Closure metadata includes episode closure members and episode-end identity/count/digest fields.
- The table covers the 6,131,894,967 shard bytes exactly; the 48,670-byte manifest is separate.

| Component | Bytes | Share of canonical NDJSON |
|---|---:|---:|
| PlayerObservationV1 | 3,831,070,235 | 62.477754% |
| CompleteLegalDomain | 2,050,429,846 | 33.438763% |
| Chosen semantic input/response | 139,966,828 | 2.282603% |
| Decision/frame metadata | 67,561,794 | 1.101809% |
| CandidateDomainDigest / decision identity | 42,660,140 | 0.695709% |
| Episode/provenance | 151,842 | 0.002476% |
| Replay linkage | 32,064 | 0.000523% |
| Closure metadata | 22,218 | 0.000362% |
| Other | 0 | 0.000000% |
| TOTAL | 6,131,894,967 | 100.000000% |

The observed result supports MIXED, specifically observation-dominant: observations are the largest component, but complete legal domains are also one-third of all canonical bytes. Repeated static episode/replay/closure metadata and framing are not dominant.

## Decision-family storage

The tables below use the actual 125,471 naturally occurring finalized decision frames. LOW_N means N<1,000; LIMITED_N means 1,000<=N<10,000; SUPPORTED means N>=10,000 for this characterization only. Percentile values are nearest-rank frame-line bytes.

### Semantic decision kind

| Decision kind | N | Mean bytes | p50 | p95 | Max | Sample |
|---|---:|---:|---:|---:|---:|---|
| PRIORITY | 122,832 | 49,132.798 | 50,232 | 80,593 | 136,265 | SUPPORTED |
| SELECT_CARDS | 2,336 | 37,859.512 | 38,018 | 56,388 | 67,813 | LIMITED_N |
| CHOOSE_COLOR | 129 | 26,339.023 | 24,260 | 41,552 | 44,774 | LOW_N |
| YES_NO | 106 | 29,730.887 | 26,139 | 52,930 | 57,257 | LOW_N |
| CHOOSE_TARGETS | 68 | 23,374.647 | 16,690 | 49,251 | 51,521 | LOW_N |
| TOTAL | 125,471 | — | — | — | — | — |

### Chosen semantic action/response surface

This table identifies the chosen semantic input/response in each frame. It is not a count of every candidate in the complete legal domain.

| Chosen surface | N | Mean bytes | p50 | p95 | Max | Sample |
|---|---:|---:|---:|---:|---:|---|
| PassPriority | 76,523 | 45,517.461 | 45,203 | 77,424 | 96,949 | SUPPORTED |
| ActivateAbility | 43,017 | 56,093.822 | 57,637 | 85,048 | 136,265 | SUPPORTED |
| PlayLand | 2,132 | 42,002.746 | 40,561 | 77,610 | 95,173 | LIMITED_N |
| CardsSelectedResponse | 2,336 | 37,859.512 | 38,018 | 56,388 | 67,813 | LIMITED_N |
| CastSpell | 731 | 46,419.949 | 43,580 | 82,393 | 98,729 | LOW_N |
| DeclareAttackers | 388 | 34,759.013 | 34,144 | 52,260 | 62,124 | LOW_N |
| ColorChosenResponse | 129 | 26,339.023 | 24,260 | 41,552 | 44,774 | LOW_N |
| YesNoResponse | 106 | 29,730.887 | 26,139 | 52,930 | 57,257 | LOW_N |
| TargetsResponse | 68 | 23,374.647 | 16,690 | 49,251 | 51,521 | LOW_N |
| CycleCard | 41 | 48,532.829 | 39,209 | 95,588 | 98,193 | LOW_N |
| TOTAL | 125,471 | — | — | — | — | — |

The following chosen surfaces were not observed in this 125,471-frame sample:

~~~text
CastSpellMode=N=0 (not observed as chosen; not an unreachable-legality claim)
CastWithKicker=N=0 (not observed as chosen; not an unreachable-legality claim)
CastWithFlashback=N=0 (not observed as chosen; not an unreachable-legality claim)
~~~

The bounded 8-episode generation run did observe some of those candidate/action kinds, but it is not used as natural-frequency evidence for the 64-episode accepted dataset.

### Overlapping storage dimensions

These categories overlap; their N values must not be summed.

| Dimension | N | Mean bytes | p50 | p95 | Max | Sample |
|---|---:|---:|---:|---:|---:|---|
| structured pending decision | 2,639 | 36,596.629 | 36,598 | 56,030 | 67,813 | LIMITED_N |
| payment-bearing actions | 515 | 76,636.786 | 77,587 | 116,460 | 125,160 | LOW_N |
| target-bearing actions | 2,023 | 39,735.498 | 35,538 | 83,000 | 102,775 | LIMITED_N |
| repeatCount-bearing actions | 43,017 | 56,093.822 | 57,637 | 85,048 | 136,265 | SUPPORTED |
| card selections | 2,336 | 37,859.512 | 38,018 | 56,388 | 67,813 | LIMITED_N |
| color choices | 129 | 26,339.023 | 24,260 | 41,552 | 44,774 | LOW_N |
| yes/no | 106 | 29,730.887 | 26,139 | 52,930 | 57,257 | LOW_N |
| priority | 122,832 | 49,132.798 | 50,232 | 80,593 | 136,265 | SUPPORTED |

## Compression characterization

The canonical dataset was not changed. Compression was applied to a deterministic USTAR tar stream containing manifest.json plus the manifest-owned shards, then the temporary archive was deleted.

~~~text
CANONICAL_NDJSON_BYTES=6131894967
CANONICAL_DATASET_BYTES=6131943637
ARCHIVE_INPUT_BYTES=6131998720 (deterministic USTAR tar stream)
~~~

Zstandard was not available:

~~~text
ZSTD_RESULTS=NOT_RUN
ZSTD_EXECUTABLE_AVAILABLE=NO
ZSTD_PYTHON_MODULE_AVAILABLE=NO (independently checked with importlib.util.find_spec)
ZSTD_REASON=zstd executable unavailable; Python zstandard module independently checked and unavailable
~~~

Measured gzip results:

| Codec | Compressed archive bytes | Canonical-to-compressed ratio | Bytes/decision | Compress | Decompress |
|---|---:|---:|---:|---:|---:|
| gzip level 1 | 758,561,434 | 8.083648x | 6,045.711 | 146.733 MiB/s | 451.742 MiB/s |
| gzip level 6 | 558,150,197 | 10.986189x | 4,448.440 | 64.692 MiB/s | 510.966 MiB/s |
| gzip level 9 | 540,543,031 | 11.344043x | 4,308.111 | 20.362 MiB/s | 263.416 MiB/s |

Compression wall times were:

~~~text
GZIP_LEVEL_1_COMPRESS_SECONDS=39.854
GZIP_LEVEL_1_DECOMPRESS_SECONDS=12.945
GZIP_LEVEL_6_COMPRESS_SECONDS=90.396
GZIP_LEVEL_6_DECOMPRESS_SECONDS=11.445
GZIP_LEVEL_9_COMPRESS_SECONDS=287.203
GZIP_LEVEL_9_DECOMPRESS_SECONDS=22.200
~~~

The archive-input-to-compressed ratios were 8.083721x, 10.986288x, and 11.344145x respectively; the table uses canonical dataset bytes for the requested canonical-to-compressed ratio. Gzip is a physical archive comparison, not semantic authority.

### Compression by decision family

The following is an analytical family view, not a new shard layout. Each family receives an
independent deterministic gzip-6 stream of the original full canonical decision-frame lines in
encounter order. `rawBytes` is the sum of those original frame bytes; `compressedBytes` includes
that family's gzip member header/trailer. Decision-kind and chosen-surface families are disjoint.
Dimension families overlap and must not be summed. LOW_N/LIMITED_N labels follow the same sample
rules as the raw family tables.

#### Semantic decision kind

| Family | N | Raw bytes | Compressed bytes | Ratio | Sample |
|---|---:|---:|---:|---:|---|
| PRIORITY | 122,832 | 6,035,079,822 | 548,074,891 | 11.011415x | SUPPORTED |
| SELECT_CARDS | 2,336 | 88,439,819 | 8,452,450 | 10.463217x | LIMITED_N |
| CHOOSE_COLOR | 129 | 3,397,734 | 227,346 | 14.945211x | LOW_N |
| YES_NO | 106 | 3,151,474 | 296,379 | 10.633256x | LOW_N |
| CHOOSE_TARGETS | 68 | 1,589,476 | 150,605 | 10.553939x | LOW_N |

#### Chosen action/response surface

| Family | N | Raw bytes | Compressed bytes | Ratio | Sample |
|---|---:|---:|---:|---:|---|
| ActivateAbility | 43,017 | 2,412,987,953 | 222,900,453 | 10.825406x | SUPPORTED |
| PassPriority | 76,523 | 3,483,132,688 | 313,633,831 | 11.105730x | SUPPORTED |
| PlayLand | 2,132 | 89,549,855 | 7,949,455 | 11.264905x | LIMITED_N |
| CardsSelectedResponse | 2,336 | 88,439,819 | 8,452,450 | 10.463217x | LIMITED_N |
| CastSpell | 731 | 33,932,983 | 3,219,454 | 10.539981x | LOW_N |
| DeclareAttackers | 388 | 13,486,497 | 1,198,637 | 11.251527x | LOW_N |
| CycleCard | 41 | 1,989,846 | 187,814 | 10.594769x | LOW_N |
| ColorChosenResponse | 129 | 3,397,734 | 227,346 | 14.945211x | LOW_N |
| YesNoResponse | 106 | 3,151,474 | 296,379 | 10.633256x | LOW_N |
| TargetsResponse | 68 | 1,589,476 | 150,605 | 10.553939x | LOW_N |

#### Overlapping dimensions

| Dimension | N | Raw bytes | Compressed bytes | Ratio | Sample |
|---|---:|---:|---:|---:|---|
| repeatCount-bearing actions | 43,017 | 2,412,987,953 | 222,900,453 | 10.825406x | SUPPORTED |
| priority | 122,832 | 6,035,079,822 | 548,074,891 | 11.011415x | SUPPORTED |
| structured pending decision | 2,639 | 96,578,503 | 9,101,030 | 10.611821x | LIMITED_N |
| target-bearing actions | 2,023 | 80,384,912 | 5,914,934 | 13.590162x | LIMITED_N |
| payment-bearing actions | 515 | 39,467,945 | 3,647,794 | 10.819675x | LOW_N |
| card selections | 2,336 | 88,439,819 | 8,452,450 | 10.463217x | LIMITED_N |
| color choices | 129 | 3,397,734 | 227,346 | 14.945211x | LOW_N |
| yes/no | 106 | 3,151,474 | 296,379 | 10.633256x | LOW_N |

## Strict A7 reader characterization

The Kotlin probe called TrajectoryV1Reader.openPublishedDataset and then streamEpisodes() on the exact dataset. It asserted the exact dataset ID and manifest digest and asserted 64 episodes / 125,471 decisions after streaming.

The accepted strict run used:

~~~text
JVM_TEST_HEAP=4g
JVM_CONFIGURATION=-DeclCollect=true (repository test configuration)
A7_READER_TEST_EXIT=0
A7_READER_TEST_BODY_SECONDS=3199.067
A7_NATIVE_INVOCATION_WALL_SECONDS=3217.172
A7_PREFLIGHT_TIME_SECONDS=1429.667
A7_STREAM_TIME_SECONDS=1768.797
A7_TOTAL_STRICT_READER_TIME_SECONDS=3198.463
A7_STREAM_DECISIONS_PER_SEC=70.936
A7_STREAM_EPISODES_PER_SEC=0.036183
EPISODES=64
DECISIONS=125471
A7_PREFLIGHT=PASS
A7_STREAM=PASS
~~~

A7 preflight includes manifest validation and full validation of every manifest-owned shard before returning the handle. The stream pass revalidates each bounded shard before yielding. Therefore these are trusted-reader measurements, not raw-I/O measurements.

A control run at the ordinary 2 GB test heap reached the accepted reader call but failed with OutOfMemoryError at TrajectoryV1Reader.openPublishedDataset after 1,066.845 seconds. It did not report a manifest, digest, schema, replay, privacy, or chosen-domain mismatch. The 4 GB run completed. No reader code or canonical data was changed to accommodate either result.

~~~text
PHASE_SPLIT_MANIFEST_ONLY=UNMEASURED
PHASE_SPLIT_PHYSICAL_CHECKSUM=UNMEASURED
PHASE_SPLIT_FRAME_DECODE=UNMEASURED
PHASE_SPLIT_A5_RECONSTRUCTION=UNMEASURED
RAW_IO_BASELINE=NOT_RUN
~~~

### Reader memory and GC

The probe sampled JVM heap usage after preflight and after each yielded episode. This is a sampled boundary maximum, not a process peak:

~~~text
JVM_HEAP_USED_AFTER_PREFLIGHT_BYTES=3608968136
JVM_HEAP_USED_MAX_SAMPLED_BYTES=3896647112
JVM_HEAP_USED_AFTER_STREAM_BYTES=3210985808
GC_COLLECTION_COUNT_DELTA=7657
GC_COLLECTION_TIME_MILLIS_DELTA=290973
PEAK_RSS=UNMEASURED
~~~

An OS process observation during the run reached approximately 4.62 GB private memory, but it was not sampled as a complete time series and is not reported as peak RSS.

## Trusted-generation / writer characterization

The existing trusted test was run once with:

~~~text
TASK=:gym:environmentV1TrustedGenerationTest
EPISODE_LIMIT=8
NEWLY_GENERATED_EPISODES=8
TRUSTED_DECISIONS=16000
TEST_BODY_SECONDS=1836.027
NATIVE_INVOCATION_WALL_SECONDS=1883.734
TEST_BODY_TRUSTED_DECISIONS_PER_SEC=8.714
TEST_BODY_TRUSTED_EPISODES_PER_SEC=0.004357
INVOCATION_WALL_DECISIONS_PER_SEC=8.494
INVOCATION_WALL_EPISODES_PER_SEC=0.004247
JVM_TEST_HEAP=8g (dedicated task configuration)
REPLAY_EXACT=8
REPLAY_DIVERGED=0
REPLAY_INCOMPLETE=0
A5_VALID=8
A6_ADMITTED=8
A7_STREAM_EPISODES=8
FAILED=0
QUARANTINED=0
UNSUPPORTED_DIAGNOSTICS=0
PUBLIC_CHOICE_REJECTIONS=0
CHOSEN_NOT_IN_DOMAIN=0
CLOSURE=8 INTERRUPTED, 0 GAME_TERMINAL
~~~

The test's printed BASE=0aa9444c... is stale relative to the actual source head. The actual execution was from HEAD=2a030fa8..., recorded above; the existing harness was not modified.

The test body includes the trusted Gym execution, observation/domain creation, semantic trajectory construction, CompactReplay verification, A5 validation, A6 admission, canonical serialization, shard/manifest finalization, and fresh A7 read. The harness does not expose independent timings for those phases.

~~~text
GYM_EXECUTION_STAGE=UNMEASURED
OBSERVATION_DOMAIN_STAGE=UNMEASURED
SEMANTIC_TRAJECTORY_STAGE=UNMEASURED
COMPACT_REPLAY_STAGE=UNMEASURED
A5_STAGE=UNMEASURED
A6_STAGE=UNMEASURED
SERIALIZATION_STAGE=UNMEASURED
SHARD_FINALIZATION_STAGE=UNMEASURED
MANIFEST_FINALIZATION_STAGE=UNMEASURED
WRITER_PEAK_RSS=UNMEASURED
WRITER_JVM_HEAP_PEAK=UNMEASURED
WRITER_GC_COUNT_TIME=UNMEASURED
~~~

The 8-episode run was a bounded runtime characterization, not a new acceptance decision. Its temporary output was not reused for the canonical 64-episode storage analysis and was not published.

## Scaling

~~~text
1_ENV=NOT_RUN
2_ENV=NOT_RUN
4_ENV=NOT_RUN
8_ENV=NOT_RUN_AS_A_SCALING_SERIES
SCALING_REASON=The accepted A9 publication architecture is serial in the existing trusted test and no separate parallel benchmark harness was added for this characterization.
~~~

The single bounded 8-episode generation run is not a 1/2/4/8 scaling campaign.

## Projection to larger decision counts

These are linear planning projections, not guaranteed future corpus sizes. The central estimate uses the measured mean full decision-frame bytes. The high estimate uses the measured p95 full decision-frame bytes. The fixed 236,642 bytes of observed episode-start/end shard overhead plus the 48,670-byte manifest are included once; future episode-count/framing behavior was not separately modeled.

Compressed values use the measured gzip level 6 canonical-to-compressed ratio of 10.986189x. They are archive estimates, not canonical sizes.

| Target decisions | Canonical mean | Canonical p95 | gzip-6 mean archive | gzip-6 p95 archive |
|---:|---:|---:|---:|---:|
| 100,000 | 4.887 GB (4.552 GiB) | 8.043 GB (7.491 GiB) | 0.445 GB (0.414 GiB) | 0.732 GB (0.682 GiB) |
| 1,000,000 | 48.869 GB (45.513 GiB) | 80.428 GB (74.905 GiB) | 4.448 GB (4.143 GiB) | 7.321 GB (6.818 GiB) |
| 2,000,000 | 97.739 GB (91.026 GiB) | 160.856 GB (149.809 GiB) | 8.896 GB (8.286 GiB) | 14.642 GB (13.636 GiB) |
| 4,000,000 | 195.477 GB (182.052 GiB) | 321.712 GB (299.618 GiB) | 17.793 GB (16.571 GiB) | 29.283 GB (27.272 GiB) |
| 10,000,000 | 488.692 GB (455.129 GiB) | 804.280 GB (749.044 GiB) | 44.482 GB (41.427 GiB) | 73.208 GB (68.181 GiB) |

## Decision gate

### A. Physical acceptability

| Workload | Decision |
|---|---|
| Small learner smoke | YES for bounded samples; the canonical format remains authoritative, but the strict reader is resource-heavy on this host. |
| Approximately 100k decisions | YES with dedicated storage and archive handling; measured central estimate is 4.887 GB canonical and 0.445 GB gzip-6 archive, with p95 planning values 8.043 GB and 0.732 GB. |
| Approximately 1M decisions | CONDITIONAL / HEAVY; central canonical estimate is 48.869 GB and p95 is 80.428 GB. Compression makes distribution copies manageable, but does not reduce canonical working-set or strict-reader costs. |
| Multi-million decisions | NO as a routine canonical single-host working artifact without a separately authorized physical-storage plan; 2M/4M/10M mean canonical estimates are approximately 97.7/195.5/488.7 GB. |

### B. Is compression alone sufficient?

~~~text
CLASSIFICATION=STRUCTURAL_STORAGE_OPTIMIZATION_LIKELY_NEEDED
~~~

Compression is sufficient as an immediate distribution/archive layer for a bounded near-term sample: gzip-6 measured an approximately 11x reduction. It is not sufficient as the sole answer for canonical multi-million storage because the canonical format remains large and the strict reader required a 4 GB heap and approximately 53.3 minutes for the measured preflight-plus-stream phases (53.6 minutes including the native invocation boundary) on this host.

### C. Dominant storage cost

~~~text
CLASSIFICATION=MIXED
QUALIFIER=PlayerObservationV1-dominant, CompleteLegalDomain materially co-dominant
~~~

The measured shares support this classification. Repeated static metadata, replay linkage, closure metadata, and framing are not dominant.

### D. Later investigation recommendations

Recommendations only; none is implemented here:

1. Investigate a static/dynamic observation split.
2. Investigate delta encoding for repeated observation/domain structure.
3. Investigate a binary physical codec or columnar/derived learner view while retaining Trajectory V1 as authority.
4. Keep compression-only archiving as the near-term distribution option.
5. Investigate dictionary/string interning only after direct measurement of repeated-string contribution; this scan did not isolate it.
6. Do not reinterpret a derived learner view or compressed archive as a replacement for the authoritative sample.

## Remaining #119 audit boundary

~~~text
P1_COMPRESSION_BY_DOMAIN_FAMILY=CLOSED_BY_THIS_AMENDMENT
P1_START_PLAYER_RNG_COUPLING_AUDIT=CLOSED_BY_SEPARATE_FINAL_ACCEPTANCE
P2_EPISODE_P99_9=CLOSED_BY_THIS_AMENDMENT
P2_GZIP_HIGH_LEVEL=CLOSED_BY_THIS_AMENDMENT (gzip level 9 measured)
P2_A7_RATE_NAMING=CLOSED_BY_THIS_AMENDMENT
P2_ZSTD_MODULE_EVIDENCE=CLOSED_BY_THIS_AMENDMENT
START_PLAYER_RNG_COUPLING_AUDIT=FINAL_ACCEPTANCE_PASS (merged PR #145)
~~

The start-player RNG coupling audit was completed separately and accepted through merged PR #145.
Its classification is PARTIALLY_COUPLED_WITH_DEFINED_BOUNDARY: whole-trajectory blocking across
start variants is not valid, pre-mulligan bounded analysis is conditional, and split grouping remains
separate. This report references that accepted result without duplicating or changing the audit.

## Unavailable or unmeasured fields

~~~text
ZSTD_LEVEL_RESULTS=UNMEASURED/NOT_RUN (codec unavailable; executable and Python module checked independently)
RAW_IO_THROUGHPUT=NOT_RUN
A7_MANIFEST_ONLY_TIME=UNMEASURED
A7_PHYSICAL_CHECKSUM_ONLY_TIME=UNMEASURED
A7_FRAME_DECODE_ONLY_TIME=UNMEASURED
A7_A5_RECONSTRUCTION_ONLY_TIME=UNMEASURED
TRUSTED_GENERATION_PER_STAGE_TIMINGS=UNMEASURED
TRUSTED_GENERATION_PEAK_RSS=UNMEASURED
TRUSTED_GENERATION_JVM_HEAP_PEAK=UNMEASURED
TRUSTED_GENERATION_GC_COUNT_TIME=UNMEASURED
FULL 1/2/4/8 SCALING SERIES=NOT_RUN
LARGE_CORPUS_GENERATION=NOT_AUTHORIZED_AND_NOT_RUN
TRAJECTORY_V2=NOT_IN_SCOPE
COMPRESSION_IMPLEMENTATION=NOT_IN_SCOPE
LEARNER_OR_TRAINING=NOT_AUTHORIZED_AND_NOT_RUN
~~~

## Semantic scope and stop-condition audit

No Trajectory V1 schema, PlayerObservationV1, CompleteLegalDomain, CandidateDomainDigest, CompactReplay, A5/A6/A7 semantic code, Rules code, deck, privacy contract, decision completeness, or golden was changed.

No privacy failure, A7 validation failure, manifest/digest mismatch, replay divergence, chosen-not-in-domain result, schema mismatch, or unexpected production semantic drift was observed. The 2 GB strict-reader OOM was recorded as a resource boundary; the authorized 4 GB strict run passed.

## Completion status

~~~text
POST_B2_CHARACTERIZATION_IMPLEMENTATION_PASS=YES
POST_B2_HOSTED_CI_PASS=NOT_RUN
POST_B2_CODE_REVIEW_PASS=YES
POST_B2_FINAL_ACCEPTANCE_PASS=NO

DATA_TRUSTED=YES

C0_AUTHORIZED=YES
TRAINING_AUTHORIZED=NO
LARGE_CORPUS_GENERATION_AUTHORIZED=NO
~~~
