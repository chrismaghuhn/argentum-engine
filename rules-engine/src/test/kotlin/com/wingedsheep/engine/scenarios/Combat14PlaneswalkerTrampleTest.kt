package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageDealtEvent
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DamageEdgeDirection
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.handlers.actions.decision.DecisionValidators
import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.mechanics.combat.CombatDamageManager
import com.wingedsheep.engine.mechanics.combat.DamageCalculator
import com.wingedsheep.engine.mechanics.layers.Layer
import com.wingedsheep.engine.mechanics.layers.SerializableModification
import com.wingedsheep.engine.mechanics.layers.addFloatingEffect
import com.wingedsheep.engine.state.components.battlefield.CountersComponent
import com.wingedsheep.engine.state.components.battlefield.ProtectorComponent
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.combat.DamageAssignmentComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.player.LossReason
import com.wingedsheep.engine.state.components.player.PlayerLeftGameComponent
import com.wingedsheep.engine.state.components.player.PlayerLostComponent
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.sdk.core.CounterType
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Subtype
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.dsl.card
import com.wingedsheep.sdk.model.CardDefinition
import com.wingedsheep.sdk.model.CardScript
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AssignCombatDamageAsUnblocked
import com.wingedsheep.sdk.scripting.DivideCombatDamageFreely
import com.wingedsheep.sdk.scripting.Duration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Current-rule characterization for ordinary trample into an attacked planeswalker.
 *
 * CR 702.19b permits ordinary trample excess only among the blockers and the object being
 * attacked. CR 702.19c-f describes the separate named "trample over planeswalkers" variant;
 * this engine exposes no such keyword, so that variant is deliberately not modeled here.
 */
class Combat14PlaneswalkerTrampleTest : FunSpec({

    val testWalker = card("C14 Test Walker") {
        manaCost = "{2}"
        typeLine = "Legendary Planeswalker — Tester"
        startingLoyalty = 3
        loyaltyAbility(1) {
            effect = Effects.GainLife(1)
        }
    }

    val testSiege = card("C14 Test Siege") {
        manaCost = "{2}{B}"
        typeLine = "Battle — Siege"
        startingDefense = 10
        oracleText = "(As this Siege enters, choose an opponent to protect it.)"
    }

    val doubleStrikeTrampler = CardDefinition.creature(
        name = "C14 Double Strike Trampler",
        manaCost = ManaCost.parse("{1}{R}"),
        subtypes = setOf(Subtype("Beast")),
        power = 2,
        toughness = 2,
        keywords = setOf(Keyword.DOUBLE_STRIKE, Keyword.TRAMPLE),
    )

    val assignAsUnblockedCreature = CardDefinition.creature(
        name = "C14 Assign As Unblocked",
        manaCost = ManaCost.parse("{1}{R}"),
        subtypes = setOf(Subtype("Beast")),
        power = 5,
        toughness = 5,
        script = CardScript(staticAbilities = listOf(AssignCombatDamageAsUnblocked())),
    )

    val divideFreelyCreature = CardDefinition.creature(
        name = "C14 Divide Freely",
        manaCost = ManaCost.parse("{1}{R}"),
        subtypes = setOf(Subtype("Beast")),
        power = 5,
        toughness = 5,
        script = CardScript(staticAbilities = listOf(DivideCombatDamageFreely())),
    )

    fun driver(): GameTestDriver = GameTestDriver().also {
        it.registerCards(TestCards.all + testWalker + testSiege + assignAsUnblockedCreature + divideFreelyCreature)
    }

    fun rerunCombatDamage(driver: GameTestDriver): ExecutionResult {
        // The driver has already exposed the normal combat-resolution pause. These
        // characterizations deliberately mutate the pre-damage state and re-enter the
        // production manager, without bypassing its candidate, assignment, or prevention paths.
        driver.replaceState(driver.state.copy(pendingDecision = null))
        return CombatDamageManager(
            driver.cardRegistry,
            DamageCalculator(driver.cardRegistry),
        ).applyCombatDamage(driver.state)
    }

    fun seedLoyalty(driver: GameTestDriver, walkerId: com.wingedsheep.sdk.model.EntityId, loyalty: Int) {
        driver.replaceState(driver.state.updateEntity(walkerId) { container ->
            container.with(CountersComponent().withAdded(CounterType.LOYALTY, loyalty))
        })
    }

    fun loyaltyOf(driver: GameTestDriver, walkerId: EntityId): Int =
        driver.state.getEntity(walkerId)?.get<CountersComponent>()?.getCount(CounterType.LOYALTY)
            ?: error("No loyalty counter on $walkerId")

    data class BlockedPlaneSetup(
        val driver: GameTestDriver,
        val attackerPlayer: EntityId,
        val defenderPlayer: EntityId,
        val attacker: EntityId,
        val blockers: List<EntityId>,
        val walker: EntityId,
        val decision: CombatResolutionDecision,
    )

    data class BlockedBattleSetup(
        val driver: GameTestDriver,
        val attackerPlayer: EntityId,
        val defenderPlayer: EntityId,
        val attacker: EntityId,
        val blocker: EntityId,
        val battle: EntityId,
    )

    fun readyBlockedPlane(
        attackerName: String = "Trample Beast",
        blockerNames: List<String> = listOf("Grizzly Bears"),
        loyalty: Int = 10,
    ): BlockedPlaneSetup {
        val driver = driver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        val attackerPlayer = driver.activePlayer!!
        val defenderPlayer = driver.getOpponent(attackerPlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, attackerName)
        val blockers = blockerNames.map { driver.putCreatureOnBattlefield(defenderPlayer, it) }
        val walker = driver.putPermanentOnBattlefield(defenderPlayer, testWalker.name)
        seedLoyalty(driver, walker, loyalty)
        driver.removeSummoningSickness(attacker)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, mapOf(attacker to walker)).error shouldBe null
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(defenderPlayer, blockers.associateWith { listOf(attacker) }).error shouldBe null
        driver.passPriorityUntil(Step.COMBAT_DAMAGE)

        return BlockedPlaneSetup(
            driver = driver,
            attackerPlayer = attackerPlayer,
            defenderPlayer = defenderPlayer,
            attacker = attacker,
            blockers = blockers,
            walker = walker,
            decision = driver.pendingDecision.shouldBeInstanceOf(),
        )
    }

    fun planeDamageEvents(driver: GameTestDriver, sourceId: EntityId, walkerId: EntityId): List<DamageDealtEvent> =
        driver.events.filterIsInstance<DamageDealtEvent>().filter {
            it.sourceId == sourceId && it.targetId == walkerId && it.isCombatDamage
        }

    fun readyBlockedBattle(): BlockedBattleSetup {
        val driver = driver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        val attackerPlayer = driver.activePlayer!!
        val defenderPlayer = driver.getOpponent(attackerPlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, "Trample Beast")
        val blocker = driver.putCreatureOnBattlefield(defenderPlayer, "Grizzly Bears")
        val battle = driver.putPermanentOnBattlefield(defenderPlayer, testSiege.name)
        driver.replaceState(driver.state.updateEntity(battle) {
            it.with(ProtectorComponent(defenderPlayer))
        })
        driver.removeSummoningSickness(attacker)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, mapOf(attacker to battle)).error shouldBe null
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(defenderPlayer, mapOf(blocker to listOf(attacker))).error shouldBe null
        driver.passPriorityUntil(Step.COMBAT_DAMAGE)

        return BlockedBattleSetup(
            driver = driver,
            attackerPlayer = attackerPlayer,
            defenderPlayer = defenderPlayer,
            attacker = attacker,
            blocker = blocker,
            battle = battle,
        )
    }

    test("C14-01 unblocked ordinary trample damages the attacked planeswalker only") {
        val driver = driver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        val attackerPlayer = driver.activePlayer!!
        val defenderPlayer = driver.getOpponent(attackerPlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, "Trample Beast")
        val walker = driver.putPermanentOnBattlefield(defenderPlayer, testWalker.name)
        seedLoyalty(driver, walker, loyalty = 7)
        driver.removeSummoningSickness(attacker)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, mapOf(attacker to walker)).error shouldBe null
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareNoBlockers(defenderPlayer).error shouldBe null
        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        loyaltyOf(driver, walker) shouldBe 2
        driver.assertLifeTotal(defenderPlayer, 20)
        val event = planeDamageEvents(driver, attacker, walker).single()
        event.amount shouldBe 5
        event.targetIsPlayer shouldBe false
    }

    test("C14-02 unblocked non-trample damage also goes only to the attacked planeswalker") {
        val driver = driver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        val attackerPlayer = driver.activePlayer!!
        val defenderPlayer = driver.getOpponent(attackerPlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, "Force of Nature")
        val walker = driver.putPermanentOnBattlefield(defenderPlayer, testWalker.name)
        seedLoyalty(driver, walker, loyalty = 7)
        driver.removeSummoningSickness(attacker)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, mapOf(attacker to walker)).error shouldBe null
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareNoBlockers(defenderPlayer).error shouldBe null
        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        loyaltyOf(driver, walker) shouldBe 2
        driver.assertLifeTotal(defenderPlayer, 20)
        planeDamageEvents(driver, attacker, walker).single().amount shouldBe 5
    }

    test("C14-03 blocked ordinary trample exposes blocker and planeswalker edges") {
        val setup = readyBlockedPlane(blockerNames = listOf("Savannah Lions"), loyalty = 3)
        val decision = setup.decision
        val drain = decision.edges.single { it.isTrampleDrain }

        decision.defenders.single().kind shouldBe com.wingedsheep.engine.core.ResolutionTargetKind.PLANESWALKER
        drain.targetId shouldBe setup.walker
        drain.direction shouldBe DamageEdgeDirection.ATTACKER_TO_PLANESWALKER
        decision.edges.none { it.direction == DamageEdgeDirection.ATTACKER_TO_PLAYER } shouldBe true

        setup.driver.submitCombatDamage(
            mapOf(
                (setup.attacker to setup.blockers.single()) to 1,
                (setup.attacker to setup.walker) to 4,
            )
        ).error shouldBe null
        setup.driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        setup.driver.findPermanent(setup.defenderPlayer, "Savannah Lions").shouldBeNull()
        setup.driver.findPermanent(setup.defenderPlayer, testWalker.name).shouldBeNull()
        setup.driver.assertLifeTotal(setup.defenderPlayer, 20)
    }

    test("C14-04 multiple blockers accept an arbitrary complete legal trample split") {
        val setup = readyBlockedPlane(
            blockerNames = listOf("Savannah Lions", "Grizzly Bears"),
            loyalty = 10,
        )

        setup.decision.edges.count { it.sourceId == setup.attacker } shouldBe 3
        setup.decision.edges.count { it.direction == DamageEdgeDirection.ATTACKER_TO_BLOCKER } shouldBe 2
        setup.decision.edges.none { it.direction == DamageEdgeDirection.ATTACKER_TO_PLAYER } shouldBe true

        setup.driver.submitCombatDamage(
            mapOf(
                (setup.attacker to setup.blockers[0]) to 2,
                (setup.attacker to setup.blockers[1]) to 2,
                (setup.attacker to setup.walker) to 1,
            )
        ).error shouldBe null
        setup.driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        setup.driver.findPermanent(setup.defenderPlayer, "Savannah Lions").shouldBeNull()
        setup.driver.findPermanent(setup.defenderPlayer, "Grizzly Bears").shouldBeNull()
        loyaltyOf(setup.driver, setup.walker) shouldBe 9
        setup.driver.assertLifeTotal(setup.defenderPlayer, 20)
    }

    test("C14-05 deathtouch trample needs only one damage per blocker before draining to the plane") {
        val setup = readyBlockedPlane(
            attackerName = "Deathtouch Trampler",
            blockerNames = listOf("Grizzly Bears", "Centaur Courser"),
            loyalty = 5,
        )

        setup.decision.edges.filter { it.sourceId == setup.attacker && !it.isTrampleDrain }.forEach {
            it.lethal shouldBe 1
        }
        setup.driver.submitCombatDamage(
            mapOf(
                (setup.attacker to setup.blockers[0]) to 1,
                (setup.attacker to setup.blockers[1]) to 1,
                (setup.attacker to setup.walker) to 1,
            )
        ).error shouldBe null
        setup.driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)

        setup.driver.findPermanent(setup.defenderPlayer, "Grizzly Bears").shouldBeNull()
        setup.driver.findPermanent(setup.defenderPlayer, "Centaur Courser").shouldBeNull()
        loyaltyOf(setup.driver, setup.walker) shouldBe 4
        setup.driver.assertLifeTotal(setup.defenderPlayer, 20)
    }

    test("C14-06 first-strike and double-strike damage each use the attacked plane") {
        val firstDriver = driver()
        firstDriver.initMirrorMatch(deck = Deck.of("Plains" to 40), skipMulligans = true)
        val firstPlayer = firstDriver.activePlayer!!
        val firstDefender = firstDriver.getOpponent(firstPlayer)
        firstDriver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val firstAttacker = firstDriver.putCreatureOnBattlefield(firstPlayer, "First Strike Knight")
        val firstWalker = firstDriver.putPermanentOnBattlefield(firstDefender, testWalker.name)
        seedLoyalty(firstDriver, firstWalker, loyalty = 5)
        firstDriver.removeSummoningSickness(firstAttacker)
        firstDriver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        firstDriver.declareAttackers(firstPlayer, mapOf(firstAttacker to firstWalker)).error shouldBe null
        firstDriver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        firstDriver.declareNoBlockers(firstDefender).error shouldBe null
        firstDriver.passPriorityUntil(Step.POSTCOMBAT_MAIN)
        loyaltyOf(firstDriver, firstWalker) shouldBe 2
        firstDriver.assertLifeTotal(firstDefender, 20)

        val doubleDriver = driver()
        doubleDriver.registerCard(doubleStrikeTrampler)
        doubleDriver.initMirrorMatch(deck = Deck.of("Mountain" to 40), skipMulligans = true)
        val doublePlayer = doubleDriver.activePlayer!!
        val doubleDefender = doubleDriver.getOpponent(doublePlayer)
        doubleDriver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val doubleAttacker = doubleDriver.putCreatureOnBattlefield(doublePlayer, doubleStrikeTrampler.name)
        val doubleWalker = doubleDriver.putPermanentOnBattlefield(doubleDefender, testWalker.name)
        seedLoyalty(doubleDriver, doubleWalker, loyalty = 9)
        doubleDriver.removeSummoningSickness(doubleAttacker)
        doubleDriver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        doubleDriver.declareAttackers(doublePlayer, mapOf(doubleAttacker to doubleWalker)).error shouldBe null
        doubleDriver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        doubleDriver.declareNoBlockers(doubleDefender).error shouldBe null
        doubleDriver.passPriorityUntil(Step.POSTCOMBAT_MAIN)
        loyaltyOf(doubleDriver, doubleWalker) shouldBe 5
        doubleDriver.assertLifeTotal(doubleDefender, 20)
    }

    test("C14-07 the damage domain names the attacked planeswalker, not its controller") {
        val setup = readyBlockedPlane(blockerNames = listOf("Savannah Lions"))
        val attackerNode = setup.decision.attackers.single()
        val defenderNode = setup.decision.defenders.single()

        attackerNode.attackedDefenderId shouldBe setup.walker
        defenderNode.id shouldBe setup.walker
        defenderNode.kind shouldBe com.wingedsheep.engine.core.ResolutionTargetKind.PLANESWALKER
        setup.decision.edges.none {
            it.sourceId == setup.attacker && it.direction == DamageEdgeDirection.ATTACKER_TO_PLAYER
        } shouldBe true
    }

    test("C14-08 ordinary trample cannot drain after the attacked planeswalker leaves before damage") {
        val driver = driver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        val attackerPlayer = driver.activePlayer!!
        val defenderPlayer = driver.getOpponent(attackerPlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, "Trample Beast")
        val blocker = driver.putCreatureOnBattlefield(defenderPlayer, "Grizzly Bears")
        val walker = driver.putPermanentOnBattlefield(defenderPlayer, testWalker.name)
        seedLoyalty(driver, walker, loyalty = 3)
        driver.removeSummoningSickness(attacker)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, mapOf(attacker to walker)).error shouldBe null
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(defenderPlayer, mapOf(blocker to listOf(attacker))).error shouldBe null

        // CR 506.4c: removing the planeswalker does not remove the attacker from combat. The
        // current rules instead leave no attacked object for ordinary trample to drain into.
        driver.moveToGraveyard(walker)
        driver.passPriorityUntil(Step.COMBAT_DAMAGE)

        val decision = driver.pendingDecision.shouldBeInstanceOf<CombatResolutionDecision>()
        decision.defenders.shouldBeEmpty()
        decision.edges.none { it.isTrampleDrain } shouldBe true

        driver.confirmCombatDamage().error shouldBe null
        driver.passPriorityUntil(Step.POSTCOMBAT_MAIN)
        driver.findPermanent(defenderPlayer, "Grizzly Bears").shouldBeNull()
        driver.assertLifeTotal(defenderPlayer, 20)
    }

    test("C14-P1-01 an unblocked attacker has no recipient after its planeswalker leaves") {
        val driver = driver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        val attackerPlayer = driver.activePlayer!!
        val defenderPlayer = driver.getOpponent(attackerPlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, "Trample Beast")
        val walker = driver.putPermanentOnBattlefield(defenderPlayer, testWalker.name)
        driver.removeSummoningSickness(attacker)
        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, mapOf(attacker to walker)).error shouldBe null
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareNoBlockers(defenderPlayer).error shouldBe null

        driver.moveToGraveyard(walker)
        val result = rerunCombatDamage(driver)

        result.error shouldBe null
        result.pendingDecision shouldBe null
        result.events.filterIsInstance<DamageDealtEvent>().none { it.targetId == walker } shouldBe true
        result.events.filterIsInstance<DamageDealtEvent>().none { it.targetId == defenderPlayer } shouldBe true
    }

    test("C14-P1-01b unblocked divide-freely damage has no decision after its planeswalker leaves") {
        val driver = driver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        val attackerPlayer = driver.activePlayer!!
        val defenderPlayer = driver.getOpponent(attackerPlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, divideFreelyCreature.name)
        val defenderCreatures = listOf(
            driver.putCreatureOnBattlefield(defenderPlayer, "Savannah Lions"),
            driver.putCreatureOnBattlefield(defenderPlayer, "Grizzly Bears"),
        )
        val walker = driver.putPermanentOnBattlefield(defenderPlayer, testWalker.name)
        driver.removeSummoningSickness(attacker)
        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, mapOf(attacker to walker)).error shouldBe null
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareNoBlockers(defenderPlayer).error shouldBe null

        driver.moveToGraveyard(walker)
        val result = rerunCombatDamage(driver)

        result.error shouldBe null
        result.pendingDecision shouldBe null
        result.events.filterIsInstance<DamageDealtEvent>().none { event ->
            event.sourceId == attacker || event.targetId in (defenderCreatures + defenderPlayer)
        } shouldBe true
    }

    test("C14-P1-02 a stale planeswalker is absent from prevention recipients") {
        val driver = driver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        val attackerPlayer = driver.activePlayer!!
        val defenderPlayer = driver.getOpponent(attackerPlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attackers = listOf(
            driver.putCreatureOnBattlefield(attackerPlayer, "Trample Beast"),
            driver.putCreatureOnBattlefield(attackerPlayer, "Trample Beast"),
        )
        val walker = driver.putPermanentOnBattlefield(defenderPlayer, testWalker.name)
        attackers.forEach(driver::removeSummoningSickness)
        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, attackers.associateWith { walker }).error shouldBe null
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareNoBlockers(defenderPlayer).error shouldBe null

        driver.moveToGraveyard(walker)
        driver.replaceState(
            driver.state.addFloatingEffect(
                layer = Layer.ABILITY,
                modification = SerializableModification.PreventNextDamage(1),
                affectedEntities = setOf(walker),
                duration = Duration.EndOfTurn,
                context = EffectContext(sourceId = null, controllerId = defenderPlayer),
            )
        )
        val result = rerunCombatDamage(driver)

        result.error shouldBe null
        result.pendingDecision shouldBe null
        result.events.filterIsInstance<DamageDealtEvent>().none { it.targetId == walker } shouldBe true
        result.events.filterIsInstance<DamageDealtEvent>().none { it.targetId == defenderPlayer } shouldBe true
    }

    test("C14-P1-03 a stale planeswalker manual assignment falls back to the live blocker") {
        val setup = readyBlockedPlane(blockerNames = listOf("Grizzly Bears"))
        setup.driver.moveToGraveyard(setup.walker)
        setup.driver.replaceState(setup.driver.state.updateEntity(setup.attacker) {
            it.with(DamageAssignmentComponent(mapOf(setup.walker to 5)))
        })

        val result = rerunCombatDamage(setup.driver)
        val blockerDamage = result.events.filterIsInstance<DamageDealtEvent>().single {
            it.targetId == setup.blockers.single()
        }

        result.error shouldBe null
        result.pendingDecision shouldBe null
        blockerDamage.amount shouldBe 5
        result.events.filterIsInstance<DamageDealtEvent>().none { it.targetId == setup.walker } shouldBe true
    }

    test("C14-P1-03b a response shaped before removal cannot apply a stale planeswalker edge") {
        val setup = readyBlockedPlane(blockerNames = listOf("Grizzly Bears"))
        val response = CombatResolutionResponse(
            decisionId = setup.decision.id,
            edges = setup.decision.edges.map { DamageEdgeAmount(it.id, it.amount) },
        )
        setup.driver.moveToGraveyard(setup.walker)

        val result = setup.driver.submitDecision(setup.decision.playerId, response)
        val blockerDamage = result.events.filterIsInstance<DamageDealtEvent>().single {
            it.targetId == setup.blockers.single()
        }

        result.error shouldBe null
        result.pendingDecision shouldBe null
        blockerDamage.amount shouldBe 5
        result.events.filterIsInstance<DamageDealtEvent>().none { it.targetId == setup.walker } shouldBe true
    }

    test("C14-P1-03c assign-as-unblocked does not pause for a stale planeswalker") {
        val driver = driver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        val attackerPlayer = driver.activePlayer!!
        val defenderPlayer = driver.getOpponent(attackerPlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, assignAsUnblockedCreature.name)
        val blocker = driver.putCreatureOnBattlefield(defenderPlayer, "Grizzly Bears")
        val walker = driver.putPermanentOnBattlefield(defenderPlayer, testWalker.name)
        driver.removeSummoningSickness(attacker)
        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, mapOf(attacker to walker)).error shouldBe null
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(defenderPlayer, mapOf(blocker to listOf(attacker))).error shouldBe null
        driver.passPriorityUntil(Step.COMBAT_DAMAGE)
        driver.pendingDecision.shouldNotBeNull()

        driver.moveToGraveyard(walker)
        val result = rerunCombatDamage(driver)
        val blockerDamage = result.events.filterIsInstance<DamageDealtEvent>().single {
            it.sourceId == attacker && it.targetId == blocker
        }

        result.error shouldBe null
        result.pendingDecision shouldBe null
        blockerDamage.amount shouldBe 5
        result.events.filterIsInstance<DamageDealtEvent>().none { it.targetId == walker } shouldBe true
    }

    test("C14-P1-04 a departed player is not a live unblocked damage recipient") {
        val driver = driver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40), skipMulligans = true)
        val attackerPlayer = driver.activePlayer!!
        val defenderPlayer = driver.getOpponent(attackerPlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, "Trample Beast")
        driver.removeSummoningSickness(attacker)
        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, mapOf(attacker to defenderPlayer)).error shouldBe null
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareNoBlockers(defenderPlayer).error shouldBe null
        driver.replaceState(driver.state.updateEntity(defenderPlayer) {
            it.with(PlayerLostComponent(LossReason.CONCESSION)).with(PlayerLeftGameComponent)
        })

        val result = rerunCombatDamage(driver)

        result.error shouldBe null
        result.pendingDecision shouldBe null
        result.events.filterIsInstance<DamageDealtEvent>().none { it.targetId == defenderPlayer } shouldBe true
    }

    test("C14-P1-05 a planeswalker controller change removes its damage recipient") {
        val setup = readyBlockedPlane(blockerNames = listOf("Grizzly Bears"))
        setup.driver.replaceState(setup.driver.state.updateEntity(setup.walker) {
            it.with(ControllerComponent(setup.attackerPlayer))
        })

        val result = rerunCombatDamage(setup.driver)
        val decision = result.pendingDecision as? CombatResolutionDecision

        result.error shouldBe null
        decision?.defenders.orEmpty().shouldBeEmpty()
        decision?.edges?.none { it.targetId == setup.walker } shouldBe true
    }

    test("C14-P1-06 a stale battle manual assignment falls back to the live blocker") {
        val setup = readyBlockedBattle()
        setup.driver.moveToGraveyard(setup.battle)
        setup.driver.replaceState(setup.driver.state.updateEntity(setup.attacker) {
            it.with(DamageAssignmentComponent(mapOf(setup.battle to 5)))
        })

        val result = rerunCombatDamage(setup.driver)
        val blockerDamage = result.events.filterIsInstance<DamageDealtEvent>().single {
            it.targetId == setup.blocker
        }

        result.error shouldBe null
        result.pendingDecision shouldBe null
        blockerDamage.amount shouldBe 5
        result.events.filterIsInstance<DamageDealtEvent>().none { it.targetId == setup.battle } shouldBe true
    }

    test("C14-P1-07 a battle controller change removes its damage recipient") {
        val setup = readyBlockedBattle()
        setup.driver.replaceState(setup.driver.state.updateEntity(setup.battle) {
            it.with(ControllerComponent(setup.attackerPlayer))
        })

        val result = rerunCombatDamage(setup.driver)
        val decision = result.pendingDecision as? CombatResolutionDecision

        result.error shouldBe null
        decision?.defenders.orEmpty().shouldBeEmpty()
        decision?.edges?.none { it.targetId == setup.battle } shouldBe true
    }

    test("C14-P1-08 a battle protector change removes its damage recipient") {
        val setup = readyBlockedBattle()
        setup.driver.replaceState(setup.driver.state.updateEntity(setup.battle) {
            it.with(ProtectorComponent(setup.attackerPlayer))
        })

        val result = rerunCombatDamage(setup.driver)
        val decision = result.pendingDecision as? CombatResolutionDecision

        result.error shouldBe null
        decision?.defenders.orEmpty().shouldBeEmpty()
        decision?.edges?.none { it.targetId == setup.battle } shouldBe true
    }

    test("C14-P1-09 object-target attacks without declaration relationship metadata fail closed") {
        val setup = readyBlockedPlane(blockerNames = listOf("Grizzly Bears"))
        setup.driver.replaceState(
            setup.driver.state
                .updateEntity(setup.attacker) { it.with(AttackingComponent(setup.walker)) }
                .updateEntity(setup.walker) { it.with(ControllerComponent(setup.attackerPlayer)) },
        )

        val result = rerunCombatDamage(setup.driver)
        val decision = result.pendingDecision as? CombatResolutionDecision

        result.error shouldBe null
        decision?.defenders.orEmpty().shouldBeEmpty()
        decision?.edges?.none { it.targetId == setup.walker } shouldBe true
    }

    test("C14-09 external plans must be complete and cannot invent a player recipient") {
        val setup = readyBlockedPlane(blockerNames = listOf("Savannah Lions"))
        val decision = setup.decision
        val complete = CombatResolutionResponse(
            decisionId = decision.id,
            edges = decision.edges.map { DamageEdgeAmount(it.id, it.amount) },
        )

        DecisionValidators.validate(decision, complete) shouldBe null
        DecisionValidators.validate(
            decision,
            complete.copy(
                edges = complete.edges + DamageEdgeAmount("${setup.attacker}->${setup.defenderPlayer}", 1),
            ),
        ).shouldNotBeNull()
        DecisionValidators.validate(
            decision,
            CombatResolutionResponse(
                decisionId = decision.id,
                edges = decision.edges.map { edge ->
                    DamageEdgeAmount(edge.id, if (edge.isTrampleDrain) edge.amount + 1 else edge.amount)
                },
            ),
        ).shouldNotBeNull()
    }

    test("C14-10 serialization and behavioral replay/fork are deterministic; trample-over-planeswalkers is NOT_APPLICABLE") {
        val setup = readyBlockedPlane(blockerNames = listOf("Savannah Lions"))
        val decision = setup.decision
        val json = Json {
            encodeDefaults = true
            allowStructuredMapKeys = true
            serializersModule = engineSerializersModule
        }
        val encoded = json.encodeToString(CombatResolutionDecision.serializer(), decision)
        val decoded = json.decodeFromString(CombatResolutionDecision.serializer(), encoded)
        decoded shouldBe decision

        val stateEncoded = json.encodeToString(GameState.serializer(), setup.driver.state)
        val stateDecoded = json.decodeFromString(GameState.serializer(), stateEncoded)
        stateDecoded.getEntity(setup.attacker)?.get<AttackingComponent>() shouldBe
            setup.driver.state.getEntity(setup.attacker)?.get<AttackingComponent>()

        val ordered = CombatResolutionResponse(
            decisionId = decision.id,
            edges = decision.edges.map { DamageEdgeAmount(it.id, it.amount) },
        )
        val reversed = ordered.copy(edges = ordered.edges.reversed())
        DecisionValidators.validate(decision, ordered) shouldBe null
        DecisionValidators.validate(decision, reversed) shouldBe null

        val forkDriver = driver()
        forkDriver.replaceState(setup.driver.state.copy(pendingDecision = decoded))
        val originalResult = setup.driver.submitDecision(decision.playerId, ordered)
        val forkResult = forkDriver.submitDecision(decoded.playerId, ordered)

        originalResult.error shouldBe null
        forkResult.error shouldBe null
        originalResult.state shouldBe forkResult.state
        originalResult.events.filterIsInstance<DamageDealtEvent>() shouldBe
            forkResult.events.filterIsInstance<DamageDealtEvent>()

        // NOT_APPLICABLE: CR 702.19c-f defines the separate named "trample over planeswalkers"
        // variant. This SDK exposes ordinary trample only; this task characterizes CR 702.19b,
        // so ordinary trample has no controller/player drain edge when attacking a planeswalker.
        decision.edges.none { it.direction == DamageEdgeDirection.ATTACKER_TO_PLAYER } shouldBe true
    }
})
