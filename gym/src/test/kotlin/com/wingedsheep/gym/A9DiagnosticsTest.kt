package com.wingedsheep.gym

import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.AssignDamageDecision
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DiagnosticKind
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.engine.registry.CardDefinitionMissingException
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.ObservationCanonicalizer
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.service.SnapshotCodec
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.scripting.AbilityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class A9DiagnosticsTest : FunSpec({

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    fun config() = GameConfig(
        players = listOf(
            PlayerConfig("Alice", Deck.of("Mountain" to 20)),
            PlayerConfig("Bob", Deck.of("Mountain" to 20))
        ),
        skipMulligans = true,
        startingPlayerIndex = 0
    )

    val unsupportedPaymentSource = card("A9 Unsupported Payment Source") {
        typeLine = "Artifact"
        activatedAbility {
            cost = Costs.Composite(Costs.Tap, Costs.SacrificeSelf)
            effect = Effects.AddManaOfChoice()
            manaAbility = true
        }
    }

    fun unsupportedDecision(playerId: EntityId) = AssignDamageDecision(
        id = "legacy-damage",
        playerId = playerId,
        prompt = "Assign damage",
        context = DecisionContext(),
        attackerId = EntityId("attacker"),
        availablePower = 1,
        orderedTargets = listOf(EntityId("target")),
        defenderId = null,
        minimumAssignments = mapOf(EntityId("target") to 1),
        defaultAssignments = mapOf(EntityId("target") to 1),
        hasTrample = false,
        hasDeathtouch = false
    )

    test("ObservationBuilder emits a typed non-wire diagnostic for a missing structured domain") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())
        val result = ObservationBuilder(cardRegistry = registry()).build(
            environment.state.copy(pendingDecision = unsupportedDecision(environment.playerIds.first())),
            environment.playerIds.first(),
            emptyList()
        )

        result.diagnostics.single().kind shouldBe DiagnosticKind.UNSUPPORTED_DECISION
        result.diagnostics.single().code shouldBe DiagnosticCode.STRUCTURED_DECISION_DOMAIN_MISSING
        (result.observation as TrainingObservation).pendingDecision!!.structuredDomain shouldBe null
    }

    test("unsupported action-level payment shapes fail closed with a typed diagnostic") {
        val cardRegistry = registry().apply { register(unsupportedPaymentSource) }
        val environment = GameEnvironment.create(cardRegistry)
        environment.reset(
            config().copy(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20, unsupportedPaymentSource.name to 1)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                )
            )
        )
        val player = environment.playerIds.first()
        val sourceId = environment.state.entities.entries.first { (id, container) ->
            id in environment.state.getZone(player, Zone.HAND) +
                environment.state.getZone(player, Zone.LIBRARY) &&
                container.get<CardComponent>()?.name == unsupportedPaymentSource.name
        }.key
        val from = environment.state.zones.entries.first { (_, ids) -> sourceId in ids }.key
        val state = environment.state.moveToZone(
            sourceId,
            from,
            ZoneKey(player, Zone.BATTLEFIELD),
        )
        val action = com.wingedsheep.engine.legalactions.LegalAction(
            action = ActivateAbility(player, sourceId, AbilityId("payment")),
            actionType = "ActivateAbility",
            description = "Unsupported payment probe",
            manaCostString = "{1}",
        )

        val result = ObservationBuilder(cardRegistry = cardRegistry).build(state, player, listOf(action))
        result.diagnostics.single().code shouldBe DiagnosticCode.PAYMENT_DOMAIN_UNSUPPORTED
        (result.observation as TrainingObservation).legalActions.single().paymentDomain shouldBe null
    }

    test("missing card setup fails at the registry boundary without an episode ledger entry") {
        val environment = GameEnvironment.create(registry())
        val invalidConfig = GameConfig(
            players = listOf(
                PlayerConfig("Alice", Deck.of("missing-card" to 1)),
                PlayerConfig("Bob", Deck.of("Mountain" to 20)),
            ),
            skipMulligans = true,
            startingPlayerIndex = 0,
        )

        val failure = shouldThrow<CardDefinitionMissingException> {
            environment.reset(invalidConfig)
        }

        failure.code shouldBe DiagnosticCode.CARD_DEFINITION_MISSING.name
        environment.diagnostics shouldBe EpisodeDiagnostics.EMPTY
    }

    test("trusted observation records the missing-domain signal once and fails closed") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())
        environment.restore(
            environment.state.copy(pendingDecision = unsupportedDecision(environment.playerIds.first())),
            environment.playerIds,
            environment.stepCount,
            environment.maxSteps
        )
        val gym = GameGymEnv(environment, 0, ObservationBuilder(cardRegistry = registry()))

        shouldThrow<UnsupportedPathFailure> { gym.observe() }
        environment.diagnostics.unsupportedDecisionCount shouldBe 1
        shouldThrow<UnsupportedPathFailure> { gym.observe() }
        environment.diagnostics.unsupportedDecisionCount shouldBe 1

        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)
        shouldThrow<UnsupportedPathFailure> { gym.restore(codec, handle) }
        environment.diagnostics.unsupportedDecisionCount shouldBe 1

        environment.reset(config())
        environment.diagnostics shouldBe EpisodeDiagnostics.EMPTY
    }

    test("fork carries the diagnostic ledger and projection cursor by value") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())
        val playerId = environment.playerIds.first()
        val signal = com.wingedsheep.engine.core.DiagnosticSignal(
            code = DiagnosticCode.STRUCTURED_DECISION_DOMAIN_MISSING,
        )
        val cursor = environment.projectionCursor(playerId)

        environment.recordObservationDiagnostics(cursor, listOf(signal)) shouldBe true
        val fork = environment.fork()

        fork.diagnostics shouldBe environment.diagnostics
        fork.recordObservationDiagnostics(cursor, listOf(signal)) shouldBe false
        fork.diagnostics shouldBe environment.diagnostics
        environment.diagnostics.unsupportedDecisionCount shouldBe 1

        val childOnlySignal = com.wingedsheep.engine.core.DiagnosticSignal(
            code = DiagnosticCode.CARD_DEFINITION_MISSING,
        )
        fork.recordObservationDiagnostics(
            ProjectionCursor(cursor.generation + 1, EntityId("child-perspective")),
            listOf(childOnlySignal),
        ) shouldBe true
        fork.diagnostics.totalCount shouldBe 2
        environment.diagnostics.totalCount shouldBe 1
    }

    test("snapshot restore rolls back the diagnostic ledger and projection cursor") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())
        val playerId = environment.playerIds.first()
        val firstSignal = com.wingedsheep.engine.core.DiagnosticSignal(
            code = DiagnosticCode.STRUCTURED_DECISION_DOMAIN_MISSING,
        )
        val firstCursor = environment.projectionCursor(playerId)
        environment.recordObservationDiagnostics(firstCursor, listOf(firstSignal)) shouldBe true

        val gym = GameGymEnv(environment, 0, ObservationBuilder(cardRegistry = registry()))
        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)

        val secondSignal = com.wingedsheep.engine.core.DiagnosticSignal(
            code = DiagnosticCode.LIBRARY_DESTINATION_UNSUPPORTED,
        )
        environment.recordObservationDiagnostics(
            ProjectionCursor(firstCursor.generation + 1, EntityId("after-snapshot")),
            listOf(secondSignal),
        ) shouldBe true
        environment.diagnostics.totalCount shouldBe 2

        gym.restore(codec, handle)
        environment.diagnostics.events shouldBe listOf(firstSignal)
        environment.projectionCursor(playerId) shouldBe firstCursor
    }

    test("invalid external input does not change diagnostics") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())
        val gym = GameGymEnv(environment, 0, ObservationBuilder(cardRegistry = registry()))
        gym.observe()
        val before = environment.diagnostics

        shouldThrow<IllegalArgumentException> { gym.step(Int.MAX_VALUE) }

        environment.diagnostics shouldBe before
    }

    test("a supported trusted episode has zero diagnostic incidence") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())
        val gym = GameGymEnv(environment, 0, ObservationBuilder(cardRegistry = registry()))

        gym.observe()

        environment.diagnostics shouldBe EpisodeDiagnostics.EMPTY
    }

    test("diagnostic sidecar does not change observation wire bytes or state digest") {
        val environment = GameEnvironment.create(registry())
        environment.reset(config())
        val gym = GameGymEnv(environment, 0, ObservationBuilder(cardRegistry = registry()))
        val before = gym.observe().observation as TrainingObservation
        val beforeWire = ObservationCanonicalizer.wireJson(before)
        val beforeSemantic = ObservationCanonicalizer.semanticJson(before)

        environment.recordObservationDiagnostics(
            ProjectionCursor(environment.projectionCursor(environment.playerIds.first()).generation + 1,
                environment.playerIds.first()),
            listOf(
                com.wingedsheep.engine.core.DiagnosticSignal(
                    code = DiagnosticCode.CARD_DEFINITION_MISSING,
                )
            ),
        ) shouldBe true

        val after = gym.observe().observation as TrainingObservation
        ObservationCanonicalizer.wireJson(after) shouldBe beforeWire
        ObservationCanonicalizer.semanticJson(after) shouldBe beforeSemantic
        after.stateDigest shouldBe before.stateDigest
        beforeWire.contains("diagnostics") shouldBe false
        beforeWire.contains(DiagnosticCode.CARD_DEFINITION_MISSING.name) shouldBe false
    }

    test("parallel environment ledgers remain isolated") {
        val first = GameEnvironment.create(registry())
        val second = GameEnvironment.create(registry())
        first.reset(config())
        second.reset(config())

        first.recordObservationDiagnostics(
            first.projectionCursor(first.playerIds.first()),
            listOf(
                com.wingedsheep.engine.core.DiagnosticSignal(
                    code = DiagnosticCode.CARD_DEFINITION_MISSING,
                )
            ),
        ) shouldBe true

        first.diagnostics.unsupportedCardCount shouldBe 1
        second.diagnostics shouldBe EpisodeDiagnostics.EMPTY
    }

    test("trusted legacy-policy entrypoint signals and fails closed") {
        val environment = GameEnvironment.create(
            registry(),
            executionMode = GameEnvironmentMode.TRUSTED
        )
        environment.reset(config())
        val action = environment.legalActions().first().action

        shouldThrow<UnsupportedPathFailure> { environment.step(action) }

        environment.diagnostics.nativePolicyFallbackCount shouldBe 1
        environment.diagnostics.events.single().code shouldBe DiagnosticCode.TRUSTED_NATIVE_POLICY_FALLBACK
    }
})
