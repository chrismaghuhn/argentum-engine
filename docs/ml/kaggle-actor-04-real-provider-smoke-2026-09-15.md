# KAGGLE_ACTOR_04 — Real Kaggle Provider Smoke

Date: 2026-09-15

Accepted integration base: `e118ec24b1ce22ad340ecb31004b12045c069cd0`

Implementation/source SHA used by the provider attempt:
`c6b72b68c4c1639718938b2253148d6d266d7cca`

The base is the merged KA02/KA03 `origin/main`. The provider smoke code is on the
KA04 branch and was therefore executed from the exact KA04 implementation SHA above.
The checkout was detached at that SHA after cloning the public writable fork.

Kaggle notebook:
<https://www.kaggle.com/code/chrismaghuhn/ka04-real-kaggle-provider-smoke-2026-09-15>

Successful committed Kaggle version: `Version #5`, script version `350153225`.

## Result

The real Kaggle provider smoke passed end to end through actor execution, local B2
finalization, Kaggle-visible Working output, provider export, download, strict local
re-import, and downloaded semantic-join characterization.

The smoke deliberately remains ineligible for training data. The exported bundle does
not contain an independently transported replay proof accepted by the offline trust
contract:

~~~text
OFFLINE_REPLAY_REVERIFICATION=NO_INDEPENDENT_PROOF
DATASET_ELIGIBLE=NO
acceptedSources=[]
~~~

No RunReport claim, provider success state, manifest digest, same-process replay result,
or successful re-import was used as a substitute for independent offline replay proof.
Deterministic B2 repack consequently remains blocked, as required.

## Kaggle provider path

The notebook first installed and verified the required JDK in the fresh provider VM:

~~~text
apt-get update -y
apt-get install -y openjdk-21-jdk-headless
java -version       -> openjdk version "21.0.12" 2026-07-21
javac -version      -> javac 21.0.12
~~~

It then cloned and checked out the exact provider-smoke implementation:

~~~text
git clone --no-tags --depth 2 --branch chris/kaggle-actor-04-real-provider-smoke-20260915 \
  https://github.com/chrismaghuhn/argentum-engine.git /kaggle/tmp/argentum-engine-ka04
git checkout --detach c6b72b68c4c1639718938b2253148d6d266d7cca
~~~

The actor task was invoked with the exact source, repository, scratch, and publication
roots:

~~~text
bash /kaggle/tmp/argentum-engine-ka04/gradlew --no-daemon \
  :gym:kaggleActor04SmokeTest --console=plain \
  -Dka04.enabled=true \
  -Dka04.engineSourceCommit=c6b72b68c4c1639718938b2253148d6d266d7cca \
  -Dka04.repositoryRoot=/kaggle/tmp/argentum-engine-ka04 \
  -Dka04.outputRoot=/kaggle/tmp/ka04-smoke \
  -Dka04.publicationRoot=/kaggle/working/ka04-smoke \
  -Dka04.profiles=1,2,4
~~~

The cold provider build completed successfully:

~~~text
BUILD SUCCESSFUL in 11m 10s
56 actionable tasks: 56 executed
KaggleActor04ProviderSmokeTest > run the configured provider smoke ladder PASSED
~~~

The actor itself performed the KA03 fresh source probe at the execution boundary. The
checkout SHA matched, tracked source was clean, and the resulting operational report
recorded the verified runtime source commit.

## Workload and actor outcomes

The locked Akiri/Chevill Commander curriculum was used without deck changes.

~~~text
WORKLOAD_PLAN_ID=d0280bd61a21efe2aac65ea3386a7c70f4e4cfa0f4772447a7f3b7ca9ff4d852
WORK_ASSIGNMENT_ID=dbacfd815eae70971067870633b45e0db6a791606461c7f9d6bc9c5a9072a7ed
JOB_COUNT=4
~~~

Each profile used independent actor output and publication roots. The provider report
contained four complete, one-step horizon-bounded episodes for every profile:

| profile | complete | failed | partial-lost | published envelopes | re-imported envelopes |
|---:|---:|---:|---:|---:|---:|
| 1 | 4 | 0 | 0 | 1 | 1 |
| 2 | 4 | 0 | 0 | 2 | 2 |
| 4 | 4 | 0 | 0 | 4 | 4 |

Totals were 12 complete episodes, zero failed episodes, and zero partial-lost episodes.
No partial episode entered a canonical B2 dataset.

## Runtime characterization

Runtime facts were measured inside the provider and kept outside semantic identities.
The `ActorRuntimeProbeV1` report recorded:

| fact | observed value |
|---|---|
| OS / kernel | Linux / 6.12.90+ |
| architecture | amd64 |
| Java | 21.0.12 |
| Gradle wrapper | available |
| Python | 3.12.13 |
| Git | 2.34.1 |
| available processors | 4 |
| available RAM | 17,002,500,096 bytes |
| `/kaggle/tmp` free | 1,100,001,071,104 bytes |
| `/kaggle/working` free | 20,940,537,856 bytes |
| max JVM memory | 4,294,967,296 bytes |

A separate notebook diagnostic measured RAM and free space again, with expected runtime
variation: 24,880,013,312 bytes available RAM, 1,099,988,254,720 bytes free in
`/kaggle/tmp`, and 20,939,964,416 bytes free in `/kaggle/working`. This confirms that
capacity is runtime evidence, not a fixed semantic contract.

The provider-visible output panel reported approximately `616 KiB / 19.5 GiB` after
the successful run.

## Publication, export, and download

The actor produced the accepted envelope layout under `/kaggle/working/ka04-smoke`:

~~~text
concurrency-1/
concurrency-2/
concurrency-4/
ka04-smoke-report.json
~~~

The successful committed version exposed 41 output files in Kaggle's Output view:
seven immutable `envelope-*` bundles (1 + 2 + 4), their B2 manifests/shards and
operational files, plus the smoke report.

The Kaggle Output actions menu's `Download output` action produced `results.zip`:

~~~text
size      = 109,730 bytes
SHA-256   = AB54306E08CCE9EC57C4189435D5C283E0AAE308699093947E71F752EBE22456
entries   = 41 files
bundles   = 7 envelope-* directories
~~~

This establishes a real provider export/download round trip. The downloaded ZIP was
treated as untrusted input until local validation completed.

Earlier Quick Save versions #2 and #3 were successful notebook versions but their
public Output view exposed zero files. Version #4 failed because the JDK-install cell
was below the Gradle cell in the fresh VM. Moving the installation cell to the first
position and using `Save & Run All (Commit)` produced the successful Version #5 and
the exportable output. These observations are provider characterization, not trust
shortcuts.

## Local re-import and semantic membership

All seven downloaded envelopes were validated with the existing
`LocalPublicationEnvelopeV1.reimport()` path. That path re-checks canonical assignment,
status, and report encodings, source provenance, the B2 manifest, shard digests, and
the existing strict `TrajectoryV1Reader` dataset contract.

~~~text
DOWNLOADED_KA04_REIMPORT_AND_SEMANTIC_JOIN=PASS
downloaded envelopes strictly re-imported = 7/7
semantic join key = expectedSemanticEpisodeId + expectedCollectionJobId
physical episode ordinal used as join authority = NO
~~~

The downloaded envelopes were then characterized through `OfflineAdmissionV1` using
the exact full assignment from the concurrency-1 bundle. The semantic identity and
environment/policy cross-checks resolved successfully across the downloaded bundles.
Because no independent replay verifier was supplied, the expected fail-closed result
was produced: `NO_INDEPENDENT_PROOF`, all membership entries remained `MISSING`,
`acceptedSources` remained empty, and `datasetEligible` was false.

No target DatasetManifest repack was attempted after that blocked admission.

## Retry, duplicate, conflict, and determinism evidence

The focused KA04 contract tests on the same implementation SHA cover exact retry
identity preservation, identical duplicate handling, conflicting same-SJI rejection,
partial-loss exclusion, staged-copy mutation rejection, semantic joining independent
of physical episode ordinal, and deterministic B2 repack ordering.

The real provider run also confirmed that every actor had a distinct operational
attempt/output envelope while the workload plan and semantic assignment identities
remained stable. Provider, host, PID, timestamps, paths, and concurrency were not
inserted into semantic workload or trajectory identities.

The determinism result is therefore a PASS for the provider-neutral semantic identity
and local B2 characterization. It is not an independent replay trust claim for the
downloaded data; that claim remains blocked by the explicit offline proof gate.

## Exact verification commands

The following native Windows commands were used because the repository's `just` Gradle
launcher is blocked on this host by the known WinError 193 executable-format issue:

~~~text
.\\gradlew.bat --no-daemon :gym:test --tests "*KaggleActor04SmokeTest" --console=plain
.\\gradlew.bat --no-daemon :gym-trainer:test --console=plain
.\\gradlew.bat --no-daemon :gym:test --console=plain
.\\gradlew.bat --no-daemon :game-server:test --tests "*ReplayTrajectoryVerificationTest" --console=plain
~~~

All of those commands passed. The opt-in Kaggle provider test and other configured
characterization tests that were skipped remain reported as `SKIPPED`, not as passes.

The downloaded artifact check was an evidence-only temporary test, not a committed
production or Kaggle-specific reader:

~~~text
.\\gradlew.bat --no-daemon :gym:test --tests "*DownloadedKa04ArtifactReimportTest" --console=plain
PASS — 7/7 downloaded envelopes strict-reimported and semantically joined
~~~

The temporary test and extracted copy were removed after verification. `git diff --check`
also passed before the evidence commit.

## Trust boundary and limitations

The actual lifecycle demonstrated was:

~~~text
WRITING_TMP
→ VALIDATING_TMP
→ FINALIZED_LOCAL
→ COPYING_TO_WORKING
→ WORKING_COPY_VERIFIED
→ PUBLICATION_READY
→ provider committed version/export
→ local strict re-import
~~~

The actor did not claim `PROVIDER_PUBLISHED`; the successful Kaggle version and downloaded
ZIP are external provider evidence. The downloaded output was not promoted to
`DATASET_ELIGIBLE`, and no training candidate was created.

No provider-specific field entered `WorkloadPlanV1`, `WorkAssignmentV1`,
`SemanticJobIdentityV1`, `TrajectoryV1`, or B2 DatasetManifest identity. No trajectory
format, replay semantics, observation privacy, decision completeness, or Magic rules
were changed.

## Final KA04 status

~~~text
TASK=KAGGLE_ACTOR_04_REAL_KAGGLE_PROVIDER_SMOKE
BASE_ORIGIN_MAIN=e118ec24b1ce22ad340ecb31004b12045c069cd0
IMPLEMENTATION_SOURCE_SHA=c6b72b68c4c1639718938b2253148d6d266d7cca
BRANCH=chris/kaggle-actor-04-real-provider-smoke-20260915

KAGGLE_EXECUTION_OCCURRED=YES
KAGGLE_VERSION=5
KAGGLE_SCRIPT_VERSION_ID=350153225
KAGGLE_ENVIRONMENT_BOOTSTRAP=PASS
SOURCE_BOOTSTRAP_VERIFICATION=PASS
ACTUAL_RUNTIME_SOURCE_AUTHORITY=PASS

WORK_ASSIGNMENT_ID=dbacfd815eae70971067870633b45e0db6a791606461c7f9d6bc9c5a9072a7ed
WORKLOAD_PLAN_ID=d0280bd61a21efe2aac65ea3386a7c70f4e4cfa0f4772447a7f3b7ca9ff4d852

CONCURRENCY_1=PASS
CONCURRENCY_2=PASS
CONCURRENCY_4=PASS
COMPLETE_EPISODES=12
FAILED_EPISODES=0
PARTIAL_LOST_EPISODES=0

LOCAL_B2_FINALIZATION=PASS
KAGGLE_WORKING_COPY=PASS
WORKING_COPY_REVALIDATION=PASS
PROVIDER_EXPORT=PASS
DOWNLOAD_ROUND_TRIP=PASS
LOCAL_REIMPORT=PASS
SEMANTIC_JOIN=PASS
DUPLICATE_POLICY=PASS
CONFLICT_POLICY=PASS

OFFLINE_REPLAY_REVERIFICATION=NO_INDEPENDENT_PROOF
DATASET_ELIGIBLE=NO
DETERMINISM_CHECK=PASS

TRAJECTORY_V1_CHANGED=NO
REPLAY_SEMANTICS_CHANGED=NO
OBSERVATION_PRIVACY_CHANGED=NO
KAGGLE_PROVIDER_FIELDS_IN_SEMANTIC_IDS=NO

NEW_LARGE_DATA_GENERATED=NO
TRAINING_STARTED=NO
KA05_STARTED=NO

FOCUSED_TESTS=PASS
REGRESSION_TESTS=PASS_WITH_SKIPS_REPORTED
HOSTED_CI=NOT_RUN_NO_PR

P1=0
P2=0
P3=0

KAGGLE_ACTOR_04_IMPLEMENTATION_PASS=YES
KAGGLE_ACTOR_04_CODE_REVIEW_PASS=PENDING
KAGGLE_ACTOR_04_FINAL_ACCEPTANCE_PASS=PENDING
PR_CREATED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
~~~

KA05, training, and any large actor run were not started.
