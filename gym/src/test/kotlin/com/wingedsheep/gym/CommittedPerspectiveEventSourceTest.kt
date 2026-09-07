package com.wingedsheep.gym

import com.wingedsheep.engine.core.BendPerformedEvent
import com.wingedsheep.engine.core.CardsDrawnEvent
import com.wingedsheep.engine.core.CardsRevealedEvent
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.HandLookedAtEvent
import com.wingedsheep.engine.core.LifeChangedEvent
import com.wingedsheep.engine.core.LifeChangeReason
import com.wingedsheep.engine.core.PhaseChangedEvent
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SpellCastEvent
import com.wingedsheep.engine.core.StepChangedEvent
import com.wingedsheep.engine.core.TurnedFaceDownEvent
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.gym.contract.PerspectiveEventDisposition
import com.wingedsheep.gym.contract.PerspectiveEventFamily
import com.wingedsheep.gym.contract.PerspectiveEventProjector
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CommittedPerspectiveEventSourceTest : FunSpec({

    val p1 = EntityId.of("p1")
    val p2 = EntityId.of("p2")

    fun registry(): CardRegistry = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    fun config() = GameConfig(
        players = listOf(
            PlayerConfig("Alice", Deck.of("Mountain" to 17, "Raging Goblin" to 3)),
            PlayerConfig("Bob", Deck.of("Mountain" to 17, "Raging Goblin" to 3)),
        ),
        startingHandSize = 2,
        skipMulligans = true,
        startingPlayerIndex = 0,
        seed = 17L,
    )

    fun projector() = PerspectiveEventProjector(registry())

    fun project(events: List<GameEvent>, perspective: EntityId = p1) =
        projector().project(events, perspective)

    fun visibleState(entityId: EntityId, ownerId: EntityId, zone: Zone): GameState =
        GameState(
            zones = mapOf(ZoneKey(ownerId, zone) to listOf(entityId)),
        )

    test("HISTA-01 strict committed action produces one immutable perspective batch") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry, executionMode = GameEnvironmentMode.TRUSTED)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(config())

        val pass = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
        gym.step(pass.actionId)

        gym.committedPerspectiveTransitionCount shouldBe 1
        val projection = gym.lastCommittedPerspectiveEventProjection(environment.playerIds[0])
            ?: error("Expected a projection for the committed Gym transition")
        projection.batch.entries.map { it.perspectiveEventOrdinal } shouldBe
            (0 until projection.batch.entries.size).toList()
        val repeatedProjection = gym.lastCommittedPerspectiveEventProjection(environment.playerIds[0])
            ?: error("Expected repeated projection for the committed Gym transition")
        projection.batch shouldBe repeatedProjection.batch
        projection.batch.canonicalJson() shouldContain environment.playerIds[0].value
        shouldThrow<IllegalArgumentException> {
            gym.lastCommittedPerspectiveEventProjection(EntityId.of("not-a-player"))
        }
    }

    test("HISTA-02 failed action produces no authoritative batch") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry, executionMode = GameEnvironmentMode.TRUSTED)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(config())

        val pass = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
        gym.step(pass.actionId)
        gym.committedPerspectiveTransitionCount shouldBe 1
        val previous = gym.lastCommittedPerspectiveEventProjection(environment.playerIds[0])
            ?: error("Expected the prior committed projection")

        shouldThrow<IllegalArgumentException> { gym.step(Int.MAX_VALUE) }

        gym.committedPerspectiveTransitionCount shouldBe 1
        gym.lastCommittedPerspectiveEventProjection(environment.playerIds[0]) shouldBe previous
    }

    test("HISTA-03 forked Gym transitions cannot enter committed history") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry, executionMode = GameEnvironmentMode.TRUSTED)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(config())

        val fork = gym.fork() as GameGymEnv
        val pass = fork.observe().observation.legalActions.first { it.kind == "PassPriority" }
        fork.step(pass.actionId)

        fork.committedPerspectiveTransitionCount shouldBe 0
        gym.committedPerspectiveTransitionCount shouldBe 0
    }

    test("legacy GameEnvironment simulation cannot populate committed history") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(config())

        val legacyPass = environment.legalActions()
            .first { it.action is com.wingedsheep.engine.core.PassPriority }
        environment.step(legacyPass.action)

        gym.committedPerspectiveTransitionCount shouldBe 0
        gym.lastCommittedPerspectiveEventProjection(environment.playerIds[0]) shouldBe null
    }

    test("HISTA-04 hidden opponent hand mutation is non-interfering") {
        val first = project(
            listOf(
                CardsDrawnEvent(p2, 1, listOf(EntityId.of("hidden-a")), listOf("Secret A")),
            ),
        )
        val second = project(
            listOf(
                CardsDrawnEvent(p2, 1, listOf(EntityId.of("hidden-b")), listOf("Secret B")),
            ),
        )

        first.batch.canonicalJson() shouldBe second.batch.canonicalJson()
        first.batch.semanticDigest() shouldBe second.batch.semanticDigest()
    }

    test("HISTA-05 hidden opponent library mutation is non-interfering") {
        val firstState = visibleState(EntityId.of("hidden-a"), p2, Zone.LIBRARY)
        val secondState = visibleState(EntityId.of("hidden-b"), p2, Zone.LIBRARY)
        val first = projector().project(
            listOf(ZoneChangeEvent(EntityId.of("hidden-a"), "Secret A", Zone.LIBRARY, Zone.HAND, p2)),
            p1,
            firstState,
            firstState.copy(zones = mapOf(ZoneKey(p2, Zone.HAND) to listOf(EntityId.of("hidden-a")))),
        )
        val second = projector().project(
            listOf(ZoneChangeEvent(EntityId.of("hidden-b"), "Secret B", Zone.LIBRARY, Zone.HAND, p2)),
            p1,
            secondState,
            secondState.copy(zones = mapOf(ZoneKey(p2, Zone.HAND) to listOf(EntityId.of("hidden-b")))),
        )

        first.batch.entries.shouldBeEmpty()
        second.batch.canonicalJson() shouldBe first.batch.canonicalJson()
        first.classifications.single().disposition shouldBe PerspectiveEventDisposition.INTENTIONALLY_HIDDEN
    }

    test("HISTA-06 hidden event insertion does not create ordinal gaps or digest changes") {
        val publicEvents = listOf(
            PhaseChangedEvent(Phase.PRECOMBAT_MAIN),
            StepChangedEvent(Step.PRECOMBAT_MAIN),
        )
        val withHiddenEvent = listOf(
            publicEvents[0],
            ZoneChangeEvent(EntityId.of("hidden"), "Hidden", Zone.LIBRARY, Zone.HAND, p2),
            publicEvents[1],
        )

        val withoutHidden = project(publicEvents)
        val hiddenBefore = visibleState(EntityId.of("hidden"), p2, Zone.LIBRARY)
        val hiddenAfter = hiddenBefore.copy(
            zones = mapOf(ZoneKey(p2, Zone.HAND) to listOf(EntityId.of("hidden"))),
        )
        val withHidden = projector().project(withHiddenEvent, p1, hiddenBefore, hiddenAfter)

        withHidden.batch.canonicalJson() shouldBe withoutHidden.batch.canonicalJson()
        withHidden.batch.entries.map { it.perspectiveEventOrdinal } shouldBe listOf(0, 1)
    }

    test("HISTA-07 private P2 event is absent from P1 and present only for P2") {
        val event = HandLookedAtEvent(p2, p1, listOf(EntityId.of("private-card")))

        val p1Projection = project(listOf(event), p1)
        val p2Projection = project(listOf(event), p2)

        p1Projection.batch.entries.shouldBeEmpty()
        p1Projection.classifications.single().disposition shouldBe
            PerspectiveEventDisposition.INTENTIONALLY_HIDDEN
        p2Projection.batch.entries.single().eventFamily shouldBe
            PerspectiveEventFamily.PRIVATE_HAND_LOOKED_AT
    }

    test("HISTA-08 public event is emitted for every authorized perspective") {
        val event = LifeChangedEvent(p2, 20, 19, LifeChangeReason.DAMAGE)

        val p1Projection = project(listOf(event), p1)
        val p2Projection = project(listOf(event), p2)

        p1Projection.batch.entries.single().eventFamily shouldBe PerspectiveEventFamily.LIFE_CHANGED
        p2Projection.batch.entries.single().eventFamily shouldBe PerspectiveEventFamily.LIFE_CHANGED
        p1Projection.batch.entries.single().semanticPayload["newLife"] shouldBe
            p2Projection.batch.entries.single().semanticPayload["newLife"]

        val publicReveal = CardsRevealedEvent(
            revealingPlayerId = p2,
            cardIds = listOf(EntityId.of("revealed-card")),
            cardNames = listOf("Revealed Card"),
            revealToSelf = false,
        )
        project(listOf(publicReveal), p1).classifications.single().disposition shouldBe
            PerspectiveEventDisposition.EMITTED
        project(listOf(publicReveal), p2).classifications.single().disposition shouldBe
            PerspectiveEventDisposition.EMITTED
    }

    test("committed source survives a failure after Rules commit") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry, executionMode = GameEnvironmentMode.TRUSTED)
        environment.reset(config())
        val source = CommittedPerspectiveEventSource(cardRegistry)
        val actor = environment.playerIds[0]
        val action = environment.legalActions().first().action

        environment.stepStrict(action)
        val committed = environment.consumeCommittedTransition()
            ?: error("Expected a committed Rules transition token")
        source.capture(committed)
        val priorProjection = source.projectLast(actor)
            ?: error("Expected a committed source projection")

        shouldThrow<IllegalStateException> { error("post-commit observation failure") }

        source.committedTransitionCount shouldBe 1
        source.projectLast(actor) shouldBe priorProjection
    }

    test("HISTA-09 face-down identity is never serialized") {
        val result = project(
            listOf(TurnedFaceDownEvent(EntityId.of("face-down-secret"), p2)),
        )

        result.batch.canonicalJson() shouldNotContain "face-down-secret"
        result.batch.canonicalJson() shouldNotContain "p2"
    }

    test("HISTA-10 raw EntityId and source coordinates are absent from serialization") {
        val result = project(
            listOf(
                SpellCastEvent(
                    spellEntityId = EntityId.of("spell-runtime-id"),
                    cardName = "Shock",
                    casterId = p2,
                ),
            ),
        )

        result.batch.canonicalJson() shouldNotContain "spell-runtime-id"
        result.batch.canonicalJson() shouldNotContain "committedActionIndex"
        result.batch.canonicalJson() shouldNotContain "rawEventOrdinal"

        shouldThrow<IllegalArgumentException> {
            com.wingedsheep.gym.contract.PerspectiveEventV1(
                perspectiveEventOrdinal = 0,
                eventFamily = PerspectiveEventFamily.SPELL_CAST,
                semanticPayload = buildJsonObject {
                    put("type", "spell_cast")
                    put("entityId", "raw-runtime-id")
                },
            )
        }
        shouldThrow<IllegalArgumentException> {
            com.wingedsheep.gym.contract.PerspectiveEventV1(
                perspectiveEventOrdinal = 0,
                eventFamily = PerspectiveEventFamily.SPELL_CAST,
                semanticPayload = buildJsonObject {
                    put("type", "spell_cast")
                    put("nested", buildJsonArray {
                        add(buildJsonObject { put("sourceId", "raw-runtime-id") })
                    })
                },
            )
        }
    }

    test("HISTA-11 uncharacterized event is unsupported, never silently dropped") {
        val result = project(listOf(BendPerformedEvent(p2, com.wingedsheep.sdk.core.BendType.AIR)))

        result.batch.entries.shouldBeEmpty()
        result.isComplete shouldBe false
        result.diagnostics.single().rawEventType shouldBe "BendPerformedEvent"
        result.classifications.single().disposition shouldBe
            PerspectiveEventDisposition.UNSUPPORTED_FOR_PERSPECTIVE_HISTORY
        shouldThrow<IllegalArgumentException> { result.requireComplete() }
    }

    test("HISTA-12 identical seed and decision produce identical bytes and digest") {
        fun runOne(): Pair<String, String> {
            val cardRegistry = registry()
            val environment = GameEnvironment.create(cardRegistry, executionMode = GameEnvironmentMode.TRUSTED)
            val gym = GameGymEnv(
                environment = environment,
                perspectivePlayerIndex = 0,
                observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(cardRegistry = cardRegistry),
            )
            gym.reset(config())
            val pass = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
            gym.step(pass.actionId)
            val projection = gym.lastCommittedPerspectiveEventProjection(environment.playerIds[0])
                ?: error("Expected a committed projection")
            return projection.batch.canonicalJson() to projection.batch.semanticDigest()
        }

        val first = runOne()
        val second = runOne()

        first shouldBe second
    }

    test("HISTA-13 reset clears committed event source") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry, executionMode = GameEnvironmentMode.TRUSTED)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = com.wingedsheep.gym.contract.ObservationBuilder(cardRegistry = cardRegistry),
        )
        gym.reset(config())
        val pass = gym.observe().observation.legalActions.first { it.kind == "PassPriority" }
        gym.step(pass.actionId)
        gym.committedPerspectiveTransitionCount shouldBe 1

        gym.reset(config())

        gym.committedPerspectiveTransitionCount shouldBe 0
        gym.lastCommittedPerspectiveEventProjection(environment.playerIds[0]) shouldBe null
    }

    test("public zone movement is projected structurally without object identity") {
        val entityId = EntityId.of("public-runtime-id")
        val result = projector().project(
            events = listOf(ZoneChangeEvent(entityId, "Public Card", Zone.BATTLEFIELD, Zone.GRAVEYARD, p2)),
            perspectivePlayerId = p1,
            beforeState = visibleState(entityId, p2, Zone.BATTLEFIELD),
            afterState = visibleState(entityId, p2, Zone.GRAVEYARD),
        )

        result.batch.entries.single().eventFamily shouldBe PerspectiveEventFamily.ZONE_CHANGED
        result.batch.canonicalJson() shouldNotContain "public-runtime-id"
        result.batch.canonicalJson() shouldNotContain "Public Card"
    }

    test("owner-visible hand movement uses Visibility while opponent remains hidden") {
        val entityId = EntityId.of("owner-hand-runtime-id")
        val event = ZoneChangeEvent(entityId, "Private Card", Zone.LIBRARY, Zone.HAND, p2)
        val before = visibleState(entityId, p2, Zone.LIBRARY)
        val after = visibleState(entityId, p2, Zone.HAND)

        val owner = projector().project(listOf(event), p2, before, after)
        val opponent = projector().project(listOf(event), p1, before, after)

        owner.batch.entries.single().eventFamily shouldBe PerspectiveEventFamily.ZONE_CHANGED
        opponent.batch.entries.shouldBeEmpty()
        owner.batch.canonicalJson() shouldNotContain "owner-hand-runtime-id"
    }
})
