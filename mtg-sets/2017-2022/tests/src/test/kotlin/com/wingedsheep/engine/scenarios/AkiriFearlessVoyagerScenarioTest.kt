package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.state.components.battlefield.AttachedToComponent
import com.wingedsheep.engine.state.components.battlefield.AttachmentsComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.serialization.CardLoader
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Scenario coverage for Akiri, Fearless Voyager (ZNR #220).
 *
 * The activated ability intentionally exposes a structured host-first choice:
 * choose a creature you control with an attached Equipment, then choose one
 * Equipment attached to that stored host. Both choices are resolution-time,
 * non-targeting decisions.
 */
class AkiriFearlessVoyagerScenarioTest : ScenarioTestBase() {

    private companion object {
        const val AKIRI = "Akiri, Fearless Voyager"
        const val HOST = "Grizzly Bears"
        const val SECOND_HOST = "Hill Giant"
        const val UNEQUIPPED_HOST = "Centaur Courser"
        const val EQUIPMENT = "Spiked Ripsaw"
        const val SECOND_EQUIPMENT = "Bonesplitter"
    }

    private fun GameTestDriver.attachEquipment(equipmentId: EntityId, hostId: EntityId) {
        var updated = state.updateEntity(equipmentId) { it.with(AttachedToComponent(hostId)) }
        updated = updated.updateEntity(hostId) { container ->
            val existing = container.get<AttachmentsComponent>()?.attachedIds.orEmpty()
            container.with(AttachmentsComponent(existing + equipmentId))
        }
        replaceState(updated)
    }

    private fun GameTestDriver.drainAkiriStack(maxIterations: Int = 100) {
        var iterations = 0
        while ((stackSize > 0 || pendingDecision != null) && iterations < maxIterations) {
            if (pendingDecision != null) {
                autoResolveDecision()
            } else {
                passPriority(priorityPlayer ?: error("Akiri stack has no priority owner"))
            }
            iterations++
        }
        stackSize shouldBe 0
        pendingDecision shouldBe null
    }

    private fun akiriAbilityId() =
        cardRegistry.getCard(AKIRI)!!.script.activatedAbilities.single().id

    private fun activateAkiri(game: ScenarioTestBase.TestGame) =
        game.execute(ActivateAbility(game.player1Id, game.findPermanent(AKIRI)!!, akiriAbilityId()))

    private fun ScenarioTestBase.TestGame.resolveAkiriYes(
        host: String = HOST,
        equipment: String = EQUIPMENT,
    ) {
        val may = getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
        withClue("Akiri's may choice is controlled by the activating player") {
            may.playerId shouldBe player1Id
        }
        answerYesNo(true).error shouldBe null
        withClue("the host decision does not open a normal priority window") {
            getLegalActions(1) shouldBe emptyList()
            getLegalActions(2) shouldBe emptyList()
        }

        val hostId = findPermanent(host)!!
        val hostDecision = getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
        withClue("host selection is a modal, non-targeting collection choice") {
            hostDecision.useTargetingUI shouldBe false
            hostDecision.options shouldContain hostId
        }
        selectCards(listOf(hostId)).error shouldBe null
        withClue("the Equipment decision does not open a normal priority window") {
            getLegalActions(1) shouldBe emptyList()
            getLegalActions(2) shouldBe emptyList()
        }

        val equipmentId = findPermanent(equipment)!!
        val equipmentDecision = getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
        withClue("Equipment selection is a modal, non-targeting collection choice") {
            equipmentDecision.useTargetingUI shouldBe false
            equipmentDecision.options shouldContain equipmentId
        }
        selectCards(listOf(equipmentId)).error shouldBe null
        withClue("unattach, tap, and indestructible complete in the same resolution") {
            hasPendingDecision() shouldBe false
        }
    }

    init {
        context("AKIRI-01 through AKIRI-10 and AKIRI-23 — equipped attack trigger") {

            test("AKIRI-01: one equipped attacker draws exactly one card") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                    .build()

                game.declareAttackers(mapOf(HOST to 2)).error shouldBe null
                game.resolveStack()

                game.librarySize(1) shouldBe 0
            }

            test("AKIRI-02: multiple equipped creatures attacking one player trigger once") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withCardAttachedTo(1, SECOND_EQUIPMENT, HOST)
                    .withCardOnBattlefield(1, SECOND_HOST, summoningSickness = false)
                    .withCardAttachedTo(1, "Loxodon Warhammer", SECOND_HOST)
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                    .build()

                game.declareAttackers(mapOf(HOST to 2, SECOND_HOST to 2)).error shouldBe null
                game.resolveStack()

                withClue("multiple equipped attackers against one player produce one card") {
                    game.librarySize(1) shouldBe 0
                }
            }

            test("AKIRI-03: multiple Equipment on one attacker still trigger once") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withCardAttachedTo(1, SECOND_EQUIPMENT, HOST)
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                    .build()

                game.declareAttackers(mapOf(HOST to 2)).error shouldBe null
                game.resolveStack()

                game.librarySize(1) shouldBe 0
            }

            test("AKIRI-04: an equipped and an unequipped attacker produce one trigger") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withCardOnBattlefield(1, SECOND_HOST, summoningSickness = false)
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                    .build()

                game.declareAttackers(mapOf(HOST to 2, SECOND_HOST to 2)).error shouldBe null
                game.resolveStack()

                game.librarySize(1) shouldBe 0
            }

            test("AKIRI-05: no equipped attacker produces no trigger") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                    .build()

                game.declareAttackers(mapOf(HOST to 2)).error shouldBe null
                game.resolveStack()

                game.librarySize(1) shouldBe 1
            }

            test("AKIRI-06: equipped attackers attacking two players draw once per defending player") {
                val driver = GameTestDriver()
                driver.registerCards(TestCards.all)
                val players = driver.initMultiplayer(
                    decks = List(3) { Deck.of("Plains" to 40) },
                    skipMulligans = true,
                    startingPlayer = 0,
                )
                val attacker = players[0]
                val firstDefender = players[1]
                val secondDefender = players[2]

                driver.putCreatureOnBattlefield(attacker, AKIRI)
                val firstHost = driver.putCreatureOnBattlefield(attacker, HOST)
                val secondHost = driver.putCreatureOnBattlefield(attacker, SECOND_HOST)
                val firstEquipment = driver.putPermanentOnBattlefield(attacker, EQUIPMENT)
                val secondEquipment = driver.putPermanentOnBattlefield(attacker, SECOND_EQUIPMENT)
                driver.attachEquipment(firstEquipment, firstHost)
                driver.attachEquipment(secondEquipment, secondHost)
                driver.removeSummoningSickness(firstHost)
                driver.removeSummoningSickness(secondHost)
                driver.putCardOnTopOfLibrary(attacker, "Plains")
                driver.putCardOnTopOfLibrary(attacker, "Plains")

                driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
                val libraryBefore = driver.state.getZone(ZoneKey(attacker, Zone.LIBRARY)).size
                driver.declareAttackers(
                    attacker,
                    mapOf(firstHost to firstDefender, secondHost to secondDefender),
                ).error shouldBe null
                driver.drainAkiriStack()

                driver.state.getZone(ZoneKey(attacker, Zone.LIBRARY)).size shouldBe libraryBefore - 2
            }

            test("AKIRI-08: a player attack plus a planeswalker attack draws once") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withCardOnBattlefield(1, SECOND_HOST, summoningSickness = false)
                    .withCardAttachedTo(1, SECOND_EQUIPMENT, SECOND_HOST)
                    .withCardOnBattlefield(2, "Liliana of the Veil")
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                    .build()

                game.declareAttackersWithPermanentTargets(
                    playerAttackers = mapOf(HOST to 2),
                    permanentAttackers = mapOf(SECOND_HOST to "Liliana of the Veil"),
                ).error shouldBe null
                game.resolveStack()

                game.librarySize(1) shouldBe 0
            }

            test("AKIRI-09: the attack trigger resolves after the Equipment is detached") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                    .build()

                game.declareAttackers(mapOf(HOST to 2)).error shouldBe null
                val hostId = game.findPermanent(HOST)!!
                val equipmentId = game.findPermanent(EQUIPMENT)!!
                game.state = game.state
                    .updateEntity(equipmentId) { it.without<AttachedToComponent>() }
                    .updateEntity(hostId) { it.without<AttachmentsComponent>() }
                game.resolveStack()

                game.librarySize(1) shouldBe 0
            }

            test("AKIRI-10: the attack trigger resolves after Akiri leaves the battlefield") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                    .build()

                game.declareAttackers(mapOf(HOST to 2)).error shouldBe null
                val akiriId = game.findPermanent(AKIRI)!!
                game.state = game.state
                    .withoutEntity(akiriId)
                    .removeFromZone(ZoneKey(game.player1Id, Zone.BATTLEFIELD), akiriId)
                game.resolveStack()

                game.librarySize(1) shouldBe 0
            }

            test("AKIRI-23: the attack trigger resolves after Equipment leaves the battlefield") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                    .build()

                game.declareAttackers(mapOf(HOST to 2)).error shouldBe null
                val equipmentId = game.findPermanent(EQUIPMENT)!!
                game.state = game.state
                    .withoutEntity(equipmentId)
                    .removeFromZone(ZoneKey(game.player1Id, Zone.BATTLEFIELD), equipmentId)

                game.resolveStack()

                game.isOnBattlefield(EQUIPMENT) shouldBe false
                game.librarySize(1) shouldBe 0
            }

            test("AKIRI-07: attacking a planeswalker with an equipped creature does not trigger") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withCardOnBattlefield(2, "Liliana of the Veil")
                    .withCardInLibrary(1, "Plains")
                    .withActivePlayer(1)
                    .inPhase(Phase.COMBAT, Step.DECLARE_ATTACKERS)
                    .build()

                game.declareAttackersWithPermanentTargets(
                    permanentAttackers = mapOf(HOST to "Liliana of the Veil")
                ).error shouldBe null
                game.resolveStack()

                game.librarySize(1) shouldBe 1
            }
        }

        context("AKIRI-11 through AKIRI-24 — host-first activated ability") {

            test("AKIRI-11: paying {W} and choosing host plus Equipment detaches and protects the host") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                game.resolveStack()
                game.resolveAkiriYes()

                val hostId = game.findPermanent(HOST)!!
                val equipmentId = game.findPermanent(EQUIPMENT)!!
                game.state.getEntity(hostId)?.has<TappedComponent>() shouldBe true
                game.state.projectedState.hasKeyword(hostId, Keyword.INDESTRUCTIBLE) shouldBe true
                game.state.getEntity(equipmentId)?.get<AttachedToComponent>() shouldBe null
                game.state.getEntity(hostId)?.get<AttachmentsComponent>()?.attachedIds.orEmpty() shouldNotContain equipmentId
                game.isOnBattlefield(EQUIPMENT) shouldBe true
            }

            test("AKIRI-12: declining the may leaves the attachment and host unchanged") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                game.resolveStack()
                game.getPendingDecision().shouldBeInstanceOf<YesNoDecision>()
                game.answerYesNo(false).error shouldBe null
                game.resolveStack()

                val hostId = game.findPermanent(HOST)!!
                val equipmentId = game.findPermanent(EQUIPMENT)!!
                game.state.getEntity(hostId)?.has<TappedComponent>() shouldBe false
                game.state.projectedState.hasKeyword(hostId, Keyword.INDESTRUCTIBLE) shouldBe false
                game.state.getEntity(equipmentId)?.get<AttachedToComponent>()?.targetId shouldBe hostId
                game.state.getEntity(hostId)?.get<AttachmentsComponent>()?.attachedIds.orEmpty() shouldContain equipmentId
            }

            test("AKIRI-13: the player chooses which of two Equipment detaches") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withCardAttachedTo(1, SECOND_EQUIPMENT, HOST)
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                game.resolveStack()
                game.answerYesNo(true).error shouldBe null

                val hostId = game.findPermanent(HOST)!!
                val firstEquipmentId = game.findPermanent(EQUIPMENT)!!
                val secondEquipmentId = game.findPermanent(SECOND_EQUIPMENT)!!
                val hostDecision = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
                hostDecision.options.toSet() shouldBe setOf(hostId)
                game.selectCards(listOf(hostId)).error shouldBe null

                val equipmentDecision = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
                equipmentDecision.options.toSet() shouldBe setOf(firstEquipmentId, secondEquipmentId)
                game.selectCards(listOf(secondEquipmentId)).error shouldBe null

                game.state.getEntity(firstEquipmentId)?.get<AttachedToComponent>()?.targetId shouldBe hostId
                game.state.getEntity(secondEquipmentId)?.get<AttachedToComponent>() shouldBe null
                game.state.getEntity(hostId)?.get<AttachmentsComponent>()?.attachedIds.orEmpty() shouldContain firstEquipmentId
                game.state.getEntity(hostId)?.get<AttachmentsComponent>()?.attachedIds.orEmpty() shouldNotContain secondEquipmentId
            }

            test("AKIRI-14: an opponent-controlled Equipment attached to your creature is legal") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(2, EQUIPMENT, HOST)
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                game.resolveStack()
                game.answerYesNo(true).error shouldBe null

                val hostId = game.findPermanent(HOST)!!
                val equipmentId = game.findPermanent(EQUIPMENT)!!
                game.selectCards(listOf(hostId)).error shouldBe null
                val equipmentDecision = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
                equipmentDecision.options shouldContain equipmentId
                game.selectCards(listOf(equipmentId)).error shouldBe null

                game.state.getEntity(equipmentId)?.get<ControllerComponent>()?.playerId shouldBe game.player2Id
                game.state.getEntity(equipmentId)?.get<AttachedToComponent>() shouldBe null
            }

            test("AKIRI-15: an already-tapped host still gains indestructible") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, tapped = true, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                game.resolveStack()
                game.resolveAkiriYes()

                val hostId = game.findPermanent(HOST)!!
                game.state.getEntity(hostId)?.has<TappedComponent>() shouldBe true
                game.state.projectedState.hasKeyword(hostId, Keyword.INDESTRUCTIBLE) shouldBe true
            }

            test("AKIRI-18: the activated ability resolves after Akiri leaves the battlefield") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                val akiriId = game.findPermanent(AKIRI)!!
                game.state = game.state
                    .withoutEntity(akiriId)
                    .removeFromZone(ZoneKey(game.player1Id, Zone.BATTLEFIELD), akiriId)
                game.resolveStack()
                game.resolveAkiriYes()

                val hostId = game.findPermanent(HOST)!!
                game.state.getEntity(hostId)?.has<TappedComponent>() shouldBe true
                game.state.projectedState.hasKeyword(hostId, Keyword.INDESTRUCTIBLE) shouldBe true
            }

            test("AKIRI-STORED-HOST: the stored host receives the follow-up") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withCardOnBattlefield(1, SECOND_HOST, summoningSickness = false)
                    .withCardAttachedTo(1, SECOND_EQUIPMENT, SECOND_HOST)
                    .withCardOnBattlefield(1, UNEQUIPPED_HOST, summoningSickness = false)
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                game.resolveStack()
                game.answerYesNo(true).error shouldBe null

                val firstHostId = game.findPermanent(HOST)!!
                val secondHostId = game.findPermanent(SECOND_HOST)!!
                val hostDecision = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
                hostDecision.options.toSet() shouldBe setOf(firstHostId, secondHostId)
                game.selectCards(listOf(secondHostId)).error shouldBe null

                val selectedEquipmentId = game.findPermanent(SECOND_EQUIPMENT)!!
                val equipmentDecision = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
                equipmentDecision.options.toSet() shouldBe setOf(selectedEquipmentId)
                game.selectCards(listOf(selectedEquipmentId)).error shouldBe null

                game.state.getEntity(firstHostId)?.has<TappedComponent>() shouldBe false
                game.state.getEntity(secondHostId)?.has<TappedComponent>() shouldBe true
                game.state.projectedState.hasKeyword(firstHostId, Keyword.INDESTRUCTIBLE) shouldBe false
                game.state.projectedState.hasKeyword(secondHostId, Keyword.INDESTRUCTIBLE) shouldBe true
            }

            test("AKIRI-17: unattachment preserves the remaining attachment and permanent identity") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withCardAttachedTo(1, SECOND_EQUIPMENT, HOST)
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                game.resolveStack()
                game.answerYesNo(true).error shouldBe null
                val hostId = game.findPermanent(HOST)!!
                val selectedId = game.findPermanent(EQUIPMENT)!!
                val remainingId = game.findPermanent(SECOND_EQUIPMENT)!!
                game.selectCards(listOf(hostId)).error shouldBe null
                game.selectCards(listOf(selectedId)).error shouldBe null

                game.isOnBattlefield(HOST) shouldBe true
                game.isOnBattlefield(EQUIPMENT) shouldBe true
                game.state.getEntity(selectedId)?.get<AttachedToComponent>() shouldBe null
                game.state.getEntity(hostId)?.get<AttachmentsComponent>()?.attachedIds.orEmpty() shouldNotContain selectedId
                game.state.getEntity(remainingId)?.get<AttachedToComponent>()?.targetId shouldBe hostId
                game.state.getEntity(hostId)?.get<AttachmentsComponent>()?.attachedIds.orEmpty() shouldContain remainingId
                game.state.getEntity(selectedId)?.get<ControllerComponent>()?.playerId shouldBe game.player1Id
                game.state.getEntity(selectedId)?.get<OwnerComponent>()?.playerId shouldBe game.player1Id
            }

            test("AKIRI-16: indestructible expires at the end of the turn") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                game.resolveStack()
                game.resolveAkiriYes()

                val hostId = game.findPermanent(HOST)!!
                game.state.projectedState.hasKeyword(hostId, Keyword.INDESTRUCTIBLE) shouldBe true
                game.passUntilPhase(Phase.ENDING, Step.END)
                game.passUntilPhase(Phase.BEGINNING, Step.UPKEEP)

                game.state.projectedState.hasKeyword(hostId, Keyword.INDESTRUCTIBLE) shouldBe false
            }

            test("AKIRI-19: activation remains legal with no legal Equipment at resolution") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                game.resolveStack()

                game.hasPendingDecision() shouldBe false
                val hostId = game.findPermanent(HOST)!!
                game.state.getEntity(hostId)?.has<TappedComponent>() shouldBe false
                game.state.projectedState.hasKeyword(hostId, Keyword.INDESTRUCTIBLE) shouldBe false
            }

            test("AKIRI-19B: an activation with a legal host can become stale before resolution") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                game.state = game.state
                    .updateEntity(game.findPermanent(EQUIPMENT)!!) { it.without<AttachedToComponent>() }
                    .updateEntity(game.findPermanent(HOST)!!) { it.without<AttachmentsComponent>() }
                game.resolveStack()

                game.hasPendingDecision() shouldBe false
                val hostId = game.findPermanent(HOST)!!
                game.state.getEntity(hostId)?.has<TappedComponent>() shouldBe false
                game.state.projectedState.hasKeyword(hostId, Keyword.INDESTRUCTIBLE) shouldBe false
            }

            test("AKIRI-20: resolution domains exclude unequipped hosts, stale Equipment, and opposing hosts") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withCardOnBattlefield(1, UNEQUIPPED_HOST, summoningSickness = false)
                    .withCardOnBattlefield(1, "Loxodon Warhammer")
                    .withCardOnBattlefield(2, "Force of Nature", summoningSickness = false)
                    .withCardAttachedTo(2, "Bonesplitter", "Force of Nature")
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                game.resolveStack()
                game.answerYesNo(true).error shouldBe null

                val hostId = game.findPermanent(HOST)!!
                val hostDecision = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
                hostDecision.options.toSet() shouldBe setOf(hostId)
                game.selectCards(listOf(hostId)).error shouldBe null

                val equipmentId = game.findPermanent(EQUIPMENT)!!
                val equipmentDecision = game.getPendingDecision().shouldBeInstanceOf<SelectCardsDecision>()
                equipmentDecision.options.toSet() shouldBe setOf(equipmentId)
                equipmentDecision.options shouldNotContain game.findPermanent("Loxodon Warhammer")
                equipmentDecision.options shouldNotContain game.findPermanent("Bonesplitter")
                game.selectCards(listOf(equipmentId)).error shouldBe null
            }

            test("AKIRI-21: equipment choice completes unattach and follow-up without another decision") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                game.resolveStack()
                game.answerYesNo(true).error shouldBe null
                val hostId = game.findPermanent(HOST)!!
                game.selectCards(listOf(hostId)).error shouldBe null
                val equipmentId = game.findPermanent(EQUIPMENT)!!
                game.selectCards(listOf(equipmentId)).error shouldBe null

                game.hasPendingDecision() shouldBe false
                game.state.getEntity(hostId)?.has<TappedComponent>() shouldBe true
                game.state.projectedState.hasKeyword(hostId, Keyword.INDESTRUCTIBLE) shouldBe true
            }

            test("AKIRI-22: the registry definition round-trips through CardLoader") {
                val definition = cardRegistry.getCard(AKIRI)!!
                val encoded = CardLoader.toJson(definition)
                val decoded = CardLoader.fromJson(encoded)

                decoded.name shouldBe definition.name
                decoded.oracleText shouldBe definition.oracleText
                decoded.typeLine shouldBe definition.typeLine
                decoded.creatureStats shouldBe definition.creatureStats
                decoded.metadata shouldBe definition.metadata
                decoded.script.triggeredAbilities.size shouldBe 1
                decoded.script.activatedAbilities.size shouldBe 1
            }

            test("AKIRI-24: host-first continuation survives fork and serialization") {
                val game = scenario()
                    .withPlayers()
                    .withCardOnBattlefield(1, AKIRI)
                    .withCardOnBattlefield(1, HOST, summoningSickness = false)
                    .withCardAttachedTo(1, EQUIPMENT, HOST)
                    .withLandsOnBattlefield(1, "Plains", 1)
                    .withActivePlayer(1)
                    .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                    .build()

                activateAkiri(game).error shouldBe null
                game.resolveStack()
                game.answerYesNo(true).error shouldBe null

                val hostId = game.findPermanent(HOST)!!
                val equipmentId = game.findPermanent(EQUIPMENT)!!
                val json = Json {
                    serializersModule = engineSerializersModule
                    allowStructuredMapKeys = true
                }

                val hostPending = json.decodeFromString<GameState>(
                    json.encodeToString(GameState.serializer(), game.state)
                )
                game.state = hostPending
                game.getPendingDecision()
                    .shouldBeInstanceOf<SelectCardsDecision>()
                    .options shouldContain hostId
                game.selectCards(listOf(hostId)).error shouldBe null

                val forkedAtEquipment = game.state.copy()
                val serializedAtEquipment = json.decodeFromString<GameState>(
                    json.encodeToString(GameState.serializer(), game.state)
                )
                serializedAtEquipment.pendingDecision
                    .shouldBeInstanceOf<SelectCardsDecision>()
                    .options shouldContain equipmentId

                fun finishFrom(state: GameState): GameState {
                    game.state = state
                    game.selectCards(listOf(equipmentId)).error shouldBe null
                    return game.state
                }

                val serializedResult = finishFrom(serializedAtEquipment)
                val forkedResult = finishFrom(forkedAtEquipment)
                forkedResult shouldBe serializedResult

                game.state.getEntity(hostId)?.has<TappedComponent>() shouldBe true
                game.state.projectedState.hasKeyword(hostId, Keyword.INDESTRUCTIBLE) shouldBe true
                game.state.getEntity(equipmentId)?.get<AttachedToComponent>() shouldBe null
            }
        }
    }
}
