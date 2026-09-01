# Argentum B1 Performance Baseline

Status: measurement/profiling only. No production optimization was implemented.

BASE_HEAD=ffcf897213f09932d30020f3c1df20b99f369b84
PARENT=ffcf897213f09932d30020f3c1df20b99f369b84
PROFILE_WORKLOAD=corpus8 baseline (primary); witness, normal4, replay2 baseline/replay also measured
EPISODES=8
TRANSITIONS=16000
SEMANTIC_DECISIONS=16000
EXTERNAL_TRANSITIONS=16000
ENGINE_PROGRESS=16000

WALL_TIME=96.943s median workload wall from two correct corpus8 runs (95.654s and 98.231s); latest pass 95.566s
EPISODES_PER_SECOND=0.082523 (median corpus8 wall)
TRANSITIONS_PER_SECOND=165.046 (median corpus8 wall)
SEMANTIC_DECISIONS_PER_SECOND=165.046 (median corpus8 wall)
EXTERNAL_TRANSITIONS_PER_SECOND=165.046 (median corpus8 wall)
ENGINE_PROGRESS_PER_SECOND=165.046 (median corpus8 wall)

CPU_PROFILE=232.250s test-JVM process CPU; 242.80% of one core / 15.18% of the 16-core host; JFR machine-total average 28.49%, maximum 75.06%
ALLOCATION_PROFILE=119,986,978,144 thread-allocated bytes; 7,499,186 bytes per external transition; JFR allocation samples led by byte[] 63.25%, LinkedHashMap.Entry 8.06%, String 4.89%, StringBuilder 3.76%
GC_PROFILE=575 MXBean collections / 1,218ms MXBean GC time; JFR 578 pauses / 1.23s pause time, P95 7.15ms, maximum 21.8ms
MEMORY_PROFILE=heap 15.7MB at start, 185.1MB at end, 386.8MB sampled peak
REPLAY_VERIFICATION_OVERHEAD=public semantic diagnostic: matching replay2 baseline 46.384s; capture+verify mode 95.405s; delta +49.021s; capture pass 58.775s; verify pass 36.514s; explicit canonicalization 15.368s capture + 14.484s verify
B0_SPECIFIC_OVERHEAD=HIGH_LIKELIHOOD but NOT_DIRECTLY_ISOLATED; exact B0 phase split is NOT_RUN
PRIMARY_BOTTLENECK=HOT_CPU transition-plus-public-observation path, led by ObservationCanonicalizer/StateDigest JSON materialization and legal-action enumeration
SECONDARY_BOTTLENECKS=ALLOCATION/GC JSON and collection pressure; activated/mana/cast domain enumeration; one-time ClassGraph registry setup; B0-specific replay/artifact verification
RECOMMENDED_OPTIMIZATION_ORDER=1 isolate B0 verification/artifact phases; 2 reduce digest canonicalization; 3 remove proven duplicate domain scans; 4 reduce allocation pressure; 5 measure cold registry reuse; 6 revisit policy sorting only if its share grows

## Executive result

The normal trusted strict path is dominated by the transition-plus-public-observation boundary, not by the external policy. In the latest primary corpus, that phase consumed 75.965s, or 79.489% of the 95.566s measured pass walltime. JFR attributes the largest application-side sample group to public semantic JSON canonicalization/digest construction, followed by legal-action and activated-ability enumeration.

The supplied B0-64 time is 1h41m49s (6,109s), or 95.453s per episode. A median linear projection of the measured corpus8 workload at the same fixed 2,000-transition horizon is 775.540s (12.93 minutes) for 64 episodes. The supplied B0 time is 7.877x that projection. This is not a proof of an exact B0 phase percentage because the B0-64 trace was not profiled in this checkout, but it is strong evidence that B0-specific verification/instrumentation or a materially different amount of work dominates the excess over ordinary trusted simulation.

The replay diagnostic demonstrates how large that class of overhead can be: two matching episodes take 46.384s without public replay capture; capture plus a second public semantic replay takes 95.405s. This diagnostic is not a trust-policy recommendation and is not the production CompactReplay implementation. The existing CompactReplay gate was executed separately and failed before completing because its bridge rejects the current ExplicitV3 payload with the message PaymentStrategy.ExplicitV3 requires CompactReplay v5 or newer; therefore actual ReplayFingerprint overhead is NOT_RUN.

## Workloads

All workloads used the locked Akiri/Chevill deck files, the existing deterministic external policy, Commander format, starting life 40, hand size 7, skip mulligans, no hand smoother, explicit starting-player index, the existing strict Gym service, and maxSteps=2,000. No card, deck, RNG, candidate, observation, privacy, replay, or semantic behavior was changed.

| Workload | Episodes | External transitions | Workload wall | Outcome |
|---|---:|---:|---:|---|
| witness: seed 0, Akiri seat 0, Akiri vs Chevill | 1 | 2,000 | 39.035s | truncated at 2,000 |
| normal4: seed 0, both starts, both roster orientations | 4 | 8,000 | 63.797s | all four truncated at 2,000 |
| corpus8: seeds 0..3, both starts, Akiri vs Chevill | 8 | 16,000 | 95.654s latest; 98.231s repeat | all eight truncated at 2,000 |
| replay2 baseline: seed 0 normal + seed 0 swapped/start 1 | 2 | 4,000 | 46.384s | both truncated at 2,000 |
| replay2 replay: same two episodes, capture + public semantic verify | 2 per pass | 4,000 per pass | 95.405s total | capture and verify PASS |

The first witness and normal4 runs are intentionally retained as representative small/normal workloads. The corpus8 result is the primary rate baseline because it amortizes one registry setup over 16,000 transitions.

## Primary timing attribution

Latest primary corpus8 baseline pass (95.566s):

| Phase | Time | Share of pass wall |
|---|---:|---:|
| Card registry / service setup | 17.474s | 18.285% |
| Episode create/reset plus opening observation | 0.565s | 0.591% |
| Deterministic external policy choice | 1.417s | 1.483% |
| Strict transition plus returned public observation | 75.965s | 79.489% |
| Diagnostics checks | 0.008s | 0.009% |
| Cleanup | 0.000s | 0.000% |
| Uninstrumented loop/measurement remainder | 0.137s | 0.143% |

The strict transition timer includes the existing candidate validation, one Rules transition through the strict Gym boundary, legal-action rebuilding, public projection, and the returned observation. The current boundary does not expose a production phase split between those operations; JFR supplies the method-level attribution below.

ENGINE_PROGRESS is defined here as the committed strict Rules transition count. In the current strict Gym path, one accepted external call reaches one GameEnvironment processAndCommit and increments the environment step count once, so ENGINE_PROGRESS and EXTERNAL_TRANSITIONS intentionally match; this is not an independent hidden-transition measurement.

The external policy is not the primary bottleneck in this workload. It consumed 1.417s while sorting and completing public choices, despite seeing 211,318 legal-action candidates across 16,008 observations.

## TOP_10_HOTSPOTS

The application counts below are JFR ExecutionSample stack-frame occurrences filtered to the Test worker in the final corpus8 recording (1,958 of 2,378 execution samples). They are sampling evidence, not direct self-time percentages. The standard JFR hot-method view also reported 58 samples (2.44%) for ObservationCanonicalizer.canonicalize.

| Rank | Application method | Test-worker stack frames | Classification |
|---:|---|---:|---|
| 1 | ObservationCanonicalizer.canonicalize (+ default) | 629 (479 + 150) | HOT_CPU; SERIALIZATION/DIGEST_COST; ALLOCATION/GC |
| 2 | LegalActionEnumerator.enumerate | 140 | HOT_CPU; REPEATED_REDUNDANT_WORK |
| 3 | GameEnvironment.legalActions | 118 | HOT_CPU; REPEATED_REDUNDANT_WORK |
| 4 | GameGymEnv.build | 71 | HOT_CPU; public-observation boundary |
| 5 | TriggerAbilityResolver.getTriggeredAbilities | 62 | HOT_CPU; Rules transition work |
| 6 | GameEnvironment.stepFromCandidateStrict | 57 | HOT_CPU; strict execution boundary |
| 7 | PredicateContext.<init> | 55 | ALLOCATION/GC; Rules predicate evaluation |
| 8 | ActivatedAbilityEnumerator.enumerate | 53 | HOT_CPU; domain enumeration |
| 9 | ObservationCanonicalizer semanticJson sortedBy comparator | 53 | SERIALIZATION/DIGEST_COST; ALLOCATION/GC |
| 10 | ActivatedAbilityEnumerator.enumerateOwnPermanents | 51 | HOT_CPU; domain enumeration |

The rank order between the last two entries is close and reflects different stack-frame counts from the same recording; both remain below the dominant canonicalization/enumeration groups.

Other measured application-side evidence:

- ActionProcessor.process: 9 test-worker stack frames; GameEnvironment.processAndCommit: 8; ActivateAbilityHandler.execute: 19.
- ManaSolver.canPay: 0 in the latest stack sample set; PaymentDomainBuilder.buildV5: 5; ObservationBuilder.paymentDomainV5For: 3; authorizePaymentManaProductionProfiles: 3.
- TargetValidator.validateObjectTarget / validatePermanentTarget: 4/4.
- ObservationBuilder.resolveActivatedAbility: 40; ObservationBuilder.legalActionToView: 12; ObservationBuilder.buildEntityFeatures: 30.
- JFR standard hot-method top entries were HashMap.getNode (247 samples, 10.39%), AbstractStringBuilder.ensureCapacityInternal (224, 9.42%), String.hashCode (194, 8.16%), HashMap.putVal (86, 3.62%), ArraysSupport.mismatch (79, 3.32%), joinTo (72, 3.03%), Arrays.copyOf (71, 2.99%), ClasspathElementZip.open (60, 2.52%), JSON canonicalization (58, 2.44%), and TimSort.binarySort (57, 2.40%).

These results classify the normal path as:

- HOT_CPU: public observation/canonicalization and legal-action/domain enumeration.
- ALLOCATION/GC: byte arrays, strings, builders, hash-map entries, JSON values, and PredicateContext objects.
- REPEATED_REDUNDANT_WORK: repeated legal-action and domain traversal at the strict/observation boundary; confirm exact duplicate call sites in the optimization phase before changing them.
- SERIALIZATION/DIGEST_COST: semantic JSON construction and SHA-256 input materialization used by the public state digest.
- REPLAY_ONLY_COST: the explicit second public semantic replay and per-frame comparison.
- B0_HARNESS_OVERHEAD: the large B0-64 delta and any B0 artifact/replay machinery not present in this profile.

## Allocation, memory, and GC

The primary corpus allocated about 120.0GB on the measured test worker over 16,000 transitions, despite only 1.23s of JFR GC pause time. This is allocation pressure rather than retained heap: the sampled heap peak was 386.8MB and GC was not the dominant normal-path wall cost.

The replay diagnostic increased sampled allocation to 41.482MB per transition versus 7.907MB per transition for the matching replay2 baseline, raised sampled heap peak from 298.6MB to 691.5MB, and raised MXBean GC time from 1,030ms to 3,074ms. That supports a replay/canonicalization allocation cost, not a proposal to weaken replay verification.

JSON report serialization and write time were measured outside the workload: the latest corpus8 run emitted approximately 18ms JSON serialization and 1ms JSON file write. JFR dump time was not separately timed. Generated JFR/JSON files remain build artifacts and are not part of the commit.

## CPU and harness/build separation

The native PowerShell Gradle command walltime includes compilation/configuration and test-worker startup; forced-rebuild invocations took roughly 2.5 to 3.5 minutes while the latest corpus8 workload took 95.654s. That difference is not game simulation. The primary performance numbers above therefore use the profiler's workload timer, while command walltime is retained as operational evidence.

The 17.474s corpus8 registry/service setup is a real cold-start cost in this test process. JFR includes ClassGraph/ZIP scanning and one-time concurrent-map/classpath activity in that period. It must not be mistaken for per-transition game cost. The production path still has a meaningful steady-state transition/observation cost after setup.

## Replay results

Public semantic replay diagnostic:

- replay2 baseline: 2 episodes, 4,000 transitions, 46.384s.
- replay2 replay mode: capture pass 2 episodes/4,000 transitions in 58.775s; public semantic verify pass 2 episodes/4,000 transitions in 36.514s; total 95.405s.
- Capture canonicalization: 15.368s.
- Verify canonicalization and digest comparison: 14.484s.
- Every captured public action was rebound by semantic key and ordinal and accepted through the same strict public API. The semantic frames and StateDigest values matched.

Actual CompactReplay/ReplayFingerprint diagnostic:

~~~powershell
$env:KOTEST_FILTER_TESTS = '*exact-pair replay gate replays complete semantic trajectories*'
.\gradlew.bat :gym:environmentV1AcceptanceTest --tests "com.wingedsheep.gym.EnvironmentV1ExactPairAcceptanceTest" --console=plain --rerun-tasks
~~~

Result: FAIL. The test executed 39 cases, with 38 skipped and the replay gate failing for both selected episodes before completion. Failure: PaymentStrategy.ExplicitV3 requires CompactReplay v5 or newer. No replay implementation was changed to work around this existing incompatibility. Actual ReplayFingerprint CPU/overhead is NOT_RUN.

## B0-64 comparison

The user-supplied B0-64 observation is:

~~~text
B0 episodes = 64
B0 walltime = 1h 41m 49s = 6,109s
B0 average walltime per episode = 95.453s
~~~

The fixed-horizon corpus8 baseline gives:

~~~text
corpus8 median linear 64-episode projection = 775.540s = 12.93m
B0 / corpus8 projection = 7.877x
~~~

The B0-specific conclusion is therefore:

B0_SPECIFIC_OVERHEAD=HIGH_LIKELIHOOD
B0_SPECIFIC_OVERHEAD_DIRECT_SPLIT=NOT_RUN
B0_NORMAL_SIMULATION_ALONE=INSUFFICIENT_TO_EXPLAIN_6,109s_UNDER_COMPARABLE_2,000_STEP_WORK

The evidence is consistent with B0-specific replay verification, artifact/instrumentation, or a different transition/episode workload dominating the 102-minute stage. It is not sufficient to assign the exact excess to one component without a B0-stage recording.

## Recommended optimization order (not implemented)

| Order | Candidate, measurement boundary | Expected impact | Complexity | Determinism risk | Rules risk | Privacy risk | Replay risk |
|---:|---|---|---|---|---|---|---|
| 1 | Isolate B0 harness replay/artifact/verification phases with a stage profile before changing production | High for B0 walltime; validates the 7.877x gap | Medium | Medium | Low | High | High |
| 2 | Reduce state/public digest canonicalization and JSON tree/string materialization at ObservationBuilder boundaries | High normal-path impact; strongest sampled subpath | Medium/High | Medium | Low | High | High |
| 3 | Remove proven duplicate legal-action/target/payment enumeration within one unchanged state generation | High normal-path impact | High | High | High | High | High |
| 4 | Reduce allocation pressure in canonicalizer, JSON serialization, maps, and builders while preserving byte-for-byte semantic output | Medium/High; should lower GC and memory pressure | Medium | Medium | Low | Medium | High |
| 5 | Reuse or prebuild the immutable card registry/classpath scan across cold starts, only after measuring deployment lifecycle | Medium cold-start impact; low steady-state impact | Medium | Medium | Low | Low | Medium |
| 6 | Reduce external-policy candidate sorting/canonicalization only if later measurements show it growing beyond the current 1.587% share | Low at this baseline | Low/Medium | Medium | Low | High | Medium |

All candidates require fresh deterministic replay, privacy, decision-completeness, Rules, and ZERO-UNSUPPORTED gates. No candidate is authorized by this report, and no optimization is present in this commit.

## Commands executed and results

Source/worktree:

- Exact origin and live remote main SHA checks: PASS; origin URL matched the required repository and remote/local main matched BASE_HEAD.
- Dedicated branch/worktree: PASS; chris/b1-performance-baseline at the required base before edits.
- just test-class B0HarnessTimeoutPolicyTest --console=plain: BLOCKED before Gradle by Windows WinError 193 when launching scripts/gradle-locked.
- Native fallback .\gradlew.bat :gym:test --tests "com.wingedsheep.gym.B0HarnessTimeoutPolicyTest" --console=plain: PASS (initial task result was from Gradle cache).
- Native fallback fresh combined ObservationPrivacyTest + StateDigestTest: PASS, 42 selected tests, 0 failures.
- Final native fallback fresh combined B0HarnessTimeoutPolicyTest + ObservationPrivacyTest + StateDigestTest + TargetPaymentDomainContractTest: PASS, 50 selected tests, 0 failures.
- Opt-in profiler without b1.profile: PASS; profiler test SKIPPED and emitted no workload artifact.
- Profiler witness baseline: PASS; 1/1 episode, 2,000 transitions.
- Profiler normal4 baseline: PASS; 4/4 episodes, 8,000 transitions.
- Profiler corpus8 baseline: PASS twice at the same head; latest 8/8 episodes, 16,000 transitions, 95.654s (repeat 98.231s).
- Profiler replay2 baseline: PASS; 2/2 episodes, 4,000 transitions.
- Profiler replay2 capture + public semantic verify: PASS; 2/2 episodes per pass, 4,000 transitions per pass, frame/digest equality.
- JFR summary/hot-method/allocation/gc-pauses/gc-cpu-time/cpu-load views: PASS on the final corpus8/replay recordings.
- JFR gc-heap view: NOT_RUN; this JDK recording has no view with that name. Equivalent gc and gc-pauses views were run successfully.
- Existing CompactReplay/ReplayFingerprint gate: FAIL at the existing ExplicitV3/v5 precondition; actual fingerprint overhead NOT_RUN.
- Intermediate profiler compile/loop attempts that exposed test-only harness defects (configuration syntax, duplicate helper name, episode-local counter, and action-key binding) were discarded; final numbers above come only from the corrected harness, with no production changes.
- Production source diff: PASS, none.
- Locked deck diff: PASS, none.
- Production optimizations: 0.
- DATA_TRUSTED=NO.
