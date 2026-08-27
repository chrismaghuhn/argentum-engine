package com.wingedsheep.gym

import com.wingedsheep.engine.core.BlockersDeclaredEvent
import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.engine.state.components.combat.BlockedComponent
import com.wingedsheep.engine.state.components.combat.BlockersDeclaredThisCombatComponent
import com.wingedsheep.engine.state.components.combat.BlockingComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.core.UnsupportedPathFailure
import com.wingedsheep.gym.contract.BlockerDeclarationDomainV1
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.ObservationCanonicalizer
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.inv.InvasionSet
import com.wingedsheep.mtg.sets.definitions.ons.OnslaughtSet
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.uds.UrzasDestinySet
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * End-to-end evidence that a strict Gym controller can use only the published blocker domain.
 * The fixture setup may manipulate an immutable test state, but every submitted assignment is
 * selected from [BlockerDeclarationDomainV1], never from GameState or a native policy.
 */
class BlockerDeclarationDomainStrictExecutionTest : FunSpec({
    test("a public simple block reaches ActionProcessor and creates the authoritative relation") {
        val prepared = prepareBlockers()
        val view = blockerView(prepared.gym)
        val domain = checkNotNull(view.blockerDeclarationDomain)
        val blocker = domain.blockerOrder.single()
        val attacker = domain.attackerOrder.single()
        domain.blockerToAttackers.getValue(blocker) shouldBe listOf(attacker)

        val stepCountBefore = prepared.environment.stepCount
        prepared.gym.step(view.actionId, blockerPayload(view, mapOf(blocker to listOf(attacker))))

        prepared.environment.stepCount shouldBe stepCountBefore + 1
        prepared.environment.state.getEntity(blocker)?.get<BlockingComponent>()?.blockedAttackerIds shouldBe
            listOf(attacker)
        prepared.environment.state.getEntity(attacker)?.get<BlockedComponent>()?.blockerIds shouldBe
            listOf(blocker)
        prepared.environment.state.getEntity(prepared.bob)?.get<BlockersDeclaredThisCombatComponent>() shouldNotBe
            null
        prepared.environment.lastStepEvents.filterIsInstance<BlockersDeclaredEvent>().size shouldBe 1
        prepared.environment.lastStepEvents.filterIsInstance<BlockersDeclaredEvent>().single().blockers shouldBe
            mapOf(blocker to listOf(attacker))
    }

    test("an explicit public empty declaration reaches Rules when no blockers is legal") {
        val prepared = prepareBlockers()
        val view = blockerView(prepared.gym)
        val domain = checkNotNull(view.blockerDeclarationDomain)
        domain.canDeclareZeroBlockers shouldBe true

        prepared.gym.step(view.actionId, blockerPayload(view, emptyMap()))

        prepared.environment.state.getEntity(prepared.bob)?.get<BlockersDeclaredThisCombatComponent>() shouldNotBe
            null
        prepared.environment.state.getEntity(prepared.blockers.single())?.get<BlockingComponent>() shouldBe null
        prepared.environment.state.getEntity(prepared.attackers.single())?.get<BlockedComponent>() shouldBe null
        prepared.environment.lastStepEvents.filterIsInstance<BlockersDeclaredEvent>().single().blockers shouldBe
            emptyMap()
    }

    test("a multiple-choice domain exposes every legal attacker and accepts the selected public edge") {
        val prepared = prepareBlockers(
            attackerNames = listOf("Raging Goblin", "Raging Goblin"),
            blockerNames = listOf("Raging Goblin"),
        )
        val view = blockerView(prepared.gym)
        val domain = checkNotNull(view.blockerDeclarationDomain)
        val blocker = domain.blockerOrder.single()
        val legalAttackers = domain.blockerToAttackers.getValue(blocker)
        legalAttackers shouldContainExactly domain.attackerOrder
        legalAttackers.size shouldBe 2
        domain.maxAttackersByBlocker.getValue(blocker) shouldBe 1

        // The submitted ID is selected from the published domain; the controller does not inspect
        // the authoritative state to discover or validate this alternative.
        val selectedAttacker = legalAttackers.last()
        prepared.gym.step(
            view.actionId,
            blockerPayload(view, mapOf(blocker to listOf(selectedAttacker))),
        )

        prepared.environment.state.getEntity(selectedAttacker)?.get<BlockedComponent>()?.blockerIds shouldBe
            listOf(blocker)
        val otherAttacker = legalAttackers.single { it != selectedAttacker }
        prepared.environment.state.getEntity(otherAttacker)?.get<BlockedComponent>() shouldBe null
    }

    test("a published global blocker restriction is enforced without a Gym-side rules copy") {
        val prepared = prepareBlockers(
            attackerNames = listOf("Raging Goblin"),
            blockerNames = listOf("Raging Goblin", "Raging Goblin"),
            includeDuelingGrounds = true,
        )
        val view = blockerView(prepared.gym)
        val domain = checkNotNull(view.blockerDeclarationDomain)
        domain.globalMaxBlockers shouldBe 1
        domain.blockerOrder.size shouldBe 2
        domain.attackerOrder.size shouldBe 1

        val blocker = domain.blockerOrder.last()
        val attacker = domain.blockerToAttackers.getValue(blocker).single()
        prepared.gym.step(view.actionId, blockerPayload(view, mapOf(blocker to listOf(attacker))))

        prepared.environment.state.getEntity(blocker)?.get<BlockingComponent>()?.blockedAttackerIds shouldBe
            listOf(attacker)
        prepared.environment.state.findEntitiesWith<BlockingComponent>().size shouldBe 1
    }

    test("a public global blocker-cap violation is rejected atomically") {
        val prepared = prepareBlockers(
            attackerNames = listOf("Raging Goblin"),
            blockerNames = listOf("Raging Goblin", "Raging Goblin"),
            includeDuelingGrounds = true,
        )
        val view = blockerView(prepared.gym)
        val domain = checkNotNull(view.blockerDeclarationDomain)
        domain.globalMaxBlockers shouldBe 1
        val payload = blockerPayload(
            view,
            domain.blockerOrder.associateWith { blocker ->
                listOf(domain.blockerToAttackers.getValue(blocker).single())
            },
        )

        assertRejectedWithoutMutation(prepared) {
            prepared.gym.step(view.actionId, payload)
        }
    }

    test("a published must-block requirement executes through the public domain") {
        val prepared = prepareBlockers(
            attackerNames = listOf("Taunting Elf"),
            blockerNames = listOf("Raging Goblin"),
        )
        val view = blockerView(prepared.gym)
        val domain = checkNotNull(view.blockerDeclarationDomain)
        val blocker = domain.blockerOrder.single()
        val attacker = domain.attackerOrder.single()

        domain.requirements shouldBe listOf(
            com.wingedsheep.gym.contract.BlockRequirementV1.BlockOneOf(
                blockerId = blocker,
                attackerIds = listOf(attacker),
            ),
        )
        domain.minimumSatisfiedRequirementCount shouldBe 1
        domain.canDeclareZeroBlockers shouldBe false

        prepared.gym.step(view.actionId, blockerPayload(view, mapOf(blocker to listOf(attacker))))

        prepared.environment.state.getEntity(blocker)?.get<BlockingComponent>()?.blockedAttackerIds shouldBe
            listOf(attacker)
    }

    test("a public mandatory-block requirement cannot be omitted") {
        val prepared = prepareBlockers(
            attackerNames = listOf("Taunting Elf"),
            blockerNames = listOf("Raging Goblin"),
        )
        val view = blockerView(prepared.gym)
        val domain = checkNotNull(view.blockerDeclarationDomain)
        domain.canDeclareZeroBlockers shouldBe false

        assertRejectedWithoutMutation(prepared) {
            prepared.gym.step(view.actionId, blockerPayload(view, emptyMap()))
        }
    }

    test("a CanBlockAnyNumber blocker executes the complete multi-block declaration") {
        val prepared = prepareBlockers(
            attackerNames = listOf("Raging Goblin", "Raging Goblin"),
            blockerNames = listOf("Wall of Glare"),
            includeWallOfGlare = true,
        )
        val view = blockerView(prepared.gym)
        val domain = checkNotNull(view.blockerDeclarationDomain)
        val blocker = domain.blockerOrder.single()
        val attackers = domain.blockerToAttackers.getValue(blocker)
        attackers shouldContainExactly domain.attackerOrder
        domain.maxAttackersByBlocker.getValue(blocker) shouldBe attackers.size

        prepared.gym.step(view.actionId, blockerPayload(view, mapOf(blocker to attackers)))

        prepared.environment.state.getEntity(blocker)?.get<BlockingComponent>()?.blockedAttackerIds shouldBe
            attackers
        attackers.forEach { attacker ->
            prepared.environment.state.getEntity(attacker)?.get<BlockedComponent>()?.blockerIds shouldBe
                listOf(blocker)
        }
    }

    test("out-of-domain blocker submissions leave the complete environment unchanged") {
        val cases = listOf<(PreparedBlockers, LegalActionView) -> kotlinx.serialization.json.JsonObject>(
            { prepared, view ->
                val attacker = checkNotNull(view.blockerDeclarationDomain).attackerOrder.single()
                blockerPayload(view, mapOf(EntityId("unknown-blocker") to listOf(attacker)))
            },
            { _, view ->
                val blocker = checkNotNull(view.blockerDeclarationDomain).blockerOrder.single()
                blockerPayload(view, mapOf(blocker to listOf(EntityId("unknown-attacker"))))
            },
        )

        cases.forEach { makePayload ->
            val prepared = prepareBlockers()
            val view = blockerView(prepared.gym)
            assertRejectedWithoutMutation(prepared) {
                prepared.gym.step(view.actionId, makePayload(prepared, view))
            }
        }
    }

    test("a public over-cap blocker payload is rejected atomically") {
        val prepared = prepareBlockers(
            attackerNames = listOf("Raging Goblin", "Raging Goblin"),
            blockerNames = listOf("Raging Goblin"),
        )
        val view = blockerView(prepared.gym)
        val domain = checkNotNull(view.blockerDeclarationDomain)
        val blocker = domain.blockerOrder.single()
        val payload = blockerPayload(
            view,
            mapOf(blocker to domain.blockerToAttackers.getValue(blocker)),
        )

        assertRejectedWithoutMutation(prepared) {
            prepared.gym.step(view.actionId, payload)
        }
    }

    test("malformed blocker payload is rejected before any authoritative mutation") {
        val prepared = prepareBlockers()
        val view = blockerView(prepared.gym)
        val payload = buildJsonObject {
            view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            put("blockers", JsonPrimitive("not-a-blocker-map"))
        }

        assertRejectedWithoutMutation(prepared) {
            prepared.gym.step(view.actionId, payload)
        }
    }

    test("a stale blocker-domain action is rejected after the live candidate changes") {
        val prepared = prepareBlockers()
        val view = blockerView(prepared.gym)
        val domain = checkNotNull(view.blockerDeclarationDomain)
        val blocker = domain.blockerOrder.single()
        val attacker = domain.attackerOrder.single()

        prepared.environment.restore(
            prepared.environment.state.copy(priorityPlayerId = prepared.alice),
            prepared.environment.playerIds,
            prepared.environment.stepCount,
        )
        assertRejectedWithoutMutation(prepared) {
            prepared.gym.step(view.actionId, blockerPayload(view, mapOf(blocker to listOf(attacker))))
        }
    }

    test("a stale blocker-domain action fails closed if the live certificate becomes unsupported") {
        val prepared = prepareBlockers()
        val view = blockerView(prepared.gym)
        val domain = checkNotNull(view.blockerDeclarationDomain)
        val blocker = domain.blockerOrder.single()
        val attacker = domain.attackerOrder.single()
        val liveState = prepared.environment.state
        val blockerWithoutOrdering = checkNotNull(liveState.getEntity(blocker))
            .without<BattlefieldEntryTimestampComponent>()

        prepared.environment.restore(
            liveState.copy(
                entities = liveState.entities + (blocker to blockerWithoutOrdering),
                objectIdentityStamps = liveState.objectIdentityStamps - blocker,
            ),
            prepared.environment.playerIds,
            prepared.environment.stepCount,
        )
        val stateBefore = prepared.environment.state
        val diagnosticsBefore = prepared.environment.diagnostics
        val stepCountBefore = prepared.environment.stepCount
        val failure = shouldThrow<UnsupportedPathFailure> {
            prepared.gym.step(view.actionId, blockerPayload(view, mapOf(blocker to listOf(attacker))))
        }

        failure.diagnostics.map { it.semanticCode } shouldContainExactly
            listOf("BLOCKER_DECLARATION_DOMAIN_UNSUPPORTED")
        prepared.environment.state shouldBe stateBefore
        prepared.environment.diagnostics shouldBe diagnosticsBefore
        prepared.environment.stepCount shouldBe stepCountBefore
    }

    test("equivalent entity-map construction order preserves blocker-domain semantics") {
        val prepared = prepareBlockers(
            attackerNames = listOf("Raging Goblin", "Raging Goblin"),
            blockerNames = listOf("Raging Goblin", "Raging Goblin"),
        )
        val originalEnvironment = prepared.environment
        val reorderedEnvironment = originalEnvironment.fork()
        val reorderedEntities = linkedMapOf<EntityId, com.wingedsheep.engine.state.ComponentContainer>()
        originalEnvironment.state.entities.entries.reversed().forEach { (id, container) ->
            reorderedEntities[id] = container
        }
        reorderedEnvironment.restore(
            originalEnvironment.state.copy(entities = reorderedEntities),
            originalEnvironment.playerIds,
            originalEnvironment.stepCount,
        )

        val builder = ObservationBuilder(cardRegistry = prepared.cardRegistry)
        val first = builder.build(
            originalEnvironment.state,
            prepared.bob,
            originalEnvironment.legalActions(),
        ).observation as TrainingObservation
        val second = builder.build(
            reorderedEnvironment.state,
            prepared.bob,
            reorderedEnvironment.legalActions(),
        ).observation as TrainingObservation

        val firstDomain = blockerView(first).blockerDeclarationDomain
        val secondDomain = blockerView(second).blockerDeclarationDomain
        firstDomain shouldBe secondDomain
        ObservationCanonicalizer.semanticJson(first) shouldBe ObservationCanonicalizer.semanticJson(second)
        first.stateDigest shouldBe second.stateDigest
    }

    test("hidden opponent information does not alter the defender's blocker domain") {
        val prepared = prepareBlockers()
        val hiddenId = prepared.environment.state.getHand(prepared.alice).firstOrNull()
            ?: prepared.environment.state.getLibrary(prepared.alice).firstOrNull()
            ?: error("Fixture must retain a hidden Alice card")
        val replacement = CardEntityFactory
            .create(prepared.cardRegistry.requireCard("Raging Goblin"), prepared.alice)
            .get<CardComponent>()
            ?: error("Replacement card must have CardComponent")
        val pairedState = prepared.environment.state.copy(
            entities = prepared.environment.state.entities + (
                hiddenId to checkNotNull(prepared.environment.state.entities[hiddenId]).with(replacement)
                )
        )
        val pairedEnvironment = prepared.environment.fork()
        pairedEnvironment.restore(
            pairedState,
            prepared.environment.playerIds,
            prepared.environment.stepCount,
        )
        val builder = ObservationBuilder(cardRegistry = prepared.cardRegistry)
        val first = builder.build(
            prepared.environment.state,
            prepared.bob,
            prepared.environment.legalActions(),
        ).observation as TrainingObservation
        val second = builder.build(
            pairedEnvironment.state,
            prepared.bob,
            pairedEnvironment.legalActions(),
        ).observation as TrainingObservation

        val firstView = blockerView(first)
        val secondView = blockerView(second)
        firstView.blockerDeclarationDomain shouldBe secondView.blockerDeclarationDomain
        ObservationCanonicalizer.semanticActionFingerprint(firstView) shouldBe
            ObservationCanonicalizer.semanticActionFingerprint(secondView)
        first.stateDigest shouldBe second.stateDigest
        ObservationCanonicalizer.semanticJson(first) shouldBe ObservationCanonicalizer.semanticJson(second)
        ObservationCanonicalizer.wireJson(first) shouldNotBe ""
        ObservationCanonicalizer.wireJson(first).contains(hiddenId.value) shouldBe false
    }
})

private data class PreparedBlockers(
    val environment: GameEnvironment,
    val gym: GameGymEnv,
    val cardRegistry: CardRegistry,
    val alice: EntityId,
    val bob: EntityId,
    val attackers: List<EntityId>,
    val blockers: List<EntityId>,
)

private fun prepareBlockers(
    attackerNames: List<String> = listOf("Raging Goblin"),
    blockerNames: List<String> = listOf("Raging Goblin"),
    includeDuelingGrounds: Boolean = false,
    includeWallOfGlare: Boolean = false,
): PreparedBlockers {
    val registry = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        if (includeDuelingGrounds) register(InvasionSet.cards)
        if (includeWallOfGlare) register(UrzasDestinySet.cards)
        if (attackerNames.any { it == "Taunting Elf" } || blockerNames.any { it == "Taunting Elf" }) {
            register(OnslaughtSet.cards)
        }
    }
    val aliceDeck = buildList {
        add("Mountain" to 99)
        attackerNames.groupingBy { it }.eachCount().forEach { (name, count) -> add(name to count) }
        if (includeDuelingGrounds) add("Dueling Grounds" to 1)
    }
    val bobDeck = buildList {
        add("Mountain" to 99)
        blockerNames.groupingBy { it }.eachCount().forEach { (name, count) -> add(name to count) }
    }
    val environment = GameEnvironment.create(registry)
    environment.reset(
        GameConfig(
            players = listOf(
                PlayerConfig("Alice", Deck.of(*aliceDeck.toTypedArray())),
                PlayerConfig("Bob", Deck.of(*bobDeck.toTypedArray())),
            ),
            startingHandSize = 2,
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = 91L,
        ),
    )
    val alice = environment.playerIds[0]
    val bob = environment.playerIds[1]
    val attackers = moveNamedCardsToBattlefield(environment, alice, attackerNames)
    val blockers = moveNamedCardsToBattlefield(environment, bob, blockerNames)
    if (includeDuelingGrounds) {
        moveNamedCardsToBattlefield(environment, alice, listOf("Dueling Grounds"))
    }
    ensureHiddenAliceHandCard(environment, alice)
    advanceToAttackers(environment, alice)
    val attack = environment.legalActions().mapNotNull { it.action as? DeclareAttackers }
        .single { it.attackers.isEmpty() }
    environment.step(attack.copy(attackers = attackers.associateWith { bob }))
    advanceToBlockers(environment, bob)

    return PreparedBlockers(
        environment = environment,
        gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 1,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
        ),
        cardRegistry = registry,
        alice = alice,
        bob = bob,
        attackers = attackers,
        blockers = blockers,
    )
}

private fun ensureHiddenAliceHandCard(environment: GameEnvironment, alice: EntityId) {
    if (environment.state.getHand(alice).isNotEmpty()) return
    val libraryCard = environment.state.getZone(alice, Zone.LIBRARY).firstOrNull()
        ?: error("Fixture must retain a hidden Alice library card")
    val state = environment.state.moveToZone(
        libraryCard,
        ZoneKey(alice, Zone.LIBRARY),
        ZoneKey(alice, Zone.HAND),
    )
    environment.restore(state, environment.playerIds, environment.stepCount)
}

private fun blockerView(gym: GameGymEnv): LegalActionView =
    (gym.observe().observation as TrainingObservation).legalActions
        .single { it.kind == "DeclareBlockers" }

private fun blockerView(observation: TrainingObservation): LegalActionView =
    observation.legalActions.single { it.kind == "DeclareBlockers" }

private fun blockerPayload(
    view: LegalActionView,
    assignments: Map<EntityId, List<EntityId>>,
) = buildJsonObject {
    view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
    put("blockers", buildJsonObject {
        assignments.forEach { (blocker, attackers) ->
            put(blocker.value, kotlinx.serialization.json.buildJsonArray {
                attackers.forEach { add(JsonPrimitive(it.value)) }
            })
        }
    })
}

private fun assertRejectedWithoutMutation(
    prepared: PreparedBlockers,
    action: () -> Unit,
) {
    val stateBefore = prepared.environment.state
    val eventsBefore = prepared.environment.events
    val lastStepEventsBefore = prepared.environment.lastStepEvents
    val diagnosticsBefore = prepared.environment.diagnostics
    val stepCountBefore = prepared.environment.stepCount
    val failure = shouldThrow<IllegalArgumentException> { action() }
    failure.message shouldNotBe null
    prepared.environment.state shouldBe stateBefore
    prepared.environment.events shouldBe eventsBefore
    prepared.environment.lastStepEvents shouldBe lastStepEventsBefore
    prepared.environment.diagnostics shouldBe diagnosticsBefore
    prepared.environment.stepCount shouldBe stepCountBefore
}

private fun moveNamedCardsToBattlefield(
    environment: GameEnvironment,
    playerId: EntityId,
    names: List<String>,
): List<EntityId> {
    var state = environment.state
    val moved = mutableListOf<EntityId>()
    names.forEach { name ->
        val cardId = state.getZone(playerId, Zone.LIBRARY)
            .plus(state.getZone(playerId, Zone.HAND))
            .firstOrNull { id ->
                state.getEntity(id)?.get<CardComponent>()?.name == name
            } ?: error("Expected $name for $playerId")
        val from = when {
            state.getZone(playerId, Zone.LIBRARY).contains(cardId) -> Zone.LIBRARY
            state.getZone(playerId, Zone.HAND).contains(cardId) -> Zone.HAND
            else -> error("Expected $name to be movable for $playerId")
        }
        state = state.moveToZone(
            cardId,
            ZoneKey(playerId, from),
            ZoneKey(playerId, Zone.BATTLEFIELD),
        )
        moved += cardId
    }
    environment.restore(state, environment.playerIds, environment.stepCount)
    return moved
}

private fun advanceToAttackers(environment: GameEnvironment, playerId: EntityId) {
    repeat(200) {
        if (environment.agentToAct == playerId && environment.state.step == Step.DECLARE_ATTACKERS) return
        val action = environment.legalActions().firstOrNull { it.action is PassPriority }
            ?: error(
                "Could not advance to attackers: step=${environment.state.step}, " +
                    "priority=${environment.state.priorityPlayerId}, actions=${environment.legalActions()}"
            )
        environment.step(action.action)
    }
    error("Could not reach DeclareAttackers for $playerId")
}

private fun advanceToBlockers(environment: GameEnvironment, playerId: EntityId) {
    repeat(200) {
        if (environment.agentToAct == playerId && environment.state.step == Step.DECLARE_BLOCKERS) return
        val action = environment.legalActions().firstOrNull { it.action is PassPriority }
            ?: error(
                "Could not advance to blockers: step=${environment.state.step}, " +
                    "priority=${environment.state.priorityPlayerId}, actions=${environment.legalActions()}"
            )
        environment.step(action.action)
    }
    error("Could not reach DeclareBlockers for $playerId")
}
