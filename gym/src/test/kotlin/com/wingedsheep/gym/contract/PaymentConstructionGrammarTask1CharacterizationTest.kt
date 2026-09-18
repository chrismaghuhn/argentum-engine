package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.InitialPoolBucketKeyV1
import com.wingedsheep.engine.core.PaymentManaColor
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.canonicalizeInitialPoolBucketsV1
import com.wingedsheep.engine.core.InitialPoolBucketV1
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.thb.cards.Shadowspear
import com.wingedsheep.mtg.sets.definitions.wth.cards.MindStone
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * C1_LIVE_PAYMENT_CHOICE_BOUNDARY_02A / TASK 1 — test-only characterization scaffold
 * (docs/ml/c1-live-payment-choice-boundary-02a.md).
 *
 * TASK 1 designs the sequential payment-construction grammar and its completeness proof; this
 * file introduces NO production behavior and NO grammar implementation. It pins, against the
 * authoritative published contract only:
 *
 *  T1  the two-bucket pool-only V5 domain (the committed _01 fixture) publishes every public
 *      construction input the designed grammar consumes (outer units, keyed initial-pool
 *      buckets in canonical order, per-activation capability entries).
 *  T2  a paid-activation domain (Mind Stone {1} with an untapped Mountain available) publishes
 *      the complete per-activation public fields the grammar's activation axis needs
 *      (production choices, activation cost units, cost order options, deterministic
 *      non-mana components, certified self damage) — i.e. the grammar needs no GameState
 *      access beyond the published DTO.
 *  T3  RED: no production payment-construction grammar/source primitive exists at this HEAD
 *      (reflective probe; the exact planned type name is documented in the report).
 *  T4  the canonical-identity substrate the design reuses already exists and is stable:
 *      canonicalizeInitialPoolBucketsV1 ordering + A3SemanticJson canonical JSON distinguish
 *      provenance-distinct bucket keys and are repeatable.
 *
 * Evidence run at TASK 1 time; the fixture reuses the accepted gym characterization pattern
 * (ShadowspearPaymentDomainV5RedCharacterizationTest companion helpers) as known reproducer
 * infrastructure only — no card-specific behavior is designed or tested.
 */
class PaymentConstructionGrammarTask1CharacterizationTest : FunSpec({

    test("T1: the two-bucket pool-only V5 domain publishes the complete public construction inputs") {
        val prepared = preparedShadowspearWithLands("Plains", 2)
        floatOneWhiteFromEachUntappedBasicLand(prepared)

        val activation = paidActivation(prepared)
        val domain = ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .paymentDomainV5For(prepared.environment.state, activation)
        domain shouldNotBe null
        val d = domain!!

        // Outer demand axis: one generic atomic unit, published with its exact indices.
        d.requiredCost shouldBe "{1}"
        d.outerAtomicCostUnits shouldHaveSize 1
        d.outerAtomicCostUnits.single().kind shouldBe com.wingedsheep.engine.core.PaymentCostKindV1.GENERIC

        // Resource axis: both provenance-distinct certified buckets, canonical keyed order.
        d.initialPoolBuckets shouldHaveSize 2
        val keys = d.initialPoolBuckets.map { it.key }
        keys.forEach { it.shouldBeInstanceOf<InitialPoolBucketKeyV1.CertifiedFloatingBucket>() }
        keys.map { (it as InitialPoolBucketKeyV1.CertifiedFloatingBucket).key.sourceId }.toSet() shouldBe
            prepared.landIds.toSet()
        d.initialPoolBuckets shouldBe canonicalizeInitialPoolBucketsV1(d.initialPoolBuckets)

        // The reserved/budget dimensions of the published contract on this fixture.
        d.reservedOuterLifePayment shouldBe 0
        d.fixedSelfDamageBudget shouldBe null

        // Pool-only shape at this state: the activated sources are tapped, so the activation
        // axis is empty and the grammar's pool-spend degenerate case applies (measured, not
        // assumed — the inventory is printed for the report).
        println("T1 inventory: sourceActivationOptions=${d.sourceActivationOptions.size}")
        d.sourceActivationOptions.forEach { option ->
            println(
                "T1 option: source=${option.sourceId.value} ability=${option.manaAbilityKey} " +
                    "productions=${option.productionChoices.size} " +
                    "actCostUnits=${option.atomicActivationManaCostUnits.size} " +
                    "orders=${option.activationCostOrderOptions.size} " +
                    "nonMana=${option.deterministicNonManaCosts} selfDamage=${option.fixedSelfDamageAmount}",
            )
        }
    }

    test("T2: a paid-activation domain publishes the complete per-activation public fields") {
        val prepared = preparedMindStoneControl()
        val activation = paidActivationFrom(prepared, prepared.environment.legalActions())
        activation.manaCostString shouldBe "{1}"

        val domain = ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .paymentDomainV5For(prepared.environment.state, activation)
        domain shouldNotBe null
        val d = domain!!

        println("T2 inventory: outerUnits=${d.outerAtomicCostUnits.size} " +
            "buckets=${d.initialPoolBuckets.size} options=${d.sourceActivationOptions.size}")
        // Every activation option must publish the exact capability fields the designed grammar
        // consumes; nothing may be missing from the public DTO (no GameState access required).
        d.sourceActivationOptions.isNotEmpty() shouldBe true
        d.sourceActivationOptions.forEach { option ->
            println(
                "T2 option: source=${option.sourceId.value} name=${option.sourceName} " +
                    "ability=${option.manaAbilityKey} productions=${option.productionChoices.size} " +
                    "actCostUnits=${option.atomicActivationManaCostUnits.size} " +
                    "orders=${option.activationCostOrderOptions.size} " +
                    "support=${option.activationSupportKind} " +
                    "nonMana=${option.deterministicNonManaCosts} selfDamage=${option.fixedSelfDamageAmount}",
            )
            option.productionChoices.isNotEmpty() shouldBe true
            option.activationCostOrderOptions.isNotEmpty() shouldBe true
            option.activationSupportKind shouldBe PaymentActivationSupportKindV1.FIXED_MANA_AND_TAP_SELF
        }
    }

    test("T3 RED: no production payment-construction grammar/source primitive exists at this HEAD") {
        // The planned production type (docs/ml/c1-live-payment-choice-boundary-02a.md, TASK 2)
        // must not exist yet; the RED is the missing primitive itself.
        val probe = runCatching {
            Class.forName("com.wingedsheep.gym.contract.PaymentConstructionGrammarV1")
        }
        probe.isFailure shouldBe true
        println("T3 RED: PaymentConstructionGrammarV1 absent (${probe.exceptionOrNull()?.javaClass?.simpleName})")
    }

    test("T4: the canonical-identity substrate exists and separates provenance-distinct keys") {
        val prepared = preparedShadowspearWithLands("Plains", 2)
        floatOneWhiteFromEachUntappedBasicLand(prepared)
        val domain = ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .paymentDomainV5For(prepared.environment.state, paidActivation(prepared))
        domain shouldNotBe null

        val buckets = domain!!.initialPoolBuckets
        fun identity(bucket: InitialPoolBucketV1): String {
            val key = bucket.key.shouldBeInstanceOf<InitialPoolBucketKeyV1.CertifiedFloatingBucket>()
            val json = buildJsonObject {
                put("kind", "CertifiedFloatingBucket")
                put("sourceId", key.key.sourceId.value)
                put("color", key.key.poolColor.name)
                put("subtypes", key.key.sourceSubtypes.joinToString(","))
                put("available", bucket.availableAmount)
            }
            return A3SemanticJson.canonicalJson(json)
        }
        val identities = buckets.map(::identity)
        identities.distinct() shouldHaveSize 2
        val roundTripped: List<String> = buckets.map(::identity)
        roundTripped shouldBe identities
        println("T4 identities: $identities")
    }
}) {
    companion object {
        private data class PreparedState(
            val environment: com.wingedsheep.gym.GameEnvironment,
            val cardRegistry: CardRegistry,
            val playerId: EntityId,
            val sourceId: EntityId,
            val abilityId: com.wingedsheep.sdk.scripting.AbilityId,
            val landIds: List<EntityId>,
        )

        private fun paidActivationFrom(prepared: PreparedState, menu: List<LegalAction>): LegalAction =
            menu.single { legalAction ->
                val activate = legalAction.action as? ActivateAbility
                activate?.sourceId == prepared.sourceId && activate.abilityId == prepared.abilityId
            }

        private fun paidActivation(prepared: PreparedState): LegalAction =
            paidActivationFrom(prepared, prepared.environment.legalActions())

        private fun preparedShadowspearWithLands(landName: String, landCount: Int): PreparedState {
            val cardRegistry = CardRegistry().apply {
                register(PortalSet.cards)
                register(PortalSet.basicLands)
                register(Shadowspear)
            }
            val environment = com.wingedsheep.gym.GameEnvironment.create(cardRegistry)
            environment.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig(
                            "Alice",
                            Deck.of(Shadowspear.name to 1, landName to (landCount + 2)),
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
            val landIds = (0 until landCount).map { moveNamedToBattlefield(landName) }
            environment.restore(state, environment.playerIds, environment.stepCount)

            val abilityId = cardRegistry.requireCard(Shadowspear.name).activatedAbilities[0].id

            return PreparedState(environment, cardRegistry, playerId, sourceId, abilityId, landIds)
        }

        private fun floatOneWhiteFromEachUntappedBasicLand(prepared: PreparedState) {
            for (landId in prepared.landIds) {
                val manaAction = prepared.environment.legalActions().first { legalAction ->
                    val activate = legalAction.action as? ActivateAbility
                    activate?.sourceId == landId && legalAction.affordable
                }
                prepared.environment.step(manaAction.action)
            }
        }

        private fun preparedMindStoneControl(): PreparedState {
            val cardRegistry = CardRegistry().apply {
                register(PortalSet.cards)
                register(PortalSet.basicLands)
                register(MindStone)
            }
            val environment = com.wingedsheep.gym.GameEnvironment.create(cardRegistry)
            environment.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of(MindStone.name to 1, "Mountain" to 2)),
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

            val sourceId = moveNamedToBattlefield(MindStone.name)
            moveNamedToBattlefield("Mountain")
            environment.restore(state, environment.playerIds, environment.stepCount)

            // Mind Stone's paid {1} draw ability is its second activated ability.
            val abilityId = cardRegistry.requireCard(MindStone.name).activatedAbilities[1].id

            return PreparedState(environment, cardRegistry, playerId, sourceId, abilityId, emptyList())
        }
    }
}
