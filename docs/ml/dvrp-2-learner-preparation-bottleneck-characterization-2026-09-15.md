# DVRP-2 learner preparation bottleneck characterization

```text
TASK=DVRP_2_EXACT_LEARNER_PREPARATION_BOTTLENECK_CHARACTERIZATION
BASE_ORIGIN_MAIN="03bce91dea0502d74c7af2a1bab5acc2e43f1fa2"
UPSTREAM_MAIN="3f46367d87c88bcf156a843a9e69fd29e1693872"
MEASUREMENT_SOURCE_COMMIT="0a27d33885262eb1c53dca759f7909f9a7b99697"
ISSUE_188=OPEN
DVRP_0_1_FINAL_ACCEPTANCE_PASS=YES
C1_05_FINAL_ACCEPTANCE_PASS=YES
C1_06_FINAL_ACCEPTANCE_PASS=YES
```

## 1. Accepted artifacts and run boundary

```text
SOURCE_DERIVED_ARTIFACT_ID=be545edcb7809a34d78ab9be03476b3cd29ab42e54e6c7f7e20b25b5bcc05a4a
LABEL_ARTIFACT_ID=22c6d18fa05301010b7057a4f524ef689a89626aa7dfb7582d73572a4f0f1c52
LABELS_CONTENT_DIGEST=5f683753dd01f8a7e20c6b6b2ef31c38e6bb4949cac116a49c7ccfbb577f3a24
MANIFEST_CONTENT_DIGEST=8bbd0060ca18108eb48ca3954e913743cc27fdd062ba78d4228db76a0b448d2d
SOURCE_DATASET_ID=69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03
SOURCE_MANIFEST_CONTENT_DIGEST=de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2
SOURCE_PATH=C:\Users\chris\AppData\Local\Temp\argentum-c1-03-derived-20260913-4eb71de7893395c4abc965d3ce705d623bca8a92
LABEL_PATH=C:\Users\chris\AppData\Local\Temp\argentum-c1-05-labels-46766034ade68e82a598203de8e35da372acdb34
SOURCE_ARTIFACT_BYTES=8083329687
LABEL_ARTIFACT_BYTES=181808138
SOURCE_ROWS=125471
LABEL_ROWS=59211
C1_05_FULL_MATERIALIZATION_RUNS=0
C1_05_TEACHER_RUNS=0
NEW_LABELS_GENERATED=0
NEW_TRAJECTORIES_GENERATED=0
REAL_FULL_ARTIFACT_CHARACTERIZATION_RUNS=1
SYNTHETIC_FIXTURE_CHARACTERIZATION_RUNS=0
```

The manifest-only preflight checked the recorded identities and file metadata before opening either
reader. The measured run then called the production `load_c1_05_training_view` exactly once. It did
not recalculate a content digest in the preflight; the strict production readers performed their
normal digest and semantic checks inside the measured stages.

## 2. Environment and limits

```text
OS=Windows-11-10.0.26200-SP0
PYTHON_VERSION=3.13.15
PYTHON_EXECUTABLE=C:\Python313\python.exe
STORAGE_LOCATION=C:\
MEMORY_PROFILE=NOT_MEASURED
TRAIN_LIMIT=2048
VALIDATION_LIMIT=512
TEST_LIMIT=0
TENSORIZATION_DEVICE="cuda:0"
TORCH_ENVIRONMENT={"cuda_available": true, "cuda_device_capability": [8, 9], "cuda_device_count": 1, "cuda_device_name": "NVIDIA GeForce RTX 4060 Ti", "tensorization_device": "cuda:0", "torch_available": true, "torch_cuda_version": "13.0", "torch_version": "2.14.0+cu130"}
```

No RSS/working-set measurement was added. `tracemalloc` was not substituted for process RSS.

## 3. Exact current call graph

```text
load_c1_05_training_view(...)
  -> LabelArtifactReader._open_structural(label_root)
       -> read manifest.json; parse/canonicalize/validate manifest
       -> read labels.ndjson; verify SHA-256/byte count
       -> parse/canonicalize/validate every label row; retain tuple
  -> tuple(label_reader.iter_labels())
       -> iterate the already parsed in-memory tuple
  -> select_bounded_label_rows(...)
       -> filter TRAIN and VALIDATION; sort each partition by source key; slice limits
  -> DerivedArtifactReader.open(source_artifact_root)
       -> read/canonicalize/validate manifest and identity
       -> strict full samples.ndjson pass: parse, canonicalize, semantic validate, digest, counts
       -> retain reader-lifetime row digests and rewind
  -> source_reader.iter_validated_samples_for_inference()
       -> full-file digest/byte/count preflight
       -> second source pass: per-row digest comparison and canonical JSON parse
       -> yield reader-issued ValidatedDerivedSample tokens
  -> for each yielded source row
       -> source-key extraction and dict pop against selected labels
       -> source_label_to_training_sample(...) only on a selected match
       -> append the typed C1_06 sample
  -> tensorize_samples(...) for bounded TRAIN+VALIDATION batches when enabled
```

The harness patched only these call boundaries to time them. It did not copy `_validate_sample`,
`_validate_row`, canonical JSON, digest, membership, or binding logic.

## 4. Measurement methodology and pass accounting

The timing source was `time.perf_counter()` for wall time and `time.process_time()` for process CPU
time. Each stage was measured once in the one real run; the synthetic fixture test was used only to
exercise the probes before the real run. CUDA tensorization synchronized `cuda:0` around each
`tensorize_samples` call. The accepted C1_06 optimizer timing was reused and not rerun.

| Path | Raw/file pass | JSON parse pass | Semantic validation pass | Selected rows consumed |
|---|---:|---:|---:|---:|
| labels structural open | 1 labels byte read | 1 label-row parse pass | 1 label-row validation pass | no |
| labels tuple iteration | no file read; in-memory tuple | 0 | 0 | no |
| source reader open | 1 full `samples.ndjson` pass | 1 source-row parse pass | 1 source-row validation pass | no |
| source inference iteration | 2 full passes: integrity preflight + token consumption | 1 source-row parse pass | 0 | selected TRAIN/VALIDATION matches only |

Therefore `SOURCE_RAW_IO_PASSES=3` means three sequential Python file-stream passes over the source
file in this reader lifetime. It is not a claim that the operating system performed three physical
storage reads; cache effects are not separately measured. `SOURCE_SEMANTIC_VALIDATION_PASSES=1`
remains distinct from raw I/O passes.

## 5. Stage timing

```text
TOTAL_PREPARATION_WALL_SECONDS=3525.3979822999972
TOTAL_PREPARATION_CPU_SECONDS=3452.59375
LABEL_STRUCTURAL_OPEN_WALL_SECONDS=40.21010729999398
LABEL_STRUCTURAL_OPEN_CPU_SECONDS=39.640625
LABEL_ITERATION_WALL_SECONDS=0.012929699994856492
LABEL_ITERATION_CPU_SECONDS=0.015625
LABEL_ROWS_VISITED=59211
BOUNDED_LABEL_SELECTION_WALL_SECONDS=0.4255719000066165
BOUNDED_LABEL_SELECTION_CPU_SECONDS=0.390625
LABEL_ROWS_SELECTED_TRAIN=2048
LABEL_ROWS_SELECTED_VALIDATION=512
SOURCE_READER_OPEN_WALL_SECONDS=1991.982284800004
SOURCE_READER_OPEN_CPU_SECONDS=1962.140625
SOURCE_MANIFEST_VALIDATION_WALL_SECONDS=0.00017240000306628644
SOURCE_STRICT_SAMPLE_FILE_VALIDATION_WALL_SECONDS=1991.9812475000217
SOURCE_OPEN_JSON_CANONICAL_PARSE_WALL_SECONDS=1193.4951416969998
SOURCE_OPEN_SEMANTIC_VALIDATION_WALL_SECONDS=739.5250074006326
SOURCE_ITERATION_WALL_SECONDS=1446.6978596975678
SOURCE_ITERATION_CPU_SECONDS=1408.828125
SOURCE_WHOLE_FILE_INTEGRITY_PREFLIGHT_WALL_SECONDS=13.040195800014772
SOURCE_ITERATION_JSON_CANONICAL_PARSE_WALL_SECONDS=1240.2142020988977
SOURCE_ROWS_VISITED=125471
SOURCE_BYTES_IF_RELIABLY_AVAILABLE=8083329687
SOURCE_ITERATION_RAW_BYTES_VISITED=16166659374
SOURCE_TOTAL_RAW_BYTES_VISITED=24249989061
SOURCE_RAW_IO_PASSES=3
SOURCE_JSON_PARSE_PASSES=2
SOURCE_SEMANTIC_VALIDATION_PASSES=1
SOURCE_ROW_ITERATION_PASSES=3
SOURCE_KEY_EXTRACTION_WALL_SECONDS=1.1830651820637286
HASH_DICT_LOOKUP_DERIVED_WALL_SECONDS=23.731873216078384
SOURCE_LABEL_JOIN_WALL_SECONDS=25.03602990930085
JOIN_SAMPLE_CONSTRUCTION_WALL_SECONDS=25.03602990930085
TRAINING_SAMPLE_CONSTRUCTION_WALL_SECONDS=0.38860510001541115
TRAIN_SAMPLES_CREATED=2048
VALIDATION_SAMPLES_CREATED=512
TENSORIZE_WALL_SECONDS=15.23764999996638
TENSORIZATION_WALL_SECONDS=15.23764999996638
BATCH_CONSTRUCTION_WALL_SECONDS=15.23764999996638
HOST_TO_DEVICE_PREPARATION=INCLUDED_IN_TENSORIZE_SAME_CALL
TENSORIZED_BATCHES=40
TENSORIZED_SAMPLES=2560
```

| Stage boundary | Wall seconds | Process CPU seconds | Share of preparation + GPU reference |
|---|---:|---:|---:|
| label preparation (open + tuple iteration + bounded selection) | 40.64860889999545 | 40.046875 | 1.140315610744516% |
| source reader open (strict trust establishment) | 1991.982284800004 | 1962.140625 | 55.881088114782386% |
| source inference iteration (integrity preflight + token parse) | 1446.6978596975678 | 1408.828125 | 40.58422165202315% |
| source-label join + sample construction consumer loop | 25.03602990930085 | 23.34375 | 0.7023358611577374% |
| tensorization / batch construction | 15.23764999996638 | 14.953125 | 0.4274618649009851% |
| accepted C1_06 GPU optimizer reference | 39.282577800011495 | NOT_MEASURED | 1.1019943340704113% |

The preparation CPU-to-wall ratio was `0.9793486486729934`;
the source-open ratio was `0.9850191138607439` and the source-
iteration ratio was `0.9738233284554078`. This is consistent
with a CPU-bound parse/validation path rather than a primarily I/O-waiting path on this host.

`JOIN_SAMPLE_CONSTRUCTION_WALL_SECONDS` is the non-overlapping consumer gap between successive
reader `next()` calls; it includes the outer source-key lookup, dict pop, selected-row conversion,
and append. The per-function `TRAINING_SAMPLE_CONSTRUCTION_*` value is nested within that stage and
is not added again. The dict-lookup value is a derived residual after subtracting the measured outer
key extraction and sample-construction calls; it includes Python loop overhead and is not a hardware-
independent microbenchmark.

The label structural-open timing is intentionally a combined boundary: the current private
`LabelArtifactReader._open_structural()` performs manifest parsing/validation, labels byte digest,
label-row parsing, and label-row semantic validation in one call. Splitting those subcategories would
require another real artifact pass or a production hook; this task does neither.

## 6. Join, bounded distribution, and last-needed position

```text
SELECTED_LABELS_REQUESTED=2560
SELECTED_LABELS_MATCHED=2560
SELECTED_LABELS_MISSING=0
LAST_SELECTED_LABEL_SOURCE_ROW_INDEX_1_BASED=105463
ROWS_SCANNED_AFTER_LAST_NEEDED_MATCH=20008
SELECTED_LABEL_SOURCE_ROW_POSITION_MIN=29288
SELECTED_LABEL_SOURCE_ROW_POSITION_P50=52354
SELECTED_LABEL_SOURCE_ROW_POSITION_P90=104553
SELECTED_LABEL_SOURCE_ROW_POSITION_MAX=105463
SOURCE_LABEL_JOIN_MATCHES_TRAIN=2048
SOURCE_LABEL_JOIN_MATCHES_VALIDATION=512
TEST_ROWS_CONSUMED=0
TEST_LABELS_CONSUMED=0
```

Selected-label distribution (the JSON sidecar contains the same object):

```json
{
  "by_candidate_count_bucket": {
    "1": 53,
    "17+": 142,
    "2": 136,
    "3-4": 248,
    "5-8": 1171,
    "9-16": 810
  },
  "by_decision_family": {
    "ACTION_CANDIDATES": 2468,
    "FOLDED_DECISION_OPTIONS": 92
  },
  "by_partition": {
    "TRAIN": 2048,
    "VALIDATION": 512
  },
  "source_row_position": {
    "max": 105463,
    "min": 29288,
    "p50": 52354,
    "p90": 104553
  }
}
```

The source iterator intentionally did not stop after the last match. `ROWS_SCANNED_AFTER_LAST_NEEDED_MATCH`
is evidence for a possible early-termination follow-up, not an authorization to implement it here.

## 7. Derived metrics and wall-clock attribution

```text
SOURCE_ROWS_PER_SECOND=86.72923593474432
SOURCE_MB_PER_SECOND=10.657185758396864
REQUESTED_SAMPLE_RATIO=0.020403121039921576
SOURCE_ROWS_VISITED_PER_SELECTED_SAMPLE=49.012109375
PERCENT_SOURCE_ROWS_VISITED=100.0
PERCENT_ROWS_AFTER_LAST_NEEDED_MATCH=15.946314287763707
TRUST_ESTABLISHMENT_WALL_SECONDS=2032.192392099998
HOT_CONSUMPTION_WALL_SECONDS=1446.7107893975626
JOIN_COST_WALL_SECONDS=25.03602990930085
TENSORIZATION_COST_WALL_SECONDS=15.23764999996638
GPU_TRAINING_COST_WALL_SECONDS=39.282577800011495
MEASURED_PIPELINE_SHARE_DENOMINATOR_SECONDS=3564.6805601000087
LABEL_PREP_PERCENT=1.140315610744516
SOURCE_OPEN_PERCENT=55.881088114782386
SOURCE_ITERATION_PERCENT=40.58422165202315
JOIN_SAMPLE_BUILD_PERCENT=0.7023358611577374
TENSORIZATION_PERCENT=0.4274618649009851
GPU_OPTIMIZER_REFERENCE_PERCENT=1.1019943340704113
```

The share denominator is measured preparation wall plus the accepted 39.282577800011495-second GPU
reference. The GPU number is a separately accepted C1_06 measurement, not a concurrent stage in this
run. The dominant stage by this same share comparison is:

```text
DOMINANT_STAGE=SOURCE_READER_OPEN
DOMINANT_STAGE_PERCENT=55.881088114782386
```

## 8. Accepted C1_06 GPU reference

```text
ACCEPTED_C1_06_GPU_REFERENCE_WALL_SECONDS=39.282577800011495
GPU_OPTIMIZER_REFERENCE_REEXECUTED=NO
GPU_PRIMARY_BOTTLENECK=NO
TRAINING_EXAMPLES_PROCESSED=6400
DECISIONS_PER_SECOND=162.92209825390142
```

The reference is the accepted C1_06 bounded CUDA smoke: 2,048 TRAIN decisions, 512 VALIDATION
decisions, zero TEST consumption, and 6,400 actual optimizer examples. No checkpoint or training
experiment was created by DVRP-2.

## 9. Bounded diagnostic scaling projections

These projections assume the same accepted artifact shape and bytes/row, linear full-file scans, the
same storage/runtime, and fixed learner limits of TRAIN=2048 plus VALIDATION=512. Source and label
artifacts are projected to grow proportionally with episode count; selected-view join/tensorization
terms are held fixed only because the limits are held fixed. Last-match position, cache state, parser
behavior, and storage contention are not projected.

```json
[
  {
    "assumption": "LINEAR_SCAN_PROJECTION; fixed TRAIN=2048, VALIDATION=512 view; same bytes/row and throughput",
    "bounded_selection_wall_seconds_linear": 0.4255719000066165,
    "episodes": 64,
    "fixed_2560_join_tensorize_wall_seconds": 40.27367990926723,
    "label_rows_linear": 59211,
    "label_structural_open_wall_seconds_linear": 40.21010729999398,
    "source_bytes_linear": 8083329687,
    "source_iteration_wall_seconds_linear": 1446.6978596975678,
    "source_reader_open_wall_seconds_linear": 1991.982284800004,
    "source_rows_linear": 125471
  },
  {
    "assumption": "LINEAR_SCAN_PROJECTION; fixed TRAIN=2048, VALIDATION=512 view; same bytes/row and throughput",
    "bounded_selection_wall_seconds_linear": 1.702287600026466,
    "episodes": 256,
    "fixed_2560_join_tensorize_wall_seconds": 40.27367990926723,
    "label_rows_linear": 236844,
    "label_structural_open_wall_seconds_linear": 160.84042919997592,
    "source_bytes_linear": 32333318748,
    "source_iteration_wall_seconds_linear": 5786.791438790271,
    "source_reader_open_wall_seconds_linear": 7967.929139200016,
    "source_rows_linear": 501884
  },
  {
    "assumption": "LINEAR_SCAN_PROJECTION; fixed TRAIN=2048, VALIDATION=512 view; same bytes/row and throughput",
    "bounded_selection_wall_seconds_linear": 3.404575200052932,
    "episodes": 512,
    "fixed_2560_join_tensorize_wall_seconds": 40.27367990926723,
    "label_rows_linear": 473688,
    "label_structural_open_wall_seconds_linear": 321.68085839995183,
    "source_bytes_linear": 64666637496,
    "source_iteration_wall_seconds_linear": 11573.582877580542,
    "source_reader_open_wall_seconds_linear": 15935.858278400032,
    "source_rows_linear": 1003768
  }
]
```

`PROJECTION != TARGET` and `PROJECTION != AUTHORIZATION`. No 256/512-episode artifact was generated or
opened.

## 10. Candidate next-step comparison

| Option | Measured bottleneck addressed | Trust/semantic risk | Complexity | Recurrent-training reuse | Larger-corpus relevance | Authoritative source format change |
|---|---|---|---|---|---|---|
| A. verified-artifact attestation / repeat-open cache | repeated source/label trust establishment across learner lifetimes | high: preserve identity, bytes, schema, and trust lifetime | medium-high | high | high | no, if additive |
| B. early termination after requested keys | rows after the last needed selected match | medium-high: prove completeness/absence and preserve fail-closed semantics | low-medium | high | medium | no |
| C. deterministic source-key index / bounded lookup | source full scan and join lookup | high: index completeness and digest binding | medium-high | high | high | no, additive index |
| D. shard-level validation / source sharding | source open and whole-file validation scale | high: shard completeness/order/identity contract | high | high | high | yes/additive shard contract |
| E. disposable deterministic learner cache | repeated join/tensorization for an unchanged bounded view | medium: cache must be disposable and identity-bound | medium | high | medium | no |
| F. Arrow / Parquet | parse/row materialization and column access | high: new physical schema and trust boundary | high | medium-high | high | yes |
| G. custom binary | parse and I/O throughput | very high: new canonical bytes and reader authority | high | medium | high | yes |
| H. no optimization yet | none | lowest | none | low | low | no |

The recommended slice is:

```text
NEXT_RECOMMENDED_SLICE=DVRP_2A_REPEAT_OPEN_TRUST_ATTESTATION_CHARACTERIZATION
```

It is selected from the measured last-needed position and stage dominance. It is a characterization or
design slice only; it does not authorize changing the reader, adding a cache, changing a format, or
changing trust semantics.

## 11. Trust, privacy, and regression review

```text
FULL_STRICT_VALIDATION_EXISTS=YES
DVRP_0_1_FULL_STRICT_VALIDATION_PRESERVED=YES
SOURCE_AUTHORITY_UNCHANGED=YES
DERIVED_ARTIFACT_ID_UNCHANGED=YES
LABEL_ARTIFACT_ID_UNCHANGED=YES
NO_CANDIDATE_TRUNCATION=YES
NO_PRIVACY_DRIFT=YES
NO_BINDING_RECONSTRUCTION=YES
SAMPLE_ORDER_CONTENT_UNCHANGED=YES
OPTIMIZATION_IMPLEMENTED=NO
ARROW_ADOPTED=NO
PARQUET_ADOPTED=NO
CUSTOM_BINARY_ADOPTED=NO
TRAJECTORY_V1_CHANGED=NO
TRUST_SEMANTICS_CHANGED=NO
```

The probes observe existing `DerivedArtifactReader` tokens and the existing source-label adapter.
They do not reconstruct bindings, change labels, expose raw entity identities as features, or skip
validation. The synthetic test exercises output counts and the zero-TEST boundary before the real
artifact run.

## 12. Verification and raw commands/results

The real run was launched only after the implementation harness was committed and the following
identity block was printed and matched:

```text
SOURCE_DERIVED_ARTIFACT_ID=be545edcb7809a34d78ab9be03476b3cd29ab42e54e6c7f7e20b25b5bcc05a4a
LABEL_ARTIFACT_ID=22c6d18fa05301010b7057a4f524ef689a89626aa7dfb7582d73572a4f0f1c52
LABELS_CONTENT_DIGEST=5f683753dd01f8a7e20c6b6b2ef31c38e6bb4949cac116a49c7ccfbb577f3a24
MANIFEST_CONTENT_DIGEST=8bbd0060ca18108eb48ca3954e913743cc27fdd062ba78d4228db76a0b448d2d
SOURCE_PATH=C:\Users\chris\AppData\Local\Temp\argentum-c1-03-derived-20260913-4eb71de7893395c4abc965d3ce705d623bca8a92
LABEL_PATH=C:\Users\chris\AppData\Local\Temp\argentum-c1-05-labels-46766034ade68e82a598203de8e35da372acdb34
TRAIN_LIMIT=2048
VALIDATION_LIMIT=512
TEST_LIMIT=0
```

```powershell
git fetch origin
git fetch upstream
C:/Python313/python.exe -m unittest tests.test_dvrp_2_characterization -v
C:/Python313/python.exe -m argentum_ml.characterization.dvrp_2 --source-artifact-root "C:\Users\chris\AppData\Local\Temp\argentum-c1-03-derived-20260913-4eb71de7893395c4abc965d3ce705d623bca8a92" --label-root "C:\Users\chris\AppData\Local\Temp\argentum-c1-05-labels-46766034ade68e82a598203de8e35da372acdb34" --report-out "docs/ml/dvrp-2-learner-preparation-bottleneck-characterization-2026-09-15.md" --json-out "docs/ml/dvrp-2-learner-preparation-bottleneck-characterization-2026-09-15.json"
just ml-test
just ml-check
git diff --check
```

The generated JSON sidecar is the machine-readable raw result for every numeric value in this report.
The report was generated from that same in-memory result, so the stage numbers and derived metrics are
not independently retyped.

```text
FOCUSED_TESTS=PASS_3_OF_3
FULL_ML_TESTS=PASS_225_OF_225
ML_CHECK=PASS
P1=0
P2=0
P3=0
CHARACTERIZATION_PASS=YES
PR_CREATED=NO
NEXT_TASK_STARTED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
```

