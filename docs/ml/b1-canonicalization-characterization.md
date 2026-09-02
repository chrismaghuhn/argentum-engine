# B1 Canonicalization Characterization

Status: Tasks 1–3 only. No production optimization was implemented.

```text
BASE_HEAD=d0f4fe9eddb377fbddd7e675ec396ecbabe254d5
PARENT=ffcf897213f09932d30020f3c1df20b99f369b84
PRODUCTION_OPTIMIZATIONS=0
PRODUCTION_SEMANTIC_CHANGES=0
DIAGNOSTIC_PRODUCTION_HOOKS=YES
PROBE_DEFAULT_ENABLED=NO
TASK4_AUTHORIZATION=NOT_GRANTED
TASK5_AUTHORIZATION=NOT_GRANTED
TASK6_AUTHORIZATION=NOT_GRANTED
DATA_TRUSTED=NO
```

## Measurement contract

The probe is enabled only with the explicit test property `b1.characterize=true`. It uses scalar counters and one scalar summary for the largest observed `semanticJson` call. It does not retain `GameState` objects, observations, semantic strings, or UTF-8 byte arrays, and it never invokes an additional UTF-8 conversion.

`stateDigestInputBytes` is counted from the actual UTF-8 byte array already created by `StateDigest.compute()` before SHA-256. The probe therefore does not create or retain a second byte representation. Hard runtime/allocation comparisons must use probe-disabled runs; these characterization runs are evidence for call counts only.

## Results

All workloads used the existing locked Akiri/Chevill decks, deterministic external policy, strict Gym path, and `maxSteps=2,000`. The largest-call row is the maximum scalar row observed during that workload; it is not a second canonicalization pass.

| Workload | Episodes | Transitions | ObservationBuilder calls | Legal-action enumerations | Semantic JSON calls (`s`) | StateDigest calls (`d`) | Digest input bytes (`b`) |
|---|---:|---:|---:|---:|---:|---:|---:|
| witness | 1 | 2,000 | 2,020 | 3,921 | 2,020 | 2,020 | 96,127,939 |
| normal4 | 4 | 8,000 | 8,074 | 15,718 | 8,074 | 8,074 | 359,928,998 |
| corpus8 | 8 | 16,000 | 16,058 | 31,342 | 16,058 | 16,058 | 694,385,554 |

| Workload | Fingerprints (`f`) | Legal-action sort keys (`m`) | Aggregate `m/f` | Max `(n,f,m)` | Max `m/f` | Duplicate proven |
|---|---:|---:|---:|---|---:|---|
| witness | 28,865 | 170,250 | 5.8981 | (26, 26, 186) | 7.1538 | YES |
| normal4 | 110,285 | 642,212 | 5.8232 | (27, 27, 198) | 7.3333 | YES |
| corpus8 | 211,368 | 1,200,164 | 5.6781 | (29, 29, 216) | 7.4483 | YES |

The direct proof is `f=n` for the largest observed call and `m>n` in every workload. In the primary `corpus8`, the current selector evaluated 1,200,164 legal-action sort keys for 211,368 semantic fingerprints. The largest observed call evaluated 216 sort keys for 29 fingerprints. This confirms the legal-action `sortedBy { canonicalize(fingerprint).toString() }` selector as a real duplicate-work candidate without changing it.

Structured-domain sort-key evaluations were zero in these workloads. Wire JSON calls were zero because the strict baseline workload uses the semantic digest path; wire canonicalization remains covered by contract tests.

## P3 measurement corrections

The workload timer and after-snapshot now complete before `Recording.stop()`/`Recording.dump()`. The diagnostic runs recorded these separate artifact times:

| Workload | Workload wall | JFR stop | JFR dump |
|---|---:|---:|---:|
| witness | 34.086s | 0.057s | 0.022s |
| normal4 | 68.264s | 0.075s | 0.033s |
| corpus8 | 122.178s | 0.045s | 0.042s |

These times include characterization/JFR overhead and are not a production benchmark.

Replay allocation snapshots are pass-local after the correction:

| Run | Pass | Transitions | Allocated bytes | Bytes/transition | Use as normal KPI |
|---|---|---:|---:|---:|---|
| replay2 baseline | baseline-only | 4,000 | 31,628,857,856 | 7,907,214.464 | Diagnostic comparison only |
| replay2 replay | capture | 4,000 | 84,070,031,176 | 21,017,507.794 | No |
| replay2 replay | verify | 4,000 | 81,692,742,200 | 20,423,185.550 | No |

Capture and verify include their respective replay work and are not comparable with the baseline-only row. The previous aggregate `41.482 MB/transition` style value is not used as an optimization KPI.

## Interpretation and authorization boundary

The first production candidate is now the canonical action sort-key computation inside one `semanticJson` execution. The smallest future candidate is to materialize each fingerprint's existing canonical sort key once, sort by that stored string, and preserve stable equal-key order. Task 4 is not authorized by this commit.

The GameGymEnv cache remains a separate candidate. The repository path shows that `MultiEnvService.create()` and strict `step()` return the newly built observation directly; no real B0/trainer repeated-`observe()` caller was measured here. The ten-read cache witness and any cache fix remain deferred to Task 5.

The CompactReplay/ReplayFingerprint gate was not run in this characterization commit. Therefore `REPLAYFINGERPRINT_OVERHEAD=NOT_RUN` and no pre-existing classification is asserted here.

## Commands executed and results

The repository wrapper was attempted first:

```powershell
just test-class B1PerformanceBaselineTest --console=plain
```

Result: `BLOCKED` before Gradle with the known Windows `WinError 193` launcher failure.

The first unquoted native property invocation was also attempted:

```powershell
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" -Db1.profile=true -Db1.characterize=true -Db1.workload=witness -Db1.mode=baseline --console=plain
```

Result: `FAIL` at Gradle argument parsing because PowerShell passed the properties as `.profile=true`, `.characterize=true`, `.workload=witness`, and `.mode=baseline`; no workload ran. The corrected invocations quoted each `-D` argument.

Measurement-only compile/skip verification:

```powershell
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" --console=plain
```

Result: `PASS`, `B1PerformanceBaselineTest` `SKIPPED`, `BUILD SUCCESSFUL`.

Characterization runs:

```powershell
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" "-Db1.profile=true" "-Db1.characterize=true" "-Db1.workload=witness" "-Db1.mode=baseline" --console=plain
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" "-Db1.profile=true" "-Db1.characterize=true" "-Db1.workload=normal4" "-Db1.mode=baseline" --console=plain
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" "-Db1.profile=true" "-Db1.characterize=true" "-Db1.workload=corpus8" "-Db1.mode=baseline" --console=plain
```

Result: all three `PASS`; 1/1, 4/4, and 8/8 episodes; 2,000, 8,000, and 16,000 transitions; semantic decisions, external transitions, and engine progress matched transitions; zero trusted-path diagnostics.

Replay boundary verification:

```powershell
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" "-Db1.profile=true" "-Db1.characterize=false" "-Db1.workload=replay2" "-Db1.mode=baseline" --console=plain
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.B1PerformanceBaselineTest" "-Db1.profile=true" "-Db1.characterize=false" "-Db1.workload=replay2" "-Db1.mode=replay" --console=plain
```

Result: both `PASS`; baseline and verify allocation/GC snapshots are distinct and pass-local.

Focused semantic instrumentation regression:

```powershell
.\gradlew.bat :gym:test --tests "com.wingedsheep.gym.contract.ObservationCanonicalizationTest" --tests "com.wingedsheep.gym.contract.StateDigestTest" --tests "com.wingedsheep.gym.contract.ObservationPrivacyTest" --tests "com.wingedsheep.gym.GameGymEnvStrictExecutionTest" --tests "com.wingedsheep.gym.contract.TargetPaymentDomainContractTest" --tests "com.wingedsheep.gym.B0HarnessTimeoutPolicyTest" --console=plain
```

Result: `PASS`, 73 selected tests, 73 passed, 0 failed, 0 skipped. No Task 4–6 acceptance gate was run.
