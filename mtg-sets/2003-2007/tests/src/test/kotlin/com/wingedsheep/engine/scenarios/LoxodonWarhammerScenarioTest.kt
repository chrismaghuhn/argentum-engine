package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.mechanics.layers.StateProjector
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mrd.cards.LoxodonWarhammer
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/**
 * Loxodon Warhammer (MRD #201) — "Equipped creature gets +3/+0 and has trample and lifelink.
 * Equip {3}."
 */
class LoxodonWarhammerScenarioTest : FunSpec({

    val equipAbilityId = LoxodonWarhammer.activatedAbilities.first().id
    val projector = StateProjector()

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + LoxodonWarhammer)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun GameTestDriver.attachedTo(equipment: EntityId): EntityId? =
        state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId

    fun equip(driver: GameTestDriver, player: EntityId, hammer: EntityId, creature: EntityId) {
        driver.giveColorlessMana(player, 3)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = hammer,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(creature))
            )
        ).isSuccess shouldBe true
        driver.bothPass()
    }

    test("Equip grants +3/+0, trample, and lifelink") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val hammer = driver.putPermanentOnBattlefield(player, "Loxodon Warhammer")
        equip(driver, player, hammer, creature)

        projector.getProjectedPower(driver.state, creature) shouldBe 6
        projector.getProjectedToughness(driver.state, creature) shouldBe 3
        driver.state.projectedState.hasKeyword(creature, Keyword.TRAMPLE) shouldBe true
        driver.state.projectedState.hasKeyword(creature, Keyword.LIFELINK) shouldBe true
    }

    test("lifelink gains life from the actual unblocked combat damage") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val hammer = driver.putPermanentOnBattlefield(player, "Loxodon Warhammer")

        equip(driver, player, hammer, creature)
        driver.removeSummoningSickness(creature)
        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(player, listOf(creature), opponent).isSuccess shouldBe true
        driver.bothPass()
        driver.declareNoBlockers(opponent).isSuccess shouldBe true
        driver.bothPass()

        driver.getLifeTotal(opponent) shouldBe 14
        driver.getLifeTotal(player) shouldBe 26
    }

    test("re-equipping transfers the stats and keywords to the new creature") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val first = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val second = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val hammer = driver.putPermanentOnBattlefield(player, "Loxodon Warhammer")

        equip(driver, player, hammer, first)
        equip(driver, player, hammer, second)

        driver.attachedTo(hammer) shouldBe second
        projector.getProjectedPower(driver.state, first) shouldBe 3
        projector.getProjectedPower(driver.state, second) shouldBe 6
        driver.state.projectedState.hasKeyword(first, Keyword.TRAMPLE) shouldBe false
        driver.state.projectedState.hasKeyword(first, Keyword.LIFELINK) shouldBe false
        driver.state.projectedState.hasKeyword(second, Keyword.TRAMPLE) shouldBe true
        driver.state.projectedState.hasKeyword(second, Keyword.LIFELINK) shouldBe true
    }

    test("Equip rejects an opponent's creature and activation outside sorcery timing") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val ownCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val hammer = driver.putPermanentOnBattlefield(player, "Loxodon Warhammer")

        driver.giveColorlessMana(player, 3)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = hammer,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(opponentCreature))
            )
        ).isSuccess shouldBe false
        driver.attachedTo(hammer) shouldBe null

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.giveColorlessMana(player, 3)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = hammer,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(ownCreature))
            )
        ).isSuccess shouldBe false
        driver.attachedTo(hammer) shouldBe null
    }

    test("when the Warhammer leaves, its bonuses end") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val hammer = driver.putPermanentOnBattlefield(player, "Loxodon Warhammer")

        equip(driver, player, hammer, creature)
        val disenchant = driver.putCardInHand(player, "Disenchant")
        driver.giveMana(player, com.wingedsheep.sdk.core.Color.WHITE, 1)
        driver.giveColorlessMana(player, 1)
        driver.castSpellWithTargets(player, disenchant, listOf(ChosenTarget.Permanent(hammer)))
            .isSuccess shouldBe true
        driver.bothPass()

        driver.getPermanents(player) shouldNotContain hammer
        driver.attachedTo(hammer).shouldBeNull()
        projector.getProjectedPower(driver.state, creature) shouldBe 3
        driver.state.projectedState.hasKeyword(creature, Keyword.TRAMPLE) shouldBe false
        driver.state.projectedState.hasKeyword(creature, Keyword.LIFELINK) shouldBe false
    }
})
