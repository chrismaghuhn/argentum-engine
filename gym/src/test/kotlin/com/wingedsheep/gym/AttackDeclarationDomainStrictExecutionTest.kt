package com.wingedsheep.gym

import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.SelectManaSourcesDecision
import com.wingedsheep.engine.core.AttackersDeclaredEvent
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.LegalActionView
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.definitions.arn.ArabianNightsSet
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.lgn.LegionsSet
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AttackDeclarationDomainStrictExecutionTest : FunSpec({
    test("out-of-domain attack choices are rejected atomically before Rules execution") {
        val prepared = prepareAttack()
        val environment = prepared.environment
        val attack = attackView(prepared.gym)
        val domain = attack.attackDeclarationDomain
        domain shouldNotBe null
        val attacker = domain!!.attackerToDefenders.keys.single()
        val payload = attackPayload(attack, attacker, EntityId("unpublished-defender"))

        val stateBefore = environment.state
        val stepCountBefore = environment.stepCount
        val lastStepEventsBefore = environment.lastStepEvents

        val failure = shouldThrow<IllegalArgumentException> {
            prepared.gym.step(attack.actionId, payload)
        }

        failure.message shouldBe
            "Attack declaration is outside the registered domain: INVALID_DEFENDER"
        environment.state shouldBe stateBefore
        environment.stepCount shouldBe stepCountBefore
        environment.lastStepEvents shouldBe lastStepEventsBefore
        environment.state.step shouldBe Step.DECLARE_ATTACKERS
        environment.state.priorityPlayerId shouldBe prepared.alice
        environment.playerIds shouldBe listOf(prepared.alice, prepared.bob)
    }

    test("action-ID-only combat submission still requires both explicit payload fields") {
        val prepared = prepareAttack()
        val attack = attackView(prepared.gym)

        val failure = shouldThrow<IllegalArgumentException> {
            prepared.gym.step(attack.actionId)
        }

        failure.message shouldContain "requires a structured action payload"
    }

    test("a declaration accepted by the snapshot reaches the existing Rules combat step") {
        val prepared = prepareAttack()
        val attack = attackView(prepared.gym)
        val domain = checkNotNull(attack.attackDeclarationDomain)
        val attacker = domain.attackerToDefenders.keys.single()
        val defender = domain.attackerToDefenders.getValue(attacker).single()
        val stepCountBefore = prepared.environment.stepCount

        prepared.gym.step(attack.actionId, attackPayload(attack, attacker, defender))

        prepared.environment.stepCount shouldBe stepCountBefore + 1
        prepared.environment.lastStepEvents.filterIsInstance<AttackersDeclaredEvent>().size shouldBe 1
        prepared.environment.state.getEntity(attacker)?.get<AttackingComponent>() shouldNotBe null
        prepared.environment.state.pendingDecision shouldBe null
    }

    test("a valid non-empty band travels through trusted Gym submission into Rules execution") {
        val prepared = prepareAttack(includeBandAttackers = true)
        val attack = attackView(prepared.gym)
        val domain = checkNotNull(attack.attackDeclarationDomain)
        val defender = domain.attackerToDefenders.getValue(prepared.bandAttackers.first()).single()
        val assignments = prepared.bandAttackers.associateWith { defender }
        val stepCountBefore = prepared.environment.stepCount

        prepared.gym.step(
            attack.actionId,
            attackPayload(attack, assignments, listOf(prepared.bandAttackers.toSet())),
        )

        prepared.environment.stepCount shouldBe stepCountBefore + 1
        prepared.environment.lastStepEvents.filterIsInstance<AttackersDeclaredEvent>().size shouldBe 1
        val bandIds = prepared.bandAttackers.map { attacker ->
            prepared.environment.state.getEntity(attacker)?.get<AttackingComponent>()?.bandId
        }
        bandIds.first() shouldNotBe null
        bandIds.distinct().size shouldBe 1
    }

    test("an invalid non-empty band is rejected atomically before Rules execution") {
        val prepared = prepareAttack(includeBandAttackers = true)
        val attack = attackView(prepared.gym)
        val domain = checkNotNull(attack.attackDeclarationDomain)
        val defender = domain.attackerToDefenders.getValue(prepared.bandAttackers.first()).single()
        val assignments = prepared.bandAttackers.associateWith { defender }
        val payload = attackPayload(
            attack,
            assignments,
            listOf(setOf(prepared.bandAttackers.first())),
        )
        val stateBefore = prepared.environment.state
        val stepCountBefore = prepared.environment.stepCount
        val lastStepEventsBefore = prepared.environment.lastStepEvents

        val failure = shouldThrow<IllegalArgumentException> {
            prepared.gym.step(attack.actionId, payload)
        }

        failure.message shouldContain "Attack declaration is outside the registered domain"
        prepared.environment.state shouldBe stateBefore
        prepared.environment.stepCount shouldBe stepCountBefore
        prepared.environment.lastStepEvents shouldBe lastStepEventsBefore
    }

    test("a structured actor mismatch is rejected as external input before domain validation") {
        val prepared = prepareAttack()
        val attack = attackView(prepared.gym)
        val domain = checkNotNull(attack.attackDeclarationDomain)
        val attacker = domain.attackerToDefenders.keys.single()
        val defender = domain.attackerToDefenders.getValue(attacker).single()
        val payload = buildJsonObject {
            attack.actionSemantics!!.forEach { (key, value) -> put(key, value) }
            put("playerId", prepared.bob.value)
            put("attackers", buildJsonObject { put(attacker.value, defender.value) })
            put("bands", buildJsonArray {})
        }
        val stateBefore = prepared.environment.state
        val stepCountBefore = prepared.environment.stepCount
        val lastStepEventsBefore = prepared.environment.lastStepEvents

        val failure = shouldThrow<IllegalArgumentException> {
            prepared.gym.step(attack.actionId, payload)
        }

        failure.message shouldBe "Structured action changed its action actor"
        prepared.environment.state shouldBe stateBefore
        prepared.environment.stepCount shouldBe stepCountBefore
        prepared.environment.lastStepEvents shouldBe lastStepEventsBefore
    }

    test("snapshot acceptance is followed by the stale-candidate guard when live state changes") {
        val prepared = prepareAttack()
        val attack = attackView(prepared.gym)
        val domain = checkNotNull(attack.attackDeclarationDomain)
        val attacker = domain.attackerToDefenders.keys.single()
        val defender = domain.attackerToDefenders.getValue(attacker).single()
        val stepCountBefore = prepared.environment.stepCount

        prepared.environment.restore(
            prepared.environment.state.copy(priorityPlayerId = prepared.bob),
            prepared.environment.playerIds,
            stepCountBefore,
        )
        val stateBefore = prepared.environment.state
        val lastStepEventsBefore = prepared.environment.lastStepEvents

        val failure = shouldThrow<IllegalArgumentException> {
            prepared.gym.step(attack.actionId, attackPayload(attack, attacker, defender))
        }

        failure.message shouldContain "Action candidate is not in the current legal action set"
        prepared.environment.state shouldBe stateBefore
        prepared.environment.stepCount shouldBe stepCountBefore
        prepared.environment.lastStepEvents shouldBe lastStepEventsBefore
    }

    test("a valid declaration stops at the existing explicit attack-tax decision boundary") {
        val prepared = prepareAttack(includeAttackTaxer = true)
        val attack = attackView(prepared.gym)
        val domain = checkNotNull(attack.attackDeclarationDomain)
        val attacker = domain.attackerToDefenders.keys.single()
        val defender = domain.attackerToDefenders.getValue(attacker).single()
        val stepCountBefore = prepared.environment.stepCount

        prepared.gym.step(attack.actionId, attackPayload(attack, attacker, defender))

        prepared.environment.stepCount shouldBe stepCountBefore + 1
        val pending = prepared.environment.state.pendingDecision
            .shouldBeInstanceOf<SelectManaSourcesDecision>()
        pending.context.sourceName shouldBe "Attack tax"
        pending.requiredCost shouldBe "{1}{1}"
    }
})

private data class PreparedAttack(
    val environment: GameEnvironment,
    val gym: GameGymEnv,
    val alice: EntityId,
    val bob: EntityId,
    val bandAttackers: List<EntityId> = emptyList(),
)

private fun prepareAttack(
    includeAttackTaxer: Boolean = false,
    includeBandAttackers: Boolean = false,
): PreparedAttack {
    val registry = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
        register(LegionsSet.cards)
        register(ArabianNightsSet.cards)
    }
    val environment = GameEnvironment.create(registry)
    val aliceDeck = buildList {
        add("Mountain" to 99)
        add("Raging Goblin" to 1)
        if (includeBandAttackers) add("Camel" to 2)
    }
    environment.reset(
        GameConfig(
            players = listOf(
                PlayerConfig("Alice", Deck.of(*aliceDeck.toTypedArray())),
                PlayerConfig("Bob", Deck.of("Mountain" to 99, "Windborn Muse" to 1)),
            ),
            startingHandSize = 2,
            skipMulligans = true,
            startingPlayerIndex = 0,
        )
    )
    val alice = environment.playerIds[0]
    val bob = environment.playerIds[1]
    moveCardsIntoHand(environment, alice, listOf("Mountain", "Raging Goblin"))

    val land = findEnvironmentAction(environment) { it.action is PlayLand }
    environment.step(land.action)
    val goblin = findEnvironmentAction(environment) {
        val action = it.action as? CastSpell
        action?.cardId != null && cardName(environment, action.cardId) == "Raging Goblin"
    }
    environment.step(goblin.action)
    val bandAttackers = if (includeBandAttackers) {
        moveCardsIntoBattlefield(environment, alice, "Camel", 2)
    } else {
        emptyList()
    }
    advanceToAttackers(environment, alice)

    if (includeAttackTaxer) {
        val muse = environment.state.getZone(bob, Zone.LIBRARY).firstOrNull { id ->
            cardName(environment, id) == "Windborn Muse"
        } ?: error("Expected Windborn Muse in Bob's library")
        val stateWithMuse = environment.state.moveToZone(
            muse,
            ZoneKey(bob, Zone.LIBRARY),
            ZoneKey(bob, Zone.BATTLEFIELD),
        )
        environment.restore(stateWithMuse, environment.playerIds, environment.stepCount)
    }

    return PreparedAttack(
        environment = environment,
        gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = registry),
        ),
        alice = alice,
        bob = bob,
        bandAttackers = bandAttackers,
    )
}

private fun attackView(gym: GameGymEnv): LegalActionView =
    (gym.observe().observation as TrainingObservation).legalActions
        .first { it.kind == "DeclareAttackers" }

private fun attackPayload(
    view: LegalActionView,
    attacker: EntityId,
    defender: EntityId,
) = attackPayload(view, mapOf(attacker to defender), emptyList())

private fun attackPayload(
    view: LegalActionView,
    attackers: Map<EntityId, EntityId>,
    bands: List<Set<EntityId>>,
) = buildJsonObject {
    view.actionSemantics!!.forEach { (key, value) -> put(key, value) }
    put("attackers", buildJsonObject {
        attackers.forEach { (attacker, defender) -> put(attacker.value, defender.value) }
    })
    put("bands", buildJsonArray {
        bands.forEach { band ->
            add(buildJsonArray { band.forEach { add(JsonPrimitive(it.value)) } })
        }
    })
}

private fun cardName(environment: GameEnvironment, entityId: EntityId): String? =
    environment.state.getEntity(entityId)?.get<CardComponent>()?.name

private fun moveCardsIntoHand(
    environment: GameEnvironment,
    playerId: EntityId,
    names: List<String>,
) {
    var state = environment.state
    names.groupingBy { it }.eachCount().forEach { (name, requiredCount) ->
        val inHand = state.getHand(playerId).count { cardName(environment, it) == name }
        repeat(requiredCount - inHand) {
            val cardId = state.getZone(playerId, Zone.LIBRARY).firstOrNull { id ->
                cardName(environment, id) == name
            } ?: error("Expected $requiredCount copies of $name for $playerId")
            state = state.moveToZone(
                cardId,
                ZoneKey(playerId, Zone.LIBRARY),
                ZoneKey(playerId, Zone.HAND),
            )
        }
    }
    environment.restore(state, environment.playerIds, environment.stepCount)
}

private fun moveCardsIntoBattlefield(
    environment: GameEnvironment,
    playerId: EntityId,
    name: String,
    count: Int,
): List<EntityId> {
    val cardIds = environment.state.getZone(playerId, Zone.LIBRARY)
        .filter { cardName(environment, it) == name }
        .take(count)
    require(cardIds.size == count) { "Expected $count copies of $name for $playerId" }
    var state = environment.state
    cardIds.forEach { cardId ->
        state = state.moveToZone(
            cardId,
            ZoneKey(playerId, Zone.LIBRARY),
            ZoneKey(playerId, Zone.BATTLEFIELD),
        )
    }
    environment.restore(state, environment.playerIds, environment.stepCount)
    return cardIds
}

private fun advanceToAttackers(environment: GameEnvironment, playerId: EntityId) {
    repeat(200) {
        if (environment.agentToAct == playerId && environment.state.step == Step.DECLARE_ATTACKERS) {
            return
        }
        val action = environment.legalActions().firstOrNull { it.action is PassPriority }
            ?: environment.legalActions().firstOrNull {
                val attack = it.action as? DeclareAttackers
                attack?.attackers?.isEmpty() == true
            }
            ?: error(
                "Could not advance to attackers: step=${environment.state.step}, " +
                    "priority=${environment.state.priorityPlayerId}, actions=${environment.legalActions()}"
            )
        environment.step(action.action)
    }
    error("Could not reach DeclareAttackers for $playerId")
}

private fun findEnvironmentAction(
    environment: GameEnvironment,
    predicate: (LegalAction) -> Boolean,
): LegalAction {
    repeat(200) {
        environment.legalActions().firstOrNull(predicate)?.let { return it }
        val action = environment.legalActions().firstOrNull { it.action is PassPriority }
            ?: environment.legalActions().firstOrNull {
                val attack = it.action as? DeclareAttackers
                attack?.attackers?.isEmpty() == true
            }
            ?: error(
                "Could not find action: step=${environment.state.step}, " +
                    "priority=${environment.state.priorityPlayerId}, actions=${environment.legalActions()}"
            )
        environment.step(action.action)
    }
    error("Could not find requested environment action")
}
