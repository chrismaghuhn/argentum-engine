package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.ManaSourcesSelectedResponse
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.mechanics.mana.ManaAbilityIdentity
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.sdk.core.Color
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.scripting.effects.MayPayManaEffect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession
import java.time.Instant

/** Exact replay proof for the pending PaymentPlanV3 carrier introduced in CompactReplay v6. */
class PendingPaymentReplayV6Test : ScenarioTestBase() {

    private val paymentSource = card("Replay Pending Payment Source") {
        typeLine = "Land"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(Color.GREEN)
            manaAbility = true
        }
    }

    private val paymentSpell = card("Replay Pending Payment Spell") {
        manaCost = "{0}"
        typeLine = "Sorcery"
        spell {
            effect = MayPayManaEffect(
                ManaCost.parse("{1}"),
                Effects.GainLife(1),
            )
        }
    }

    private fun mockWs(id: String): WebSocketSession =
        mockk(relaxed = true) { every { this@mockk.id } returns id }

    private fun requireApplied(result: GameSession.ActionResult) {
        check(result !is GameSession.ActionResult.Failure) {
            "action failed: ${(result as GameSession.ActionResult.Failure).reason}"
        }
    }

    private fun advanceToPriority(session: GameSession, playerId: EntityId) {
        repeat(100) {
            val state = session.getStateForTesting().shouldNotBeNull()
            if (state.priorityPlayerId == playerId && state.activePlayerId == playerId &&
                state.phase == Phase.PRECOMBAT_MAIN && state.pendingDecision == null && state.stack.isEmpty()
            ) return
            val priority = state.priorityPlayerId ?: error("Expected priority while preparing replay")
            requireApplied(session.executeAutoPass(priority))
        }
        error("Could not reach a clean priority window for $playerId")
    }

    private fun advanceUntilPending(
        session: GameSession,
        expected: Class<out com.wingedsheep.engine.core.PendingDecision>,
    ) {
        repeat(100) {
            val state = session.getStateForTesting().shouldNotBeNull()
            if (expected.isInstance(state.pendingDecision)) return
            val priority = state.priorityPlayerId ?: error("Expected priority while resolving replay fixture")
            requireApplied(session.executeAutoPass(priority))
        }
        error("Replay fixture did not reach ${expected.simpleName}")
    }

    private fun paymentPlan(sourceId: EntityId): PaymentPlanV3 = PaymentPlanV3(
        activations = listOf(
            SourceActivationV2(
                sourceId = sourceId,
                manaAbilityKey = ManaAbilityIdentity.key(paymentSource.activatedAbilities.single()),
                productionChoice = ProductionChoice(PaymentManaColor.GREEN),
                activationCostOrder = listOf(
                    ActivationCostComponentRefV1.DeterministicNonManaComponent(0),
                ),
            ),
        ),
        outerAllocation = listOf(
            PaymentAllocationV1(
                target = PaymentTargetV1.OuterCostUnit(symbolIndex = 0, unitIndexWithinSymbol = 0),
                resource = ManaResourceRefV1.ActivationOutputUnit(activationIndex = 0, outputIndex = 0),
            ),
        ),
    )

    private fun replayFrom(session: GameSession, snapshot: ReplayRecordingSnapshot): CompactReplay =
        CompactReplay(
            version = snapshot.version,
            gameId = session.sessionId,
            players = session.getPlayers().map { ReplayPlayerInfo(it.playerId.value, it.playerName) },
            startedAt = (snapshot.startedAt ?: Instant.now()).toString(),
            endedAt = Instant.now().toString(),
            winnerName = null,
            setup = snapshot.setup,
            actions = snapshot.actions,
            yields = snapshot.yields,
            pinnedCards = session.getPinnedCards(),
            checkpoints = ReplayCheckpointPolicy.withV3Tail(
                checkpoints = snapshot.checkpoints,
                actionCount = snapshot.actions.size,
                fingerprint = snapshot.fingerprint,
            ),
        )

    init {
        test("pending V3 payment survives v6 codec, content identity, and exact reconstruction") {
            cardRegistry.register(paymentSource)
            cardRegistry.register(paymentSpell)

            val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
            val playerOne = EntityId.of("pending-payment-v6-player-one")
            val playerTwo = EntityId.of("pending-payment-v6-player-two")
            val deck = mapOf(paymentSource.name to 1, paymentSpell.name to 1, "Mountain" to 5)
            session.addPlayer(PlayerSession(mockWs("pending-payment-v6-ws-one"), playerOne, "Alice"), deck)
            session.addPlayer(PlayerSession(mockWs("pending-payment-v6-ws-two"), playerTwo, "Bob"), deck)
            session.startGame()
            session.keepHand(playerOne)
            session.keepHand(playerTwo)

            val player = session.getStateForTesting().shouldNotBeNull().activePlayerId
                ?: error("Expected an active player")
            advanceToPriority(session, player)
            val beforeLand = session.getStateForTesting().shouldNotBeNull()
            val playLand = session.getLegalActions(player).map { it.action }
                .filterIsInstance<PlayLand>()
                .single { action ->
                    beforeLand.getEntity(action.cardId)
                        ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                        ?.name == paymentSource.name
                }
            requireApplied(session.executeAction(player, playLand))
            advanceToPriority(session, player)

            val beforeCast = session.getStateForTesting().shouldNotBeNull()
            val cast = session.getLegalActions(player).map { it.action }
                .filterIsInstance<CastSpell>()
                .single { action ->
                    beforeCast.getEntity(action.cardId)
                        ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                        ?.name == paymentSpell.name
                }
            requireApplied(session.executeAction(player, cast))
            advanceUntilPending(session, YesNoDecision::class.java)

            val yes = session.getStateForTesting().shouldNotBeNull().pendingDecision
                .shouldBeInstanceOf<YesNoDecision>()
            requireApplied(session.executeAction(player, SubmitDecision(player, YesNoResponse(yes.id, true))))
            val pending = session.getStateForTesting().shouldNotBeNull().pendingDecision
                .shouldBeInstanceOf<SelectManaSourcesDecision>()
            val sourceId = session.getStateForTesting().shouldNotBeNull().getBattlefield(player).single { id ->
                session.getStateForTesting().shouldNotBeNull().getEntity(id)
                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                    ?.name == paymentSource.name
            }
            val plan = paymentPlan(sourceId)
            requireApplied(
                session.executeAction(
                    player,
                    SubmitDecision(
                        player,
                        ManaSourcesSelectedResponse(pending.id, paymentPlan = plan),
                    ),
                ),
            )
            advanceToPriority(session, player)

            val snapshot = session.replayRecordingSnapshot().shouldNotBeNull()
            snapshot.version shouldBe CompactReplay.CURRENT_VERSION
            val replay = replayFrom(session, snapshot)
            replay.version shouldBe 6
            val decoded = ReplayCodec.decode(ReplayCodec.encode(replay))
            decoded shouldBe replay
            decoded.actions.filterIsInstance<SubmitDecision>()
                .mapNotNull { it.response as? ManaSourcesSelectedResponse }
                .single { it.paymentPlan != null }
                .paymentPlan shouldBe plan
            ReplayContentCanonicalizerV1.identity(decoded).shouldNotBeNull()

            ReplayReconstructor(cardRegistry, null).reconstruct(decoded).fidelity shouldBe ReplayFidelity.EXACT
        }
    }
}
