package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.mechanics.mana.FloatingManaProvenanceClassification
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.player.ManaPoolComponent
import com.wingedsheep.engine.state.components.player.ManaProvenanceCompleteness
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.thb.cards.Shadowspear
import com.wingedsheep.mtg.sets.definitions.wth.cards.MindStone
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ARENA_ML_01_PAYMENT_DOMAIN_01 — minimal RED characterization of the live-policy payment boundary
 * observed by the ARENA_ML_01 smoke (docs/ml/arena-ml-01-smoke-report.md, decision #71).
 *
 * TARGET_BEHAVIOR = future follow-up (a trusted payment-domain publication for this boundary).
 * CURRENT_CHARACTERIZED_BEHAVIOR = unsupported: after one floated white mana is consumed through
 * the legacy proportional provenance seam, the remaining pool carries subtype-only provenance
 * (`manaBySubtype={Plains:1}`, `manaBySource={}`, INCOMPLETE). The Rules classifier names that
 * shape Ambiguous, `toV5InitialPoolBuckets` refuses it, `PaymentDomainBuilder.buildV5` returns
 * null, and the trusted observation emits [DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED] — exactly
 * as accepted at the ARENA_ML_01 boundary.
 *
 * This test is CPU-only and deterministic: the boundary exists before any model request, so no
 * CUDA worker is involved. It characterizes; it does not fix or route around anything. No
 * production file is changed by this task, and there is no card-name special case in production
 * code — Shadowspear appears only as the known reproducer fixture.
 *
 * Evidence ladder:
 *  L1 minimal authoritative state (Shadowspear + two untapped Plains, priority in main phase)
 *  L2 legal-action presence, engine side (menu advertises the paid {1} activation)
 *  L3 payment request construction (the V4 path shares paymentDomainRequestFor with V5 and is
 *     used only as a discriminator; see the individual test for the exact proven statement)
 *  L4 PaymentDomainV5 builder entry reached, then refused
 *  L5 exact rejecting condition: FloatingManaProvenanceClassification.Ambiguous("source and
 *     subtype provenance must both identify the pool") -> toV5InitialPoolBuckets -> null
 *  L6 diagnostic propagation into the whole observation (PAYMENT_DOMAIN_UNSUPPORTED)
 *
 * Controls:
 *  working control 1 — floating two certified whites (both Plains mana abilities) keeps the V5
 *                     domain publishable: the float alone does not create the boundary.
 *  working control 2 — Mind Stone's paid {1} activation publishes a complete V5 domain in the
 *                     same fixture shape, proving paid ability activations are not generically
 *                     unsupported by the trusted path.
 *  negative control — without any mana resource the engine itself lists the activation as not
 *                     affordable, and no spendable source is published for it.
 */
class ShadowspearPaymentDomainV5RedCharacterizationTest : FunSpec({

    test("L1/L2: minimal state — Shadowspear {1} is an engine-legal, advertised-cost candidate") {
        val prepared = preparedShadowspearWithLands("Plains", 2)
        val menu = prepared.environment.legalActions()
        val activation = paidActivationFrom(prepared, menu)

        // The authoritative legal menu itself advertises the printed activation cost.
        activation.affordable shouldBe true
        activation.manaCostString shouldBe "{1}"
    }

    test("working control: floating two certified whites keeps the V5 domain publishable") {
        val prepared = preparedShadowspearWithLands("Plains", 2)
        floatOneWhiteFromEachUntappedBasicLand(prepared)

        val pool = poolOf(prepared)
        pool.white shouldBe 2
        pool.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.COMPLETE
        pool.manaBySource.size shouldBe 2

        val domain = ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .paymentDomainV5For(prepared.environment.state, paidActivation(prepared))
        domain shouldNotBe null
        domain!!.requiredCost shouldBe "{1}"
    }

    test("RED L3/L5: paying one floated white degrades provenance to the exact boundary shape") {
        val prepared = preparedShadowspearWithLands("Plains", 2)
        floatOneWhiteFromEachUntappedBasicLand(prepared)
        prepared.environment.step(paidActivation(prepared).action)

        // The exact ARENA_ML_01 decision-#71 pool, reproduced engine-authentically.
        val pool = poolOf(prepared)
        pool.white shouldBe 1
        pool.manaBySubtype shouldBe mapOf(Subtype.PLAINS to 1)
        pool.manaBySource shouldBe emptyMap()
        pool.manaByFloatingBucket shouldBe emptyMap()
        pool.manaProvenanceCompleteness shouldBe ManaProvenanceCompleteness.INCOMPLETE

        val classification = FloatingManaProvenanceClassification.classify(pool)
        val ambiguous = classification.shouldBeInstanceOf<FloatingManaProvenanceClassification.Ambiguous>()
        ambiguous.reason shouldBe "source and subtype provenance must both identify the pool"
    }

    test("RED L3: the request seam succeeds at the boundary state — V4 publishes only from a " +
        "clean pool, so the refusal is isolated to initial-pool provenance") {
        val prepared = preparedShadowspearWithLands("Plains", 2)
        floatOneWhiteFromEachUntappedBasicLand(prepared)
        prepared.environment.step(paidActivation(prepared).action)
        val boundaryActivation = paidActivation(prepared)
        val builder = ObservationBuilder(cardRegistry = prepared.cardRegistry)

        // At the boundary state the V4 sibling path — which shares the private
        // paymentDomainRequestFor seam (ability resolution, additional-cost payment,
        // target-cost dependency, spell context) — also returns null. But V4's builder admits
        // only NoTrackedProvenance pools ("paid activation costs therefore still fail closed"
        // per its KDoc), so a V4 null here cannot distinguish request failure from pool
        // admission. The discriminator is therefore the PRE-payment state: same request inputs,
        // two certified whites floating, V4 publishes. That proves the request seam succeeds
        // for this action, isolating the boundary refusal to V5's initial-pool admission.
        val preState = preparedShadowspearWithLands("Plains", 2)
        floatOneWhiteFromEachUntappedBasicLand(preState)
        val preActivation = paidActivation(preState)
        val v4Before = builder.paymentDomainFor(preState.environment.state, preActivation)
        v4Before shouldNotBe null
        v4Before!!.requiredCost shouldBe "{1}"

        ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .paymentDomainFor(prepared.environment.state, boundaryActivation) shouldBe null
        ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .paymentDomainV5For(prepared.environment.state, boundaryActivation) shouldBe null
    }

    test("RED L4/L5: paymentDomainV5For refuses the Shadowspear activation at the boundary state") {
        val prepared = preparedShadowspearWithLands("Plains", 2)
        floatOneWhiteFromEachUntappedBasicLand(prepared)
        prepared.environment.step(paidActivation(prepared).action)

        val activation = paidActivation(prepared)
        activation.affordable shouldBe true

        ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .paymentDomainV5For(prepared.environment.state, activation) shouldBe null
    }

    test("RED L6: the trusted observation carries PAYMENT_DOMAIN_UNSUPPORTED at the boundary state") {
        val prepared = preparedShadowspearWithLands("Plains", 2)
        floatOneWhiteFromEachUntappedBasicLand(prepared)
        prepared.environment.step(paidActivation(prepared).action)

        val result = ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .build(prepared.environment.state, prepared.playerId, prepared.environment.legalActions())

        result.diagnostics.map { it.code } shouldContain DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
    }

    test("working control: Mind Stone's paid {1} activation publishes a complete V5 domain " +
        "in the same fixture shape") {
        val prepared = preparedMindStoneControl()
        val activation = paidActivationFrom(prepared, prepared.environment.legalActions())
        activation.manaCostString shouldBe "{1}"

        val domain = ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .paymentDomainV5For(prepared.environment.state, activation)
        domain shouldNotBe null
        domain!!.requiredCost shouldBe "{1}"
    }

    test("negative control: without any mana resource the engine lists the activation as " +
        "unaffordable and publishes no spendable source") {
        val prepared = preparedShadowspearWithLands("Plains", 0)
        val activation = paidActivationFrom(prepared, prepared.environment.legalActions())

        activation.affordable shouldBe false

        val domain = ObservationBuilder(cardRegistry = prepared.cardRegistry)
            .paymentDomainV5For(prepared.environment.state, activation)
        // No mana resource exists: whatever the publication decision, no spendable source and no
        // pool bucket may be advertised for this action.
        (domain == null || (domain.initialPoolBuckets.isEmpty() && domain.sourceActivationOptions.isEmpty())) shouldBe true
    }
}) {
    companion object {
        private data class PreparedState(
            val environment: GameEnvironment,
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

        /**
         * Minimal faithful state: one player controls Shadowspear and [landCount] untapped [landName]s,
         * holds priority in their precombat main phase, and the engine lists Shadowspear's paid {1}
         * activation. Built with the generic engine/card infrastructure (same fixture pattern as
         * GameGymEnvDeterministicActivatedCostPaymentTest).
         */
        private fun preparedShadowspearWithLands(
            landName: String,
            landCount: Int,
        ): PreparedState {
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

            // Shadowspear's first declared activated ability is the paid {1} ability (the equip
            // {2} ability is second and requires a creature target that this state never has).
            val abilityId = cardRegistry.requireCard(Shadowspear.name).activatedAbilities[0].id

            return PreparedState(
                environment = environment,
                cardRegistry = cardRegistry,
                playerId = playerId,
                sourceId = sourceId,
                abilityId = abilityId,
                landIds = landIds,
            )
        }

        /** Activate each untapped basic land's mana ability once (mana abilities resolve immediately). */
        private fun floatOneWhiteFromEachUntappedBasicLand(prepared: PreparedState) {
            for (landId in prepared.landIds) {
                val manaAction = prepared.environment.legalActions().first { legalAction ->
                    val activate = legalAction.action as? ActivateAbility
                    activate?.sourceId == landId && legalAction.affordable
                }
                prepared.environment.step(manaAction.action)
            }
        }

        private fun poolOf(prepared: PreparedState): ManaPoolComponent =
            checkNotNull(prepared.environment.state.getEntity(prepared.playerId)?.get<ManaPoolComponent>())

        private fun preparedMindStoneControl(): PreparedState {
            val cardRegistry = CardRegistry().apply {
                register(PortalSet.cards)
                register(PortalSet.basicLands)
                register(MindStone)
            }
            val environment = GameEnvironment.create(cardRegistry)
            environment.reset(
                GameConfig(
                    players = listOf(
                        PlayerConfig("Alice", Deck.of(MindStone.name to 1, "Mountain" to 3)),
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

            // Mind Stone's paid {1} draw ability is its second activated ability (the first is
            // the {T} mana ability), matching the established control fixture.
            val abilityId = cardRegistry.requireCard(MindStone.name).activatedAbilities[1].id

            return PreparedState(
                environment = environment,
                cardRegistry = cardRegistry,
                playerId = playerId,
                sourceId = sourceId,
                abilityId = abilityId,
                landIds = emptyList(),
            )
        }
    }
}
