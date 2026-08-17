package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.legalactions.EnumerationMode
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.persistence.persistenceJson
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.StateDigest
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Card-specific A4/A6 boundary checks for the pinned Sync-04 behavior changes.
 *
 * The generic observation, digest, fingerprint, and replay suites remain the authoritative gates;
 * these checks make sure the changed card states cross the same serialization boundary without
 * changing their perspective-safe semantic identity.
 */
class Sync04CardSemanticParityTest : ScenarioTestBase() {

    private val legalActionEnumerator by lazy { LegalActionEnumerator.create(cardRegistry) }
    private val observationBuilder by lazy { ObservationBuilder(cardRegistry = cardRegistry) }

    private fun observation(state: GameState, perspective: EntityId): TrainingObservation {
        val actor = state.pendingDecision?.playerId ?: state.priorityPlayerId
        val legalActions = if (state.pendingDecision == null && actor == perspective) {
            legalActionEnumerator.enumerate(state, perspective, EnumerationMode.ACTIONS_ONLY)
        } else {
            emptyList()
        }
        return observationBuilder.build(state, perspective, legalActions).observation as TrainingObservation
    }

    private fun roundTrip(state: GameState): GameState {
        val encoded = persistenceJson.encodeToString(GameState.serializer(), state)
        return persistenceJson.decodeFromString(GameState.serializer(), encoded)
    }

    private fun assertSemanticParity(state: GameState, perspective: EntityId) {
        val restored = roundTrip(state)
        val liveObservation = observation(state, perspective)
        val restoredObservation = observation(restored, perspective)

        withClue("Sync-04 state must survive the persistence serializer") {
            ReplayFingerprint.of(state, CompactReplay.CURRENT_VERSION) shouldBe
                ReplayFingerprint.of(restored, CompactReplay.CURRENT_VERSION)
        }
        withClue("A4 StateDigest must survive the state round trip") {
            StateDigest.compute(liveObservation.copy(stateDigest = "")) shouldBe
                StateDigest.compute(restoredObservation.copy(stateDigest = ""))
            liveObservation.stateDigest shouldBe restoredObservation.stateDigest
        }
        withClue("the perspective-safe observation must remain structurally identical") {
            liveObservation.copy(stateDigest = "") shouldBe restoredObservation.copy(stateDigest = "")
        }
    }

    init {
        test("Inspiring Vantage preserves A4/A6 identity after entering untapped") {
            val game = scenario()
                .withPlayers("Player1", "Player2")
                .withCardInHand(1, "Inspiring Vantage")
                .withLandsOnBattlefield(1, "Plains", 1)
                .withLandsOnBattlefield(1, "Mountain", 1)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
                .build()

            game.execute(
                com.wingedsheep.engine.core.PlayLand(
                    game.player1Id,
                    game.findCardsInHand(1, "Inspiring Vantage").single(),
                )
            ).error shouldBe null

            assertSemanticParity(game.state, game.player1Id)
        }

        test("Annoyed Altisaur preserves its Cascade decision identity after serialization") {
            var builder = scenario()
                .withPlayers("Player1", "Player2")
                .withCardInHand(1, "Annoyed Altisaur")
                .withLandsOnBattlefield(1, "Forest", 7)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
            builder = builder.withCardInLibrary(1, "Mountain")
            builder = builder.withCardInLibrary(1, "Grizzly Bears")
            repeat(4) { builder = builder.withCardInLibrary(1, "Forest") }
            val game = builder.build()

            game.castSpell(1, "Annoyed Altisaur").error shouldBe null
            game.resolveStack()
            game.hasPendingDecision() shouldBe true

            assertSemanticParity(game.state, game.player1Id)
        }

        test("Cori Mountain Monastery preserves impulse permission identity after serialization") {
            var builder = scenario()
                .withPlayers("Player1", "Player2")
                .withCardOnBattlefield(1, "Cori Mountain Monastery")
                .withLandsOnBattlefield(1, "Mountain", 6)
                .withLandsOnBattlefield(1, "Forest", 2)
                .withActivePlayer(1)
                .inPhase(Phase.PRECOMBAT_MAIN, Step.PRECOMBAT_MAIN)
            builder = builder.withCardInLibrary(1, "Grizzly Bears")
            repeat(3) { builder = builder.withCardInLibrary(1, "Forest") }
            val game = builder.build()
            val cori = game.findPermanent("Cori Mountain Monastery")!!
            val impulse = cardRegistry.getCard("Cori Mountain Monastery")!!.activatedAbilities.last()

            game.execute(
                com.wingedsheep.engine.core.ActivateAbility(
                    playerId = game.player1Id,
                    sourceId = cori,
                    abilityId = impulse.id,
                )
            ).error shouldBe null
            if (game.hasPendingDecision()) game.submitManaSourcesAutoPay()
            game.resolveStack()
            game.isInExile(1, "Grizzly Bears") shouldBe true

            assertSemanticParity(game.state, game.player1Id)
        }
    }
}
