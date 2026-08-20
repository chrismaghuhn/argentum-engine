package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

/**
 * Shadowspear (THB #236).
 *
 * Current Oracle text (Scryfall, fetched 2026-08-17):
 * "Equipped creature gets +1/+1 and has trample and lifelink.
 * {1}: Permanents your opponents control lose hexproof and indestructible until end of turn.
 * Equip {2}"
 */
class ShadowspearScenarioTest : FunSpec({

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        driver.initMirrorMatch(deck = Deck.of("Plains" to 40), startingLife = 20)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun abilityId(driver: GameTestDriver, equip: Boolean) =
        driver.cardRegistry.requireCard("Shadowspear").activatedAbilities.single { it.isEquipAbility == equip }.id

    fun equip(driver: GameTestDriver, player: EntityId, spear: EntityId, creature: EntityId) {
        driver.giveColorlessMana(player, 2)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = spear,
                abilityId = abilityId(driver, equip = true),
                targets = listOf(ChosenTarget.Permanent(creature)),
            )
        ).isSuccess shouldBe true
        driver.bothPass().isSuccess shouldBe true
    }

    test("equip grants +1/+1, trample, and lifelink only to its current host") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val firstHost = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val secondHost = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val spear = driver.putPermanentOnBattlefield(player, "Shadowspear")

        equip(driver, player, spear, firstHost)

        driver.state.projectedState.getPower(firstHost) shouldBe 4
        driver.state.projectedState.getToughness(firstHost) shouldBe 4
        driver.state.projectedState.hasKeyword(firstHost, Keyword.TRAMPLE) shouldBe true
        driver.state.projectedState.hasKeyword(firstHost, Keyword.LIFELINK) shouldBe true
        driver.state.projectedState.getPower(secondHost) shouldBe 3
        driver.state.projectedState.hasKeyword(secondHost, Keyword.TRAMPLE) shouldBe false
        driver.state.projectedState.hasKeyword(secondHost, Keyword.LIFELINK) shouldBe false

        equip(driver, player, spear, secondHost)

        driver.state.projectedState.getPower(firstHost) shouldBe 3
        driver.state.projectedState.getToughness(firstHost) shouldBe 3
        driver.state.projectedState.hasKeyword(firstHost, Keyword.TRAMPLE) shouldBe false
        driver.state.projectedState.hasKeyword(firstHost, Keyword.LIFELINK) shouldBe false
        driver.state.projectedState.getPower(secondHost) shouldBe 4
        driver.state.projectedState.hasKeyword(secondHost, Keyword.TRAMPLE) shouldBe true
        driver.state.projectedState.hasKeyword(secondHost, Keyword.LIFELINK) shouldBe true
        driver.state.getEntity(spear)?.get<AttachedToComponent>()?.targetId shouldBe secondHost
    }

    test("equip targets only a creature you control and only at sorcery speed") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val ownCreature = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val opponentCreature = driver.putCreatureOnBattlefield(opponent, "Centaur Courser")
        val spear = driver.putPermanentOnBattlefield(player, "Shadowspear")

        driver.giveColorlessMana(player, 2)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = spear,
                abilityId = abilityId(driver, equip = true),
                targets = listOf(ChosenTarget.Permanent(opponentCreature)),
            )
        ).isSuccess shouldBe false
        driver.state.getEntity(spear)?.get<AttachedToComponent>().shouldBeNull()

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.giveColorlessMana(player, 2)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = spear,
                abilityId = abilityId(driver, equip = true),
                targets = listOf(ChosenTarget.Permanent(ownCreature)),
            )
        ).isSuccess shouldBe false
        driver.state.getEntity(spear)?.get<AttachedToComponent>().shouldBeNull()
    }

    test("the {1} ability works while unequipped and removes both protections only from opponents") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val ownHexproof = driver.putCreatureOnBattlefield(player, "Sagu Mauler")
        val ownIndestructible = driver.putCreatureOnBattlefield(player, "Darksteel Gargoyle")
        val opponentHexproof = driver.putCreatureOnBattlefield(opponent, "Sagu Mauler")
        val opponentIndestructible = driver.putCreatureOnBattlefield(opponent, "Darksteel Gargoyle")
        val spear = driver.putPermanentOnBattlefield(player, "Shadowspear")

        driver.giveColorlessMana(player, 1)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = spear,
                abilityId = abilityId(driver, equip = false),
                targets = emptyList(),
            )
        ).isSuccess shouldBe true
        driver.bothPass().isSuccess shouldBe true

        driver.state.projectedState.hasKeyword(opponentHexproof, Keyword.HEXPROOF) shouldBe false
        driver.state.projectedState.hasKeyword(opponentIndestructible, Keyword.INDESTRUCTIBLE) shouldBe false
        driver.state.projectedState.hasKeyword(ownHexproof, Keyword.HEXPROOF) shouldBe true
        driver.state.projectedState.hasKeyword(ownIndestructible, Keyword.INDESTRUCTIBLE) shouldBe true
        driver.state.getEntity(spear)?.get<AttachedToComponent>() shouldBe null
    }

    test("protection removal lasts through the end of turn and does not affect later permanents") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val existingHexproof = driver.putCreatureOnBattlefield(opponent, "Sagu Mauler")
        val existingIndestructible = driver.putCreatureOnBattlefield(opponent, "Darksteel Gargoyle")
        val spear = driver.putPermanentOnBattlefield(player, "Shadowspear")

        driver.giveColorlessMana(player, 1)
        driver.submit(
            ActivateAbility(
                playerId = player,
                sourceId = spear,
                abilityId = abilityId(driver, equip = false),
                targets = emptyList(),
            )
        ).isSuccess shouldBe true
        driver.bothPass().isSuccess shouldBe true

        val laterHexproof = driver.putCreatureOnBattlefield(opponent, "Sagu Mauler")
        val laterIndestructible = driver.putCreatureOnBattlefield(opponent, "Darksteel Gargoyle")
        driver.state.projectedState.hasKeyword(existingHexproof, Keyword.HEXPROOF) shouldBe false
        driver.state.projectedState.hasKeyword(existingIndestructible, Keyword.INDESTRUCTIBLE) shouldBe false
        driver.state.projectedState.hasKeyword(laterHexproof, Keyword.HEXPROOF) shouldBe true
        driver.state.projectedState.hasKeyword(laterIndestructible, Keyword.INDESTRUCTIBLE) shouldBe true

        driver.passPriorityUntil(Step.UPKEEP)
        driver.state.projectedState.hasKeyword(existingHexproof, Keyword.HEXPROOF) shouldBe true
        driver.state.projectedState.hasKeyword(existingIndestructible, Keyword.INDESTRUCTIBLE) shouldBe true
        driver.state.projectedState.hasKeyword(laterHexproof, Keyword.HEXPROOF) shouldBe true
        driver.state.projectedState.hasKeyword(laterIndestructible, Keyword.INDESTRUCTIBLE) shouldBe true
    }

    test("lifelink from the equipped creature gains life for combat damage") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val attacker = driver.putCreatureOnBattlefield(player, "Centaur Courser")
        val blocker = driver.putCreatureOnBattlefield(opponent, "Savannah Lions")
        val spear = driver.putPermanentOnBattlefield(player, "Shadowspear")

        driver.removeSummoningSickness(attacker)
        equip(driver, player, spear, attacker)
        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(player, listOf(attacker), opponent).isSuccess shouldBe true
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(opponent, mapOf(blocker to listOf(attacker))).isSuccess shouldBe true
        driver.passPriorityUntil(Step.COMBAT_DAMAGE)
        if (driver.pendingDecision != null) {
            driver.confirmCombatDamage().isSuccess shouldBe true
        }
        while (driver.stackSize > 0) {
            driver.bothPass().isSuccess shouldBe true
        }

        driver.getLifeTotal(opponent) shouldBe 17
        driver.getLifeTotal(player) shouldBe 24
    }
})
