package com.wingedsheep.gameserver.replay

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.CostUnitAllocation
import com.wingedsheep.engine.core.GameAction
import com.wingedsheep.engine.core.ManaSpendReference
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV1
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.ProductionChoice
import com.wingedsheep.engine.core.SpendAllocation
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.legalactions.LegalActionEnumerator
import com.wingedsheep.engine.mechanics.mana.ManaAbilityIdentity
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.gameserver.ScenarioTestBase
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.targets.TargetPlayerOrPlaneswalker
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

    private val paymentSpell = card("Replay Payment Spell") {
        manaCost = "{R}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    private val paymentModalSpell = card("Replay Payment Modal Spell") {
        manaCost = "{R}"
        typeLine = "Instant"
        spell {
            modal(chooseCount = 1) {
                mode("Target player gains 1 life") {
                    target("target player", TargetPlayerOrPlaneswalker())
                    effect = Effects.GainLife(1)
                }
                mode("Gain 1 life") {
                    effect = Effects.GainLife(1)
                }
            }
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
        val result = session.executeAction(playerId, action)
        check(result !is GameSession.ActionResult.Failure) {
            "action=$action; reason=${(result as GameSession.ActionResult.Failure).reason}"
        }
        advanceToPriority(session, playerId)
    }

    init {
        test("PaymentPlanV1 survives CompactReplay encode/decode and reconstruction") {
            cardRegistry.register(paymentPermanent)
            cardRegistry.register(paymentSpell)
            val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
            val playerOne = EntityId.of("payment-replay-player-one")
            val playerTwo = EntityId.of("payment-replay-player-two")
            // Seven cards exactly keeps the spell and the payment permanent in the opening hand,
            // while still leaving a second copy of the permanent in the library.
            val deck = mapOf(paymentPermanent.name to 2, paymentSpell.name to 1, "Mountain" to 4)
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
                paymentStrategy = PaymentStrategy.Explicit(
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

        test("CastSpell PaymentPlanV1 survives CompactReplay encode/decode and reconstruction") {
            cardRegistry.register(paymentPermanent)
            cardRegistry.register(paymentSpell)
            val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
            val playerOne = EntityId.of("cast-replay-player-one")
            val playerTwo = EntityId.of("cast-replay-player-two")
            val deck = mapOf(paymentPermanent.name to 1, paymentSpell.name to 1, "Mountain" to 5)
            session.addPlayer(PlayerSession(mockWs("cast-replay-ws-1"), playerOne, "Alice"), deck)
            session.addPlayer(PlayerSession(mockWs("cast-replay-ws-2"), playerTwo, "Bob"), deck)
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
                ?: error("Expected the CastSpell replay payment land: ${enumerator.enumerate(firstState, player)}")
            submitAndResolve(session, player, playLand.action)

            val state = session.getStateForTesting().shouldNotBeNull()
            val sourceId = state.getBattlefield(player).firstOrNull { id ->
                state.getEntity(id)
                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                    ?.name == paymentPermanent.name
            } ?: error("Expected CastSpell replay payment source")
            val spellId = state.getHand(player).firstOrNull { id ->
                state.getEntity(id)
                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                    ?.name == paymentSpell.name
            } ?: error("Expected CastSpell replay spell in hand: ${state.getHand(player)}")
            val manaAbilityKey = ManaAbilityIdentity.key(paymentPermanent.activatedAbilities[0])
            val legalCast = enumerator.enumerate(state, player)
                .firstOrNull { legal ->
                    val action = legal.action as? CastSpell ?: return@firstOrNull false
                    action.cardId == spellId
                }
                ?: error("Expected CastSpell replay action: ${enumerator.enumerate(state, player)}")
            val explicitCast = (legalCast.action as CastSpell).copy(
                paymentStrategy = PaymentStrategy.Explicit(
                    paymentPlan = PaymentPlanV1(
                        sourceActivations = listOf(
                            SourceActivation(
                                sourceId = sourceId,
                                manaAbilityKey = manaAbilityKey,
                                productionChoice = ProductionChoice(PaymentManaColor.RED),
                            ),
                        ),
                        poolSpend = PoolSpend(),
                        spendAllocation = SpendAllocation(
                            costUnits = listOf(
                                CostUnitAllocation(
                                    symbolIndex = 0,
                                    spends = listOf(ManaSpendReference(sourceId = sourceId)),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            submitAndResolve(session, player, explicitCast)

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
            val recordedCast = decoded.actions.filterIsInstance<CastSpell>()
                .firstOrNull { it.cardId == spellId }
                ?: error("Expected a recorded explicit CastSpell: ${decoded.actions}")
            recordedCast.paymentStrategy shouldBe explicitCast.paymentStrategy

            val reconstructed = ReplayReconstructor(cardRegistry, null).reconstruct(decoded)
            reconstructed.frameCount shouldBe decoded.frameCount
            ReplayReconstructor(cardRegistry, null)
                .reconstructStateAt(decoded, decoded.actions.size)
                .shouldNotBeNull()
        }

        test("CastSpellMode PaymentPlanV1 preserves chosen mode, targets, and replay reconstruction") {
            cardRegistry.register(paymentPermanent)
            cardRegistry.register(paymentModalSpell)
            val session = GameSession(cardRegistry = cardRegistry, maxPlayers = 2)
            val playerOne = EntityId.of("modal-replay-player-one")
            val playerTwo = EntityId.of("modal-replay-player-two")
            val deck = mapOf(paymentPermanent.name to 1, paymentModalSpell.name to 1, "Mountain" to 5)
            session.addPlayer(PlayerSession(mockWs("modal-replay-ws-1"), playerOne, "Alice"), deck)
            session.addPlayer(PlayerSession(mockWs("modal-replay-ws-2"), playerTwo, "Bob"), deck)
            session.startGame()
            session.keepHand(playerOne)
            session.keepHand(playerTwo)

            val player = session.getStateForTesting().shouldNotBeNull().activePlayerId
                ?: error("Expected an active player after modal mulligans")
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
                ?: error("Expected the modal replay payment land: ${enumerator.enumerate(firstState, player)}")
            submitAndResolve(session, player, playLand.action)

            val state = session.getStateForTesting().shouldNotBeNull()
            val sourceId = state.getBattlefield(player).firstOrNull { id ->
                state.getEntity(id)
                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                    ?.name == paymentPermanent.name
            } ?: error("Expected modal replay payment source")
            val spellId = state.getHand(player).firstOrNull { id ->
                state.getEntity(id)
                    ?.get<com.wingedsheep.engine.state.components.identity.CardComponent>()
                    ?.name == paymentModalSpell.name
            } ?: error("Expected modal replay spell in hand: ${state.getHand(player)}")
            val manaAbilityKey = ManaAbilityIdentity.key(paymentPermanent.activatedAbilities[0])
            val legalCast = enumerator.enumerate(state, player)
                .firstOrNull { legal ->
                    val action = legal.action as? CastSpell ?: return@firstOrNull false
                    legal.actionType == "CastSpellMode" && action.cardId == spellId &&
                        action.chosenModes == listOf(0)
                }
                ?: error("Expected CastSpellMode replay action: ${enumerator.enumerate(state, player)}")
            val target = ChosenTarget.Player(playerTwo)
            val explicitCast = (legalCast.action as CastSpell).copy(
                targets = listOf(target),
                modeTargetsOrdered = listOf(listOf(target)),
                paymentStrategy = PaymentStrategy.Explicit(
                    paymentPlan = PaymentPlanV1(
                        sourceActivations = listOf(
                            SourceActivation(
                                sourceId = sourceId,
                                manaAbilityKey = manaAbilityKey,
                                productionChoice = ProductionChoice(PaymentManaColor.RED),
                            ),
                        ),
                        poolSpend = PoolSpend(),
                        spendAllocation = SpendAllocation(
                            costUnits = listOf(
                                CostUnitAllocation(
                                    symbolIndex = 0,
                                    spends = listOf(ManaSpendReference(sourceId = sourceId)),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            submitAndResolve(session, player, explicitCast)

            val setup = session.getReplaySetup().shouldNotBeNull()
            val replay = CompactReplay(
                gameId = session.sessionId,
                players = session.getPlayers().map { ReplayPlayerInfo(it.playerId.value, it.playerName) },
                startedAt = "2026-08-22T00:00:00Z",
                endedAt = "2026-08-22T00:01:00Z",
                winnerName = null,
                setup = setup,
                actions = session.getRecordedActions(),
            )

            val decoded = ReplayCodec.decode(ReplayCodec.encode(replay))
            decoded shouldBe replay
            val recordedCast = decoded.actions.filterIsInstance<CastSpell>()
                .firstOrNull { it.cardId == spellId }
                ?: error("Expected a recorded explicit CastSpellMode: ${decoded.actions}")
            recordedCast.chosenModes shouldBe listOf(0)
            recordedCast.targets shouldBe listOf(target)
            recordedCast.modeTargetsOrdered shouldBe listOf(listOf(target))
            recordedCast.paymentStrategy shouldBe explicitCast.paymentStrategy

            val reconstructed = ReplayReconstructor(cardRegistry, null).reconstruct(decoded)
            reconstructed.frameCount shouldBe decoded.frameCount
            ReplayReconstructor(cardRegistry, null)
                .reconstructStateAt(decoded, decoded.actions.size)
                .shouldNotBeNull()
        }
    }
}
