package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.dst.cards.LeoninShikari
import com.wingedsheep.mtg.sets.definitions.mrd.cards.Bonesplitter
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Leonin Shikari — Darksteel #6.
 *
 * The card's single material clause is an unconditional static permission that lifts equip
 * activations from sorcery timing to instant timing. These scenarios exercise that permission
 * with the real Bonesplitter equip ability, while retaining normal-timing and no-Shikari controls.
 */
class LeoninShikariScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + Bonesplitter + LeoninShikari)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    val equipId = Bonesplitter.activatedAbilities.first().id

    test("Leonin Shikari's static permission allows equip during combat") {
        val driver = createDriver()
        val you = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(you, "Centaur Courser")
        val equipment = driver.putPermanentOnBattlefield(you, "Bonesplitter")
        driver.putCreatureOnBattlefield(you, "Leonin Shikari")

        driver.passPriorityUntil(Step.BEGIN_COMBAT)
        driver.giveColorlessMana(you, 1)

        driver.submit(
            ActivateAbility(you, equipment, equipId, targets = listOf(ChosenTarget.Permanent(creature)))
        ).isSuccess shouldBe true
        driver.bothPass()

        driver.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe creature
    }

    test("equip remains legal at normal sorcery timing without Leonin Shikari") {
        val driver = createDriver()
        val you = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(you, "Centaur Courser")
        val equipment = driver.putPermanentOnBattlefield(you, "Bonesplitter")

        driver.giveColorlessMana(you, 1)
        driver.submit(
            ActivateAbility(you, equipment, equipId, targets = listOf(ChosenTarget.Permanent(creature)))
        ).isSuccess shouldBe true
        driver.bothPass()

        driver.state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId shouldBe creature
    }

    test("without Leonin Shikari equip is rejected during combat") {
        val driver = createDriver()
        val you = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(you, "Centaur Courser")
        val equipment = driver.putPermanentOnBattlefield(you, "Bonesplitter")

        driver.passPriorityUntil(Step.BEGIN_COMBAT)
        driver.giveColorlessMana(you, 1)

        driver.submitExpectFailure(
            ActivateAbility(you, equipment, equipId, targets = listOf(ChosenTarget.Permanent(creature)))
        )
        driver.state.getEntity(equipment)?.get<AttachedToComponent>().shouldBeNull()
    }
})
