package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CostUnitAllocation
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.ManaSpendReference
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV1
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SpendAllocation
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.mechanics.mana.ManaAbilityIdentity
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession

/** CompactReplay round-trip coverage for the serialized action-level PaymentPlanV1. */
class PaymentPlanReplayTest : ScenarioTestBase() {

    private val paymentPermanent = card("Replay Payment Permanent") {
        typeLine = "Land"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddManaOfChoice()
            manaAbility = true
        }
        activatedAbility {
            cost = Costs.Mana("{R}")
            effect = Effects.GainLife(1)
        }
    }

    private fun mockWs(id: String): WebSocketSession =
        mockk(relaxed = true) { every { this@mockk.id } returns id }

    private fun requireApplied(result: GameSession.ActionResult) {
        check(result !is GameSession.ActionResult.Failure) {
            (result as GameSession.ActionResult.Failure).reason
        }
    }

    private fun advanceToPriority(session: GameSession, playerId: EntityId) {
        repeat(100) {
            val state = session.getStateForTesting().shouldNotBeNull()
            if (state.priorityPlayerId == playerId &&
                state.activePlayerId == playerId &&
                state.phase == Phase.PRECOMBAT_MAIN &&
                state.pendingDecision == null
            ) return
            val priority = state.priorityPlayerId ?: error("Expected priority while preparing replay")
            requireApplied(session.executeAutoPass(priority))
        }
        error("Could not return priority to $playerId")
    }

    private fun submitAndResolve(session: GameSession, playerId: EntityId, action: GameAction) {
        requireApplied(session.executeAction(playerId, action))
        advanceToPriority(session, playerId)
    }

    init {
        test("PaymentPlanV1 survives CompactReplay encode/decode and reconstruction") {
            cardRegistry.register(paymentPermanent)
            val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
            val playerOne = EntityId.of("payment-replay-player-one")
            val playerTwo = EntityId.of("payment-replay-player-two")
            val deck = mapOf(paymentPermanent.name to 3, "Mountain" to 4)
            session.addPlayer(PlayerSession(mockWs("payment-ws-1"), playerOne, "Alice"), deck)
            session.addPlayer(PlayerSession(mockWs("payment-ws-2"), playerTwo, "Bob"), deck)
            session.startGame()
            session.keepHand(playerOne)
            session.keepHand(playerTwo)

            val player = session.getStateForTesting().shouldNotBeNull().activePlayerId
                ?: error("Expected an active player after mulligans")

            val enumerator = LegalActionEnumerator.create(cardRegistry)
            advanceToPriority(session, player)
            val firstState = session.getStateForTesting().shouldNotBeNull()
            val playLand = enumerator.enumerate(firstState, player)
                .firstOrNull { legal ->
                    val action = legal.action as? PlayLand ?: return@firstOrNull false
                    firstState.getEntity(action.cardId)
                        ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                        ?.name == paymentPermanent.name
                }
                ?: error("Expected the first payment land action: ${enumerator.enumerate(firstState, player)}")
            submitAndResolve(session, player, playLand.action)

            val battlefieldSources = session.getStateForTesting().shouldNotBeNull()
                .getBattlefield(player)
                .filter { id ->
                    session.getStateForTesting().shouldNotBeNull().getEntity(id)
                        ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                        ?.name == paymentPermanent.name
                }
            if (battlefieldSources.size != 1) {
                val state = session.getStateForTesting().shouldNotBeNull()
                val battlefieldNames = state.getBattlefield(player).mapNotNull { id ->
                    state.getEntity(id)
                        ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                        ?.name
                }
                error("Expected one payment land, found ${battlefieldSources.size}: $battlefieldNames")
            }
            val abilitySource = battlefieldSources[0]
            val manaSource = abilitySource
            val paymentAbility = paymentPermanent.activatedAbilities[1]
            val manaAbilityKey = ManaAbilityIdentity.key(paymentPermanent.activatedAbilities[0])
            val legalActivation = enumerator.enumerate(session.getStateForTesting().shouldNotBeNull(), player)
                .firstOrNull { legal ->
                    val action = legal.action as? ActivateAbility ?: return@firstOrNull false
                    action.sourceId == abilitySource && action.abilityId == paymentAbility.id
                }
                ?: error("Expected payment ability: ${enumerator.enumerate(session.getStateForTesting().shouldNotBeNull(), player)}")
            val explicitAction = (legalActivation.action as ActivateAbility).copy(
                paymentStrategy = com.wingedsheep.engine.core.PaymentStrategy.Explicit(
                    paymentPlan = PaymentPlanV1(
                        sourceActivations = listOf(
                            SourceActivation(
                                sourceId = manaSource,
                                manaAbilityKey = manaAbilityKey,
                                productionChoice = ProductionChoice(PaymentManaColor.RED),
                            ),
                        ),
                        poolSpend = PoolSpend(),
                        spendAllocation = SpendAllocation(
                            costUnits = listOf(
                                CostUnitAllocation(
                                    symbolIndex = 0,
                                    spends = listOf(ManaSpendReference(sourceId = manaSource)),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            submitAndResolve(session, player, explicitAction)

            val setup = session.getReplaySetup().shouldNotBeNull()
            val replay = CompactReplay(
                gameId = session.sessionId,
                players = session.getPlayers().map { ReplayPlayerInfo(it.playerId.value, it.playerName) },
                startedAt = "2026-08-21T00:00:00Z",
                endedAt = "2026-08-21T00:01:00Z",
                winnerName = null,
                setup = setup,
                actions = session.getRecordedActions(),
            )

            val decoded = ReplayCodec.decode(ReplayCodec.encode(replay))
            decoded shouldBe replay
            val recordedPaymentAction = decoded.actions.filterIsInstance<ActivateAbility>()
                .firstOrNull { it.sourceId == abilitySource }
                ?: error("Expected a recorded explicit ActivateAbility: ${decoded.actions}")
            recordedPaymentAction.paymentStrategy shouldBe explicitAction.paymentStrategy

            val reconstructed = ReplayReconstructor(cardRegistry, null).reconstruct(decoded)
            reconstructed.frameCount shouldBe decoded.frameCount
            ReplayReconstructor(cardRegistry, null)
                .reconstructStateAt(decoded, decoded.actions.size)
                .shouldNotBeNull()
        }
    }
}
