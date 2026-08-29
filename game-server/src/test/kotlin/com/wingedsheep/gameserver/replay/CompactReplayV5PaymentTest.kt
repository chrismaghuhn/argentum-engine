package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.FixedManaOutput
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.mechanics.mana.ManaAbilityIdentity
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import org.springframework.web.socket.WebSocketSession

/** Task-6 replay acceptance for the new persisted ExplicitV3 action carrier. */
class CompactReplayV5PaymentTest : ScenarioTestBase() {

    private val replayV5Source = card("Replay V5 Payment Source") {
        typeLine = "Land"
        activatedAbility {
            cost = Costs.Tap
            effect = Effects.AddMana(com.wingedsheep.sdk.core.Color.GREEN)
            manaAbility = true
        }
    }

    private val replayV5Spell = card("Replay V5 Payment Spell") {
        manaCost = "{G}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    private fun mockWs(id: String): WebSocketSession =
        mockk(relaxed = true) { every { this@mockk.id } returns id }

    private fun advanceToPriority(session: GameSession, playerId: EntityId) {
        repeat(100) {
            val state = session.getStateForTesting().shouldNotBeNull()
            if (state.priorityPlayerId == playerId &&
                state.activePlayerId == playerId &&
                state.phase == Phase.PRECOMBAT_MAIN &&
                state.pendingDecision == null &&
                state.stack.isEmpty()
            ) return
            val priority = state.priorityPlayerId ?: error("Expected priority while preparing replay")
            val result = session.executeAutoPass(priority)
            check(result !is GameSession.ActionResult.Failure) {
                "Could not advance priority: ${(result as GameSession.ActionResult.Failure).reason}"
            }
        }
        error("Could not return priority to $playerId")
    }

    private fun submit(session: GameSession, playerId: EntityId, action: GameAction) {
        val result = session.executeAction(playerId, action)
        check(result !is GameSession.ActionResult.Failure) {
            "action=$action; reason=${(result as GameSession.ActionResult.Failure).reason}"
        }
    }

    private fun v5Plan(sourceId: EntityId): PaymentPlanV3 {
        val key = ManaAbilityIdentity.key(replayV5Source.activatedAbilities.single())
        return PaymentPlanV3(
            activations = listOf(
                SourceActivationV2(
                    sourceId = sourceId,
                    manaAbilityKey = key,
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
    }

    init {
        test("PAY106-13: ExplicitV3 preserves ordered nodes and allocations through v5 replay") {
            val plan = PaymentPlanV3(
                activations = listOf(
                    SourceActivationV2(
                        sourceId = EntityId("source-0"),
                        manaAbilityKey = "ability-0",
                        productionChoice = ProductionChoice(
                            producedColor = PaymentManaColor.GREEN,
                            fixedOutputs = listOf(FixedManaOutput(0, PaymentManaColor.GREEN)),
                        ),
                    ),
                ),
                outerAllocation = listOf(
                    PaymentAllocationV1(
                        target = PaymentTargetV1.OuterCostUnit(0, 0),
                        resource = ManaResourceRefV1.ActivationOutputUnit(0, 0),
                    ),
                ),
            )
            val action = CastSpell(
                playerId = EntityId("p1"),
                cardId = EntityId("spell"),
                paymentStrategy = PaymentStrategy.ExplicitV3(paymentPlan = plan),
            )
            val original = CompactReplay(
                version = 5,
                gameId = "v5-wire",
                players = listOf(ReplayPlayerInfo("p1", "Alice"), ReplayPlayerInfo("p2", "Bob")),
                startedAt = "2026-08-29T00:00:00Z",
                endedAt = "2026-08-29T00:01:00Z",
                winnerName = null,
                setup = ReplaySetup(
                    seed = 1L,
                    format = Format.Standard,
                    attackMode = AttackMode.MULTIPLE,
                    players = listOf(
                        ReplayPlayerSetup("p1", "Alice", com.wingedsheep.sdk.model.Deck(cards = listOf("Forest"))),
                        ReplayPlayerSetup("p2", "Bob", com.wingedsheep.sdk.model.Deck(cards = listOf("Forest"))),
                    ),
                    seatRoster = emptyList(),
                ),
                actions = listOf(action),
            )

            val decoded = ReplayCodec.decode(ReplayCodec.encode(original))
            decoded shouldBe original
            decoded.version shouldBe 5
            val decodedAction = decoded.actions.single() as CastSpell
            decodedAction.paymentStrategy shouldBe action.paymentStrategy
        }

        test("PAY106-13: ExplicitV3 payment replays exactly through the reconstructed engine") {
            cardRegistry.register(replayV5Source)
            cardRegistry.register(replayV5Spell)
            val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
            val playerOne = EntityId.of("replay-v5-player-one")
            val playerTwo = EntityId.of("replay-v5-player-two")
            val deck = mapOf(replayV5Source.name to 1, replayV5Spell.name to 1, "Mountain" to 5)
            session.addPlayer(PlayerSession(mockWs("replay-v5-ws-1"), playerOne, "Alice"), deck)
            session.addPlayer(PlayerSession(mockWs("replay-v5-ws-2"), playerTwo, "Bob"), deck)
            session.startGame()
            session.keepHand(playerOne)
            session.keepHand(playerTwo)

            val player = session.getStateForTesting().shouldNotBeNull().activePlayerId
                ?: error("Expected active player")
            advanceToPriority(session, player)
            val enumerator = LegalActionEnumerator.create(cardRegistry)
            val initial = session.getStateForTesting().shouldNotBeNull()
            val playLand = enumerator.enumerate(initial, player)
                .firstOrNull { legal ->
                    val action = legal.action as? PlayLand ?: return@firstOrNull false
                    initial.getEntity(action.cardId)
                        ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                        ?.name == replayV5Source.name
                }
                ?: error("Expected Replay V5 source in hand")
            submit(session, player, playLand.action)
            advanceToPriority(session, player)

            val afterLand = session.getStateForTesting().shouldNotBeNull()
            val sourceId = afterLand.getBattlefield(player).firstOrNull { id ->
                afterLand.getEntity(id)
                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                    ?.name == replayV5Source.name
            } ?: error("Expected Replay V5 source on battlefield")
            val spellId = afterLand.getHand(player).firstOrNull { id ->
                afterLand.getEntity(id)
                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                    ?.name == replayV5Spell.name
            } ?: error("Expected Replay V5 spell in hand")
            val legalCast = enumerator.enumerate(afterLand, player)
                .firstOrNull { legal ->
                    val action = legal.action as? CastSpell ?: return@firstOrNull false
                    action.cardId == spellId
                }
                ?: error("Expected Replay V5 CastSpell action")
            val explicit = (legalCast.action as CastSpell).copy(
                paymentStrategy = PaymentStrategy.ExplicitV3(paymentPlan = v5Plan(sourceId)),
            )
            submit(session, player, explicit)

            val liveFinal = session.getStateForTesting().shouldNotBeNull()
            val actions = session.getRecordedActions()
            actions.filterIsInstance<CastSpell>().single { it.cardId == spellId }
                .paymentStrategy shouldBe explicit.paymentStrategy
            val setup = session.getReplaySetup().shouldNotBeNull()
            val replay = CompactReplay(
                version = 5,
                gameId = session.sessionId,
                players = session.getPlayers().map { ReplayPlayerInfo(it.playerId.value, it.playerName) },
                startedAt = "2026-08-29T00:00:00Z",
                endedAt = "2026-08-29T00:01:00Z",
                winnerName = null,
                setup = setup,
                actions = actions,
                checkpoints = listOf(
                    ReplayCheckpoint(
                        afterActionCount = actions.size,
                        fingerprint = ReplayFingerprint.of(liveFinal, 5),
                    ),
                ),
            )

            val decoded = ReplayCodec.decode(ReplayCodec.encode(replay))
            decoded shouldBe replay
            val reconstructed = ReplayReconstructor(cardRegistry, null).reconstruct(decoded)
            reconstructed.fidelity shouldBe ReplayFidelity.EXACT
            val reconstructedFinal = ReplayReconstructor(cardRegistry, null)
                .reconstructStateAt(decoded, decoded.actions.size)
                .shouldNotBeNull()
            ReplayFingerprint.of(reconstructedFinal, 5) shouldBe ReplayFingerprint.of(liveFinal, 5)
        }

        test("PAY106-14: ExplicitV3 remains rejected under CompactReplay-v4") {
            val action = CastSpell(
                playerId = EntityId("p1"),
                cardId = EntityId("spell"),
                paymentStrategy = PaymentStrategy.ExplicitV3(paymentPlan = PaymentPlanV3()),
            )
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                CompactReplay(
                    version = 4,
                    gameId = "v4-with-v3",
                    players = listOf(ReplayPlayerInfo("p1", "Alice")),
                    startedAt = "2026-08-29T00:00:00Z",
                    endedAt = "2026-08-29T00:01:00Z",
                    winnerName = null,
                    setup = ReplaySetup(
                        seed = 1L,
                        format = Format.Standard,
                        attackMode = AttackMode.MULTIPLE,
                        players = listOf(
                            ReplayPlayerSetup("p1", "Alice", com.wingedsheep.sdk.model.Deck(cards = listOf("Forest"))),
                        ),
                        seatRoster = emptyList(),
                    ),
                    actions = listOf(action),
                )
            }
        }
    }
}
