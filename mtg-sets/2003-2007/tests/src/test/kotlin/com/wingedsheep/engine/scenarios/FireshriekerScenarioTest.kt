package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.mrd.cards.Fireshrieker
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/**
 * Fireshrieker (MRD #171) — "Equipped creature has double strike. Equip {2}."
 */
class FireshriekerScenarioTest : FunSpec({

    val equipAbilityId = Fireshrieker.activatedAbilities.first().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + Fireshrieker)
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
                targets = listOf(ChosenTarget.Permanent(creature))
            )
        ).isSuccess shouldBe true
        driver.bothPass()
    }

    fun GameTestDriver.attachedTo(equipment: EntityId): EntityId? =
        state.getEntity(equipment)?.get<AttachedToComponent>()?.targetId

    test("Equip grants double strike to the equipped creature only") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val equipped = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val other = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val fireshrieker = driver.putPermanentOnBattlefield(player, "Fireshrieker")

        equip(driver, player, fireshrieker, equipped)

        driver.state.projectedState.hasKeyword(equipped, Keyword.DOUBLE_STRIKE) shouldBe true
        driver.state.projectedState.hasKeyword(other, Keyword.DOUBLE_STRIKE) shouldBe false
    }

    test("re-equipping transfers double strike and clears the old attachment") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val first = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val second = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val fireshrieker = driver.putPermanentOnBattlefield(player, "Fireshrieker")

        equip(driver, player, fireshrieker, first)
        driver.attachedTo(fireshrieker) shouldBe first
        driver.state.projectedState.hasKeyword(first, Keyword.DOUBLE_STRIKE) shouldBe true

        equip(driver, player, fireshrieker, second)

        driver.attachedTo(fireshrieker) shouldBe second
        driver.state.projectedState.hasKeyword(first, Keyword.DOUBLE_STRIKE) shouldBe false
        driver.state.projectedState.hasKeyword(second, Keyword.DOUBLE_STRIKE) shouldBe true
    }

    test("Equip rejects an opponent's creature and activation outside sorcery timing") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val ownCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val fireshrieker = driver.putPermanentOnBattlefield(player, "Fireshrieker")

        driver.giveColorlessMana(player, 2)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = fireshrieker,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(opponentCreature))
            )
        ).isSuccess shouldBe false
        driver.attachedTo(fireshrieker) shouldBe null

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.giveColorlessMana(player, 2)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = fireshrieker,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(ownCreature))
            )
        ).isSuccess shouldBe false
        driver.attachedTo(fireshrieker) shouldBe null
    }

    test("when the equipped creature leaves, Fireshrieker stays and becomes unattached") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val fireshrieker = driver.putPermanentOnBattlefield(player, "Fireshrieker")

        equip(driver, player, fireshrieker, creature)
        val doomBlade = driver.putCardInHand(player, "Doom Blade")
        driver.giveMana(player, com.wingedsheep.sdk.core.Color.BLACK, 1)
        driver.giveColorlessMana(player, 1)
        driver.castSpellWithTargets(player, doomBlade, listOf(ChosenTarget.Permanent(creature)))
            .isSuccess shouldBe true
        driver.bothPass()

        driver.findPermanent(player, "Fireshrieker") shouldBe fireshrieker
        driver.attachedTo(fireshrieker).shouldBeNull()
        driver.state.projectedState.hasKeyword(fireshrieker, Keyword.DOUBLE_STRIKE) shouldBe false
    }

    test("when Fireshrieker leaves, its granted double strike ends") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val fireshrieker = driver.putPermanentOnBattlefield(player, "Fireshrieker")

        equip(driver, player, fireshrieker, creature)
        driver.state.projectedState.hasKeyword(creature, Keyword.DOUBLE_STRIKE) shouldBe true

        val disenchant = driver.putCardInHand(player, "Disenchant")
        driver.giveMana(player, com.wingedsheep.sdk.core.Color.WHITE, 1)
        driver.giveColorlessMana(player, 1)
        driver.castSpellWithTargets(player, disenchant, listOf(ChosenTarget.Permanent(fireshrieker))
        ).isSuccess shouldBe true
        driver.bothPass()

        driver.getPermanents(player) shouldNotContain fireshrieker
        driver.state.projectedState.hasKeyword(creature, Keyword.DOUBLE_STRIKE) shouldBe false
    }
})
