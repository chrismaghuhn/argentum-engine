package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mrd.cards.VulshokBattlegear
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Vulshok Battlegear (MRD #272) — "Equipped creature gets +3/+3. Equip {3}."
 */
class VulshokBattlegearScenarioTest : FunSpec({

    val equipAbilityId = VulshokBattlegear.activatedAbilities.first().id
    val projector = StateProjector()

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + VulshokBattlegear)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun equip(driver: GameTestDriver, player: EntityId, equipment: EntityId, creature: EntityId) {
        driver.giveColorlessMana(player, 3)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = equipment,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(creature)),
            )
        ).isSuccess shouldBe true
        driver.bothPass()
    }

    test("Equip gives the creature exactly +3/+3") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val battlegear = driver.putPermanentOnBattlefield(player, "Vulshok Battlegear")
        equip(driver, player, battlegear, creature)

        projector.getProjectedPower(driver.state, creature) shouldBe 6
        projector.getProjectedToughness(driver.state, creature) shouldBe 6
    }

    test("re-equipping transfers +3/+3 and rejects an opponent or non-sorcery target") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val first = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val second = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val battlegear = driver.putPermanentOnBattlefield(player, "Vulshok Battlegear")

        equip(driver, player, battlegear, first)
        equip(driver, player, battlegear, second)

        projector.getProjectedPower(driver.state, first) shouldBe 3
        projector.getProjectedToughness(driver.state, first) shouldBe 3
        projector.getProjectedPower(driver.state, second) shouldBe 6
        projector.getProjectedToughness(driver.state, second) shouldBe 6
        driver.state.getEntity(battlegear)?.get<AttachedToComponent>()?.targetId shouldBe second

        driver.giveColorlessMana(player, 3)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = battlegear,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(opponentCreature)),
            )
        ).isSuccess shouldBe false
        driver.state.getEntity(battlegear)?.get<AttachedToComponent>()?.targetId shouldBe second

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.giveColorlessMana(player, 3)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = battlegear,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(first)),
            )
        ).isSuccess shouldBe false
        driver.state.getEntity(battlegear)?.get<AttachedToComponent>()?.targetId shouldBe second
    }

    test("the equipped creature leaving the battlefield clears the bonus and attachment") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val battlegear = driver.putPermanentOnBattlefield(player, "Vulshok Battlegear")

        equip(driver, player, battlegear, creature)
        val caster = driver.priorityPlayer!!
        val swords = driver.putCardInHand(caster, "Swords to Plowshares")
        driver.giveMana(caster, com.wingedsheep.sdk.core.Color.WHITE, 1)
        driver.castSpellWithTargets(
            caster,
            swords,
            listOf(ChosenTarget.Permanent(creature)),
        ).isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(player, "Vulshok Battlegear") shouldBe battlegear
        driver.state.getEntity(battlegear)?.get<AttachedToComponent>() shouldBe null
        driver.findPermanent(player, "Centaur Courser") shouldBe null
    }
})
