# DVRP-2A repeat-open trust attestation characterization

```text
TASK=DVRP_2A_REPEAT_OPEN_TRUST_ATTESTATION_CHARACTERIZATION
DATE=2026-09-15
BASE_ORIGIN_MAIN=03bce91dea0502d74c7af2a1bab5acc2e43f1fa2
UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872
AUDIT_BASE_HEAD=03bce91dea0502d74c7af2a1bab5acc2e43f1fa2
DVRP_2_REVIEWED_HEAD=62539d1a6c79909192121c3c3a32552dff83593b
ISSUE_188=OPEN
```

## Executive conclusion

```text
REPEAT_OPEN_ATTESTATION_FEASIBLE=CONDITIONAL
SMALL_ATTESTATION_ALONE_PROVES_UNCHANGED_BYTES=NO
TRUSTED_IMMUTABILITY_PRIMITIVE_REQUIRED=YES
REPEAT_FULL_VALIDATION_REMAINS_REQUIRED=YES_UNLESS_IMMUTABLE_STORE_IS_AUTHORIZED
```

The answer to the required authority question is:

> No. A small attestation cannot prove that unchanged 8-GB content is still present after a
> process restart without either rereading/authenticating the content or relying on an independently
> trusted immutability primitive.

The current reader has a strong content-authenticated open, but its trust state is transient. A
second reader in the same process and a new process both repeat the full strict source validation.
The smallest safe follow-up is therefore a design for a content-addressed immutable artifact store
and its publication/retention authority. This report does not implement that store or an attestation
cache.

## 1. Exact repository state and accepted predecessor

```text
BASE_ORIGIN_MAIN=03bce91dea0502d74c7af2a1bab5acc2e43f1fa2
UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872
START_BRANCH=chris/dvrp-2a-repeat-open-attestation-characterization-20260915
START_HEAD=03bce91dea0502d74c7af2a1bab5acc2e43f1fa2
DVRP_2_FINAL_ACCEPTANCE_PASS=YES
DVRP_2_REVIEWED_HEAD=62539d1a6c79909192121c3c3a32552dff83593b
ISSUE_188=OPEN
```

The working tree started from the current writable fork `origin/main`. The root checkout's unrelated
changes were not touched. DVRP-2 was used as accepted measurement evidence; its 8-GB artifact was
not opened again by this task.

## 2. DVRP-2 accepted measurement evidence reused here

```text
SOURCE_DERIVED_ARTIFACT_ID=be545edcb7809a34d78ab9be03476b3cd29ab42e54e6c7f7e20b25b5bcc05a4a
LABEL_ARTIFACT_ID=22c6d18fa05301010b7057a4f524ef689a89626aa7dfb7582d73572a4f0f1c52
SOURCE_ARTIFACT_BYTES=8083329687
TOTAL_PREPARATION_WALL_SECONDS=3525.3979822999972
SOURCE_READER_OPEN_WALL_SECONDS=1991.982284800004
SOURCE_ITERATION_WALL_SECONDS=1446.6978596975678
GPU_OPTIMIZER_REFERENCE_WALL_SECONDS=39.282577800011495
GPU_PRIMARY_BOTTLENECK=NO
```

The accepted DVRP-2 run measured the strict source-reader open at approximately 33.20 minutes and
55.88% of the preparation-plus-GPU comparison denominator. It measured source inference iteration
at approximately 24.11 minutes. `NEW_REAL_FULL_ARTIFACT_SCANS=0` for DVRP-2A.

The theoretical maximum repeat-open saving used below is the accepted
`SOURCE_READER_OPEN_WALL_SECONDS`. It is not a measured fast-path result.

## 3. Current reader trust graph

The audited path is the production `DerivedArtifactReader` in
`ml/src/argentum_ml/data/derived_reader.py`:

```text
DerivedArtifactReader.open(root)                         [line 278]
  -> require real root and regular manifest/samples files [280-293]
  -> read manifest bytes; reject framing/noncanonical JSON [284-290]
  -> validate exact manifest/version/schema/identity fields [389-439]
  -> deep-freeze the in-memory manifest                    [291]
  -> _validate_sample_file(stream, manifest)               [295, 456-501]
       -> parse and canonicalize every sample row           [470-472]
       -> semantic/deep sample validation                   [472]
       -> duplicate reference, byte/count/partition checks  [474-495]
       -> whole samplesContentDigest                        [478-490]
       -> capture one SHA-256 row digest per row             [479-500]
  -> rewind the open stream and retain reader state         [296-301]

iter_validated_samples_for_inference()                    [326-353]
  -> _verify_validated_sample_file()                       [332, 504-521]
       -> full raw stream digest/byte/count preflight        [508-521]
  -> rewind and stream every row                            [333-350]
  -> compare each raw row with retained row_digests         [338-342]
  -> parse/canonicalize the row, but do not deep-validate    [343]
  -> issue a reader-authorized ValidatedDerivedSample token  [345-348]
  -> close the one-shot reader                             [352-364]
```

`open()` proves, for the current open operation:

- the root, manifest, and sample file are real/non-symlink paths at the checks;
- the manifest has the exact known V1 fields, identities, schema, and canonical bytes;
- `samples.ndjson` has the manifest-declared byte count, row count, partition counts, and
  `samplesContentDigest`;
- every source row is canonical JSON and passes the complete current semantic validation;
- the current reader lifetime has a whole-file digest, count, and one raw-row digest per row;
- the stream being consumed is the stream opened by this reader.

Facts that exist only in process memory are `_ValidatedSampleFileState` at lines 261-265, including
`samples_content_digest`, `samples_byte_count`, `sample_count`, and `row_digests`, plus the open
file handle and the deeply frozen manifest. No attestation is persisted, no expected external
artifact ID is accepted by `DerivedArtifactReader.open(root)`, and no Python validator contract or
validator source commit is present in the derived manifest. The manifest's
`materializerImplementationIdentity` identifies the producer, not the reader implementation.

## 4. Same-process and cross-process distinction

| Case | Characterized behavior | Safe fast reuse status |
|---|---|---|
| A. Same open reader / same handle | Existing DVRP-0/1 state protects the one-shot inference lifetime; the inference path still performs its whole-file preflight and per-row checks. | `EXISTING_HANDLE_ONLY; ONE_SHOT` |
| B. Second reader in same process | Fixture counted two `_validate_sample_file()` calls for two `open()` calls. | `NO_SECOND_READER_REPEATS_FULL_VALIDATION` |
| C. New process, same path | Child-process fixture counted `FULL_VALIDATION_CALLS=1`; in-memory state is gone. | `NO_PERSISTED_TRUST_STATE` |
| D. Byte-identical copy to another path | Copy has the same content-derived identity and reopens successfully. | `CONTENT_PASS; PATH_IS_NOT_AUTHORITY` |
| E. Content-addressed immutable store | Not present in this repository/run. It is the condition under which an additive attestation could be considered. | `CONDITIONAL_DESIGN_ONLY` |

The same-process result must not be generalized into a process-restart result. A file handle and
`row_digests` cannot cross a process boundary through the current reader API.

## 5. Mutation and substitution threat matrix

The classifications below distinguish content rehash, filesystem metadata, and the absence of an
external expected identity. `PLATFORM_DEPENDENT` is used where Windows/Linux behavior or permissions
change the result.

| Mutation/substitution | Current reader observation | Classification |
|---|---|---|
| Same path, modified bytes | Existing manifest's `samplesContentDigest` fails during `open()`. | `DETECTED_BY_REHASH` |
| Same path, same-size modified bytes | Digest fails even when size is unchanged; fixture passes. | `DETECTED_BY_REHASH` |
| Same-size mutation after `open()` | `_verify_validated_sample_file()` fails before the first token; fixture passes. | `DETECTED_BY_REHASH` |
| Truncated file | Byte/count/digest or framing validation fails; fixture passes. | `DETECTED_BY_REHASH` |
| Appended rows/bytes | Byte/count/digest validation fails; fixture passes. | `DETECTED_BY_REHASH` |
| Row modification | Whole-file digest and, after open, row digest fail; fixture passes. | `DETECTED_BY_REHASH` |
| Row reorder | Content digest changes; two-row fixture fails closed. | `DETECTED_BY_REHASH` |
| `manifest.json` mutation before a new open | Canonical/version/manifest-content/derived-identity checks fail; fixture passes. | `DETECTED_BY_EXISTING_IDENTITY` |
| Samples unchanged, manifest mutated after an existing open | Existing reader uses its frozen manifest and opened stream; it does not reread the manifest. New reader rejects it. | `DETECTED_BY_EXISTING_IDENTITY_FOR_NEW_OPEN; EXISTING_HANDLE_STATE_ONLY` |
| `samples.ndjson` replacement under the original manifest | Content digest fails; fixture passes. | `DETECTED_BY_REHASH` |
| Entire directory replaced by another valid artifact with its own manifest | Bare `open(path)` accepts the replacement because it is internally self-consistent and no expected external artifact ID is supplied; fixture proves different derived IDs. | `NOT_PROVABLY_DETECTED` |
| Root or component symlink substitution | Code checks `is_symlink()` and rejects symlink paths. Creating the directory-symlink fixture was denied by Windows `WinError 1314`. | `DETECTED_BY_FILESYSTEM_IDENTITY; PLATFORM_DEPENDENT; NOT_PROVEN_BY_FIXTURE` |
| Hard-linked sample under another directory | Byte-identical hardlink reopens successfully on this host. File identity is not used as content authority. | `CONTENT_PASS; FILESYSTEM_IDENTITY_NOT_AUTHORITY` |
| Byte-identical copy to another path | Copy reopens and retains the same content-derived artifact ID; fixture passes. | `DETECTED_BY_REHASH_IF_REOPENED; PATH_INDEPENDENT_CONTENT_PASS` |
| mtime changed, bytes unchanged | Current reader ignores mtime and content still validates; metadata-only strategy would see a metadata change but that is not cryptographic proof. | `DETECTED_ONLY_BY_FILESYSTEM_IDENTITY; NOT_CONTENT_PROOF` |
| mtime restored/spoofed, same-size bytes changed | Content digest still fails; fixture passes. Metadata-only trust would not be sufficient. | `DETECTED_BY_REHASH; METADATA_ONLY_NOT_PROVABLY_DETECTED` |
| ctime/file-id change | Current reader does not use these fields. Their availability and stability differ across Windows/Linux. | `PLATFORM_DEPENDENT; NOT_PROVABLY_DETECTED_BY_CURRENT_READER` |
| Validator source/version changed | No validator identity is stored or compared by this reader. A self-consistent changed producer commit is accepted by the fixture; a validator mismatch is not representable in the current manifest. | `NOT_PROVABLY_DETECTED` |
| Unknown derived schema/version | Known V1 reader rejects unsupported version/schema; fixture passes. | `DETECTED_BY_EXISTING_IDENTITY` |
| Process restart | `_ValidatedSampleFileState`, handle, and frozen object are gone; child process repeats full validation. | `DETECTED_BY_REHASH_FOR_NEW_OPEN; FAST_REUSE_NOT_AVAILABLE` |

The directory-replacement result is the authority boundary that metadata-only designs miss: a new
valid artifact is not malformed. Only an externally bound expected content identity, or a trusted
immutable publication primitive, can distinguish it from the previously accepted artifact.

## 6. Minimum safe identity set (not a frozen attestation schema)

The audit does not freeze `VerifiedDerivedArtifactV1`. It identifies the facts a later authorization
would need to bind:

1. exact canonical manifest bytes, or a cryptographic digest of those bytes;
2. exact `samplesContentDigest`, `samplesByteCount`, `sampleCount`, row framing, and any required
   partition/count facts;
3. the derived artifact identity and its source dataset/manifest identity;
4. derived-view, model-facing, trajectory, split, and format/version identities;
5. the validator contract/implementation/version whose validation result is being reused;
6. a publication/immutability proof that prevents the bound bytes from being changed or replaced;
7. an expected artifact identity supplied by the caller, rather than whatever identity the current
   path happens to declare.

Path, mtime, size, ctime, inode/file ID, or a mutable `latest` pointer can be useful diagnostics.
They are not substitutes for the content identity and the independent immutability authority.

## 7. Candidate trust strategies

| Strategy | Addresses | Trust strength | Cross-process safety | Portability | Complexity/reuse | Decision |
|---|---|---|---|---|---|---|
| A. Full revalidation | Current content integrity and validator/schema checks | Strong for the currently opened artifact; does not prove continuity without an expected external ID | Yes for the currently opened bytes | High | High repeated cost; measured open is 1,991.98 s | Safe baseline, not a fast path |
| B. Small attestation plus size/mtime/ctime/file ID | Repeat-open cost | Insufficient against same-size mutation, spoofed/restored metadata, or valid replacement | No without a trusted external primitive | Metadata semantics are platform-dependent | Low implementation cost, unsafe recurrent reuse | Reject as standalone authorization |
| C. Existing open handle | Same-process repeated consumption | Strong only for that handle/lifetime; current reader is one-shot | No | Host/process-specific | Low, but cannot solve restart/reopen | Keep as a local lifetime concept only |
| D. Content-addressed immutable artifact store | Repeat open across processes and relocation | Conditional strong: depends on atomic publication and independently trusted no-mutation/versioning guarantees | Yes if the store contract is authoritative | Requires a portable store contract; Windows/Linux filesystem metadata alone is insufficient | Medium-high design cost, high recurrent reuse | Recommended prerequisite |
| E. Partial or sampled content verification | Some accidental corruption | Probabilistic, not an accepted trust proof | No | Broad but irrelevant to cryptographic authority | Low-medium, poor semantic value | Reject for trust authorization |
| F. OS/filesystem immutable mechanisms | Store-level no-mutation guarantee | Conditional and privilege/platform dependent; read-only bits/ACLs alone are not enough | Potentially, if the OS/store primitive is explicitly trusted | Windows/Linux behavior differs materially | High operational/design coupling | Evaluate only as part of D, not alone |

Arrow, Parquet, custom binary, sharding migration, source indexing, and learner caches are outside
this slice. They do not solve the authority question by themselves.

## 8. Filesystem and Kaggle portability

The current `is_symlink()` and regular-file checks are useful fail-closed checks, but a check of a
path followed by a later open is not an immutable publication proof against every TOCTOU or privileged
writer scenario. Windows file sharing/replacement behavior, `st_ino` semantics, ctime behavior, and
symlink privileges differ from Linux. A cross-platform attestation must make the storage primitive
explicit rather than silently treating metadata as cryptographic evidence.

A viable future store design would need atomic publication, an immutable content-addressed root,
manifest and shard identities bound to the published object, and a writer/retention policy that the
learner is authorized to trust. A later Kaggle actor should publish authoritative trajectory shards
under that store contract. The learner repeat-open path would consume the immutable derived artifact;
the actor generation path and learner cache path remain separate.

## 9. Theoretical performance impact

```text
THEORETICAL_MAX_REPEAT_OPEN_SAVINGS_SECONDS≈1991.982284800004
REAL_FAST_PATH_BENCHMARK_EXECUTED=NO
```

If a future trusted immutable store legitimately eliminates the repeated strict source open for an
unchanged artifact, the arithmetic upper bound for the accepted DVRP-2 bounded view is:

```text
3525.3979822999972 - 1991.982284800004
= 1533.4156974999933 seconds
≈ 25.56 minutes
THEORETICAL_SAVED_SHARE_OF_PREPARATION≈56.50%
```

This is not a measured fast path and is not an authorization to skip validation. After that hypothetical
saving, source inference iteration (`1446.6978596975678` s) would remain the next major measured
learner data-path cost.

## 10. Fixture and test evidence

The focused suite uses only small synthetic artifacts and existing production
`DerivedArtifactReader` APIs. It does not regenerate C1_05 data, labels, trajectories, or a model.

```text
UNCHANGED_ARTIFACT=PASS
SECOND_READER_SAME_PROCESS_FULL_VALIDATION=PASS
SAME_SIZE_MUTATION_BEFORE_OPEN=PASS
SAME_SIZE_MUTATION_AFTER_OPEN=PASS
TRUNCATION=PASS
APPEND=PASS
MANIFEST_MUTATION=PASS
SAMPLES_FILE_REPLACEMENT=PASS
VALID_ARTIFACT_DIRECTORY_REPLACEMENT_WITHOUT_EXPECTED_ID=PASS_EXPECTED_NEGATIVE_EVIDENCE
ROW_REORDER=PASS
BYTE_IDENTICAL_COPY_RELOCATION=PASS
HARD_LINK=PASS
MTIME_CHANGE_UNCHANGED_BYTES=PASS
MTIME_RESTORED_BYTE_MUTATION=PASS
UNKNOWN_SCHEMA_VERSION=PASS
MATERIALIZER_SOURCE_IDENTITY_NOT_VALIDATOR_IDENTITY=PASS
NEW_PROCESS_REPEATS_FULL_VALIDATION=PASS
SYMLINK_SUBSTITUTION=NOT_PROVEN_WINDOWS_WINERROR_1314
```

The focused DVRP-2A suite ran 17 tests: 16 passed and one was skipped because Windows denied
directory-symlink creation. The affected existing reader suites ran 48/48 without skips. The combined
focused-plus-affected command ran 65 tests with 64 passes and one explicitly not-proven symlink
fixture. This is not a claim that symlink behavior is portable-proof; the production code-level
rejection remains visible in the audit.

## 11. Recommended next direction

```text
NEXT_RECOMMENDED_SLICE=DVRP_2B_CONTENT_ADDRESSED_IMMUTABLE_ARTIFACT_STORE_DESIGN
```

This is recommended before `DVRP_2B_IMPLEMENT_VERIFIED_ATTESTATION` because the audit found that the
missing fact is not another digest field. The missing fact is an independently trusted answer to
"these exact bytes cannot have been replaced".

- Expected value: it could make the full 1,991.98-second strict-open cost reusable across later
  process lifetimes, subject to a separately measured fast path.
- Trust strength: potentially strong only with atomic immutable publication, content identity, and
  explicit validator binding; a metadata-only cache is not enough.
- Cross-process: possible under the store contract; impossible with current in-memory state alone.
- Windows/Linux: requires a storage-level contract because ordinary filesystem metadata and symlink/
  replacement behavior are not portable authority.
- Complexity: medium-high design/operations work, but lower semantic risk than silently adding a
  reader bypass; it also gives future Kaggle-generated shards a clear publication boundary.

## 12. Limitations and scope closure

```text
NEW_REAL_FULL_ARTIFACT_SCANS=0
C1_05_FULL_MATERIALIZATION_RUNS=0
C1_05_TEACHER_RUNS=0
NEW_LABELS_GENERATED=0
NEW_TRAJECTORIES_GENERATED=0
REAL_FAST_PATH_BENCHMARK_EXECUTED=NO
MEMORY_PROFILE=NOT_MEASURED
SYMLINK_FIXTURE=NOT_PROVEN_ON_THIS_WINDOWS_HOST
LINUX_FILESYSTEM_RUN=NOT_RUN
IMMUTABLE_STORE=NOT_PRESENT
```

No production reader, learner, source format, cache, index, sharding, Arrow, Parquet, custom binary,
or TrajectoryV1 code was changed. The report's `PASS` mutation results mean the current existing
reader detected the tested condition; they do not mean a future metadata-only attestation would be
safe.

```text
FULL_STRICT_VALIDATION_EXISTS=YES
FAIL_CLOSED=YES
SOURCE_AUTHORITY_UNCHANGED=YES
MODEL_FACING_SEMANTICS_UNCHANGED=YES
NO_PRIVACY_DRIFT=YES
NO_BINDING_RECONSTRUCTION=YES
NO_CANDIDATE_TRUNCATION=YES
TRAJECTORY_V1_CHANGED=NO
TRUST_SEMANTICS_CHANGED=NO
OPTIMIZATION_IMPLEMENTED=NO
```

## 13. Verification and handoff

```text
FOCUSED_TESTS=PASS_16_OF_16_PLUS_1_SKIPPED_NOT_PROVEN
AFFECTED_READER_TESTS=PASS_48_OF_48
FULL_ML_TESTS=PASS_238_OF_238_PLUS_1_SKIPPED_NOT_PROVEN
ML_CHECK=PASS
P1=0
P2=0
P3=0
CHARACTERIZATION_PASS=YES
PR_CREATED=NO
NEXT_TASK_STARTED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
```

The skipped test is only the Windows directory-symlink fixture denied by `WinError 1314`; it is not
counted as a pass. No other test was skipped or unavailable.
