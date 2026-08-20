package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mrd.cards.LightningGreaves
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/**
 * Lightning Greaves (MRD #199) — "Equipped creature has haste and shroud. Equip {0}."
 */
class LightningGreavesScenarioTest : FunSpec({

    val equipAbilityId = LightningGreaves.activatedAbilities.first().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + LightningGreaves)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun equipForZero(driver: GameTestDriver, player: EntityId, equipment: EntityId, creature: EntityId) {
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = equipment,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(creature))
            )
        ).isSuccess shouldBe true
        driver.bothPass()
    }

    fun GameTestDriver.attachedTo(equipment: EntityId): EntityId? =
        state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId

    test("zero-cost Equip grants haste and shroud to the equipped creature") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val greaves = driver.putPermanentOnBattlefield(player, "Lightning Greaves")

        equipForZero(driver, player, greaves, creature)

        driver.state.projectedState.hasKeyword(creature, Keyword.HASTE) shouldBe true
        driver.state.projectedState.hasKeyword(creature, Keyword.SHROUD) shouldBe true
    }

    test("re-equipping transfers haste and shroud to the new creature") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val first = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val second = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val greaves = driver.putPermanentOnBattlefield(player, "Lightning Greaves")

        equipForZero(driver, player, greaves, first)
        equipForZero(driver, player, greaves, second)

        driver.attachedTo(greaves) shouldBe second
        driver.state.projectedState.hasKeyword(first, Keyword.HASTE) shouldBe false
        driver.state.projectedState.hasKeyword(first, Keyword.SHROUD) shouldBe false
        driver.state.projectedState.hasKeyword(second, Keyword.HASTE) shouldBe true
        driver.state.projectedState.hasKeyword(second, Keyword.SHROUD) shouldBe true
    }

    test("Equip accepts only your creature and only at sorcery timing") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val ownCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val greaves = driver.putPermanentOnBattlefield(player, "Lightning Greaves")

        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = greaves,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(opponentCreature))
            )
        ).isSuccess shouldBe false
        driver.attachedTo(greaves) shouldBe null

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = greaves,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(ownCreature))
            )
        ).isSuccess shouldBe false
        driver.attachedTo(greaves) shouldBe null
    }

    test("shroud prevents even its controller from targeting the equipped creature") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val other = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val greaves = driver.putPermanentOnBattlefield(player, "Lightning Greaves")

        equipForZero(driver, player, greaves, creature)

        val growth = driver.putCardInHand(player, "Giant Growth")
        driver.giveMana(player, com.wingedsheep.sdk.core.Color.GREEN, 1)
        driver.castSpellWithTargets(player, growth, listOf(ChosenTarget.Permanent(creature)))
            .isSuccess shouldBe false

        val otherGrowth = driver.putCardInHand(player, "Giant Growth")
        driver.giveMana(player, com.wingedsheep.sdk.core.Color.GREEN, 1)
        driver.castSpellWithTargets(player, otherGrowth, listOf(ChosenTarget.Permanent(other)))
            .isSuccess shouldBe true
    }

    test("when Lightning Greaves leaves, its granted keywords end") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val greaves = driver.putPermanentOnBattlefield(player, "Lightning Greaves")

        equipForZero(driver, player, greaves, creature)
        driver.state.projectedState.hasKeyword(creature, Keyword.HASTE) shouldBe true
        driver.state.projectedState.hasKeyword(creature, Keyword.SHROUD) shouldBe true

        val disenchant = driver.putCardInHand(player, "Disenchant")
        driver.giveMana(player, com.wingedsheep.sdk.core.Color.WHITE, 1)
        driver.giveColorlessMana(player, 1)
        driver.castSpellWithTargets(player, disenchant, listOf(ChosenTarget.Permanent(greaves)))
            .isSuccess shouldBe true
        driver.bothPass()

        driver.getPermanents(player) shouldNotContain greaves
        driver.attachedTo(greaves).shouldBeNull()
        driver.state.projectedState.hasKeyword(creature, Keyword.HASTE) shouldBe false
        driver.state.projectedState.hasKeyword(creature, Keyword.SHROUD) shouldBe false
    }
})
