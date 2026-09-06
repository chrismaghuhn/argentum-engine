# B2 A9 serialized decision-family closure audit

Date: 2026-09-06

## Purpose

The fresh 64-episode A9 run already passed replay, A5/A6, publication, A7 reopening, privacy,
and determinism. This addendum closes the remaining acceptance review item: the A9 evidence must
prove that the serialized decision surfaces are classified against the accepted A8 matrix.

This audit did not restart Gym, Rules gameplay, or the A9 matrix. It opened the existing finalized
dataset through the strict A7 reader and derived the values below from the persisted
`TrajectoryV1` records only.

## Dataset and execution

```text
DATASET_ID=69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03
MANIFEST_CONTENT_DIGEST=de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2
EPISODE_COUNT=64
DECISION_COUNT=125471
SHARD_COUNT=64
READER=TrajectoryV1Reader.openPublishedDataset
READER_STREAM=64
GAMEPLAY_RESTARTED=NO
DATASET_REWRITTEN=NO
```

The opt-in audit task uses an 8 GB test-worker heap and a two-hour Kotest timeout because A7's
manifest preflight revalidates and holds the large persisted trajectories before streaming them.
The ordinary `:gym:test` task excludes both the A9 generator and this audit task.

## Authority boundary

The audit reads only:

* `observationBefore.pendingDecision.kind` for serialized actor-owned pending families;
* `completeLegalDomain.candidates` for persisted candidate-kind inventory;
* `chosenSemanticAction.candidate.kind` for the actually chosen serialized action kinds;
* the chosen candidate's stored `requiredPayloadFields`.

It does not access `GameState`, `CardRegistry`, `ManaSolver`, live action registries, or hidden
information. Unknown serialized action or pending names fail closed. An actor-owned serialized
`GENERIC` pending boundary also fails closed; the A8 privacy sentinel is not counted as a policy
family.

## Serialized audit output

The exact output from the finalized dataset is:

```text
A8_CLOSURE_AUDIT=PASS
A8_CLOSURE_DATASET_ID=69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03
A8_CLOSURE_EPISODE_COUNT=64
A8_CLOSURE_DECISION_COUNT=125471
SERIALIZED_CANDIDATE_ACTION_KINDS={ActivateAbility=1405807, CastSpell=7714, CastSpellMode=1275, CastWithFlashback=1278, CastWithKicker=1438, CycleCard=844, DECISION=17887, DeclareAttackers=388, PassPriority=122444, PlayLand=2778}
SERIALIZED_CHOSEN_ACTION_KINDS={ActivateAbility=43017, CastSpell=655, CastSpellMode=66, CastWithKicker=10, CycleCard=41, DeclareAttackers=388, PassPriority=76523, PlayLand=2132}
SERIALIZED_PENDING_DECISION_FAMILIES={CHOOSE_COLOR=129, CHOOSE_TARGETS=68, SELECT_CARDS=2336, YES_NO=106}
SERIALIZED_REQUIRED_PAYLOAD_FIELDS={additionalCostPayment=27, attackers=388, bands=388, costPayment=488, manaColorChoice=1082, paymentStrategy=4235, repeatCount=1560, targets=1955}
A8_ABSENT_REACHABLE_FAMILIES=[ORDER_OBJECTS, CHOOSE_OPTION, REORDER_LIBRARY, COMBAT_RESOLUTION, SELECT_MANA_SOURCES, DECISION, CastWithFlashback, DeclareBlockers]
A8_ABSENT_REACHABLE_COVERED_BY_ACCEPTED_A8_EVIDENCE=[ORDER_OBJECTS: A8 targeted trigger-order witness, CHOOSE_OPTION: A8 targeted Outpost Siege option witness, REORDER_LIBRARY: A8 targeted Read the Bones ordering witness, COMBAT_RESOLUTION: A8 targeted trample/combat-resolution witness, SELECT_MANA_SOURCES: A8 targeted Mentor/Battlefield Forge payment witness, DECISION: A8 folded-decision contract, CastWithFlashback: A8 static locked-definition witness, DeclareBlockers: A8 targeted blocker-domain witness]
A8_UNCLASSIFIED=[]
A8_REAL_BLOCKERS=[]
FAIL_FAST_PROVEN_ZERO=YES
```

The absent list is not treated as random unreachability. Every absent reachable family is covered
by the corresponding accepted A8 targeted/static witness. Families classified unreachable by A8
were not observed in the serialized actor-owned surfaces. The current dataset therefore has:

```text
ALL_CURRENT_FAMILIES_CLASSIFIED=YES
UNCLASSIFIED=[]
REAL_BLOCKERS=[]
DECISION_FAMILY_CLOSURE=PASS
```

## Evidence integration

`EnvironmentV1TrustedGenerationTest` now performs the same serialized audit in its final A7 reader
pass, retains the audit result in `A9GenerationEvidence`, and renders:

```text
FAIL_FAST_PROVEN_ZERO=YES
LIVE_OBSERVED_ACTION_KINDS=<aggregated per-episode observation evidence>
LIVE_OBSERVED_DECISION_FAMILIES=<aggregated per-episode pending evidence>
LIVE_REQUIRED_PAYLOAD_FIELDS=<aggregated per-episode required fields>
SERIALIZED_CANDIDATE_ACTION_KINDS=<reader-derived inventory>
SERIALIZED_CHOSEN_ACTION_KINDS=<reader-derived chosen kinds>
SERIALIZED_PENDING_DECISION_FAMILIES=<reader-derived pending families>
SERIALIZED_REQUIRED_PAYLOAD_FIELDS=<reader-derived chosen fields>
A8_CLOSURE_AUDIT=PASS
```

The hard-coded zero counters in the existing generation evidence remain explicitly labeled as
`FAIL_FAST_PROVEN_ZERO`: an unsupported diagnostic, public-choice rejection, non-exact replay,
incomplete binding, or quarantine aborts the run before evidence finalization. They are not used as
a substitute for the reader-derived closure audit.

## Verification

```text
FOCUSED_A7_CLOSURE_AUDIT=PASS
TESTS=1
FAILURES=0
CONFIGURED_SKIPS=0
GRADLE_TIME=44m40s
```

The post-audit surrounding regressions also remained green:

```text
:gym:test=PASS, 590 tests, 0 failures, 6 configured SKIPPED
:gym-trainer:test=PASS, 147 tests, 0 failures, 1 configured SKIPPED
:game-server:test=PASS, 609 tests, 0 failures, 13 configured SKIPPED
git diff --check=PASS
```

The audit was run with native `gradlew.bat --no-daemon` on Windows because the repository's
`just`/locked wrapper is unavailable before Gradle on this host. No hosted CI was run by this
local characterization update.

## Status

```text
A9_FAMILY_CLOSURE_P1=CLOSED
A9_GENERATION_RESUMED=NO
DATASET_GENERATION_RUN=NO_NEW_GENERATION
A9_FINAL_ACCEPTANCE_PASS=NO
B2_FINAL_ACCEPTANCE_PASS=NO
DATA_TRUSTED=NO
C0_AUTHORIZED=NO
TRAINING_AUTHORIZED=NO
PR_CREATED=NO
```

The original A9 generation report remains the source for the 64-episode replay/publication
evidence. This file is its serialized decision-family closure addendum.
