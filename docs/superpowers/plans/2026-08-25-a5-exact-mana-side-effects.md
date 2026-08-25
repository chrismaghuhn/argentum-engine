# A5 Exact Mana Side Effects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish complete PaymentDomainV4 source choices for ordinary tap mana abilities with exact fixed self-damage side effects, and execute those choices through the existing transactional side-effect executor.

**Architecture:** Keep `PaymentManaProductionProfile` responsible only for exact mana output. Add a separate Rules-owned `PaymentManaSideEffectCertificate` map keyed by the same stable `manaAbilityKey`. `supportsPaymentPlanV1()` becomes the shared complete-source closure proof; it cross-checks aggregate pain metadata but never uses it to select or execute damage. Existing `PaymentPlanValidator` key binding and `ManaAbilitySideEffectExecutor` execution remain authoritative.

**Tech Stack:** Kotlin/JDK 21, Gradle/`just`, Kotest, `rules-engine`, `gym`, immutable `GameState`, PaymentDomainV4, PaymentPlanV2.

---

## File map

- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentManaProductionProfile.kt` for the independent side-effect certificate, exact resolver, production-leaf separation, and invalidation.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/ManaSolver.kt` to carry certificates for each exact ability key and to remove the duplicate pain-shape inspection.
- Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanValidator.kt` to require complete production/certificate maps and exact aggregate cross-checks while retaining all existing fail-closed cost/restriction gates.
- Add `rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentManaSideEffectCertificateTest.kt` for resolver, key completeness, cross-check, and unsupported-shape regressions.
- Add `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvLlanowarWastesPaymentDomainTest.kt` for the real Llanowar Wastes public domain and exact `{C}`, `{B}`, and `{G}` execution paths.
- Add `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvTearAsunderPaymentDomainTest.kt` for real normal and kicked Tear Asunder domains and public-domain-only PaymentPlanV2 construction.
- Modify `docs/data-contracts.md` only to document the unchanged PaymentDomainV4 wire contract and the internal complete-source certificate rule.
- Do not modify PR #73, its corpus/policy/deck inputs, public DTO/version constants, replay codecs, or card definitions.

### Task 1: Add Rules RED tests for the independent certificates

**Files:** Create `rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentManaSideEffectCertificateTest.kt`.

- [ ] **Step 1: Define real inline ability fixtures.** Use the SDK DSL to create ordinary tap abilities with these effects: `AddMana(BLACK)`, `AddColorlessMana(1)`, `AddMana(BLACK).then(DealDamage(1, Player.You))`, dynamic damage to `Player.You`, fixed damage to another player, and two non-mana leaves. Keep the test identities structural by calling `ManaAbilityIdentity.key` on the exact `ActivatedAbility` instances.

- [ ] **Step 2: Write the failing resolver assertions.** The supported composite must currently fail because `PaymentManaProductionProfileResolver.resolve` treats `DealDamageEffect` as an unsupported production leaf. The desired assertions are:

```kotlin
test("fixed self damage is certified separately from exact mana production") {
    val ability = ActivatedAbility(
        cost = AbilityCost.Tap,
        effect = Effects.AddMana(Color.BLACK)
            .then(Effects.DealDamage(1, EffectTarget.PlayerRef(Player.You))),
        isManaAbility = true,
    )

    PaymentManaProductionProfileResolver.resolve(ability.effect, setOf(Color.BLACK)) shouldBe
        PaymentManaProductionProfile.SelectableSingleOutput(setOf(PaymentManaColor.BLACK))
    PaymentManaSideEffectCertificateResolver.resolve(ability.effect) shouldBe
        PaymentManaSideEffectCertificate.FixedSelfDamage(1)
}

test("a pure colorless ability has no side-effect certificate payload") {
    val effect = Effects.AddColorlessMana(1)

    PaymentManaProductionProfileResolver.resolve(effect, emptySet()) shouldBe
        PaymentManaProductionProfile.SelectableSingleOutput(setOf(PaymentManaColor.COLORLESS))
    PaymentManaSideEffectCertificateResolver.resolve(effect) shouldBe
        PaymentManaSideEffectCertificate.NoSideEffect
}
```

- [ ] **Step 3: Cover the fail-closed certificate matrix.** Assert `Unsupported` for dynamic damage, a non-`Player.You` target, two non-mana leaves, and a gated/choice-bearing extra effect. Assert that fixed production restrictions still produce an unsupported production profile, not a side-effect authorization.

- [ ] **Step 4: Run the new class and confirm RED.** Run `just test-class PaymentManaSideEffectCertificateTest`. Expected wrapper status is either a normal failing test at the first supported-composite assertion or the known Windows `WinError 193` launcher block. If blocked, run the labeled fallback `./gradlew.bat --no-daemon :rules-engine:test --tests '*PaymentManaSideEffectCertificateTest' --console=plain` and preserve the expected assertion failure. Do not change production code to make the test compile or pass.

- [ ] **Step 5: Commit only the RED test.** Verify `git diff --name-only origin/main...HEAD` contains only the design/plan commits and the new test, then commit `test: reproduce exact mana side-effect profile gap`.

### Task 2: Add Gym RED regressions for real source publication and payment

**Files:** Create `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvLlanowarWastesPaymentDomainTest.kt`; create `gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvTearAsunderPaymentDomainTest.kt`.

- [ ] **Step 1: Build real-card fixtures from the existing environment pattern.** Register `PortalSet.cards`, `PortalSet.basicLands`, the real APC `LlanowarWastes`, and the real DMU `TearAsunder` through `CardRegistry`. Reset to `PRECOMBAT_MAIN`, move the real spell/source cards to the intended zones, and call `environment.restore`. Do not create card-name production branches or synthetic replacements for the motivating cards.

- [ ] **Step 2: Assert the real Llanowar Wastes domain.** Observe an ordinary fixed-cost action that has Llanowar Wastes available and assert `paymentDomain.version == 4`. The published source activations must contain exactly the three production identities/choices for `{C}`, `{B}`, and `{G}`; no colored sibling may be omitted. This assertion must be RED before production changes.

- [ ] **Step 3: Add normal and kicked Tear Asunder RED assertions.** Locate the real legal `CastSpell` action variants and assert that both the normal `{1}{G}` action and kicked `{2}{G}{B}` action have `PaymentDomainV4`. Before the fix, both fail closed because the real Wastes source has an unsupported colored sibling profile.

- [ ] **Step 4: Build plans only from public fields.** For each selected source, copy only `sourceId`, `manaAbilityKey`, and `productionChoice` from `PaymentSourceActivationDomain`; allocate each cost symbol through the public `costUnits` indexes. Do not inspect `ManaSource`, card definitions, hidden ability IDs, or solver output to fill a missing choice.

- [ ] **Step 5: Add execution and atomicity assertions.** Submit the serialized action semantics plus `PaymentStrategy.ExplicitV2`. Assert `{B}` and `{G}` tap the selected Wastes, reduce the controller's life by exactly one, and emit only one damage event; assert `{C}` taps and causes no damage. Submit incomplete, wrong-key, unpublished, or wrong-production plans and assert unchanged state identity/digest, step count, life, tapped components, and events.

- [ ] **Step 6: Run both new classes and confirm RED.** Run `just test-class GameGymEnvLlanowarWastesPaymentDomainTest` and `just test-class GameGymEnvTearAsunderPaymentDomainTest`. If the wrapper is blocked, run the separately labeled native `./gradlew.bat --no-daemon :gym:test --tests '*GameGymEnvLlanowarWastesPaymentDomainTest' --tests '*GameGymEnvTearAsunderPaymentDomainTest' --console=plain`. Expected failure is missing `PaymentDomainV4`, not a fixture/compiler error.

- [ ] **Step 7: Commit the RED Gym tests.** Stage only the two new test files and commit `test: reproduce pain-land payment domain gap`.

### Task 3: Implement the independent production and side-effect resolvers

**Files:** Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentManaProductionProfile.kt`.

- [ ] **Step 1: Add the certificate type with an explicit unsupported state.** Implement the exact sealed vocabulary:

```kotlin
sealed interface PaymentManaSideEffectCertificate {
    data object NoSideEffect : PaymentManaSideEffectCertificate
    data class FixedSelfDamage(val amount: Int) : PaymentManaSideEffectCertificate
    data class Unsupported(val reason: String) : PaymentManaSideEffectCertificate
}
```

The certificate records support evidence only. No executor or state mutation method belongs on this type.

- [ ] **Step 2: Add `PaymentManaSideEffectCertificateResolver`.** Flatten only `CompositeEffect` structure. Return `NoSideEffect` for zero non-mana leaves. Return `FixedSelfDamage` only when there is exactly one non-mana leaf, it is `DealDamageEffect`, its amount is `DynamicAmount.Fixed` with `amount > 0`, its target is exactly `EffectTarget.PlayerRef(Player.You)`, and it has no unsupported damage flags/source indirection. Return `Unsupported` for every other non-mana shape.

- [ ] **Step 3: Separate production leaves from side-effect leaves.** Make `PaymentManaProductionProfileResolver` collect only `AddManaEffect`, `AddColorlessManaEffect`, and the already-supported exact color-choice leaves before resolving output. A fixed self-damage composite must therefore yield the same single-output production profile as its mana leaf. Keep dynamic amounts, restrictions, unresolved choices, and unsupported production leaves fail-closed.

- [ ] **Step 4: Preserve aggregate reconciliation and invalidation.** `authorizePaymentManaProductionProfiles()` must continue to reject output profiles that disagree with the final `ManaSource` aggregate. `invalidatePaymentManaProductionProfiles(reason)` must invalidate both production and side-effect maps so a runtime production modifier cannot leave a stale certificate behind.

- [ ] **Step 5: Compile the Rules module without running a broad test.** Use the focused RED class as the next check after the implementation is integrated; do not add execution logic to this file.

### Task 4: Carry certificates through source discovery and close the source-level gate

**Files:** Modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/ManaSolver.kt`; modify `rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/mana/PaymentPlanValidator.kt`.

- [ ] **Step 1: Add the per-key field to `ManaSource`.** Add `paymentManaSideEffectCertificates: Map<String, PaymentManaSideEffectCertificate> = emptyMap()` adjacent to `paymentManaProductionProfiles`. It is internal Rules metadata and does not alter any Gym DTO.

- [ ] **Step 2: Populate both maps for every discovered ability.** At the same point where `ManaSolver` stores `PaymentManaProductionProfileResolver.resolve(ability.effect, effectColors)`, store `PaymentManaSideEffectCertificateResolver.resolve(ability.effect)` under `ManaAbilityIdentity.key(ability)`. Intrinsic subtype abilities receive `NoSideEffect` under their `intrinsic(...)` key. All source copies retain the maps, and every invalidation path clears/closes both.

- [ ] **Step 3: Replace duplicate pain inspection with the resolver output.** Use the exact certificate's `FixedSelfDamage.amount` only to calculate the existing solver preference/crosscheck metadata for the ability. Do not use `colorPainCost` or `selfDamageAmount` to choose an ability or to execute damage. Remove the private duplicate traversal once all callers use the Rules-owned certificate resolver.

- [ ] **Step 4: Strengthen `supportsPaymentPlanV1()` as a complete proof.** Keep all existing guards for sacrifice, secondary tap, activation mana costs, restrictions, riders, context-sensitive abilities, unrepresented intrinsic choices, and non-ordinary tap costs. For profile-bearing sources require equal production/certificate key sets, no unsupported profile/certificate, and valid production/aggregate reconciliation. The source must be rejected if any currently legal payment-relevant ability is absent or unsupported.

- [ ] **Step 5: Make pain metadata a cross-check only.** For every currently legal color and colorless ability option, derive the minimum certified self-damage amount from the certificate bound to that exact ability key. Compare the derived values with `colorPainCost`/`colorlessPainCost`; reject mismatches. Also reject any `PayLife` or other unsupported cost through the existing ordinary-tap-cost gate. Never infer `FixedSelfDamage` from an aggregate pain value.

- [ ] **Step 6: Keep `PaymentPlanValidator` execution unchanged except for the closure gate.** Its existing lookup must still resolve the submitted `manaAbilityKey` to the exact current `ActivatedAbility`, and the accepted materialization must continue to pass that ability to `ManaAbilitySideEffectExecutor`. Validator rejection remains state-atomic.

### Task 5: Run GREEN focused regressions and repair only implementation failures

**Files:** The Rules/Gym files above only.

- [ ] **Step 1: Run the Rules certificate class.** Run `just test-class PaymentManaSideEffectCertificateTest`; if blocked, run the labeled native fallback. Confirm the supported composite, no-side-effect, unsupported-shape, key-completeness, and pain-crosscheck tests pass.

- [ ] **Step 2: Run the real Llanowar Wastes Gym class.** Confirm all `{C}`, `{B}`, and `{G}` entries publish, explicit public plans execute, colored abilities damage exactly once, colorless does not damage, and invalid plans remain atomic.

- [ ] **Step 3: Run the real Tear Asunder Gym class.** Confirm normal and kicked cost variants both publish PaymentDomainV4 and that each plan is derived from the observed public domain rather than solver internals.

- [ ] **Step 4: Run surrounding existing payment tests.** Run `just test-class PaymentPlanV1Test`, `just test-class PaymentPlanV2Test`, `just test-class ExplicitPaymentPlanExecutorTest`, `just test-class GameGymEnvPaymentDomainAuthorityTest`, and `just test-class GameGymEnvPaymentBundleTest`. A failure outside the changed contract is reported and not reverted or worked around.

- [ ] **Step 5: Verify unsupported shapes remain closed.** Add or retain assertions for dynamic/choice-bearing effects, fixed damage to another recipient, multiple non-mana effects, restrictions, riders, pay-life costs, secondary activation costs, and one unsupported sibling. Every such source must publish no partial PaymentDomainV4.

### Task 6: Document, run affected suites, and perform final review

**Files:** Modify `docs/data-contracts.md`; inspect all changed files.

- [ ] **Step 1: Document the unchanged wire contract.** In the PaymentDomainV4 section, state that fixed self-damage is admitted only through a Rules-owned per-ability closure certificate keyed by the already-public `manaAbilityKey`; execution remains in `ManaAbilitySideEffectExecutor`; the DTO/schema/replay versions do not change; and one unsupported sibling withholds the complete source.

- [ ] **Step 2: Run the complete affected Rules and Gym suites.** Run `just test-rules` and `just test-gym`. If `just` remains blocked by `WinError 193`, run clearly labeled native `./gradlew.bat --no-daemon :rules-engine:test :gym:test --console=plain` equivalents. Do not run the 72-episode corpus or any PR #73 acceptance command.

- [ ] **Step 3: Run static and scope checks.** Run `git diff --check`, inspect `git diff --stat origin/main...HEAD`, verify `git status --short`, and search the diff for `Llanowar`, `TearAsunder`, PR #73 paths, corpus paths, schema/replay version changes, new damage execution, and automatic payment selection. Card names may appear only in tests/fixtures, never in production logic.

- [ ] **Step 4: Perform independent two-axis diff review.** Review the full diff against the approved spec for standards and contract fidelity. Confirm the certificate is not consumed as execution authority, the key maps are exact, `colorPainCost` is only a consistency check, all source siblings are required, and transactional executor behavior is preserved. Resolve findings and rerun focused tests.

- [ ] **Step 5: Record hosted CI separately.** Verify `origin` is `https://github.com/chrismaghuhn/argentum-engine.git` before any authorized branch publication. Report local `PASS`/`FAIL`, wrapper `BLOCKED`/native `PASS`, hosted CI status, and coverage status independently. Do not create a PR unless explicitly requested.

### Task 7: Post-merge acceptance boundary

- [ ] **Step 1:** Only after this production change is merged and the user explicitly authorizes synchronization, sync PR #73 without editing its files or rebasing its corpus changes into this branch.
- [ ] **Step 2:** Run the exact 72 episodes from `0/72` with the pinned seeds/configuration.
- [ ] **Step 3:** Confirm episode 3, game seed `1`, policy seed `5259908`, previous step `1571`, crosses the former Tear Asunder/Llanowar Wastes failure.
- [ ] **Step 4:** Stop at the first NEW failure and report the corpus separately from implementation/CI evidence. This task does not perform any of these steps.
