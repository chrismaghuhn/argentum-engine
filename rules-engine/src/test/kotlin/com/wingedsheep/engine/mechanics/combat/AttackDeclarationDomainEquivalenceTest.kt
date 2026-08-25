package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.legalactions.AttackDeclarationDomainValidator
import com.wingedsheep.engine.legalactions.AttackDeclarationValidationResult
import com.wingedsheep.engine.legalactions.RulesAttackDeclarationDomainResult
import com.wingedsheep.engine.mechanics.combat.rules.defaultAttackDefenderRules
import com.wingedsheep.engine.mechanics.combat.rules.defaultAttackRestrictionRules
import com.wingedsheep.engine.mechanics.mana.ManaAbilitySideEffectExecutor
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.combat.GoadedComponent
import com.wingedsheep.engine.state.components.combat.MustAttackPlayerComponent
import com.wingedsheep.engine.state.components.combat.MustAttackThisTurnComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Exhaustive small-fixture proof that the published factored certificate has the same
 * non-monetary declaration language as the Rules pre-tax validator.
 */
class AttackDeclarationDomainEquivalenceTest : FunSpec({
    test("matches Rules for an asymmetric defender relation") {
        assertEquivalent(asymmetricDefenderFixture())
    }

    test("matches Rules for co-attacker requirements and a global cap") {
        assertEquivalent(coAttackerAndCapFixture())
    }

    test("matches Rules for Taunt and explicit zero-attacker legality") {
        val fixture = tauntFixture()
        assertEquivalent(fixture)
        fixture.certificate().canDeclareZeroAttackers shouldBe false
    }

    test("matches Rules for generic, projected, and this-turn mandatory attackers") {
        assertEquivalent(mandatoryFixture())
    }

    test("matches Rules for Goad when a non-goader defender is available") {
        val fixture = goadNonGoaderFixture()
        assertEquivalent(fixture)
        val domain = fixture.certificate()
        val goaded = fixture.attackerIds.single()
        domain.mandatoryAttackers shouldContain goaded
        domain.attackerToDefenders[goaded]!!.size shouldBe 1
    }

    test("matches Rules for Goad fallback when every legal player is a goader") {
        assertEquivalent(goadFallbackFixture())
    }

    test("the independent four-attacker band generator includes two disjoint bands") {
        val attackers = listOf(
            EntityId("attacker-a"),
            EntityId("attacker-b"),
            EntityId("attacker-c"),
            EntityId("attacker-d"),
        )

        allBandLists(attackers) shouldContain listOf(
            setOf(attackers[0], attackers[1]),
            setOf(attackers[2], attackers[3]),
        )
    }
})

private data class Fixture(
    val name: String,
    val state: GameState,
    val player: EntityId,
    val manager: AttackPhaseManager,
    val attackerIds: List<EntityId>,
)

private fun Fixture.certificate() = when (val result = manager.getAttackDeclarationDomain(state, player)) {
    is RulesAttackDeclarationDomainResult.Supported -> result.domain
    is RulesAttackDeclarationDomainResult.Unsupported ->
        error("$name published an unsupported attack declaration domain: ${result.reason}")
}

private fun assertEquivalent(fixture: Fixture) {
    // Capture both universes before constructing or inspecting the certificate. In particular,
    // this test never uses certificate relation keys or defender values as a generation source.
    val candidateAttackers = fixture.manager
        .getAttackDeclarationCandidateAttackers(fixture.state, fixture.player)
        .sortedBy(EntityId::value)
    val candidateDefenders = CombatDefenders
        .getAttackDeclarationCandidateDefenders(fixture.state, fixture.player)
        .sortedBy(EntityId::value)
    val certificate = fixture.certificate()

    for ((index, declaration) in declarationUniverse(fixture.player, candidateAttackers, candidateDefenders)
        .withIndex()
    ) {
        val certificateAccepted = AttackDeclarationDomainValidator
            .validate(certificate, declaration) is AttackDeclarationValidationResult.Accepted
        val rulesAccepted = fixture.manager.validateDeclarationBeforeTax(fixture.state, declaration) == null
        if (certificateAccepted != rulesAccepted) {
            error(
                "${fixture.name} mismatch at declaration $index: " +
                    "attackers=${declaration.attackers.mapKeys { it.key.value }} " +
                    "bands=${declaration.bands.map { band -> band.map(EntityId::value).sorted() }} " +
                    "certificateAccepted=$certificateAccepted rulesAccepted=$rulesAccepted",
            )
        }
    }
}

private fun declarationUniverse(
    player: EntityId,
    candidateAttackers: List<EntityId>,
    candidateDefenders: List<EntityId>,
): List<DeclareAttackers> =
    allSubsets(candidateAttackers).flatMap { selectedAttackers ->
        defenderAssignments(selectedAttackers, candidateDefenders).flatMap { assignments ->
            allBandLists(selectedAttackers).map { bands ->
                DeclareAttackers(player, assignments, bands)
            }
        }
    }

private fun defenderAssignments(
    attackers: List<EntityId>,
    defenders: List<EntityId>,
): List<Map<EntityId, EntityId>> {
    if (attackers.isEmpty()) return listOf(emptyMap())
    var assignments: List<Map<EntityId, EntityId>> = listOf(emptyMap())
    for (attacker in attackers) {
        assignments = assignments.flatMap { partial ->
            defenders.map { defender ->
                LinkedHashMap(partial).apply { put(attacker, defender) }
            }
        }
    }
    return assignments
}

private fun <T> allSubsets(items: List<T>): List<List<T>> {
    require(items.size < Int.SIZE_BITS - 1)
    return (0 until (1 shl items.size)).map { mask ->
        items.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
    }
}

/** Generate every ordered band-list shape up to the selected attacker count. */
private fun allBandLists(selectedAttackers: List<EntityId>): List<List<Set<EntityId>>> {
    val nonEmptyBands = allSubsets(selectedAttackers)
        .filter { it.isNotEmpty() }
        .map { it.toSet() }
    return (0..selectedAttackers.size).flatMap { length ->
        orderedBandLists(nonEmptyBands, length)
    }
}

private fun orderedBandLists(
    possibleBands: List<Set<EntityId>>,
    length: Int,
): List<List<Set<EntityId>>> {
    if (length == 0) return listOf(emptyList())
    return possibleBands.flatMap { first ->
        orderedBandLists(possibleBands, length - 1).map { rest -> listOf(first) + rest }
    }
}

private fun asymmetricDefenderFixture(): Fixture {
    val (driver, players) = newDriver(playerCount = 3)
    val active = players[0]
    val islandDefender = players[1]
    driver.putLandOnBattlefield(islandDefender, "Island")
    val dandan = driver.putCreatureOnBattlefield(active, "Dandân")
    val courser = driver.putCreatureOnBattlefield(active, "Centaur Courser")
    driver.removeSummoningSickness(dandan)
    driver.removeSummoningSickness(courser)
    return fixture("asymmetric-defender", driver, players, listOf(dandan, courser))
}

private fun coAttackerAndCapFixture(): Fixture {
    val (driver, players) = newDriver()
    val active = players[0]
    val puma = driver.putCreatureOnBattlefield(active, "Scarred Puma")
    val courser = driver.putCreatureOnBattlefield(active, "Centaur Courser")
    driver.putPermanentOnBattlefield(active, "Dueling Grounds")
    driver.removeSummoningSickness(puma)
    driver.removeSummoningSickness(courser)
    return fixture("co-attacker-and-cap", driver, players, listOf(puma, courser))
}

private fun tauntFixture(): Fixture {
    val (driver, players) = newDriver()
    val active = players[0]
    val defender = players[1]
    val first = driver.putCreatureOnBattlefield(active, "Centaur Courser")
    val second = driver.putCreatureOnBattlefield(active, "Banding Scout")
    driver.addComponent(active, MustAttackPlayerComponent(defenderId = defender, activeThisTurn = true))
    driver.removeSummoningSickness(first)
    driver.removeSummoningSickness(second)
    return fixture("taunt", driver, players, listOf(first, second))
}

private fun mandatoryFixture(): Fixture {
    val (driver, players) = newDriver()
    val active = players[0]
    val projectedMustAttack = driver.putCreatureOnBattlefield(active, "Goblin Brigand")
    val mustAttackThisTurn = driver.putCreatureOnBattlefield(active, "Centaur Courser")
    driver.addComponent(mustAttackThisTurn, MustAttackThisTurnComponent)
    driver.removeSummoningSickness(projectedMustAttack)
    driver.removeSummoningSickness(mustAttackThisTurn)
    return fixture("mandatory-attackers", driver, players, listOf(projectedMustAttack, mustAttackThisTurn))
}

private fun goadNonGoaderFixture(): Fixture {
    val (driver, players) = newDriver(playerCount = 3)
    val active = players[0]
    val goader = players[1]
    val creature = driver.putCreatureOnBattlefield(active, "Centaur Courser")
    driver.addComponent(creature, GoadedComponent(setOf(goader)))
    driver.removeSummoningSickness(creature)
    return fixture("goad-non-goader", driver, players, listOf(creature))
}

private fun goadFallbackFixture(): Fixture {
    val (driver, players) = newDriver()
    val active = players[0]
    val goader = players[1]
    val creature = driver.putCreatureOnBattlefield(active, "Centaur Courser")
    driver.addComponent(creature, GoadedComponent(setOf(goader)))
    driver.removeSummoningSickness(creature)
    return fixture("goad-fallback", driver, players, listOf(creature))
}

private fun fixture(
    name: String,
    driver: GameTestDriver,
    players: List<EntityId>,
    attackerIds: List<EntityId>,
): Fixture {
    val active = players[0]
    driver.replaceState(
        driver.state.copy(
            phase = Phase.COMBAT,
            step = Step.DECLARE_ATTACKERS,
            activePlayerId = active,
            priorityPlayerId = active,
        ),
    )
    val manager = AttackPhaseManager(
        driver.cardRegistry,
        defaultAttackRestrictionRules(),
        defaultAttackDefenderRules(),
        ManaAbilitySideEffectExecutor.noOp(driver.cardRegistry),
    )
    return Fixture(name, driver.state, active, manager, attackerIds)
}

private fun newDriver(playerCount: Int = 2): Pair<GameTestDriver, List<EntityId>> {
    val driver = GameTestDriver()
    driver.registerCards(TestCards.all)
    val deck = Deck.of("Forest" to 20, "Grizzly Bears" to 20)
    val players = if (playerCount == 2) {
        driver.initMirrorMatch(deck = deck, skipMulligans = true)
        listOf(driver.player1, driver.player2)
    } else {
        driver.initMultiplayer(
            decks = List(playerCount) { deck },
            skipMulligans = true,
            startingPlayer = 0,
        )
    }
    return driver to players
}
