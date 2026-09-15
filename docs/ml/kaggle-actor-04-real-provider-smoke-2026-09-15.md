# KAGGLE_ACTOR_04 — Real Kaggle Provider Smoke

Date: 2026-09-15
Accepted integration base: e118ec24b1ce22ad340ecb31004b12045c069cd0
Implementation/source SHA used by the provider attempt: c6b72b68c4c1639718938b2253148d6d266d7cca

## Result

The provider notebook executed on Kaggle and verified the public checkout at the exact implementation
SHA. The actor build then stopped before LocalActorExecutionV1 because this Kaggle runtime provides
OpenJDK 17.0.18 but no Java 21 installation, while the repository Gradle toolchain requires Java 21
and has no configured toolchain download repository.

This is a provider/build characterization, not trusted remote-data acceptance. No actor episode, B2
dataset, Kaggle working envelope, export, or download was fabricated after the fail-closed stop.

The notebook was saved as a successful Kaggle Quick Save, Version 1, at:

https://www.kaggle.com/code/chrismaghuhn/ka04-real-kaggle-provider-smoke-2026-09-15

The Quick Save is notebook-version evidence only. It is not PROVIDER_PUBLISHED actor output and does
not make any data dataset-eligible.

## Provider notebook procedure

The notebook cloned only the public writable fork and detached the exact implementation SHA:

~~~text
git clone --no-tags --depth 1 --branch chris/kaggle-actor-04-real-provider-smoke-20260915 https://github.com/chrismaghuhn/argentum-engine.git /kaggle/tmp/argentum-engine-ka04
git checkout --detach c6b72b68c4c1639718938b2253148d6d266d7cca
~~~

It then attempted the opt-in provider-neutral smoke task with:

~~~text
bash /kaggle/tmp/argentum-engine-ka04/gradlew --no-daemon :gym:kaggleActor04SmokeTest --console=plain
-Dka04.enabled=true
-Dka04.engineSourceCommit=c6b72b68c4c1639718938b2253148d6d266d7cca
-Dka04.repositoryRoot=/kaggle/tmp/argentum-engine-ka04
-Dka04.outputRoot=/kaggle/tmp/ka04-smoke
-Dka04.publicationRoot=/kaggle/working/ka04-smoke
-Dka04.profiles=1,2,4
~~~

The cell also checked git status with tracked-only output and found the detached checkout clean. The
provider-visible output check found no /kaggle/working/ka04-smoke artifact after the build stop.

## Kaggle environment summary

These facts were read inside the Kaggle kernel and were not inserted into semantic identities:

| fact | observed value |
|---|---|
| OS / kernel | Linux / 6.12.90+ |
| architecture | x86_64 |
| Java / javac | OpenJDK 17.0.18 / javac 17.0.18 |
| Java 21 | unavailable; only Java 17 candidates were present |
| JAVA_HOME | not configured |
| Python | 3.12.13 |
| Git | 2.34.1 |
| available CPUs | 4 |
| available RAM | 32,308,600,832 bytes |
| /kaggle/tmp free | 1,101,680,312,320 bytes |
| /kaggle/working free | 20,940,595,200 bytes |

The Gradle failure was:

~~~text
Cannot find a Java installation on the machine matching languageVersion=21.
Toolchain download repositories have not been configured.
~~~

No dynamic JDK installation or unpinned toolchain download was attempted.

## Local reference evidence from the same implementation SHA

The local opt-in harness ran the accepted actor/replay/B2 composition with the locked Akiri and Chevill
curriculum sources. The source digests were checked against the authoritative files:

~~~text
Akiri   = E774200BF9444DBF420B27573C63BAC4659F59568BBB53340D3A0FD7BDBE5E04
Chevill = 0257823208E24D8EAC90773081B98ECF875FB77639BAFD820BC24CA41FC06474
~~~

The deterministic four-job plan produced:

~~~text
WORKLOAD_PLAN_ID=d0280bd61a21efe2aac65ea3386a7c70f4e4cfa0f4772447a7f3b7ca9ff4d852
FULL_ASSIGNMENT_ID=dbacfd815eae70971067870633b45e0db6a791606461c7f9d6bc9c5a9072a7ed
~~~

Local profile results were four complete one-step horizon-bounded episodes per profile, with zero
failed and zero partial-lost episodes:

| profile | complete | failed | partial-lost | published envelopes | re-imported envelopes |
|---:|---:|---:|---:|---:|---:|
| 1 | 4 | 0 | 0 | 1 | 1 |
| 2 | 4 | 0 | 0 | 2 | 2 |
| 4 | 4 | 0 | 0 | 4 | 4 |

Each actor used an independent B2 writer and publication envelope. Local staged publication, strict
copied-dataset validation, re-import, semantic joining by expectedSemanticEpisodeId plus
expectedCollectionJobId, exact duplicate retention, conflict fail-closed behavior, partial-loss
exclusion, and deterministic B2 repack were covered by focused tests.

The local offline admission run intentionally supplied no independent replay verifier:

~~~text
OFFLINE_REPLAY_REVERIFICATION=NO_INDEPENDENT_PROOF
DATASET_ELIGIBLE=NO
acceptedSources=[]
~~~

The semantic-join/repack test uses an explicitly test-supplied verifier only to characterize the
existing join and repack mechanics. That is not transported replay proof and is not a remote dataset
trust claim.

## Verification evidence

Focused and regression commands used the native Gradle fallback because the repository just Gradle
wrapper is blocked on this Windows host by the known WinError 193 executable-format issue. The
fallback results were:

~~~text
./gradlew.bat --no-daemon :gym:test --tests *KaggleActor04SmokeTest --console=plain
PASS — 8 KA04 contract/integration tests

./gradlew.bat --no-daemon :gym-trainer:test --console=plain
PASS — full gym-trainer suite

./gradlew.bat --no-daemon :gym:test --console=plain
PASS — full gym suite; opt-in Kaggle provider test SKIPPED as configured

./gradlew.bat --no-daemon :game-server:test --tests *ReplayTrajectoryVerificationTest --console=plain
PASS — targeted replay verification suite

git diff --check
PASS
~~~

The skipped opt-in/provider and unrelated heavy characterization tests are not counted as passes.
Hosted CI was not run because no PR was created.

## Final KA04 status

~~~text
TASK=KAGGLE_ACTOR_04_REAL_KAGGLE_PROVIDER_SMOKE
BASE_ORIGIN_MAIN=e118ec24b1ce22ad340ecb31004b12045c069cd0
IMPLEMENTATION_SOURCE_SHA=c6b72b68c4c1639718938b2253148d6d266d7cca
BRANCH=chris/kaggle-actor-04-real-provider-smoke-20260915

KAGGLE_EXECUTION_OCCURRED=YES
KAGGLE_ENVIRONMENT_BOOTSTRAP=FAIL_JDK21_UNAVAILABLE
SOURCE_BOOTSTRAP_VERIFICATION=PASS
ACTUAL_RUNTIME_SOURCE_AUTHORITY=NOT_RUN_BUILD_BLOCKED

WORK_ASSIGNMENT_ID=dbacfd815eae70971067870633b45e0db6a791606461c7f9d6bc9c5a9072a7ed
WORKLOAD_PLAN_ID=d0280bd61a21efe2aac65ea3386a7c70f4e4cfa0f4772447a7f3b7ca9ff4d852

CONCURRENCY_1=NOT_RUN_REMOTE
CONCURRENCY_2=NOT_RUN_REMOTE
CONCURRENCY_4=NOT_RUN_REMOTE
COMPLETE_EPISODES=0
FAILED_EPISODES=0
PARTIAL_LOST_EPISODES=0

LOCAL_B2_FINALIZATION=PASS_LOCAL_REFERENCE
KAGGLE_WORKING_COPY=NOT_RUN
WORKING_COPY_REVALIDATION=NOT_RUN
PROVIDER_EXPORT=NOT_AVAILABLE_BUILD_BLOCKED
DOWNLOAD_ROUND_TRIP=NOT_AVAILABLE_BUILD_BLOCKED
LOCAL_REIMPORT=PASS_LOCAL_REFERENCE
SEMANTIC_JOIN=PASS_LOCAL_REFERENCE
DUPLICATE_POLICY=PASS_LOCAL_REFERENCE
CONFLICT_POLICY=PASS_LOCAL_REFERENCE

OFFLINE_REPLAY_REVERIFICATION=NO_INDEPENDENT_PROOF
DATASET_ELIGIBLE=NO
DETERMINISM_CHECK=PASS_LOCAL_REFERENCE

TRAJECTORY_V1_CHANGED=NO
REPLAY_SEMANTICS_CHANGED=NO
OBSERVATION_PRIVACY_CHANGED=NO
KAGGLE_PROVIDER_FIELDS_IN_SEMANTIC_IDS=NO

NEW_LARGE_DATA_GENERATED=NO
TRAINING_STARTED=NO
KA05_STARTED=NO

FOCUSED_TESTS=PASS_LOCAL_8_KA04_PLUS_RUNTIME_PROBE
REGRESSION_TESTS=PASS_NATIVE_GYM_TRAINER_GYM_REPLAY_TARGETED
HOSTED_CI=NOT_RUN_NO_PR

P1=0
P2=0
P3=0

KAGGLE_ACTOR_04_IMPLEMENTATION_PASS=NO_PROVIDER_BUILD_BLOCKED
KAGGLE_ACTOR_04_CODE_REVIEW_PASS=PENDING
KAGGLE_ACTOR_04_FINAL_ACCEPTANCE_PASS=PENDING
STOP_FOR_EXACT_SHA_REVIEW=YES
~~~

The unresolved independent replay-proof gate remains fail-closed. No Kaggle pilot data is eligible for
training, and KAGGLE_ACTOR_05 was not started.
