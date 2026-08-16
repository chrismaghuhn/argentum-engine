# Akiri, Fearless Voyager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement canonical ZNR `Akiri, Fearless Voyager` as a rules-faithful
card definition using the approved host-first resolution composition, prove its
trigger and activation contracts in one scenario test file, and publish one
reviewable Draft PR without changing generic engine behavior.

**Architecture:** Compose the existing per-defending-player attack trigger with
the existing `MayEffect`, `IfYouDo`, named `PipelineBuilder` collection slots,
`CardSource.AttachedTo`, `SelectFromCollectionEffect`,
`ForEachInCollectionEffect`, `UnattachEquipment`, `Tap`, and temporary keyword
grant primitives. Store the selected host and Equipment explicitly so the
post-unattach effects never rediscover the former attachment host.

**Tech Stack:** Kotlin, Gradle/JDK 21, Kotest scenario harness, existing Argentum
Engine SDK/card DSL, Git Bash for the repository's extensionless Bash wrapper
on Windows, and the repository's `just` verification recipes.

---

## Scope and file map

Create or modify only these task-owned files:

- Create
  `mtg-sets/2017-2022/src/main/kotlin/com/wingedsheep/mtg/sets/definitions/znr/cards/AkiriFearlessVoyager.kt`.
- Create
  `mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/AkiriFearlessVoyagerScenarioTest.kt`.
- Modify only the generated Akiri object in
  `mtg-sets/src/test/resources/snapshots/cards/ZNR.json`, using the approved
  snapshot recipe after the card is green.
- Modify only the A8-CARD-001 section in
  `docs/ml/curriculum/akiri-chevill-closure-backlog.md`; do not alter aggregate
  counts, decklists, or the Chevill/Commander items.
- The approved design and this plan are process artifacts under
  `docs/superpowers/`.

No generic SDK, rules-engine, server, client, AI, Gym, Commander, Chevill,
decklist, or ML-policy source file may change. If the card cannot be represented
with the existing primitives, stop and report the exact missing generic
capability instead of adding an Akiri-specific workaround.

## Task 1 — Establish the card RED-first

**Files:** Create only
`mtg-sets/2017-2022/tests/src/test/kotlin/com/wingedsheep/engine/scenarios/AkiriFearlessVoyagerScenarioTest.kt`.

- [x] Build the test with the existing `ScenarioTestBase`/`GameTestDriver`
  helpers and existing card names; do not create a production Akiri definition
  yet.
- [x] Cover `AKIRI-01` through `AKIRI-10`: equipped-attack grouping, repeated
  Equipment/attacker cases, distinct attacked players, mixed player/non-player
  defenders, and trigger resolution after Akiri or Equipment leaves.
- [x] Cover `AKIRI-11` through `AKIRI-18`: pay `{W}`, answer the resolution
  `may`, expose host and Equipment decisions, detach the selected Equipment,
  tap/grant indestructible to the stored host, decline without mutation, keep
  the unselected Equipment attached, allow an opponent-controlled Equipment,
  handle an already-tapped host, expire indestructible, and retain attachment
  invariants.
- [x] Cover `AKIRI-19` through `AKIRI-22`: activation with no legal resolution
  Equipment, resolution-time legal domains, no priority gap between subchoices
  and follow-up effects, and registry/snapshot/serialization/continuation
  boundaries exposed by existing helpers.
- [x] Assert explicitly that host and Equipment decisions are non-targeting
  `SelectCardsDecision` instances, that both decisions preserve their selected
  IDs, and that no test uses `first()`, random selection, or collection order.
- [x] Include the required subtle case: Player 1 controls the creature while
  Player 2 controls the attached Equipment. The first domain is determined by
  creature control; the second domain does not filter Equipment by controller.
- [x] Run the focused test before creating the card file. Use the repository
  recipe first:

  ```powershell
  just test-class AkiriFearlessVoyagerScenarioTest
  ```

  On this Windows checkout, the known extensionless Bash launcher can fail in
  Python before Gradle with `WinError 193`. The equivalent RED command is:

  ```powershell
  & 'C:\Program Files\Git\bin\bash.exe' -lc "cd /c/argentum-engine-akiri-r2 && scripts/gradle-locked :mtg-sets:2017-2022:tests:test --tests com.wingedsheep.engine.scenarios.AkiriFearlessVoyagerScenarioTest"
  ```

  The expected RED is a missing/unregistered Akiri card or the corresponding
  absent behavior, not a Kotlin compile failure in the new test. If the test
  itself does not compile, fix only the test harness usage before production
  work.

  **RED evidence captured on 2026-08-16:** before the production card file
  existed, the focused test compiled and failed with
  `Card not found in registry: Akiri, Fearless Voyager` (12 failing cases).

## Task 2 — Add the minimal canonical card definition

**File:** Create
`mtg-sets/2017-2022/src/main/kotlin/com/wingedsheep/mtg/sets/definitions/znr/cards/AkiriFearlessVoyager.kt`.

- [x] Use canonical ZNR metadata from the Scryfall `set=znr` lookup: mana cost
  `{1}{R}{W}`, `Legendary Creature — Kor Warrior`, 3/3, red-white identity,
  rare, collector `220`, artist `Ekaterina Burmak`, the current flavor text,
  and the exact normal image URI
  `https://cards.scryfall.io/normal/front/6/9/69e42511-f653-4a6f-a5d4-50e21dfc8077.jpg`.
- [x] Use the exact current Oracle text and do not put `{W}` in the resolution
  description as though it were a `may` payment.
- [x] Model the trigger with:

  ```kotlin
  Triggers.YouAttackPlayerWithFilter(
      GameObjectFilter.Creature.youControl().equipped()
  )
  ```

  and `Effects.DrawCards(1)`.
- [x] Model the activated ability with `Costs.Mana("{W}")` and a
  `MayEffect` whose feasibility check is
  `FeasibilityCheck.ControlsPermanentMatching(GameObjectFilter.Creature.youControl().equipped())`.
  The feasibility check may suppress an impossible resolution-time prompt; it
  must not make `{W}` an optional activation cost or a target restriction.
- [x] Use this exact pipeline shape, with named slots and no arbitrary
  selection:

  ```kotlin
  effect = MayEffect(
      effect = Effects.IfYouDo(
          action = Effects.Pipeline {
              val hosts = gather(
                  GameObjectFilter.Creature.youControl().equipped(),
                  name = "hostCandidates",
              )
              val host = chooseExactly(
                  count = 1,
                  from = hosts,
                  prompt = "Choose a creature you control with an attached Equipment",
                  alwaysPrompt = true,
                  name = "host",
              )
              val equipment = gather(
                  CardSource.AttachedTo(
                      host = EffectTarget.PipelineTarget("host"),
                      filter = GameObjectFilter.Artifact.withSubtype(Subtype.EQUIPMENT),
                  ),
                  name = "equipmentCandidates",
              )
              val chosenEquipment = chooseExactly(
                  count = 1,
                  from = equipment,
                  prompt = "Choose an Equipment to unattach",
                  alwaysPrompt = true,
                  name = "chosenEquipment",
              )
              run(
                  ForEachInCollectionEffect(
                      collection = chosenEquipment.key,
                      effect = Effects.UnattachEquipment(EffectTarget.Self),
                  )
              )
          },
          ifYouDo = Effects.Composite(
              Effects.Tap(EffectTarget.PipelineTarget("host")),
              Effects.GrantKeyword(
                  Keyword.INDESTRUCTIBLE,
                  EffectTarget.PipelineTarget("host"),
                  Duration.EndOfTurn,
              ),
          ),
          successCriterion = SuccessCriterion.CollectionNonEmpty("chosenEquipment"),
      ),
      feasibility = FeasibilityCheck.ControlsPermanentMatching(
          GameObjectFilter.Creature.youControl().equipped(),
      ),
  )
  ```

- [x] Keep both selections non-targeting by leaving `useTargetingUI = false`
  and using collection selection rather than `target(...)`.
- [x] Use the stored `host` slot for tap and indestructible. Do not use
  `EffectTarget.EquippedCreature`, reconstruct the host after unattach, or
  filter the second domain with `.youControl()`.

## Task 3 — Focused green pass and card-level audit

**Files:** The card and its one scenario test only.

- [x] Run the focused Akiri scenario through the Git Bash Gradle command from
  Task 1 and make it green. Keep changes limited to the card composition or
  its card test assertions/setup.
- [x] Verify the pending-decision sequence: after the `may` is accepted,
  host selection is pending, then Equipment selection is pending, then
  unattach/tap/indestructible run contiguously without normal priority.
- [x] Verify the second-domain filter against an opposing controller and against
  an unattached/non-Equipment/other-host attachment.
- [x] Verify `ifYouDo` is keyed to the selected Equipment collection, not to the
  host's tapped state; an already-tapped host still gets indestructible.
- [x] Verify source-leaves-before-resolution and trigger-created-before-leave
  cases use the normal source-independent effect/trigger behavior.
- [x] Run the card lint/coverage checks required by the card workflow:

  ```powershell
  just check-card-printing "Akiri, Fearless Voyager"
  ```

  The Scryfall authority comparison must match name, mana cost, type line,
  Oracle text, P/T, rarity, collector number, artist, flavor, and image URI.

## Task 4 — Registry, snapshot, and closure bookkeeping

**Files:** `mtg-sets/src/test/resources/snapshots/cards/ZNR.json` and the
Akiri section of `docs/ml/curriculum/akiri-chevill-closure-backlog.md`.

- [x] Confirm `CardDiscovery` finds the top-level `AkiriFearlessVoyager` value
  without a manual registry entry.
- [x] Run the ZNR snapshot gate and inspect the diff. Rebless only if the
  snapshot change is exactly the new Akiri object; do not normalize or rebless
  unrelated cards.
- [x] Exercise the existing card-loader/serialization round-trip path and
  verify the named pipeline slots and card metadata survive it.
- [x] Update only A8-CARD-001 from “missing exact card definition” to the
  evidence-backed Akiri closure status, listing the focused matrix and exact
  verification evidence. Do not mark A8-FEATURE-001, Chevill, aggregate closure,
  decklists, or ML policy as resolved by this card.

## Task 5 — Regression verification

**Files:** No new production files expected.

- [x] Run the focused Akiri test and relevant existing equipment/unattach,
  duration, trigger, continuation, and serialization tests.
- [x] Run the minimum repository gates through `just`; where the Windows
  launcher fails before Gradle, use the exact Git Bash equivalent and record the
  wrapper limitation separately:

  ```powershell
  & 'C:\Program Files\Git\bin\bash.exe' -lc "cd /c/argentum-engine-akiri-r2 && just build"
  & 'C:\Program Files\Git\bin\bash.exe' -lc "cd /c/argentum-engine-akiri-r2 && just test"
  ```

- [x] At minimum run `:rules-engine:test`, `:mtg-sdk:test`,
  `:mtg-sets:2017-2022:tests:test`, and `:game-server:test` through the
  repository's locked wrapper. AI/Gym gates are not affected by a card-only
  change, but run them if the repository's standard card gate includes them.
- [x] Run `git diff --check`, conflict-marker scan, and the frozen AI baseline
  test. The existing expected `FrozenBaselineTest` hash is
  `6ff9ded1403d59ac`; if it changes, stop and report the unrelated baseline
  drift without reblessing it.
- [x] Confirm `git status` contains no edits outside the approved file map.

## Task 6 — Commit, publish, and Draft PR

- [x] Review `git diff origin/main...HEAD --name-status` and ensure there are no
  generic-engine or unrelated curriculum changes.
- [x] Commit the implementation as one semantic card commit after the tests
  are green, with a message such as
  `Add Akiri, Fearless Voyager to Zendikar Rising`. The already-approved Spec
  and Plan documentation may be committed separately before implementation.
- [x] Verify the branch is `agent/a8-card-001r2-akiri` and `origin` is exactly
  `https://github.com/chrismaghuhn/argentum-engine.git`.
- [ ] Push the branch without force-push or rebase.
- [ ] Open exactly one Draft PR targeting `chrismaghuhn/argentum-engine:main`.
  The PR body must report the host-first semantics, the opponent-controlled
  Equipment test, all local gates, the Windows wrapper limitation if it
  remains, and any unverified hosted checks.
- [ ] Do not merge, mark Ready, enable auto-merge, touch unrelated PRs, or
  claim completion before the final verification output is captured.

## Final self-review checklist

- [x] The card's “may” is resolution-time and `{W}` is always paid on
  activation.
- [x] The host domain is exactly controller-controlled equipped creatures.
- [x] The Equipment domain is exactly all Equipment attached to the stored host,
  independent of Equipment controller.
- [x] Neither choice is a target; both are externally represented decisions;
  no priority gap exists between subchoices or follow-up effects.
- [x] No `first()`, collection-order, random, or hidden automatic Equipment
  choice exists.
- [x] The stored host is used after unattach.
- [x] No generic production source file changed.
- [x] The card was compared to current Scryfall data and the snapshot diff is
  card-local.
- [x] The final report separates confirmed green gates, wrapper limitations,
  and any hosted/unknown verification.
