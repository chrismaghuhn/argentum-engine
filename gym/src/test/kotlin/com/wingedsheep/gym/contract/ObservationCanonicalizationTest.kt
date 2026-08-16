package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ObservationCanonicalizationTest : FunSpec({

    fun registry(): CardRegistry = CardRegistry().also {
        it.register(PortalSet.cards)
        it.register(PortalSet.basicLands)
    }

    fun environment(): GameEnvironment {
        val env = GameEnvironment.create(registry())
        env.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20))
                ),
                skipMulligans = true,
                startingPlayerIndex = 0
            )
        )
        return env
    }

    fun observation(env: GameEnvironment): TrainingObservation =
        ObservationBuilder().build(env.state, env.playerIds.first(), env.legalActions())
            .observation as TrainingObservation

    test("wire JSON retains transport IDs while semantic JSON excludes them") {
        val base = observation(environment())
        val transportVariant = base.copy(
            legalActions = base.legalActions.mapIndexed { index, action ->
                action.copy(
                    actionId = index + 17,
                    description = "presentation variant $index"
                )
            }
        )

        ObservationCanonicalizer.wireJson(base) shouldNotBe
            ObservationCanonicalizer.wireJson(transportVariant)
        ObservationCanonicalizer.semanticJson(base) shouldBe
            ObservationCanonicalizer.semanticJson(transportVariant)
        StateDigest.compute(base) shouldBe StateDigest.compute(transportVariant)
    }

    test("structured legal-action changes affect semantic identity without using description") {
        val base = observation(environment())
        val first = base.legalActions.first()
        val structuredVariant = base.copy(
            legalActions = listOf(
                first.copy(
                    affordable = !first.affordable,
                    description = "the same presentation text"
                )
            ) + base.legalActions.drop(1)
        )

        ObservationCanonicalizer.semanticJson(base) shouldNotBe
            ObservationCanonicalizer.semanticJson(structuredVariant)
        StateDigest.compute(base) shouldNotBe StateDigest.compute(structuredVariant)
    }

    test("set and map insertion order does not change canonical wire JSON") {
        val base = observation(environment())
        val cardZoneIndex = base.zones.indexOfFirst { it.cards.isNotEmpty() }
        val cardIndex = base.zones[cardZoneIndex].cards.indexOfFirst { true }
        val card = base.zones[cardZoneIndex].cards[cardIndex]
        val reordered = card.copy(
            types = linkedSetOf("TYPE_B", "TYPE_A"),
            subtypes = linkedSetOf("SUBTYPE_B", "SUBTYPE_A"),
            colors = linkedSetOf("COLOR_B", "COLOR_A"),
            keywords = linkedSetOf("KEYWORD_B", "KEYWORD_A"),
            counters = linkedMapOf("counter-b" to 2, "counter-a" to 1)
        )
        val sameSetDifferentInsertionOrder = reordered.copy(
            types = linkedSetOf("TYPE_A", "TYPE_B"),
            subtypes = linkedSetOf("SUBTYPE_A", "SUBTYPE_B"),
            colors = linkedSetOf("COLOR_A", "COLOR_B"),
            keywords = linkedSetOf("KEYWORD_A", "KEYWORD_B"),
            counters = linkedMapOf("counter-a" to 1, "counter-b" to 2)
        )
        fun withCard(replacement: EntityFeatures): TrainingObservation = base.copy(
            zones = base.zones.mapIndexed { index, zone ->
                if (index != cardZoneIndex) zone else zone.copy(
                    cards = zone.cards.mapIndexed { nestedIndex, nested ->
                        if (nestedIndex == cardIndex) replacement else nested
                    }
                )
            }
        )

        ObservationCanonicalizer.wireJson(withCard(reordered)) shouldBe
            ObservationCanonicalizer.wireJson(withCard(sameSetDifferentInsertionOrder))
    }

    test("rules-significant stack order remains observable") {
        val base = observation(environment())
        val lower = StackItemView(
            entityId = EntityId("stack-lower"),
            controllerId = base.perspectivePlayerId,
            sourceEntityId = EntityId("source-lower"),
            name = "Lower",
            kind = StackItemKind.SPELL
        )
        val upper = lower.copy(
            entityId = EntityId("stack-upper"),
            sourceEntityId = EntityId("source-upper"),
            name = "Upper"
        )
        val ordered = base.copy(stack = listOf(lower, upper))
        val reversed = base.copy(stack = listOf(upper, lower))

        ObservationCanonicalizer.semanticJson(ordered) shouldNotBe
            ObservationCanonicalizer.semanticJson(reversed)
    }

    test("hidden hand identity is absent from both canonical forms") {
        val env = environment()
        val opponent = env.playerIds[1]
        val hiddenId = env.state.getHand(opponent).first()
        val replacement = CardEntityFactory
            .create(registry().requireCard("Raging Goblin"), opponent)
            .get<com.wingedsheep.engine.state.components.identity.CardComponent>()
        val pairedState = env.state.copy(
            entities = env.state.entities + (
                hiddenId to checkNotNull(env.state.entities[hiddenId]).with(checkNotNull(replacement))
                )
        )
        val maskedA = ObservationBuilder().build(env.state, env.playerIds[0], emptyList())
            .observation as TrainingObservation
        val maskedB = ObservationBuilder().build(pairedState, env.playerIds[0], emptyList())
            .observation as TrainingObservation

        val wireA = ObservationCanonicalizer.wireJson(maskedA)
        val wireB = ObservationCanonicalizer.wireJson(maskedB)
        wireA.contains("Raging Goblin") shouldBe false
        wireB.contains("Raging Goblin") shouldBe false
        wireA.contains(hiddenId.value) shouldBe false
        wireB.contains(hiddenId.value) shouldBe false
        ObservationCanonicalizer.semanticJson(maskedA) shouldBe
            ObservationCanonicalizer.semanticJson(maskedB)
    }
})
