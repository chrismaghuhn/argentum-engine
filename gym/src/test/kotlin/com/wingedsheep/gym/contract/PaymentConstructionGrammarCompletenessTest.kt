package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.AtomicManaCostUnitV1
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.FixedManaOutput
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.InitialPoolBucketV1
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.engine.core.canonicalizeInitialPoolBucketsV1
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.handlers.PredicateEvaluator
import com.wingedsheep.engine.mechanics.mana.ManaSolver
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidation
import com.wingedsheep.engine.mechanics.mana.PaymentPlanValidator
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.SummoningSicknessComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.gym.ActionPaymentPlanValidator
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.mtg.sets.definitions.arn.cards.CityOfBrass
import com.wingedsheep.mtg.sets.definitions.lea.cards.BirdsOfParadise
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.rav.cards.GolgariRotFarm
import com.wingedsheep.mtg.sets.definitions.rav.cards.GolgariSignet
import com.wingedsheep.mtg.sets.definitions.thb.cards.Shadowspear
import com.wingedsheep.mtg.sets.definitions.wth.cards.MindStone
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityCost
import com.wingedsheep.sdk.scripting.costs.CostAtom
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * C1_LIVE_PAYMENT_CHOICE_BOUNDARY_02A / TASK 2 — completeness proof and measurements for
 * [PaymentConstructionGrammarV1] (docs/ml/c1-live-payment-choice-boundary-02a.md, TASK 2 sections).
 *
 * Gate A  small-domain completeness: for every fixture the set of grammar-reachable terminal plans
 *         equals the independent flat reference set under the frozen conservative identity (§7).
 * Gate A+ authority agreement: every reachable plan is AcceptedV3 by the Rules PaymentPlanValidator
 *         on the real state, passes the trusted ActionPaymentPlanValidator seam, and executes
 *         through the engine leaving exactly the predicted floating mana.
 * Gate B  structural coverage: each published V5 dimension D1-D11 is exercised non-degenerately by
 *         a named fixture or DTO-level case (asserted where it is exercised).
 * Gate C  measured maximum branching per fixture versus the structural bound.
 * Gate D  measured maximum construction depth per fixture versus the structural bound.
 * Gate E  exact canonical terminal-plan counts per fixture (pinned).
 * Gate F  bound derivation over the locked Akiri/Chevill curriculum envelope.
 * Gate G  determinism, canonical step order, step injectivity, one path per plan, no dead ends.
 * Gate H  public-data-only API (no GameState / engine-service parameter anywhere).
 */
class PaymentConstructionGrammarCompletenessTest : FunSpec({

    // ---------------------------------------------------------------- fixtures F1-F8 (real cards)

    test("F1: one certified pool bucket, {1} — exactly one plan") {
        val fixture = buildFixture(
            label = "F1",
            deck = mapOf(Shadowspear.name to 1, "Plains" to 3),
            battlefield = listOf(Shadowspear.name, "Plains"),
            floatNames = listOf("Plains"),
            outer = Outer.ActivateFirstAbility(Shadowspear.name),
        )
        val report = proveFixture(fixture)
        report.terminalCount shouldBe 1
        fixture.domain.initialPoolBuckets.size shouldBe 1
        fixture.domain.sourceActivationOptions.shouldBeEmpty()
    }

    test("F2: two provenance-distinct certified buckets, {1} — two plans (the _01 fixture)") {
        val fixture = buildFixture(
            label = "F2",
            deck = mapOf(Shadowspear.name to 1, "Plains" to 4),
            battlefield = listOf(Shadowspear.name, "Plains", "Plains"),
            floatNames = listOf("Plains", "Plains"),
            outer = Outer.ActivateFirstAbility(Shadowspear.name),
        )
        val report = proveFixture(fixture)
        report.terminalCount shouldBe 2
        // D2: two keyed CertifiedFloatingBucket resources, both reachable.
        fixture.domain.initialPoolBuckets.map { it.key }.forEach {
            it.shouldBeInstanceOf<InitialPoolBucketKeyV1.CertifiedFloatingBucket>()
        }
    }

    test("F3: two certified buckets plus one untapped source, {2} — pool+activation multi-step") {
        val fixture = buildFixture(
            label = "F3",
            deck = mapOf(MindStone.name to 1, "Plains" to 5),
            battlefield = listOf("Plains", "Plains", "Plains"),
            hand = listOf(MindStone.name),
            floatNames = listOf("Plains", "Plains"),
            outer = Outer.CastFromHand(MindStone.name),
        )
        val report = proveFixture(fixture)
        report.terminalCount shouldBe F3_EXPECTED
        // A plan that activates the untapped source without spending its output (surplus floats).
        report.surplusActivationPlans shouldBeGreaterThan 0
    }

    test("F4: production-choice axis, evidence-driven (productionChoices.size > 1)") {
        val chosen = multiChoiceCandidate()
        println("F4 evidence: first candidate emitting productionChoices.size > 1 = ${chosen.card.name} " +
            "(sizes=${chosen.productionSizes})")
        val fixture = buildFixture(
            label = "F4",
            deck = mapOf(Shadowspear.name to 1, chosen.card.name to 1, "Plains" to 3),
            battlefield = listOf(Shadowspear.name, chosen.card.name, "Plains"),
            outer = Outer.ActivateFirstAbility(Shadowspear.name),
            extraCards = listOf(chosen.card),
        )
        val report = proveFixture(fixture)
        report.terminalCount shouldBe F4_EXPECTED
        fixture.domain.sourceActivationOptions.maxOf { it.productionChoices.size } shouldBeGreaterThan 1
    }

    test("F4b: fixed-output bundle axis (Golgari Rot Farm), {1} — outputs are distinct resources") {
        val fixture = buildFixture(
            label = "F4b",
            deck = mapOf(Shadowspear.name to 1, GolgariRotFarm.name to 1, "Forest" to 3),
            battlefield = listOf(Shadowspear.name, GolgariRotFarm.name),
            outer = Outer.ActivateFirstAbility(Shadowspear.name),
            extraCards = listOf(GolgariRotFarm),
        )
        val bundle = fixture.domain.sourceActivationOptions.single().productionChoices.single()
        bundle.fixedOutputs!!.map { it.color } shouldBe listOf(PaymentManaColor.BLACK, PaymentManaColor.GREEN)
        val report = proveFixture(fixture)
        report.terminalCount shouldBe 2
    }

    test("F5: two multi-choice sources plus a mixed {1}{W} cost — branching measurement") {
        val chosen = multiChoiceCandidate()
        val fixture = buildFixture(
            label = "F5",
            deck = mapOf(chosen.card.name to 2, "Armored Pegasus" to 1, "Plains" to 3),
            battlefield = listOf(chosen.card.name, chosen.card.name),
            hand = listOf("Armored Pegasus"),
            outer = Outer.CastFromHand("Armored Pegasus"),
            extraCards = listOf(chosen.card),
        )
        fixture.domain.outerAtomicCostUnits.map { it.kind }.toSet() shouldBe
            setOf(PaymentCostKindV1.GENERIC, PaymentCostKindV1.COLORED)
        val report = proveFixture(fixture)
        report.terminalCount shouldBe F5_EXPECTED
    }

    test("F6: paid source with inner cost and a real cost order (Golgari Signet + two Forests), {2}") {
        val fixture = f6Fixture()
        val signet = fixture.domain.sourceActivationOptions.single { it.sourceName == GolgariSignet.name }
        signet.atomicActivationManaCostUnits.size shouldBe 1
        signet.activationCostOrderOptions.single() shouldBe listOf(
            ActivationCostComponentRefV1.ManaComponent,
            ActivationCostComponentRefV1.DeterministicNonManaComponent(0),
        )
        val report = proveFixture(fixture)
        report.terminalCount shouldBe F6_EXPECTED
        // D5: some reachable plan pays the Signet's inner cost from an earlier activation output.
        report.innerPaidFromOutputPlans shouldBeGreaterThan 0
    }

    test("F7: impossible payment (demand > supply) — no false terminal plan, typed refusal") {
        // Measured first: the engine menu never offers the unaffordable paid action, so no V5
        // domain for it can reach the grammar through the live path.
        val unaffordable = buildFixture(
            label = "F7-menu",
            deck = mapOf(MindStone.name to 1, "Forest" to 3),
            battlefield = listOf("Forest"),
            hand = listOf(MindStone.name),
            outer = Outer.CastFromHand(MindStone.name),
            requireOuter = false,
        )
        unaffordable.outerOrNull shouldBe null
        // The impossible shape is therefore derived from a REAL emission (F6: Golgari Signet +
        // two Forests, {2}) by raising only the outer demand above the total supply (4 mana max).
        val real = f6Fixture().domain
        val impossible = real.copy(
            requiredCost = "{5}",
            outerAtomicCostUnits = (0 until 5).map { generic(0, it) },
        )
        PaymentConstructionFlatReferenceEnumerator.enumerate(impossible).legalPlans.shouldBeEmpty()
        PaymentConstructionGrammarV1.initial(impossible).shouldBeInstanceOf<PaymentConstructionResultV1.Failed>()
            .failure.shouldBeInstanceOf<PaymentConstructionFailureV1.NoLegalPaymentV1>()
        // Colour-impossible variant: {W}{W} from a black/green-only supply.
        val colourImpossible = real.copy(
            requiredCost = "{W}{W}",
            outerAtomicCostUnits = (0 until 2).map {
                AtomicManaCostUnitV1(it, 0, PaymentCostKindV1.COLORED, setOf(PaymentManaColor.WHITE))
            },
        )
        PaymentConstructionFlatReferenceEnumerator.enumerate(colourImpossible).legalPlans.shouldBeEmpty()
        PaymentConstructionGrammarV1.initial(colourImpossible).shouldBeInstanceOf<PaymentConstructionResultV1.Failed>()
            .failure.shouldBeInstanceOf<PaymentConstructionFailureV1.NoLegalPaymentV1>()
        println("F7: menu offers unaffordable action=false; derived {5} and {W}{W}: reference=0 grammar=NoLegalPaymentV1")
    }

    test("F8: duplicate-path shape (three certified buckets, {2}) — one path per plan") {
        val fixture = buildFixture(
            label = "F8",
            deck = mapOf(MindStone.name to 1, "Plains" to 5),
            battlefield = listOf("Plains", "Plains", "Plains"),
            hand = listOf(MindStone.name),
            floatNames = listOf("Plains", "Plains", "Plains"),
            outer = Outer.CastFromHand(MindStone.name),
        )
        val report = proveFixture(fixture)
        // Ordered pairs of distinct capacity-1 buckets for the two GENERIC units.
        report.terminalCount shouldBe 6
        report.terminalPaths shouldBe report.terminalCount
        // A free-target-order construction would reach every plan once per target order (2! = 2).
        val naivePaths = report.terminalCount * 2
        println("F8: grammar paths=${report.terminalPaths} distinct plans=${report.terminalCount} " +
            "free-target-order paths=$naivePaths")
    }

    // --------------------------------------------------------- DTO-level generality (Gates A/B/G)

    test("Gate A (DTO-level): seeded random V5 domains — grammar set equals reference set") {
        val random = Random(20260930)
        var compared = 0
        var skipped = 0
        var totalPlans = 0L
        var maxBranching = 0
        var maxDepth = 0
        var maxWork = 0L
        var budgetBinding = 0
        var noLegal = 0
        var twoUnitInnerCompared = 0
        repeat(RANDOM_CASES) {
            val domain = randomDomain(random)
            val reference = runCatching { PaymentConstructionFlatReferenceEnumerator.enumerate(domain, RANDOM_CASE_MAX_CANDIDATES) }
                .getOrElse { error ->
                    if (error is IllegalStateException && error.message.orEmpty().startsWith("Reference fixture too large")) {
                        skipped++
                        return@repeat
                    }
                    throw error
                }
            val referenceIds = reference.legalPlans.map(PaymentConstructionFlatReferenceEnumerator::identity).toSet()
            val initial = PaymentConstructionGrammarV1.initial(domain)
            if (referenceIds.isEmpty()) {
                initial.shouldBeInstanceOf<PaymentConstructionResultV1.Failed>()
                    .failure.shouldBeInstanceOf<PaymentConstructionFailureV1.NoLegalPaymentV1>()
                noLegal++
            } else {
                val walk = walkGrammar(domain)
                withClue("random case $it: $domain") {
                    walk.identities shouldBe referenceIds
                }
                walk.terminalPaths shouldBe walk.identities.size
                walk.plans.forEach { plan ->
                    PaymentConstructionFlatReferenceEnumerator.violation(domain, plan) shouldBe null
                }
                totalPlans += walk.identities.size
                maxBranching = maxOf(maxBranching, walk.maxBranching)
                maxDepth = maxOf(maxDepth, walk.maxDepth)
                maxWork = maxOf(maxWork, walk.maxWork)
                val bounds = PaymentConstructionGrammarV1.structuralBounds(domain)
                walk.maxBranching.toLong() shouldBeLessThanOrEqual bounds.alternatives
                walk.maxDepth.toLong() shouldBeLessThanOrEqual bounds.depth
            }
            if (domain.sourceActivationOptions.any { it.atomicActivationManaCostUnits.size == 2 }) twoUnitInnerCompared++
            if (domain.fixedSelfDamageBudget != null &&
                domain.sourceActivationOptions.sumOf { it.fixedSelfDamageAmount } > domain.fixedSelfDamageBudget!!
            ) {
                budgetBinding++
            }
            compared++
        }
        println(
            "GateA-DTO: cases=$RANDOM_CASES compared=$compared skipped=$skipped noLegalPayment=$noLegal " +
                "plans=$totalPlans maxBranching=$maxBranching maxDepth=$maxDepth maxViabilityWork=$maxWork " +
                "budgetBindingCases=$budgetBinding twoUnitInnerCompared=$twoUnitInnerCompared",
        )
        compared shouldBeGreaterThan RANDOM_CASES * 9 / 10
        budgetBinding shouldBeGreaterThan 0
        twoUnitInnerCompared shouldBeGreaterThan 0
        noLegal shouldBeGreaterThan 0
    }

    test("Gate B (DTO-level): D6 multiple cost-order options and D10 self-damage budget are enumerated") {
        val source = EntityId.of("dto-source")
        val painA = EntityId.of("dto-pain-a")
        val painB = EntityId.of("dto-pain-b")
        val domain = PaymentDomainV5(
            requiredCost = "{1}",
            outerAtomicCostUnits = listOf(generic(0, 0)),
            initialPoolBuckets = listOf(InitialPoolBucketV1(InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.GREEN), 1)),
            sourceActivationOptions = listOf(
                PaymentSourceActivationDomainV2(
                    sourceId = source,
                    sourceName = "dto paid source",
                    manaAbilityKey = "paid",
                    productionChoices = listOf(ProductionChoice(PaymentManaColor.RED)),
                    atomicActivationManaCostUnits = listOf(generic(0, 0)),
                    activationSupportKind = PaymentActivationSupportKindV1.FIXED_MANA_AND_TAP_SELF,
                    deterministicNonManaCosts = listOf(PaymentDeterministicNonManaCostKindV1.TAP_SELF),
                    activationCostOrderOptions = listOf(
                        listOf(ActivationCostComponentRefV1.ManaComponent, ActivationCostComponentRefV1.DeterministicNonManaComponent(0)),
                        listOf(ActivationCostComponentRefV1.DeterministicNonManaComponent(0), ActivationCostComponentRefV1.ManaComponent),
                    ),
                ),
                painOption(painA, PaymentManaColor.WHITE),
                painOption(painB, PaymentManaColor.WHITE),
            ),
            reservedOuterLifePayment = 3,
            fixedSelfDamageBudget = 1,
        )
        val referenceIds = PaymentConstructionFlatReferenceEnumerator.enumerate(domain).legalPlans
            .map(PaymentConstructionFlatReferenceEnumerator::identity).toSet()
        val walk = walkGrammar(domain)
        walk.identities shouldBe referenceIds
        // D6: both published cost orders of the paid source are reachable as distinct plans.
        walk.plans.mapNotNull { plan -> plan.activations.firstOrNull { it.sourceId == source }?.activationCostOrder }
            .toSet().size shouldBe 2
        // D10: with budget 1 no reachable plan activates both pain sources.
        walk.plans.none { plan -> plan.activations.map { it.sourceId }.containsAll(listOf(painA, painB)) } shouldBe true
        walk.plans.any { plan -> painA in plan.activations.map { it.sourceId } } shouldBe true
        println("GateB-DTO: plans=${walk.identities.size} orders=2 budgetRespected=true")
    }

    // ---------------------------------------------------------------- typed failures (Gate D11)

    test("typed failures: unsupported shape, bound exceeded, invalid step, premature materialize") {
        val base = PaymentDomainV5(
            requiredCost = "{1}",
            outerAtomicCostUnits = listOf(generic(0, 0)),
            initialPoolBuckets = listOf(InitialPoolBucketV1(InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.RED), 1)),
            sourceActivationOptions = emptyList(),
        )
        val root = PaymentConstructionGrammarV1.initial(base).shouldBeInstanceOf<PaymentConstructionResultV1.Ok<PaymentConstructionStateV1>>().value

        PaymentConstructionGrammarV1.materialize(root).shouldBeInstanceOf<PaymentConstructionResultV1.Failed>()
            .failure.shouldBeInstanceOf<PaymentConstructionFailureV1.InvalidPartialConstructionV1>()
        PaymentConstructionGrammarV1.apply(root, PaymentConstructionStepV1.Finalize)
            .shouldBeInstanceOf<PaymentConstructionResultV1.Failed>()
            .failure.shouldBeInstanceOf<PaymentConstructionFailureV1.InvalidPartialConstructionV1>()
        // A hand-made finalized state with unpaid cost units is refused, never trusted.
        val forged = root.copy(finalized = true)
        PaymentConstructionGrammarV1.nextSteps(forged).shouldBeInstanceOf<PaymentConstructionResultV1.Failed>()
            .failure.shouldBeInstanceOf<PaymentConstructionFailureV1.InvalidPartialConstructionV1>()

        val duplicateChoice = base.copy(
            sourceActivationOptions = listOf(
                painOption(EntityId.of("dup"), PaymentManaColor.RED).copy(
                    productionChoices = listOf(ProductionChoice(PaymentManaColor.RED), ProductionChoice(PaymentManaColor.RED)),
                ),
            ),
        )
        PaymentConstructionGrammarV1.initial(duplicateChoice).shouldBeInstanceOf<PaymentConstructionResultV1.Failed>()
            .failure.shouldBeInstanceOf<PaymentConstructionFailureV1.UnsupportedDomainShapeV1>()

        val deep = base.copy(
            outerAtomicCostUnits = (0 until MAX_PAYMENT_CONSTRUCTION_DEPTH).map { generic(0, it) },
            initialPoolBuckets = listOf(
                InitialPoolBucketV1(InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.RED), MAX_PAYMENT_CONSTRUCTION_DEPTH),
            ),
        )
        PaymentConstructionGrammarV1.initial(deep).shouldBeInstanceOf<PaymentConstructionResultV1.Failed>()
            .failure.shouldBeInstanceOf<PaymentConstructionFailureV1.ConstructionBoundExceededV1>()
            .bound shouldBe PaymentConstructionBoundV1.DEPTH

        // Viability-work ceiling: 20 pain sources with pairwise distinct damage (so none collapse into
        // one source type), a 14-white demand, and a budget of 100 < 1+2+...+14. The cost-free
        // relaxation cannot see the budget, so the exhaustive search runs until the ceiling fails it
        // closed instead of answering.
        val exhausting = PaymentDomainV5(
            requiredCost = "{W}x14",
            outerAtomicCostUnits = (0 until 14).map {
                AtomicManaCostUnitV1(it, 0, PaymentCostKindV1.COLORED, setOf(PaymentManaColor.WHITE))
            },
            initialPoolBuckets = emptyList(),
            sourceActivationOptions = (1..20).map { damage ->
                painOption(EntityId.of("pain-$damage"), PaymentManaColor.WHITE).copy(fixedSelfDamageAmount = damage)
            },
            reservedOuterLifePayment = 1,
            fixedSelfDamageBudget = 100,
        )
        val exhausted = PaymentConstructionGrammarV1.initial(exhausting).shouldBeInstanceOf<PaymentConstructionResultV1.Failed>()
            .failure.shouldBeInstanceOf<PaymentConstructionFailureV1.ConstructionBoundExceededV1>()
        exhausted.bound shouldBe PaymentConstructionBoundV1.VIABILITY_WORK

        val bonus = base.copy(
            sourceActivationOptions = listOf(
                painOption(EntityId.of("bonus"), PaymentManaColor.RED).copy(
                    productionChoices = listOf(ProductionChoice(PaymentManaColor.RED, bonusChoice = PaymentManaColor.RED)),
                ),
            ),
        )
        PaymentConstructionGrammarV1.initial(bonus).shouldBeInstanceOf<PaymentConstructionResultV1.Failed>()
            .failure.shouldBeInstanceOf<PaymentConstructionFailureV1.UnsupportedDomainShapeV1>()

        val oversized = base.copy(
            sourceActivationOptions = (0 until MAX_PAYMENT_CONSTRUCTION_ALTERNATIVES).map {
                painOption(EntityId.of("wide-$it"), PaymentManaColor.RED)
            },
        )
        val refused = PaymentConstructionGrammarV1.initial(oversized).shouldBeInstanceOf<PaymentConstructionResultV1.Failed>()
            .failure.shouldBeInstanceOf<PaymentConstructionFailureV1.ConstructionBoundExceededV1>()
        refused.bound shouldBe PaymentConstructionBoundV1.ALTERNATIVES
        println("typed failures: Invalid/Unsupported/BoundExceeded(${refused.observed}>${refused.limit}) verified")
    }

    test("prefix replay rejects every forged ledger violation (L1-L7, order, publication)") {
        val red = InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.RED)
        val a = EntityId.of("land-a")
        val s = EntityId.of("paid-s")
        val s2 = EntityId.of("paid-s2")
        val p = EntityId.of("pain-p")
        fun free(sourceId: EntityId, key: String, color: PaymentManaColor) =
            painOption(sourceId, color).copy(manaAbilityKey = key, fixedSelfDamageAmount = 0)
        fun paid(sourceId: EntityId) = free(sourceId, "paid", PaymentManaColor.COLORLESS).copy(
            atomicActivationManaCostUnits = listOf(generic(0, 0)),
            activationCostOrderOptions = listOf(
                listOf(ActivationCostComponentRefV1.ManaComponent, ActivationCostComponentRefV1.DeterministicNonManaComponent(0)),
            ),
        )
        val domain = PaymentDomainV5(
            requiredCost = "{W}{1}",
            outerAtomicCostUnits = listOf(
                AtomicManaCostUnitV1(0, 0, PaymentCostKindV1.COLORED, setOf(PaymentManaColor.WHITE)),
                generic(1, 0),
            ),
            initialPoolBuckets = listOf(InitialPoolBucketV1(red, 1)),
            sourceActivationOptions = listOf(
                free(a, "a1", PaymentManaColor.GREEN),
                free(a, "a2", PaymentManaColor.WHITE),
                paid(s),
                paid(s2),
                painOption(p, PaymentManaColor.WHITE),
            ),
            reservedOuterLifePayment = 1,
            fixedSelfDamageBudget = 0,
        )
        PaymentConstructionGrammarV1.initial(domain).shouldBeInstanceOf<PaymentConstructionResultV1.Ok<PaymentConstructionStateV1>>()
        val options = domain.sourceActivationOptions
        fun act(index: Int, vararg inner: ManaResourceRefV1, position: Int = 0) = SourceActivationV2(
            sourceId = options[index].sourceId,
            manaAbilityKey = options[index].manaAbilityKey,
            productionChoice = options[index].productionChoices.single(),
            activationCostOrder = options[index].activationCostOrderOptions.single(),
            activationCostAllocation = inner.map { PaymentAllocationV1(PaymentTargetV1.ActivationCostUnit(position, 0, 0), it) },
        )
        fun output(activation: Int) = ManaResourceRefV1.ActivationOutputUnit(activation, 0)
        val pool = ManaResourceRefV1.InitialPoolResource(red)
        val outerWhite = PaymentTargetV1.OuterCostUnit(0, 0)
        val outerGeneric = PaymentTargetV1.OuterCostUnit(1, 0)

        val forgeries = mapOf(
            "non-canonical outer target order" to PaymentConstructionStateV1(
                domain, outerAllocation = listOf(PaymentAllocationV1(outerGeneric, pool)),
            ),
            "L1 one source activated twice" to PaymentConstructionStateV1(domain, listOf(act(0), act(1))),
            "L2 inner cost paid from its own output" to PaymentConstructionStateV1(domain, listOf(act(2, output(0)))),
            "L2 inner cost paid from a later output" to PaymentConstructionStateV1(
                domain, listOf(act(2, output(1)), act(0)),
            ),
            "L4 output spent twice" to PaymentConstructionStateV1(
                domain, listOf(act(0), act(2, output(0), position = 1), act(3, output(0), position = 2)),
            ),
            "L5 bucket capacity exceeded" to PaymentConstructionStateV1(
                domain, listOf(act(2, pool), act(3, pool, position = 1)),
            ),
            "L6 colour does not satisfy the unit" to PaymentConstructionStateV1(
                domain, outerAllocation = listOf(PaymentAllocationV1(outerWhite, pool)),
            ),
            "L7 self-damage budget exceeded" to PaymentConstructionStateV1(domain, listOf(act(4))),
            "activation after an unpaid activation" to PaymentConstructionStateV1(domain, listOf(act(2), act(0, position = 1))),
            "unpublished option" to PaymentConstructionStateV1(domain, listOf(act(0).copy(manaAbilityKey = "unpublished"))),
            "unpublished production" to PaymentConstructionStateV1(
                domain, listOf(act(0).copy(productionChoice = ProductionChoice(PaymentManaColor.BLACK))),
            ),
            "unpublished cost order" to PaymentConstructionStateV1(
                domain, listOf(act(0).copy(activationCostOrder = listOf(ActivationCostComponentRefV1.ManaComponent))),
            ),
            "outer allocation before the open activation is paid" to PaymentConstructionStateV1(
                domain, listOf(act(2)), listOf(PaymentAllocationV1(outerWhite, pool)),
            ),
        )
        for ((rule, state) in forgeries) {
            withClue(rule) {
                PaymentConstructionGrammarV1.nextSteps(state).shouldBeInstanceOf<PaymentConstructionResultV1.Failed>()
                    .failure.shouldBeInstanceOf<PaymentConstructionFailureV1.InvalidPartialConstructionV1>()
                PaymentConstructionGrammarV1.apply(state, PaymentConstructionStepV1.Finalize)
                    .shouldBeInstanceOf<PaymentConstructionResultV1.Failed>()
                    .failure.shouldBeInstanceOf<PaymentConstructionFailureV1.InvalidPartialConstructionV1>()
            }
        }
        // A well-formed prefix built from the same helpers is accepted (the forgeries are not vacuous).
        PaymentConstructionGrammarV1.nextSteps(PaymentConstructionStateV1(domain, listOf(act(1), act(2, pool, position = 1))))
            .shouldBeInstanceOf<PaymentConstructionResultV1.Ok<List<PaymentConstructionStepV1>>>()
        println("replay rejections verified: ${forgeries.keys}")
    }

    test("Gate A (DTO-level): a two-unit inner cost is paid unit by unit from mixed resources") {
        // "{2},{T}: Add {C}{C}" shape plus a free green source and one red bucket; the outer {C}{C}
        // can only come from the paid source, so every plan passes through a partly paid activation.
        val paidSource = EntityId.of("two-unit-paid")
        val land = EntityId.of("green-land")
        val domain = PaymentDomainV5(
            requiredCost = "{C}{C}",
            outerAtomicCostUnits = (0 until 2).map {
                AtomicManaCostUnitV1(it, 0, PaymentCostKindV1.COLORLESS, setOf(PaymentManaColor.COLORLESS))
            },
            initialPoolBuckets = listOf(
                InitialPoolBucketV1(InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.RED), 2),
            ),
            sourceActivationOptions = listOf(
                PaymentSourceActivationDomainV2(
                    sourceId = paidSource,
                    sourceName = "dto two-unit paid source",
                    manaAbilityKey = "cc",
                    productionChoices = listOf(
                        ProductionChoice(
                            PaymentManaColor.COLORLESS,
                            fixedOutputs = listOf(FixedManaOutput(0, PaymentManaColor.COLORLESS), FixedManaOutput(1, PaymentManaColor.COLORLESS)),
                        ),
                    ),
                    atomicActivationManaCostUnits = listOf(generic(0, 0), generic(0, 1)),
                    activationSupportKind = PaymentActivationSupportKindV1.FIXED_MANA_AND_TAP_SELF,
                    deterministicNonManaCosts = listOf(PaymentDeterministicNonManaCostKindV1.TAP_SELF),
                    activationCostOrderOptions = listOf(
                        listOf(ActivationCostComponentRefV1.ManaComponent, ActivationCostComponentRefV1.DeterministicNonManaComponent(0)),
                    ),
                ),
                painOption(land, PaymentManaColor.GREEN).copy(fixedSelfDamageAmount = 0),
            ),
        )
        val referenceIds = PaymentConstructionFlatReferenceEnumerator.enumerate(domain).legalPlans
            .map(PaymentConstructionFlatReferenceEnumerator::identity).toSet()
        val walk = walkGrammar(domain)
        walk.identities shouldBe referenceIds
        walk.terminalPaths shouldBe walk.identities.size
        val mixedPayments = walk.plans.count { plan ->
            val inner = plan.activations.first { it.sourceId == paidSource }.activationCostAllocation
            inner.map { it.resource::class }.toSet().size == 2
        }
        mixedPayments shouldBeGreaterThan 0
        println("two-unit inner: plans=${walk.identities.size} mixedBucketAndOutputPayments=$mixedPayments states=${walk.states}")
    }

    // ------------------------------------------------------------------------- Gate H

    test("Gate H: the grammar API takes only published data (no GameState or engine services)") {
        val forbidden = listOf("GameState", "ManaSolver", "GameEnvironment", "CardRegistry", "ObservationBuilder")
        val signatures = PaymentConstructionGrammarV1::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .flatMap { method -> method.parameterTypes.map { it.simpleName } + method.returnType.simpleName }
        signatures.filter { name -> forbidden.any { name.contains(it) } }.shouldBeEmpty()
        PaymentConstructionStateV1::class.java.declaredFields.map { it.type.simpleName }
            .filter { name -> forbidden.any { name.contains(it) } }.shouldBeEmpty()
    }

    // ------------------------------------------------------------------------- Gate F

    test("Gate F: Akiri curriculum envelope stays inside the operational ceilings") {
        envelopeReport("akiri-v0.1.txt", "Sun Titan")
    }

    test("Gate F: Chevill curriculum envelope stays inside the operational ceilings") {
        envelopeReport("chevill-v0.1.txt", "Harvester of Souls", lifeCostSourceName = "War Room")
    }
}) {
    companion object {
        // Gate E pinned exact canonical terminal-plan counts (measured, then pinned).
        const val F3_EXPECTED = 8
        const val F4_EXPECTED = 26
        const val F5_EXPECTED = 20
        const val F6_EXPECTED = 44
        const val RANDOM_CASES = 600
        const val RANDOM_CASE_MAX_CANDIDATES = 1_000_000L

        // ------------------------------------------------------------ fixture construction

        sealed interface Outer {
            data class ActivateFirstAbility(val name: String) : Outer
            data class CastFromHand(val name: String) : Outer
        }

        class Fixture(
            val label: String,
            val environment: GameEnvironment,
            val cardRegistry: CardRegistry,
            val playerId: EntityId,
            val outerOrNull: LegalAction?,
            val domainOrNull: PaymentDomainV5?,
        ) {
            val outer: LegalAction get() = outerOrNull!!
            val domain: PaymentDomainV5 get() = domainOrNull!!
        }

        fun f6Fixture(): Fixture = buildFixture(
            label = "F6",
            deck = mapOf(MindStone.name to 1, GolgariSignet.name to 1, "Forest" to 4),
            battlefield = listOf(GolgariSignet.name, "Forest", "Forest"),
            hand = listOf(MindStone.name),
            outer = Outer.CastFromHand(MindStone.name),
            extraCards = listOf(GolgariSignet),
        )

        fun baseRegistry(extra: List<CardDefinition>): CardRegistry = CardRegistry().apply {
            register(PortalSet.cards)
            register(PortalSet.basicLands)
            register(Shadowspear)
            register(MindStone)
            extra.forEach { register(it) }
        }

        fun buildFixture(
            label: String,
            deck: Map<String, Int>,
            battlefield: List<String>,
            outer: Outer,
            hand: List<String> = emptyList(),
            floatNames: List<String> = emptyList(),
            extraCards: List<CardDefinition> = emptyList(),
            requireAffordable: Boolean = true,
            requireOuter: Boolean = true,
        ): Fixture {
            val cardRegistry = baseRegistry(extraCards)
            val environment = GameEnvironment.create(cardRegistry)
            environment.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of(*deck.map { it.key to it.value }.toTypedArray())),
                        PlayerConfig("Bob", Deck.of("Mountain" to 2)),
                    ),
                    startingHandSize = 1,
                    skipMulligans = true,
                    startingPlayerIndex = 0,
                    format = Format.Standard,
                ),
            )
            advanceToMain(environment)
            val playerId = environment.playerIds.first()
            var state = environment.state
            val moved = mutableListOf<Pair<String, EntityId>>()
            fun move(name: String, zone: Zone): EntityId {
                val cardId = state.entities.entries.first { (id, container) ->
                    id in state.getZone(playerId, Zone.HAND) + state.getZone(playerId, Zone.LIBRARY) &&
                        container.get<CardComponent>()?.name == name &&
                        moved.none { it.second == id }
                }.key
                val sourceZone = state.zones.entries.first { (_, ids) -> cardId in ids }.key
                if (sourceZone != ZoneKey(playerId, zone)) {
                    state = state.moveToZone(cardId, sourceZone, ZoneKey(playerId, zone))
                }
                moved += name to cardId
                return cardId
            }
            val battlefieldIds = battlefield.map { it to move(it, Zone.BATTLEFIELD) }
            val handIds = hand.map { it to move(it, Zone.HAND) }
            environment.restore(state, environment.playerIds, environment.stepCount)

            val floated = mutableSetOf<EntityId>()
            for (name in floatNames) {
                val landId = battlefieldIds.first { (n, id) -> n == name && id !in floated }.second
                val manaAction = environment.legalActions().first { legalAction ->
                    (legalAction.action as? ActivateAbility)?.sourceId == landId && legalAction.affordable
                }
                environment.step(manaAction.action)
                floated += landId
            }

            val legalAction = when (outer) {
                is Outer.ActivateFirstAbility -> {
                    val sourceId = battlefieldIds.first { it.first == outer.name }.second
                    val abilityId = cardRegistry.requireCard(outer.name).activatedAbilities[0].id
                    environment.legalActions().singleOrNull {
                        val activate = it.action as? ActivateAbility
                        activate?.sourceId == sourceId && activate.abilityId == abilityId
                    }
                }

                is Outer.CastFromHand -> {
                    val cardId = handIds.first { it.first == outer.name }.second
                    environment.legalActions().singleOrNull { (it.action as? CastSpell)?.cardId == cardId }
                }
            }
            if (legalAction == null) {
                requireOuter shouldBe false
                println("$label: the engine menu offers no candidate for the outer action")
                return Fixture(label, environment, cardRegistry, playerId, null, null)
            }
            if (requireAffordable) legalAction.affordable shouldBe true
            val domain = ObservationBuilder(cardRegistry = cardRegistry)
                .paymentDomainV5For(environment.state, legalAction)
            domain shouldNotBe null
            println(
                "$label domain: cost=${domain!!.requiredCost} outerUnits=${domain.outerAtomicCostUnits.size} " +
                    "buckets=${domain.initialPoolBuckets.size} options=${domain.sourceActivationOptions.size} " +
                    domain.sourceActivationOptions.joinToString(prefix = "[", postfix = "]") {
                        "${it.sourceName}/${it.manaAbilityKey}:prod=${it.productionChoices.size}" +
                            ",inner=${it.atomicActivationManaCostUnits.size},dmg=${it.fixedSelfDamageAmount}"
                    },
            )
            return Fixture(label, environment, cardRegistry, playerId, legalAction, domain)
        }

        fun advanceToMain(environment: GameEnvironment) {
            while (environment.state.step != Step.PRECOMBAT_MAIN) {
                val pass = environment.legalActions().first { it.action is PassPriority }
                environment.step(pass.action)
            }
        }

        data class MultiChoice(val card: CardDefinition, val productionSizes: List<Int>)

        /**
         * F4 evidence-driven selection: the first real candidate whose ACTUALLY EMITTED V5 domain
         * shows productionChoices.size > 1. No card is fixed before the emission is measured.
         */
        fun multiChoiceCandidate(): MultiChoice {
            val candidates = listOf(CityOfBrass, BirdsOfParadise)
            for (card in candidates) {
                val domain = runCatching {
                    buildFixture(
                        label = "F4-probe(${card.name})",
                        deck = mapOf(Shadowspear.name to 1, card.name to 1, "Plains" to 3),
                        battlefield = listOf(Shadowspear.name, card.name),
                        outer = Outer.ActivateFirstAbility(Shadowspear.name),
                        extraCards = listOf(card),
                        requireAffordable = false,
                    ).domain
                }.getOrNull() ?: continue
                val sizes = domain.sourceActivationOptions.map { it.productionChoices.size }
                if (sizes.any { it > 1 }) return MultiChoice(card, sizes)
            }
            error("No candidate emitted a V5 option with productionChoices.size > 1")
        }

        // ---------------------------------------------------------------- walking the grammar

        class WalkReport(
            val plans: List<PaymentPlanV3>,
            val identities: Set<String>,
            val terminalPaths: Int,
            val states: Int,
            val maxBranching: Int,
            val maxDepth: Int,
            val maxWork: Long,
        )

        /**
         * Exhaustive walk over every reachable construction state. Asserts Gate G on every state:
         * determinism, canonical step order, injective successors, no dead ends.
         */
        fun walkGrammar(domain: PaymentDomainV5): WalkReport {
            val root = PaymentConstructionGrammarV1.initial(domain)
                .shouldBeInstanceOf<PaymentConstructionResultV1.Ok<PaymentConstructionStateV1>>().value
            val plans = mutableListOf<PaymentPlanV3>()
            var states = 0
            var maxBranching = 0
            var maxDepth = 0
            var maxWork = 0L
            fun visit(state: PaymentConstructionStateV1, depth: Int) {
                states++
                val (result, work) = PaymentConstructionGrammarV1.nextStepsMeasured(state)
                maxWork = maxOf(maxWork, work)
                val steps = result.shouldBeInstanceOf<PaymentConstructionResultV1.Ok<List<PaymentConstructionStepV1>>>().value
                PaymentConstructionGrammarV1.nextSteps(state) shouldBe result
                steps shouldBe steps.sortedWith(canonicalStepOrder)
                steps.toSet().size shouldBe steps.size
                if (PaymentConstructionGrammarV1.isTerminal(state)) {
                    steps.shouldBeEmpty()
                    maxDepth = maxOf(maxDepth, depth)
                    plans += PaymentConstructionGrammarV1.materialize(state)
                        .shouldBeInstanceOf<PaymentConstructionResultV1.Ok<PaymentPlanV3>>().value
                    return
                }
                (steps.size > 0) shouldBe true
                maxBranching = maxOf(maxBranching, steps.size)
                val successors = steps.map { step ->
                    PaymentConstructionGrammarV1.apply(state, step)
                        .shouldBeInstanceOf<PaymentConstructionResultV1.Ok<PaymentConstructionStateV1>>().value
                }
                successors.toSet().size shouldBe successors.size
                successors.forEach { visit(it, depth + 1) }
            }
            visit(root, 0)
            val identities = plans.map(PaymentConstructionFlatReferenceEnumerator::identity)
            return WalkReport(
                plans = plans,
                identities = identities.toSet(),
                terminalPaths = identities.size,
                states = states,
                maxBranching = maxBranching,
                maxDepth = maxDepth,
                maxWork = maxWork,
            )
        }

        private fun kindRank(step: PaymentConstructionStepV1): Int = when (step) {
            is PaymentConstructionStepV1.ActivateSource -> 0
            is PaymentConstructionStepV1.AllocateActivationCostUnit -> 1
            is PaymentConstructionStepV1.AllocateOuterCostUnit -> 2
            PaymentConstructionStepV1.Finalize -> 3
        }

        private fun resourceRank(resource: ManaResourceRefV1): Triple<Int, Int, Int> = when (resource) {
            is ManaResourceRefV1.InitialPoolResource -> Triple(0, 0, 0)
            is ManaResourceRefV1.ActivationOutputUnit -> Triple(1, resource.activationIndex, resource.outputIndex)
        }

        /** Independent statement of the documented canonical order (pool buckets keep DTO order). */
        val canonicalStepOrder: Comparator<PaymentConstructionStepV1> = Comparator { a, b ->
            val kind = kindRank(a).compareTo(kindRank(b))
            if (kind != 0) return@Comparator kind
            when {
                a is PaymentConstructionStepV1.ActivateSource && b is PaymentConstructionStepV1.ActivateSource ->
                    compareValuesBy(a, b, { it.sourceOptionIndex }, { it.productionChoiceIndex }, { it.activationCostOrderIndex })

                else -> {
                    val ra = resourceRank((a as? PaymentConstructionStepV1.AllocateActivationCostUnit)?.resource
                        ?: (a as PaymentConstructionStepV1.AllocateOuterCostUnit).resource)
                    val rb = resourceRank((b as? PaymentConstructionStepV1.AllocateActivationCostUnit)?.resource
                        ?: (b as PaymentConstructionStepV1.AllocateOuterCostUnit).resource)
                    compareValuesBy(ra, rb, { it.first }, { it.second }, { it.third })
                }
            }
        }

        // --------------------------------------------------------------- per-fixture proof

        class FixtureReport(
            val terminalCount: Int,
            val terminalPaths: Int,
            val surplusActivationPlans: Int,
            val innerPaidFromOutputPlans: Int,
        )

        /** Gates A, A+, C, D, E (printed), G for one real fixture. */
        fun proveFixture(fixture: Fixture): FixtureReport {
            val domain = fixture.domain
            val reference = PaymentConstructionFlatReferenceEnumerator.enumerate(domain)
            val referenceIds = reference.legalPlans.map(PaymentConstructionFlatReferenceEnumerator::identity).toSet()
            val walk = walkGrammar(domain)
            val bounds = PaymentConstructionGrammarV1.structuralBounds(domain)

            // Gate A: set equality under the frozen conservative identity.
            walk.identities shouldBe referenceIds
            // Gate G: one construction path per distinct plan.
            walk.terminalPaths shouldBe walk.identities.size
            // Gates C/D: measured maxima within the structural per-domain bounds.
            walk.maxBranching.toLong() shouldBeLessThanOrEqual bounds.alternatives
            walk.maxDepth.toLong() shouldBeLessThanOrEqual bounds.depth

            // Gate A+: Rules authority, trusted seam, and execution agree with every reachable plan.
            val validator = PaymentPlanValidator(
                ManaSolver(fixture.cardRegistry, PredicateEvaluator(fixture.cardRegistry)),
            )
            val observationBuilder = ObservationBuilder(cardRegistry = fixture.cardRegistry)
            val snapshot = fixture.environment.state
            val playerIds = fixture.environment.playerIds
            val stepCount = fixture.environment.stepCount
            for (plan in walk.plans) {
                validator.validateV3(
                    state = snapshot,
                    playerId = fixture.playerId,
                    cost = ManaCost.parse(domain.requiredCost),
                    plan = plan,
                    reservedOuterLifePayment = domain.reservedOuterLifePayment,
                ).shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()
                fixture.environment.restore(snapshot, playerIds, stepCount)
                val submitted = when (val action = fixture.outer.action) {
                    is CastSpell -> action.copy(paymentStrategy = PaymentStrategy.ExplicitV3(plan))
                    is ActivateAbility -> action.copy(paymentStrategy = PaymentStrategy.ExplicitV3(plan))
                    else -> error("unexpected outer action $action")
                }
                ActionPaymentPlanValidator.requireOrdinary(snapshot, fixture.outer, submitted, observationBuilder)
                fixture.environment.stepFromCandidateStrict(fixture.outer, submitted)
                floatingByColor(fixture.environment.state, fixture.playerId) shouldBe expectedFloating(domain, plan)
            }
            fixture.environment.restore(snapshot, playerIds, stepCount)

            val surplus = walk.plans.count { plan -> unusedOutputs(plan).isNotEmpty() }
            val innerFromOutput = walk.plans.count { plan ->
                plan.activations.any { activation ->
                    activation.activationCostAllocation.any { it.resource is ManaResourceRefV1.ActivationOutputUnit }
                }
            }
            println(
                "${fixture.label}: GateA reference=${referenceIds.size} (generated=${reference.generatedCandidates}) " +
                    "grammar=${walk.identities.size} paths=${walk.terminalPaths} states=${walk.states} " +
                    "GateC maxBranching=${walk.maxBranching}/bound=${bounds.alternatives} " +
                    "GateD maxDepth=${walk.maxDepth}/bound=${bounds.depth} maxViabilityWork=${walk.maxWork} " +
                    "authorityAccepted+executed=${walk.plans.size} surplusPlans=$surplus innerFromOutput=$innerFromOutput",
            )
            return FixtureReport(walk.identities.size, walk.terminalPaths, surplus, innerFromOutput)
        }

        private fun outputs(plan: PaymentPlanV3): List<List<PaymentManaColor>> =
            plan.activations.map { it.productionChoice.fixedOutputs?.map { o -> o.color } ?: listOf(it.productionChoice.producedColor) }

        private fun unusedOutputs(plan: PaymentPlanV3): List<PaymentManaColor> {
            val used = (plan.activations.flatMap { it.activationCostAllocation } + plan.outerAllocation)
                .mapNotNull { it.resource as? ManaResourceRefV1.ActivationOutputUnit }
                .toSet()
            return outputs(plan).flatMapIndexed { activationIndex, colors ->
                colors.filterIndexed { outputIndex, _ ->
                    ManaResourceRefV1.ActivationOutputUnit(activationIndex, outputIndex) !in used
                }
            }
        }

        private fun expectedFloating(domain: PaymentDomainV5, plan: PaymentPlanV3): Map<PaymentManaColor, Int> {
            val totals = PaymentManaColor.entries.associateWith { 0 }.toMutableMap()
            val allocations = plan.activations.flatMap { it.activationCostAllocation } + plan.outerAllocation
            for (bucket in domain.initialPoolBuckets) {
                val color = when (val key = bucket.key) {
                    is InitialPoolBucketKeyV1.UnrestrictedPoolBucket -> key.color
                    is InitialPoolBucketKeyV1.CertifiedFloatingBucket -> key.key.poolColor
                }
                val spent = allocations.count { (it.resource as? ManaResourceRefV1.InitialPoolResource)?.bucketKey == bucket.key }
                totals[color] = totals.getValue(color) + bucket.availableAmount - spent
            }
            unusedOutputs(plan).forEach { totals[it] = totals.getValue(it) + 1 }
            return totals
        }

        private fun floatingByColor(state: GameState, playerId: EntityId): Map<PaymentManaColor, Int> {
            val pool = state.getEntity(playerId)?.get<ManaPoolComponent>() ?: ManaPoolComponent()
            return mapOf(
                PaymentManaColor.WHITE to pool.white,
                PaymentManaColor.BLUE to pool.blue,
                PaymentManaColor.BLACK to pool.black,
                PaymentManaColor.RED to pool.red,
                PaymentManaColor.GREEN to pool.green,
                PaymentManaColor.COLORLESS to pool.colorless,
            )
        }

        // -------------------------------------------------------------- DTO-level helpers

        fun generic(symbolIndex: Int, unitIndex: Int) =
            AtomicManaCostUnitV1(symbolIndex, unitIndex, PaymentCostKindV1.GENERIC)

        fun painOption(sourceId: EntityId, color: PaymentManaColor) = PaymentSourceActivationDomainV2(
            sourceId = sourceId,
            sourceName = "dto pain source",
            manaAbilityKey = "pain-${color.name}",
            productionChoices = listOf(ProductionChoice(color)),
            atomicActivationManaCostUnits = emptyList(),
            activationSupportKind = PaymentActivationSupportKindV1.FIXED_MANA_AND_TAP_SELF,
            deterministicNonManaCosts = listOf(PaymentDeterministicNonManaCostKindV1.TAP_SELF),
            activationCostOrderOptions = listOf(listOf(ActivationCostComponentRefV1.DeterministicNonManaComponent(0))),
            fixedSelfDamageAmount = 1,
        )

        /** Seeded random DTO inside the V5 contract; small enough for the brute-force reference. */
        fun randomDomain(random: Random): PaymentDomainV5 {
            val colors = PaymentManaColor.entries
            fun color() = colors[random.nextInt(colors.size)]
            val buckets = colors.shuffled(random).take(random.nextInt(0, 3)).map {
                InitialPoolBucketV1(InitialPoolBucketKeyV1.UnrestrictedPoolBucket(it), random.nextInt(1, 3))
            }
            fun unit(symbolIndex: Int): AtomicManaCostUnitV1 = when (random.nextInt(6)) {
                0, 1, 2 -> AtomicManaCostUnitV1(symbolIndex, 0, PaymentCostKindV1.GENERIC)
                3 -> AtomicManaCostUnitV1(symbolIndex, 0, PaymentCostKindV1.COLORLESS, setOf(PaymentManaColor.COLORLESS))
                4 -> AtomicManaCostUnitV1(symbolIndex, 0, PaymentCostKindV1.COLORED, setOf(color()))
                else -> AtomicManaCostUnitV1(symbolIndex, 0, PaymentCostKindV1.COLORED, setOf(color(), color()))
            }
            val options = buildList {
                repeat(random.nextInt(0, 4)) { sourceIndex ->
                    val sourceId = EntityId.of("rnd-$sourceIndex")
                    repeat(if (random.nextInt(4) == 0) 2 else 1) { optionIndex ->
                        // 0, 1, or 2 inner units: two units exercise partly paid open activations.
                        val inner = when (random.nextInt(6)) {
                            0 -> listOf(unit(0))
                            1 -> listOf(unit(0), unit(1))
                            else -> emptyList()
                        }
                        val productions = if (random.nextInt(4) == 0) {
                            val bundle = listOf(color(), color())
                            listOf(
                                ProductionChoice(
                                    bundle.first(),
                                    fixedOutputs = bundle.mapIndexed { i, c -> FixedManaOutput(i, c, 1) },
                                )
                            )
                        } else {
                            colors.shuffled(random).take(random.nextInt(1, 3)).sortedBy { it.ordinal }.map(::ProductionChoice)
                        }
                        val tap = ActivationCostComponentRefV1.DeterministicNonManaComponent(0)
                        val orders = if (inner.isEmpty()) {
                            listOf(listOf(tap))
                        } else if (random.nextInt(4) == 0) {
                            listOf(listOf(ActivationCostComponentRefV1.ManaComponent, tap), listOf(tap, ActivationCostComponentRefV1.ManaComponent))
                        } else {
                            listOf(listOf(ActivationCostComponentRefV1.ManaComponent, tap))
                        }
                        add(
                            PaymentSourceActivationDomainV2(
                                sourceId = sourceId,
                                sourceName = "rnd",
                                manaAbilityKey = "k$optionIndex",
                                productionChoices = productions,
                                atomicActivationManaCostUnits = inner,
                                activationSupportKind = PaymentActivationSupportKindV1.FIXED_MANA_AND_TAP_SELF,
                                deterministicNonManaCosts = listOf(PaymentDeterministicNonManaCostKindV1.TAP_SELF),
                                activationCostOrderOptions = orders,
                                fixedSelfDamageAmount = if (random.nextInt(4) == 0) 1 else 0,
                            )
                        )
                    }
                }
            }
            val outer = (0 until random.nextInt(0, 4)).map(::unit)
            val budget = if (random.nextInt(3) == 0) random.nextInt(0, 2) else null
            return PaymentDomainV5(
                requiredCost = "{dto}",
                outerAtomicCostUnits = outer,
                initialPoolBuckets = canonicalizeInitialPoolBucketsV1(buckets),
                sourceActivationOptions = options,
                reservedOuterLifePayment = if (budget != null) 1 else 0,
                fixedSelfDamageBudget = budget,
            )
        }

        // --------------------------------------------------------------- Gate F envelope

        /**
         * Envelope measurement for one locked curriculum deck: every mana-producing card of the
         * deck is put onto the battlefield untapped and without summoning sickness at once (the
         * structural bounds are additive per source, so this is the maximum over every subset of
         * the deck's sources), plus headroom for floating pool buckets and the largest outer cost
         * the deck can present. Sources whose presence makes V5 refuse the domain are recorded;
         * they can never reach the grammar.
         */
        fun envelopeReport(deckFile: String, outerCardName: String, lifeCostSourceName: String? = null) {
            val deck = readLockedDeck(deckFile)
            val cardRegistry = CardRegistry().apply {
                MtgSetCatalog.all.forEach { set ->
                    register(set.cards)
                    register(set.basicLands)
                }
            }
            val environment = GameEnvironment.create(cardRegistry)
            environment.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig(
                            "Alice",
                            Deck.of(*deck.cards.drop(1).groupingBy { it }.eachCount().map { it.key to it.value }.toTypedArray()),
                            startingLife = 40,
                            commanderCardName = deck.commander,
                        ),
                        PlayerConfig(
                            "Bob",
                            Deck.of(*deck.cards.drop(1).groupingBy { it }.eachCount().map { it.key to it.value }.toTypedArray()),
                            startingLife = 40,
                            commanderCardName = deck.commander,
                        ),
                    ),
                    startingHandSize = 7,
                    skipMulligans = true,
                    startingPlayerIndex = 0,
                    format = Format.Commander(),
                ),
            )
            advanceToMain(environment)
            val playerId = environment.playerIds.first()
            var state = environment.state
            fun ownedIn(zone: Zone) = state.getZone(playerId, zone)
            fun nameOf(id: EntityId) = state.getEntity(id)?.get<CardComponent>()?.name

            val outerId = (ownedIn(Zone.HAND) + ownedIn(Zone.LIBRARY)).first { nameOf(it) == outerCardName }
            val outerZone = state.zones.entries.first { (_, ids) -> outerId in ids }.key
            state = state.moveToZone(outerId, outerZone, ZoneKey(playerId, Zone.HAND))
            val builder = ObservationBuilder(cardRegistry = cardRegistry)

            val manaCards = (ownedIn(Zone.HAND) + ownedIn(Zone.LIBRARY)).filter { id ->
                val definition = nameOf(id)?.let(cardRegistry::getCard) ?: return@filter false
                id != outerId && (definition.typeLine.isLand || definition.activatedAbilities.any { it.isManaAbility })
            }
            fun withSource(base: GameState, id: EntityId): GameState {
                val from = base.zones.entries.first { (_, ids) -> id in ids }.key
                return base.moveToZone(id, from, ZoneKey(playerId, Zone.BATTLEFIELD))
                    .updateEntity(id) { it.without<SummoningSicknessComponent>() }
            }
            // The outer candidate is taken from the menu with every mana source present (an
            // unaffordable paid action is never offered); the action value itself is state-free.
            environment.restore(manaCards.fold(state, ::withSource), environment.playerIds, environment.stepCount)
            val outerAction = environment.legalActions().single { (it.action as? CastSpell)?.cardId == outerId }
            val refused = mutableListOf<String>()
            var published = 0
            for (id in manaCards) {
                val candidate = withSource(state, id)
                if (builder.paymentDomainV5For(candidate, outerAction) != null) {
                    state = candidate
                    published++
                } else {
                    refused += nameOf(id) ?: id.value
                }
            }
            environment.restore(state, environment.playerIds, environment.stepCount)
            val domain = builder.paymentDomainV5For(state, outerAction)!!
            val bounds = PaymentConstructionGrammarV1.structuralBounds(domain)

            val colorsBySource = domain.sourceActivationOptions.groupBy { it.sourceId }.mapValues { (_, options) ->
                options.flatMap { option ->
                    option.productionChoices.flatMap { it.fixedOutputs?.map { o -> o.color } ?: listOf(it.producedColor) }
                }.toSet().size
            }
            val poolHeadroom = maxOf(PaymentManaColor.entries.size, colorsBySource.values.sum())
            val maxOuterUnits = deck.cards.distinct().maxOf { name -> maxFixedOuterUnits(cardRegistry.requireCard(name)) }
            val envelopeAlternatives = bounds.alternatives + poolHeadroom
            val envelopeDepthWithoutTax = bounds.depth - domain.outerAtomicCostUnits.size + maxOuterUnits
            val commanderTaxHeadroom = (MAX_PAYMENT_CONSTRUCTION_DEPTH - envelopeDepthWithoutTax) / 2

            // Measured behaviour on the envelope itself: root plus seeded random construction walks.
            val random = Random(deckFile.hashCode())
            var maxBranching = 0
            var maxDepth = 0
            var maxWork = 0L
            val validator = PaymentPlanValidator(ManaSolver(cardRegistry, PredicateEvaluator(cardRegistry)))
            val root = PaymentConstructionGrammarV1.initial(domain)
                .shouldBeInstanceOf<PaymentConstructionResultV1.Ok<PaymentConstructionStateV1>>().value
            repeat(ENVELOPE_WALKS) {
                var current = root
                var depth = 0
                while (!PaymentConstructionGrammarV1.isTerminal(current)) {
                    val (result, work) = PaymentConstructionGrammarV1.nextStepsMeasured(current)
                    val steps = result.shouldBeInstanceOf<PaymentConstructionResultV1.Ok<List<PaymentConstructionStepV1>>>().value
                    maxWork = maxOf(maxWork, work)
                    maxBranching = maxOf(maxBranching, steps.size)
                    current = PaymentConstructionGrammarV1.apply(current, steps[random.nextInt(steps.size)])
                        .shouldBeInstanceOf<PaymentConstructionResultV1.Ok<PaymentConstructionStateV1>>().value
                    depth++
                }
                maxDepth = maxOf(maxDepth, depth)
                val plan = PaymentConstructionGrammarV1.materialize(current)
                    .shouldBeInstanceOf<PaymentConstructionResultV1.Ok<PaymentPlanV3>>().value
                validator.validateV3(
                    state = state,
                    playerId = playerId,
                    cost = ManaCost.parse(domain.requiredCost),
                    plan = plan,
                ).shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()
            }

            // Adversarial viability sweep on the real envelope sources: every outer demand size from
            // 1 to one past the cost-free output total, as all-generic, single-colour (each colour the
            // deck produces), and half-colour/half-generic demands. Demands just above the true
            // supply pass the cost-free relaxation (a paid source's inner cost is ignored there) and
            // force the exhaustive search — the worst case of the viability check.
            val relaxedTotal = domain.sourceActivationOptions.groupBy { it.sourceId }.values.sumOf { options ->
                options.maxOf { option ->
                    option.productionChoices.maxOf { it.fixedOutputs?.size ?: 1 }
                }
            }
            val deckColors = domain.sourceActivationOptions
                .flatMap { option -> option.productionChoices.flatMap { it.fixedOutputs?.map { o -> o.color } ?: listOf(it.producedColor) } }
                .toSet()
            var sweepMaxWork = 0L
            var sweepCases = 0
            var sweepPayable = 0
            for (size in 1..relaxedTotal + 1) {
                val shapes = buildList<List<AtomicManaCostUnitV1>> {
                    add((0 until size).map { generic(0, it) })
                    deckColors.forEach { color ->
                        add((0 until size).map { AtomicManaCostUnitV1(it, 0, PaymentCostKindV1.COLORED, setOf(color)) })
                        add((0 until size).map { index ->
                            if (index < size / 2) {
                                AtomicManaCostUnitV1(index, 0, PaymentCostKindV1.COLORED, setOf(color))
                            } else {
                                generic(size, index)
                            }
                        })
                    }
                }
                for (units in shapes) {
                    // null = the envelope's own (unbounded) budget; 1 and 3 make every pain source a
                    // budget-relevant ("complex") source for the viability search.
                    for (budget in listOf<Int?>(null, 1, 3)) {
                        val derived = domain.copy(
                            requiredCost = "{sweep}",
                            outerAtomicCostUnits = units,
                            reservedOuterLifePayment = if (budget == null) 0 else 1,
                            fixedSelfDamageBudget = budget,
                        )
                        val (result, work) = PaymentConstructionGrammarV1.nextStepsMeasured(PaymentConstructionStateV1(derived))
                        when (result) {
                            is PaymentConstructionResultV1.Ok -> sweepPayable++
                            is PaymentConstructionResultV1.Failed ->
                                result.failure.shouldBeInstanceOf<PaymentConstructionFailureV1.NoLegalPaymentV1>()
                        }
                        sweepMaxWork = maxOf(sweepMaxWork, work)
                        sweepCases++
                    }
                }
            }
            maxWork = maxOf(maxWork, sweepMaxWork)

            // A REAL budget-bound domain when the deck has an outer PayLife action (War Room: "{3},
            // {T}, Pay life equal to the number of colours in your commander's identity"). Its
            // positive reservation makes fixedSelfDamageBudget non-null, so every pain source is
            // budget-relevant for the viability search.
            if (lifeCostSourceName != null) {
                val lifeSourceId = state.getBattlefield(playerId).first { nameOf(it) == lifeCostSourceName }
                val lifeAction = environment.legalActions().single {
                    val activate = it.action as? ActivateAbility
                    activate?.sourceId == lifeSourceId &&
                        cardRegistry.requireCard(lifeCostSourceName).activatedAbilities
                            .single { ability -> ability.id == activate.abilityId }.isManaAbility.not()
                }
                val lifeDomain = builder.paymentDomainV5For(state, lifeAction)!!
                lifeDomain.reservedOuterLifePayment shouldBeGreaterThan 0
                lifeDomain.fixedSelfDamageBudget shouldNotBe null
                var lifeMaxWork = 0L
                var lifeMaxBranching = 0
                val lifeRoot = PaymentConstructionGrammarV1.initial(lifeDomain)
                    .shouldBeInstanceOf<PaymentConstructionResultV1.Ok<PaymentConstructionStateV1>>().value
                repeat(ENVELOPE_WALKS) {
                    var current = lifeRoot
                    while (!PaymentConstructionGrammarV1.isTerminal(current)) {
                        val (result, work) = PaymentConstructionGrammarV1.nextStepsMeasured(current)
                        val steps = result.shouldBeInstanceOf<PaymentConstructionResultV1.Ok<List<PaymentConstructionStepV1>>>().value
                        lifeMaxWork = maxOf(lifeMaxWork, work)
                        lifeMaxBranching = maxOf(lifeMaxBranching, steps.size)
                        current = PaymentConstructionGrammarV1.apply(current, steps[random.nextInt(steps.size)])
                            .shouldBeInstanceOf<PaymentConstructionResultV1.Ok<PaymentConstructionStateV1>>().value
                    }
                    val plan = PaymentConstructionGrammarV1.materialize(current)
                        .shouldBeInstanceOf<PaymentConstructionResultV1.Ok<PaymentPlanV3>>().value
                    validator.validateV3(
                        state = state,
                        playerId = playerId,
                        cost = ManaCost.parse(lifeDomain.requiredCost),
                        plan = plan,
                        reservedOuterLifePayment = lifeDomain.reservedOuterLifePayment,
                    ).shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()
                }
                maxWork = maxOf(maxWork, lifeMaxWork)
                println(
                    "GateF $deckFile real PayLife outer ($lifeCostSourceName): cost=${lifeDomain.requiredCost} " +
                        "reservedOuterLifePayment=${lifeDomain.reservedOuterLifePayment} " +
                        "fixedSelfDamageBudget=${lifeDomain.fixedSelfDamageBudget} options=${lifeDomain.sourceActivationOptions.size} " +
                        "walks=$ENVELOPE_WALKS maxBranching=$lifeMaxBranching maxViabilityWork=$lifeMaxWork (all AcceptedV3)",
                )
            }

            val bySource = domain.sourceActivationOptions.groupBy { it.sourceId }.values
            val paidSources = bySource.count { options -> options.any { it.atomicActivationManaCostUnits.isNotEmpty() } }
            val painSources = bySource.count { options -> options.any { it.fixedSelfDamageAmount > 0 } }
            println(
                "GateF $deckFile sources: paid(inner cost)=$paidSources pain(fixed self-damage)=$painSources " +
                    "=> complex sources without budget <= $paidSources, with budget <= ${paidSources + painSources}",
            )
            println(
                "GateF $deckFile sweep: relaxedOutputTotal=$relaxedTotal colours=$deckColors cases=$sweepCases " +
                    "payable=$sweepPayable maxViabilityWork=$sweepMaxWork",
            )
            println(
                "GateF $deckFile: manaCards=${manaCards.size} published=$published refused=$refused " +
                    "options=${domain.sourceActivationOptions.size} sources=${colorsBySource.size} " +
                    "outer=${domain.requiredCost} structural(alternatives=${bounds.alternatives}, depth=${bounds.depth}) " +
                    "poolHeadroom=$poolHeadroom maxDeckOuterUnits=$maxOuterUnits " +
                    "ENVELOPE alternatives<=$envelopeAlternatives depth<=$envelopeDepthWithoutTax(+2/commander-tax-step) " +
                    "commanderTaxHeadroom=$commanderTaxHeadroom " +
                    "walks=$ENVELOPE_WALKS measured(maxBranching=$maxBranching, maxDepth=$maxDepth, maxViabilityWork=$maxWork) " +
                    "ceilings(alternatives=$MAX_PAYMENT_CONSTRUCTION_ALTERNATIVES, depth=$MAX_PAYMENT_CONSTRUCTION_DEPTH, " +
                    "work=$MAX_PAYMENT_CONSTRUCTION_VIABILITY_WORK)",
            )
            envelopeAlternatives shouldBeLessThanOrEqual MAX_PAYMENT_CONSTRUCTION_ALTERNATIVES.toLong()
            envelopeDepthWithoutTax shouldBeLessThanOrEqual MAX_PAYMENT_CONSTRUCTION_DEPTH.toLong()
            (commanderTaxHeadroom >= MIN_COMMANDER_TAX_HEADROOM) shouldBe true
            maxWork * VIABILITY_WORK_MARGIN shouldBeLessThanOrEqual MAX_PAYMENT_CONSTRUCTION_VIABILITY_WORK
        }

        const val ENVELOPE_WALKS = 40
        const val MIN_COMMANDER_TAX_HEADROOM = 20
        const val VIABILITY_WORK_MARGIN = 100L

        /** Largest fixed ordinary cost the card can present: its mana cost or an activated ability's. */
        private fun maxFixedOuterUnits(card: CardDefinition): Int {
            val costs = buildList {
                add(card.manaCost)
                card.activatedAbilities.forEach { ability -> addAll(manaCostsOf(ability.cost)) }
            }
            return costs.maxOf { it.toAtomicDomain()?.size ?: 0 }
        }

        private fun manaCostsOf(cost: AbilityCost): List<ManaCost> = when (cost) {
            is AbilityCost.Composite -> cost.costs.flatMap(::manaCostsOf)
            is AbilityCost.Atom -> (cost.atom as? CostAtom.Mana)?.let { listOf(it.cost) } ?: emptyList()
            else -> emptyList()
        }

        private data class LockedDeck(val commander: String, val cards: List<String>)

        private fun readLockedDeck(fileName: String): LockedDeck {
            val root = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
                .first { Files.isDirectory(it.resolve("docs").resolve("ml").resolve("curriculum")) }
            val cards = Files.readAllLines(root.resolve("docs/ml/curriculum").resolve(fileName))
                .filter { it.matches(Regex("^\\d{3}\\t.*")) }
                .map { it.substringAfterLast('\t') }
            return LockedDeck(cards.first(), cards)
        }
    }
}
