package com.wingedsheep.engine.mechanics.mana

import com.wingedsheep.engine.core.CostUnitAllocation
import com.wingedsheep.engine.core.ManaSpendReference
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV1
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.core.SpendAllocation
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

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

    val payableAbilitySource = card("Payment Plan Payable Ability Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Mana("{1}{B}")
            effect = Effects.GainLife(1)
        }
    }

    fun game(): Pair<GameTestDriver, EntityId> {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + anyColorSource + payableAbilitySource)
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

    test("floating mana generic spend preserves the controller-selected remainder") {
        val (driver, player) = game()
        driver.giveMana(player, com.wingedsheep.sdk.core.Color.BLACK)
        driver.giveMana(player, com.wingedsheep.sdk.core.Color.GREEN)
        val validator = PaymentPlanValidator(ManaSolver(driver.cardRegistry))

        val spendBlack = validator.validate(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{1}"),
            plan = plan(
                poolSpend = PoolSpend(black = 1),
                allocations = listOf(
                    CostUnitAllocation(0, listOf(ManaSpendReference(poolColor = PaymentManaColor.BLACK))),
                ),
            ),
        ).shouldBeInstanceOf<PaymentPlanValidation.Accepted>()

        spendBlack.poolAfterSpend.black shouldBe 0
        spendBlack.poolAfterSpend.green shouldBe 1

        val spendGreen = validator.validate(
            state = driver.state,
            playerId = player,
            cost = ManaCost.parse("{1}"),
            plan = plan(
                poolSpend = PoolSpend(green = 1),
                allocations = listOf(
                    CostUnitAllocation(0, listOf(ManaSpendReference(poolColor = PaymentManaColor.GREEN))),
                ),
            ),
        ).shouldBeInstanceOf<PaymentPlanValidation.Accepted>()

        spendGreen.poolAfterSpend.black shouldBe 1
        spendGreen.poolAfterSpend.green shouldBe 0
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

    test("stable mana ability identity excludes the runtime handle") {
        val ability = anyColorSource.activatedAbilities.single()
        val sameShapeWithDifferentRuntimeId = ability.copy(id = AbilityId("another-runtime-id"))

        ManaAbilityIdentity.key(ability) shouldBe ManaAbilityIdentity.key(sameShapeWithDifferentRuntimeId)
    }
})
