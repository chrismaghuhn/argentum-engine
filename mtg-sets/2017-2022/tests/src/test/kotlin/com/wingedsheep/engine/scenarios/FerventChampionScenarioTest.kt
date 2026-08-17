package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.ChooseTargetsDecision
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.eld.cards.FerventChampion
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Fervent Champion (ELD #124).
 *
 * Covers first strike, haste, the attacking-Knight target domain and temporary +1/+0, plus the
 * source-targeted equip-cost reduction. The target and payment assertions intentionally use the
 * engine's explicit decision/payment boundaries rather than selecting by collection order.
 */
class FerventChampionScenarioTest : FunSpec({

    val testBlade = card("Fervent Champion Test Blade") {
        manaCost = "{1}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equipped creature gets +1/+0.\nEquip {3}"
        equipAbility("{3}")
    }

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(FerventChampion, testBlade))
        driver.initMirrorMatch(
            deck = Deck.of("Plains" to 40),
            startingPlayer = 0,
            skipMulligans = true,
        )
        return driver
    }

    fun GameTestDriver.resolveStack() {
        var guard = 0
        while (stackSize > 0 && pendingDecision == null && guard++ < 50) {
            bothPass()
        }
    }

    fun GameTestDriver.attackNow(attackers: Map<EntityId, EntityId>) =
        passPriorityUntil(Step.DECLARE_ATTACKERS)
            .let { declareAttackers(activePlayer!!, attackers) }

    test("has first strike and haste") {
        val driver = newDriver()
        val champion = driver.putCreatureOnBattlefield(driver.player1, "Fervent Champion")
        val knight = driver.putCreatureOnBattlefield(
            driver.player1,
            "Syr Alin, the Lion's Claw",
        )
        driver.removeSummoningSickness(knight)

        withClue("Fervent Champion has first strike") {
            driver.state.projectedState.hasKeyword(champion, Keyword.FIRST_STRIKE) shouldBe true
        }
        withClue("Fervent Champion has haste and may attack immediately after entering") {
            driver.attackNow(mapOf(champion to driver.player2, knight to driver.player2)).error shouldBe null
        }
    }

    test("attack trigger targets another attacking Knight you control and gives +1/+0 until end of turn") {
        val driver = newDriver()
        val champion = driver.putCreatureOnBattlefield(driver.player1, "Fervent Champion")
        val knight = driver.putCreatureOnBattlefield(
            driver.player1,
            "Syr Alin, the Lion's Claw",
        )
        val nonKnight = driver.putCreatureOnBattlefield(
            driver.player1,
            "Grizzly Bears",
        )
        driver.removeSummoningSickness(knight)
        driver.removeSummoningSickness(nonKnight)
        driver.removeSummoningSickness(champion)

        driver.attackNow(mapOf(champion to driver.player2, knight to driver.player2, nonKnight to driver.player2))
        driver.resolveStack()

        val targetDecision = driver.pendingDecision as ChooseTargetsDecision
        targetDecision.legalTargets.getValue(0) shouldContain knight
        targetDecision.legalTargets.getValue(0) shouldNotContain champion
        targetDecision.legalTargets.getValue(0) shouldNotContain nonKnight

        driver.submitTargetSelection(driver.player1, listOf(knight)).error shouldBe null
        driver.resolveStack()
        driver.state.projectedState.getPower(knight) shouldBe 5

        // The effect is temporary; the base 4/4 returns after the turn's cleanup.
        driver.passPriorityUntil(Phase.BEGINNING)
        driver.state.projectedState.getPower(knight) shouldBe 4
    }

    test("equip abilities targeting Fervent Champion cost {3} less, but other targets do not") {
        val driver = newDriver()
        val champion = driver.putCreatureOnBattlefield(driver.player1, "Fervent Champion")
        val otherCreature = driver.putCreatureOnBattlefield(
            driver.player1,
            "Grizzly Bears",
        )
        driver.removeSummoningSickness(otherCreature)
        val blade = driver.putPermanentOnBattlefield(driver.player1, testBlade.name)
        val equipId = testBlade.activatedAbilities.single().id
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)

        withClue("Equip {3} targeting Fervent Champion is free under the {3} reduction") {
            driver.submit(
                ActivateAbility(
                    driver.player1,
                    blade,
                    equipId,
                    targets = listOf(com.wingedsheep.engine.state.components.stack.ChosenTarget.Permanent(champion)),
                )
            ).isSuccess shouldBe true
            driver.bothPass()
            driver.state.getEntity(blade)?.get<AttachedToComponent>()?.targetId shouldBe champion
        }

        val secondBlade = driver.putPermanentOnBattlefield(driver.player1, testBlade.name)
        driver.giveColorlessMana(driver.player1, 2)
        withClue("the same reduction does not apply when the equip targets another creature") {
            driver.submit(
                ActivateAbility(
                    driver.player1,
                    secondBlade,
                    equipId,
                    targets = listOf(com.wingedsheep.engine.state.components.stack.ChosenTarget.Permanent(otherCreature)),
                )
            ).isSuccess shouldBe false
        }
    }
})
