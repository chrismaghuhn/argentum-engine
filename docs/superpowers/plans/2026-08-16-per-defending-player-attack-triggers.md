# Per-defending-player attack trigger implementation plan

> **For agentic workers:** Execute this plan in the current isolated worktree
> `C:\\argentum-engine-attack-grouping`. Preserve the approved scope and use
> RED-first development. Do not implement Akiri.

**Implementation status:** In progress.

**Goal:** Add the smallest generic SDK/engine capability for one attack trigger
instance per distinct Player directly attacked by at least the configured number
of qualifying attackers, while preserving all existing attack-trigger
cardinalities and excluding non-player attack targets.

**Architecture:** Snapshot the authoritative declaration map into a canonical
serializable `List<DeclaredAttack>` on `AttackersDeclaredEvent`. Add an
explicit `EventPattern.YouAttackPlayerEvent` and facade. Resolve its
multiplicity as a deterministic list of attacked Player IDs at the generic
matcher/detector boundary. Create one `PendingTrigger` per returned Player
with `TriggerContext(triggeringPlayerId = attackedPlayer)`, then hand all
instances to the existing APNAP/simultaneous-trigger pipeline.

**Scope guard:** No Akiri definition, draw/protection ability, equipment-specific
engine code, combat legality change, damage change, Commander change, Gym/ML
change, replay policy change, snapshot reblessing, merge, rebase, or unrelated
cleanup.

## File map

- Modify
  `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/GameEvent.kt`
  to add serializable `DeclaredAttack` and the backward-compatible
  `declaredAttacks` event field.
- Modify
  `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/AttackPhaseManager.kt`
  to emit the canonical `DeclaredAttack` list from the declaration map.
- Modify
  `mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/scripting/EventPattern.kt`
  to add `YouAttackPlayerEvent` and any required description/text helpers.
- Modify
  `mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/dsl/Triggers.kt`
  to expose the explicit generic facade without changing
  `YouAttackWithFilter`.
- Modify
  `rules-engine/src/main/kotlin/com/wingedsheep/engine/event/TriggerIndex.kt`
  to index the new pattern under `ATTACKERS_DECLARED`.
- Modify
  `rules-engine/src/main/kotlin/com/wingedsheep/engine/event/TriggerMatcher.kt`
  to implement per-player matching and preserve all existing Boolean paths.
- Modify
  `rules-engine/src/main/kotlin/com/wingedsheep/engine/event/TriggerDetector.kt`
  to consume the complete player-match list and create one context-bound
  pending trigger per group; audit delayed/duplicate/attack-caused paths.
- Modify `docs/card-sdk-language-reference.md` to document the new public SDK
  vocabulary and its per-player/minimum semantics.
- Create
  `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/PerDefendingPlayerAttackTriggerScenarioTest.kt`
  for the synthetic generic acceptance matrix and existing-cardinality
  regressions.
- Create
  `rules-engine/src/test/kotlin/com/wingedsheep/engine/event/AttackersDeclaredEventSerializationTest.kt`
  for canonical event ordering, round-trip, historical-payload fail-closed
  behavior, and equivalent fork/declaration grouping.
- Create
  `mtg-sdk/src/test/kotlin/com/wingedsheep/sdk/serialization/AttackTriggerPatternSerializationTest.kt`
  for the new event-pattern/facade serialization shape, if the current package
  naming confirms this path; otherwise keep the test in the existing SDK
  serialization test package without adding a new production dependency.

## Task 1: Establish RED tests before production changes

**Files:**

- Create
  `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/PerDefendingPlayerAttackTriggerScenarioTest.kt`
- Create
  `rules-engine/src/test/kotlin/com/wingedsheep/engine/event/AttackersDeclaredEventSerializationTest.kt`
- Create or modify the focused SDK serialization test identified above.

- [ ] Build a synthetic triggered ability/card fixture that uses the new
  intended generic pattern shape but no Akiri definition, no Akiri name, and no
  hard-coded Equipment logic.
- [ ] Add ATTACK-GROUP-01 through ATTACK-GROUP-10. Assert both trigger count and,
  for ATTACK-GROUP-10, the exact set/sequence of
  `triggerContext.triggeringPlayerId` values.
- [ ] Add `minAttackers = 2` coverage:
  two qualifying attackers to B matches B;
  one to B plus one to C matches neither;
  one to B plus one to C must produce zero matches.
- [ ] Add canonical-ordering tests proving equivalent declaration maps with
  reversed insertion order produce identical `declaredAttacks` serialization
  and identical final per-player trigger order.
- [ ] Add historical-payload tests where `declaredAttacks` is absent but
  legacy attacker fields are populated; the new pattern must produce no
  invented matches.
- [ ] Add representative regressions for ordinary `YouAttack`, existing
  `YouAttackWithFilter`, per-attacker `AttackEvent`, and a combat-damage
  trigger.
- [ ] Run the focused test before adding production support. Capture explicit
  RED evidence for ATTACK-GROUP-03: current code must fail to represent or
  produce the required two matches.
- [ ] Attempt the repository-approved `just` focused recipe first. If its
  Windows bash launcher fails before Gradle, use the documented
  `gradlew.bat`/JDK 21 fallback and record the wrapper failure separately.

## Task 2: Add the canonical attack-event snapshot

**Files:**

- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/GameEvent.kt`
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/AttackPhaseManager.kt`
- Test
  `rules-engine/src/test/kotlin/com/wingedsheep/engine/event/AttackersDeclaredEventSerializationTest.kt`

- [ ] Add immutable serializable `DeclaredAttack(attackerId, defenderId)`.
- [ ] Add `declaredAttacks: List<DeclaredAttack> = emptyList()` without
  changing existing field meanings or event registration names.
- [ ] Emit entries sorted first by `attackerId`, then by `defenderId`; do not
  rely on declaration-map iteration order.
- [ ] Keep live `AttackingComponent` as existing combat authority/state and do
  not add a second target component.
- [ ] Make historical absent-field payloads decode successfully and remain
  fail-closed for the new pattern.
- [ ] Run the serialization test and compare the encoded event from reversed
  equivalent input-map order byte-for-byte or structurally, according to the
  repository's JSON comparison convention.

## Task 3: Add the explicit SDK pattern

**Files:**

- Modify `mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/scripting/EventPattern.kt`
- Modify `mtg-sdk/src/main/kotlin/com/wingedsheep/sdk/dsl/Triggers.kt`
- Modify `docs/card-sdk-language-reference.md`
- Test the SDK pattern serialization.

- [ ] Add serializable `YouAttackPlayerEvent(minAttackers = 1,
  attackerFilter = null)`.
- [ ] Expose a generic facade for filtered use; retain existing
  `YouAttack`/`YouAttackWithFilter` constructors unchanged.
- [ ] Update all exhaustive event-pattern descriptions/text replacement or
  validation paths required by the compiler.
- [ ] Ensure the new SDK type serializes/deserializes with the existing card
  serializer and the equipped-creature filter remains ordinary reusable filter
  vocabulary.
- [ ] Document that minimum count is applied per attacked Player after filter
  evaluation and that non-player targets do not count.

## Task 4: Implement result-producing matching and detection multiplicity

**Files:**

- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/event/TriggerIndex.kt`
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/event/TriggerMatcher.kt`
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/event/TriggerDetector.kt`
- Test the synthetic scenario matrix.

- [ ] Index `YouAttackPlayerEvent` with `ATTACKERS_DECLARED`.
- [ ] Implement a result-producing matcher that:
  reads only `declaredAttacks`;
  evaluates the existing filter against projected state;
  filters targets by actual Player identity;
  groups after qualification;
  applies `minAttackers` per Player;
  returns deterministic Player IDs.
- [ ] Treat empty `declaredAttacks` as missing/no per-player information and
  return no matches. Never infer a target from a controller, protector,
  `attackersAgainstPlayer`, or live combat state.
- [ ] Use an explicit
  `TriggerContext(triggeringPlayerId = attackedPlayer)` for each pending
  trigger. Leave `TriggerContext.fromEvent(AttackersDeclaredEvent)`
  unchanged.
- [ ] Add one pending trigger per matching Player, then let the existing APNAP
  and simultaneous-trigger ordering pipeline sort them.
- [ ] Audit and update delayed-trigger matching, duplicate attack-trigger
  handling, attack-caused-trigger classification, and any exhaustive event
  dispatch so the new opt-in type neither disappears nor collapses to one
  Boolean trigger.
- [ ] Verify existing `YouAttack`, `YouAttackWithFilter`, per-attacker
  `AttackEvent`, and combat-damage paths do not change cardinality.

## Task 5: Green focused matrix and targeted regression

**Files:**

- Test files from Tasks 1–4.
- Existing representative tests only if a narrowly scoped assertion/update is
  required by the new event field defaults.

- [ ] Run the focused generic matrix and confirm:
  ATTACK-GROUP-01 through ATTACK-GROUP-09 PASS;
  ATTACK-GROUP-10 PASS, or document a justified N/A only if the model truly
  cannot preserve the binding.
- [ ] Confirm same-player deduplication, distinct-player multiplicity,
  planeswalker/battle exclusion, and non-qualifying exclusion.
- [ ] Confirm filter evaluation still observes existing attachment state,
  including `GameObjectFilter.Creature.equipped()`, without adding
  Akiri-specific code.
- [ ] Confirm deterministic ordering under reversed declaration-map insertion
  order and normal APNAP sorting.
- [ ] Run existing attack and multiplayer tests, including
  `AttacksAnOpponentScenarioTest`,
  `WheneverACreatureYouControlWithMenaceAttacksTest`, and
  `MultiDefenderCombatTest`.
- [ ] Record `AKIRI_06_ENGINE_PRIMITIVE = SATISFIED` only if the generic
  equivalent case passes. Do not add or mark any Akiri card as implemented.

## Task 6: Full local verification and frozen baseline

**Files:** no additional production files expected.

- [ ] Verify with JDK 21. Attempt repository `just` gates first; if the
  extensionless/bash launcher remains unavailable on Windows, run equivalent
  Gradle wrapper tasks and document the exact limitation.
- [ ] Run at minimum:
  `:rules-engine:test`
  `:mtg-sdk:test`
  `:mtg-sets:scenarioTest`
  `:game-server:test`
  `:ai:test`
  `:gym:test`
  `:gym-server:test`
  `:gym-trainer:test`
  `:oracle-assay:test`
  plus `npm run build` and `npm run test -- --run` in `web-client`.
- [ ] Run the existing `ai/.../FrozenBaselineTest.kt` directly through the
  appropriate test task and record `OLD_HASH` and `NEW_HASH`. Required
  historical hash is `6ff9ded1403d59ac`; if it changes, stop and classify
  `ARG_RULES_ATTACK_GROUPING_BLOCKED` without reblessing.
- [ ] Do not rebless or normalize CardDefinition snapshots. Classify only
  failures reproduced on clean base as pre-existing.

## Task 7: Diff audit, publication, and hosted CI

- [ ] Run `git status`, `git diff --check`, and conflict-marker scan.
- [ ] Review `git diff origin/main...HEAD --stat` and
  `git diff origin/main...HEAD --name-status` for only approved production,
  test, SDK-doc, and design/plan files.
- [ ] Fetch upstream once for publication bookkeeping and record
  `UPSTREAM_MAIN_AT_PUBLICATION`; do not chase unrelated commits.
- [ ] Commit semantic changes separately where practical:
  red tests, production primitive, and serialization/docs/regression closure.
- [ ] Push `agent/rules-attack-grouping` to the configured origin without
  force-push or rebase.
- [ ] Verify origin remains
  `https://github.com/chrismaghuhn/argentum-engine.git`.
- [ ] Open exactly one Draft PR targeting `chrismaghuhn/argentum-engine` only
  after local gates pass. Do not merge, enable auto-merge, or mark Ready.
- [ ] Wait for hosted CI on the exact final head. If it fails, classify the
  failure before changing code.

