# Variable spell-cost domain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make normal hand-cast legal actions publish complete, explicit variable-sacrifice additional-cost domains to Rules and Gym consumers.

**Architecture:** Reuse `SelectionCostPresentation` for candidate and `AdditionalCostData` derivation, and use `VariablePermanentsCost.canPay` for payability. Preserve the existing Gym contract and cast validator; only connect the missing normal `CastSpellEnumerator` seam and add focused regressions.

**Tech Stack:** Kotlin, Gradle, Kotest, immutable `GameState`, `rules-engine`, `gym`, and kotlinx.serialization JSON.

---

## File map

- Create `rules-engine/src/test/kotlin/com/wingedsheep/engine/legalactions/CastSpellEnumeratorVariablePermanentsTest.kt` for normal hand-cast enumeration behavior.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/enumerators/CastSpellEnumerator.kt` to preflight variable permanent sacrifice costs and retain cost data when the domain is empty.
- Create `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvVariableSacrificeTest.kt` for the observation-to-structured-action regression.
- Do not modify Gym production code, SDK data types, the cast handler, card definitions, decklists, or frozen snapshots.

## Task 1: Establish the clean isolated baseline

**Files:** none.

- [ ] **Step 1: Verify the worktree identity and clean status.**

Run from `C:\\Users\\chris\\.config\\superpowers\\worktrees\\argentum-engine\\agent-a5-a8-variable-spell-cost-domain`:

```powershell
git status --short --branch
git rev-parse HEAD
git rev-parse origin/main
git remote get-url origin
```

Expected: branch `agent/a5-a8-variable-spell-cost-domain`, clean status, `HEAD == origin/main == b43b41d19f0d671e2b5a173a94bd133cf6e8c415`, and origin `https://github.com/chrismaghuhn/argentum-engine.git`.

- [ ] **Step 2: Run the smallest existing Rules test baseline.**

Run:

```powershell
just test-class CastSpellEnumeratorTest
```

Expected: the existing class passes. Record any failure as baseline evidence and do not attribute it to Issue #67.

## Task 2: Add the normal-spell RED characterization

**Files:**
- Create: `rules-engine/src/test/kotlin/com/wingedsheep/engine/legalactions/CastSpellEnumeratorVariablePermanentsTest.kt`

- [ ] **Step 1: Define a synthetic direct additional-cost spell.**

Use a `CardDefinition.instant` with mana cost `{1}` and:

```kotlin
additionalCosts = listOf(
    Costs.additional.SacrificePermanents(
        filter = GameObjectFilter.Creature,
        minCount = 0,
    )
)
```

Register it with the existing enumeration fixture and place it in hand. Use the normal spell path; do not wrap the cost in `KeywordAbility.OptionalAdditionalCost`.

- [ ] **Step 2: Write the desired assertions before production changes.**

Cover these test names and assertions:

```kotlin
test("normal variable sacrifice publishes an explicit zero choice with no candidates")
// cast exists; info.costType == "VariableSacrifice";
// validSacrificeTargets == emptyList(); sacrificeMinCount == 0; sacrificeMaxCount == 0

test("normal variable sacrifice publishes all one or three legal candidates")
// min == 0; max == candidate count; published IDs equal the battlefield candidates

test("normal variable sacrifice is unavailable when its positive minimum is unreachable")
// a minCount of 2 with one eligible creature produces no affordable normal cast

test("normal variable sacrifice publishes only matching permanents controlled by the caster")
// own matching creature is present; own nonmatching permanent and opponent matching creature
// are absent from validSacrificeTargets

test("normal variable sacrifice enumeration keeps deterministic candidate ordering")
// equivalent state setup enumerated twice produces equal ordered candidate IDs
```

- [ ] **Step 3: Run the focused new class and record RED.**

Run:

```powershell
just test-class CastSpellEnumeratorVariablePermanentsTest
```

Expected: the tests fail because the normal cast action has `additionalCostInfo == null` and an unreachable positive minimum is not gated. If the tests pass or fail during fixture construction, correct the test until the failure is the missing normal enumerator behavior.

## Task 3: Implement the smallest generic Enumerator fix

**Files:**
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/enumerators/CastSpellEnumerator.kt`

- [ ] **Step 1: Add a nullable retained cost-data holder beside the existing additional-cost accumulators.**

The holder must distinguish “no variable selection cost” from “variable selection cost with zero candidates”:

```kotlin
var variablePermanentsCostInfo: AdditionalCostData? = null
```

- [ ] **Step 2: Handle the normal `CostAtom.VariablePermanents` branch.**

For the exact generic sacrifice shape, use the existing helpers:

```kotlin
is CostAtom.VariablePermanents -> {
    val candidates = SelectionCostPresentation.candidates(
        state,
        playerId,
        cardId,
        cost,
        context.costUtils,
        context.predicateEvaluator,
    )
    if (atom.action == PermanentCostAction.SACRIFICE) {
        variablePermanentsCostInfo = SelectionCostPresentation
            .costData(cost, candidates)
            ?.second
        if (!VariablePermanentsCost.canPay(state, playerId, atom)) {
            canPayAdditionalCosts = false
        }
    }
}
```

Do not reproduce battlefield filtering, controller checks, filter evaluation, or ordering in `CastSpellEnumerator`.

- [ ] **Step 3: Thread the retained data into `buildAdditionalCostData`.**

Add `variablePermanentsCostInfo: AdditionalCostData? = null` to the helper and return it before branches that depend on non-empty candidate lists:

```kotlin
if (variablePermanentsCostInfo != null) {
    return variablePermanentsCostInfo
}
```

Pass the holder from the normal cast call. Leave the `AdditionalCostData` schema and all existing cost branches unchanged.

- [ ] **Step 4: Run the focused Rules class for GREEN.**

Run:

```powershell
just test-class CastSpellEnumeratorVariablePermanentsTest
```

Expected: all new enumeration tests pass, including the empty-domain zero-choice case.

## Task 4: Add the actual Gym structured-action regression

**Files:**
- Create: `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvVariableSacrificeTest.kt`

- [ ] **Step 1: Build a deterministic environment state with a synthetic spell, mana, and candidates.**

Register the synthetic card in a `CardRegistry`, initialize a `GameEnvironment`, advance to `PRECOMBAT_MAIN` using legal `PassPriority` actions, and move known card IDs from the initialized library into the acting player's hand and battlefield using immutable `GameState.moveToZone` updates. Restore the edited state through `GameEnvironment.restore`; do not add a production state-mutation API.

- [ ] **Step 2: Verify the acting observation publishes the structured contract.**

Build a `GameGymEnv` around the environment and assert for the synthetic `CastSpell` view:

```kotlin
view.requiresStructuredAction shouldBe true
view.validSacrificeTargets shouldBe expectedIds.sortedBy { it.value }
view.sacrificeMinCount shouldBe 0
view.sacrificeMaxCount shouldBe expectedIds.size
```

- [ ] **Step 3: Verify fail-closed and explicit selections.**

Using the action's `actionSemantics` as the base payload:

```kotlin
shouldThrow<IllegalArgumentException> { gym.step(actionId, payloadWithoutAdditionalCostPayment) }
shouldThrow<IllegalArgumentException> {
    gym.step(actionId, payloadWithVariableCostPermanents(listOf(opponentOrNonDomainId)))
}
gym.step(actionId, payloadWithVariableCostPermanents(emptyList()))
```

Assert that the two rejected submissions leave `environment.stepCount` unchanged and that the explicit empty selection advances the environment. Add a second fresh-state assertion with one valid ID when the test fixture retains a candidate after the zero submission.

## Task 5: Run surrounding regressions and inspect boundaries

**Files:** none beyond the files above.

- [ ] **Step 1: Run the focused Rules and Gym tests together.**

Run:

```powershell
just test-class CastSpellEnumeratorVariablePermanentsTest
just test-class GameGymEnvVariableSacrificeTest
```

Expected: both classes pass with zero failures.

- [ ] **Step 2: Run existing variable-sacrifice and contract regressions.**

Run:

```powershell
just test-class VariableSacrificeAdditionalCostTest
just test-class GameGymEnvActionContractTest
```

Expected: existing kicker/cost-linkage and structured-action tests remain green.

- [ ] **Step 3: Run the required module gates through `just`.**

Run:

```powershell
just test-rules-engine
just test-mtg-sdk
just test-gym
just test-gym-server
just test-game-server
```

If a recipe name differs, use the corresponding documented `just --list` recipe or the repository's documented Gradle equivalent and record the exact command. Do not re-bless snapshots.

- [ ] **Step 4: Run privacy, determinism, and baseline checks.**

Run the relevant existing classes, including `ObservationPrivacyTest`, `StateDigestTest`, snapshot/replay regressions, and `FrozenBaselineTest`. Expected: no new hidden information, stable semantic candidates, and FrozenBaseline unchanged at `6ff9ded1403d59ac`.

- [ ] **Step 5: Inspect the final diff and scope.**

Run:

```powershell
git diff --check
git diff --stat
git status --short
```

Confirm only the Enumerator, focused Rules/Gym tests, and process documentation changed. Confirm no Plumb card, deck, PR #55, Issue #49, or frozen snapshot files changed.

## Task 6: Commit, publish, and report conservatively

**Files:** all reviewed files in this branch.

- [ ] **Step 1: Commit the implementation with verified evidence.**

Use a focused commit message such as:

```powershell
git add docs/superpowers rules-engine/src/main/kotlin/com/wingedsheep/engine/legalactions/enumerators/CastSpellEnumerator.kt rules-engine/src/test/kotlin/com/wingedsheep/engine/legalactions/CastSpellEnumeratorVariablePermanentsTest.kt gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvVariableSacrificeTest.kt
git commit -m "fix: publish variable spell sacrifice domains"
```

- [ ] **Step 2: Push only the dedicated fork branch.**

Run:

```powershell
git push -u origin agent/a5-a8-variable-spell-cost-domain
```

Verify the push target is `chrismaghuhn/argentum-engine`, never `upstream`.

- [ ] **Step 3: Open a draft PR only after fresh verification.**

Create a draft PR against `chrismaghuhn/argentum-engine:main` titled `[A5/A8] Publish variable-permanent spell additional-cost domains`. The body must state `Closes #67` only if the acceptance checklist is fully met, and must explicitly state:

```text
PLUMB_CARD_DEFINITION_ADDED: NO
PR_55_TOUCHED: NO
DECKS_CHANGED: NO
ISSUE_49_TOUCHED: NO
```

Report hosted CI as `PASS`, `FAIL`, or `SKIPPED` from actual evidence. Do not merge the PR.



