package com.wingedsheep.gym

import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.components.player.KnownInformationAcquisitionReason
import com.wingedsheep.engine.state.components.player.KnownInformationAudience
import com.wingedsheep.engine.state.components.player.KnownInformationLedgerComponentV1
import com.wingedsheep.engine.mechanics.KnownInformationLedger
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.service.SnapshotCodec
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Gym-level proof that state-owned known information participates in snapshot/restore. */
class KnownInformationLedgerSnapshotTest : FunSpec({
    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    fun config() = GameConfig(
        players = listOf(
            PlayerConfig("Alice", Deck.of("Mountain" to 60)),
            PlayerConfig("Bob", Deck.of("Mountain" to 60)),
        ),
        skipMulligans = true,
        startingPlayerIndex = 0,
    )

    test("HISTB-16 snapshot and restore preserve the state-owned ledger exactly") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(config())

        val playerId = environment.playerIds.first()
        val cardId = environment.state.getHand(playerId).first()
        val knownState = KnownInformationLedger.recordCards(
            state = environment.state,
            cardIds = listOf(cardId),
            perspectivePlayerIds = listOf(playerId),
            audience = KnownInformationAudience.PERSPECTIVE_PRIVATE,
            acquisitionReason = KnownInformationAcquisitionReason.PRIVATE_HAND_LOOK,
        )
        val committedState = KnownInformationLedger.applyAfterAction(
            beforeState = environment.state,
            result = ExecutionResult.success(knownState),
            cardRegistry = cardRegistry,
        ).state
        environment.restore(
            state = committedState,
            playerIds = environment.playerIds,
            stepCount = environment.stepCount,
            maxSteps = environment.maxSteps,
        )
        val expected = KnownInformationLedger.forPlayer(environment.state, playerId)
        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)

        val withoutLedger = environment.state.updateEntity(playerId) {
            it.without<KnownInformationLedgerComponentV1>()
        }
        environment.restore(
            state = withoutLedger,
            playerIds = environment.playerIds,
            stepCount = environment.stepCount,
            maxSteps = environment.maxSteps,
        )
        gym.restore(codec, handle)

        KnownInformationLedger.forPlayer(environment.state, playerId) shouldBe expected
    }
})
