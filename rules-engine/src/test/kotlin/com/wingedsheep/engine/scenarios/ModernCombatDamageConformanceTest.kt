package com.wingedsheep.engine.scenarios

import com.wingedsheep.engine.core.CombatResolutionDecision
import com.wingedsheep.engine.core.CombatResolutionResponse
import com.wingedsheep.engine.core.DamageEdge
import com.wingedsheep.engine.core.DamageEdgeAmount
import com.wingedsheep.engine.core.DamageEdgeDirection
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.ResolutionAttacker
import com.wingedsheep.engine.core.ResolutionBlocker
import com.wingedsheep.engine.core.ResolutionDefender
import com.wingedsheep.engine.core.ResolutionTargetKind
import com.wingedsheep.engine.core.OrderBlockers
import com.wingedsheep.engine.handlers.actions.decision.DecisionValidators
import com.wingedsheep.engine.handlers.actions.combat.OrderBlockersHandler
import com.wingedsheep.engine.mechanics.combat.CombatDamageUtils
import com.wingedsheep.engine.support.GameTestDriver
import com.wingedsheep.engine.support.TestCards
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * RED-first A2.2 matrix. These tests pin the modern assignment contract at the
 * structured-decision boundary and the live combat-step boundary.
 */
class ModernCombatDamageConformanceTest : FunSpec({

    val attackerId = EntityId.of("attacker")
    val controllerId = EntityId.of("attacking-player")

    fun decisionForAttacker(
        power: Int,
        blockerToughness: List<Int>,
        hasTrample: Boolean = false,
        hasDeathtouch: Boolean = false,
        blockerMarkedDamage: List<Int> = blockerToughness.map { 0 },
        defenderKind: ResolutionTargetKind = ResolutionTargetKind.PLAYER,
        defenderValue: Int? = 20,
    ): CombatResolutionDecision {
        val blockerIds = blockerToughness.indices.map { EntityId.of("blocker-$it") }
        val defenderId = EntityId.of("defender")
        val blockers = blockerIds.zip(blockerToughness).map { (id, toughness) ->
            ResolutionBlocker(
                id = id,
                name = id.value,
                power = 2,
                toughness = toughness,
                hasDeathtouch = false,
                hasFirstStrike = false,
                hasDoubleStrike = false,
                dealsDamageThisStep = true,
                blockedAttackerIds = listOf(attackerId),
                orderedAttackers = emptyList(),
                markedDamage = blockerMarkedDamage[blockerIds.indexOf(id)],
            )
        }
        val edges = blockerIds.zip(blockerToughness).map { (blockerId, toughness) ->
            DamageEdge(
                id = "$attackerId->$blockerId",
                sourceId = attackerId,
                targetId = blockerId,
                direction = DamageEdgeDirection.ATTACKER_TO_BLOCKER,
                amount = power / blockerIds.size,
                maximum = power,
                lethal = toughness,
                orderConstrained = false,
                isTrampleDrain = false,
                editableBy = controllerId,
            )
        }.toMutableList()
        if (hasTrample) {
            edges += DamageEdge(
                id = "$attackerId->defender",
                sourceId = attackerId,
                targetId = defenderId,
                direction = when (defenderKind) {
                    ResolutionTargetKind.PLAYER -> DamageEdgeDirection.ATTACKER_TO_PLAYER
                    ResolutionTargetKind.PLANESWALKER -> DamageEdgeDirection.ATTACKER_TO_PLANESWALKER
                    ResolutionTargetKind.BATTLE -> DamageEdgeDirection.ATTACKER_TO_BATTLE
                },
                amount = 0,
                maximum = power,
                lethal = 0,
                orderConstrained = false,
                isTrampleDrain = true,
                editableBy = controllerId,
            )
        }
        return CombatResolutionDecision(
            id = "decision",
            playerId = controllerId,
            prompt = "Assign combat damage",
            context = DecisionContext(
                sourceId = attackerId,
                sourceName = "Synthetic attacker",
                phase = DecisionPhase.COMBAT,
            ),
            firstStrike = false,
            attackers = listOf(
                ResolutionAttacker(
                    id = attackerId,
                    name = "Synthetic attacker",
                    power = power,
                    toughness = 5,
                    hasTrample = hasTrample,
                    hasDeathtouch = hasDeathtouch,
                    hasFirstStrike = false,
                    hasDoubleStrike = false,
                    dealsDamageThisStep = true,
                    bandId = null,
                    attackedDefenderId = defenderId,
                    blockedByIds = blockerIds,
                    markedDamage = 0,
                )
            ),
            blockers = blockers,
            defenders = listOf(
                ResolutionDefender(
                    id = defenderId,
                    kind = defenderKind,
                    name = defenderKind.name,
                    lifeOrLoyaltyOrDefense = defenderValue,
                )
            ),
            edges = edges,
        )
    }

    fun response(
        decision: CombatResolutionDecision,
        amounts: Map<String, Int>,
    ): CombatResolutionResponse =
        CombatResolutionResponse(
            decisionId = decision.id,
            edges = decision.edges.map { edge ->
                DamageEdgeAmount(edge.id, amounts[edge.id] ?: 0)
            },
        )

    fun edge(decision: CombatResolutionDecision, index: Int): String =
        decision.edges[index].id

    test("COMBAT-01 ordinary combat accepts every arbitrary complete split") {
        val decision = decisionForAttacker(power = 5, blockerToughness = listOf(3, 4))
        for (firstBlockerAmount in 0..5) {
            val submitted = response(
                decision,
                mapOf(
                    edge(decision, 0) to firstBlockerAmount,
                    edge(decision, 1) to 5 - firstBlockerAmount,
                ),
            )
            DecisionValidators.validate(decision, submitted) shouldBe null
        }
    }

    test("COMBAT-02 ordinary combat rejects invalid totals, negatives, and unknown edges") {
        val decision = decisionForAttacker(power = 5, blockerToughness = listOf(3, 4))
        val submitted = response(
            decision,
            mapOf(edge(decision, 0) to 2, edge(decision, 1) to 2),
        )

        DecisionValidators.validate(decision, submitted) shouldBe
            "Source attacker: combat assignment must total exactly 5, got 4"

        DecisionValidators.validate(
            decision,
            response(decision, mapOf(edge(decision, 0) to 5, edge(decision, 1) to 1)),
        ) shouldBe "Source attacker: combat assignment must total exactly 5, got 6"

        DecisionValidators.validate(
            decision,
            response(decision, mapOf(edge(decision, 0) to -1, edge(decision, 1) to 6)),
        ) shouldBe "Edge ${edge(decision, 0)}: amount -1 below 0"

        DecisionValidators.validate(
            decision,
            CombatResolutionResponse(
                decisionId = decision.id,
                edges = listOf(DamageEdgeAmount("unrelated-target", 5)),
            ),
        ) shouldBe "Unknown edge id: unrelated-target"
    }

    test("COMBAT-03 ordinary combat has no generic lethal-first gate") {
        val decision = decisionForAttacker(power = 5, blockerToughness = listOf(3, 4))
        DecisionValidators.validate(
            decision,
            response(decision, mapOf(edge(decision, 0) to 0, edge(decision, 1) to 5)),
        ) shouldBe null
    }

    fun decisionForBlocker(power: Int, attackerCount: Int): CombatResolutionDecision {
        val blockerId = EntityId.of("shared-blocker")
        val attackerIds = (0 until attackerCount).map { EntityId.of("blocked-attacker-$it") }
        val defenderId = EntityId.of("blocker-defender")
        val attackers = attackerIds.map { id ->
            ResolutionAttacker(
                id = id,
                name = id.value,
                power = 2,
                toughness = 2,
                hasTrample = false,
                hasDeathtouch = false,
                hasFirstStrike = false,
                hasDoubleStrike = false,
                dealsDamageThisStep = true,
                bandId = null,
                attackedDefenderId = defenderId,
                blockedByIds = listOf(blockerId),
                markedDamage = 0,
            )
        }
        val blocker = ResolutionBlocker(
            id = blockerId,
            name = "Shared blocker",
            power = power,
            toughness = 5,
            hasDeathtouch = false,
            hasFirstStrike = false,
            hasDoubleStrike = false,
            dealsDamageThisStep = true,
            blockedAttackerIds = attackerIds,
            orderedAttackers = emptyList(),
            markedDamage = 0,
        )
        return CombatResolutionDecision(
            id = "blocker-decision",
            playerId = EntityId.of("blocking-player"),
            prompt = "Assign blocker damage",
            context = DecisionContext(
                sourceId = blockerId,
                sourceName = blocker.name,
                phase = DecisionPhase.COMBAT,
            ),
            firstStrike = false,
            attackers = attackers,
            blockers = listOf(blocker),
            defenders = emptyList(),
            edges = attackerIds.map { attackerId ->
                DamageEdge(
                    id = "$blockerId->$attackerId",
                    sourceId = blockerId,
                    targetId = attackerId,
                    direction = DamageEdgeDirection.BLOCKER_TO_ATTACKER,
                    amount = power / attackerCount,
                    maximum = power,
                    lethal = 0,
                    orderConstrained = false,
                    isTrampleDrain = false,
                    editableBy = EntityId.of("blocking-player"),
                )
            },
        )
    }

    test("COMBAT-05 a blocker dividing damage among multiple attackers accepts arbitrary splits") {
        val decision = decisionForBlocker(power = 5, attackerCount = 2)
        for (firstAttackerAmount in 0..5) {
            DecisionValidators.validate(
                decision,
                response(
                    decision,
                    mapOf(
                        decision.edges[0].id to firstAttackerAmount,
                        decision.edges[1].id to 5 - firstAttackerAmount,
                    ),
                ),
            ) shouldBe null
        }
    }

    fun createDriver(): GameTestDriver {
        val driver = GameTestDriver()
        driver.registerCards(TestCards.all)
        return driver
    }

    test("COMBAT-04 modern board emits without a damage-order decision") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40))
        val attackerPlayer = driver.activePlayer!!
        val defenderPlayer = driver.getOpponent(attackerPlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, "Force of Nature")
        val blockerA = driver.putCreatureOnBattlefield(defenderPlayer, "Grizzly Bears")
        val blockerB = driver.putCreatureOnBattlefield(defenderPlayer, "Goblin Guide")
        driver.removeSummoningSickness(attacker)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, listOf(attacker), defenderPlayer).error shouldBe null
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(
            defenderPlayer,
            mapOf(blockerA to listOf(attacker), blockerB to listOf(attacker)),
        ).error shouldBe null
        driver.state.pendingDecision.shouldBeNull()

        driver.passPriorityUntil(Step.COMBAT_DAMAGE)
        val decision = driver.pendingDecision.shouldBeInstanceOf<CombatResolutionDecision>()
        decision.edges.filter { it.sourceId == attacker }.sumOf { it.amount } shouldBe 5
        decision.edges.filter {
            it.sourceId == attacker && it.direction == DamageEdgeDirection.ATTACKER_TO_BLOCKER
        }.map { it.targetId }.toSet() shouldBe setOf(blockerA, blockerB)
    }

    test("reversed blocker relation order does not create order semantics") {
        val driver = createDriver()
        driver.initMirrorMatch(deck = Deck.of("Forest" to 40))
        val attackerPlayer = driver.activePlayer!!
        val defenderPlayer = driver.getOpponent(attackerPlayer)

        driver.passPriorityUntil(Step.PRECOMBAT_MAIN)
        val attacker = driver.putCreatureOnBattlefield(attackerPlayer, "Force of Nature")
        val blockerA = driver.putCreatureOnBattlefield(defenderPlayer, "Grizzly Bears")
        val blockerB = driver.putCreatureOnBattlefield(defenderPlayer, "Goblin Guide")
        driver.removeSummoningSickness(attacker)

        driver.passPriorityUntil(Step.DECLARE_ATTACKERS)
        driver.declareAttackers(attackerPlayer, listOf(attacker), defenderPlayer)
        driver.passPriorityUntil(Step.DECLARE_BLOCKERS)
        driver.declareBlockers(
            defenderPlayer,
            mapOf(blockerB to listOf(attacker), blockerA to listOf(attacker)),
        ).error shouldBe null
        driver.state.pendingDecision.shouldBeNull()

        driver.passPriorityUntil(Step.COMBAT_DAMAGE)
        val decision = driver.pendingDecision.shouldBeInstanceOf<CombatResolutionDecision>()
        val attackerEdges = decision.edges.filter { it.sourceId == attacker }
        attackerEdges.sumOf { it.amount } shouldBe 5
        attackerEdges.filter { it.direction == DamageEdgeDirection.ATTACKER_TO_BLOCKER }
            .map { it.targetId }.toSet() shouldBe setOf(blockerA, blockerB)
    }

    test("COMBAT-06 basic trample requires lethal before drain") {
        val decision = decisionForAttacker(power = 5, blockerToughness = listOf(3), hasTrample = true)
        for ((blockerAmount, drainAmount) in listOf(3 to 2, 4 to 1, 5 to 0)) {
            DecisionValidators.validate(
                decision,
                response(
                    decision,
                    mapOf(
                        decision.edges[0].id to blockerAmount,
                        decision.edges[1].id to drainAmount,
                    ),
                ),
            ) shouldBe null
        }
        DecisionValidators.validate(
            decision,
            response(decision, mapOf(decision.edges[0].id to 2, decision.edges[1].id to 3)),
        ) shouldBe "Trample drain attacker->defender: preceding blocker not at lethal"
    }

    test("COMBAT-07 trample with multiple blockers validates aggregate lethal") {
        val decision = decisionForAttacker(power = 7, blockerToughness = listOf(2, 3), hasTrample = true)
        for ((first, second, drain) in listOf(Triple(2, 3, 2), Triple(3, 3, 1))) {
            DecisionValidators.validate(
                decision,
                response(
                    decision,
                    mapOf(
                        decision.edges[0].id to first,
                        decision.edges[1].id to second,
                        decision.edges[2].id to drain,
                    ),
                ),
            ) shouldBe null
        }
        for ((first, second, drain) in listOf(Triple(0, 5, 2), Triple(2, 2, 3))) {
            DecisionValidators.validate(
                decision,
                response(
                    decision,
                    mapOf(
                        decision.edges[0].id to first,
                        decision.edges[1].id to second,
                        decision.edges[2].id to drain,
                    ),
                ),
            ) shouldBe "Trample drain attacker->defender: preceding blocker not at lethal"
        }
    }

    test("COMBAT-08 marked damage reduces the trample lethal requirement") {
        val decision = decisionForAttacker(
            power = 3,
            blockerToughness = listOf(5),
            hasTrample = true,
            blockerMarkedDamage = listOf(4),
        )
        val submitted = response(
            decision,
            mapOf(edge(decision, 0) to 1, edge(decision, 1) to 2),
        )

        DecisionValidators.validate(decision, submitted) shouldBe null
    }

    test("COMBAT-09 shared-blocker trample uses order-independent same-step aggregate") {
        val decision = decisionForAttacker(power = 2, blockerToughness = listOf(5), hasTrample = true)
        val secondAttacker = EntityId.of("second-attacker")
        val blocker = decision.blockers.single().id
        val expanded = decision.copy(
            attackers = decision.attackers + decision.attackers.single().copy(
                id = secondAttacker,
                name = "Second attacker",
                power = 4,
                hasTrample = false,
                hasDeathtouch = false,
                blockedByIds = listOf(blocker),
            ),
            blockers = decision.blockers.map { it.copy(blockedAttackerIds = listOf(attackerId, secondAttacker)) },
            edges = decision.edges + DamageEdge(
                id = "$secondAttacker->$blocker",
                sourceId = secondAttacker,
                targetId = blocker,
                direction = DamageEdgeDirection.ATTACKER_TO_BLOCKER,
                amount = 4,
                maximum = 4,
                lethal = 5,
                orderConstrained = false,
                isTrampleDrain = false,
                editableBy = controllerId,
            ),
        )
        val amounts = mapOf(
            edge(expanded, 0) to 1,
            edge(expanded, 1) to 1,
            edge(expanded, 2) to 4,
        )

        DecisionValidators.validate(expanded, response(expanded, amounts)) shouldBe null

        val reordered = expanded.copy(edges = expanded.edges.reversed())
        DecisionValidators.validate(reordered, response(reordered, amounts)) shouldBe null
    }

    test("COMBAT-10 deathtouch trample needs only one damage per blocker") {
        val decision = decisionForAttacker(
            power = 3,
            blockerToughness = listOf(5, 5),
            hasTrample = true,
            hasDeathtouch = true,
        )
        val submitted = response(
            decision,
            mapOf(
                edge(decision, 0) to 1,
                edge(decision, 1) to 1,
                edge(decision, 2) to 1,
            ),
        )

        DecisionValidators.validate(decision, submitted) shouldBe null
    }

    test("COMBAT-11 deathtouch without trample remains freely splittable") {
        val decision = decisionForAttacker(
            power = 3,
            blockerToughness = listOf(5, 5),
            hasDeathtouch = true,
        )
        DecisionValidators.validate(
            decision,
            response(decision, mapOf(decision.edges[0].id to 0, decision.edges[1].id to 3)),
        ) shouldBe null
    }

    test("trample drain accepts lethal assignment to every blocker") {
        val decision = decisionForAttacker(power = 5, blockerToughness = listOf(2, 2), hasTrample = true)
        val submitted = response(
            decision,
            mapOf(
                edge(decision, 0) to 2,
                edge(decision, 1) to 2,
                edge(decision, 2) to 1,
            ),
        )

        DecisionValidators.validate(decision, submitted) shouldBe null
    }

    test("COMBAT-13 ordinary trample drains to the attacked planeswalker only") {
        val decision = decisionForAttacker(
            power = 3,
            blockerToughness = listOf(2),
            hasTrample = true,
            defenderKind = ResolutionTargetKind.PLANESWALKER,
            defenderValue = 3,
        )
        val drain = decision.edges.single { it.isTrampleDrain }
        drain.direction shouldBe DamageEdgeDirection.ATTACKER_TO_PLANESWALKER
        decision.edges.none { it.direction == DamageEdgeDirection.ATTACKER_TO_PLAYER } shouldBe true
        DecisionValidators.validate(
            decision,
            response(decision, mapOf(decision.edges.first().id to 2, drain.id to 1)),
        ) shouldBe null
    }

    test("COMBAT-15 trample drains to an attacked battle, not a player") {
        val decision = decisionForAttacker(
            power = 3,
            blockerToughness = listOf(2),
            hasTrample = true,
            defenderKind = ResolutionTargetKind.BATTLE,
            defenderValue = 3,
        )
        val drain = decision.edges.single { it.isTrampleDrain }
        drain.direction shouldBe DamageEdgeDirection.ATTACKER_TO_BATTLE
        decision.edges.none { it.direction == DamageEdgeDirection.ATTACKER_TO_PLAYER } shouldBe true
        DecisionValidators.validate(
            decision,
            response(decision, mapOf(decision.edges.first().id to 2, drain.id to 1)),
        ) shouldBe null
    }

    test("COMBAT-14 trample-over-planeswalkers is an explicit unsupported variant") {
        // The SDK exposes ordinary TRAMPLE only. The distinct keyword/edge needed to
        // spill past an attacked planeswalker to its controller is intentionally not
        // invented in this combat milestone.
        ResolutionTargetKind.entries shouldBe listOf(
            ResolutionTargetKind.PLAYER,
            ResolutionTargetKind.PLANESWALKER,
            ResolutionTargetKind.BATTLE,
        )
    }

    test("COMBAT-23 combat assignment decision and continuation round-trip") {
        val decision = decisionForAttacker(power = 5, blockerToughness = listOf(3, 4)).copy(
            id = "serialized-decision",
            playerId = controllerId,
            coChooserId = EntityId.of("second-chooser"),
            edges = decisionForAttacker(power = 5, blockerToughness = listOf(3, 4)).edges.map { edge ->
                edge.copy(amount = if (edge.targetId.value == "blocker-0") 2 else edge.amount)
            },
        )
        val continuation = com.wingedsheep.engine.core.CombatResolutionContinuation(
            decisionId = decision.id,
            firstStrike = false,
            pendingChoosers = listOf(controllerId, EntityId.of("second-chooser")),
            decisionShape = decision,
        )
        val json = Json {
            serializersModule = com.wingedsheep.engine.core.engineSerializersModule
            encodeDefaults = true
        }
        val encoded = json.encodeToString(
            com.wingedsheep.engine.core.ContinuationFrame.serializer(),
            continuation,
        )
        encoded.contains("orderConstrained") shouldBe false
        encoded.contains("orderedAttackers") shouldBe false
        val decoded = json.decodeFromString<com.wingedsheep.engine.core.ContinuationFrame>(encoded)
        decoded shouldBe continuation
        val forked = GameState().copy(
            pendingDecision = decision,
            continuationStack = listOf(decoded),
        )
        forked.peekContinuation() shouldBe continuation
        forked.pendingDecision shouldBe decision
    }

    test("COMBAT-24 equivalent edge-map insertion order has the same semantic result") {
        val decision = decisionForAttacker(power = 5, blockerToughness = listOf(3, 4))
        val assignments = linkedMapOf(
            decision.edges[0].id to 0,
            decision.edges[1].id to 5,
        )
        val reversed = linkedMapOf(
            decision.edges[1].id to 5,
            decision.edges[0].id to 0,
        )
        DecisionValidators.validate(decision, response(decision, assignments)) shouldBe null
        DecisionValidators.validate(decision, response(decision, reversed)) shouldBe null
    }

    test("legacy order action is rejected and legacy response maps are ignored") {
        val action = OrderBlockers(
            playerId = controllerId,
            attackerId = attackerId,
            orderedBlockers = listOf(EntityId.of("blocker-0")),
        )
        val handler = OrderBlockersHandler()
        val expectedError = "Damage-assignment order is obsolete; submit combat damage assignments"
        handler.validate(GameState(), action) shouldBe expectedError
        handler.execute(GameState(), action).error shouldBe expectedError

        val decision = decisionForAttacker(power = 5, blockerToughness = listOf(3, 4))
        val withLegacyMaps = response(
            decision,
            mapOf(edge(decision, 0) to 2, edge(decision, 1) to 3),
        ).copy(
            orderedBlockers = mapOf(attackerId to decision.blockers.map { it.id }),
            orderedAttackers = decision.blockers.associate { it.id to listOf(attackerId) },
        )
        DecisionValidators.validate(decision, withLegacyMaps) shouldBe null
    }

    test("COMBAT-21 chooser authorities are sequenced in multiplayer APNAP order") {
        val player1 = EntityId.of("player-1")
        val player2 = EntityId.of("player-2")
        val player3 = EntityId.of("player-3")
        val state = GameState(
            activePlayerId = player2,
            turnOrder = listOf(player1, player2, player3),
        )

        CombatDamageUtils.apnapChooserOrder(
            state = state,
            activePlayerId = player2,
            chooserIds = listOf(player3, player1, player2, player3),
        ) shouldBe listOf(player2, player3, player1)
    }
})
