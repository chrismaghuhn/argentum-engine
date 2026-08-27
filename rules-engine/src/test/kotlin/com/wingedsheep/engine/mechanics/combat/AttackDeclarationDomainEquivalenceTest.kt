package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.legalactions.AttackDeclarationDomainValidator
import com.wingedsheep.engine.legalactions.AttackDeclarationValidationResult
import com.wingedsheep.engine.legalactions.RulesAttackDeclarationDomainResult
import com.wingedsheep.engine.mechanics.combat.rules.defaultAttackDefenderRules
import com.wingedsheep.engine.mechanics.combat.rules.defaultAttackRestrictionRules
import com.wingedsheep.engine.mechanics.mana.ManaAbilitySideEffectExecutor
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.ProtectorComponent
import com.wingedsheep.engine.state.components.combat.GoadedComponent
import com.wingedsheep.engine.state.components.combat.MustAttackPlayerComponent
import com.wingedsheep.engine.state.components.combat.MustAttackThisTurnComponent
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.Phase
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.CantBeAttackedWithout
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Exhaustive small-fixture proof that the published factored certificate has the same
 * non-monetary declaration language as the Rules pre-tax validator.
 */
class AttackDeclarationDomainEquivalenceTest : FunSpec({
    test("attack candidates follow combat object rank instead of EntityId value") {
        val fixture = asymmetricDefenderFixture()
        val idsByEntityId = fixture.attackerIds.sortedBy(EntityId::value)
        val rankById = mapOf(
            idsByEntityId[0] to 200L,
            idsByEntityId[1] to 100L,
        )
        val rankedFixture = fixture.copy(
            state = fixture.state.copy(
                objectIdentityStamps = fixture.state.objectIdentityStamps + rankById,
            )
        )

        rankedFixture.certificate().attackerOrder shouldBe
            idsByEntityId.sortedBy { rankedFixture.state.objectIdentityStamps.getValue(it) }
    }

    test("mixed attack defenders follow seat order then combat object order") {
        val fixture = planeswalkerAndBattleDefenderFixture()
        val attacker = fixture.attackerIds.single()
        val playerDefender = fixture.state.activePlayers.first { it != fixture.player }
        val objectDefenders = fixture.defenderIds.sortedBy(EntityId::value).reversed()
        val rankById = objectDefenders.mapIndexed { index, entityId ->
            entityId to (100L + index)
        }.toMap()
        val rankedFixture = fixture.copy(
            state = fixture.state.copy(
                objectIdentityStamps = fixture.state.objectIdentityStamps + rankById,
            )
        )

        rankedFixture.certificate().attackerToDefenders.getValue(attacker) shouldBe
            listOf(playerDefender) + objectDefenders
    }

    test("matches Rules for an asymmetric defender relation") {
        assertEquivalent(asymmetricDefenderFixture())
    }

    test("preserves global defender order when an early attacker sees only the later defender") {
        val fixture = asymmetricDefenderFixture(islandDefenderIndex = 2)
        val result = fixture.manager.getAttackDeclarationDomain(fixture.state, fixture.player)

        (result is RulesAttackDeclarationDomainResult.Supported) shouldBe true
        val domain = (result as RulesAttackDeclarationDomainResult.Supported).domain
        val earlyDefender = fixture.state.activePlayers[1]
        val lateDefender = fixture.state.activePlayers[2]

        domain.defenderOrder shouldBe listOf(earlyDefender, lateDefender)
        domain.attackerToDefenders.getValue(fixture.attackerIds[0]) shouldBe listOf(lateDefender)
        domain.attackerToDefenders.getValue(fixture.attackerIds[1]) shouldBe
            listOf(earlyDefender, lateDefender)
        AttackDeclarationDomainValidator.isStructurallyValid(domain) shouldBe true
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

    test("mandatory attackers preserve attackerOrder rank") {
        val fixture = mandatoryFixture()
        val first = fixture.attackerIds[0]
        val second = fixture.attackerIds[1]
        val ranked = fixture.copy(
            state = fixture.state.copy(
                objectIdentityStamps = fixture.state.objectIdentityStamps +
                    (first to 200L) +
                    (second to 100L),
            ),
        )
        val domain = ranked.certificate()

        domain.attackerOrder shouldBe listOf(second, first)
        domain.mandatoryAttackers shouldBe domain.attackerOrder
    }

    test("co-attacker candidates preserve attackerOrder rank") {
        val fixture = coAttackerOrderingFixture()
        val puma = fixture.attackerIds[0]
        val firstQualifying = fixture.attackerIds[1]
        val secondQualifying = fixture.attackerIds[2]
        val ranked = fixture.copy(
            state = fixture.state.copy(
                objectIdentityStamps = fixture.state.objectIdentityStamps +
                    (puma to 300L) +
                    (firstQualifying to 100L) +
                    (secondQualifying to 200L),
            ),
        )
        val domain = ranked.certificate()

        domain.coAttackerRequirements.getValue(puma).single().anyOf shouldBe
            listOf(firstQualifying, secondQualifying)
    }

    test("band partitions preserve attackerOrder rank") {
        val fixture = fourAttackerBandFixture()
        val ranked = fixture.copy(
            state = fixture.state.copy(
                objectIdentityStamps = fixture.state.objectIdentityStamps +
                    fixture.attackerIds.mapIndexed { index, attackerId ->
                        attackerId to (400L - index * 100L)
                    },
            ),
        )
        val domain = ranked.certificate()
        val defender = ranked.state.activePlayers.first { it != ranked.player }
        val projected = ranked.state.projectedState

        domain.bandConstraints.bandingAttackersByDefender.getValue(defender) shouldBe
            domain.attackerOrder.filter { projected.hasKeyword(it, Keyword.BANDING) }
        domain.bandConstraints.nonBandingAttackersByDefender.getValue(defender) shouldBe
            domain.attackerOrder.filterNot { projected.hasKeyword(it, Keyword.BANDING) }
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

    test("publishes independently retained planeswalker and battle defenders") {
        val fixture = planeswalkerAndBattleDefenderFixture()
        val domain = fixture.certificate()
        val attacker = fixture.attackerIds.single()
        val planeswalker = fixture.defenderIds[0]
        val battle = fixture.defenderIds[1]

        // These defender IDs come directly from fixture setup. This proof deliberately does not
        // use CombatDefenders' candidate-universe helper as an oracle for either publication.
        listOf(planeswalker, battle).forEach { defender ->
            val declaration = DeclareAttackers(
                playerId = fixture.player,
                attackers = mapOf(attacker to defender),
            )

            fixture.manager.validateDeclarationBeforeTax(fixture.state, declaration) shouldBe null
            domain.attackerToDefenders.getValue(attacker) shouldContain defender
        }
    }

    test("does not publish planeswalkers or battles with illegal controller or protector") {
        val fixture = illegalPlaneswalkerAndBattleDefenderFixture()
        val domain = fixture.certificate()
        val attacker = fixture.attackerIds.single()

        fixture.defenderIds.forEach { defender ->
            val declaration = DeclareAttackers(
                playerId = fixture.player,
                attackers = mapOf(attacker to defender),
            )

            (fixture.manager.validateDeclarationBeforeTax(fixture.state, declaration) == null) shouldBe false
            domain.attackerToDefenders.getValue(attacker).contains(defender) shouldBe false
        }
    }

    test("publishes an AttackMode-accepted Battle when its controller is left but protector is not") {
        val fixture = attackModeBattleFixture(controllerIndex = 1, protectorIndex = 2)
        val attacker = fixture.attackerIds.single()
        val battle = fixture.defenderIds.single()
        val declaration = DeclareAttackers(
            playerId = fixture.player,
            attackers = mapOf(attacker to battle),
        )

        fixture.manager.validateDeclarationBeforeTax(fixture.state, declaration) shouldBe null
        // The legacy validAttackTargets hint remains AttackMode-filtered; the certificate must not
        // reuse it because the current Rules validator evaluates this Battle by its controller.
        CombatDefenders
            .getAttackDeclarationCandidateDefenders(fixture.state, fixture.player)
            .contains(battle) shouldBe false
        fixture.certificate().attackerToDefenders.getValue(attacker) shouldContain battle
        assertEquivalent(fixture)
    }

    test("keeps AttackMode Battle controller and protector semantics distinct") {
        val fixture = attackModeBattleFixture(controllerIndex = 2, protectorIndex = 1)
        val attacker = fixture.attackerIds.single()
        val battle = fixture.defenderIds.single()
        val declaration = DeclareAttackers(
            playerId = fixture.player,
            attackers = mapOf(attacker to battle),
        )

        (fixture.manager.validateDeclarationBeforeTax(fixture.state, declaration) == null) shouldBe false
        fixture.certificate().attackerToDefenders.getValue(attacker).contains(battle) shouldBe false
        assertEquivalent(fixture)
    }

    test("retains a Battle-only attacker when every player and planeswalker is restricted") {
        val fixture = battleOnlyDefenderFixture()
        val attacker = fixture.attackerIds.single()
        val player = fixture.defenderIds[0]
        val planeswalker = fixture.defenderIds[1]
        val battle = fixture.defenderIds[2]

        fixture.manager.getAttackDeclarationCandidateAttackers(fixture.state, fixture.player) shouldContain attacker
        fixture.manager.isRestrictedFromAllDefenders(fixture.state, attacker, fixture.player) shouldBe true

        val playerDeclaration = DeclareAttackers(
            playerId = fixture.player,
            attackers = mapOf(attacker to player),
        )
        val planeswalkerDeclaration = DeclareAttackers(
            playerId = fixture.player,
            attackers = mapOf(attacker to planeswalker),
        )
        val battleDeclaration = DeclareAttackers(
            playerId = fixture.player,
            attackers = mapOf(attacker to battle),
        )

        (fixture.manager.validateDeclarationBeforeTax(fixture.state, playerDeclaration) == null) shouldBe false
        (fixture.manager.validateDeclarationBeforeTax(fixture.state, planeswalkerDeclaration) == null) shouldBe false
        fixture.manager.validateDeclarationBeforeTax(fixture.state, battleDeclaration) shouldBe null

        fixture.certificate().attackerToDefenders.getValue(attacker) shouldContain battle
        assertEquivalent(fixture)
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

    test("a four-attacker fixture accepts two disjoint valid bands on both sides") {
        val fixture = fourAttackerBandFixture()
        val domain = fixture.certificate()
        val defender = fixture.state.activePlayers.first { it != fixture.player }
        val declaration = DeclareAttackers(
            playerId = fixture.player,
            attackers = fixture.attackerIds.associateWith { defender },
            bands = listOf(
                setOf(fixture.attackerIds[0], fixture.attackerIds[1]),
                setOf(fixture.attackerIds[2], fixture.attackerIds[3]),
            ),
        )

        val certificateAccepted = AttackDeclarationDomainValidator
            .validate(domain, declaration) is AttackDeclarationValidationResult.Accepted
        val rulesAccepted = fixture.manager.validateDeclarationBeforeTax(fixture.state, declaration) == null

        certificateAccepted shouldBe true
        rulesAccepted shouldBe true
        certificateAccepted shouldBe rulesAccepted
    }
})

private data class Fixture(
    val name: String,
    val state: GameState,
    val player: EntityId,
    val manager: AttackPhaseManager,
    val attackerIds: List<EntityId>,
    val defenderIds: List<EntityId> = emptyList(),
)

private fun Fixture.certificate() = when (val result = manager.getAttackDeclarationDomain(state, player)) {
    is RulesAttackDeclarationDomainResult.Supported -> result.domain
    is RulesAttackDeclarationDomainResult.Unsupported ->
        error("$name published an unsupported attack declaration domain: ${result.reason}")
}

private fun assertEquivalent(fixture: Fixture) {
    // Build both universes directly from the fixture state before constructing or inspecting the
    // certificate. In particular, this test never uses certificate relation keys/values or either
    // Rules candidate helper as a generation source, so helper underpublication is observable.
    val projected = fixture.state.projectedState
    val candidateAttackers = fixture.state.getBattlefield()
        .filter { attackerId ->
            projected.isCreature(attackerId) && projected.getController(attackerId) == fixture.player
        }
        .distinct()
    val candidateDefenders = (
        fixture.state.activePlayers.filter { it != fixture.player } +
            fixture.state.getBattlefield().filter { defenderId ->
                projected.isPlaneswalker(defenderId) || projected.isBattle(defenderId)
            }
        )
        .distinct()
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

private fun asymmetricDefenderFixture(islandDefenderIndex: Int = 1): Fixture {
    val (driver, players) = newDriver(playerCount = 3)
    val active = players[0]
    val islandDefender = players[islandDefenderIndex]
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

private fun coAttackerOrderingFixture(): Fixture {
    val (driver, players) = newDriver()
    val active = players[0]
    val puma = driver.putCreatureOnBattlefield(active, "Scarred Puma")
    val firstQualifying = driver.putCreatureOnBattlefield(active, "Centaur Courser")
    val secondQualifying = driver.putCreatureOnBattlefield(active, "Centaur Courser")
    listOf(puma, firstQualifying, secondQualifying).forEach(driver::removeSummoningSickness)
    return fixture(
        "co-attacker-ordering",
        driver,
        players,
        listOf(puma, firstQualifying, secondQualifying),
    )
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

private fun fourAttackerBandFixture(): Fixture {
    val (driver, players) = newDriver()
    val active = players[0]
    val firstBanding = driver.putCreatureOnBattlefield(active, "Banding Scout")
    val firstNonBanding = driver.putCreatureOnBattlefield(active, "Centaur Courser")
    val secondBanding = driver.putCreatureOnBattlefield(active, "Banding Scout")
    val secondNonBanding = driver.putCreatureOnBattlefield(active, "Centaur Courser")
    listOf(firstBanding, firstNonBanding, secondBanding, secondNonBanding).forEach {
        driver.removeSummoningSickness(it)
    }
    return fixture(
        name = "four-attacker-two-bands",
        driver = driver,
        players = players,
        attackerIds = listOf(firstBanding, firstNonBanding, secondBanding, secondNonBanding),
    )
}

private fun planeswalkerAndBattleDefenderFixture(): Fixture {
    val (driver, players) = newDriver(
        extraCards = listOf(attackDomainWalker, attackDomainSiege),
    )
    val active = players[0]
    val opponent = players[1]
    val attacker = driver.putCreatureOnBattlefield(active, "Grizzly Bears")
    val planeswalker = driver.putPermanentOnBattlefield(opponent, attackDomainWalker.name)
    val battle = driver.putPermanentOnBattlefield(active, attackDomainSiege.name)
    driver.replaceState(driver.state.updateEntity(battle) {
        it.with(ProtectorComponent(opponent))
    })
    driver.removeSummoningSickness(attacker)

    return fixture(
        name = "planeswalker-and-battle-defenders",
        driver = driver,
        players = players,
        attackerIds = listOf(attacker),
        defenderIds = listOf(planeswalker, battle),
    )
}

private fun illegalPlaneswalkerAndBattleDefenderFixture(): Fixture {
    val (driver, players) = newDriver(
        extraCards = listOf(attackDomainWalker, attackDomainSiege),
    )
    val active = players[0]
    val planeswalker = driver.putPermanentOnBattlefield(active, attackDomainWalker.name)
    val battle = driver.putPermanentOnBattlefield(active, attackDomainSiege.name)
    val attacker = driver.putCreatureOnBattlefield(active, "Grizzly Bears")
    driver.replaceState(driver.state.updateEntity(battle) {
        it.with(ProtectorComponent(active))
    })
    driver.removeSummoningSickness(attacker)

    return fixture(
        name = "illegal-planeswalker-and-battle-defenders",
        driver = driver,
        players = players,
        attackerIds = listOf(attacker),
        defenderIds = listOf(planeswalker, battle),
    )
}

private fun attackModeBattleFixture(controllerIndex: Int, protectorIndex: Int): Fixture {
    val (driver, players) = newDriver(
        playerCount = 3,
        extraCards = listOf(attackDomainSiege),
    )
    val active = players[0]
    val attacker = driver.putCreatureOnBattlefield(active, "Grizzly Bears")
    val battle = driver.putPermanentOnBattlefield(players[controllerIndex], attackDomainSiege.name)
    driver.replaceState(
        driver.state.updateEntity(battle) {
            it.with(ProtectorComponent(players[protectorIndex]))
        }.copy(attackMode = AttackMode.LEFT),
    )
    driver.removeSummoningSickness(attacker)

    return fixture(
        name = "attack-mode-battle-controller-$controllerIndex-protector-$protectorIndex",
        driver = driver,
        players = players,
        attackerIds = listOf(attacker),
        defenderIds = listOf(battle),
    )
}

private fun battleOnlyDefenderFixture(): Fixture {
    val (driver, players) = newDriver(
        extraCards = listOf(attackDomainWalker, attackDomainSiege, attackDomainMoat),
    )
    val active = players[0]
    val opponent = players[1]
    val attacker = driver.putCreatureOnBattlefield(active, "Grizzly Bears")
    val planeswalker = driver.putPermanentOnBattlefield(opponent, attackDomainWalker.name)
    driver.putPermanentOnBattlefield(opponent, attackDomainMoat.name)
    val battle = driver.putPermanentOnBattlefield(active, attackDomainSiege.name)
    driver.replaceState(driver.state.updateEntity(battle) {
        it.with(ProtectorComponent(opponent))
    })
    driver.removeSummoningSickness(attacker)

    return fixture(
        name = "battle-only-defender",
        driver = driver,
        players = players,
        attackerIds = listOf(attacker),
        defenderIds = listOf(opponent, planeswalker, battle),
    )
}

private fun fixture(
    name: String,
    driver: GameTestDriver,
    players: List<EntityId>,
    attackerIds: List<EntityId>,
    defenderIds: List<EntityId> = emptyList(),
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
    return Fixture(name, driver.state, active, manager, attackerIds, defenderIds)
}

private fun newDriver(
    playerCount: Int = 2,
    extraCards: List<CardDefinition> = emptyList(),
): Pair<GameTestDriver, List<EntityId>> {
    val driver = GameTestDriver()
    driver.registerCards(TestCards.all + extraCards)
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

private val attackDomainWalker = card("Attack Domain Walker") {
    manaCost = "{2}{U}{U}"
    typeLine = "Planeswalker — Test"
    startingLoyalty = 4
}

private val attackDomainSiege = card("Attack Domain Siege") {
    manaCost = "{2}{B}{B}"
    typeLine = "Battle — Siege"
    startingDefense = 5
}

private val attackDomainMoat = card("Attack Domain Moat") {
    manaCost = "{2}{W}"
    typeLine = "Enchantment"
    staticAbility {
        ability = CantBeAttackedWithout(Keyword.FLYING)
    }
}
