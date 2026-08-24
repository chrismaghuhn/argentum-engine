# A5 Equip PaymentDomainV4 Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Publish the existing PaymentDomainV4 contract for fixed, target-invariant Equip ActivateAbility actions and prove the complete explicit V2 payment path through ActivateAbilityHandler without weakening the fail-closed boundary.

**Architecture:** Add a Rules-owned ActivatedAbilityCostCalculator that owns the handler's complete effective-cost transformation chain: source text replacement, ability generic reduction, activated-ability reduction, target-aware Equip reduction, explicit free-first Equip choice, and colored-cost relaxation. Refactor ActivateAbilityHandler to use it, and make ObservationBuilder use the same calculator to compare the advertised cost against the unbound and every public target-bound cost. The existing target mapper, PaymentDomainBuilder, PaymentPlanValidator, and Gym ExplicitV2 gate remain the contract owners; Gym does not reimplement cost semantics.

**Tech Stack:** Kotlin, Gradle/just, Kotest, Argentum Rules ECS, Gym ActionTargetDomainV1, PaymentDomainV4, PaymentPlanV2, and PaymentStrategy.ExplicitV2.

---

## File map

- Create rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/cost/ActivatedAbilityCostCalculator.kt: shared exact effective-ability-cost calculation.
- Modify rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/ability/ActivateAbilityHandler.kt: use the shared calculation in validation and execution.
- Create rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/cost/ActivatedAbilityCostCalculatorTest.kt: exact fixed/dynamic Equip cost tests.
- Modify gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt: retain the Equip guard and add the all-or-nothing proof.
- Modify gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentDomainAuthorityTest.kt: RED, publication, and fail-closed target/payment-shape tests.
- Modify gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvActionContractTest.kt: complete public ExplicitV2 execution and rejection tests.
- Never modify PR #73, decklists, cards, Seed-0 policy, ML, B0, trajectories, the 72-episode corpus, or public schema/replay versions.

### Task 1: Add and run the RED reproduction

Files: modify gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentDomainAuthorityTest.kt.

- [ ] Step 1: Prepare a real fixed Equip action. Rename the misleading fixed {1} fixture to fixedCostEquipment. Add preparedEquipPayment() that moves the Equipment, one controlled creature, and one Mountain onto Alice's battlefield in precombat main, then returns the environment and all discovered entity IDs. Use the existing seeded setup and state lookup conventions; do not add a production card.
- [ ] Step 2: Write the public RED test. Use the actual environment.legalActions() result and ObservationBuilder, not a synthetic replacement LegalAction. Select the target-bearing ActivateAbility for the Equipment and assert that the canonical target domain has the target with min/max one, the public mana cost is {1}, the payment domain is non-null, and its version is 4. Before the production change this must fail because the current ability.isEquipAbility guard returns null.
- [ ] Step 3: Run only the RED test with just test-class GameGymEnvPaymentDomainAuthorityTest. Expected before implementation: FAIL at the non-null payment-domain assertion. If just is blocked by the known Windows WSL/WinError 193 launcher issue, run the equivalent gradlew.bat test as separately labeled fallback evidence and report just as not run.
- [ ] Step 4: Commit the RED test with git add gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentDomainAuthorityTest.kt followed by git commit -m "test: reproduce missing equip payment domain".

### Task 2: Extract the Rules-owned exact cost pipeline

Files: create rules-engine/src/main/kotlin/com/wingedsheep/engine/mechanics/cost/ActivatedAbilityCostCalculator.kt; modify rules-engine/src/main/kotlin/com/wingedsheep/engine/handlers/actions/ability/ActivateAbilityHandler.kt; create rules-engine/src/test/kotlin/com/wingedsheep/engine/mechanics/cost/ActivatedAbilityCostCalculatorTest.kt.

- [ ] Step 1: Add a public Rules-owned calculator with calculate(state, sourceId, controllerId, ability, targets = emptyList(), equipPayment = null): AbilityCost. It must read the source TextReplacementComponent and apply exactly this order: text replacement; ability generic reduction using the existing DynamicAmountEvaluator and EffectContext(sourceId, controllerId, targets); CastPermissionUtils.applyActivatedAbilityCostReduction; CastPermissionUtils.applyEquipCostReduction with the first chosen permanent target and the ability source; CastPermissionUtils.applyFreeFirstEquipDiscount; and CastPermissionUtils.relaxAbilityCostColorsIfAny.
- [ ] Step 2: Move the existing handler applyGenericCostReduction and reduceGenericInCost behavior into the calculator unchanged, including target context and evaluator behavior. Do not add target or payment selection.
- [ ] Step 3: In both ActivateAbilityHandler.validate and executeActivation, replace the duplicated local raw-cost/reduction chain with the shared calculator using the submitted action targets and action.alternativePayment?.equipPayment. Keep the handler's separate text replacement for effect and target requirements. Preserve all ExplicitV2 validation, source exclusion, target validation, stack, and attachment behavior. Remove only the now-unused private generic-cost helper and reducer.
- [ ] Step 4: Add ActivatedAbilityCostCalculatorTest with complete AbilityCost comparisons covering: fixed ActivatedAbility.equip(ManaCost.parse("{1}")) with no target and every legal target; targetPower generic reduction with different target powers; ReduceEquipCost.onlyIfTargetIsSource with the granting and non-granting target; and a non-mana Equip cost remaining a non-mana AbilityCost rather than becoming a partial mana string.
- [ ] Step 5: Run just test-class ActivatedAbilityCostCalculatorTest and just test-class EquipAbilityManaRestrictionTest. Commit the Rules seam with git add on the three listed Rules files and git commit -m "refactor: share activated ability effective cost semantics".

### Task 3: Implement the all-or-nothing Gym proof

Files: modify gym/src/main/kotlin/com/wingedsheep/gym/contract/ObservationBuilder.kt.

- [ ] Step 1: Add a lazy ActivatedAbilityCostCalculator built from the existing castPermissionUtils. Do not copy the transformation chain into Gym.
- [ ] Step 2: Change only the current Equip clause to ability.isEquipAbility && !isSupportedEquipPayment(state, legalAction, action, ability, requiredCost). Keep X, Convoke, Waterbend, tap-for-generic, alternative payment, additional-cost, and targeted generic-reduction rejection unchanged.
- [ ] Step 3: Implement isSupportedEquipPayment. It must require ActionTargetDomainMapper.map(legalAction) { true } to return Supported; one canonical mandatory fixed target requirement with minTargets 1 and maxTargets 1 and at least one candidate; one corresponding Rules ability target requirement; an ordinary mana-only base cost; no target-dependent generic reduction; and a public cost parsed using only colored, colorless, and generic symbols.
- [ ] Step 4: Use the shared calculator once with no chosen target to model the unbound/enumerated cost and once with ChosenTarget.Permanent(candidateId) for every candidate in legalAction.targetRequirements. Require every complete AbilityCost to equal AbilityCost.Atom(CostAtom.Mana(parsedPublicCost)). If one candidate differs, return false for the entire action; never remove a candidate from the target domain.
- [ ] Step 5: Only after the proof succeeds, use the existing buildAbilityPaymentContext, including its Equip classification, and the unchanged PaymentDomainBuilder and tap-source exclusion. The predicate must not solve for a target or payment.
- [ ] Step 6: Run just test-class GameGymEnvPaymentDomainAuthorityTest and confirm the RED test is green while dynamic target-dependent Equip remains null. Commit with git commit -m "gym: publish exact payment domains for fixed equip".

### Task 4: Prove the complete public ExplicitV2 chain and negative matrix

Files: modify gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvPaymentDomainAuthorityTest.kt and gym/src/test/kotlin/com/wingedsheep/gym/GameGymEnvActionContractTest.kt.

- [ ] Step 1: Add inline test-only generic dynamic and target-restricted negatives. For equipAbility("{1}", genericCostReduction = DynamicAmounts.targetPower()), place targets with different powers and assert paymentDomain is null while the target domain retains every candidate. Add a fixed Equip plus ReduceEquipCost.onlyIfTargetIsSource fixture with the granting target and another candidate; assert the entire payment domain is null, not that one target is removed.
- [ ] Step 2: Keep alternative/free-first, X, Convoke, and other unsupported shapes fail-closed. Extend existing direct legal-action coverage only where needed by constructing the real ability and setting alternativePayment, hasXCost, hasConvoke, or additionalCostInfo, then asserting null. Never use AutoPay or a native fallback.
- [ ] Step 3: In GameGymEnvActionContractTest, observe the real target-bearing Equip action and construct a PaymentStrategy.ExplicitV2 from the observed domain: one SourceActivation using source.sourceId, source.manaAbilityKey, and source.productionChoices.single(); an empty PoolSpend; and one CostUnitAllocationV2 for the domain's symbolIndex containing ManaSpendReferenceV2(sourceId = source.sourceId, amount = 1). Overlay actionSemantics with this payment and one explicit serialized Permanent target, then submit with gym.step(actionId, payload).
- [ ] Step 4: Assert the ExplicitV2 submission succeeds and the Equipment's AttachedToComponent.targetId equals the submitted target. This proves TargetDomain -> PaymentDomainV4 -> PaymentPlanV2 -> ExplicitV2 -> trusted Gym gate -> handler validation -> payment -> attach.
- [ ] Step 5: Submit a different public target or an incomplete {1} allocation and assert rejection with unchanged environment step count.
- [ ] Step 6: Run just test-class GameGymEnvPaymentDomainAuthorityTest and just test-class GameGymEnvActionContractTest. Commit with git commit -m "test: prove explicit v2 equip gym contract".

### Task 5: Regression, hosted CI, and independent review

- [ ] Step 1: Run the focused gates: just test-class ActivatedAbilityCostCalculatorTest; just test-class EquipAbilityManaRestrictionTest; just test-class GameGymEnvPaymentDomainAuthorityTest; and just test-class GameGymEnvActionContractTest.
- [ ] Step 2: Run just test-rules and just test-gym. Run just test only as the relevant full repository regression after these are clean. Do not run any Seed-0 or 72-episode corpus recipe. If just is blocked, run separately labeled gradlew.bat equivalents and report exact statuses.
- [ ] Step 3: Inspect with git diff --check, git status --short, git diff --stat origin/main...HEAD, and the complete diff for ObservationBuilder, ActivatedAbilityCostCalculator, and ActivateAbilityHandler. Confirm no Bonesplitter branch, target filtering, AutoPay/native fallback, schema/replay bump, PR #73/decklist/card/corpus change, or duplicated full cost chain in Gym.
- [ ] Step 4: Before push, verify git remote get-url origin is https://github.com/chrismaghuhn/argentum-engine.git and record git rev-parse HEAD and git rev-parse origin/main. The authorized PR must target chrismaghuhn/argentum-engine; hosted CI is separate evidence from local gates.
- [ ] Step 5: Independently review every changed file before merge. Confirm the shared calculator is the only full activated-ability cost owner, every public target is checked all-or-nothing, the handler validates ExplicitV2 against the chosen target, and unsupported shapes remain fail-closed. Record every requested gate as PASS, FAIL, NOT_RUN, SKIPPED, or BLOCKED.
