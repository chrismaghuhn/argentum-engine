package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivationCostComponentRefV1
import com.wingedsheep.engine.core.AtomicManaCostUnitV1
import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.ManaSpentEvent
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.SourceActivationV2
import com.wingedsheep.engine.core.SpellCastEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.apc.cards.LlanowarWastes
import com.wingedsheep.mtg.sets.definitions.dmu.cards.TearAsunder
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.ChoiceSlot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private data class PreparedTearAsunderGym(
    val environment: GameEnvironment,
    val gym: GameGymEnv,
    val playerId: EntityId,
    val tearAsunderId: EntityId,
    val targetArtifactId: EntityId,
    val wastesId: EntityId,
    val forestIds: List<EntityId>,
)

/** Public-domain-to-trusted-execution regressions for real normal and kicked Tear Asunder casts. */
class GameGymEnvTearAsunderPaymentDomainTest : FunSpec({

    val actionJson = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    val targetArtifact = card("Gym Tear Asunder Target Artifact") {
        typeLine = "Artifact"
    }

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(LlanowarWastes)
        register(TearAsunder)
        register(targetArtifact)
    }

    fun prepared(): PreparedTearAsunderGym {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig(
                        "Alice",
                        Deck.of(
                            TearAsunder.name to 1,
                            LlanowarWastes.name to 1,
                            targetArtifact.name to 1,
                            "Forest" to 8,
                        ),
                    ),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 1,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 93502L,
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

        val tearAsunderId = moveNamed(TearAsunder.name, Zone.HAND)
        val wastesId = moveNamed(LlanowarWastes.name, Zone.BATTLEFIELD)
        val targetArtifactId = moveNamed(targetArtifact.name, Zone.BATTLEFIELD)
        val forestIds = buildList { repeat(4) { add(moveNamed("Forest", Zone.BATTLEFIELD)) } }
        environment.restore(state, environment.playerIds, environment.stepCount)

        return PreparedTearAsunderGym(
            environment = environment,
            gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
            ),
            playerId = playerId,
            tearAsunderId = tearAsunderId,
            targetArtifactId = targetArtifactId,
            wastesId = wastesId,
            forestIds = forestIds,
        )
    }

    fun viewFor(
        prepared: PreparedTearAsunderGym,
        kicked: Boolean,
    ): LegalActionView {
        return (prepared.gym.observe().observation as TrainingObservation).legalActions.single { legal ->
            legal.sourceEntityId == prepared.tearAsunderId &&
                (legal.kind == "CastWithKicker") == kicked
        }
    }

    fun publicTarget(view: LegalActionView, expectedTarget: EntityId): EntityId {
        val requirement = view.targetDomain?.requirements?.singleOrNull()
            ?: error("Expected one public target requirement for ${view.description}")
        requirement.minTargets shouldBe 1
        requirement.maxTargets shouldBe 1
        return requirement.candidates.single { it == expectedTarget }
    }

    fun publicSource(
        view: LegalActionView,
        sourceId: EntityId,
        color: PaymentManaColor,
    ) = view.paymentDomain?.sourceActivationOptions?.single { source ->
        source.sourceId == sourceId && source.productionChoices.single().producedColor == color
    } ?: error("Expected public source $sourceId producing $color")

    fun paymentFromPublicDomain(
        prepared: PreparedTearAsunderGym,
        view: LegalActionView,
        kicked: Boolean,
    ): PaymentStrategy.ExplicitV3 {
        val domain = view.paymentDomain ?: error("Expected PaymentDomainV5")
        val genericUnits = domain.outerAtomicCostUnits.filter {
            it.kind == com.wingedsheep.engine.core.PaymentCostKindV1.GENERIC
        }
        val green = domain.outerAtomicCostUnits.single {
            it.allowedColors == setOf(PaymentManaColor.GREEN)
        }
        val black = domain.outerAtomicCostUnits.singleOrNull {
            it.allowedColors == setOf(PaymentManaColor.BLACK)
        }

        val wastesColor = if (kicked) PaymentManaColor.BLACK else PaymentManaColor.GREEN
        val wastes = publicSource(view, prepared.wastesId, wastesColor)
        val genericForests = prepared.forestIds.take(genericUnits.size)
            .map { publicSource(view, it, PaymentManaColor.GREEN) }
        val greenSource = if (kicked) {
            publicSource(view, prepared.forestIds[genericUnits.size], PaymentManaColor.GREEN)
        } else {
            wastes
        }

        if (kicked) {
            black ?: error("Expected public black kicker cost unit")
        } else {
            black shouldBe null
        }

        val selected = buildList {
            addAll(genericForests)
            add(greenSource)
            add(wastes.takeUnless { it.sourceId == greenSource.sourceId })
        }.filterNotNull()
        val activations = selected.map { source ->
            SourceActivationV2(
                sourceId = source.sourceId,
                manaAbilityKey = source.manaAbilityKey,
                productionChoice = source.productionChoices.single(),
                activationCostOrder = source.activationCostOrderOptions.single(),
            )
        }
        val output = { activationIndex: Int ->
            ManaResourceRefV1.ActivationOutputUnit(activationIndex, outputIndex = 0)
        }
        val allocations = buildList {
            genericUnits.forEachIndexed { unitIndex, unit ->
                add(
                    PaymentAllocationV1(
                        target = PaymentTargetV1.OuterCostUnit(
                            symbolIndex = unit.symbolIndex,
                            unitIndexWithinSymbol = unit.unitIndexWithinSymbol,
                        ),
                        resource = output(unitIndex),
                    ),
                )
            }
            val greenActivationIndex = genericForests.size
            val greenResource = if (kicked) output(greenActivationIndex) else output(greenActivationIndex)
            add(
                PaymentAllocationV1(
                    target = PaymentTargetV1.OuterCostUnit(
                        symbolIndex = green.symbolIndex,
                        unitIndexWithinSymbol = green.unitIndexWithinSymbol,
                    ),
                    resource = greenResource,
                ),
            )
            if (kicked) {
                add(
                    PaymentAllocationV1(
                        target = PaymentTargetV1.OuterCostUnit(
                            symbolIndex = black!!.symbolIndex,
                            unitIndexWithinSymbol = black.unitIndexWithinSymbol,
                        ),
                        resource = output(selected.lastIndex),
                    ),
                )
            }
        }

        return PaymentStrategy.ExplicitV3(
            paymentPlan = PaymentPlanV3(
                activations = activations,
                outerAllocation = allocations,
            ),
        )
    }

    fun payload(
        view: LegalActionView,
        payment: PaymentStrategy,
        targetId: EntityId,
    ): JsonObject = buildJsonObject {
        view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
        put(
            "paymentStrategy",
            actionJson.encodeToJsonElement(PaymentStrategy.serializer(), payment),
        )
        put(
            "targets",
            buildJsonArray {
                add(buildJsonObject {
                    put("type", "Permanent")
                    put("entityId", targetId.value)
                })
            },
        )
    }

    fun assertRejectedAtomically(
        prepared: PreparedTearAsunderGym,
        view: LegalActionView,
        payment: PaymentStrategy,
        targetId: EntityId,
    ) {
        val stateBefore = prepared.environment.state
        val stepBefore = prepared.environment.stepCount
        shouldThrow<IllegalArgumentException> {
            prepared.gym.step(view.actionId, payload(view, payment, targetId))
        }
        prepared.environment.state shouldBe stateBefore
        prepared.environment.stepCount shouldBe stepBefore
        prepared.environment.lastStepEvents shouldBe emptyList()
        prepared.environment.state.getEntity(prepared.wastesId)?.has<TappedComponent>() shouldBe false
    }

    test("real Tear Asunder normal {1}{G} executes public V3 payment and exact Wastes G ability") {
        val prepared = prepared()
        val view = viewFor(prepared, kicked = false)
        val domain = view.paymentDomain ?: error("Expected PaymentDomainV5")
        val target = publicTarget(view, prepared.targetArtifactId)
        val payment = paymentFromPublicDomain(prepared, view, kicked = false)
        val selectedSourceIds = payment.paymentPlan!!.activations.map { it.sourceId }.toSet()
        val lifeBefore = prepared.environment.state.lifeTotal(prepared.playerId)

        view.manaCost shouldBe "{1}{G}"
        domain.requiredCost shouldBe view.manaCost
        domain.version shouldBe 5
        domain.outerAtomicCostUnits.size shouldBe 2
        assertRejectedAtomically(prepared, view, PaymentStrategy.AutoPay, target)
        assertRejectedAtomically(
            prepared,
            view,
            PaymentStrategy.Explicit(manaAbilitiesToActivate = listOf(prepared.wastesId)),
            target,
        )

        val stepBefore = prepared.environment.stepCount
        prepared.gym.step(view.actionId, payload(view, payment, target))

        prepared.environment.stepCount shouldBe stepBefore + 1
        prepared.environment.state.lifeTotal(prepared.playerId) shouldBe lifeBefore - 1
        prepared.environment.state.getEntity(prepared.wastesId)?.has<TappedComponent>() shouldBe true
        prepared.environment.state.getEntity(prepared.forestIds.first())?.has<TappedComponent>() shouldBe true
        prepared.environment.state.getEntity(prepared.forestIds.drop(1).first())?.has<TappedComponent>() shouldBe false
        prepared.environment.lastStepEvents.filterIsInstance<ManaSpentEvent>().single().let { spent ->
            spent.total shouldBe 2
            spent.green shouldBe 2
            spent.black shouldBe 0
            spent.colorless shouldBe 0
        }
        prepared.environment.lastStepEvents.filterIsInstance<DamageDealtEvent>().single().let { damage ->
            damage.amount shouldBe 1
            damage.targetId shouldBe prepared.playerId
        }
        prepared.environment.lastStepEvents.filterIsInstance<SpellCastEvent>().single().let { cast ->
            cast.totalManaSpent shouldBe 2
            cast.declaredCostSlot shouldBe null
            cast.spentManaSourceIds shouldBe selectedSourceIds
        }
    }

    test("real Tear Asunder kicked {2}{G}{B} executes public V3 payment and exact Wastes B ability") {
        val prepared = prepared()
        val view = viewFor(prepared, kicked = true)
        val domain = view.paymentDomain ?: error("Expected PaymentDomainV5")
        val target = publicTarget(view, prepared.targetArtifactId)
        val payment = paymentFromPublicDomain(prepared, view, kicked = true)
        val selectedSourceIds = payment.paymentPlan!!.activations.map { it.sourceId }.toSet()
        val lifeBefore = prepared.environment.state.lifeTotal(prepared.playerId)

        view.manaCost shouldBe "{2}{G}{B}"
        domain.requiredCost shouldBe view.manaCost
        domain.version shouldBe 5
        domain.outerAtomicCostUnits.size shouldBe 4
        assertRejectedAtomically(prepared, view, PaymentStrategy.AutoPay, target)
        assertRejectedAtomically(
            prepared,
            view,
            PaymentStrategy.Explicit(manaAbilitiesToActivate = listOf(prepared.wastesId)),
            target,
        )

        val stepBefore = prepared.environment.stepCount
        prepared.gym.step(view.actionId, payload(view, payment, target))

        prepared.environment.stepCount shouldBe stepBefore + 1
        prepared.environment.state.lifeTotal(prepared.playerId) shouldBe lifeBefore - 1
        prepared.environment.state.getEntity(prepared.wastesId)?.has<TappedComponent>() shouldBe true
        prepared.forestIds.take(3).forEach { sourceId ->
            prepared.environment.state.getEntity(sourceId)?.has<TappedComponent>() shouldBe true
        }
        prepared.environment.state.getEntity(prepared.forestIds[3])?.has<TappedComponent>() shouldBe false
        prepared.environment.lastStepEvents.filterIsInstance<ManaSpentEvent>().single().let { spent ->
            spent.total shouldBe 4
            spent.green shouldBe 3
            spent.black shouldBe 1
            spent.colorless shouldBe 0
        }
        prepared.environment.lastStepEvents.filterIsInstance<DamageDealtEvent>().single().let { damage ->
            damage.amount shouldBe 1
            damage.targetId shouldBe prepared.playerId
        }
        prepared.environment.lastStepEvents.filterIsInstance<SpellCastEvent>().single().let { cast ->
            cast.totalManaSpent shouldBe 4
            cast.declaredCostSlot shouldBe ChoiceSlot.KICKED
            cast.spentManaSourceIds shouldBe selectedSourceIds
        }
    }

    test("real Tear Asunder kicked rejects a wrong-cost public V3 plan atomically") {
        val prepared = prepared()
        val view = viewFor(prepared, kicked = true)
        val target = publicTarget(view, prepared.targetArtifactId)
        val valid = paymentFromPublicDomain(prepared, view, kicked = true)
        val validPlan = valid.paymentPlan ?: error("Expected PaymentPlanV3")
        val generic = view.paymentDomain!!.outerAtomicCostUnits.first {
            it.kind == com.wingedsheep.engine.core.PaymentCostKindV1.GENERIC
        }
        val underpaid = valid.copy(
            paymentPlan = validPlan.copy(
                outerAllocation = validPlan.outerAllocation.filterNot {
                    it.target == PaymentTargetV1.OuterCostUnit(
                        symbolIndex = generic.symbolIndex,
                        unitIndexWithinSymbol = generic.unitIndexWithinSymbol,
                    )
                },
            ),
        )

        assertRejectedAtomically(prepared, view, underpaid, target)
    }
})
