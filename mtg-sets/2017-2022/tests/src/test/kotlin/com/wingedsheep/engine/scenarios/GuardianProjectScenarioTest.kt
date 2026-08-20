package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.AbilityFizzledEvent
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.handlers.ConditionEvaluator
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.effects.ZoneTransitionService
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.LastKnownPermanentComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.TokenComponent
import com.wingedsheep.engine.state.components.stack.EntitySnapshot
import com.wingedsheep.engine.state.components.stack.TriggeredAbilityOnStackComponent
import com.wingedsheep.engine.state.components.stack.captureEntitySnapshots
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.mtg.sets.definitions.snc.cards.WitnessProtection
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.conditions.TriggeringEntityNameNotSharedWithControlledCreatureOrGraveyard
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** Focused behavioral evidence for Guardian Project's Oracle clauses. */
class GuardianProjectScenarioTest : FunSpec({

    fun newDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all + WitnessProtection)
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40))
        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        return driver
    }

    fun castCreatureAndResolveSpell(
        driver: GameTestDriver,
        player: com.wingedsheep.sdk.model.EntityId,
        name: String,
    ) {
        val creature = driver.putCardInHand(player, name)
        driver.giveMana(player, Color.GREEN, amount = 2)
        driver.castSpell(player, creature).isSuccess shouldBe true
        driver.bothPass()
    }

    fun castFaceDownCreatureAndResolveSpell(
        driver: GameTestDriver,
        player: com.wingedsheep.sdk.model.EntityId,
    ) {
        val creature = driver.putCardInHand(player, "Morph Test Creature")
        driver.giveColorlessMana(player, amount = 3)
        driver.submit(
            CastSpell(
                playerId = player,
                cardId = creature,
                castFaceDown = true,
                paymentStrategy = PaymentStrategy.FromPool,
            )
        ).isSuccess shouldBe true
        driver.bothPass()
        driver.bothPass()
    }

    test("a unique nontoken creature entering draws one card") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast + 1
    }

    test("a nontoken creature sharing a name with another controlled creature does not trigger") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")

        driver.stackSize shouldBe 0
        driver.getHandSize(player) shouldBe handBeforeCast
    }

    test("a nontoken creature sharing a name with a creature card in its controller's graveyard does not trigger") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        driver.putCardInGraveyard(player, "Grizzly Bears")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")

        driver.stackSize shouldBe 0
        driver.getHandSize(player) shouldBe handBeforeCast
    }

    test("an opponent's creature with the same name does not suppress the draw") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        driver.putCreatureOnBattlefield(driver.player2, "Grizzly Bears")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast + 1
    }

    test("an opponent's graveyard card with the same name does not suppress the draw") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        driver.putCardInGraveyard(driver.player2, "Grizzly Bears")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast + 1
    }

    test("a same-name creature appearing after the trigger makes the ability fizzle") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast
        driver.events.filterIsInstance<AbilityFizzledEvent>().last().reason shouldBe
            "Intervening-if condition is no longer true"
    }

    test("a same-name creature appearing then disappearing allows the ability to resolve") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        val existing = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        driver.replaceState(
            driver.state.moveToZone(
                existing,
                ZoneKey(player, Zone.BATTLEFIELD),
                ZoneKey(player, Zone.EXILE),
            )
        )
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast + 1
    }

    test("a same-name graveyard card appearing after the trigger makes the ability fizzle") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        driver.putCardInGraveyard(player, "Grizzly Bears")
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast
        driver.events.filterIsInstance<AbilityFizzledEvent>().last().reason shouldBe
            "Intervening-if condition is no longer true"
    }

    test("a same-name graveyard card appearing then disappearing allows the ability to resolve") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        val existing = driver.putCardInGraveyard(player, "Grizzly Bears")
        driver.replaceState(
            driver.state.moveToZone(
                existing,
                ZoneKey(player, Zone.GRAVEYARD),
                ZoneKey(player, Zone.EXILE),
            )
        )
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast + 1
    }

    test("the name comparison preserves projected names") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val existing = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val aura = driver.putCardInHand(player, "Witness Protection")
        driver.giveMana(player, Color.BLUE)
        driver.castSpell(player, aura, targets = listOf(existing)).isSuccess shouldBe true
        driver.bothPass()

        driver.state.projectedState.getName(existing) shouldBe "Legitimate Businessperson"
        val handBeforeCast = driver.getHandSize(player)
        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast + 1
    }

    test("the trigger keeps its original name after a later incarnation becomes face-down") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val existing = driver.putCreatureOnBattlefield(player, "Grizzly Bears")
        val existingAura = driver.putCardInHand(player, "Witness Protection")
        driver.giveMana(player, Color.BLUE)
        driver.castSpell(player, existingAura, targets = listOf(existing)).isSuccess shouldBe true
        driver.bothPass()

        val handBeforeTrigger = driver.getHandSize(player)
        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        val triggeringCreature = driver.getPermanents(player).single { entityId ->
            entityId != existing &&
                driver.state.getEntity(entityId)?.get<CardComponent>()?.name == "Grizzly Bears"
        }

        val exiled = ZoneTransitionService.moveToZone(driver.state, triggeringCreature, Zone.EXILE).state
        val returned = ZoneTransitionService.moveToZone(exiled, triggeringCreature, Zone.BATTLEFIELD).state
        val returnedFaceDown = returned.updateEntity(triggeringCreature) { it.with(FaceDownComponent) }
        returnedFaceDown.projectedState.isFaceDown(triggeringCreature) shouldBe true
        val secondLeave = ZoneTransitionService.moveToZone(
            returnedFaceDown,
            triggeringCreature,
            Zone.EXILE,
        ).state
        val existingAuraRemoved = ZoneTransitionService.moveToZone(
            secondLeave,
            existingAura,
            Zone.GRAVEYARD,
        ).state
        driver.replaceState(existingAuraRemoved)

        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeTrigger
        driver.events.filterIsInstance<AbilityFizzledEvent>().last().reason shouldBe
            "Intervening-if condition is no longer true"
    }

    test("a missing battlefield entry identity fails the name check closed") {
        val driver = newDriver()
        val player = driver.player1
        val triggeringCreature = driver.putCreatureOnBattlefield(player, "Grizzly Bears")

        ConditionEvaluator().evaluate(
            driver.state,
            TriggeringEntityNameNotSharedWithControlledCreatureOrGraveyard,
            EffectContext(
                sourceId = null,
                controllerId = player,
                triggeringEntityId = triggeringCreature,
            )
        ) shouldBe false

        ConditionEvaluator().evaluate(
            driver.state,
            TriggeringEntityNameNotSharedWithControlledCreatureOrGraveyard,
            EffectContext(
                sourceId = null,
                controllerId = player,
                triggeringEntityId = triggeringCreature,
                triggeringEntityEntryTimestamp = 42L,
                triggeringEntityName = "Different Name",
                triggeringEntityNameKnown = true,
            )
        ) shouldBe false
    }

    test("two face-down creatures do not share a name") {
        val driver = newDriver()
        val player = driver.activePlayer!!
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val handBeforeCast = driver.getHandSize(player)

        castFaceDownCreatureAndResolveSpell(driver, player)
        castFaceDownCreatureAndResolveSpell(driver, player)

        driver.getHandSize(player) shouldBe handBeforeCast + 2
    }

    test("a face-down creature entering and leaving the battlefield has nameless LKI") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val handBeforeCast = driver.getHandSize(player)

        val creature = driver.putCardInHand(player, "Morph Test Creature")
        driver.giveColorlessMana(player, amount = 3)
        driver.submit(
            CastSpell(
                playerId = player,
                cardId = creature,
                castFaceDown = true,
                paymentStrategy = PaymentStrategy.FromPool,
            )
        ).isSuccess shouldBe true
        driver.bothPass()
        val faceDownCreature = driver.getPermanents(player).single { entityId ->
            driver.state.getEntity(entityId)?.has<FaceDownComponent>() == true
        }
        EntitySnapshot.fromProjection(faceDownCreature, driver.state).name shouldBe null
        captureEntitySnapshots(listOf(faceDownCreature), driver.state).single().name shouldBe null
        val transition = ZoneTransitionService.moveToZone(driver.state, faceDownCreature, Zone.GRAVEYARD)
        driver.replaceState(transition.state)

        driver.state.getEntity(faceDownCreature)
            ?.get<LastKnownPermanentComponent>()?.snapshot?.name shouldBe null
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast + 1
    }

    test("a same-name creature that leaves and returns is another object for the trigger") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val handBeforeCast = driver.getHandSize(player)

        castCreatureAndResolveSpell(driver, player, "Grizzly Bears")
        val creature = driver.findPermanent(player, "Grizzly Bears")!!
        val trigger = driver.state.stack.mapNotNull { stackEntityId ->
            driver.state.getEntity(stackEntityId)?.get<TriggeredAbilityOnStackComponent>()
        }.single()
        val originalEntryTimestamp = trigger.triggeringEntityEntryTimestamp.shouldNotBeNull()
        val exiled = ZoneTransitionService.moveToZone(driver.state, creature, Zone.EXILE).state
        val returned = ZoneTransitionService.moveToZone(exiled, creature, Zone.BATTLEFIELD).state
        returned.getEntity(creature)
            ?.get<com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent>()
            ?.timestamp shouldNotBe originalEntryTimestamp
        driver.replaceState(returned)
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast
        driver.events.filterIsInstance<AbilityFizzledEvent>().last().reason shouldBe
            "Intervening-if condition is no longer true"
    }

    test("a token creature entering does not trigger") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val dissenter = driver.putCreatureOnBattlefield(player, "Doomed Dissenter")
        val bolt = driver.putCardInHand(player, "Lightning Bolt")
        val handBeforeCast = driver.getHandSize(player)
        driver.giveMana(player, Color.RED)

        driver.castSpell(player, bolt, targets = listOf(dissenter)).isSuccess shouldBe true
        driver.bothPass()
        driver.bothPass()

        driver.stackSize shouldBe 0
        driver.getHandSize(player) shouldBe handBeforeCast - 1
        val token = driver.getPermanents(player).singleOrNull { entityId ->
            driver.state.getEntity(entityId)?.get<CardComponent>()?.name == "Zombie Token"
        }
        token.shouldNotBeNull()
        driver.state.getEntity(token)?.get<TokenComponent>().shouldNotBeNull()
    }

    test("the intervening name check is repeated when a unique entering creature moves to the graveyard") {
        val driver = newDriver()
        val player = driver.player1
        driver.putPermanentOnBattlefield(player, "Guardian Project")
        val creature = driver.putCardInHand(player, "Grizzly Bears")
        val handBeforeCast = driver.getHandSize(player)
        driver.giveMana(player, Color.GREEN, amount = 2)

        driver.castSpell(player, creature).isSuccess shouldBe true
        driver.bothPass()
        driver.moveToGraveyard(driver.findPermanent(player, "Grizzly Bears")!!)
        driver.bothPass()

        driver.getHandSize(player) shouldBe handBeforeCast - 1
        driver.events.filterIsInstance<AbilityFizzledEvent>().last().reason shouldBe
            "Intervening-if condition is no longer true"
        driver.state.getZone(ZoneKey(player, Zone.GRAVEYARD)).size shouldBe 1
    }
})
