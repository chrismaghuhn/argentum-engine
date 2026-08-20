package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mrd.cards.AuriokSteelshaper
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Auriok Steelshaper (MRD #4)
 * Equip costs you pay cost {1} less. As long as this creature is equipped, each creature you
 * control that's a Soldier or a Knight gets +1/+1.
 */
class AuriokSteelshaperScenarioTest : FunSpec({

    val testBlade = card("A8 Test Blade") {
        manaCost = "{1}"
        typeLine = "Artifact — Equipment"
        oracleText = "Equipped creature gets +1/+0.\nEquip {2}"
        equipAbility("{2}")
    }
    val testKnight = card("A8 Test Knight") {
        manaCost = "{1}{W}"
        typeLine = "Creature — Human Knight"
        power = 2
        toughness = 2
    }
    val equipAbilityId = testBlade.activatedAbilities.single().id
    val projector = StateProjector()

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + listOf(AuriokSteelshaper, testBlade, testKnight))
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun equip(driver: GameTestDriver, player: com.wingedsheep.sdk.model.EntityId, blade: com.wingedsheep.sdk.model.EntityId, target: com.wingedsheep.sdk.model.EntityId) {
        driver.giveColorlessMana(player, 1)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = blade,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(target))
            )
        ).isSuccess shouldBe true
        driver.bothPass()
    }

    test("discounts Equip and buffs your equipped Steelshaper's Soldiers and Knights only") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val steelshaper = driver.putCreatureOnBattlefield(player, "Auriok Steelshaper")
        val secondSteelshaper = driver.putCreatureOnBattlefield(player, "Auriok Steelshaper")
        val knight = driver.putCreatureOnBattlefield(player, "A8 Test Knight")
        val bear = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val opponentKnight = driver.putCreatureOnBattlefield(opponent, "A8 Test Knight")
        val blade = driver.putPermanentOnBattlefield(player, "A8 Test Blade")

        projector.getProjectedPower(driver.state, steelshaper) shouldBe 1
        projector.getProjectedToughness(driver.state, steelshaper) shouldBe 1
        equip(driver, player, blade, steelshaper)

        driver.state.getEntity(blade)?.get<AttachedToComponent>()?.targetId shouldBe steelshaper
        // The {2} Equip activated successfully with only {1}; the Steelshaper's discount applied.
        projector.getProjectedPower(driver.state, steelshaper) shouldBe 2
        projector.getProjectedToughness(driver.state, steelshaper) shouldBe 2
        projector.getProjectedPower(driver.state, secondSteelshaper) shouldBe 2
        projector.getProjectedToughness(driver.state, secondSteelshaper) shouldBe 2
        projector.getProjectedPower(driver.state, knight) shouldBe 3
        projector.getProjectedToughness(driver.state, knight) shouldBe 3
        projector.getProjectedPower(driver.state, bear) shouldBe 2
        projector.getProjectedToughness(driver.state, bear) shouldBe 2
        projector.getProjectedPower(driver.state, opponentKnight) shouldBe 2
        projector.getProjectedToughness(driver.state, opponentKnight) shouldBe 2

        equip(driver, player, blade, bear)

        driver.state.getEntity(blade)?.get<AttachedToComponent>()?.targetId shouldBe bear
        projector.getProjectedPower(driver.state, steelshaper) shouldBe 1
        projector.getProjectedToughness(driver.state, steelshaper) shouldBe 1
        projector.getProjectedPower(driver.state, secondSteelshaper) shouldBe 1
        projector.getProjectedToughness(driver.state, secondSteelshaper) shouldBe 1
        projector.getProjectedPower(driver.state, knight) shouldBe 2
        projector.getProjectedToughness(driver.state, knight) shouldBe 2
    }
})
