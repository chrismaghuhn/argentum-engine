package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.FixedManaOutput
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.LifeChangeReason
import com.wingedsheep.engine.core.LifeChangedEvent
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.engine.core.ManaSpentEvent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.handlers.CostHandler
import com.wingedsheep.engine.handlers.actions.spell.CastPaymentProcessor
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.battlefield.ReplacementEffectSourceComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.mtg.sets.definitions.apc.cards.LlanowarWastes
import com.wingedsheep.mtg.sets.definitions.rav.cards.GolgariSignet
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ActivationRestriction
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.PlayersCantActivateAbilities
import com.wingedsheep.sdk.scripting.PreventDamage
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import com.wingedsheep.sdk.scripting.ReduceActivatedAbilityCost
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/** RED/contract coverage for the V3 ordered program's single global resource ledger. */
class PaymentPlanV3ValidatorTest : FunSpec({

    /** Rules-owned cost modifier used only to prove effective-cost re-resolution on a stale plan. */
    val activationCostModifier = card("PAY106 Activated Cost Modifier") {
        typeLine = "Enchantment"
        staticAbility {
            ability = ReduceActivatedAbilityCost(
                filter = GroupFilter(GameObjectFilter.Artifact.youControl()),
                amount = DynamicAmount.Fixed(1),
            )
        }
    }

    /** A free mana ability that is legal only while an untapped Forest remains. */
    val sequenceGuardedManaSource = card("PAY106 Sequence Guarded Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(com.wingedsheep.sdk.core.Color.BLACK)
            manaAbility = true
            restrictions = listOf(
                ActivationRestriction.OnlyIfCondition(
                    Conditions.YouControl(GameObjectFilter.Land.untapped())
                )
            )
        }
    }

    /** A mana ability whose generic cost is reduced only while an untapped Forest exists. */
    val sequenceCostChangingManaSource = card("PAY106 Sequence Cost Changing Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Composite(Costs.Mana("{1}"), Costs.Tap)
            effect = Effects.AddMana(com.wingedsheep.sdk.core.Color.BLACK)
            manaAbility = true
            genericCostReduction = DynamicAmount.Conditional(
                condition = Conditions.YouControl(GameObjectFilter.Land.untapped()),
                ifTrue = DynamicAmount.Fixed(1),
                ifFalse = DynamicAmount.Fixed(0),
            )
        }
    }

    val permissionTargetManaSource = card("PAY106 Permission Target Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(com.wingedsheep.sdk.core.Color.BLACK)
            manaAbility = true
        }
    }

    /**
     * Its own mana ability is ordinary, but tapping this source turns on an external permission
     * lock for the other source. The V5 stability certificate must reject the whole slice before
     * an ordered A -> B program can mutate A and bypass B's authoritative activation check.
     */
    val permissionGuardingManaSource = card("PAY106 Permission Guarding Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(com.wingedsheep.sdk.core.Color.GREEN)
            manaAbility = true
        }
        staticAbility {
            ability = PlayersCantActivateAbilities(
                affected = Player.You,
                permanentFilter = GameObjectFilter.Artifact.named(permissionTargetManaSource.name),
                condition = Conditions.EntityMatches(
                    EffectTarget.Self,
                    GameObjectFilter.Any.tapped(),
                ),
            )
        }
    }

    /**
     * Initially legal, but no longer legal after a painful mana ability records life loss. This
     * models a later-node rule fact that is not an ability-local cost/restriction modifier.
     */
    val lifeHistoryGuardedManaSource = card("PAY106 Life History Guarded Mana Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.BLACK)
            manaAbility = true
            restrictions = listOf(
                ActivationRestriction.OnlyIfCondition(
                    Conditions.Not(Conditions.YouLostLifeThisTurn),
                )
            )
        }
    }

    val executorSpell = card("PAY106 Executor Spell") {
        manaCost = "{1}{B}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    data class SignetFixture(
        val driver: GameTestDriver,
        val player: EntityId,
        val forestId: EntityId,
        val signetId: EntityId,
        val forestKey: String,
        val signetKey: String,
        val signetOutputs: List<FixedManaOutput>,
    )

    data class PermissionFixture(
        val driver: GameTestDriver,
        val player: EntityId,
        val guardingSourceId: EntityId,
        val targetSourceId: EntityId,
        val guardingKey: String,
        val targetKey: String,
    )

    data class PainFixture(
        val driver: GameTestDriver,
        val player: EntityId,
        val sourceId: EntityId,
        val greenKey: String,
        val colorlessKey: String,
    )

    data class PainSequenceFixture(
        val driver: GameTestDriver,
        val player: EntityId,
        val painSourceId: EntityId,
        val painKey: String,
        val guardedSourceId: EntityId,
        val guardedKey: String,
    )

    fun signetFixture(forestCount: Int = 1, includePool: ManaPoolComponent? = null): SignetFixture {
        val driver = GameTestDriver()
        driver.registerCards(
            TestCards.all + GolgariSignet + activationCostModifier +
                sequenceGuardedManaSource + sequenceCostChangingManaSource + executorSpell
        )
        driver.initMirrorMatch(Deck.of("Forest" to 40), startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val player = driver.activePlayer!!
        val forests = (0 until forestCount).map { driver.putPermanentOnBattlefield(player, "Forest") }
        val signetId = driver.putPermanentOnBattlefield(player, GolgariSignet.name)
        if (includePool != null) driver.addComponent(player, includePool)

        val solver = ManaSolver(driver.cardRegistry)
        val sources = solver.findAvailableManaSources(
            state = driver.state,
            playerId = player,
            spellContext = null,
            paymentOrderRequired = true,
        )
        val forestSource = sources.single { it.entityId == forests.first() }
        val signetSource = sources.single { it.entityId == signetId }
        val bundle = signetSource.paymentManaProductionProfiles.values
            .single { it is PaymentManaProductionProfile.FixedOutputBundle }
                as PaymentManaProductionProfile.FixedOutputBundle

        return SignetFixture(
            driver = driver,
            player = player,
            forestId = forests.first(),
            signetId = signetId,
            forestKey = forestSource.paymentManaAbilityOrder.single(),
            signetKey = signetSource.paymentManaAbilityOrder.single(),
            signetOutputs = bundle.outputs.mapIndexed { index, output ->
                FixedManaOutput(index, output.color)
            },
        )
    }

    fun permissionFixture(): PermissionFixture {
        val driver = GameTestDriver()
        driver.registerCards(
            TestCards.all + permissionGuardingManaSource + permissionTargetManaSource
        )
        driver.initMirrorMatch(Deck.of("Forest" to 40), startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val player = driver.activePlayer!!
        val guardingSourceId = driver.putPermanentOnBattlefield(
            player,
            permissionGuardingManaSource.name,
        )
        val targetSourceId = driver.putPermanentOnBattlefield(
            player,
            permissionTargetManaSource.name,
        )
        val sources = ManaSolver(driver.cardRegistry).findAvailableManaSources(
            state = driver.state,
            playerId = player,
            spellContext = null,
            paymentOrderRequired = true,
        )
        return PermissionFixture(
            driver = driver,
            player = player,
            guardingSourceId = guardingSourceId,
            targetSourceId = targetSourceId,
            guardingKey = sources.single { it.entityId == guardingSourceId }
                .paymentManaAbilityOrder.single(),
            targetKey = sources.single { it.entityId == targetSourceId }
                .paymentManaAbilityOrder.single(),
        )
    }

    fun painFixture(): PainFixture {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + LlanowarWastes)
        driver.initMirrorMatch(Deck.of("Forest" to 40), startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val player = driver.activePlayer!!
        val sourceId = driver.putPermanentOnBattlefield(player, LlanowarWastes.name)
        val source = ManaSolver(driver.cardRegistry)
            .findAvailableManaSources(
                state = driver.state,
                playerId = player,
                spellContext = null,
                paymentOrderRequired = true,
            )
            .single { it.entityId == sourceId }
        return PainFixture(
            driver = driver,
            player = player,
            sourceId = sourceId,
            greenKey = source.manaAbilityOptionsFor(Color.GREEN).single().let(ManaAbilityIdentity::key),
            colorlessKey = source.manaAbilityOptionsFor(null).single().let(ManaAbilityIdentity::key),
        )
    }

    fun painSequenceFixture(): PainSequenceFixture {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + LlanowarWastes + lifeHistoryGuardedManaSource)
        driver.initMirrorMatch(Deck.of("Forest" to 40), startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val player = driver.activePlayer!!
        val painSourceId = driver.putPermanentOnBattlefield(player, LlanowarWastes.name)
        val guardedSourceId = driver.putPermanentOnBattlefield(player, lifeHistoryGuardedManaSource.name)
        val sources = ManaSolver(driver.cardRegistry).findAvailableManaSources(
            state = driver.state,
            playerId = player,
            spellContext = null,
            paymentOrderRequired = true,
        )
        val painSource = sources.single { it.entityId == painSourceId }
        val guardedSource = sources.single { it.entityId == guardedSourceId }
        return PainSequenceFixture(
            driver = driver,
            player = player,
            painSourceId = painSourceId,
            painKey = painSource.manaAbilityOptionsFor(Color.GREEN).single().let(ManaAbilityIdentity::key),
            guardedSourceId = guardedSourceId,
            guardedKey = guardedSource.paymentManaAbilityOrder.single(),
        )
    }

    fun singlePainPlan(
        fixture: PainFixture,
        manaAbilityKey: String,
        producedColor: PaymentManaColor,
    ): PaymentPlanV3 = PaymentPlanV3(
        activations = listOf(
            SourceActivationV2(
                sourceId = fixture.sourceId,
                manaAbilityKey = manaAbilityKey,
                productionChoice = ProductionChoice(producedColor),
                activationCostOrder = listOf(
                    ActivationCostComponentRefV1.DeterministicNonManaComponent(0),
                ),
            ),
        ),
        outerAllocation = listOf(
            PaymentAllocationV1(
                target = PaymentTargetV1.OuterCostUnit(0, 0),
                resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
            ),
        ),
    )

    fun painSequencePlan(fixture: PainSequenceFixture): PaymentPlanV3 = PaymentPlanV3(
        activations = listOf(
            SourceActivationV2(
                sourceId = fixture.painSourceId,
                manaAbilityKey = fixture.painKey,
                productionChoice = ProductionChoice(PaymentManaColor.GREEN),
                activationCostOrder = listOf(
                    ActivationCostComponentRefV1.DeterministicNonManaComponent(0),
                ),
            ),
            SourceActivationV2(
                sourceId = fixture.guardedSourceId,
                manaAbilityKey = fixture.guardedKey,
                productionChoice = ProductionChoice(PaymentManaColor.BLACK),
                activationCostOrder = listOf(
                    ActivationCostComponentRefV1.DeterministicNonManaComponent(0),
                ),
            ),
        ),
        outerAllocation = listOf(
            PaymentAllocationV1(
                target = PaymentTargetV1.OuterCostUnit(0, 0),
                resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
            ),
            PaymentAllocationV1(
                target = PaymentTargetV1.OuterCostUnit(1, 0),
                resource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
            ),
        ),
    )

    fun permissionPlan(fixture: PermissionFixture): PaymentPlanV3 = PaymentPlanV3(
        activations = listOf(
            SourceActivationV2(
                sourceId = fixture.guardingSourceId,
                manaAbilityKey = fixture.guardingKey,
                productionChoice = ProductionChoice(PaymentManaColor.GREEN),
                activationCostOrder = listOf(
                    ActivationCostComponentRefV1.DeterministicNonManaComponent(0),
                ),
            ),
            SourceActivationV2(
                sourceId = fixture.targetSourceId,
                manaAbilityKey = fixture.targetKey,
                productionChoice = ProductionChoice(PaymentManaColor.BLACK),
                activationCostOrder = listOf(
                    ActivationCostComponentRefV1.DeterministicNonManaComponent(0),
                ),
            ),
        ),
        outerAllocation = listOf(
            PaymentAllocationV1(
                target = PaymentTargetV1.OuterCostUnit(0, 0),
                resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
            ),
            PaymentAllocationV1(
                target = PaymentTargetV1.OuterCostUnit(1, 0),
                resource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
            ),
        ),
    )

    fun forestActivation(fixture: SignetFixture): SourceActivationV2 =
        SourceActivationV2(
            sourceId = fixture.forestId,
            manaAbilityKey = fixture.forestKey,
            productionChoice = ProductionChoice(PaymentManaColor.GREEN),
            activationCostOrder = listOf(ActivationCostComponentRefV1.DeterministicNonManaComponent(0)),
        )

    fun signetActivation(
        fixture: SignetFixture,
        activationIndex: Int = 1,
        paymentResource: ManaResourceRefV1 = ManaResourceRefV1.ActivationOutputUnit(0, 0),
    ): SourceActivationV2 = SourceActivationV2(
        sourceId = fixture.signetId,
        manaAbilityKey = fixture.signetKey,
        productionChoice = ProductionChoice(
            producedColor = PaymentManaColor.BLACK,
            fixedOutputs = fixture.signetOutputs,
        ),
        activationCostOrder = listOf(
            ActivationCostComponentRefV1.ManaComponent,
            ActivationCostComponentRefV1.DeterministicNonManaComponent(0),
        ),
        activationCostAllocation = listOf(
            PaymentAllocationV1(
                target = PaymentTargetV1.ActivationCostUnit(activationIndex, 0, 0),
                resource = paymentResource,
            ),
        ),
    )

    fun validPlan(fixture: SignetFixture): PaymentPlanV3 = PaymentPlanV3(
        activations = listOf(
            forestActivation(fixture),
            signetActivation(fixture),
        ),
        outerAllocation = listOf(
            PaymentAllocationV1(
                target = PaymentTargetV1.OuterCostUnit(0, 0),
                resource = ManaResourceRefV1.ActivationOutputUnit(1, 1),
            ),
            PaymentAllocationV1(
                target = PaymentTargetV1.OuterCostUnit(1, 0),
                resource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
            ),
        ),
    )

    fun validate(
        fixture: SignetFixture,
        cost: ManaCost = ManaCost.parse("{1}{B}"),
        plan: PaymentPlanV3 = validPlan(fixture),
    ): PaymentPlanValidation = PaymentPlanValidator(ManaSolver(fixture.driver.cardRegistry)).validateV3(
        state = fixture.driver.state,
        playerId = fixture.player,
        cost = cost,
        plan = plan,
        spellContext = SpellPaymentContext(),
    )

    test("PAY106-03: Forest output pays Signet activation and Signet output pays outer cost") {
        val fixture = signetFixture()

        val accepted = validate(fixture).shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()

        accepted.program.activations.map { it.source.entityId } shouldBe
            listOf(fixture.forestId, fixture.signetId)
        accepted.program.allocations.size shouldBe 3
    }

    test("PAY106-EXECUTOR-01: ExplicitV3 executes the validated ordered program") {
        val fixture = signetFixture()
        val services = EngineServices(fixture.driver.cardRegistry)
        val processor = CastPaymentProcessor(
            manaSolver = services.manaSolver,
            costHandler = CostHandler(fixture.driver.cardRegistry),
            manaAbilitySideEffectExecutor = services.manaAbilitySideEffectExecutor,
        )
        val action = CastSpell(
            playerId = fixture.player,
            cardId = fixture.signetId,
            paymentStrategy = PaymentStrategy.ExplicitV3(validPlan(fixture)),
        )
        val before = fixture.driver.state

        val result = processor.processPayment(
            state = before,
            action = action,
            effectiveCost = ManaCost.parse("{1}{B}"),
            cardName = "PAY106 Executor Test",
            xValue = 0,
            spellContext = SpellPaymentContext(),
        )

        result.error shouldBe null
        result.state.getEntity(fixture.forestId)?.has<TappedComponent>() shouldBe true
        result.state.getEntity(fixture.signetId)?.has<TappedComponent>() shouldBe true
        result.state.getEntity(fixture.player)?.get<ManaPoolComponent>()?.unrestrictedTotal shouldBe 0
    }

    test("PAY106-EXECUTOR-02: unconsumed ordered output is published only after its source succeeds") {
        val fixture = signetFixture()
        val services = EngineServices(fixture.driver.cardRegistry)
        val processor = CastPaymentProcessor(
            manaSolver = services.manaSolver,
            costHandler = CostHandler(fixture.driver.cardRegistry),
            manaAbilitySideEffectExecutor = services.manaAbilitySideEffectExecutor,
        )
        val plan = validPlan(fixture).copy(
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
                ),
            ),
        )
        val result = processor.processPayment(
            state = fixture.driver.state,
            action = CastSpell(
                playerId = fixture.player,
                cardId = fixture.signetId,
                paymentStrategy = PaymentStrategy.ExplicitV3(plan),
            ),
            effectiveCost = ManaCost.parse("{B}"),
            cardName = "PAY106 Executor Output Test",
            xValue = 0,
            spellContext = SpellPaymentContext(),
        )

        result.error shouldBe null
        val pool = result.state.getEntity(fixture.player)?.get<ManaPoolComponent>()
        pool?.black shouldBe 0
        pool?.green shouldBe 1
        pool?.manaBySource?.get(fixture.signetId) shouldBe 1
        result.events.filterIsInstance<ManaSpentEvent>().single().black shouldBe 1
    }

    test("PAY106-EXECUTOR-03: an initial pool unit can fund an inner activation on the same ledger") {
        val fixture = signetFixture(includePool = ManaPoolComponent(green = 1))
        val services = EngineServices(fixture.driver.cardRegistry)
        val processor = CastPaymentProcessor(
            manaSolver = services.manaSolver,
            costHandler = CostHandler(fixture.driver.cardRegistry),
            manaAbilitySideEffectExecutor = services.manaAbilitySideEffectExecutor,
        )
        val green = ManaResourceRefV1.InitialPoolResource(
            InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.GREEN),
        )
        val plan = PaymentPlanV3(
            activations = listOf(
                signetActivation(
                    fixture = fixture,
                    activationIndex = 0,
                    paymentResource = green,
                ),
            ),
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
                ),
            ),
        )
        val result = processor.processPayment(
            state = fixture.driver.state,
            action = CastSpell(
                playerId = fixture.player,
                cardId = fixture.signetId,
                paymentStrategy = PaymentStrategy.ExplicitV3(plan),
            ),
            effectiveCost = ManaCost.parse("{B}"),
            cardName = "PAY106 Executor Shared Ledger Test",
            xValue = 0,
            spellContext = SpellPaymentContext(),
        )

        result.error shouldBe null
        result.state.getEntity(fixture.signetId)?.has<TappedComponent>() shouldBe true
        result.state.getEntity(fixture.player)?.get<ManaPoolComponent>()?.green shouldBe 1
        result.state.getEntity(fixture.player)?.get<ManaPoolComponent>()?.black shouldBe 0
    }

    test("PAY106-EXECUTOR-04: a rejected V3 program returns the untouched state and no events") {
        val fixture = signetFixture()
        val services = EngineServices(fixture.driver.cardRegistry)
        val processor = CastPaymentProcessor(
            manaSolver = services.manaSolver,
            costHandler = CostHandler(fixture.driver.cardRegistry),
            manaAbilitySideEffectExecutor = services.manaAbilitySideEffectExecutor,
        )
        val invalidPlan = validPlan(fixture).copy(
            activations = listOf(
                forestActivation(fixture),
                signetActivation(
                    fixture = fixture,
                    paymentResource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
                ),
            ),
        )
        val before = fixture.driver.state

        val result = processor.processPayment(
            state = before,
            action = CastSpell(
                playerId = fixture.player,
                cardId = fixture.signetId,
                paymentStrategy = PaymentStrategy.ExplicitV3(invalidPlan),
            ),
            effectiveCost = ManaCost.parse("{1}{B}"),
            cardName = "PAY106 Executor Rejection Test",
            xValue = 0,
            spellContext = SpellPaymentContext(),
        )

        result.error shouldNotBe null
        result.state shouldBe before
        result.events shouldBe emptyList()
    }

    test("PAY106-EXECUTOR-05: CastSpell dispatches ExplicitV3 without a legacy fallback") {
        val fixture = signetFixture()
        val spellId = fixture.driver.putCardInHand(fixture.player, executorSpell.name)

        val result = fixture.driver.submit(
            CastSpell(
                playerId = fixture.player,
                cardId = spellId,
                paymentStrategy = PaymentStrategy.ExplicitV3(validPlan(fixture)),
            ),
        )

        result.isSuccess shouldBe true
        fixture.driver.state.getEntity(fixture.forestId)?.has<TappedComponent>() shouldBe true
        fixture.driver.state.getEntity(fixture.signetId)?.has<TappedComponent>() shouldBe true
        fixture.driver.state.getEntity(fixture.player)?.get<ManaPoolComponent>()?.unrestrictedTotal shouldBe 0
    }

    test("PAY106-EXECUTOR-06: ActivateAbility dispatches ExplicitV3 for a paid Signet activation") {
        val fixture = signetFixture()
        val signetAbilityId = GolgariSignet.activatedAbilities.single().id
        val plan = PaymentPlanV3(
            activations = listOf(forestActivation(fixture)),
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
                ),
            ),
        )

        val result = fixture.driver.submit(
            ActivateAbility(
                playerId = fixture.player,
                sourceId = fixture.signetId,
                abilityId = signetAbilityId,
                costPayment = AdditionalCostPayment(tappedPermanents = listOf(fixture.signetId)),
                paymentStrategy = PaymentStrategy.ExplicitV3(plan),
            ),
        )

        result.isSuccess shouldBe true
        fixture.driver.state.getEntity(fixture.forestId)?.has<TappedComponent>() shouldBe true
        fixture.driver.state.getEntity(fixture.signetId)?.has<TappedComponent>() shouldBe true
        val pool = fixture.driver.state.getEntity(fixture.player)?.get<ManaPoolComponent>()
        pool?.black shouldBe 1
        pool?.green shouldBe 1
    }

    test("PAY106-EXECUTOR-SEQ-01: a later conditional mana node is rejected before mutation") {
        val fixture = signetFixture()
        val guardedId = fixture.driver.putPermanentOnBattlefield(
            fixture.player,
            sequenceGuardedManaSource.name,
        )
        val solver = ManaSolver(fixture.driver.cardRegistry)
        val guardedSource = solver.findAvailableManaSources(
            state = fixture.driver.state,
            playerId = fixture.player,
            spellContext = null,
            paymentOrderRequired = true,
        ).single { it.entityId == guardedId }
        val guardedKey = guardedSource.paymentManaAbilityOrder.single()
        val plan = PaymentPlanV3(
            activations = listOf(
                forestActivation(fixture),
                SourceActivationV2(
                    sourceId = guardedId,
                    manaAbilityKey = guardedKey,
                    productionChoice = ProductionChoice(PaymentManaColor.BLACK),
                    activationCostOrder = listOf(
                        ActivationCostComponentRefV1.DeterministicNonManaComponent(0)
                    ),
                ),
            ),
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
                ),
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(1, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
                ),
            ),
        )
        val before = fixture.driver.state
        val services = EngineServices(fixture.driver.cardRegistry)
        val result = OrderedPaymentProgramExecutor(
            manaSolver = services.manaSolver,
            manaAbilitySideEffectExecutor = services.manaAbilitySideEffectExecutor,
        ).executeV3(
            state = before,
            playerId = fixture.player,
            cost = ManaCost.parse("{G}{B}"),
            plan = plan,
            paymentContext = SpellPaymentContext(),
            reason = "PAY106 sequence stability",
        )

        result.error shouldNotBe null
        result.state shouldBe before
        result.events shouldBe emptyList()
    }

    test("PAY106-EXECUTOR-SEQ-02: a later cost-changing mana node is rejected before mutation") {
        val fixture = signetFixture()
        val changingId = fixture.driver.putPermanentOnBattlefield(
            fixture.player,
            sequenceCostChangingManaSource.name,
        )
        val solver = ManaSolver(fixture.driver.cardRegistry)
        val changingSource = solver.findAvailableManaSources(
            state = fixture.driver.state,
            playerId = fixture.player,
            spellContext = null,
            paymentOrderRequired = true,
        ).single { it.entityId == changingId }
        val changingKey = changingSource.paymentManaAbilityOrder.single()
        val plan = PaymentPlanV3(
            activations = listOf(
                forestActivation(fixture),
                SourceActivationV2(
                    sourceId = changingId,
                    manaAbilityKey = changingKey,
                    productionChoice = ProductionChoice(PaymentManaColor.BLACK),
                    activationCostOrder = listOf(
                        ActivationCostComponentRefV1.ManaComponent,
                        ActivationCostComponentRefV1.DeterministicNonManaComponent(0),
                    ),
                ),
            ),
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
                ),
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(1, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
                ),
            ),
        )
        val before = fixture.driver.state
        val services = EngineServices(fixture.driver.cardRegistry)
        val result = OrderedPaymentProgramExecutor(
            manaSolver = services.manaSolver,
            manaAbilitySideEffectExecutor = services.manaAbilitySideEffectExecutor,
        ).executeV3(
            state = before,
            playerId = fixture.player,
            cost = ManaCost.parse("{G}{B}"),
            plan = plan,
            paymentContext = SpellPaymentContext(),
            reason = "PAY106 sequence cost stability",
        )

        result.error shouldNotBe null
        result.state shouldBe before
        result.events shouldBe emptyList()
    }

    test("PAY106-EXECUTOR-STABILITY-03: external activation permission cannot change between nodes") {
        val fixture = permissionFixture()
        val services = EngineServices(fixture.driver.cardRegistry)

        services.castPermissionUtils.isActivationPreventedForPlayer(
            state = fixture.driver.state,
            sourceId = fixture.targetSourceId,
            activatingPlayerId = fixture.player,
        ) shouldBe false

        val stateAfterGuardingSourceTap = fixture.driver.state.updateEntity(fixture.guardingSourceId) {
            it.with(TappedComponent)
        }
        services.castPermissionUtils.isActivationPreventedForPlayer(
            state = stateAfterGuardingSourceTap,
            sourceId = fixture.targetSourceId,
            activatingPlayerId = fixture.player,
        ) shouldBe true

        val before = fixture.driver.state
        val result = OrderedPaymentProgramExecutor(
            manaSolver = services.manaSolver,
            manaAbilitySideEffectExecutor = services.manaAbilitySideEffectExecutor,
        ).executeV3(
            state = before,
            playerId = fixture.player,
            cost = ManaCost.parse("{G}{B}"),
            plan = permissionPlan(fixture),
            paymentContext = SpellPaymentContext(),
            reason = "PAY106 external activation permission stability",
        )

        result.error shouldNotBe null
        result.state shouldBe before
        result.events shouldBe emptyList()
    }

    test("PAY106-SIDEEFFECT-02: a colored pain activation pays and deals exactly one damage") {
        val fixture = painFixture()
        val services = EngineServices(fixture.driver.cardRegistry)
        val result = OrderedPaymentProgramExecutor(
            manaSolver = services.manaSolver,
            manaAbilitySideEffectExecutor = services.manaAbilitySideEffectExecutor,
        ).executeV3(
            state = fixture.driver.state,
            playerId = fixture.player,
            cost = ManaCost.parse("{G}"),
            plan = singlePainPlan(fixture, fixture.greenKey, PaymentManaColor.GREEN),
            paymentContext = SpellPaymentContext(),
            reason = "PAY106 fixed self-damage payment",
        )

        result.error shouldBe null
        result.state.getEntity(fixture.sourceId)?.has<TappedComponent>() shouldBe true
        result.state.lifeTotal(fixture.player) shouldBe fixture.driver.state.lifeTotal(fixture.player) - 1
        result.events.filterIsInstance<DamageDealtEvent>().single().let { damage ->
            damage.sourceId shouldBe fixture.sourceId
            damage.targetId shouldBe fixture.player
            damage.amount shouldBe 1
            damage.targetIsPlayer shouldBe true
        }
        result.events.filterIsInstance<LifeChangedEvent>().single().let { life ->
            life.playerId shouldBe fixture.player
            life.reason shouldBe LifeChangeReason.DAMAGE
            life.newLife shouldBe life.oldLife - 1
        }
        result.events.filterIsInstance<ManaSpentEvent>().single().green shouldBe 1
    }

    test("PAY106-SIDEEFFECT-03: pain before a life-history-sensitive node is not certified") {
        val fixture = painSequenceFixture()
        val sources = ManaSolver(fixture.driver.cardRegistry).findAvailableManaSources(
            state = fixture.driver.state,
            playerId = fixture.player,
            spellContext = null,
            paymentOrderRequired = true,
        )

        sources.single { it.entityId == fixture.guardedSourceId }
            .paymentManaExecutionStabilityCertified shouldBe false
        sources.single { it.entityId == fixture.painSourceId }
            .paymentManaExecutionStabilityCertified shouldBe false

        val before = fixture.driver.state
        val rejected = PaymentPlanValidator(ManaSolver(fixture.driver.cardRegistry)).validateV3(
            state = before,
            playerId = fixture.player,
            cost = ManaCost.parse("{G}{B}"),
            plan = painSequencePlan(fixture),
            spellContext = SpellPaymentContext(),
        ).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "not stable"
        fixture.driver.state shouldBe before
    }

    test("PAY106-SIDEEFFECT-04: invalid later node after pain stays transactional") {
        val fixture = painSequenceFixture()
        val before = fixture.driver.state
        val services = EngineServices(fixture.driver.cardRegistry)
        val result = OrderedPaymentProgramExecutor(
            manaSolver = services.manaSolver,
            manaAbilitySideEffectExecutor = services.manaAbilitySideEffectExecutor,
        ).executeV3(
            state = before,
            playerId = fixture.player,
            cost = ManaCost.parse("{G}{B}"),
            plan = painSequencePlan(fixture),
            paymentContext = SpellPaymentContext(),
            reason = "PAY106 fixed self-damage stability rejection",
        )

        result.error shouldNotBe null
        result.state shouldBe before
        result.events shouldBe emptyList()
        result.state.getEntity(fixture.painSourceId)?.has<TappedComponent>() shouldBe false
    }

    test("PAY106-SIDEEFFECT-05: a pain source's colorless output remains damage-free") {
        val fixture = painFixture()
        val services = EngineServices(fixture.driver.cardRegistry)
        val before = fixture.driver.state
        val result = OrderedPaymentProgramExecutor(
            manaSolver = services.manaSolver,
            manaAbilitySideEffectExecutor = services.manaAbilitySideEffectExecutor,
        ).executeV3(
            state = before,
            playerId = fixture.player,
            cost = ManaCost.parse("{C}"),
            plan = singlePainPlan(fixture, fixture.colorlessKey, PaymentManaColor.COLORLESS),
            paymentContext = SpellPaymentContext(),
            reason = "PAY106 pain-free mixed source payment",
        )

        result.error shouldBe null
        result.state.getEntity(fixture.sourceId)?.has<TappedComponent>() shouldBe true
        result.state.lifeTotal(fixture.player) shouldBe before.lifeTotal(fixture.player)
        result.events.filterIsInstance<DamageDealtEvent>() shouldBe emptyList()
        result.events.filterIsInstance<LifeChangedEvent>() shouldBe emptyList()
        result.events.filterIsInstance<ManaSpentEvent>().single().colorless shouldBe 1
    }

    test("PAY106-SIDEEFFECT-06: a live damage replacement closes fixed self-damage V5") {
        val fixture = painFixture()
        val stateWithReplacement = fixture.driver.state.updateEntity(fixture.sourceId) { container ->
            container.with(
                ReplacementEffectSourceComponent(
                    replacementEffects = listOf(PreventDamage(appliesTo = EventPattern.DamageEvent()))
                )
            )
        }
        val solver = ManaSolver(fixture.driver.cardRegistry)
        val source = solver.findAvailableManaSources(
            state = stateWithReplacement,
            playerId = fixture.player,
            spellContext = null,
            paymentOrderRequired = true,
        ).single { it.entityId == fixture.sourceId }

        source.paymentManaExecutionStabilityCertified shouldBe false

        val before = stateWithReplacement
        val result = OrderedPaymentProgramExecutor(
            manaSolver = solver,
            manaAbilitySideEffectExecutor = EngineServices(
                fixture.driver.cardRegistry,
            ).manaAbilitySideEffectExecutor,
        ).executeV3(
            state = before,
            playerId = fixture.player,
            cost = ManaCost.parse("{G}"),
            plan = singlePainPlan(fixture, fixture.greenKey, PaymentManaColor.GREEN),
            paymentContext = SpellPaymentContext(),
            reason = "PAY106 fixed self-damage replacement closure",
        )

        result.error shouldNotBe null
        result.state shouldBe before
        result.events shouldBe emptyList()
    }

    test("PAY106-ATOMIC-01: both units of an outer {2} cost are independently addressable") {
        val fixture = signetFixture(includePool = ManaPoolComponent(green = 1))
        val plan = validPlan(fixture).copy(
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(1, 1),
                ),
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 1),
                    resource = ManaResourceRefV1.InitialPoolResource(
                        InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.GREEN),
                    ),
                ),
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(1, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
                ),
            ),
        )

        validate(fixture, cost = ManaCost.parse("{2}{B}"), plan = plan)
            .shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()
    }

    test("PAY106-04: a Signet output cannot fund its own activation") {
        val fixture = signetFixture()
        val plan = validPlan(fixture).copy(
            activations = listOf(
                forestActivation(fixture),
                signetActivation(
                    fixture,
                    paymentResource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
                ),
            ),
        )
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = plan).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "earlier"
        fixture.driver.state shouldBe before
    }

    test("PAY106-05: a later activation output cannot pay an earlier activation") {
        val fixture = signetFixture()
        val plan = PaymentPlanV3(
            activations = listOf(
                signetActivation(
                    fixture,
                    activationIndex = 0,
                    paymentResource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
                ),
                forestActivation(fixture),
            ),
        )
        val before = fixture.driver.state

        val rejected = validate(fixture, cost = ManaCost.ZERO, plan = plan)
            .shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "earlier"
        fixture.driver.state shouldBe before
    }

    test("PAY106-06: one activation output cannot pay both an inner and outer target") {
        val fixture = signetFixture(forestCount = 2)
        val plan = validPlan(fixture).copy(
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
                ),
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(1, 0),
                    resource = ManaResourceRefV1.ActivationOutputUnit(1, 0),
                ),
            ),
        )
        // Keep the same forest output for both the Signet mana cost and outer generic cost.
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = plan).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "more than once"
        fixture.driver.state shouldBe before
    }

    test("PAY106-07: the same source cannot appear in two activation nodes") {
        val fixture = signetFixture()
        val plan = validPlan(fixture).copy(
            activations = listOf(
                forestActivation(fixture),
                forestActivation(fixture),
                signetActivation(fixture, activationIndex = 2),
            ),
        )
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = plan).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "more than once"
        fixture.driver.state shouldBe before
    }

    test("PAY106-08: initial pool bucket capacity is global across inner and outer payments") {
        val fixture = signetFixture(includePool = ManaPoolComponent(green = 1))
        val greenBucket = InitialPoolBucketKeyV1.UnrestrictedPoolBucket(PaymentManaColor.GREEN)
        val poolResource = ManaResourceRefV1.InitialPoolResource(greenBucket)
        val plan = PaymentPlanV3(
            activations = listOf(
                signetActivation(
                    fixture,
                    activationIndex = 0,
                    paymentResource = poolResource,
                ),
            ),
            outerAllocation = listOf(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(0, 0),
                    resource = poolResource,
                ),
            ),
        )
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = plan).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "capacity"
        fixture.driver.state shouldBe before
    }

    test("PAY106-09: a source that is absent from the current state is stale") {
        val fixture = signetFixture()
        val stale = validPlan(fixture).copy(
            activations = listOf(
                forestActivation(fixture),
                signetActivation(fixture).copy(sourceId = EntityId("stale-source")),
            ),
        )
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = stale).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "currently available"
        fixture.driver.state shouldBe before
    }

    test("PAY106-10: a stale mana ability key is rejected before ledger use") {
        val fixture = signetFixture()
        val stale = validPlan(fixture).copy(
            activations = listOf(
                forestActivation(fixture),
                signetActivation(fixture).copy(manaAbilityKey = "stale-key"),
            ),
        )
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = stale).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "identity"
        fixture.driver.state shouldBe before
    }

    test("PAY106-09B: a selected source tapped after publication makes the plan stale") {
        val fixture = signetFixture()
        val plan = validPlan(fixture)

        validate(fixture, plan = plan).shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()
        fixture.driver.tapPermanent(fixture.signetId)
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = plan)
            .shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "currently available"
        fixture.driver.state shouldBe before
    }

    test("PAY106-10B: an effective activation-cost change rejects the old plan with the same ability key") {
        val fixture = signetFixture()
        val plan = validPlan(fixture)

        validate(fixture, plan = plan).shouldBeInstanceOf<PaymentPlanValidation.AcceptedV3>()

        val signetAbility = fixture.driver.cardRegistry
            .requireCard(GolgariSignet.name)
            .activatedAbilities
            .single()
        val solver = ManaSolver(fixture.driver.cardRegistry)
        val originalCost = solver.calculateEffectiveActivatedAbilityCost(
            state = fixture.driver.state,
            sourceId = fixture.signetId,
            controllerId = fixture.player,
            ability = signetAbility,
        )

        fixture.driver.putPermanentOnBattlefield(fixture.player, activationCostModifier.name)

        val changedCost = solver.calculateEffectiveActivatedAbilityCost(
            state = fixture.driver.state,
            sourceId = fixture.signetId,
            controllerId = fixture.player,
            ability = signetAbility,
        )
        changedCost shouldNotBe originalCost
        plan.activations[1].manaAbilityKey shouldBe fixture.signetKey
        val before = fixture.driver.state

        val rejected = validate(fixture, plan = plan)
            .shouldBeInstanceOf<PaymentPlanValidation.Rejected>()

        rejected.reason shouldContain "cost"
        fixture.driver.state shouldBe before
    }
})
