# DVRP-2B content-addressed immutable artifact store design

```text
TASK=DVRP_2B_CONTENT_ADDRESSED_IMMUTABLE_ARTIFACT_STORE_DESIGN
DATE=2026-09-15
BASE_ORIGIN_MAIN=03bce91dea0502d74c7af2a1bab5acc2e43f1fa2
UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872
AUDIT_BASE_HEAD=03bce91dea0502d74c7af2a1bab5acc2e43f1fa2
ISSUE_188=OPEN
DVRP_2A_FINAL_ACCEPTANCE_PASS=YES
DVRP_2A_REVIEWED_HEAD=f2ea39e958476eddb3fe72d8f8dc2f0083b2320e
```

## Decision summary

```text
CONTENT_ADDRESSED_STORE_DESIGN=PASS
ATOMIC_PUBLICATION_DESIGN=PASS
IMMUTABILITY_AUTHORITY_DEFINED=YES
EXPECTED_ARTIFACT_ID_REQUIRED=YES
VALIDATOR_BINDING_DEFINED=YES
CROSS_PROCESS_TRUST_MODEL=PASS
WINDOWS_LINUX_PORTABILITY=CONDITIONAL_CAPABILITY_ADAPTER
KAGGLE_COMPATIBILITY=CONDITIONAL_PUBLICATION_ADAPTER
REPEAT_OPEN_ATTESTATION_FEASIBLE=CONDITIONAL
SMALL_ATTESTATION_ALONE_PROVES_UNCHANGED_BYTES=NO
TRUSTED_IMMUTABILITY_PRIMITIVE_REQUIRED=YES
```

The design is conditional by intent. Atomic visibility prevents consumers from observing a partial
publication, but it does not by itself prove that the bytes cannot later be replaced. Only a store
with an independently trusted immutable-after-publication authority may authorize a future
cross-process fast open. A local adapter without that authority remains `ATOMIC_ONLY` and must use
the existing full strict reader.

## 1. Current trust problem

DVRP-2A accepted this DVRP-2 evidence:

```text
SOURCE_ARTIFACT_BYTES=8083329687
SOURCE_READER_OPEN_WALL_SECONDS=1991.982284800004
TOTAL_PREPARATION_WALL_SECONDS=3525.3979822999972
GPU_OPTIMIZER_REFERENCE_WALL_SECONDS=39.282577800011495
```

The current Python reader proves one open lifetime. `DerivedArtifactReader.open()`
(`ml/src/argentum_ml/data/derived_reader.py:278-305`) checks a real directory and regular files,
canonicalizes and validates the manifest, then calls `_validate_sample_file()`
(`:456-501`) for a complete source pass. That pass parses and semantically validates every row,
checks counts and `samplesContentDigest`, and stores `_ValidatedSampleFileState` (`:260-265`) with
the whole-file digest, byte count, sample count, and per-row SHA-256 digests.

`iter_validated_samples_for_inference()` (`:326-353`) performs a whole-file digest/count preflight
(`_verify_validated_sample_file`, `:504-521`), then checks each row digest, parses the row, and issues
a reader-authorized token. DVRP-0/1 therefore preserves strict validation once and avoids repeating
deep semantic validation inside the same inference lifetime.

None of the retained state, frozen manifest, or open handle survives process termination. The current
`open(root)` interface also has no caller-supplied expected artifact ID and no reader validator
identity. It authenticates the artifact currently found at the path, not continuity with an earlier
accepted artifact.

The current Kotlin derived materializer (`C1LearnerArtifactMaterializer.kt:41-61,142-150`) stages
bytes but moves `samples.ndjson` and `manifest.json` separately into the output directory. Its live
exception cleanup is useful, but a process crash between the two moves can leave a partial visible
directory. The A6 `TrajectoryV1Publisher` already demonstrates the stronger complete-root publication
pattern (`TrajectoryV1Publisher.kt:165-207,313-335`): verify staged content, write the manifest last,
then atomically move the complete directory. DVRP-2B designs around that existing evidence without
changing either implementation.

## 2. Identity model

The store must keep three identities separate:

```text
CONTENT IDENTITY  = what exact derived bytes/semantics are accepted
PATH IDENTITY     = where a provider currently locates them
PROVIDER IDENTITY = which store/version owns that location
```

The existing `derivedArtifactId` remains the semantic content identity. Its current preimage binds the
derived schema, source dataset/manifest, trajectory/model/split identities, materializer identity and
config, sample content digest, and partition counts (`C1LearnerContractsV1.kt:194-235`).
`manifestContentDigest` binds the exact canonical manifest bytes, while `samplesContentDigest`,
`samplesByteCount`, `sampleCount`, and partition counts bind the payload accounting.

A future store-owned publication record must bind, at minimum:

```text
artifact kind and artifact/manifest format version
derivedArtifactId
manifestContentDigest
samplesContentDigest
samplesByteCount and sampleCount
required partition/count facts
derived/model-facing/trajectory/split identities
sourceDatasetId and sourceManifestContentDigest
materializer implementation/source commit and config digest
validator contract/version
validator implementation/source commit or immutable digest
validator policy/config digest
publication contract/version
provider version/generation and immutability capability
```

This is an identity *set*, not a frozen new artifact schema. The validator and publication fields
should be reviewed as an additive receipt/contract. Existing `derivedArtifactId`, manifest bytes,
`samples.ndjson`, and `TrajectoryV1` remain unchanged.

The provider locator may contain a local path, object key, object generation, account, or region. It
is never sufficient authority. A mutable `latest`, filename, mtime, size, inode/file ID, URL, or path
must not authorize a fast open.

## 3. Authority model

### What makes a published artifact immutable?

`IMMUTABLE_PUBLISHED` requires all of the following:

1. Complete payload and manifest are privately staged and fully strict-validated.
2. Content and manifest digests are computed from the bytes that will be published.
3. A store-owned record binds content identity, validator identity, and publication policy.
4. Final creation is conditional/no-overwrite (`CREATE_NEW` or provider equivalent).
5. No accepted writer principal can overwrite the content, manifest, or commit record for that ID.
6. The provider version/generation is pinned and cannot silently redirect.
7. Deletion is separate lifecycle authority and never repoints the old ID.
8. Missing, mismatched, or uncertain evidence makes the object ineligible for trusted repeat-open.

Read-only bits, same-user ACLs, mtime, size, inode/file ID, and an in-memory boolean do not satisfy
this definition alone. A plain local filesystem therefore provides at most `ATOMIC_ONLY` unless an
independently trusted OS/service boundary enforces the no-overwrite and retention rules.

### Who may publish?

Only the publication owner/adapter may transition a validated staging transaction into the immutable
namespace. The trajectory actor may produce staging bytes, but it cannot self-approve them. The
publication owner issues the receipt, owns crash recovery, exposes capability state, and keeps
deletion/retention authority separate from ordinary writers. The learner supplies the exact expected
identity and cannot manufacture a receipt.

### How does a later process request the same artifact?

The future seam is an exact identity request, not a path request:

```text
openExact(expectedArtifactIdentity, expectedValidatorBinding)
```

The store resolves by content ID, verifies the store receipt/commit marker, compares every expected
identity field, and returns a pinned trusted handle only for the matching immutable provider version.
If an alias/path resolves to another ID, it fails closed. This preserves content identity across
relocation and prevents a valid replacement artifact from masquerading as the old one.

## 4. Proposed publication lifecycle

```text
STAGING
  -> FULL_STRICT_VALIDATION
  -> CONTENT_ID_COMPUTED
  -> PUBLICATION_RECORD_BOUND
  -> ATOMIC_PUBLICATION
  -> IMMUTABLE_PUBLISHED
  -> TRUSTED_REPEAT_OPEN_ELIGIBLE
```

### STAGING and strict validation

Use a unique private transaction root, exclusive file creation, regular non-symlink files, safe
relative references, and no consumer-visible alias. Staging is never eligible for trusted open.
The strict phase retains the current canonical JSON, schema/version, identity, row framing, content
digest, byte/count, partition, and semantic checks. Metadata, sampled blocks, or a manifest-declared
digest without byte authentication cannot replace it.

### Content identity and publication record

Compute the existing `derivedArtifactId` and exact manifest/payload digests from staged bytes. Additive
receipt fields may identify the validator and store policy, but must not silently rewrite the accepted
artifact identity. The receipt is issued by the store authority, not accepted from the materializer as
an assertion.

### Atomic publication

For a local same-volume store, the preferred operation is:

```text
complete staging root
  -> force/close staged files as the provider supports
  -> atomic directory move to objects/<derivedArtifactId>
  -> commit marker already inside, or conditionally written last
```

Unsupported directory `ATOMIC_MOVE` must not degrade to copy-plus-delete while retaining an atomic
claim. A provider without directory atomicity uses immutable child objects plus a final conditional
commit marker listing exact child versions and digests; no marker means not published.

### Immutable publication and future fast open

After commit, the content-ID namespace is append-only/no-overwrite. A mutable alias may be updated for
humans but is never authority. A later fast open requires exact expected identity, valid receipt/marker,
matching validator binding, pinned immutable provider version, and no retention/recovery uncertainty.
Any failed condition returns `FULL_VALIDATION_REQUIRED`, `NOT_FOUND`, or a fail-closed error. The
existing `DerivedArtifactReader.open(path)` retains its current strict semantics.

## 5. Validator binding

The current derived manifest binds the Kotlin materializer implementation/source commit and config,
but not the reader/validator implementation that would later reuse a validation result. A future
receipt must bind a tuple equivalent to:

```text
validator contract identity/version
validator implementation identity
validator source commit or immutable implementation digest
validator policy/config digest
validation result = FULL_STRICT_VALIDATION_PASS
```

The tuple is code-owned/approved, not an arbitrary caller string. A validator implementation change
is a new trust domain by default. Old receipts become ineligible unless an explicit compatibility or
equivalence contract is reviewed. Unknown validator versions fail closed; they do not silently inherit
the latest reader.

```text
materializer identity = who produced the derived bytes
validator identity     = which rules established reusable trust
store identity         = which authority prevents later replacement
```

All three are needed for repeat-open authorization. None is replaced by a path or provider name.

## 6. Crash and recovery model

| Crash point | Visible state | Eligibility after restart |
|---|---|---|
| Before staging is complete | Partial private transaction | Not published; discard or quarantine |
| During staged file write | Partial or missing file | Not published; discard or quarantine |
| After staged validation, before publication | Complete private root | Not published until a fresh publication decision |
| During local directory move | Old or new namespace, subject to provider atomicity | New object eligible only if complete target/marker is proven; otherwise validate or quarantine |
| After payload move, before commit marker | Complete-looking orphan | Not eligible; recovery may verify and republish normally |
| After commit marker, before alias update | Immutable object and record, no alias | Eligible by exact content ID; alias absence is harmless |
| During alias update | Old/new alias or no alias | Alias is never authority; exact ID decides |
| During object upload | Some child objects | Not eligible until conditional commit marker exists |
| During recovery/GC | Uncertain staging/orphan state | Fail closed; never delete a live leased object |

Recovery is idempotent. It may delete abandoned staging data, quarantine an inconsistent final object,
or complete an explicitly verified publication. It must not infer trust from the presence of a digest-
named directory.

## 7. Duplicate, conflict, and concurrency behavior

### Same content published twice

Two transactions with the same complete content identity race on one final key. The first conditional
create wins. The second returns `ALREADY_PUBLISHED` only if every bound identity, manifest/payload
digest, count, validator binding, and immutability capability agrees. It never overwrites the object.

### Same logical name points to different bytes

A logical name is a versioned alias, not an identity. An alias update from content ID A to B never
changes A. A consumer requesting A rejects B. A publisher cannot overwrite a name while pretending it
is the same content; it must publish a new content ID and record the alias transition.

### Same content ID with inconsistent bytes

This is a store integrity failure. Quarantine the object/record and refuse trusted open. Require full
independent verification before disposition. Never accept a manifest's self-declared digest without
checking the bound bytes under the store authority.

### Concurrent readers and writers

Readers see only committed immutable versions. Writers use unique staging roots and conditional final
creation; they never modify a published object. A reader pins the publication record/provider version
for its handle, so a later alias update does not affect it. Provider outage, missing marker, expired
lease, or uncertain version means `NOT_TRUSTED`, not a metadata fallback.

### Retention and deletion

Immutable-after-publication does not mean never deletable. A separate retention controller may delete
an object only after policy/retention expiry and release of active leases/references. Deletion removes
availability; it never repoints the old content ID. Re-publication after deletion requires a new
publication record and validation; the old receipt is not automatically reusable across deletion or a
provider-version change.

## 8. Threat and failure matrix

| Threat/failure | Control | Result |
|---|---|---|
| Path renamed or copied | Exact expected content ID and receipt lookup | Safe under store contract; path is not authority |
| Mutable `latest` alias | Versioned convenience alias only | Cannot authorize fast open |
| Same-size byte mutation | Initial full digest plus immutable post-publication authority | Rehash detects it or the primitive prevents it |
| mtime/size spoofing | Never use them as content proof | Rejected as authority |
| Valid different artifact at same path | Expected external artifact ID plus content-addressed lookup | Detected by exact request; bare current `open(path)` cannot prove continuity |
| Symlink/ancestor substitution | No-symlink regular-file checks and safe provider resolution | Adapter-specific; uncertainty fails closed |
| File replacement after publication | No-overwrite immutable object/version primitive | Safe only for `IMMUTABLE_CONTENT_STORE` |
| Crash before publication | Private staging and commit marker/atomic root publication | Ineligible until commit evidence exists |
| Concurrent duplicate publication | `CREATE_NEW`/conditional create plus exact equality check | Idempotent |
| Conflicting publication | Never overwrite content ID or silently retarget logical name | Explicit conflict |
| Validator upgrade | Receipt binds validator contract/implementation/version | Compatibility review or full revalidation |
| Provider metadata change | Pin provider generation/version but retain content identity | Provider metadata alone is insufficient |
| Deletion/republication | Lease-aware GC and new record | Old receipt not automatically reusable |
| Privileged local writer | Separate store/OS or service authority | Plain same-user filesystem remains `ATOMIC_ONLY` |
| Partial cloud upload | Commit marker with exact child versions written last | No marker means not published |

## 9. Portability and future publication providers

### Windows and Linux local stores

Both platforms provide regular-file and symlink checks and can support same-volume rename in suitable
configurations. They differ in directory durability, file sharing/deletion, symlink privileges,
inode/file-ID behavior, and crash semantics. The store interface must expose capabilities rather than
assume them:

```text
ATOMIC_DIRECTORY_PUBLISH_SUPPORTED
COMMIT_MARKER_SUPPORTED
IMMUTABLE_AFTER_PUBLICATION_SUPPORTED
DURABILITY_AFTER_CRASH_SUPPORTED
NO_SYMLINK_REGULAR_FILE_ENFORCEMENT_SUPPORTED
```

If a local adapter proves only atomic visibility, it reports `ATOMIC_ONLY` and never grants a
cross-process fast open. Read-only attributes and ordinary permissions are operational controls, not
independent immutability authority unless the threat model places the writer/administrator outside the
consumer trust boundary.

### Object storage

An object provider can implement the semantic contract if its adapter can conditionally create
objects, pin an object version/generation, prevent overwrite for the retention period, publish a
last-written commit marker, and expose failure/retention state. The provider key is still a locator;
the content ID and store-owned record remain authoritative.

### Local, Google, and Kaggle compatibility

Compatibility is conditional, not assumed:

```text
actor generates authoritative trajectory shards
  -> provider/versioned publication output
  -> adapter obtains exact bytes and provider version
  -> full validation + identity computation
  -> content-addressed immutable store publication
  -> learner requests exact derivedArtifactId
```

Local disk, a Google/object-storage deployment, and a Kaggle publication adapter can implement the
same semantic contract if they provide the required conditional/versioned/retention capabilities.
A Kaggle output path, Google object name, public URL, or mutable dataset `latest` is not sufficient.
If a provider cannot offer an independently trusted immutable version, it remains actor/source
transport and the store revalidates its bytes before publication. Actor generation and learner cache
consumption remain separate.

## 10. Future fast-open contract boundary

The future seam should be a deep store interface with a small caller surface, conceptually:

```text
publishValidated(staging, expectedIdentity, validatorBinding) ->
    PUBLISHED | ALREADY_PUBLISHED | CONFLICT | NOT_ELIGIBLE

openExact(expectedIdentity, expectedValidatorBinding) ->
    TRUSTED_IMMUTABLE_HANDLE | FULL_VALIDATION_REQUIRED | NOT_FOUND | CONFLICT

release(handle) -> RETENTION_REFERENCE_RELEASED
```

The implementation hides filesystem/object-provider details behind adapters. The interface must include
failure modes and capability state; a plain `Path` return is too shallow and would recreate the
DVRP-2A authority gap.

`TRUSTED_IMMUTABLE_HANDLE` may later feed a reader that skips the repeated 8-GB byte rehash. That
future reader must still read and compare the exact manifest/receipt identity, preserve schema/version
and privacy semantics, and bind the handle to the pinned provider version. The existing
`DerivedArtifactReader.open(path)` retains its current full strict behavior; the fast path should be
an explicit store-issued handle, not a hidden weakening of `open()`.

## 11. Performance boundary

```text
SOURCE_READER_OPEN_WALL_SECONDS=1991.982284800004
THEORETICAL_MAX_REPEAT_OPEN_SAVINGS_SECONDS≈1991.982284800004
REAL_FAST_PATH_BENCHMARK_EXECUTED=NO
```

If a future `IMMUTABLE_CONTENT_STORE` is accepted, the arithmetic upper bound is approximately
1,991.98 seconds saved per repeat open for the accepted DVRP-2 artifact. This is a theoretical
subtraction from the accepted measurement, not a measured fast-path wall time or a promise that all
consumer work disappears. Source iteration remained approximately 1,446.70 seconds in DVRP-2 and
would become the next measured data-path target.

```text
THEORETICAL_REPEAT_OPEN_SAVING != MEASURED_FAST_PATH
```

## 12. Recommended implementation decomposition

No step below is authorized by this design commit. It is the smallest sequence to review later:

1. Freeze an additive publication/validator receipt contract and capability vocabulary; keep existing
   `derivedArtifactId`, manifest, samples, and TrajectoryV1 bytes unchanged.
2. Build a local `ATOMIC_ONLY` fixture adapter with RED crash/duplicate/conflict/relocation tests;
   prove that it never grants trusted fast open.
3. Add a separately controlled immutable-store adapter and test the actual no-overwrite/retention
   authority, not merely read-only file bits.
4. Integrate complete derived-artifact staging and publication behind the store seam; retain the
   current strict materializer/reader as fallback until exact equivalence is proven.
5. Add consumer `openExact(expectedIdentity, expectedValidatorBinding)` and fail-closed fallback to
   full strict validation.
6. Add provider adapters for any local/cloud/Kaggle publication path only after their capability
   contracts are independently verified.
7. Run a bounded fast-open benchmark first. A real 8-GB repeat-open measurement requires separate
   authorization; this design task does not start it.

## 13. Scope closure and verification

```text
FULL_STRICT_VALIDATION_PRESERVED=YES
TRAJECTORY_V1_CHANGED=NO
TRUST_SEMANTICS_CHANGED=NO
OPTIMIZATION_IMPLEMENTED=NO
NEW_REAL_FULL_ARTIFACT_SCANS=0
C1_05_FULL_MATERIALIZATION_RUNS=0
C1_05_TEACHER_RUNS=0
NEW_LABELS_GENERATED=0
NEW_TRAJECTORIES_GENERATED=0
ARTIFACT_STORE_IMPLEMENTED=NO
READER_FAST_PATH_IMPLEMENTED=NO
ATTESTATION_CACHE_IMPLEMENTED=NO
SOURCE_KEY_INDEX_IMPLEMENTED=NO
ARROW_ADOPTED=NO
PARQUET_ADOPTED=NO
CUSTOM_BINARY_ADOPTED=NO
KAGGLE_JOB_STARTED=NO
LEARNER_TRAINING_STARTED=NO
```

This design task adds no implementation helpers and therefore has no new design-helper test surface.
Existing repository ML tests and `ml-check` are regression gates for this report-only change. No 8-GB
source or accepted C1_05 artifact is opened.

```text
P1=0
P2=0
P3=0
DESIGN_PASS=YES
DVRP_2B_IMPLEMENTATION_AUTHORIZED=NO
NEXT_TASK_STARTED=NO
STOP_FOR_EXACT_SHA_REVIEW=YES
```
