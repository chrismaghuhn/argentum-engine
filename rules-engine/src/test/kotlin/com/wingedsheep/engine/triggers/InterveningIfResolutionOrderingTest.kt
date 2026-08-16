package com.wingedsheep.engine.triggers

import com.wingedsheep.engine.core.AbilityFizzledEvent
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.event.GlobalGrantedTriggeredAbility
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.player.CreaturesDiedThisTurnComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Counters
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.Conditions
import com.wingedsheep.sdk.dsl.Targets
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.TriggeredAbility
import com.wingedsheep.sdk.scripting.conditions.Condition
import com.wingedsheep.sdk.scripting.conditions.CreatureDiedThisTurnCondition
import com.wingedsheep.sdk.scripting.effects.GainLifeEffect
import com.wingedsheep.sdk.scripting.references.Player
import com.wingedsheep.sdk.scripting.targets.TargetRequirement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Focused CR 608.2a/608.2b ordering coverage.
 *
 * ORDER-01 deliberately makes both the intervening-if and the chosen target illegal before
 * resolution. The event reason proves which resolution step ran first.
 */
class InterveningIfResolutionOrderingTest : FunSpec({

    fun buildDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun installTargetedEndStepAbility(
        driver: GameTestDriver,
        interveningIf: Condition? = CreatureDiedThisTurnCondition,
        triggerRestriction: Condition? = null,
        targetRequirement: TargetRequirement = Targets.Creature,
        additionalTargetRequirements: List<TargetRequirement> = emptyList()
    ) {
        val ability = TriggeredAbility.create(
            trigger = EventPattern.StepEvent(Step.END, Player.You),
            effect = GainLifeEffect(1),
            targetRequirement = targetRequirement,
            additionalTargetRequirements = additionalTargetRequirements,
            interveningIf = interveningIf,
            triggerRestriction = triggerRestriction
        )
        driver.replaceState(
            driver.state.copy(
                globalGrantedTriggeredAbilities = listOf(
                    GlobalGrantedTriggeredAbility(
                        ability = ability,
                        controllerId = driver.player1,
                        sourceId = driver.player1,
                        sourceName = "Ordering Test Ability",
                        duration = Duration.Permanent
                    )
                )
            )
        )
    }

    fun putTargetedAbilityOnStack(driver: GameTestDriver, targets: List<EntityId>) {
        driver.passPriorityUntil(Step.END)
        driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        driver.submitTargetSelection(driver.player1, targets)
        driver.pendingDecision shouldBe null
    }

    fun lastFizzleReason(driver: GameTestDriver): String =
        driver.events.filterIsInstance<AbilityFizzledEvent>().last().reason

    test("ORDER-01: false intervening-if wins before all-illegal-target validation") {
        val driver = buildDriver()
        installTargetedEndStepAbility(driver)
        val target = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        driver.replaceState(
            driver.state.updateEntity(driver.player1) {
                it.with(CreaturesDiedThisTurnComponent(count = 1))
            }
        )
        driver.passPriorityUntil(Step.END)
        driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        driver.submitTargetSelection(driver.player1, listOf(target))
        driver.pendingDecision shouldBe null

        driver.replaceState(
            driver.state.updateEntity(driver.player1) {
                it.without<CreaturesDiedThisTurnComponent>()
            }
        )
        driver.moveToGraveyard(target)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore
        driver.stackSize shouldBe 0
        driver.events.filterIsInstance<AbilityFizzledEvent>().last().reason shouldBe
            "Intervening-if condition is no longer true"
    }

    test("ORDER-02: true intervening-if still reaches all-illegal-target validation") {
        val driver = buildDriver()
        installTargetedEndStepAbility(driver)
        val target = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        driver.replaceState(
            driver.state.updateEntity(driver.player1) {
                it.with(CreaturesDiedThisTurnComponent(count = 1))
            }
        )
        putTargetedAbilityOnStack(driver, listOf(target))
        driver.moveToGraveyard(target)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore
        driver.stackSize shouldBe 0
        lastFizzleReason(driver) shouldBe "All targets are invalid"
    }

    test("ORDER-03: false intervening-if wins while the target remains legal") {
        val driver = buildDriver()
        installTargetedEndStepAbility(driver)
        val target = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        driver.replaceState(
            driver.state.updateEntity(driver.player1) {
                it.with(CreaturesDiedThisTurnComponent(count = 1))
            }
        )
        putTargetedAbilityOnStack(driver, listOf(target))
        driver.replaceState(
            driver.state.updateEntity(driver.player1) {
                it.without<CreaturesDiedThisTurnComponent>()
            }
        )
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore
        lastFizzleReason(driver) shouldBe "Intervening-if condition is no longer true"
    }

    test("ORDER-04: true intervening-if and legal target resolve the effect") {
        val driver = buildDriver()
        installTargetedEndStepAbility(driver)
        val target = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        driver.replaceState(
            driver.state.updateEntity(driver.player1) {
                it.with(CreaturesDiedThisTurnComponent(count = 1))
            }
        )
        putTargetedAbilityOnStack(driver, listOf(target))
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 1
        driver.events.filterIsInstance<AbilityFizzledEvent>() shouldBe emptyList()
    }

    test("ORDER-05: triggerRestriction is not rechecked at resolution") {
        val driver = buildDriver()
        val creature = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val target = driver.putPermanentOnBattlefield(driver.player1, "Sol Ring")
        val lifeBefore = driver.getLifeTotal(driver.player1)
        installTargetedEndStepAbility(
            driver,
            interveningIf = null,
            triggerRestriction = Conditions.YouControl(GameObjectFilter.Creature),
            targetRequirement = Targets.Permanent
        )

        putTargetedAbilityOnStack(driver, listOf(target))
        driver.moveToGraveyard(creature)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 1
        driver.events.filterIsInstance<AbilityFizzledEvent>() shouldBe emptyList()
    }

    test("ORDER-06: one illegal target does not cause an all-target fizzle") {
        val driver = buildDriver()
        installTargetedEndStepAbility(
            driver,
            additionalTargetRequirements = listOf(Targets.Creature)
        )
        val firstTarget = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val secondTarget = driver.putCreatureOnBattlefield(driver.player1, "Grizzly Bears")
        val lifeBefore = driver.getLifeTotal(driver.player1)

        driver.replaceState(
            driver.state.updateEntity(driver.player1) {
                it.with(CreaturesDiedThisTurnComponent(count = 1))
            }
        )
        driver.passPriorityUntil(Step.END)
        driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        driver.submitMultiTargetSelection(
            driver.player1,
            mapOf(0 to listOf(firstTarget), 1 to listOf(secondTarget))
        )
        driver.pendingDecision shouldBe null
        driver.moveToGraveyard(firstTarget)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore + 1
        driver.events.filterIsInstance<AbilityFizzledEvent>() shouldBe emptyList()
    }

    test("ORDER-07: BOUNTY intervening-if wins before an illegal target") {
        val driver = buildDriver()
        val target = driver.putPermanentOnBattlefield(driver.player2, "Sol Ring")
        val bountyPermanent = driver.putPermanentOnBattlefield(driver.player2, "Sol Ring")
        val lifeBefore = driver.getLifeTotal(driver.player1)
        val ability = TriggeredAbility.create(
            trigger = EventPattern.StepEvent(Step.UPKEEP, Player.You),
            effect = GainLifeEffect(1),
            targetRequirement = Targets.PermanentOpponentControls,
            interveningIf = Conditions.OpponentControls(
                GameObjectFilter.Permanent.withCounter(Counters.BOUNTY),
                negate = true
            )
        )
        driver.replaceState(
            driver.state.copy(
                globalGrantedTriggeredAbilities = listOf(
                    GlobalGrantedTriggeredAbility(
                        ability = ability,
                        controllerId = driver.player1,
                        sourceId = driver.player1,
                        sourceName = "Synthetic BOUNTY Ordering Ability",
                        duration = Duration.Permanent
                    )
                )
            )
        )

        // buildDriver leaves player 1 in their precombat main phase. Cross player 2's
        // upkeep/turn so the next upkeep is the controller's upkeep (Player.You).
        driver.passPriorityUntil(Step.UPKEEP)
        driver.passPriorityUntil(Step.END)
        driver.passPriorityUntil(Step.UPKEEP)
        driver.pendingDecision.shouldBeInstanceOf<ChooseTargetsDecision>()
        driver.submitTargetSelection(driver.player1, listOf(target))
        driver.pendingDecision shouldBe null

        driver.addComponent(
            bountyPermanent,
            CountersComponent(mapOf(CounterType.BOUNTY to 1))
        )
        driver.moveToGraveyard(target)
        driver.bothPass()

        driver.getLifeTotal(driver.player1) shouldBe lifeBefore
        driver.stackSize shouldBe 0
        lastFizzleReason(driver) shouldBe "Intervening-if condition is no longer true"
    }
})
