package com.wingedsheep.gameserver.policy

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.ManaResourceRefV1
import com.wingedsheep.engine.core.PaymentAllocationV1
import com.wingedsheep.engine.core.PaymentCostKindV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PaymentPlanV3
import com.wingedsheep.engine.core.PaymentStrategy
import com.wingedsheep.engine.core.PaymentTargetV1
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
import com.wingedsheep.gym.ActionPaymentPlanValidator
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.GameGymEnv
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.PaymentDomainV5
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.thb.cards.Shadowspear
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * C1_LIVE_PAYMENT_CHOICE_BOUNDARY_01 — test-only RED characterization of the missing
 * Live-C1 explicit payment-choice boundary (docs/ml/c1-live-payment-choice-boundary-01.md;
 * predecessor docs/ml/arena-ml-01-payment-domain-02-fix.md).
 *
 * What is characterized here (and deliberately NOT fixed in this slice):
 *  1. A complete PaymentDomainV5 exists for a minimal two-bucket floating state
 *     (two certified whites from two distinct Plains sources, one generic {1} cost).
 *  2. Two provenance-distinct PaymentPlanV3 values (spend bucket A vs bucket B) are both
 *     valid at the trusted boundaries (gym explicit preflight + Rules execution).
 *  3. Each plan consumes exactly its selected bucket; the remaining certified pools are
 *     semantically distinct (different surviving source identity), so the choice is
 *     policy-relevant and must not be made by any hidden engine rule.
 *  4. The Live-C1 policy boundary nevertheless binds that paid action only as the
 *     aggregate AutoPay template: the exact JVM binding table contains exactly one
 *     binding for the activation, no payment-plan alternative is exposed, and therefore
 *     the current live C1 seat cannot express which valid plan it selected.
 *
 * Expected current result (the RED):
 *   TRUSTED_PAYMENT_DOMAIN_COMPLETE = YES
 *   MULTIPLE_VALID_EXPLICIT_V3_PLANS = YES
 *   EXACT_EXECUTION_WORKS = YES
 *   LIVE_C1_CAN_SELECT_PLAN = NO
 *
 * No production file is changed. The fixture reuses the accepted ARENA_ML_01_PAYMENT_DOMAIN_01
 * minimal engine/card fixture pattern (ShadowspearPaymentDomainV5RedCharacterizationTest) as
 * known reproducer evidence only — there is no card-specific behavior under test.
 */
class C1LivePaymentChoiceBoundaryCharacterizationTest : FunSpec({

    test("TRUSTED_PAYMENT_DOMAIN_COMPLETE: V5 publishes the complete two-bucket choice domain") {
        val prepared = preparedTwoWhiteBuckets()
        val domain = paymentDomainFor(prepared)

        domain.requiredCost shouldBe "{1}"
        domain.outerAtomicCostUnits shouldHaveSize 1
        domain.outerAtomicCostUnits.single().kind shouldBe PaymentCostKindV1.GENERIC
        // The complete domain names both provenance-distinct certified buckets with exact
        // producing-source identity, in canonical keyed order.
        domain.initialPoolBuckets shouldHaveSize 2
        val certified = domain.initialPoolBuckets.map {
            it.key.shouldBeInstanceOf<InitialPoolBucketKeyV1.CertifiedFloatingBucket>()
        }
        certified.map { it.key.sourceId }.toSet() shouldBe prepared.landIds.toSet()
        certified.map { it.key.poolColor }.toSet() shouldBe setOf(PaymentManaColor.WHITE)
    }

    test("MULTIPLE_VALID_EXPLICIT_V3_PLANS: both bucket choices pass the trusted gym preflight") {
        val prepared = preparedTwoWhiteBuckets()
        val domain = paymentDomainFor(prepared)
        val activation = paidActivation(prepared)

        // Both plans are built purely from the published public domain — no GameState-derived
        // choice, no private provenance, no solver.
        val planA = singleBucketPlan(domain, domain.initialPoolBuckets[0].key)
        val planB = singleBucketPlan(domain, domain.initialPoolBuckets[1].key)

        ActionPaymentPlanValidator.requireOrdinary(
            state = prepared.environment.state,
            legalAction = activation,
            submitted = (activation.action as ActivateAbility).copy(
                paymentStrategy = PaymentStrategy.ExplicitV3(paymentPlan = planA),
            ),
            observationBuilder = ObservationBuilder(cardRegistry = prepared.cardRegistry),
        )
        ActionPaymentPlanValidator.requireOrdinary(
            state = prepared.environment.state,
            legalAction = activation,
            submitted = (activation.action as ActivateAbility).copy(
                paymentStrategy = PaymentStrategy.ExplicitV3(paymentPlan = planB),
            ),
            observationBuilder = ObservationBuilder(cardRegistry = prepared.cardRegistry),
        )
    }

    test("EXACT_EXECUTION_WORKS: each plan spends exactly its bucket and remaining provenance differs") {
        val preparedA = preparedTwoWhiteBuckets()
        val preparedB = preparedTwoWhiteBuckets()
        val domainA = paymentDomainFor(preparedA)
        val domainB = paymentDomainFor(preparedB)

        val sourceA = domainA.initialPoolBuckets[0]
            .key.shouldBeInstanceOf<InitialPoolBucketKeyV1.CertifiedFloatingBucket>().key.sourceId
        val sourceB = domainB.initialPoolBuckets[1]
            .key.shouldBeInstanceOf<InitialPoolBucketKeyV1.CertifiedFloatingBucket>().key.sourceId

        preparedA.environment.step(
            (paidActivation(preparedA).action as ActivateAbility).copy(
                paymentStrategy = PaymentStrategy.ExplicitV3(
                    singleBucketPlan(domainA, domainA.initialPoolBuckets[0].key),
                ),
            ),
        )
        preparedB.environment.step(
            (paidActivation(preparedB).action as ActivateAbility).copy(
                paymentStrategy = PaymentStrategy.ExplicitV3(
                    singleBucketPlan(domainB, domainB.initialPoolBuckets[1].key),
                ),
            ),
        )

        val poolA = poolOf(preparedA)
        val poolB = poolOf(preparedB)

        // Both executions are exact and remain certified.
        for (pool in listOf(poolA, poolB)) {
            pool.white shouldBe 1
            pool.manaBySource.size shouldBe 1
            pool.manaByFloatingBucket.size shouldBe 1
            pool.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.COMPLETE
        }

        // The surviving source identity is exactly the unselected bucket's source per plan.
        poolA.manaBySource.keys shouldBe setOf(sourceB)
        poolA.manaByFloatingBucket.keys.single().sourceId shouldBe sourceB
        poolB.manaBySource.keys shouldBe setOf(sourceA)
        poolB.manaByFloatingBucket.keys.single().sourceId shouldBe sourceA

        // The two legal choices therefore leave semantically distinct certified pools:
        // choosing between them is policy-relevant, not provably equivalent.
        (poolA.manaBySource.keys != poolB.manaBySource.keys) shouldBe true
    }

    test("LIVE_C1 binding: the exact binding for the paid action is the aggregate AutoPay template") {
        val prepared = preparedTwoWhiteBuckets()
        val snapshot = LivePolicySourceAdapter.fromObservationResult(prepared.observationResult())

        // Every exact JVM binding for the Shadowspear {1} activation carries only the
        // implicit aggregate strategy; no binding names a payment plan.
        val paidBindings = snapshot.exactSourceBindings.sourceBindingOrdinals
            .map { ordinal -> snapshot.exactSourceBindings.exactBindingFor(ordinal) }
            .filterIsInstance<PolicySeatExactBinding.LegalActionBinding>()
            .map { it.legalAction }
            .filter { legalAction ->
                val activate = legalAction.action as? ActivateAbility
                activate?.sourceId == prepared.sourceId && legalAction.manaCostString == "{1}"
            }
        paidBindings shouldHaveSize 1
        val boundAction = paidBindings.single().action.shouldBeInstanceOf<ActivateAbility>()
        boundAction.sourceId shouldBe prepared.sourceId
        boundAction.paymentStrategy shouldBe PaymentStrategy.AutoPay
    }

    test("LIVE_C1_CAN_SELECT_PLAN_A_VS_B = NO: no binding exposes a payment-plan alternative") {
        val prepared = preparedTwoWhiteBuckets()
        val observationResult = prepared.observationResult()
        val snapshot = LivePolicySourceAdapter.fromObservationResult(observationResult)

        // The flat live channel maps each legal action to exactly one binding (identity
        // ordinals); there is no second binding of the same action that could name a different
        // payment plan, and no structured-choice domain is produced for payment plans by any
        // live source today.
        snapshot.snapshot.selectionBindingChannel.sourceBindingOrdinals.toList() shouldBe
            observationResult.observation.legalActions.indices.toList()
        snapshot.snapshot.structuredChoiceDomain shouldBe null

        val paidBindingCount = snapshot.exactSourceBindings.sourceBindingOrdinals
            .map { ordinal -> snapshot.exactSourceBindings.exactBindingFor(ordinal) }
            .filterIsInstance<PolicySeatExactBinding.LegalActionBinding>()
            .count { (it.legalAction.action as? ActivateAbility)?.sourceId == prepared.sourceId }
        paidBindingCount shouldBe 1

        // Contrast: the trusted gym boundary requires and accepts an explicit V3 payload for
        // the very same action (see EXACT_EXECUTION_WORKS / the trusted-payload test). The gap
        // is exactly the missing live payment-choice channel, not missing payment semantics.
    }

    test("trusted gym template+payload pattern distinguishes the plans; id-only submission is refused") {
        val preparedA = preparedTwoWhiteBuckets()
        val gymA = preparedA.gym()
        val viewA = gymA.observe().observation.legalActions.single {
            it.kind == "ActivateAbility" && it.sourceEntityId == preparedA.sourceId && it.manaCost == "{1}"
        }
        val domainA = checkNotNull(viewA.paymentDomain)

        // An action-ID-only live-style submission (implicit payment) is rejected closed.
        shouldThrow<IllegalArgumentException> { gymA.step(viewA.actionId) }

        // An explicit payload naming bucket A executes; the same template with bucket B
        // executes on a fresh identical state — the trusted boundary CAN express the choice.
        val payloadA = explicitPayload(viewA, domainA, domainA.initialPoolBuckets[0].key)
        gymA.step(viewA.actionId, payloadA).diagnostics shouldBe emptyList()
        poolOf(preparedA).manaBySource.keys shouldBe setOf(
            unspentBucketSource(domainA, spentIndex = 0),
        )

        val preparedB = preparedTwoWhiteBuckets()
        val gymB = preparedB.gym()
        val viewB = gymB.observe().observation.legalActions.single {
            it.kind == "ActivateAbility" && it.sourceEntityId == preparedB.sourceId && it.manaCost == "{1}"
        }
        val domainB = checkNotNull(viewB.paymentDomain)
        val payloadB = explicitPayload(viewB, domainB, domainB.initialPoolBuckets[1].key)
        gymB.step(viewB.actionId, payloadB).diagnostics shouldBe emptyList()
        poolOf(preparedB).manaBySource.keys shouldBe setOf(
            unspentBucketSource(domainB, spentIndex = 1),
        )
    }
}) {
    companion object {
        private data class PreparedState(
            val environment: GameEnvironment,
            val cardRegistry: CardRegistry,
            val playerId: EntityId,
            val sourceId: EntityId,
            val landIds: List<EntityId>,
        ) {
            fun gym(): GameGymEnv = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = ObservationBuilder(cardRegistry = cardRegistry),
            )

            fun observationResult() = gym().observe()
        }

        /**
         * Minimal generic fixture (same accepted pattern as the PAYMENT_DOMAIN_01 reproducer):
         * one Shadowspear, two untapped Plains, priority in precombat main, two certified
         * whites floated (one per Plains). Card names are fixture evidence only.
         */
        private fun preparedTwoWhiteBuckets(): PreparedState {
            val cardRegistry = CardRegistry().apply {
                register(PortalSet.cards)
                register(PortalSet.basicLands)
                register(Shadowspear)
            }
            val environment = GameEnvironment.create(cardRegistry)
            environment.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig(
                            "Alice",
                            Deck.of(Shadowspear.name to 1, "Plains" to 4),
                        ),
                        PlayerConfig("Bob", Deck.of("Mountain" to 2)),
                    ),
                    startingHandSize = 1,
                    skipMulligans = true,
                    startingPlayerIndex = 0,
                    format = Format.Standard,
                ),
            )

            var state = environment.state
            while (state.step != Step.PRECOMBAT_MAIN) {
                val pass = environment.legalActions().first { it.action is PassPriority }
                environment.step(pass.action)
                state = environment.state
            }

            val playerId = environment.playerIds.first()
            fun moveNamedToBattlefield(name: String): EntityId {
                val cardId = state.entities.entries.first { (id, container) ->
                    id in state.getZone(playerId, Zone.HAND) + state.getZone(playerId, Zone.LIBRARY) &&
                        container.get<CardComponent>()?.name == name
                }.key
                val sourceZone = state.zones.entries.first { (_, ids) -> cardId in ids }.key
                state = state.moveToZone(cardId, sourceZone, ZoneKey(playerId, Zone.BATTLEFIELD))
                return cardId
            }

            val sourceId = moveNamedToBattlefield(Shadowspear.name)
            val landIds = (0 until 2).map { moveNamedToBattlefield("Plains") }
            environment.restore(state, environment.playerIds, environment.stepCount)

            // Float one certified white from each Plains (mana abilities resolve immediately).
            for (landId in landIds) {
                val manaAction = environment.legalActions().first { legalAction ->
                    val activate = legalAction.action as? ActivateAbility
                    activate?.sourceId == landId && legalAction.affordable
                }
                environment.step(manaAction.action)
            }

            return PreparedState(environment, cardRegistry, playerId, sourceId, landIds)
        }

        private fun paidActivation(prepared: PreparedState): LegalAction =
            prepared.environment.legalActions().single { legalAction ->
                val activate = legalAction.action as? ActivateAbility
                activate?.sourceId == prepared.sourceId && legalAction.manaCostString == "{1}"
            }

        /**
         * The public PaymentDomainV5 for the paid activation, taken from the trusted gym's
         * registered observation view (the public boundary — no internal builder access).
         */
        private fun paymentDomainFor(prepared: PreparedState): PaymentDomainV5 {
            val view = prepared.observationResult().observation.legalActions.single {
                it.kind == "ActivateAbility" && it.sourceEntityId == prepared.sourceId && it.manaCost == "{1}"
            }
            return checkNotNull(view.paymentDomain)
        }

        /** One generic outer unit paid from exactly one published certified bucket. */
        private fun singleBucketPlan(
            domain: PaymentDomainV5,
            bucketKey: InitialPoolBucketKeyV1,
        ): PaymentPlanV3 {
            val unit = domain.outerAtomicCostUnits.single()
            return PaymentPlanV3(
                activations = emptyList(),
                outerAllocation = listOf(
                    PaymentAllocationV1(
                        target = PaymentTargetV1.OuterCostUnit(
                            symbolIndex = unit.symbolIndex,
                            unitIndexWithinSymbol = unit.unitIndexWithinSymbol,
                        ),
                        resource = ManaResourceRefV1.InitialPoolResource(bucketKey),
                    ),
                ),
            )
        }

        private val payloadJson = Json {
            encodeDefaults = true
            classDiscriminator = "type"
            serializersModule = engineSerializersModule
        }

        private fun explicitPayload(
            view: LegalActionView,
            domain: PaymentDomainV5,
            bucketKey: InitialPoolBucketKeyV1,
        ) = buildJsonObject {
            view.actionSemantics?.forEach { (key, value) -> put(key, value) }
            put(
                "paymentStrategy",
                payloadJson.encodeToJsonElement(
                    PaymentStrategy.serializer(),
                    PaymentStrategy.ExplicitV3(singleBucketPlan(domain, bucketKey)),
                ),
            )
        }

        /** The source of the OTHER certified bucket (canonical keyed order), for pool assertions. */
        private fun unspentBucketSource(domain: PaymentDomainV5, spentIndex: Int): EntityId =
            domain.initialPoolBuckets[1 - spentIndex]
                .key.shouldBeInstanceOf<InitialPoolBucketKeyV1.CertifiedFloatingBucket>()
                .key.sourceId

        private fun poolOf(prepared: PreparedState): ManaPoolComponent =
            checkNotNull(
                prepared.environment.state.getEntity(prepared.playerId)?.get<ManaPoolComponent>(),
            )
    }
}
