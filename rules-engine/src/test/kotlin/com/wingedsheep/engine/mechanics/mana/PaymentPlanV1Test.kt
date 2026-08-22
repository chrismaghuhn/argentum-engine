package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.CostUnitAllocation
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.FixedManaOutput
import com.wingedsheep.engine.core.ManaSpendReference
import com.wingedsheep.engine.core.ManaSpentEvent
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV1
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.core.SpendAllocation
import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.mtg.sets.definitions.gtc.cards.BorosCharm
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ActivationRestriction
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.effects.Mode
import com.wingedsheep.sdk.scripting.effects.ModalEffect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Focused contract tests for exact action-level mana payment. */
class PaymentPlanV1Test : FunSpec({

    val anyColorSource = card("Payment Plan Any Color Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddManaOfChoice()
            manaAbility = true
        }
    }

    val fixedBundleSource = card("Payment Plan Fixed Bundle Source") {
        typeLine = "Land — Cave"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.BLACK).then(Effects.AddMana(Color.GREEN))
            manaAbility = true
        }
    }

    val fixedBundleSpell = card("Payment Plan Fixed Bundle Spell") {
        manaCost = "{B}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    val fixedBundleAbilitySource = card("Payment Plan Fixed Bundle Ability Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Mana("{B}")
            effect = Effects.GainLife(1)
        }
    }

    val payableAbilitySource = card("Payment Plan Payable Ability Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Mana("{1}{B}")
            effect = Effects.GainLife(1)
        }
    }

    val trackingRestrictedSource = card("Payment Plan Tracking Restricted Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddManaOfChoice()
            manaAbility = true
            restrictions = listOf(ActivationRestriction.OncePerTurn)
        }
    }

    val ordinarySpell = card("Payment Plan Ordinary Spell") {
        manaCost = "{1}{B}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    val extraManaModal = CardDefinition(
        name = "Payment Plan Extra Mana Modal",
        manaCost = ManaCost.parse("{R}"),
        typeLine = TypeLine.sorcery(),
        oracleText = "Choose one — Pay {1}: Gain 1 life.",
        script = CardScript.spell(
            effect = ModalEffect(
                modes = listOf(
                    Mode.noTarget(Effects.GainLife(1), "Pay {1}: Gain 1 life")
                        .copy(additionalManaCost = "{1}"),
                ),
                chooseCount = 1,
                minChooseCount = 1,
            ),
        ),
    )

    val extraCostModal = CardDefinition(
        name = "Payment Plan Extra Cost Modal",
        manaCost = ManaCost.parse("{R}"),
        typeLine = TypeLine.sorcery(),
        oracleText = "Choose one — Sacrifice a creature: Gain 1 life.",
        script = CardScript.spell(
            effect = ModalEffect(
                modes = listOf(
                    Mode.noTarget(Effects.GainLife(1), "Sacrifice a creature: Gain 1 life")
                        .copy(
                            additionalCosts = listOf(
                                Costs.additional.SacrificePermanent(
                                    filter = GameObjectFilter.Creature,
                                    count = 1,
                                ),
                            ),
                        ),
                ),
                chooseCount = 1,
                minChooseCount = 1,
            ),
        ),
    )

    val unresolvedChooseNModal = CardDefinition(
        name = "Payment Plan Unresolved Choose N Modal",
        manaCost = ManaCost.parse("{R}"),
        typeLine = TypeLine.sorcery(),
        oracleText = "Choose one or more — Gain 1 life or draw a card.",
        script = CardScript.spell(
            effect = ModalEffect(
                modes = listOf(
                    Mode.noTarget(Effects.GainLife(1), "Gain 1 life"),
                    Mode.noTarget(Effects.DrawCards(1), "Draw a card"),
                ),
                chooseCount = 2,
                minChooseCount = 1,
            ),
        ),
    )

    fun game(): Pair<GameTestDriver, EntityId> {
        val driver = GameTestDriver()
        driver.registerCards(
            TestCards.all + listOf(
                anyColorSource,
                fixedBundleSource,
                fixedBundleSpell,
                fixedBundleAbilitySource,
                payableAbilitySource,
                trackingRestrictedSource,
                ordinarySpell,
                BorosCharm,
                extraManaModal,
                extraCostModal,
                unresolvedChooseNModal,
            ),
        )
        driver.initMirrorMatch(Deck.of("Forest" to 20), startingPlayer = 0)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver to driver.activePlayer!!
    }

    fun key(driver: GameTestDriver, player: EntityId, sourceId: EntityId, color: PaymentManaColor): String {
        val engineColor = color.asEngineColor()
        val source = ManaSolver(driver.cardRegistry)
            .findAvailableManaSources(driver.state, player)
            .single { it.entityId == sourceId }
        return source.manaAbilityOptionsFor(engineColor)
            .single()
            .let(ManaAbilityIdentity::key)
    }

    fun plan(
        sourceActivations: List<SourceActivation> = emptyList(),
        poolSpend: PoolSpend = PoolSpend(),
        allocations: List<CostUnitAllocation>,
    ) = PaymentPlanV1(
        sourceActivations = sourceActivations,
        poolSpend = poolSpend,
        spendAllocation = SpendAllocation(costUnits = allocations),
    )

    test("multicolor source production and multiple source allocation are explicit") {
        val (driver, player) = game()
        val first = driver.putPermanentOnBattlefield(player, anyColorSource.name)
        val second = driver.putPermanentOnBattlefield(player, anyColorSource.name)
        val solver = ManaSolver(driver.cardRegistry)
        val validator = PaymentPlanValidator(solver)
        val firstBlackKey = key(driver, player, first, PaymentManaColor.BLACK)
        val secondGreenKey = key(driver, player, second, PaymentManaColor.GREEN)

        val result = validator.validate(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{1}{B}"),
            plan = plan(
                sourceActivations = listOf(
                    SourceActivation(first, firstBlackKey, ProductionChoice(PaymentManaColor.BLACK)),
                    SourceActivation(second, secondGreenKey, ProductionChoice(PaymentManaColor.GREEN)),
                ),
                allocations = listOf(
                    CostUnitAllocation(0, listOf(ManaSpendReference(sourceId = second))),
                    CostUnitAllocation(1, listOf(ManaSpendReference(sourceId = first))),
                ),
            ),
        )

        result.shouldBeInstanceOf<PaymentPlanValidation.Accepted>()
    }

    test("fixed deterministic multi-mana source is supported by PaymentPlanV1") {
        val (driver, player) = game()
        val sourceId = driver.putPermanentOnBattlefield(player, fixedBundleSource.name)
        val source = ManaSolver(driver.cardRegistry)
            .findAvailableManaSources(driver.state, player)
            .single { it.entityId == sourceId }

        source.supportsPaymentPlanV1() shouldBe true
    }

    test("fixed output bundle plans account for every output and preserve unavoidable leftovers") {
        val (driver, player) = game()
        val sourceId = driver.putPermanentOnBattlefield(player, fixedBundleSource.name)
        val source = ManaSolver(driver.cardRegistry)
            .findAvailableManaSources(driver.state, player)
            .single { it.entityId == sourceId }
        val abilityKey = source.manaAbilityOptionsFor(Color.BLACK)
            .single()
            .let(ManaAbilityIdentity::key)
        val bundle = listOf(
            FixedManaOutput(0, PaymentManaColor.BLACK),
            FixedManaOutput(1, PaymentManaColor.GREEN),
        )

        fun validate(cost: String, allocations: List<CostUnitAllocation>): PaymentPlanValidation.Accepted =
            PaymentPlanValidator(ManaSolver(driver.cardRegistry)).validate(
                state = driver.state,
                playerId = player,
                cost = ManaCost.parse(cost),
                plan = plan(
                    sourceActivations = listOf(
                        SourceActivation(
                            sourceId = sourceId,
                            manaAbilityKey = abilityKey,
                            productionChoice = ProductionChoice(
                                producedColor = PaymentManaColor.BLACK,
                                fixedOutputs = bundle,
                            ),
                        ),
                    ),
                    allocations = allocations,
                ),
            ).shouldBeInstanceOf<PaymentPlanValidation.Accepted>()

        val blackOnly = validate(
            "{B}",
            listOf(
                CostUnitAllocation(
                    0,
                    listOf(ManaSpendReference(sourceId = sourceId, sourceOutputIndex = 0)),
                ),
            ),
        )
        blackOnly.poolAfterSpend.green shouldBe 1
        blackOnly.poolAfterSpend.manaBySource shouldBe mapOf(sourceId to 1)
        blackOnly.poolAfterSpend.manaBySubtype shouldBe mapOf(Subtype.CAVE to 1)
        blackOnly.materialization.manaSpent.black shouldBe 1
        blackOnly.materialization.manaSpent.green shouldBe 0
        blackOnly.materialization.spentManaProvenance.sourceIds shouldBe setOf(sourceId)

        val blackAndGeneric = validate(
            "{1}{B}",
            listOf(
                CostUnitAllocation(0, listOf(ManaSpendReference(sourceId = sourceId, sourceOutputIndex = 1))),
                CostUnitAllocation(1, listOf(ManaSpendReference(sourceId = sourceId, sourceOutputIndex = 0))),
            ),
        )
        blackAndGeneric.poolAfterSpend.isEmpty() shouldBe true
        blackAndGeneric.materialization.manaSpent.black shouldBe 1
        blackAndGeneric.materialization.manaSpent.green shouldBe 1

        val blackAndGreen = validate(
            "{B}{G}",
            listOf(
                CostUnitAllocation(0, listOf(ManaSpendReference(sourceId = sourceId, sourceOutputIndex = 0))),
                CostUnitAllocation(1, listOf(ManaSpendReference(sourceId = sourceId, sourceOutputIndex = 1))),
            ),
        )
        blackAndGreen.poolAfterSpend.isEmpty() shouldBe true

        val generic = validate(
            "{2}",
            listOf(
                CostUnitAllocation(
                    0,
                    listOf(
                        ManaSpendReference(sourceId = sourceId, sourceOutputIndex = 0),
                        ManaSpendReference(sourceId = sourceId, sourceOutputIndex = 1),
                    ),
                ),
            ),
        )
        generic.poolAfterSpend.isEmpty() shouldBe true
    }

    test("bundle sources reject legacy production and unindexed output spends") {
        val (driver, player) = game()
        val sourceId = driver.putPermanentOnBattlefield(player, fixedBundleSource.name)
        val source = ManaSolver(driver.cardRegistry)
            .findAvailableManaSources(driver.state, player)
            .single { it.entityId == sourceId }
        val abilityKey = source.manaAbilityOptionsFor(Color.BLACK).single().let(ManaAbilityIdentity::key)

        fun result(choice: ProductionChoice, reference: ManaSpendReference) =
            PaymentPlanValidator(ManaSolver(driver.cardRegistry)).validate(
                state = driver.state,
                playerId = player,
                cost = ManaCost.parse("{B}"),
                plan = plan(
                    sourceActivations = listOf(SourceActivation(sourceId, abilityKey, choice)),
                    allocations = listOf(CostUnitAllocation(0, listOf(reference))),
                ),
            )

        val legacy = result(
            ProductionChoice(PaymentManaColor.BLACK),
            ManaSpendReference(sourceId = sourceId),
        ).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()
        legacy.reason shouldBe "Fixed-output source requires canonical fixedOutputs"

        val missingIndex = result(
            ProductionChoice(
                PaymentManaColor.BLACK,
                fixedOutputs = listOf(
                    FixedManaOutput(0, PaymentManaColor.BLACK),
                    FixedManaOutput(1, PaymentManaColor.GREEN),
                ),
            ),
            ManaSpendReference(sourceId = sourceId),
        ).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()
        missingIndex.reason shouldBe "Bundle source spends require sourceOutputIndex"

        val singleton = result(
            ProductionChoice(
                PaymentManaColor.BLACK,
                fixedOutputs = listOf(FixedManaOutput(0, PaymentManaColor.BLACK)),
            ),
            ManaSpendReference(sourceId = sourceId, sourceOutputIndex = 0),
        ).shouldBeInstanceOf<PaymentPlanValidation.Rejected>()
        singleton.reason shouldBe "fixedOutputs must contain at least two outputs"
    }

    test("intrinsic single-output sources remain supported") {
        val (driver, player) = game()
        val sourceId = driver.putPermanentOnBattlefield(player, "Forest")
        val result = PaymentPlanValidator(ManaSolver(driver.cardRegistry)).validate(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{G}"),
            plan = plan(
                sourceActivations = listOf(
                    SourceActivation(
                        sourceId = sourceId,
                        manaAbilityKey = "intrinsic:G",
                        productionChoice = ProductionChoice(PaymentManaColor.GREEN),
                    ),
                ),
                allocations = listOf(
                    CostUnitAllocation(0, listOf(ManaSpendReference(sourceId = sourceId))),
                ),
            ),
        )

        result.shouldBeInstanceOf<PaymentPlanValidation.Accepted>()
    }

    test("CastSpell consumes only the selected bundle output and floats the rest with provenance") {
        val (driver, player) = game()
        val spellId = driver.putCardInHand(player, fixedBundleSpell.name)
        val sourceId = driver.putPermanentOnBattlefield(player, fixedBundleSource.name)
        val source = ManaSolver(driver.cardRegistry)
            .findAvailableManaSources(driver.state, player)
            .single { it.entityId == sourceId }
        val abilityKey = source.manaAbilityOptionsFor(Color.BLACK).single().let(ManaAbilityIdentity::key)

        val result = driver.submit(
            CastSpell(
                playerId = player,
                cardId = spellId,
                paymentStrategy = PaymentStrategy.Explicit(
                    paymentPlan = plan(
                        sourceActivations = listOf(
                            SourceActivation(
                                sourceId,
                                abilityKey,
                                ProductionChoice(
                                    PaymentManaColor.BLACK,
                                    fixedOutputs = listOf(
                                        FixedManaOutput(0, PaymentManaColor.BLACK),
                                        FixedManaOutput(1, PaymentManaColor.GREEN),
                                    ),
                                ),
                            ),
                        ),
                        allocations = listOf(
                            CostUnitAllocation(
                                0,
                                listOf(ManaSpendReference(sourceId = sourceId, sourceOutputIndex = 0)),
                            ),
                        ),
                    ),
                ),
            ),
        )

        result.isSuccess shouldBe true
        result.events.filterIsInstance<ManaSpentEvent>().single().let {
            it.black shouldBe 1
            it.green shouldBe 0
        }
        val pool = driver.state.getEntity(player)?.get<ManaPoolComponent>() ?: error("missing mana pool")
        pool.green shouldBe 1
        pool.manaBySource shouldBe mapOf(sourceId to 1)
        pool.manaBySubtype shouldBe mapOf(Subtype.CAVE to 1)
        driver.isTapped(sourceId) shouldBe true
    }

    test("ActivateAbility uses the shared bundle materialization and does not reuse its leftover") {
        val (driver, player) = game()
        val abilitySourceId = driver.putPermanentOnBattlefield(player, fixedBundleAbilitySource.name)
        val manaSourceId = driver.putPermanentOnBattlefield(player, fixedBundleSource.name)
        val manaSource = ManaSolver(driver.cardRegistry)
            .findAvailableManaSources(driver.state, player)
            .single { it.entityId == manaSourceId }
        val manaAbilityKey = manaSource.manaAbilityOptionsFor(Color.BLACK).single().let(ManaAbilityIdentity::key)
        val ability = driver.cardRegistry.getCard(fixedBundleAbilitySource.name)!!
            .activatedAbilities.single()

        val result = driver.submit(
            com.wingedsheep.engine.core.ActivateAbility(
                playerId = player,
                sourceId = abilitySourceId,
                abilityId = ability.id,
                paymentStrategy = PaymentStrategy.Explicit(
                    paymentPlan = plan(
                        sourceActivations = listOf(
                            SourceActivation(
                                manaSourceId,
                                manaAbilityKey,
                                ProductionChoice(
                                    PaymentManaColor.BLACK,
                                    fixedOutputs = listOf(
                                        FixedManaOutput(0, PaymentManaColor.BLACK),
                                        FixedManaOutput(1, PaymentManaColor.GREEN),
                                    ),
                                ),
                            ),
                        ),
                        allocations = listOf(
                            CostUnitAllocation(
                                0,
                                listOf(ManaSpendReference(sourceId = manaSourceId, sourceOutputIndex = 0)),
                            ),
                        ),
                    ),
                ),
            ),
        )

        result.isSuccess shouldBe true
        val pool = driver.state.getEntity(player)?.get<ManaPoolComponent>() ?: error("missing mana pool")
        pool.green shouldBe 1
        pool.manaBySource shouldBe mapOf(manaSourceId to 1)
        pool.manaBySubtype shouldBe mapOf(Subtype.CAVE to 1)
        driver.isTapped(manaSourceId) shouldBe true
    }

    test("ActivateAbility materializes the submitted plan without invoking the solver") {
        val (driver, player) = game()
        val abilitySource = driver.putPermanentOnBattlefield(player, payableAbilitySource.name)
        val blackSource = driver.putPermanentOnBattlefield(player, anyColorSource.name)
        val genericSource = driver.putPermanentOnBattlefield(player, anyColorSource.name)
        val ability = driver.cardRegistry.getCard(payableAbilitySource.name)!!
            .activatedAbilities.single()

        val result = driver.submit(
            com.wingedsheep.engine.core.ActivateAbility(
                playerId = player,
                sourceId = abilitySource,
                abilityId = ability.id,
                paymentStrategy = com.wingedsheep.engine.core.PaymentStrategy.Explicit(
                    paymentPlan = plan(
                        sourceActivations = listOf(
                            SourceActivation(
                                blackSource,
                                key(driver, player, blackSource, PaymentManaColor.BLACK),
                                ProductionChoice(PaymentManaColor.BLACK),
                            ),
                            SourceActivation(
                                genericSource,
                                key(driver, player, genericSource, PaymentManaColor.GREEN),
                                ProductionChoice(PaymentManaColor.GREEN),
                            ),
                        ),
                        allocations = listOf(
                            CostUnitAllocation(0, listOf(ManaSpendReference(sourceId = genericSource))),
                            CostUnitAllocation(1, listOf(ManaSpendReference(sourceId = blackSource))),
                        ),
                    )
                )
            )
        )

        result.isSuccess shouldBe true
        driver.isTapped(blackSource) shouldBe true
        driver.isTapped(genericSource) shouldBe true
    }

    test("CastSpell explicit PaymentPlanV1 is the submitted payment") {
        val (driver, player) = game()
        val spellId = driver.putCardInHand(player, ordinarySpell.name)
        val blackSource = driver.putPermanentOnBattlefield(player, anyColorSource.name)
        val genericSource = driver.putPermanentOnBattlefield(player, anyColorSource.name)

        val result = driver.submit(
            CastSpell(
                playerId = player,
                cardId = spellId,
                paymentStrategy = PaymentStrategy.Explicit(
                    paymentPlan = plan(
                        sourceActivations = listOf(
                            SourceActivation(
                                blackSource,
                                key(driver, player, blackSource, PaymentManaColor.BLACK),
                                ProductionChoice(PaymentManaColor.BLACK),
                            ),
                            SourceActivation(
                                genericSource,
                                key(driver, player, genericSource, PaymentManaColor.GREEN),
                                ProductionChoice(PaymentManaColor.GREEN),
                            ),
                        ),
                        allocations = listOf(
                            CostUnitAllocation(0, listOf(ManaSpendReference(sourceId = genericSource))),
                            CostUnitAllocation(1, listOf(ManaSpendReference(sourceId = blackSource))),
                        ),
                    ),
                ),
            ),
        )

        result.isSuccess shouldBe true
        result.events.filterIsInstance<ManaSpentEvent>().single().black shouldBe 1
        driver.isTapped(blackSource) shouldBe true
        driver.isTapped(genericSource) shouldBe true
    }

    test("CastSpellMode materializes Boros Charm's exact PaymentPlanV1 and preserves the mode") {
        val (driver, player) = game()
        val spellId = driver.putCardInHand(player, BorosCharm.name)
        val redSource = driver.putPermanentOnBattlefield(player, anyColorSource.name)
        val whiteSource = driver.putPermanentOnBattlefield(player, anyColorSource.name)

        val result = driver.submit(
            CastSpell(
                playerId = player,
                cardId = spellId,
                chosenModes = listOf(1),
                paymentStrategy = PaymentStrategy.Explicit(
                    paymentPlan = plan(
                        sourceActivations = listOf(
                            SourceActivation(
                                redSource,
                                key(driver, player, redSource, PaymentManaColor.RED),
                                ProductionChoice(PaymentManaColor.RED),
                            ),
                            SourceActivation(
                                whiteSource,
                                key(driver, player, whiteSource, PaymentManaColor.WHITE),
                                ProductionChoice(PaymentManaColor.WHITE),
                            ),
                        ),
                        allocations = listOf(
                            CostUnitAllocation(0, listOf(ManaSpendReference(sourceId = redSource))),
                            CostUnitAllocation(1, listOf(ManaSpendReference(sourceId = whiteSource))),
                        ),
                    ),
                ),
            ),
        )

        result.isSuccess shouldBe true
        val spent = result.events.filterIsInstance<ManaSpentEvent>().single()
        spent.red shouldBe 1
        spent.white shouldBe 1
        driver.isTapped(redSource) shouldBe true
        driver.isTapped(whiteSource) shouldBe true

        val stackSpell = driver.state.getEntity(driver.state.stack.single())
            ?.get<SpellOnStackComponent>()
            ?: error("Expected Boros Charm on the stack")
        stackSpell.chosenModes shouldBe listOf(1)
        stackSpell.modeTargetsOrdered shouldBe emptyList()
    }

    test("fixed choose-one modal eligibility rejects unresolved or mode-specific payment shapes") {
        val (driver, player) = game()

        fun supports(card: CardDefinition): Boolean {
            val cardId = driver.putCardInHand(player, card.name)
            return ModalPaymentPlanSupport.supportsFixedChooseOne(
                state = driver.state,
                cardDef = card,
                action = CastSpell(playerId = player, cardId = cardId, chosenModes = listOf(0)),
                conditionEvaluator = ConditionEvaluator(),
            )
        }

        supports(BorosCharm) shouldBe true
        supports(extraManaModal) shouldBe false
        supports(extraCostModal) shouldBe false
        supports(unresolvedChooseNModal) shouldBe false
    }

    test("floating mana generic spend preserves the controller-selected remainder") {
        val (driver, player) = game()
        driver.giveMana(player, com.wingedsheep.sdk.core.Color.BLACK)
        driver.giveMana(player, com.wingedsheep.sdk.core.Color.GREEN)
        driver.giveMana(player, com.wingedsheep.sdk.core.Color.RED)
        val spellId = driver.putCardInHand(player, ordinarySpell.name)

        val result = driver.submit(
            CastSpell(
                playerId = player,
                cardId = spellId,
                paymentStrategy = PaymentStrategy.Explicit(
                    paymentPlan = plan(
                        poolSpend = PoolSpend(black = 1, green = 1),
                        allocations = listOf(
                            CostUnitAllocation(
                                0,
                                listOf(ManaSpendReference(poolColor = PaymentManaColor.GREEN)),
                            ),
                            CostUnitAllocation(
                                1,
                                listOf(ManaSpendReference(poolColor = PaymentManaColor.BLACK)),
                            ),
                        ),
                    ),
                ),
            ),
        )

        result.isSuccess shouldBe true
        val remainingPool = driver.state.getEntity(player)?.get<ManaPoolComponent>()
            ?: error("missing player mana pool")
        remainingPool.black shouldBe 0
        remainingPool.green shouldBe 0
        remainingPool.red shouldBe 1
    }

    test("colorless pool spend is distinct from generic spend") {
        val (driver, player) = game()
        driver.giveColorlessMana(player, 1)
        val result = PaymentPlanValidator(ManaSolver(driver.cardRegistry)).validate(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{C}"),
            plan = plan(
                poolSpend = PoolSpend(colorless = 1),
                allocations = listOf(
                    CostUnitAllocation(0, listOf(ManaSpendReference(poolColor = PaymentManaColor.COLORLESS))),
                ),
            ),
        )

        val accepted = result.shouldBeInstanceOf<PaymentPlanValidation.Accepted>()
        accepted.poolAfterSpend.colorless shouldBe 0
    }

    test("colored requirements reject an out-of-domain production choice") {
        val (driver, player) = game()
        val source = driver.putPermanentOnBattlefield(player, "Forest")
        val result = PaymentPlanValidator(ManaSolver(driver.cardRegistry)).validate(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{G}"),
            plan = plan(
                sourceActivations = listOf(
                    SourceActivation(
                        sourceId = source,
                        manaAbilityKey = "intrinsic:G",
                        productionChoice = ProductionChoice(PaymentManaColor.RED),
                    )
                ),
                allocations = listOf(
                    CostUnitAllocation(0, listOf(ManaSpendReference(sourceId = source))),
                ),
            ),
        )

        result.shouldBeInstanceOf<PaymentPlanValidation.Rejected>()
    }

    test("runtime ability handles are rejected even when the source is legal") {
        val (driver, player) = game()
        val source = driver.putPermanentOnBattlefield(player, anyColorSource.name)
        val result = PaymentPlanValidator(ManaSolver(driver.cardRegistry)).validate(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{G}"),
            plan = plan(
                sourceActivations = listOf(
                    SourceActivation(
                        sourceId = source,
                        manaAbilityKey = "generated-runtime-handle",
                        productionChoice = ProductionChoice(PaymentManaColor.GREEN),
                    )
                ),
                allocations = listOf(
                    CostUnitAllocation(0, listOf(ManaSpendReference(sourceId = source))),
                ),
            ),
        )

        result.shouldBeInstanceOf<PaymentPlanValidation.Rejected>()
    }

    test("floating mana provenance is fail-closed for PaymentPlanV1") {
        val (driver, player) = game()
        val pool = driver.state.getEntity(player)?.get<ManaPoolComponent>() ?: error("missing player mana pool")
        driver.addComponent(
            player,
            pool.copy(
                red = 1,
                manaBySource = mapOf(EntityId("floating-source") to 1),
            ),
        )

        val result = PaymentPlanValidator(ManaSolver(driver.cardRegistry)).validate(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{R}"),
            plan = plan(
                poolSpend = PoolSpend(red = 1),
                allocations = listOf(
                    CostUnitAllocation(0, listOf(ManaSpendReference(poolColor = PaymentManaColor.RED))),
                ),
            ),
        )

        result.shouldBeInstanceOf<PaymentPlanValidation.Rejected>()
    }

    test("floating mana subtype provenance is fail-closed for PaymentPlanV1") {
        val (driver, player) = game()
        val pool = driver.state.getEntity(player)?.get<ManaPoolComponent>() ?: error("missing player mana pool")
        driver.addComponent(
            player,
            pool.copy(
                red = 1,
                manaBySubtype = mapOf(Subtype.CAVE to 1),
            ),
        )

        val result = PaymentPlanValidator(ManaSolver(driver.cardRegistry)).validate(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{R}"),
            plan = plan(
                poolSpend = PoolSpend(red = 1),
                allocations = listOf(
                    CostUnitAllocation(0, listOf(ManaSpendReference(poolColor = PaymentManaColor.RED))),
                ),
            ),
        )

        result.shouldBeInstanceOf<PaymentPlanValidation.Rejected>()
    }

    test("mana abilities with activation tracking are fail-closed for PaymentPlanV1") {
        val (driver, player) = game()
        val source = driver.putPermanentOnBattlefield(player, trackingRestrictedSource.name)
        val result = PaymentPlanValidator(ManaSolver(driver.cardRegistry)).validate(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{G}"),
            plan = plan(
                sourceActivations = listOf(
                    SourceActivation(
                        sourceId = source,
                        manaAbilityKey = key(driver, player, source, PaymentManaColor.GREEN),
                        productionChoice = ProductionChoice(PaymentManaColor.GREEN),
                    ),
                ),
                allocations = listOf(
                    CostUnitAllocation(0, listOf(ManaSpendReference(sourceId = source))),
                ),
            ),
        )

        result.shouldBeInstanceOf<PaymentPlanValidation.Rejected>()
    }

    test("stable mana ability identity excludes the runtime handle") {
        val ability = anyColorSource.activatedAbilities.single()
        val sameShapeWithDifferentRuntimeId = ability.copy(id = AbilityId("another-runtime-id"))

        ManaAbilityIdentity.key(ability) shouldBe ManaAbilityIdentity.key(sameShapeWithDifferentRuntimeId)
    }

    test("PaymentPlanV1 keeps legacy JSON meaning and round-trips the canonical bundle shape") {
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }
        val legacy = json.decodeFromString<PaymentPlanV1>(
            """{"sourceActivations":[],"poolSpend":{},"spendAllocation":{}}"""
        )
        legacy shouldBe PaymentPlanV1()

        val bundle = ProductionChoice(
            producedColor = PaymentManaColor.BLACK,
            fixedOutputs = listOf(
                FixedManaOutput(0, PaymentManaColor.BLACK),
                FixedManaOutput(1, PaymentManaColor.GREEN),
            ),
        )
        val encoded = json.encodeToString(bundle)
        json.decodeFromString<ProductionChoice>(encoded) shouldBe bundle
        (encoded.indexOf("\"index\":0") < encoded.indexOf("\"index\":1")) shouldBe true
    }
})
