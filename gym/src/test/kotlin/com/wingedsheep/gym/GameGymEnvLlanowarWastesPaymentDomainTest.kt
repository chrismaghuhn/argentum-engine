package com.wingedsheep.gym

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.CostUnitAllocationV2
import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ManaSpentEvent
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV2
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.PoolSpend
import com.wingedsheep.engine.core.SourceActivation
import com.wingedsheep.engine.core.SpendAllocationV2
import com.wingedsheep.engine.core.ManaSpendReferenceV2
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.apc.cards.LlanowarWastes
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

private data class PreparedLlanowarWastesGym(
    val environment: GameEnvironment,
    val gym: GameGymEnv,
    val playerId: EntityId,
    val spellId: EntityId,
    val sourceId: EntityId,
)

/** RED coverage for complete real Llanowar Wastes publication and exact execution. */
class GameGymEnvLlanowarWastesPaymentDomainTest : FunSpec({

    val fixedCostSpell = card("Gym Llanowar Wastes Payment Spell") {
        manaCost = "{1}"
        typeLine = "Sorcery"
        spell {
            effect = Effects.GainLife(1)
        }
    }

    val actionJson = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(LlanowarWastes)
        register(fixedCostSpell)
    }

    fun prepared(): PreparedLlanowarWastesGym {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            fixedCostSpell.name to 1,
                            LlanowarWastes.name to 1,
                            "Forest" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 93501L,
            ),
        )

        val playerId = environment.playerIds.first()
        var state = environment.state
        while (state.step != Step.PRECOMBAT_MAIN) {
            val pass = environment.legalActions().first { it.actionType == "PassPriority" }
            environment.step(pass.action)
            state = environment.state
        }

        fun moveNamed(name: String, destination: Zone): EntityId {
            val entityId = state.entities.entries.first { (candidate, container) ->
                candidate in state.getZone(playerId, Zone.HAND) + state.getZone(playerId, Zone.LIBRARY) &&
                    container.get<CardComponent>()?.name == name
            }.key
            val sourceZone = state.zones.entries.first { (_, ids) -> entityId in ids }.key
            state = state.moveToZone(entityId, sourceZone, ZoneKey(playerId, destination))
            return entityId
        }

        val spellId = moveNamed(fixedCostSpell.name, Zone.HAND)
        val sourceId = moveNamed(LlanowarWastes.name, Zone.BATTLEFIELD)
        environment.restore(state, environment.playerIds, environment.stepCount)

        return PreparedLlanowarWastesGym(
            environment = environment,
            gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
            ),
            playerId = playerId,
            spellId = spellId,
            sourceId = sourceId,
        )
    }

    fun spellView(prepared: PreparedLlanowarWastesGym): LegalActionView =
        (prepared.gym.observe().observation as TrainingObservation).legalActions.single {
            it.kind == "CastSpell" && it.sourceEntityId == prepared.spellId
        }

    fun payload(view: LegalActionView, payment: PaymentStrategy): JsonObject = buildJsonObject {
        view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
        put(
            "paymentStrategy",
            actionJson.encodeToJsonElement(PaymentStrategy.serializer(), payment),
        )
    }

    fun paymentFor(
        view: LegalActionView,
        sourceId: EntityId,
        color: PaymentManaColor,
    ): PaymentStrategy.ExplicitV2 {
        val domain = view.paymentDomain ?: error("Expected PaymentDomainV4")
        val source = domain.sourceActivations.single { it.sourceId == sourceId &&
            it.productionChoices.single().producedColor == color }
        val choice = source.productionChoices.single()
        val symbol = domain.costUnits.single()
        return PaymentStrategy.ExplicitV2(
            paymentPlan = PaymentPlanV2(
                sourceActivations = listOf(
                    SourceActivation(
                        sourceId = source.sourceId,
                        manaAbilityKey = source.manaAbilityKey,
                        productionChoice = choice,
                    ),
                ),
                poolSpend = PoolSpend(),
                spendAllocation = SpendAllocationV2(
                    costUnits = listOf(
                        CostUnitAllocationV2(
                            symbolIndex = symbol.symbolIndex,
                            spends = listOf(
                                ManaSpendReferenceV2(sourceId = sourceId, amount = 1),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    test("real Llanowar Wastes publishes all exact current mana abilities") {
        val prepared = prepared()
        val view = spellView(prepared)
        val domain = view.paymentDomain ?: error("Expected PaymentDomainV4")

        domain.version shouldBe 4
        domain.sourceActivations.filter { it.sourceId == prepared.sourceId }
            .map { it.productionChoices.single().producedColor }
            .shouldContainExactlyInAnyOrder(
                PaymentManaColor.COLORLESS,
                PaymentManaColor.BLACK,
                PaymentManaColor.GREEN,
            )
    }

    for ((label, color, expectedDamage) in listOf(
        Triple("black", PaymentManaColor.BLACK, 1),
        Triple("green", PaymentManaColor.GREEN, 1),
        Triple("colorless", PaymentManaColor.COLORLESS, 0),
    )) {
        test("selecting the $label ability pays, taps exactly Wastes, and applies expected damage") {
            val prepared = prepared()
            val view = spellView(prepared)
            val lifeBefore = prepared.environment.state.lifeTotal(prepared.playerId)

            prepared.gym.step(
                view.actionId,
                payload(view, paymentFor(view, prepared.sourceId, color)),
            )

            prepared.environment.state.lifeTotal(prepared.playerId) shouldBe lifeBefore - expectedDamage
            prepared.environment.state.getEntity(prepared.sourceId)?.has<TappedComponent>() shouldBe true
            prepared.environment.lastStepEvents.filterIsInstance<ManaSpentEvent>().size shouldBe 1
            prepared.environment.lastStepEvents.filterIsInstance<DamageDealtEvent>().size shouldBe expectedDamage
            if (expectedDamage == 1) {
                prepared.environment.lastStepEvents.filterIsInstance<DamageDealtEvent>()
                    .single().targetId shouldBe prepared.playerId
            }
        }
    }

    test("invalid public plans remain atomic") {
        val prepared = prepared()
        val view = spellView(prepared)
        val stateBefore = prepared.environment.state
        val stepBefore = prepared.environment.stepCount
        val valid = paymentFor(view, prepared.sourceId, PaymentManaColor.BLACK)
        val invalid = valid.copy(paymentPlan = valid.paymentPlan!!.copy(spendAllocation = SpendAllocationV2()))

        shouldThrow<IllegalArgumentException> {
            prepared.gym.step(view.actionId, payload(view, invalid))
        }

        prepared.environment.state shouldBe stateBefore
        prepared.environment.stepCount shouldBe stepBefore
        prepared.environment.lastStepEvents shouldBe emptyList()
        prepared.environment.state.getEntity(prepared.sourceId)?.has<TappedComponent>() shouldBe false
    }
})
