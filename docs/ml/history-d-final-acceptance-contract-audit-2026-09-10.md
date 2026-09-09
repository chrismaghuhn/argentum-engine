# History-D final-acceptance contract audit

```text
TASK=HISTORY_D_FINAL_ACCEPTANCE_CONTRACT_AUDIT_01
AUDIT_BASE=b564e7ece69e2ca1044c927d5dc43ec6244ad39e
AUDIT_BASE_PARENT=886dc85620690ab83471e02a12897f2508a1d881
ORIGIN_MAIN=886dc85620690ab83471e02a12897f2508a1d881
UPSTREAM_MAIN=3f46367d87c88bcf156a843a9e69fd29e1693872
B564_IN_ORIGIN_MAIN=NO
```

## Verdict

`HISTORY_D_FINAL_ACCEPTANCE_PASS` remains `NO`. The repository has no single living,
machine-readable or documentation-defined `HISTORY_D_FINAL_ACCEPTANCE` contract that fixes the
number of seeds, policy seeds, perspectives, repetitions, horizon, or terminal-game requirement.

The reviewed `b564e7ece...` test is a valid accepted bounded witness. It is a test-only child of
current `origin/main`, not part of `origin/main` itself. It proves the exact locked workload reaches
the configured 4000-step horizon without a History-D failure; it does not by itself prove universal
History-D completeness or define the missing final-acceptance policy.

No arbitrary horizon extension is authorized by this audit.

## Authority reviewed

| Source | What it establishes | What it does not establish |
| --- | --- | --- |
| [`PerspectiveHistoryV1.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/contract/PerspectiveHistoryV1.kt) | V1 schema identity/version, semantic payloads, perspective-safe references, contiguous append ordinals, canonical JSON, and semantic digest. | No final-acceptance matrix or completeness gate. |
| [`PerspectiveHistoryStateV1.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/history/PerspectiveHistoryStateV1.kt) and [`GameGymEnv.kt`](../../gym/src/main/kotlin/com/wingedsheep/gym/GameGymEnv.kt) | Episode-scoped histories exist for every player; committed strict transitions are projected and appended for each perspective; reset/snapshot/fork/failure boundaries are explicit. | No numeric seed/policy/horizon acceptance rule. |
| [`PerspectiveHistoryV1Test.kt`](../../gym/src/test/kotlin/com/wingedsheep/gym/PerspectiveHistoryV1Test.kt) | Structural History-D tests cover append ordinals, canonical stability, snapshots, identical suffixes, fork/reset/failure isolation, hidden-state non-interference, privacy, and snapshot integrity. | Its locked integration test is only 64 choices with `seed=0xD15EA5E5L`; it is not the accepted seed-0/4000 witness. |
| [`PostStep3522LockedHistoryFirstBlockerCharacterizationTest.kt`](../../gym/src/test/kotlin/com/wingedsheep/gym/PostStep3522LockedHistoryFirstBlockerCharacterizationTest.kt) at the reviewed base | `seed=0`, policy seed `0x41L`, fixed Akiri/Chevill decks, Commander, Akiri starts, skipped mulligans, 4000 successful choices, 4000 committed steps, no History-D exception, nonterminal horizon truncation. | It is one bounded run and does not assert a two-perspective canonical-byte comparison or a fresh-run repeat. |
| [`c0-perspective-history-known-information-characterization-2026-09-07.md`](./c0-perspective-history-known-information-characterization-2026-09-07.md) and [`c0-history-a-committed-perspective-event-source-2026-09-07.md`](./c0-history-a-committed-perspective-event-source-2026-09-07.md) | History-D is a later derived sequence/history layer; A explicitly did not claim complete event vocabulary or final history acceptance. The future HIST-01..13 matrix is still marked blocked/future. | No ratified current numeric History-D final gate. |
| [`pre-c1-performance-diagnostic-2026-09-08.md`](./pre-c1-performance-diagnostic-2026-09-08.md) | The former 64-choice History-D acceptance was historical evidence; the longer reachable path reopened the current History-D/Track-B gate. | No replacement final-acceptance matrix. |
| [Issue #100](https://github.com/chrismaghuhn/argentum-engine/issues/100) | B2 acceptance requires replay-backed trusted trajectories, family closure, terminal/interrupted closure, provenance, and deterministic reconstruction. | It is a B2/data gate, not a History-D final-acceptance definition. |
| [Issue #119](https://github.com/chrismaghuhn/argentum-engine/issues/119) | Post-B2 planning calls for a bounded 64-episode characterization and later measurement. | It explicitly is not an implementation authorization or History-D acceptance substitute. |
| [Issue #1](https://github.com/chrismaghuhn/argentum-engine/issues/1) | The roadmap requires the same pinned environment plus ordered semantic decisions to reproduce the same semantic trajectory, and separates B0, B1, B2, C0 and C1. | It does not specify a History-D seed/policy/horizon matrix. |

The CI workflow runs module and scenario tests and keeps coverage main-only; it does not contain a
History-D final-acceptance job. Green CI is therefore a delivery/process gate, not the missing
semantic contract.

## CURRENT_HISTORY_D_ACCEPTANCE_CONTRACT

The current contract is the intersection of these explicit requirements:

1. History is sourced only from successful committed strict Gym transitions; speculative forks,
   failed actions, legacy simulation, reset, restore, and replay reconstruction are not trusted
   event-source inputs.
2. Each perspective receives a perspective-safe semantic history. Raw runtime IDs, object stamps,
   raw coordinates, inaccessible identities, and internal known-information state are not model
   history.
3. `PerspectiveHistoryV1` has exact version/schema identity, a nonblank semantic episode identity,
   perspective identity, canonical semantic JSON, a SHA-256 semantic digest, and contiguous
   append-only perspective ordinals.
4. Snapshot/restore, reset, fork, and failed-transition behavior must preserve those boundaries;
   missing or invalid History-C/D evidence fails closed rather than being inferred.
5. A bounded exact locked run is useful acceptance evidence only when its workload, horizon, result,
   and failure state are explicitly recorded. The reviewed current witness records:

   ```text
   seed=0
   policySeed=0x41L
   Akiri starts
   skipMulligans=true
   Commander
   starting life=40
   fixed persisted decks
   maxSteps=4000
   successfulChoices=4000
   committedStep=4000
   terminated=false
   truncated=true because of the horizon
   History-D failures=0
   ```

There is no current authority that turns those workload settings into a universal claim over other seeds,
policies, orientations, or complete games.

## ALREADY_SATISFIED

- The structural History-D contract and its lifecycle/privacy/fail-closed tests are present.
- Accepted History-A/B/C/D event closures through the current locked Step-3522 boundary are
  preserved on `origin/main`; no current Step-3522 failure remains in the reviewed witness.
- The reviewed 4000-step locked witness crosses Step 3522 and completes its entire configured
  horizon with zero History-D failures.
- The runtime creates and maintains a history namespace for both players, and the structural tests
  exercise perspective isolation. This is infrastructure evidence, not a dedicated two-perspective
  4000-step acceptance assertion.
- The project-level B0/B1/B2 and `DATA_TRUSTED` status supplied for this audit is separate from the
  History-D final gate and is not reopened here.

## REMAINING_GATES

The following are not proven as a current History-D final-acceptance contract:

- A ratified definition of whether final acceptance is bounded-prefix acceptance or requires a
  naturally terminal game.
- A dedicated exact-locked-run assertion that compares both perspective histories at 4000 steps.
- A fresh-process repeat of that exact 4000-step run comparing both canonical history byte strings
  and semantic digests, as well as the semantic result/closure.
- Any required count of additional engine seeds, policy seeds, roster orientations, or starting
  players.
- Any required coverage claim for event families not reached by the locked witness.
- The relationship between a History-D bounded witness and the project-level
  `TRACK_B_HISTORY_COMPLETE` gate.

These are contract gaps, not evidence that the clean 4000-step witness failed.

## MINIMUM_ACCEPTANCE_MATRIX

No numeric matrix below is currently repo-authoritative. If a focused follow-up is authorized, the
smallest defensible bounded proposal is:

| Axis | Proposed minimum | Status |
| --- | --- | --- |
| Workload cell | One exact persisted Akiri-vs-Chevill cell: Commander, 40 life, skipped mulligans, Akiri starting, engine seed `0`, policy seed `0x41L`, current accepted code/decks. | Already witnessed once; adoption as a final gate is underspecified. |
| Horizon | `maxSteps=4000`; require 4000 committed choices/steps, no History-D failure, and explicit `INTERRUPTED/HORIZON_REACHED` semantics if the game is nonterminal. Do not extend to 8000 by implication. | Proposed bounded contract only. |
| Perspectives | Read and compare both player histories from the same committed run; require privacy-safe canonical JSON, contiguous ordinals, and digests for each. | Required by the per-perspective model, but not explicitly asserted by the 4000 witness. |
| Repeat | Two independent fresh process/episode runs with identical semantic inputs and reinitialized policy state; compare both canonical histories/digests and the semantic closure/result. | Smallest useful determinism check; not currently named as a History-D final-gate count. |
| Extra seeds/policies | None for the locked-cell minimum. | Additional values are confidence evidence unless explicitly ratified. |
| Natural terminal games | Not required by the current History-D code contract if bounded interruption is explicitly accepted. | Terminal/interrupted distribution is an existing B2/data requirement, not a demonstrated History-D requirement. |

In compact form, the proposal is:

```text
1 locked workload cell
× 2 fresh deterministic repeats
× 2 perspective histories inspected per run
```

This is a proposal for the next contract decision, not a claim that the repository already mandates
those numbers. A broader confidence matrix may reuse the B2 schedule—both roster orientations,
both starting players, and seeds `0..15`—but that is B2/data confidence evidence, not the smallest
History-D gate and must not be silently imported as a History-D requirement.

## Hard contract versus confidence/soak

### History-D hard contract

- committed-only source and lifecycle boundaries;
- perspective isolation and privacy-safe semantic payloads/references;
- exact V1 schema, canonicalization, digest, and contiguous ordinals;
- fail-closed handling of unsupported/incomplete authority;
- deterministic append/snapshot/reset/fork behavior covered by the structural tests;
- an explicitly adopted bounded or terminal workload criterion.

### Optional confidence or adjacent evidence

- multiple engine/policy seeds and roster/start orientations;
- terminal-game frequency, interrupted-prefix distribution, and failure/quarantine populations;
- long soak and 1/2/4/8 environment scaling;
- throughput, allocation, heap, GC, canonical-byte, or reader measurements;
- large trusted generation, sequence windows, learner smoke, BC, RL, self-play, or C1 experiments.

The first group is History-D correctness. The second group belongs mostly to B0 reliability, B2
trusted trajectory/data acceptance, B1 performance, or C0/C1 ML planning. None authorizes
performance optimization or training while the global gates remain closed.

## UNDERSPECIFIED_ITEMS

1. Whether `HISTORY_D_FINAL_ACCEPTANCE_PASS` means “the agreed bounded locked prefix is clean” or
   “a complete natural game is clean”.
2. The minimum number of fresh repeats and whether repeat identity is process-level or episode-level.
3. Whether both perspective histories must be byte/digest-compared in the same run or only tested
   structurally.
4. Whether alternate seeds, policy seeds, seat orientations, and starting players are hard gates or
   optional confidence cells.
5. Whether “all event families” means all families reachable by the locked workload or the entire
   current engine vocabulary, including unreachable/unsupported families.
6. Whether a bounded interruption is a valid History-D final result, independently of B2's explicit
   terminal/interrupted episode taxonomy.
7. Whether Hosted CI, independent review, and coverage status are recorded as process gates for a
   future History-D PR or are intended to be included in the semantic final-acceptance label.
8. How `TRACK_B_HISTORY_COMPLETE` is derived from bounded History-D evidence.

## Layer ownership

```text
History-D  = committed perspective-history composition, canonical sequence state, and its
              bounded/terminal completeness decision once explicitly defined.
B0         = environment reliability, public-choice control, and soak/recovery behavior.
B2         = replay-backed trajectory publication, episode closure, data provenance, and corpus
              acceptance (including its 64-cell bounded matrix).
B1         = performance/scaling measurement and optimization authorization.
C0/C1      = sequence/model/evaluation contracts, learner experiments, training and RL.
```

The remaining ambiguity is therefore primarily a History-D contract-definition issue. It is not a
new event-family fix, a B0 regression, a performance blocker, or a C1/training prerequisite that can
be resolved by running more steps.

## NEXT_SINGLE_TASK

```text
HISTORY_D_FINAL_ACCEPTANCE_CONTRACT_DEFINITION_02
```

Documentation/design-only: explicitly ratify the bounded-vs-terminal interpretation, the minimum
perspective/repeat matrix, and the boundary between History-D and B2/Track-B. After that decision,
run only the adopted bounded matrix in a separately authorized verification task. Do not extend the
locked horizon, add event support, or start performance/C1 work from this audit.

```text
HISTORY_D_FINAL_ACCEPTANCE_PASS=NO
HISTORY_D_CURRENT_GATE=OPEN
TRACK_B_HISTORY_COMPLETE=NO
PERFORMANCE_DIAGNOSTIC_PASS=BLOCKED
PERFORMANCE_OPTIMIZATION_AUTHORIZED=NO
C1_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
```

```text
STOP_FOR_EXACT_SHA_REVIEW=YES
```
