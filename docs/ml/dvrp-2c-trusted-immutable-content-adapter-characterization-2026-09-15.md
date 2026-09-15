# DVRP-2C trusted immutable content adapter characterization

```text
TASK=DVRP_2C_TRUSTED_IMMUTABLE_CONTENT_ADAPTER
DATE=2026-09-15
BASE_ORIGIN_MAIN=03bce91dea0502d74c7af2a1bab5acc2e43f1fa2
DVRP_2B_FINAL_ACCEPTANCE_PASS=YES
DVRP_2B_REVIEWED_HEAD=262b41d8c9d11d62c661efbf91605189a28ecdca
```

## Decision

```text
IMMUTABLE_CONTENT_ADAPTER_PROVEN=NO
NO_OVERWRITE_AUTHORITY=NOT_PROVEN
CROSS_PROCESS_TRUST=NOT_PROVEN
TRUSTED_HANDLE_ISSUANCE=NOT_PERFORMED
```

No concrete `ImmutableContentArtifactStore` was implemented. The accepted DVRP-2B contract
requires an independently trusted no-overwrite/version/retention authority. The available local
filesystem does not provide one that this slice can prove, so the result remains fail-closed
`ATOMIC_ONLY`.

## Platform capability evidence

The audit ran on:

```text
OS=Windows 11 Home
OS_VERSION=10.0.26200
FILESYSTEM=C: NTFS
WSL=version 2; only docker-desktop distribution; STOPPED
DOCKER_LINUX_DAEMON=UNAVAILABLE
```

The available `chattr.exe` is the Git-for-Windows utility. Its help output lists the Windows
readonly attribute (`r`) and does not expose the Linux ext4 immutable (`i`) attribute. It therefore
does not establish a Linux immutable inode on this NTFS path. `fsutil fsinfo volumeinfo C:` and
`fltmc` were access-denied in this non-elevated process; neither command establishes a trusted
immutable store capability.

The current ACL inspection shows Modify rights for the local/sandbox principals on the working
filesystem. This is ordinary same-user filesystem authority, not an independently administered
no-overwrite boundary.

## Why the obvious local approaches are rejected

None of the following proves the required invariant after process restart:

```text
path or filename
mtime or size
NTFS readonly attribute
ordinary same-user ACL
receipt or content digest alone
open file handle after the process exits
atomic directory creation without immutable retention authority
```

Each can be paired with the existing strict validation for visibility and integrity, but none can
authorize `TrustedImmutableHandleV1` against a later byte replacement by a principal inside the
same filesystem trust boundary. Implementing one as `IMMUTABLE_CONTENT` would weaken the accepted
DVRP-2B trust model.

## Scope closure

```text
ATOMIC_ONLY_STILL_FAIL_CLOSED=YES
FULL_STRICT_VALIDATION_PRESERVED=YES
REAL_FAST_PATH_IMPLEMENTED=NO
REAL_FAST_PATH_BENCHMARK_EXECUTED=NO
NEW_REAL_FULL_ARTIFACT_SCANS=0
TRAJECTORY_V1_CHANGED=NO
```

The accepted DVRP-2 upper bound of approximately `1991.9823` seconds of repeat-open validation
work remains theoretical. No fast-path speedup was measured.

## What would unblock DVRP-2C

A later implementation may proceed only after supplying and independently verifying one of these
authority boundaries:

1. A separately administered local service/volume that enforces no overwrite and retention for the
   content-addressed namespace, with an exact provider version bound into the receipt.
2. A Linux filesystem deployment where the immutable primitive and its privileged publication/
   retention authority are available and tested under the accepted threat model.
3. A versioned/WORM object provider adapter that exposes conditional publication, pinned versions,
   retention, and authoritative commit state.

Until one is available, the existing `AtomicOnlyArtifactStore` and strict
`DerivedArtifactReader.open(path)` path remain the only trusted choices. No reader fast-open,
receipt cache, or trusted handle is authorized by this characterization.

## Verification boundary

No focused adapter tests were run because there is no implementable immutable primitive to exercise.
The unchanged ML contract suite and compile check may still be run as baseline regressions, but a
green baseline cannot convert this `NOT_PROVEN` capability result into `PASS`.
