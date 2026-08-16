# Modern Combat Damage A2.2R1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by task with verification checkpoints.

**Goal:** Close the two independent-review P1 gaps in PR #10 without changing the A2.2 architecture: preserve first-step First/Double Strike eligibility across the second damage step and surface blocker-only multi-attacker damage choices as a complete live assignment graph.

**Architecture:** Store the historical First/Double Strike flags on combat creatures when the first combat-damage step begins, use that immutable snapshot only for regular-step eligibility, and remove it with the other combat-duration state. Extend the existing `CombatResolutionDecision` graph builder with blocker-centered candidates so a blocker that deals damage to multiple attackers can own editable `BLOCKER_TO_ATTACKER` edges even when every attacker is ineligible in the first step. Ordinary assignment remains arbitrary; trample lethal requirements remain validator semantics.

**Tech Stack:** Kotlin, immutable ECS `GameState`, Kotlin serialization, Kotest, Gradle/JDK 21.

---

### Task 1: Historical eligibility regression tests

**Files:**
- Modify: `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/FirstStrikeCombatTest.kt`

- [x] **Step 1: Add live tests for First Strike loss, First Strike gain, and Double Strike loss between steps.**

  Each test must enter the first-strike step, change only the live `CardComponent.baseKeywords` before regular damage, and assert the defending player is damaged exactly once when the historical rule requires it. Use an existing first-strike creature to force the first step for the gain case; use `Keyword.DOUBLE_STRIKE` on an existing test creature for the double-strike case.

- [x] **Step 2: Run only the new tests and confirm they fail for the current-keyword implementation.**

  Run `.\gradlew.bat :rules-engine:test --tests "com.wingedsheep.engine.scenarios.FirstStrikeCombatTest" --no-daemon --max-workers=2 --console=plain`.

  Expected RED evidence: the loss cases deal an extra hit or the gain case misses its regular hit because `dealsDamageThisStep` reads only current keywords.

### Task 2: First-step eligibility snapshot

**Files:**
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/state/components/combat/CombatComponents.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDamageManager.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatManager.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/effects/ZoneMovementUtils.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/Serialization.kt`

- [x] **Step 1: Add a serializable per-creature snapshot component.**

  The component records whether the creature had First Strike and Double Strike when the first damage step began. Register it as a polymorphic `Component` so paused/serialized continuations retain the semantic state.

- [x] **Step 2: Capture the snapshot at the top of the first-strike damage entrypoint.**

  Capture current projected keywords for all live attacking and blocking creatures exactly once before any prevention or assignment decision. Re-entrant continuation calls must not overwrite it.

- [x] **Step 3: Replace every damage-step eligibility read with the snapshot-aware rule.**

  First step uses current First/Double Strike. Regular step uses `!hadFirstStrike && !hadDoubleStrike` from the snapshot, or current Double Strike, and falls back to current keywords when no first-step snapshot exists because the first step was skipped.

- [x] **Step 4: Remove the snapshot on end combat, zone changes, and combat removal/regeneration paths.**

- [x] **Step 5: Run the focused FirstStrikeCombatTest and confirm GREEN.**

### Task 3: Blocker-only live regression test

**Files:**
- Modify: `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/CombatResolutionBoardTest.kt`

- [x] **Step 1: Add a custom test-only First Strike blocker with `CanBlockAnyNumber`.**

  Declare two ordinary attackers blocked by that one blocker, enter the first-strike damage step, and assert a live `CombatResolutionDecision` is present even though both attacker nodes have `dealsDamageThisStep == false`.

- [x] **Step 2: Assert the graph contains editable blocker-to-attacker edges and no silent auto-distribution.**

  Both edges must be owned by the blocker controller, total the blocker's power, and target both attackers. Submit a non-default split to prove the response path writes the chosen blocker assignment.

- [x] **Step 3: Run the focused board test and confirm RED before production changes.**

  Run `.\gradlew.bat :rules-engine:test --tests "com.wingedsheep.engine.scenarios.CombatResolutionBoardTest" --no-daemon --max-workers=2 --console=plain`.

### Task 4: Blocker-centered assignment graph

**Files:**
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDamageManager.kt`

- [x] **Step 1: Collect blocker candidates independently of attacker candidates.**

  Include a live blocker that deals damage this step, blocks at least two live attackers, and has no completed assignment. Do not require any of those attackers to deal damage in the current step.

- [x] **Step 2: Extend graph emission without duplicating existing attacker semantics.**

  Build attacker nodes from both attacker candidates and blocker candidate relations, mark normal first-step attackers as ineligible, retain attacker-side trample/drain edges only for attacker candidates, and emit blocker-to-attacker edges for blocker candidates with the blocker controller/banding chooser authority.

- [x] **Step 3: Preserve complete-plan validation and continuation behavior.**

  A blocker-only graph must have a valid source total, use the existing response validator/resumer, and re-enter the same damage step only after every chooser submits. Remove the neutral blocker distribution fallback from this live path by ensuring the graph is emitted first.

- [x] **Step 4: Run focused blocker and combat regression tests.**

### Task 5: Synchronization and verification

**Files:**
- Modify: `docs/superpowers/plans/2026-08-16-modern-combat-damage-r1.md`
- Modify: existing PR #10 body through the GitHub API/CLI only after verification.

- [x] **Step 1: Run the focused A2.2 matrix and all combat regression classes.**

- [x] **Step 2: Run the full local regression, frozen baseline, web-client gates, and `git diff --check`.**

- [x] **Step 3: Fetch `origin/main`, verify it is the reported current head, and merge it into the PR branch without rebasing or force-pushing.**

- [x] **Step 4: Repeat focused and full gates after synchronization.**

- [ ] **Step 5: Commit the R1 changes, push the existing Draft branch, and verify PR #10 remains Draft/not merged while hosted checks run on the new head.**
