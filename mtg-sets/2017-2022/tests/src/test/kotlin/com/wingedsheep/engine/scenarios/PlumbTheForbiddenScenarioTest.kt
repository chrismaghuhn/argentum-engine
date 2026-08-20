package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.state.components.identity.CopyOfComponent
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.stx.cards.PlumbTheForbidden
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AdditionalCostPayment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * Behavioral coverage for Plumb the Forbidden (STX #81).
 *
 * Oracle, verified against Scryfall STX #81 and Wizards' Strixhaven release notes:
 * "As an additional cost to cast this spell, you may sacrifice one or more creatures. When you
 * do, copy this spell for each creature sacrificed this way. You draw a card and lose 1 life."
 *
 * Every variable sacrifice is supplied as an explicit payment choice. The action-domain test
 * proves that the controller's filtered candidate pool and the explicit zero choice are visible
 * before the cast is submitted.
 */
class PlumbTheForbiddenScenarioTest : FunSpec({

    val sacrificeCreature = CardDefinition.creature(
        name = "Plumb Sacrifice Probe",
        manaCost = ManaCost.parse("{1}"),
        subtypes = setOf(Subtype.HUMAN),
        power = 1,
        toughness = 1,
    )

    val nonCreaturePermanent = CardDefinition.artifact(
        name = "Plumb Noncreature Probe",
        manaCost = ManaCost.parse("{1}"),
    )

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + sacrificeCreature + nonCreaturePermanent + PlumbTheForbidden)
        driver.initMirrorMatch(Deck.of("Swamp" to 40), skipMulligans = true)
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun castPlumb(
        driver: GameTestDriver,
        player: EntityId,
        sacrificedCreatures: List<EntityId>,
    ): EntityId {
        val card = driver.putCardInHand(player, PlumbTheForbidden.name)
        driver.giveMana(player, Color.BLACK, 2)
        driver.submit(
            CastSpell(
                playerId = player,
                cardId = card,
                additionalCostPayment = AdditionalCostPayment(
                    variableCostPermanents = sacrificedCreatures,
                ),
                paymentStrategy = PaymentStrategy.FromPool,
            )
        ).error shouldBe null
        return card
    }

    fun resolveAllStackObjects(driver: GameTestDriver) {
        var passes = 0
        while (driver.state.stack.isNotEmpty()) {
            driver.bothPass()
            if (++passes > 30) error("Stack did not drain")
        }
    }

    test("zero sacrifices is an explicit choice and draws once while losing one life") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val handBeforeCast = driver.getHandSize(player)

        castPlumb(driver, player, sacrificedCreatures = emptyList())
        driver.state.stack.count { entityId ->
            driver.state.getEntity(entityId)?.has<TriggeredAbilityOnStackComponent>() == true
        } shouldBe 0
        resolveAllStackObjects(driver)

        (driver.getHandSize(player) - handBeforeCast) shouldBe 1
        driver.getLifeTotal(player) shouldBe 19
    }

    test("one explicitly selected sacrifice produces one copy, one extra draw, and one extra life loss") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sacrifice = driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        val handBeforeCast = driver.getHandSize(player)

        castPlumb(driver, player, sacrificedCreatures = listOf(sacrifice))
        driver.state.stack.count { entityId ->
            driver.state.getEntity(entityId)?.has<TriggeredAbilityOnStackComponent>() == true
        } shouldBe 1
        driver.bothPass()

        driver.state.stack.count { entityId ->
            driver.state.getEntity(entityId)?.has<CopyOfComponent>() == true
        } shouldBe 1
        resolveAllStackObjects(driver)

        (driver.getHandSize(player) - handBeforeCast) shouldBe 2
        driver.getLifeTotal(player) shouldBe 18
    }

    test("three sacrifices freeze the copy count before a later creature enters") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sacrifices = List(3) { driver.putCreatureOnBattlefield(player, sacrificeCreature.name) }
        val handBeforeCast = driver.getHandSize(player)

        castPlumb(driver, player, sacrificedCreatures = sacrifices)
        driver.state.stack.count { entityId ->
            driver.state.getEntity(entityId)?.has<CopyOfComponent>() == true
        } shouldBe 0
        driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        driver.bothPass()

        driver.state.stack.count { entityId ->
            driver.state.getEntity(entityId)?.has<CopyOfComponent>() == true
        } shouldBe 3
        resolveAllStackObjects(driver)

        (driver.getHandSize(player) - handBeforeCast) shouldBe 4
        driver.getLifeTotal(player) shouldBe 16
    }

    test("cast action exposes only controller creatures and the explicit zero-to-all domain") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val opponent = driver.getOpponent(player)
        val sacrificeA = driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        val sacrificeB = driver.putCreatureOnBattlefield(player, sacrificeCreature.name)
        driver.putPermanentOnBattlefield(player, nonCreaturePermanent.name)
        driver.putCreatureOnBattlefield(opponent, sacrificeCreature.name)
        val card = driver.putCardInHand(player, PlumbTheForbidden.name)
        driver.giveMana(player, Color.BLACK, 2)

        val casts = driver.legalActions(player)
            .filter { action ->
                val cast = action.action as? CastSpell
                cast?.cardId == card
            }
        casts.size shouldBe 1
        val costInfo = casts.single().additionalCostInfo.shouldNotBeNull()
        costInfo.costType shouldBe "VariableSacrifice"
        costInfo.validSacrificeTargets shouldBe listOf(sacrificeA, sacrificeB)
        costInfo.sacrificeCount shouldBe 0
        costInfo.sacrificeMinCount shouldBe 0
        costInfo.sacrificeMaxCount shouldBe 2
    }

    test("selected sacrifice payload is retained on the original and every copied spell") {
        val driver = createDriver()
        val player = driver.activePlayer!!
        val sacrifices = List(2) { driver.putCreatureOnBattlefield(player, sacrificeCreature.name) }
        val spellId = castPlumb(driver, player, sacrificedCreatures = sacrifices)

        driver.state.getEntity(spellId)!!.get<SpellOnStackComponent>()!!
            .sacrificedPermanents.map { snapshot -> snapshot.entityId } shouldBe sacrifices

        driver.bothPass()

        val copies = driver.state.stack.filter { entityId ->
            driver.state.getEntity(entityId)?.has<CopyOfComponent>() == true
        }
        copies.size shouldBe 2
        copies.forEach { copyId ->
            driver.state.getEntity(copyId)!!.get<SpellOnStackComponent>()!!
                .sacrificedPermanents.map { snapshot -> snapshot.entityId } shouldBe sacrifices
        }
    }
})
