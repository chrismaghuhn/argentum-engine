package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DeclareBlockers
import com.wingedsheep.engine.core.PassPriority
import com.wingedsheep.engine.core.PlayLand
import com.wingedsheep.engine.core.SubmitDecision
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.gym.service.DeckSpec
import com.wingedsheep.gym.service.EnvConfig
import com.wingedsheep.gym.service.MultiEnvService
import com.wingedsheep.gym.service.PlayerSpec
import com.wingedsheep.gym.service.SnapshotCodec
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Step
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/** Focused A3 contract tests. The commander card is only a registry fixture. */
class CommanderGymContractTest : FunSpec({

    fun registry() = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    fun player(name: String) = PlayerSpec(
        name = name,
        deck = DeckSpec.Explicit(mapOf("Mountain" to 99)),
        commanderCardName = "Raging Goblin"
    )

    fun commanderEnvConfig(maxSteps: Int? = null) = EnvConfig(
        players = listOf(player("Alice"), player("Bob")),
        format = Format.Commander(),
        seed = 7L,
        maxSteps = maxSteps,
        skipMulligans = true,
        startingPlayerIndex = 0,
        perspectivePlayerIndex = 0
    )

    test("Commander Gym configuration is exactly two-player") {
        shouldThrow<IllegalArgumentException> {
            EnvConfig(
                players = listOf(player("A"), player("B"), player("C")),
                format = Format.Commander()
            )
        }
        shouldThrow<IllegalArgumentException> {
            EnvConfig(
                players = listOf(player("A"), player("B").copy(commanderCardName = " ")),
                format = Format.Commander()
            )
        }
    }

    test("service maps Commander format, commander identity, and deterministic seed") {
        val created = MultiEnvService(registry()).create(commanderEnvConfig())
        val observation = created.observation.observation as TrainingObservation
        val sameSeed = MultiEnvService(registry()).create(commanderEnvConfig())

        observation.players shouldHaveSize 2
        observation.players.forEach { it.lifeTotal shouldBe 40 }
        val commandZone = observation.zones.first {
            it.ownerId == observation.perspectivePlayerId && it.zoneType == Zone.COMMAND
        }
        commandZone.cards.single().name shouldBe "Raging Goblin"
        observation.terminated.shouldBeFalse()
        observation.truncated.shouldBeFalse()
        observation.stateDigest shouldBe sameSeed.observation.observation.stateDigest
    }

    test("Commander cast, zone choice, tax recast, and combat damage stay external through Gym") {
        val environment = GameEnvironment.create(registry())
        environment.reset(
                GameConfig(
                    format = Format.Commander(commanderDamageThreshold = 1),
                    startingHandSize = 3,
                    players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 99), commanderCardName = "Raging Goblin"),
                    PlayerConfig("Bob", Deck.of("Mountain" to 99), commanderCardName = "Raging Goblin")
                ),
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 7L
            )
        )

        val alice = environment.playerIds[0]
        val bob = environment.playerIds[1]
        val aliceCommander = environment.state.getZone(alice, Zone.COMMAND).single()
        val bobCommander = environment.state.getZone(bob, Zone.COMMAND).single()

        fun passPriority() {
            val legal = environment.legalActions()
            val action = legal.firstOrNull { it.action is PassPriority }?.action
                ?: legal.firstOrNull {
                    val combat = it.action as? DeclareAttackers
                    combat?.attackers?.isEmpty() == true
                }?.action
                ?: legal.firstOrNull {
                    val combat = it.action as? DeclareBlockers
                    combat?.blockers?.isEmpty() == true
                }?.action
                ?: error("No pass action at ${environment.state.phase}/${environment.state.step}")
            environment.step(action)
        }

        fun advanceToMain(playerId: com.wingedsheep.sdk.model.EntityId, afterTurn: Int) {
            repeat(160) {
                if (environment.pendingDecision != null) {
                    error(
                        "Unexpected pending decision while advancing to main phase: " +
                            "${environment.pendingDecision} at turn ${environment.turnNumber} " +
                            "${environment.state.phase}/${environment.state.step}"
                    )
                }
                if (environment.state.activePlayerId == playerId &&
                    environment.state.priorityPlayerId == playerId &&
                    environment.state.step == Step.PRECOMBAT_MAIN &&
                    environment.turnNumber > afterTurn
                ) return
                passPriority()
            }
            error("Could not reach ${playerId.value}'s precombat main phase")
        }

        fun advanceToStep(playerId: com.wingedsheep.sdk.model.EntityId, step: Step) {
            repeat(80) {
                if (environment.pendingDecision != null) {
                    error("Unexpected pending decision while advancing to $step")
                }
                if (environment.state.priorityPlayerId == playerId && environment.state.step == step) return
                passPriority()
            }
            error("Could not reach $step for ${playerId.value}")
        }

        fun castCommander(commanderId: com.wingedsheep.sdk.model.EntityId) {
            val legal = environment.legalActions().firstOrNull {
                val action = it.action as? CastSpell
                action?.cardId == commanderId && it.affordable
            } ?: error("Commander $commanderId was not an affordable Gym action")
            environment.step(legal.action)
        }

        advanceToMain(alice, afterTurn = 0)
        val firstLand = environment.legalActions().firstOrNull { it.action is PlayLand }
            ?: error("Alice had no land action on the opening main phase")
        environment.step(firstLand.action)
        castCommander(aliceCommander)
        environment.state.getZone(alice, Zone.BATTLEFIELD) shouldContain aliceCommander
        environment.state.getEntity(aliceCommander)!!.get<CommanderComponent>()!!
            .castsFromCommandZone shouldBe 1

        advanceToMain(bob, afterTurn = environment.turnNumber)
        val bobLand = environment.legalActions().firstOrNull { it.action is PlayLand }
            ?: error("Bob had no land action on his first main phase")
        environment.step(bobLand.action)
        castCommander(bobCommander)

        val firstAliceTurn = environment.turnNumber
        advanceToMain(alice, afterTurn = firstAliceTurn)
        advanceToStep(alice, Step.DECLARE_ATTACKERS)
        val attackTemplate = environment.legalActions().firstOrNull {
            it.action is DeclareAttackers
        }?.action as? DeclareAttackers ?: error("Alice had no attacker declaration template")
        val attack = attackTemplate.copy(attackers = mapOf(aliceCommander to bob))
        environment.step(attack)

        advanceToStep(bob, Step.DECLARE_BLOCKERS)
        val blockTemplate = environment.legalActions().firstOrNull {
            it.action is DeclareBlockers
        }?.action as? DeclareBlockers ?: error("Bob had no blocker declaration template")
        val block = blockTemplate.copy(blockers = mapOf(bobCommander to listOf(aliceCommander)))
        environment.step(block)

        // Both commanders die in combat. The Gym must stop on the owner-bound 903.9a choice
        // instead of selecting a zone or consuming a horizon step behind the controller's back.
        var combatResolutionAttempts = 0
        while (environment.pendingDecision == null && !environment.isTerminal && combatResolutionAttempts < 80) {
            passPriority()
            combatResolutionAttempts++
        }
        val firstZoneDecision = environment.pendingDecision
        (firstZoneDecision is YesNoDecision) shouldBe true
        (firstZoneDecision!!.playerId == alice || firstZoneDecision.playerId == bob) shouldBe true
        var answeredDecisions = 0
        while (environment.pendingDecision != null) {
            val decision = environment.pendingDecision as? YesNoDecision
                ?: error("Commander zone replacement exposed an unsupported decision shape")
            environment.step(
                SubmitDecision(
                    decision.playerId,
                    YesNoResponse(decision.id, choice = true)
                )
            )
            answeredDecisions++
        }
        answeredDecisions shouldBe 2
        environment.state.getZone(alice, Zone.COMMAND) shouldBe listOf(aliceCommander)
        environment.state.getZone(bob, Zone.COMMAND) shouldBe listOf(bobCommander)

        // Build three red sources through ordinary Gym land actions. The second commander cast
        // must advertise and accept the +2 generic commander tax.
        var lastAliceTurn = environment.turnNumber
        repeat(2) {
            advanceToMain(alice, afterTurn = lastAliceTurn)
            val land = environment.legalActions().firstOrNull { it.action is PlayLand }
                ?: error("Alice had no land action while building commander tax")
            environment.step(land.action)
            lastAliceTurn = environment.turnNumber
        }
        advanceToMain(alice, afterTurn = lastAliceTurn)
        val recast = environment.legalActions().firstOrNull {
            val action = it.action as? CastSpell
            action?.cardId == aliceCommander && it.affordable
        } ?: error("Commander recast with tax was not an affordable Gym action")
        recast.manaCostString shouldBe "{2}{R}"
        environment.step(recast.action)
        environment.state.getEntity(aliceCommander)!!.get<CommanderComponent>()!!
            .castsFromCommandZone shouldBe 2

        // Raging Goblin has haste. Let it connect after the recast; the one-point threshold makes
        // the resulting terminal/winner distinction deterministic while the state still records
        // the authoritative commander-damage entry.
        advanceToStep(alice, Step.DECLARE_ATTACKERS)
        val lethalAttackTemplate = environment.legalActions().firstOrNull {
            it.action is DeclareAttackers
        }?.action as? DeclareAttackers ?: error("Alice had no recast attacker declaration template")
        val lethalAttack = lethalAttackTemplate.copy(attackers = mapOf(aliceCommander to bob))
        environment.step(lethalAttack)
        advanceToStep(bob, Step.DECLARE_BLOCKERS)
        val noBlocks = environment.legalActions().firstOrNull {
            val action = it.action as? DeclareBlockers
            action?.blockers?.isEmpty() == true
        } ?: error("Bob had no empty-blocker action after the lethal attack")
        environment.step(noBlocks.action)
        var lethalResolutionAttempts = 0
        while (!environment.isTerminal && lethalResolutionAttempts < 80) {
            passPriority()
            lethalResolutionAttempts++
        }
        environment.state.commanderDamageOf(aliceCommander, bob) shouldBe 1
        environment.isTerminal shouldBe true
        environment.winnerId shouldBe alice
    }

    test("maxSteps reports truncation and rejects post-horizon actions") {
        val environment = GameEnvironment.create(registry())
        val config = GameConfig(
            format = Format.Commander(),
            players = listOf(
                PlayerConfig("Alice", Deck.of("Mountain" to 99), commanderCardName = "Raging Goblin"),
                PlayerConfig("Bob", Deck.of("Mountain" to 99), commanderCardName = "Raging Goblin")
            ),
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = 7L
        )

        environment.reset(config, maxSteps = 1)
        environment.isTruncated.shouldBeFalse()
        val result = environment.step(environment.legalActions().first().action)

        result.terminated.shouldBeFalse()
        result.truncated.shouldBeTrue()
        environment.legalActions() shouldBe emptyList()
        shouldThrow<IllegalStateException> {
            environment.step(com.wingedsheep.engine.core.PassPriority(environment.playerIds.first()))
        }
    }

    test("snapshot and fork preserve horizon state") {
        val cardRegistry = registry()
        val environment = GameEnvironment.create(cardRegistry)
        val gym = GameGymEnv(
            environment = environment,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = cardRegistry)
        )
        val config = GameConfig(
            format = Format.Commander(),
            players = listOf(
                PlayerConfig("Alice", Deck.of("Mountain" to 99), commanderCardName = "Raging Goblin"),
                PlayerConfig("Bob", Deck.of("Mountain" to 99), commanderCardName = "Raging Goblin")
            ),
            skipMulligans = true,
            startingPlayerIndex = 0,
            seed = 7L
        )
        gym.reset(config, maxSteps = 2)
        val codec = SnapshotCodec()
        val handle = gym.snapshot(codec)
        val fork = environment.fork()

        fork.stepCount shouldBe environment.stepCount
        fork.isTruncated shouldBe environment.isTruncated

        environment.step(environment.legalActions().first().action)
        gym.restore(codec, handle)
        environment.stepCount shouldBe 0
        gym.observe().observation.let { (it as TrainingObservation).truncated.shouldBeFalse() }
    }
})
