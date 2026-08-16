# Modern Combat Damage Conformance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. The plan was executed in this worktree; the verification record is summarized below.

**Implementation status:** Complete. The focused A2.2 matrix, relevant combat suites, full repository regression, frozen AI baseline, server contract test, and web-client build/test gates are green. `just test-rules` was attempted first but is unavailable in this Windows environment because WSL2 is not active; the documented Gradle fallback passed.

**Goal:** Make the existing combat-resolution graph conform to modern Magic combat damage semantics while making legacy damage-assignment order and generic lethal-first paths unreachable in current gameplay.

**Architecture:** Keep `CombatResolutionDecision` as the single semantic assignment-plan boundary. Extract final-plan validation into a pure rules helper that uses the decision nodes and all edge amounts, then remove order-component reads/writes from gameplay and replace lethal-first defaults with deterministic arbitrary legal splits. Preserve old serialized shapes only at the compatibility boundary. Leave the existing downstream damage modifier and simultaneous-application pipeline intact.

**Tech Stack:** Kotlin, JDK 21, Gradle/Kotest, immutable ECS `GameState`, `CombatResolutionDecision` continuations, React/TypeScript wire compatibility.

---

## File map

- Create `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDamageAssignmentPlanValidator.kt` for pure complete-plan arithmetic, ordinary unrestricted splits, same-step aggregate trample lethality, and final-plan completeness.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/decision/DecisionValidators.kt` to perform shape/edge checks and delegate semantic checks to the pure validator.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDamageManager.kt` to build arbitrary modern defaults, stop reading legacy order components, and keep one complete plan before the existing damage batch.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/continuations/CombatContinuationResumer.kt` to ignore legacy order maps and only materialize `DamageAssignmentComponent` from the cached graph.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatManager.kt` and `CombatRemovalHelper.kt` only where current-step cleanup currently treats legacy order as active gameplay state.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/DamageCalculator.kt` so gameplay callers no longer obtain order-dependent or generic lethal-first distributions; retain only neutral arithmetic helpers needed by other mechanics.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/combat/OrderBlockersHandler.kt` and `CombatModule.kt` so the old action remains decodable but cannot be used by current gameplay.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/view/ClientStateTransformer.kt` so new client state never exposes a live order component; retain the nullable DTO field for old payload decoding.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/CombatResolution.kt` and related comments to mark order fields as compatibility-only without changing their serialized names.
- Create `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/ModernCombatDamageConformanceTest.kt` as the engine-level COMBAT-01..24 matrix; it contains no card-definition work outside local synthetic test fixtures.
- Modify existing combat-resolution tests only when their assertions describe obsolete order or lethal-first behavior; keep each change focused on the modern contract.
- Add the design specification and this plan; no SDK language-reference change is needed because no SDK vocabulary is added.

### Task 1: Establish RED tests for the modern assignment contract

**Files:**
- Create: `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/ModernCombatDamageConformanceTest.kt`

- [ ] **Step 1: Write the first failing validator tests.** Build small `CombatResolutionDecision` values directly with `EntityId.of`, `ResolutionAttacker`, `ResolutionBlocker`, and `DamageEdge`. Cover the two first failures before production changes:

~~~kotlin
test("COMBAT-01 ordinary combat accepts an arbitrary complete split") {
    val decision = decisionForAttacker(power = 5, blockerToughness = listOf(3, 4))
    val response = response(decision, mapOf(edge(decision, 0) to 2, edge(decision, 1) to 3))

    DecisionValidators.validate(decision, response) shouldBe null
}

test("COMBAT-02 ordinary combat rejects an incomplete source total") {
    val decision = decisionForAttacker(power = 5, blockerToughness = listOf(3, 4))
    val response = response(decision, mapOf(edge(decision, 0) to 2, edge(decision, 1) to 2))

    DecisionValidators.validate(decision, response) shouldBe
        "Source attacker: combat assignment must total exactly 5, got 4"
}
~~~

The helper creates a non-trample attacker, two blocker edges with zero-capable
amounts, and an explicit amount for every edge. It must not insert an order
component or an `orderedBlockers` response map.

- [ ] **Step 2: Run the focused test and record the RED result.**

Run:

~~~powershell
.\gradlew.bat :rules-engine:test --tests "com.wingedsheep.engine.scenarios.ModernCombatDamageConformanceTest" --no-daemon --max-workers=2 --console=plain
~~~

Expected: the test compiles against the current decision API and COMBAT-01 or
COMBAT-02 fails because the existing validator still applies generic
lethal-first logic or permits a short total. The repository's `just` wrapper
must still be attempted for the focused gate; on Windows its bash launcher may
fail with WinError 193, in which case this documented Gradle-wrapper fallback
is the reproducible gate.

### Task 2: Add the pure complete-plan validator

**Files:**
- Create: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDamageAssignmentPlanValidator.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/decision/DecisionValidators.kt`
- Test: `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/ModernCombatDamageConformanceTest.kt`

- [ ] **Step 1: Implement the neutral source-total and target arithmetic.** The public entry point is:

~~~kotlin
internal object CombatDamageAssignmentPlanValidator {
    fun validate(
        decision: CombatResolutionDecision,
        amounts: Map<String, Int>,
    ): String?
}
~~~

For every source group, sum every edge, use the common edge `maximum` as the
source's combat-damage budget, and require equality, not merely `<=`, for a
source with edges. Reject duplicate edge IDs, unknown IDs, negative amounts,
and amounts above the cached edge maximum in the outer decision validator.
Do not inspect `orderConstrained`, `lethal`, `orderedAttackers`,
`orderedBlockers`, or either legacy order component.

- [ ] **Step 2: Replace the generic lethal-first block.** In
`DecisionValidators.validateCombatResolution`, keep response type, edge-id,
range, and source-total shape checks, then pass the fully materialized amount
map to the pure validator. Remove the `belowLethal` count and its error text.

- [ ] **Step 3: Run COMBAT-01 and COMBAT-02 GREEN.**

~~~powershell
.\gradlew.bat :rules-engine:test --tests "com.wingedsheep.engine.scenarios.ModernCombatDamageConformanceTest" --no-daemon --max-workers=2 --console=plain
~~~

Expected: both tests pass; no ordinary split is rejected for being below a
per-target lethal threshold, while a plan that does not assign the source's
full combat-damage amount is rejected.

### Task 3: Make defaults modern and remove gameplay order reads

**Files:**
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDamageManager.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/DamageCalculator.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDamageUtils.kt`
- Test: `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/ModernCombatDamageConformanceTest.kt`

- [ ] **Step 1: Write RED integration assertions for COMBAT-03 and COMBAT-04.** Put a 5-power non-trample attacker against two blockers, assert a board is emitted without an `OrderObjectsDecision` or `OrderBlockers` action, and confirm the default edge amounts are a complete split without requiring lethal-first. Repeat with the same blockers declared in reverse relation order and assert the semantic default target set and total are unchanged.

- [ ] **Step 2: Replace order-dependent distributions used by current combat gameplay.** Use a stable neutral distribution that assigns the available budget to the first legal edge and zero to the rest, or preserves an existing complete manual assignment. This default is only a UI seed; it is not a legality rule. The function must not read `DamageAssignmentOrderComponent` or `AttackerOrderComponent`.

- [ ] **Step 3: Remove order-component reads from board construction and proposal.** Use `BlockedComponent.blockerIds` and `BlockingComponent.blockedAttackerIds` as unordered relation lists. The board emits all legal edges directly. Current gameplay must not call any order-dependent helper.

- [ ] **Step 4: Run COMBAT-03 and COMBAT-04 GREEN and scan the gameplay path.**

~~~powershell
rg -n "get<DamageAssignmentOrderComponent>|get<AttackerOrderComponent>|calculateAutoDamageDistribution|calculateBlockerDamageDistribution|orderConstrained.*lethal|belowLethal" rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/continuations
~~~

Expected: no current gameplay call site reads either order component and no
generic lethal-first validator remains. Compatibility declarations and
serialization registrations may still match the names.

### Task 4: Enforce trample separately, including same-step aggregate damage

**Files:**
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDamageAssignmentPlanValidator.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/decision/DecisionValidators.kt`
- Modify: `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/ModernCombatDamageConformanceTest.kt`

- [ ] **Step 1: Write RED tests for COMBAT-05 through COMBAT-10.** Cover ordinary multiple blockers, trample with multiple blockers, deathtouch trample, marked damage from the first-strike step, same-step damage from another source, and trample over a planeswalker/battle. For a positive trample drain, compute the blocker amount from the aggregate attacker-to-blocker edges; for a negative response, leave at least one blocker below its aggregate lethal amount. A deathtouch trampler needs only one positive damage on a blocker unless marked/same-step damage already makes it lethal. A blocker already at or above toughness may require zero additional damage.

- [ ] **Step 2: Implement `lethalRequired` in the pure validator.** Resolve
the attacker node for each trample drain source and the blocker node for each
of its attacker-to-blocker edges. Use:

~~~kotlin
val remaining = (blocker.toughness - blocker.markedDamage - otherAssignedDamage)
    .coerceAtLeast(0)
val lethalRequired = if (remaining == 0) 0
    else if (attacker.hasDeathtouch) 1
    else remaining
~~~

`otherAssignedDamage` is the same-step total on that blocker excluding the
candidate trampler's own edge. Require the aggregate total including the
candidate trampler edge to meet `lethalRequired` before accepting a positive
drain. Do not use `DamageEdge.lethal` as the authoritative gate.

- [ ] **Step 3: Keep destination-specific trample rules.** A player drain is
legal only for an attacked player, a planeswalker drain remains on the
planeswalker, and a battle drain remains on the battle. Do not create a
player-drain edge for a planeswalker attack. Preserve the existing edge
direction and `isTrampleDrain` serialization shape.

- [ ] **Step 4: Run COMBAT-05 through COMBAT-10 GREEN.**

~~~powershell
.\gradlew.bat :rules-engine:test --tests "com.wingedsheep.engine.scenarios.ModernCombatDamageConformanceTest" --no-daemon --max-workers=2 --console=plain
~~~

Expected: ordinary split tests do not use trample gating, while all positive
and negative trample/deathtouch/marked/same-step cases behave as specified.

### Task 5: Remove legacy order generation and writes at the compatibility boundary

**Files:**
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/combat/OrderBlockersHandler.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/combat/CombatModule.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/continuations/CombatContinuationResumer.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/CombatResolution.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/CombatContinuations.kt`
- Test: `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/ModernCombatDamageConformanceTest.kt`

- [ ] **Step 1: Write RED compatibility tests for COMBAT-11.** Submit an
`OrderBlockers` action in the declare-blockers step and assert validation
returns a compatibility-only rejection and the state receives no order
component. Submit a `CombatResolutionResponse` with non-empty legacy order
maps and assert the final state has the same modern assignments as the same
response with empty maps.

- [ ] **Step 2: Make the old action unreachable in new gameplay.** Keep its
serializer and handler registration so old payloads decode, but make
`OrderBlockersHandler.validate` return the stable error
`"Damage-assignment order is obsolete; submit combat damage assignments"`.
The modern declare-blockers flow must no longer pause for an order decision.

- [ ] **Step 3: Ignore legacy response order maps in the resumer.** Remove the
loops that write `DamageAssignmentOrderComponent` and
`AttackerOrderComponent`. Keep the fields in `CombatResolutionResponse`
with compatibility-only KDoc.

- [ ] **Step 4: Run COMBAT-11 and static reachability checks.**

~~~powershell
rg -n "with\\(DamageAssignmentOrderComponent|with\\(AttackerOrderComponent|OrderBlockers\\(" rules-engine/src/main/kotlin rules-engine/src/test/kotlin
~~~

Expected: only compatibility declarations/serializer and the rejecting
handler mention the old action; no current board, continuation, or damage
pipeline path creates either order component.

### Task 6: Preserve the complete-plan-before-damage boundary

**Files:**
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/continuations/CombatContinuationResumer.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDamageManager.kt`
- Test: `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/ModernCombatDamageConformanceTest.kt`

- [ ] **Step 1: Write RED tests for COMBAT-12 through COMBAT-15.** Verify a multi-chooser board remains pending after the first chooser submits, that no damage event or `DamageComponent` is applied before the last chooser, that shared-blocker plans include both source directions before submission, and that the final batch contains every nonzero assignment.

- [ ] **Step 2: Materialize the final graph only after all choosers.** Keep
`pendingChoosers`, but validate the accumulated complete amount map at the
last response before writing `DamageAssignmentComponent`. Reject missing or
short totals before calling `applyCombatDamage`.

- [ ] **Step 3: Keep one downstream apply batch.** Do not call
`applySingleAssignment` or any state-changing damage helper from the resumer.
The resumer only writes immutable assignment components and re-enters the
existing manager once. The manager's existing loop applies the already
modified
batch, then performs lifelink/SBA after the loop.

- [ ] **Step 4: Run COMBAT-12 through COMBAT-15 GREEN.**

~~~powershell
.\gradlew.bat :rules-engine:test --tests "com.wingedsheep.engine.scenarios.ModernCombatDamageConformanceTest" --no-daemon --max-workers=2 --console=plain
~~~

Expected: no damage state/event exists between chooser responses, and all
assignments are present before the simultaneous apply phase.

### Task 7: Verify first-strike and double-strike step semantics

**Files:**
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatManager.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDamageManager.kt`
- Test: `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/ModernCombatDamageConformanceTest.kt`

- [ ] **Step 1: Write RED tests for COMBAT-16 through COMBAT-19.** Cover first strike followed by regular damage, double strike dealing in both steps, a blocker removed between steps, and a toughness/power state change between steps. Assert that each step has its own complete assignment plan, first-strike damage is marked before the second plan, and a creature that no longer deals damage in step two contributes no edge.

- [ ] **Step 2: Run the tests against the existing lifecycle and isolate any failure to the step boundary.** Use the existing `clearDamageAssignmentsForNewDamageStep` and `dealsDamageThisStep` paths; do not reintroduce order components as a shortcut. If a fix is needed, clear only per-step assignment components while retaining `DamageComponent` marked damage and the existing first-strike marker.

- [ ] **Step 3: Run COMBAT-16 through COMBAT-19 GREEN and the existing first-strike suite.**

~~~powershell
.\gradlew.bat :rules-engine:test --tests "com.wingedsheep.engine.scenarios.ModernCombatDamageConformanceTest" --tests "*FirstStrikeCombatTest" --tests "*TrampleAfterFirstStrikeKillTest" --no-daemon --max-workers=2 --console=plain
~~~

### Task 8: Verify banding chooser authority and APNAP sequencing

**Files:**
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDamageUtils.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/combat/CombatDamageManager.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/core/CombatContinuations.kt`
- Test: `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/ModernCombatDamageConformanceTest.kt`

- [ ] **Step 1: Write RED tests for COMBAT-20 through COMBAT-22.** Assert a banding blocker owns attacker-to-blocker division, a banded attacker owns blocker-to-attacker division where the rules require it, and a multiplayer plan queues distinct controllers in active-player/nonactive-player order. Assert `orderConstrained` never changes ordinary legality; only `editableBy` changes authority.

- [ ] **Step 2: Make chooser sequencing deterministic.** Build the chooser
queue from the active player followed by the remaining relevant controllers in
`state.turnOrder`, preserving first occurrence and omitting sources that do not
deal damage this step. Keep each edge's `editableBy` as the authority result
from banding. The queue is the only sequencing state; no order list is stored
on a creature.

- [ ] **Step 3: Run COMBAT-20 through COMBAT-22 GREEN and existing banding/multiplayer suites.**

~~~powershell
.\gradlew.bat :rules-engine:test --tests "com.wingedsheep.engine.scenarios.ModernCombatDamageConformanceTest" --tests "*BandingTrampleDrainScenarioTest" --tests "*MultiDefenderCombatTest" --no-daemon --max-workers=2 --console=plain
~~~

### Task 9: Keep client/replay compatibility without exposing live order state

**Files:**
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/view/ClientStateTransformer.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/view/ClientDTO.kt`
- Modify: `web-client/src/components/combat/CombatArrows.tsx`
- Modify: `web-client/src/components/decisions/OrderBlockersUI.tsx`
- Test: `game-server/src/test/kotlin/com/wingedsheep/engine/session/CombatDamageMaskingEnricherTest.kt` or the existing package-local equivalent

- [ ] **Step 1: Write RED tests for COMBAT-23.** Transform a state containing
legacy order components and assert the modern combat DTO leaves the nullable
order field absent/null for a current state; decode a legacy DTO fixture and
assert it still deserializes.

- [ ] **Step 2: Stop live transformation and rendering from treating order as
current gameplay.** Keep nullable fields and old response types for decode,
but make current transformation return null and make the client only render
`CombatResolutionDecision` edges. No new UI action may submit `OrderBlockers`.

- [ ] **Step 3: Run the focused server/client contract tests and TypeScript checks available in the repository.**

~~~powershell
.\gradlew.bat :game-server:test --tests "*CombatDamageMaskingEnricherTest" --no-daemon --max-workers=2 --console=plain
npm --prefix web-client test -- --runInBand
~~~

If the web test script is unavailable, run
`npm --prefix web-client run build` and report the exact unavailable script
rather than changing package scripts.

### Task 10: Complete the matrix and verify the exit invariants

**Files:**
- Modify: `rules-engine/src/test/kotlin/com/wingedsheep/engine/scenarios/ModernCombatDamageConformanceTest.kt`
- Modify: existing combat tests only for assertions that explicitly encode obsolete order/lethal-first behavior
- Modify: the design specification only if an implementation detail changes the documented boundary

- [ ] **Step 1: Write RED tests for COMBAT-24.** Assert static reachability: no
current gameplay source contains reads of either order component, writes from a
combat-resolution response, generic lethal-first validation, or an
`OrderObjectsDecision` generated solely for damage assignment. Assert the
compatibility serializer declarations still exist.

- [ ] **Step 2: Run the complete focused matrix and relevant existing suites.**

~~~powershell
.\gradlew.bat :rules-engine:test --tests "com.wingedsheep.engine.scenarios.ModernCombatDamageConformanceTest" --tests "*CombatDamageAssignmentTest" --tests "*CombatResolutionBoardTest" --tests "*CombatLethalDamageTest" --tests "*FirstStrikeCombatTest" --tests "*TrampleAfterFirstStrikeKillTest" --tests "*BandingTrampleDrainScenarioTest" --no-daemon --max-workers=2 --console=plain
~~~

Expected: all A2.2 tests and relevant existing tests pass. Any unrelated
pre-existing failure is reproduced in isolation and reported without changing
its code.

- [ ] **Step 3: Run repository verification through `just` first, then its
Windows fallback if needed.**

~~~powershell
just test-rules
.\gradlew.bat :rules-engine:test :mtg-sets:scenarioTest --no-daemon --max-workers=2 --console=plain
~~~

Also run `git diff --check`, `git status --short`, and the reachability
`rg` scan. Confirm the six exit invariants in the handoff.

- [ ] **Step 4: Review scope before integration.** Confirm the diff contains no
card definition, Akiri, Chevill, Commander-zone, observation, Gym, or ML work,
no unrelated user changes, and no SDK language-reference drift. Keep the branch
unmerged and open a single Draft PR only after the user-requested push gate has
been satisfied by fresh verification.
