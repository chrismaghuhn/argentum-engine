package com.wingedsheep.engine.triggers

import com.wingedsheep.engine.core.AbilityFizzledEvent
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.player.CreaturesDiedThisTurnComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.conditions.Condition
import com.wingedsheep.sdk.scripting.conditions.CreatureDiedThisTurnCondition
import com.wingedsheep.sdk.scripting.effects.Mode
import com.wingedsheep.sdk.scripting.effects.ModalEffect
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import com.wingedsheep.sdk.scripting.targets.TargetCreature
import com.wingedsheep.sdk.scripting.targets.TargetRequirement
import com.wingedsheep.sdk.scripting.filters.unified.TargetFilter
import com.wingedsheep.sdk.scripting.values.ContextPropertyKey
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Synthetic resolution coverage for CR 608.2b.
 *
 * These tests deliberately bypass card definitions and put a locked triggered ability directly
 * on the stack. That keeps the assertions about the generic resolution payload rather than a
 * card-specific authoring path.
 */
class PartialIllegalTargets608Test : FunSpec({

    fun driver(): GameTestDriver = GameTestDriver().also {
        it.registerCards(TestCards.all)
        it.initMirrorMatch(deck = Deck.of("Forest" to 40))
    }

    fun putTriggeredAbility(
        driver: GameTestDriver,
        effect: com.wingedsheep.sdk.scripting.effects.Effect,
        targets: List<ChosenTarget>,
        targetRequirements: List<TargetRequirement>,
        chosenModes: List<Int> = emptyList(),
        modeTargetsOrdered: List<List<ChosenTarget>> = emptyList(),
        modeTargetRequirements: Map<Int, List<TargetRequirement>> = emptyMap(),
        damageDistribution: Map<com.wingedsheep.sdk.model.EntityId, Int>? = null,
        interveningIf: Condition? = null
    ) {
        val result = StackResolver(driver.cardRegistry).putTriggeredAbility(
            state = driver.state,
            ability = TriggeredAbilityOnStackComponent(
                sourceId = driver.player1,
                sourceName = "Synthetic 608.2b ability",
                controllerId = driver.player1,
                effect = effect,
                description = "Synthetic 608.2b ability",
                damageDistribution = damageDistribution,
                chosenModes = chosenModes,
                modeTargetsOrdered = modeTargetsOrdered,
                modeTargetRequirements = modeTargetRequirements,
                interveningIf = interveningIf
            ),
            targets = targets,
            targetRequirements = targetRequirements
        )
        result.error shouldBe null
        driver.replaceState(result.newState)
    }

    val targetCount = DynamicAmount.ContextProperty(ContextPropertyKey.TARGET_COUNT)

    test("608-01: all targets illegal removes the ability without resolving") {
        val driver = driver()
        val target = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(1),
            targets = listOf(ChosenTarget.Permanent(target)),
            targetRequirements = listOf(Targets.Creature)
        )
        driver.moveToGraveyard(target)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore
        driver.stackSize shouldBe 0
    }

    test("608-02: a non-targeted instruction sees only legal surviving targets") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(targetCount),
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(TargetCreature(count = 2))
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        lifeBefore + 1 shouldBe driver.getLifeTotal(driver.player1)
    }

    test("608-03: an up-to target requirement also keeps the legal survivor") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(targetCount),
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(TargetCreature(count = 2, optional = true))
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        lifeBefore + 1 shouldBe driver.getLifeTotal(driver.player1)
    }

    test("608-04: multiple requirement payloads retain the surviving target count") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(targetCount),
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(
                TargetCreature(id = "first"),
                TargetCreature(id = "second")
            )
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        lifeBefore + 1 shouldBe driver.getLifeTotal(driver.player1)
    }

    test("608-05: prechosen mode target payload is partially legal") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val modeRequirement = TargetCreature(count = 2)
        val modal = ModalEffect(
            modes = listOf(
                Mode(
                    effect = Effects.GainLife(targetCount),
                    targetRequirements = listOf(modeRequirement),
                    description = "Gain life for each target"
                )
            ),
            chooseCount = 1
        )
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = modal,
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(modeRequirement),
            chosenModes = listOf(0),
            modeTargetsOrdered = listOf(
                listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second))
            ),
            modeTargetRequirements = mapOf(0 to listOf(modeRequirement))
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        lifeBefore + 1 shouldBe driver.getLifeTotal(driver.player1)
    }

    test("608-05b: an illegal mode target does not suppress its non-targeted sibling") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val requirement = TargetCreature()
        val modal = ModalEffect(
            modes = listOf(
                Mode(
                    effect = Effects.Composite(
                        Effects.Destroy(EffectTarget.ContextTarget(0)),
                        Effects.GainLife(1)
                    ),
                    targetRequirements = listOf(requirement),
                    description = "Destroy a target creature and gain 1 life"
                ),
                Mode(
                    effect = Effects.Destroy(EffectTarget.ContextTarget(0)),
                    targetRequirements = listOf(requirement),
                    description = "Destroy another target creature"
                )
            ),
            chooseCount = 2
        )
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = modal,
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(requirement, requirement),
            chosenModes = listOf(0, 1),
            modeTargetsOrdered = listOf(
                listOf(ChosenTarget.Permanent(first)),
                listOf(ChosenTarget.Permanent(second))
            ),
            modeTargetRequirements = mapOf(0 to listOf(requirement), 1 to listOf(requirement))
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 1
        (second in driver.state.getBattlefield()) shouldBe false
    }

    test("608-06: legal target portions and non-targeted instructions both resolve") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.Composite(
                Effects.Destroy(EffectTarget.ContextTarget(1)),
                Effects.GainLife(1)
            ),
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(TargetCreature(count = 2))
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 1
        (second in driver.state.getBattlefield()) shouldBe false
    }

    test("608-07: locked damage distribution is not recomputed after one target leaves") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")

        putTriggeredAbility(
            driver,
            effect = Effects.DividedDamage(total = 2, minTargets = 1, maxTargets = 2),
            targets = listOf(ChosenTarget.Permanent(first), ChosenTarget.Permanent(second)),
            targetRequirements = listOf(TargetCreature(count = 2)),
            damageDistribution = mapOf(first to 1, second to 1)
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        driver.state.getEntity(second)?.get<DamageComponent>()?.amount shouldBe 1
    }

    test("608-08: the same object may occupy separate requirement instances") {
        val driver = driver()
        val first = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val second = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(targetCount),
            targets = listOf(
                ChosenTarget.Permanent(first),
                ChosenTarget.Permanent(first),
                ChosenTarget.Permanent(second)
            ),
            targetRequirements = listOf(
                TargetCreature(),
                TargetCreature(),
                TargetCreature()
            )
        )
        driver.moveToGraveyard(first)
        driver.bothPass()

        lifeBefore + 1 shouldBe driver.getLifeTotal(driver.player1)
    }

    test("608-09: controller, type, and property changes recheck each locked slot") {
        val driver = driver()
        val controllerChanged = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val typeChanged = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val propertyChanged = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val survivor = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)
        val requirement = TargetCreature(
            count = 4,
            filter = TargetFilter.CreatureYouControl.powerAtLeast(2)
        )

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(targetCount),
            targets = listOf(
                ChosenTarget.Permanent(controllerChanged),
                ChosenTarget.Permanent(typeChanged),
                ChosenTarget.Permanent(propertyChanged),
                ChosenTarget.Permanent(survivor)
            ),
            targetRequirements = listOf(requirement)
        )
        driver.replaceState(
            driver.state
                .updateEntity(controllerChanged) { it.with(ControllerComponent(driver.player2)) }
                .updateEntity(typeChanged) {
                    it.get<CardComponent>()?.let { card ->
                        it.with(card.copy(typeLine = TypeLine.artifact(), baseStats = null))
                    } ?: it
                }
                .updateEntity(propertyChanged) {
                    it.get<CardComponent>()?.let { card ->
                        it.with(card.copy(baseStats = com.wingedsheep.sdk.model.CreatureStats(0, 2)))
                    } ?: it
                }
        )
        driver.bothPass()

        lifeBefore + 1 shouldBe driver.getLifeTotal(driver.player1)
    }

    test("608-10: CR 608.2a intervening-if is checked before target legality") {
        val driver = driver()
        val target = driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)
        driver.replaceState(
            driver.state.updateEntity(driver.player1) {
                it.with(CreaturesDiedThisTurnComponent(count = 1))
            }
        )

        putTriggeredAbility(
            driver,
            effect = Effects.GainLife(1),
            targets = listOf(ChosenTarget.Permanent(target)),
            targetRequirements = listOf(Targets.Creature),
            interveningIf = CreatureDiedThisTurnCondition
        )
        driver.replaceState(
            driver.state.updateEntity(driver.player1) {
                it.without<CreaturesDiedThisTurnComponent>()
            }
        )
        driver.moveToGraveyard(target)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore
        driver.events.filterIsInstance<AbilityFizzledEvent>().last().reason shouldBe
            "Intervening-if condition is no longer true"
    }
})
