# B1 Canonicalization Optimization — Task 6 Final Gate Report

Status: Task 6 executable gates completed. The optimization is accepted at the code-review level;
final trust is blocked only by the reproduced pre-existing CompactReplay version failure and
unestablished hosted CI.

```text
BASE=d0f4fe9eddb377fbddd7e675ec396ecbabe254d5
IMPLEMENTATION_PARENT=95ee0146c24688366a8f66e5b817752d8f6e6856
PRODUCTION_OPTIMIZATIONS=1
PRODUCTION_SEMANTIC_CHANGES=0
DIAGNOSTIC_PRODUCTION_HOOKS=NO
PROBE_DEFAULT_ENABLED=NO
TASK5=ACCEPTED
TASK6=EXECUTED_WITH_PREEXISTING_REPLAY_BLOCKER
TASK_4_EXACT_SHA=036205719a0ede84d7d9307a0bcca1c1b741fa4c
CODE_REVIEW_PASS=YES
HOSTED_CI_PASS=NOT_ESTABLISHED
COMPACT_REPLAY=FAIL
REPLAYFINGERPRINT_OVERHEAD=NOT_RUN
EXACT_PAIR_BASELINE=d0f4fe9eddb377fbddd7e675ec396ecbabe254d5:FAIL
EXACT_PAIR_TASK4=036205719a0ede84d7d9307a0bcca1c1b741fa4c:FAIL
PREEXISTING_REPLAY_BLOCKER=PROVEN
FINAL_TRUST_STATUS=BLOCKED_BY_PREEXISTING_REPLAY_VERSION
DATA_TRUSTED=NO
```

## RED → GREEN

The focused RED test ran before the production change with the real seed-0 Akiri/Chevill witness:

```text
n=26
f=26
m=182
result=FAIL (expected m=n contract)
```

After the Decorate-Sort change, the same test ran green:

```text
n=26
f=26
m=26
result=PASS
```

The additional Equal-Key contract test is also `PASS`. It uses two distinct JsonObject
fingerprints whose targetEntityIds arrays canonicalize to the same sort key and verifies that
forward and reverse input order are preserved by the decorated stable sort.

## Production change

ObservationCanonicalizer now materializes each legal-action semantic fingerprint's existing
canonical sort string once, sorts the pairs by that stored string, and projects the original
fingerprints back into the semantic JSON array. Serializer settings, semantic fields, array
normalization, stable equal-key order, legal-action membership, and StateDigest input remain
unchanged.

The Task 1–3 diagnostic production probe, all B1 characterization hooks, and the RED-only probe
test were removed before benchmarking. No ThreadLocal probe calls remain in the production
observation path.

## Matched benchmark

All runs use the existing locked Akiri/Chevill decks, deterministic external policy, strict Gym
path, and maxSteps=2,000. JFR remains enabled for the existing profiler, but the workload timer
and JVM after-snapshot stop before JFR stop/dump. These are matched Task 4 runs, not hosted CI
evidence. Hosted CI remains NOT_ESTABLISHED.

| Workload | Episodes | Transitions | Semantic decisions | Workload wall | Episodes/sec | Transitions/sec |
|---|---:|---:|---:|---:|---:|---:|
| witness | 1 | 2,000 | 2,000 | 37.851s | 0.026419 | 52.839 |
| normal4 | 4 | 8,000 | 8,000 | 63.359s | 0.063132 | 126.264 |
| corpus8 run 1 | 8 | 16,000 | 16,000 | 90.720s | 0.088183 | 176.432 |
| corpus8 run 2 | 8 | 16,000 | 16,000 | 90.803s | 0.088102 | 176.141 |
| corpus8 median | 8 | 16,000 | 16,000 | 90.761s | 0.088143 | 176.286 |
| replay2 baseline | 2 | 4,000 | 4,000 | 42.711s | 0.046826 | 93.652 |

The accepted pre-change corpus8 median was 96.943s (165.046 transitions/sec). The new median is
90.761s, a directional 6.377% wall-time reduction. This is not an isolated causal estimate,
because the pre-change report used the earlier snapshot boundary that included JFR stop/dump;
the corrected boundary is part of the comparison. The result is therefore evidence of a useful
candidate, not a claim of a 6.377% production speedup.

Probe-free corpus8 allocation was 5,117,326.887 and 5,122,519.7465 bytes per transition across
the two runs, median 5,119,923.31675. Do not compare this directly with the earlier 7,499,186
value without accounting for the accepted P3 snapshot-boundary correction.

## Verification

```text
RED before fix: PASS as a RED check; test failed with m=182, n=26
GREEN after fix: PASS; test passed with m=26, n=26
Equal-Key canonicalization test: PASS
ObservationCanonicalizationTest + StateDigestTest + profiler skip: PASS
Probe-free witness/normal4/corpus8/replay2 baseline: PASS
Task 5 cache characterization: PASS as a separate NOT_PROVEN relevance result
Task 6 focused public-contract gates: PASS, 72/72 tests
Task 6 payment-domain contract gates: PASS, 50/50 tests
Task 6 B0HarnessTimeoutPolicyTest: PASS, 3/3 tests
Task 6 exact-pair replay gate: FAIL, 1 failed and 38 skipped out of 39; ExplicitV3/v5 precondition
CompactReplay/ReplayFingerprint overhead: NOT_RUN after the exact-pair bridge failure
Exact-pair baseline reproduction on d0f4fe9e: FAIL with the identical ExplicitV3/v5 message
Hosted CI: NOT_ESTABLISHED
DATA_TRUSTED: NO
```

The Task 4 production commit is independently reviewed as CODE_REVIEW_PASS=YES. The exact-pair
gate failed identically on both the exact baseline d0f4fe9e... and the Task-4 head
036205719a..., proving the failure is pre-existing. It remains a separate replay/version blocker;
no replay contract was changed to bypass it. The B1 plan is complete at this authorization
boundary, but DATA_TRUSTED remains NO because Hosted CI is not established and the replay gate is
incomplete.
