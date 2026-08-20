package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.dst.cards.VulshokMorningstar
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Vulshok Morningstar (DST #157) — "Equipped creature gets +2/+2. Equip {2}."
 */
class VulshokMorningstarScenarioTest : FunSpec({

    val equipAbilityId = VulshokMorningstar.activatedAbilities.first().id
    val projector = StateProjector()

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + VulshokMorningstar)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun equip(driver: GameTestDriver, player: EntityId, equipment: EntityId, creature: EntityId) {
        driver.giveColorlessMana(player, 2)
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

    test("Equip gives the creature exactly +2/+2") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val morningstar = driver.putPermanentOnBattlefield(player, "Vulshok Morningstar")
        equip(driver, player, morningstar, creature)

        projector.getProjectedPower(driver.state, creature) shouldBe 5
        projector.getProjectedToughness(driver.state, creature) shouldBe 5
    }

    test("re-equipping transfers the bonus and invalid targets or timing do not move it") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val first = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val second = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val morningstar = driver.putPermanentOnBattlefield(player, "Vulshok Morningstar")

        equip(driver, player, morningstar, first)
        projector.getProjectedPower(driver.state, first) shouldBe 5
        projector.getProjectedToughness(driver.state, first) shouldBe 5

        equip(driver, player, morningstar, second)
        projector.getProjectedPower(driver.state, first) shouldBe 3
        projector.getProjectedToughness(driver.state, first) shouldBe 3
        projector.getProjectedPower(driver.state, second) shouldBe 5
        projector.getProjectedToughness(driver.state, second) shouldBe 5
        driver.state.getEntity(morningstar)?.get<AttachedToComponent>()?.targetId shouldBe second

        driver.giveColorlessMana(player, 2)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = morningstar,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(opponentCreature)),
            )
        ).isSuccess shouldBe false
        driver.state.getEntity(morningstar)?.get<AttachedToComponent>()?.targetId shouldBe second

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.giveColorlessMana(player, 2)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = morningstar,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(first)),
            )
        ).isSuccess shouldBe false
        driver.state.getEntity(morningstar)?.get<AttachedToComponent>()?.targetId shouldBe second
    }

    test("the equipped creature leaving the battlefield clears the bonus and attachment") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val morningstar = driver.putPermanentOnBattlefield(player, "Vulshok Morningstar")

        equip(driver, player, morningstar, creature)
        val caster = driver.priorityPlayer!!
        val swords = driver.putCardInHand(caster, "Swords to Plowshares")
        driver.giveMana(caster, com.wingedsheep.sdk.core.Color.WHITE, 1)
        driver.castSpellWithTargets(
            caster,
            swords,
            listOf(ChosenTarget.Permanent(creature)),
        ).isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(player, "Vulshok Morningstar") shouldBe morningstar
        driver.state.getEntity(morningstar)?.get<AttachedToComponent>() shouldBe null
        driver.findPermanent(player, "Centaur Courser") shouldBe null
    }
})
