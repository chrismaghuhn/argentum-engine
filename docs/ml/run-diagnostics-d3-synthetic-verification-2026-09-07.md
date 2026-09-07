# Run Diagnostics D3 — Synthetic Stall / Failure Verification

Date: 2026-09-07

This is a test-only verification slice for the accepted D1/D2 diagnostics implementation. It does
not add workload integration or change diagnostic production behavior.

## Provenance and boundary

```text
BASE=70dcde4108dd05ba399902815bb05eb871e58561
HEAD=the single D3 commit containing this report; exact SHA is in the completion report
PARENT=BASE
REMOTE_HEAD=the pushed D3 commit; exact SHA is in the completion report
UPSTREAM_SHA=5021faf88093a93091e4de7914fbe0f411499d58

PRODUCTION_CODE_CHANGED=NO
WORKLOAD_ADAPTERS_ADDED=NO
REAL_WORKLOAD_INTEGRATION=NOT_RUN
LINUX_RUNTIME_CHARACTERIZATION=NOT_RUN
GLOBAL_LINUX_RUNTIME_CLAIM=NO
D4_AUTHORIZED=NO
```

The tests exercise the real `ExternalSupervisor`, `StallClassifier`,
`ProcessIdentityChecker`, `ProcessMetricsSampler`, `JvmEvidenceCollector`,
`JvmEvidenceAnalyzer`, `DiagnosticBundleWriter`, `DiagnosticRetention`,
`StatusSidecarReader`, `AtomicStatusFile`, and `DiagnosticsRecorder` through their existing
injection seams. No Gym, Rules, replay, trajectory, trainer, learner, or game-server module is
imported.

## Deterministic fixture support

The test-only fixtures provide a monotonic clock, fixed wall clock, status timeline, process
identity timeline, scalar metrics timeline, JVM command results, no-op sleeper, recording bundle
sink, temporary bounded bundle roots, and an atomic-publication fault adapter. A small test-only
filesystem provider makes retention directory listing fail deterministically without changing
the production retention API. Tests use fake elapsed time; they do not wait for a real stall or
run a workload soak.

## Fixture matrix

| Fixture | Expected | Observed | Must-not-happen assertion |
| --- | --- | --- | --- |
| D3-01 `HEALTHY_PROGRESS` | `HEALTHY`, continue | PASS | Long stage age alone does not capture or change semantics. |
| D3-02 `HEARTBEAT_ONLY_NO_USEFUL_PROGRESS` | `SUSPECTED_STALL`, bounded capture | PASS | Heartbeat/PID liveness does not imply useful progress; no termination. |
| D3-03 `CPU_SPIN` | `CPU_SPIN_SUSPECT` | PASS | Stable hot evidence is not called a deadlock and does not kill. |
| D3-04 `BLOCKED_WAIT` | `BLOCKED_WAIT_SUSPECT` | PASS | Stable wait evidence without a cycle is not called deadlock. |
| D3-05 `PROCESS_EXIT` | `PROCESS_EXITED`, continue | PASS | No JVM attach, retry, recovery, or semantic terminal result. |
| D3-06 `STATUS_WRITE_FAILURE` | Non-fatal failure; stale sidecar is conservative | PASS | Old status remains readable and heartbeat/recorder progress continues. |
| D3-07 `PARTIAL_TEMP_FILE` | Old complete status remains current | PASS | Partial JSON is neither parsed nor accepted as the target. |
| D3-08 `MISSING_JCMD` | Explicit unavailable JVM evidence; generic fallback | PASS | No fabricated dump and no indefinite wait. |
| D3-09 `SLOW_BUT_PROGRESSING_STAGE` | `HEALTHY` | PASS | Stage/wall age alone does not interrupt or classify a failure. |
| D3-10 stale sidecar + live process | Conservative suspected stall | PASS | Live process is not reported healthy solely because it is alive. |
| D3-11 missing CPU | Generic suspected stall | PASS | Missing CPU is not converted to zero or a CPU diagnosis. |
| D3-12 missing RSS | CPU evidence remains usable; RSS stays null | PASS | Missing RSS is not fabricated. |
| D3-13 process exits during capture | Next sample exits without more attach | PASS | No new JVM commands after the observed exit. |
| D3-14 unsupported atomic move | `PROVIDER_UNSUPPORTED`; old target retained | PASS | No silent non-atomic replacement fallback. |
| D3-15 command timeout | Timed-out evidence is explicit; generic fallback | PASS | Bounded command path does not hang. |
| D3-16 explicit deadlock report | `DEADLOCK_DETECTED`, continue-only | PASS | No target termination, retry, recovery, or semantic result. |
| D3-17 contradictory stable hot/wait | `SUSPECTED_STALL` | PASS | Ambiguous evidence cannot produce a confident spin/wait class. |
| D3-18 cursor regression | `UNKNOWN`, continue | PASS | Regressed cursors do not become fresh progress or trigger capture. |
| D3-19 PID/start mismatch | `IDENTITY_MISMATCH`, no attach | PASS | No metrics or JVM command is run for a reused identity. |
| D3-20 missing/failed optional bundle evidence | Explicit availability metadata | PASS | Optional files are not fabricated; privileged files remain non-dataset-safe. |

## Evidence and semantic boundaries

- `DEADLOCK_DETECTED` is emitted only from the accepted explicit JVM deadlock text condition.
  Stable `WAITING` evidence alone remains `BLOCKED_WAIT_SUSPECT`.
- Raw `GC.heap_info` text is captured as privileged evidence when available, but D2 V1 does not
  parse it. `gcPressure` remains unavailable and cannot produce `GC_PRESSURE_SUSPECT`.
- Missing values remain missing. No test treats missing CPU, RSS, status, or JVM output as zero,
  healthy, or proof of a root cause.
- Every observed action is either `CONTINUE_OBSERVING` or
  `CAPTURE_DIAGNOSTICS_AND_CONTINUE`. No fixture can call target termination, retry, recovery,
  interruption, or a game outcome.
- Process identity tests bind PID and start instant. A mismatch prevents metrics and JVM capture.

## Publication, bundle, privacy, and retention coverage

- Injected atomic write and replace failures preserve the old valid `run-status.json`; partial
  temporary bytes are cleaned or ignored and never become the accepted target.
- Real `DiagnosticBundleWriter` output is checked for `summary.json`, safe scalar files, explicit
  `AVAILABLE`/`MISSING`/`FAILED`/`TIMED_OUT`/`NOT_CONFIGURED` records, and bounded total bytes.
- Privileged thread dumps, heap information, and VM flags are stored separately and marked
  `datasetSafe=false` under `DEVELOPER_PRIVILEGED_DIAGNOSTIC_NOT_DATASET_SAFE`.
- Normal status and summary scans reject raw game state, observations, legal domains, action
  payloads, rewards, hidden objects, and replay contents. No gameplay state is present in a
  fixture input.
- Persistent synthetic stalls exercise cooldown and a two-bundle maximum. The history ring is
  exercised at capacity two. Retention deletes only old `stall-*` directories under its own root,
  preserves unrelated files, and reports `RETENTION_FAILED` without deleting anything when the
  injected directory listing fails.

## Verification and limitations

The final completion report records the exact commands and results for:

```text
:run-diagnostics:test
:run-diagnostics:check
:run-diagnostics:build
CLI --help smoke
git diff --check
```

The local provider is Windows/NTFS. Linux parser behavior is covered only by deterministic
synthetic `/proc` fixture tests inherited from D2; live Linux runtime characterization was not
run. No real workload integration, long-running freeze reproduction, dataset generation, B0/B2
soak, D4 adapter, C1 job, training, or corpus generation was performed.
