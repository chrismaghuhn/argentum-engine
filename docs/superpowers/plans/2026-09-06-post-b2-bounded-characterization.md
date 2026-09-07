# Post-B2 Bounded Data Characterization Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

Goal: Measure the accepted Trajectory V1 dataset's physical storage, strict A7 reader throughput, bounded trusted-generation throughput, compression behavior, and decision-family distribution, then publish an evidence-bounded C0 report without changing semantic contracts.

Architecture: The exact finalized A9 dataset remains external and read-only. An opt-in Kotlin test invokes TrajectoryV1Reader.openPublishedDataset and streamEpisodes so reader numbers include the accepted A7 validation path. A standalone Python scanner performs deterministic byte accounting, family grouping, bounded gzip archive measurement when zstd is unavailable, and projections; it never rewrites canonical data. The existing A9 trusted-generation test is run once with a9.episodeLimit=8 for bounded writer timing.

Tech Stack: Kotlin/JVM 21, Kotest, accepted Gym Trainer Trajectory V1 reader, Python 3.13 standard library, Windows PowerShell, gzip fallback when zstd is not installed.

---

### Task 1: Establish exact provenance and preserve the accepted dataset

Files:
- Read-only: git refs, docs/ml/b2-a9-fresh-restart-after-pr135-2026-09-06.md, external finalized dataset directory.

- [x] Step 1: Verify repository identity.

Run:

~~~powershell
git fetch origin
git fetch upstream
git rev-parse origin/main
git remote get-url origin
~~~

Expected: origin/main is 2a030fa8aa6eb86a1b468f1c7a9ec7f5a10cda89, and origin is https://github.com/chrismaghuhn/argentum-engine.git.

- [x] Step 2: Verify isolated worktree identity.

Run:

~~~powershell
git worktree add C:\Users\chris\.config\superpowers\worktrees\argentum-engine\post-b2-bounded-characterization-20260906 -b chris/post-b2-bounded-characterization-20260906 origin/main
git status --short --branch
~~~

Expected: the worktree is clean at the authorized base.

- [x] Step 3: Resolve the exact dataset by identity.

Use only the finalized directory whose manifest contains:

~~~text
datasetId=69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03
manifestContentDigest=de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2
~~~

Do not regenerate the 64-episode corpus or use .staging.

### Task 2: Add the opt-in strict-reader measurement probe

Files:
- Create: gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/trajectory/PostB2TrajectoryReaderMeasurementTest.kt

- [x] Step 1: Add a disabled-by-default test.

The test reads -DpostB2.dataset=<absolute-path> or the inherited POST_B2_DATASET environment variable, and binds the accepted dataset ID and digest as constants. With the dataset setting absent it is disabled. When enabled it calls TrajectoryV1Reader.openPublishedDataset, asserts both identities, streams every episode through streamEpisodes, and asserts manifest episode/decision counts. It prints elapsed milliseconds, counts, sampled JVM heap usage, and GC deltas. It does not access private reader internals or bypass A7 validation.

- [x] Step 2: Run the probe against the exact finalized dataset.

Run through the native fallback because the repository just wrapper cannot launch /bin/bash on this Windows host:

~~~powershell
$env:POST_B2_DATASET=<dataset-root>
.\gradlew.bat :gym-trainer:test --tests "*PostB2TrajectoryReaderMeasurementTest" --console=plain -DeclCollect=true
~~~

Record preflight and streaming timings separately; classify any omitted phase split as UNMEASURED rather than inferring causality.

### Task 3: Add deterministic storage and compression analysis

Files:
- Create: scripts/post_b2_bounded_characterization.py

- [x] Step 1: Implement read-only manifest-owned scanning.

The scanner must read only manifest.json and the shard paths named by the manifest. For each shard it must stream LF-delimited frames, parse JSON, and collect frame byte lengths, episode byte totals, decision byte totals, decision kinds, chosen action/response shapes, payload fields, and canonical field-value sizes. It must report counts with every percentile and label small populations.

- [x] Step 2: Implement an exact analytical component partition.

Partition each canonical decision frame into: observation, complete domain, candidate digest plus semantic decision identity, chosen semantic input/response, decision/frame metadata, and other. Partition episode-start metadata into episode/provenance, replay linkage, and closure metadata; partition episode-end fields into closure metadata. Assign JSON object punctuation to decision/frame metadata and state that this is a deterministic analytical decomposition, not a changed wire format. Assert that category totals equal canonical shard bytes.

- [x] Step 3: Implement bounded archive compression.

Prefer an existing zstd executable. If absent, record ZSTD=NOT_RUN and measure Python standard-library gzip at levels 1, 6, and 9 over a deterministic tar stream of manifest.json plus manifest-owned shards. Report compressed bytes, ratio, and compression/decompression throughput separately from canonical bytes. Do not add a production dependency or modify source files.

- [x] Step 4: Run the scanner and retain its output outside the repository.

Write JSON evidence to a temporary directory, inspect it for identity/count parity, and use it to draft the report. Do not commit the external dataset, compressed archive, or temporary scan output.

### Task 4: Run the bounded trusted-generation measurement

Files:
- Read-only execution: gym/src/test/kotlin/com/wingedsheep/gym/EnvironmentV1TrustedGenerationTest.kt

- [x] Step 1: Run no more than eight newly generated episodes.

Measure one invocation with -Da9.episodeLimit=8 using the existing trusted Gym to semantic trajectory to CompactReplay to A5 to A6 to writer to A7 path. Capture total wall-clock time and the test's trusted decision/episode counts. Do not run the 64-episode matrix again.

- [x] Step 2: Report stage limitations honestly.

Use only the coarse wall-clock boundary unless test output exposes a stage timing. Mark per-stage Gym, replay, A5, A6, serialization, finalization, RSS, JVM heap peak, and GC metrics UNMEASURED when no independent measurement exists; do not claim precise causality from the one wall-clock number.

### Task 5: Publish the evidence report

Files:
- Create: docs/ml/post-b2-bounded-data-characterization-2026-09-06.md

- [x] Step 1: Include exact base, head, runtime, dataset identities, counts, byte distributions, component attribution, family tables, codec results, reader/generator throughput, memory/GC status, projections, decision gate, and every unavailable field.

The report must explicitly distinguish CANONICAL_DATASET_SIZE from DISTRIBUTION_ARCHIVE_SIZE, never treat compression as semantic authority, and set the final status block to the user-authorized post-B2 characterization state.

- [x] Step 2: Cross-check report values against the machine-readable scan output and fresh command logs.

Reject any number that cannot be traced to an exact dataset count, a named measurement method, or a clearly labeled inference.

### Task 6: Verify, commit, and push without opening a PR

Files:
- Modified only by the preceding tasks.

- [x] Step 1: Run the required repository gates.

Run just test-gym, just test-gym-trainer, just test-server, and git diff --check. If the just wrapper remains blocked by missing WSL /bin/bash, retain the exact BLOCKED result and run the separately labeled native fallback tasks; do not relabel the wrapper as passing.

- [x] Step 2: Review the diff for semantic scope.

Confirm no Trajectory V1, PlayerObservationV1, CompleteLegalDomain, CandidateDomainDigest, CompactReplay, A5/A6/A7, Rules, deck, privacy, or golden files changed.

- [x] Step 3: Commit and push the focused branch.

Run:

~~~powershell
git add docs/superpowers/plans/2026-09-06-post-b2-bounded-characterization.md docs/ml/post-b2-bounded-data-characterization-2026-09-06.md scripts/post_b2_bounded_characterization.py gym-trainer/src/test/kotlin/com/wingedsheep/gym/trainer/trajectory/PostB2TrajectoryReaderMeasurementTest.kt
git commit -m "docs: characterize post-B2 trajectory storage"
git push -u origin chris/post-b2-bounded-characterization-2026-09-06
~~~

Do not create or merge a pull request. Report the exact resulting HEAD and stop with POST_B2_HOSTED_CI_PASS=NOT_RUN, POST_B2_CODE_REVIEW_PASS=NO, and POST_B2_FINAL_ACCEPTANCE_PASS=NO.

---

## Self-review checklist

- [x] Spec coverage: dataset identity, storage distributions, component attribution, family analysis, compression, A7 reader, bounded writer, runtime provenance, projections, decision gate, unavailable fields, verification, and stop statuses are all assigned to Tasks 2–6.
- [x] Placeholder scan: no implementation step relies on an unspecified file, hidden fallback, or future authorization; unavailable measurements are explicitly reported as UNMEASURED.
- [x] Type/interface consistency: the only code seam is the existing public TrajectoryV1Reader handle and streamEpisodes path; storage analysis remains external and does not introduce a production API.
