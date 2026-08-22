# A5 fixed CastSpellMode PaymentDomainV1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish and execute the existing PaymentPlanV1 contract for fully-bound ordinary fixed-cost CastSpellMode actions while preserving modal Rules semantics and fail-closing every unsupported shape.

**Architecture:** Reuse the existing Rules-owned modal choose-count and cost-finality computations. Add one small shared eligibility predicate for the fixed choose-one payment shape; use it from ObservationBuilder and CastSpellHandler. Keep CastPaymentProcessor's existing explicit-plan materializer authoritative, so no new payment model or executor is introduced.

**Tech Stack:** Kotlin/JDK 21, Gradle/just, Kotest, `rules-engine`, `gym`, `game-server` CompactReplay, immutable GameState, PaymentDomainV1/PaymentPlanV1.

---

### Task 1: Preserve the RED characterization and ordinary-cast baseline

**Files:**
- Modify: `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentDomainAuthorityTest.kt`

- [x] **Step 1: Characterize the real modal action**

Use the registered `BorosCharm` definition with Mountain and Plains on the battlefield. Assert the
first legal action for the card has `actionType == "CastSpellMode"`, an underlying `CastSpell`, one
`chosenModes` entry, and `manaCostString == "{R}{W}"`. Build the public observation and assert the
current domain is null with `PAYMENT_DOMAIN_UNSUPPORTED`.

- [x] **Step 2: Verify RED**

Run:

```text
.\gradlew.bat :gym:test --tests com.wingedsheep.gym.GameGymEnvPaymentDomainAuthorityTest
```

Expected before production changes: the characterization passes, existing ordinary CastSpell
coverage passes, and the new desired assertion that the modal domain is non-null fails because the
current `ObservationBuilder.isSupportedCastSpellPayment` rejects non-empty `chosenModes`.

- [ ] **Step 3: Keep the ordinary CastSpell control green**

The existing `targeted CastSpell without target-dependent cost still publishes PaymentDomainV1`
test remains the nonmodal control. Do not alter its expected domain or its PaymentPlanV1 materializer.

---

### Task 2: Add the shared fixed choose-one modal eligibility seam

**Files:**
- Create: `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/ModalPaymentPlanSupport.kt`
- Test: `rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV1Test.kt`

- [ ] **Step 1: Add a pure eligibility predicate**

Add `ModalPaymentPlanSupport.supportsFixedChooseOne(state, cardDef, action, conditionEvaluator)`
that returns true only when:

```kotlin
action.chosenModes.size == 1
ModalChooseCounts.forCast(...).let { it.first == 1 && it.last == 1 }
selectedMode.additionalManaCost == null
selectedMode.additionalCosts == null
action.modeTargetsOrdered.isEmpty() || action.modeTargetsOrdered.size == 1
```

Reject missing/invalid mode indices and all unresolved choose-N/dynamic ranges. Keep the helper a
predicate only: it must not calculate or pay costs and must not add a new serialized type.

- [ ] **Step 2: Test unsupported modal shapes**

Add Rules fixtures for a mode with `additionalManaCost`, a mode with `additionalCosts`, and a modal
whose effective choose-count range is not exactly `1..1`. Assert the predicate rejects each shape.
Keep the test independent of Gym so the shared Rules seam is exercised directly.

- [ ] **Step 3: Run the focused Rules test and confirm the new tests are initially red**

Run:

```text
.\gradlew.bat :rules-engine:test --tests com.wingedsheep.engine.mechanics.mana.PaymentPlanV1Test
```

The new desired modal execution assertion must still fail until the handler and observation
publication are wired; predicate-only rejection tests may pass once the helper exists.

---

### Task 3: Publish CastSpellMode through the existing canonical PaymentDomain

**Files:**
- Modify: `gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt`
- Test: `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentDomainAuthorityTest.kt`

- [ ] **Step 1: Accept only eligible CastSpellMode actions**

Update `isSupportedCastSpellPayment` to accept `legalAction.actionType` values `CastSpell` and
`CastSpellMode`. For `CastSpellMode`, require the shared fixed choose-one predicate. Keep the old
nonmodal checks unchanged and continue rejecting `CastSpellModal`, X, alternative/resource payment,
face casts, free casts, splice, secondary mana costs, and mode-specific additional payment data.

- [ ] **Step 2: Reuse the existing cost authority**

For both action types, retain the existing parsed ordinary-cost check, `CostCalculator` target-cost
finality check, cast permission color relaxation, `SpellPaymentContext`, and
`PaymentDomainBuilder.build`. Pass the enumerated `CastSpellMode` target candidates and target count
to `hasTargetDependentCastCost`; do not add a modal-specific cost calculator.

- [ ] **Step 3: Add the focused domain tests**

Assert a fixed Boros Charm mode now publishes `{R}{W}` with a non-null domain. Add a targeted modal
mode with target-independent cost and assert it publishes. Add a modal with a target-dependent cost,
and a modal with mode-specific additional mana/additional cost, and assert each produces
`PAYMENT_DOMAIN_UNSUPPORTED` with a null domain.

- [ ] **Step 4: Run the Gym authority test**

Run the class through `just test-class GameGymEnvPaymentDomainAuthorityTest`; if the Windows wrapper
again fails with `WinError 193`, record it and run the equivalent native Gradle class command. The
expected GREEN result is all focused authority tests passing.

---

### Task 4: Let CastSpellHandler accept the same bound modal plan

**Files:**
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastSpellHandler.kt`
- Modify: `rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/spell/CastPaymentProcessor.kt` only if the existing explicit-plan call cannot carry the already-selected mode context
- Test: `rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanV1Test.kt`

- [ ] **Step 1: Replace only the modal rejection in `validatePayment`**

In the existing `PaymentStrategy.Explicit(paymentPlan != null)` guard, replace the unconditional
`chosenModes.isNotEmpty()` rejection with the shared fixed choose-one predicate. Continue rejecting
modal target/cost shapes when the predicate is false. Leave all ordinary CastSpell and
ActivateAbility branches unchanged.

- [ ] **Step 2: Keep the existing explicit-plan executor authoritative**

Do not call `ManaSolver.solve()`, AutoPay, FromPool, or legacy runtime source IDs after a plan is
submitted. Let `CastPaymentProcessor.explicitPlanPay` revalidate with `PaymentPlanValidator`, consume
the accepted pool spend, activate exactly the submitted source abilities/production choices, emit
the existing `ManaSpentEvent`, and return to the unchanged chosen-mode/target/stack flow.

- [ ] **Step 3: Add Rules execution coverage**

Submit a `CastSpell` for `BorosCharm` with one chosen mode and a complete `PaymentPlanV1`. Assert
success, exact source taps/production and `ManaSpentEvent`, and that the stack payload retains the
chosen mode and targets. Add explicit-plan rejection tests for a mode-specific extra payment and
an unresolved choose-N action. Existing ordinary CastSpell and ActivateAbility tests must remain
green.

- [ ] **Step 4: Run the focused Rules tests**

```text
.\gradlew.bat :rules-engine:test --tests com.wingedsheep.engine.mechanics.mana.PaymentPlanV1Test
```

Expected result: the new modal plan execution passes and all existing PaymentPlanV1 coverage passes.

---

### Task 5: Version the observation contract and verify trusted Gym execution

**Files:**
- Modify: `gym/src/main/kotlin/com/wingedsheep/gym/contract/SchemaHash.kt`
- Test: `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentPlanTest.kt`
- Test: `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvSnapshotTest.kt` only if the existing fixture cannot express the modal fork assertion

- [ ] **Step 1: Bump SchemaHash**

Change the current `v1.11-castspell-payment-domain` value to a unique
`v1.12-castspellmode-payment-domain` value. Do not change DTO field names or add a second schema
hash.

- [ ] **Step 2: Exercise the trusted Gym boundary**

Add a Boros Charm `GameGymEnv` fixture. Assert the action view has `kind == "CastSpellMode"`, one
chosen mode in `actionSemantics`, and a complete PaymentDomainV1. Submit a plan using the view's
public source IDs/ability keys and assert the action succeeds. Attempt AutoPay, FromPool, and legacy
Explicit source-only payloads and assert each is rejected without changing step count or digest.

- [ ] **Step 3: Preserve fork/snapshot/digest**

Fork before submission and assert the fork observation has the same modal action semantics, domain,
schema hash, and `stateDigest`. Snapshot, execute the modal plan, restore, and assert the original
digest and modal chosen-mode action are reconstructed exactly.

- [ ] **Step 4: Run focused Gym tests**

```text
just test-class GameGymEnvPaymentPlanTest
just test-class GameGymEnvSnapshotTest
```

Use the native Gradle equivalents only if the known wrapper failure recurs.

---

### Task 6: Replay and documentation contract coverage

**Files:**
- Modify: `game-server/src/test/kotlin/com/wingedsheep/gameserver/replay/PaymentPlanReplayTest.kt`
- Modify: `docs/card-sdk-language-reference.md`

- [ ] **Step 1: Add modal PaymentPlan replay coverage**

Encode a `CastSpell` carrying one `chosenModes` entry, its target payload, and an explicit
PaymentPlanV1 through CompactReplay. Decode and reconstruct it, then assert the chosen mode, targets,
payment plan, events, and state digest match the original execution.

- [ ] **Step 2: Update the contract documentation**

Update the existing modal/cast payment note to say choose-one `CastSpellMode` actions with ordinary
fixed costs are now PaymentDomainV1-backed, while choose-N and mode-specific payment shapes remain
fail-closed. Do not add a new SDK primitive or card-specific exception.

- [ ] **Step 3: Run replay tests**

```text
just test-class PaymentPlanReplayTest
```

The existing ordinary CastSpell replay case must remain green.

---

### Task 7: Full verification, review, commit, and Draft PR

**Files:**
- All files changed by Tasks 1–6; no files from PR #73 or CycleCard worktrees.

- [ ] **Step 1: Inspect scope and run focused gates**

Run the relevant Rules/Gym/server/replay classes plus the existing ActivateAbility and ordinary
CastSpell PaymentPlan tests. Confirm `git diff --stat origin/main...HEAD` contains only this scope.

- [ ] **Step 2: Run engine/server baseline gates**

Run `just test-rules`, `just test-server`, and the repository's FrozenBaseline command. Do not run
`just rebless-cards`; any golden diff is a failure to investigate, not a reason to rebless.

- [ ] **Step 3: Run Hosted CI**

Push the branch to `origin` only after local gates pass and start the repository Hosted CI workflow.
Record the exact commit and all check conclusions; distinguish infrastructure failures from code
failures.

- [ ] **Step 4: Request an independent code review**

Review `origin/main` against the final HEAD for modal shape completeness, payment-policy bypasses,
target-finality drift, replay/digest preservation, and accidental PR #73 changes. Resolve all
blocking findings before opening the PR.

- [ ] **Step 5: Commit and open a Draft PR**

Commit only scoped files with a capability-focused message and the repository's co-author trailer.
Verify the destination is `chrismaghuhn/argentum-engine`, set the PR to Draft, include exact test/
CI evidence and the known wrapper limitation, and do not merge.
