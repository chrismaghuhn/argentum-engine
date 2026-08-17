package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.AnyPlayerMayPayContinuation
import com.wingedsheep.engine.core.CardsSelectedResponse
import com.wingedsheep.engine.core.ContinuationFrame
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.SelectCardsDecision
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.continuations.SacrificeAndPayContinuationResumer
import com.wingedsheep.engine.handlers.effects.player.AnyPlayerMayPayExecutor
import com.wingedsheep.engine.handlers.effects.player.PayOrSufferExecutor
import com.wingedsheep.engine.mechanics.cost.CostPaymentService
import com.wingedsheep.engine.mechanics.cost.PaymentResult
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.mechanics.layers.Layer
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.mechanics.layers.addFloatingEffect
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.support.ScenarioTestBase
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.costs.CostAtom
import com.wingedsheep.sdk.scripting.costs.PayCost
import com.wingedsheep.sdk.scripting.effects.AnyPlayerMayPayEffect
import com.wingedsheep.sdk.scripting.effects.PayOrSufferEffect
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json

/**
 * Focused characterization for the generic cost candidate-domain invariant.
 *
 * These tests intentionally exercise the production affordability, decision, APNAP continuation,
 * and mana-ability enumeration paths rather than testing a card-specific workaround.
 */
class CostExcludeSelfConsistencyTest : ScenarioTestBase() {

    private val compositeTapManaSource = card("Test Composite Tap Mana Source") {
        manaCost = "{0}"
        typeLine = "Creature — Test"
        power = 1
        toughness = 1
        oracleText = "{T}, Tap another creature you control: Add {C}."

        activatedAbility {
            cost = Costs.Composite(
                Costs.Tap,
                Costs.TapPermanents(
                    count = 1,
                    filter = GameObjectFilter.Creature,
                    excludeSelf = true
                )
            )
            effect = Effects.AddColorlessMana(1)
            manaAbility = true
        }
    }

    init {
        cardRegistry.register(compositeTapManaSource)

        test("COST-EXCLUDE-01: CostPaymentService includes the source when Sacrifice.excludeSelf=false") {
            val game = sacrificeGame()
            val source = cardOnBattlefield(game.state, game.player1Id, "Goblin Guide")
            val cost = sacrificeCost(excludeSelf = false)
            val service = CostPaymentService(EngineServices(cardRegistry))

            service.canAfford(game.state, game.player1Id, cost, source) shouldBe true
            val payment = service.pay(game.state, game.player1Id, cost, source)
                .shouldBeInstanceOf<PaymentResult.Pending>()
            val decision = payment.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
            decision.options shouldContain source
            game.state = payment.state
            game.submitDecision(CardsSelectedResponse(decision.id, listOf(source)))
            game.state.getZone(ZoneKey(game.player1Id, Zone.GRAVEYARD)) shouldContain source
        }

        test("COST-EXCLUDE-02: CostPaymentService excludes the source when Sacrifice.excludeSelf=true") {
            val game = scenario().withPlayers()
                .withCardOnBattlefield(1, "Goblin Guide")
                .build()
            val source = cardOnBattlefield(game.state, game.player1Id, "Goblin Guide")
            val cost = sacrificeCost(excludeSelf = true)
            val service = CostPaymentService(EngineServices(cardRegistry))

            service.canAfford(game.state, game.player1Id, cost, source) shouldBe false
            service.pay(game.state, game.player1Id, cost, source)
                .shouldBeInstanceOf<PaymentResult.Unaffordable>()
        }

        test("COST-EXCLUDE-03: affordability and the CostPaymentService decision domain agree") {
            val service = CostPaymentService(EngineServices(cardRegistry))

            for (excludeSelf in listOf(false, true)) {
                val game = sacrificeGame(onlySource = true)
                val source = cardOnBattlefield(game.state, game.player1Id, "Goblin Guide")
                val cost = sacrificeCost(excludeSelf)
                val affordable = service.canAfford(game.state, game.player1Id, cost, source)
                val payment = service.pay(game.state, game.player1Id, cost, source)

                affordable shouldBe (excludeSelf == false)
                if (excludeSelf) {
                    payment.shouldBeInstanceOf<PaymentResult.Unaffordable>()
                } else {
                    val decision = payment.shouldBeInstanceOf<PaymentResult.Pending>()
                        .pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
                    decision.options shouldBe listOf(source)
                }
            }
        }

        test("COST-EXCLUDE-04: AnyPlayerMayPay includes a paying player's source when excludeSelf=false") {
            val game = sacrificeGame()
            val source = cardOnBattlefield(game.state, game.player1Id, "Goblin Guide")
            val result = AnyPlayerMayPayExecutor().execute(
                game.state,
                anyPlayerCost(excludeSelf = false),
                EffectContext(sourceId = source, controllerId = game.player1Id)
            )
            val decision = result.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()

            decision.options shouldContain source
        }

        test("COST-EXCLUDE-05: AnyPlayerMayPay excludes a paying player's source when excludeSelf=true") {
            val game = sacrificeGame()
            val source = cardOnBattlefield(game.state, game.player1Id, "Goblin Guide")
            val result = AnyPlayerMayPayExecutor().execute(
                game.state,
                anyPlayerCost(excludeSelf = true),
                EffectContext(sourceId = source, controllerId = game.player1Id)
            )
            val decision = result.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()

            decision.options shouldNotContain source
        }

        test("COST-EXCLUDE-06: AnyPlayerMayPay continuation preserves the candidate domain") {
            val json = Json {
                serializersModule = engineSerializersModule
                encodeDefaults = true
            }

            for (excludeSelf in listOf(false, true)) {
                // Player 1 is asked first and declines. Player 2 controls the source, so the
                // continuation's recomputation is forced to apply excludeSelf to the source.
                val game = scenario().withPlayers()
                    .withCardOnBattlefield(1, "Savannah Lions")
                    .withCardOnBattlefield(2, "Goblin Guide")
                    .build()
                val source = cardOnBattlefield(game.state, game.player2Id, "Goblin Guide")
                val first = AnyPlayerMayPayExecutor().execute(
                    game.state,
                    anyPlayerCost(excludeSelf),
                    EffectContext(sourceId = source, controllerId = game.player2Id)
                )
                val firstDecision = first.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
                val continuation = first.state.peekContinuation()
                    .shouldBeInstanceOf<AnyPlayerMayPayContinuation>()
                val encoded = json.encodeToString(ContinuationFrame.serializer(), continuation)
                val decoded = json.decodeFromString(ContinuationFrame.serializer(), encoded)
                    .shouldBeInstanceOf<AnyPlayerMayPayContinuation>()
                decoded shouldBe continuation

                val resumed = SacrificeAndPayContinuationResumer(EngineServices(cardRegistry))
                    .resumeAnyPlayerMayPay(
                        state = first.state.clearPendingDecision(),
                        continuation = decoded,
                        response = CardsSelectedResponse(firstDecision.id, emptyList()),
                        checkForMore = { state: GameState, events ->
                            ExecutionResult.success(state, events)
                        }
                    )

                if (excludeSelf) {
                    resumed.pendingDecision shouldBe null
                } else {
                    val nextDecision = resumed.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
                    nextDecision.options shouldBe listOf(source)
                }
            }
        }

        test("COST-EXCLUDE-09: AnyPlayerMayPay continuation uses projected controller ownership") {
            for (excludeSelf in listOf(false, true)) {
                // The source remains in Player 1's owner-keyed battlefield zone but is controlled
                // by Player 2 through a Layer 2 effect. Player 1 has a separate legal permanent,
                // so the first prompt is real; after Player 1 declines, Player 2 must be offered
                // the source when excludeSelf=false.
                val game = scenario().withPlayers()
                    .withCardOnBattlefield(1, "Goblin Guide")
                    .withCardOnBattlefield(1, "Savannah Lions")
                    .build()
                val source = cardOnBattlefield(game.state, game.player1Id, "Goblin Guide")
                game.state = game.state.addFloatingEffect(
                    layer = Layer.CONTROL,
                    modification = SerializableModification.ChangeController(game.player2Id),
                    affectedEntities = setOf(source),
                    duration = Duration.EndOfTurn,
                    context = EffectContext(sourceId = source, controllerId = game.player1Id)
                )
                game.state.projectedState.getController(source) shouldBe game.player2Id

                val first = AnyPlayerMayPayExecutor().execute(
                    game.state,
                    anyPlayerCost(excludeSelf),
                    EffectContext(sourceId = source, controllerId = game.player1Id)
                )
                val firstDecision = first.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
                firstDecision.playerId shouldBe game.player1Id
                firstDecision.options shouldNotContain source

                val continuation = first.state.peekContinuation()
                    .shouldBeInstanceOf<AnyPlayerMayPayContinuation>()
                val resumed = SacrificeAndPayContinuationResumer(EngineServices(cardRegistry))
                    .resumeAnyPlayerMayPay(
                        state = first.state.clearPendingDecision(),
                        continuation = continuation,
                        response = CardsSelectedResponse(firstDecision.id, emptyList()),
                        checkForMore = { state: GameState, events ->
                            ExecutionResult.success(state, events)
                        }
                    )

                if (excludeSelf) {
                    resumed.pendingDecision shouldBe null
                } else {
                    val nextDecision = resumed.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
                    nextDecision.playerId shouldBe game.player2Id
                    nextDecision.options shouldBe listOf(source)
                }
            }
        }

        test("COST-EXCLUDE-07: PayOrSuffer keeps its existing excludeSelf=true control behavior") {
            val game = sacrificeGame()
            val source = cardOnBattlefield(game.state, game.player1Id, "Goblin Guide")
            val result = PayOrSufferExecutor(cardRegistry).execute(
                game.state,
                PayOrSufferEffect(
                    cost = sacrificeCost(excludeSelf = true),
                    suffer = Effects.GainLife(1)
                ),
                EffectContext(sourceId = source, controllerId = game.player1Id)
            )
            val decision = result.pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()

            decision.options shouldNotContain source
        }

        test("COST-EXCLUDE-08: CostPaymentService TapPermanents keeps self-inclusive and self-exclusive domains distinct") {
            val service = CostPaymentService(EngineServices(cardRegistry))

            val inclusiveGame = scenario().withPlayers()
                .withCardOnBattlefield(1, "Goblin Guide")
                .build()
            val inclusiveSource = cardOnBattlefield(inclusiveGame.state, inclusiveGame.player1Id, "Goblin Guide")
            val inclusive = service.pay(
                inclusiveGame.state,
                inclusiveGame.player1Id,
                PayCost.Atom(CostAtom.TapPermanents(1, GameObjectFilter.Creature, excludeSelf = false)),
                inclusiveSource
            ).shouldBeInstanceOf<PaymentResult.Pending>()
                .pendingDecision.shouldBeInstanceOf<SelectCardsDecision>()
            inclusive.options shouldContain inclusiveSource

            val exclusiveGame = scenario().withPlayers()
                .withCardOnBattlefield(1, "Goblin Guide")
                .build()
            val exclusiveSource = cardOnBattlefield(exclusiveGame.state, exclusiveGame.player1Id, "Goblin Guide")
            service.canAfford(
                exclusiveGame.state,
                exclusiveGame.player1Id,
                PayCost.Atom(CostAtom.TapPermanents(1, GameObjectFilter.Creature, excludeSelf = true)),
                exclusiveSource
            ) shouldBe false
        }

        test("TapPermanents.excludeSelf is honored for composite mana-ability candidate data") {
            val game = scenario().withPlayers()
                .withCardOnBattlefield(1, compositeTapManaSource.name)
                .build()
            val source = cardOnBattlefield(game.state, game.player1Id, compositeTapManaSource.name)
            val action = game.getLegalActions(1).first {
                val activate = it.action as? com.wingedsheep.engine.core.ActivateAbility
                activate?.sourceId == source
            }

            action.additionalCostInfo!!.validTapTargets shouldNotContain source
        }
    }

    private fun sacrificeGame(onlySource: Boolean = false): TestGame {
        val builder = scenario().withPlayers()
            .withCardOnBattlefield(1, "Goblin Guide")
        if (!onlySource) builder.withCardOnBattlefield(1, "Savannah Lions")
        return builder.build()
    }

    private fun sacrificeCost(excludeSelf: Boolean): PayCost =
        PayCost.Atom(
            CostAtom.Sacrifice(
                filter = GameObjectFilter.Creature,
                count = 1,
                excludeSelf = excludeSelf
            )
        )

    private fun anyPlayerCost(excludeSelf: Boolean): AnyPlayerMayPayEffect =
        AnyPlayerMayPayEffect(cost = sacrificeCost(excludeSelf))

    private fun cardOnBattlefield(state: GameState, playerId: EntityId, name: String): EntityId =
        state.getBattlefield(playerId).first { id ->
            state.getEntity(id)?.get<CardComponent>()?.name == name
        }
}
