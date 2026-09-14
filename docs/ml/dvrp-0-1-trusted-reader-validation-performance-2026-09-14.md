# DVRP-0/1 Trusted Reader Validation Performance

Date: 2026-09-14

This slice characterizes and removes redundant deep validation inside one trusted `DerivedArtifactReader` lifetime. It does not open the accepted C1_03 artifact, perform Teacher readmission, change a data contract, or add a persistent cache.

## Identity and scope

```text
TASK=C1_DVRP_0_1_TRUSTED_READER_REDUNDANT_VALIDATION_OPTIMIZATION
BASE_SHA=f71350737307381fd505275cfc08e5c809426408
IMPLEMENTATION_HEAD=51d1f006cc5db9bfa39c6893075a959d01d3ad68
ISSUE=188
ORIGIN_MAIN_AT_START=f71350737307381fd505275cfc08e5c809426408
UPSTREAM_MAIN_REFERENCE=3f46367d87c88bcf156a843a9e69fd29e1693872

FULL_ACCEPTED_8GB_ARTIFACT_OPENED=NO
FULL_ARTIFACT_RESCAN_STARTED=NO
ACCEPTED_ARTIFACT_RESCANNED=NO
TEACHER_READMISSION_RUN_STARTED=NO
```

The implementation commit is `51d1f006cc` (full SHA recorded above). The worktree branch also contains this evidence report as a later docs-only commit; the final branch SHA is reported in the handoff.

## DVRP-0 baseline

The current baseline lifecycle was:

```text
DerivedArtifactReader.open()
  -> manifest validation
  -> _validate_sample_file()
       -> parse every row
       -> canonicality and semantic validation every row
       -> whole-file digest/count validation
  -> seek(0)

iter_validated_samples_for_inference()
  -> _validate_sample_file() again
  -> seek(0)
  -> parse and deeply validate every row again
  -> issue ValidatedDerivedSample
```

On the bounded synthetic fixture, test-only counters measured:

```text
BENCHMARK_BYTES=2015926
BENCHMARK_SAMPLE_COUNT=512
BENCHMARK_REPETITIONS=3
PYTHON_VERSION=3.13.15
RUNTIME=Windows-11-10.0.26200-SP0 / AMD64 Family 25 Model 33 Stepping 2

BASELINE_FULL_FILE_DEEP_VALIDATION_PASSES=2
BASELINE_DEEP_VALIDATION_PASSES_PER_ROW=3
BASELINE_PARSE_CALLS=1536
BASELINE_DEEP_VALIDATION_CALLS=1536
BASELINE_FULL_BYTE_PASSES=2
```

The baseline timings were the mean of three fresh synthetic artifacts:

```text
BASELINE_OPEN_SECONDS=0.323347
BASELINE_CONSUMPTION_SECONDS=0.682171
BASELINE_TOTAL_SECONDS=1.005519
```

```text
DVRP_0_BASELINE_CHARACTERIZATION=PASS
REDUNDANT_FULL_DEEP_VALIDATION_CONFIRMED=YES
SAFE_SINGLE_LIFETIME_OPTIMIZATION_DESIGN_AVAILABLE=YES
```

The baseline RED tests showed the expected failures before implementation: the trusted inference path made two full validation calls, and a mutation of a not-yet-consumed future row was yielded instead of rejected.

## DVRP-1 optimized trust model

`DerivedArtifactReader.open()` still performs full strict validation. During that pass it now retains transient reader-lifetime state:

```text
validated whole-file SHA-256
validated byte count
validated sample count
one SHA-256 digest per validated raw NDJSON row
```

`iter_validated_samples_for_inference()` then performs:

1. a whole-file SHA-256/byte-count/sample-count preflight before the first token;
2. per-row SHA-256 comparison against the row fingerprint captured during strict open validation;
3. existing `_parse_sample_line()` parsing to recreate the sample representation; and
4. reader-issued `ValidatedDerivedSample` construction without repeating `_validate_sample()`.

The row fingerprint check detects changes to rows before they are consumed. A row already read into the stream buffer remains the exact bytes validated during open; subsequent rows are checked before issuance. The reader remains one-shot and closes after iteration.

```text
FULL_STRICT_VALIDATION_PRESERVED=YES
BYTE_IDENTITY_PROOF=WHOLE_FILE_SHA256_PREFLIGHT_PLUS_PER_ROW_SHA256_FINGERPRINTS
VALIDATED_SAMPLE_ISSUER_AUTHORITY_UNCHANGED=YES
ONE_SHOT_READER_LIFETIME_SEMANTICS_PRESERVED=YES
```

The public `iter_samples()` path was not broadened into the optimization. Its existing strict validation and close semantics remain intact; its second validation does not retain unused row fingerprints.

## GREEN correctness and mutation results

```text
MUTATION_BEFORE_ITERATION_REJECTED=YES
NO_TOKEN_BEFORE_PRE_ITERATION_MUTATION_REJECTION=YES
MUTATED_ROW_NOT_YIELDED=YES
TRUNCATION_REJECTED=YES
EXTRA_ROW_REJECTED=YES
SAMPLE_SEQUENCE_EQUIVALENT=YES
READER_CLOSE_SEMANTICS_UNCHANGED=YES
UNKNOWN/MALFORMED_REGRESSIONS=PASS
```

The focused suite covers the unchanged sequence, same-size pre-iteration mutation, future-row mutation, truncation, extra rows, one-shot closure, and the closed `ValidatedDerivedSample` constructor. Existing reader malformed-artifact coverage remains green.

## Optimized benchmark

The same bounded fixture and three-repetition method produced:

```text
OPTIMIZED_FULL_FILE_DEEP_VALIDATION_PASSES=1
OPTIMIZED_DEEP_VALIDATION_PASSES_PER_ROW=1
OPTIMIZED_PARSE_CALLS=1024
OPTIMIZED_DEEP_VALIDATION_CALLS=512
OPTIMIZED_FULL_BYTE_PASSES=2

OPTIMIZED_OPEN_SECONDS=0.345182
OPTIMIZED_CONSUMPTION_SECONDS=0.256366
OPTIMIZED_TOTAL_SECONDS=0.601548

SPEEDUP_TOTAL=1.67x
SPEEDUP_CONSUMPTION=2.66x
```

These are local bounded measurements, not universal hardware guarantees. Peak RSS was not measured. The transient validation state stores one 32-byte SHA-256 digest per validated row plus normal Python tuple/object overhead; no persistent state is written.

## Verification

```text
FOCUSED_READER_TESTS=7 tests, PASS
DERIVED_READER_TESTS=41 tests, PASS
FULL_ML_TESTS=167 tests, PASS
PYTHON_COMPILEALL=PASS
JUST_ML_TEST=PASS (167 tests)
JUST_ML_CHECK=PASS
```

## Scope closure

```text
PERSISTENT_ATTESTATION_ADDED=NO
ARROW_ADOPTED=NO
PARQUET_ADOPTED=NO
BINARY_CACHE_ADDED=NO
SHARDING_ADDED=NO

TRAJECTORY_V1_CHANGED=NO
MODEL_FACING_SCHEMA_CHANGED=NO
TEACHER_CHANGED=NO
C1_03B_CHANGED=NO
DATASET_CHANGED=NO
DERIVED_ARTIFACT_CHANGED=NO
TRAINING_CODE_ADDED=NO
LABELS_CREATED=NO

TEACHER_READMISSION_AUTHORIZED=NO
BOOTSTRAP_LABEL_MATERIALIZER_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
C1_04_AUTHORIZED=NO
```

No accepted 8-GB artifact was opened or rematerialized. This slice does not claim any result for historical C1_03 ownership counts after the fix.

## Self-review and handoff state

```text
P1=0
P2=0
P3=0
LOCAL_SELF_REVIEW=PASS
DVRP_0_1_CODE_REVIEW_PASS=NO
DVRP_0_1_FINAL_ACCEPTANCE_PASS=NO
PR_CREATED=NO
NEXT_TASK_STARTED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
```

The remaining `NO` values mean external exact-SHA review and acceptance are pending; they are not test failures.
