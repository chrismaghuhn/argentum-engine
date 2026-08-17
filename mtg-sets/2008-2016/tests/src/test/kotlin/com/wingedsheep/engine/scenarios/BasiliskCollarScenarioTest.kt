package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.wwk.cards.BasiliskCollar
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Basilisk Collar (WWK #122) — "Equipped creature has deathtouch and lifelink. Equip {2}."
 */
class BasiliskCollarScenarioTest : FunSpec({

    val equipAbilityId = BasiliskCollar.activatedAbilities.first().id

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + BasiliskCollar)
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

    test("Equip grants deathtouch and lifelink only to the equipped creature") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val equipped = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val unequipped = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val collar = driver.putPermanentOnBattlefield(player, "Basilisk Collar")

        equip(driver, player, collar, equipped)

        driver.state.projectedState.hasKeyword(equipped, Keyword.DEATHTOUCH) shouldBe true
        driver.state.projectedState.hasKeyword(equipped, Keyword.LIFELINK) shouldBe true
        driver.state.projectedState.hasKeyword(unequipped, Keyword.DEATHTOUCH) shouldBe false
        driver.state.projectedState.hasKeyword(unequipped, Keyword.LIFELINK) shouldBe false
        driver.state.getEntity(collar)?.get<AttachedToComponent>()?.targetId shouldBe equipped
    }

    test("re-equipping removes both granted keywords from the former host") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val first = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val second = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val collar = driver.putPermanentOnBattlefield(player, "Basilisk Collar")

        equip(driver, player, collar, first)
        equip(driver, player, collar, second)

        driver.state.projectedState.hasKeyword(first, Keyword.DEATHTOUCH) shouldBe false
        driver.state.projectedState.hasKeyword(first, Keyword.LIFELINK) shouldBe false
        driver.state.projectedState.hasKeyword(second, Keyword.DEATHTOUCH) shouldBe true
        driver.state.projectedState.hasKeyword(second, Keyword.LIFELINK) shouldBe true
        driver.state.getEntity(collar)?.get<AttachedToComponent>()?.targetId shouldBe second
    }

    test("Equip only targets your creatures and only works at sorcery timing") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val ownCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val collar = driver.putPermanentOnBattlefield(player, "Basilisk Collar")

        driver.giveColorlessMana(player, 2)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = collar,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(opponentCreature)),
            )
        ).isSuccess shouldBe false
        driver.state.getEntity(collar)?.get<AttachedToComponent>() shouldBe null

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.giveColorlessMana(player, 2)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = collar,
                abilityId = equipAbilityId,
                targets = listOf(ChosenTarget.Permanent(ownCreature)),
            )
        ).isSuccess shouldBe false
        driver.state.getEntity(collar)?.get<AttachedToComponent>() shouldBe null
    }

    test("lifelink applies to combat damage and the Collar becomes unattached when its host leaves") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val creature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        driver.removeSummoningSickness(creature)
        val collar = driver.putPermanentOnBattlefield(player, "Basilisk Collar")

        equip(driver, player, collar, creature)
        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(player, listOf(creature), opponent)
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.passPriorityUntil(Step.COMBAT_DAMAGE)
        while (driver.state.stack.isNotEmpty()) driver.bothPass()

        driver.getLifeTotal(opponent) shouldBe 17
        driver.getLifeTotal(player) shouldBe 23

        val caster = driver.priorityPlayer!!
        val lightningBolt = driver.putCardInHand(caster, "Lightning Bolt")
        driver.giveMana(caster, com.wingedsheep.sdk.core.Color.RED, 1)
        val boltResult = driver.castSpellWithTargets(caster, lightningBolt, listOf(ChosenTarget.Permanent(creature)))
        withClue("Lightning Bolt cast: ${boltResult.error}") {
            boltResult.isSuccess shouldBe true
        }
        driver.bothPass()

        driver.findPermanent(player, "Basilisk Collar") shouldBe collar
        driver.state.getEntity(collar)?.get<AttachedToComponent>().shouldBeNull()
        driver.getPermanents(player) shouldNotContain creature
        driver.state.projectedState.hasKeyword(creature, Keyword.DEATHTOUCH) shouldBe false
        driver.state.projectedState.hasKeyword(creature, Keyword.LIFELINK) shouldBe false
    }
})
