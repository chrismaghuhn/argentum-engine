package com.wingedsheep.gym

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.GameInitializer
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.TakeMulligan
import com.wingedsheep.engine.core.TurnManager
import com.wingedsheep.engine.handlers.MulliganHandler
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.gym.contract.ObservationBuilder
import com.wingedsheep.gym.contract.TrainingObservation
import com.wingedsheep.mtg.sets.MtgSetCatalog
import com.wingedsheep.sdk.core.AttackMode
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.core.Zone
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Characterization-only audit for the relationship between the numeric engine seed and the
 * configured starting-player/roster variants. It deliberately observes the existing immutable
 * GameState RNG cursor; it does not add a production cursor or alter setup semantics.
 */
class StartPlayerRngCouplingAuditTest : FunSpec({
    test("RNG-01 same seed and same setup reproduce the exact initial state") {
        AUDIT_SEEDS.forEach { seed ->
            val first = initialize(seed, startingPlayerIndex = 0)
            val second = initialize(seed, startingPlayerIndex = 0)

            first.state shouldBe second.state
            first.events shouldBe second.events
            first.seed shouldBe seed
            second.seed shouldBe seed
        }
    }

    test("RNG-02 start-player reversal preserves setup RNG and per-player zones") {
        AUDIT_SEEDS.forEach { seed ->
            val first = initialize(seed, startingPlayerIndex = 0)
            val second = initialize(seed, startingPlayerIndex = 1)

            first.state.rng.state shouldBe second.state.rng.state
            AUDIT_PLAYERS.forEach { player ->
                zoneIds(first.state, player.id, Zone.LIBRARY) shouldBe
                    zoneIds(second.state, player.id, Zone.LIBRARY)
                zoneIds(first.state, player.id, Zone.HAND) shouldBe
                    zoneIds(second.state, player.id, Zone.HAND)
            }
            first.events shouldBe second.events

            first.state.turnOrder shouldNotBe second.state.turnOrder
            first.state.activePlayerId shouldNotBe second.state.activePlayerId
            first.state.priorityPlayerId shouldNotBe second.state.priorityPlayerId
            first.state shouldNotBe second.state
        }
    }

    test("RNG-03 hand smoothing remains config-order coupled, not start-player coupled") {
        AUDIT_SEEDS.forEach { seed ->
            val first = initialize(seed, startingPlayerIndex = 0, useHandSmoother = true)
            val second = initialize(seed, startingPlayerIndex = 1, useHandSmoother = true)

            first.state.rng.state shouldBe second.state.rng.state
            AUDIT_PLAYERS.forEach { player ->
                zoneIds(first.state, player.id, Zone.LIBRARY) shouldBe
                    zoneIds(second.state, player.id, Zone.LIBRARY)
                zoneIds(first.state, player.id, Zone.HAND) shouldBe
                    zoneIds(second.state, player.id, Zone.HAND)
            }
        }
    }

    test("RNG-04 first policy-visible observation differs only at the active-player boundary") {
        AUDIT_SEEDS.forEach { seed ->
            val first = observedAtSetup(seed, startingPlayerIndex = 0)
            val second = observedAtSetup(seed, startingPlayerIndex = 1)

            first.environment.state.rng.state shouldBe second.environment.state.rng.state
            first.observation.stateDigest shouldNotBe second.observation.stateDigest
            first.observation.activePlayerId shouldNotBe second.observation.activePlayerId
            first.observation.agentToAct shouldNotBe second.observation.agentToAct
            first.observation.zones.associateBy { it.ownerId } shouldBe
                second.observation.zones.associateBy { it.ownerId }
        }
    }

    test("RNG-05 the first-player draw path does not advance GameRng") {
        AUDIT_SEEDS.forEach { seed ->
            val first = initialize(seed, startingPlayerIndex = 0)
            val second = initialize(seed, startingPlayerIndex = 1)
            val turnManager = TurnManager(registry())

            val firstDraw = turnManager.drawCards(
                first.state,
                checkNotNull(first.state.activePlayerId),
                count = 1,
            )
            val secondDraw = turnManager.drawCards(
                second.state,
                checkNotNull(second.state.activePlayerId),
                count = 1,
            )

            firstDraw.newState.rng.state shouldBe first.state.rng.state
            secondDraw.newState.rng.state shouldBe second.state.rng.state
            firstDraw.newState.rng.state shouldBe secondDraw.newState.rng.state
        }
    }

    test("RNG-06 equal mulligan counts keep the total cursor equal but bind streams to turn order") {
        AUDIT_SEEDS.forEach { seed ->
            val first = takeOneMulliganPerTurn(
                initialize(seed, startingPlayerIndex = 0, skipMulligans = false).state,
            )
            val second = takeOneMulliganPerTurn(
                initialize(seed, startingPlayerIndex = 1, skipMulligans = false).state,
            )

            first.rng.state shouldBe second.rng.state
            val anyPlayerZoneChanged = AUDIT_PLAYERS.any { player ->
                zoneIds(first, player.id, Zone.LIBRARY) != zoneIds(second, player.id, Zone.LIBRARY) ||
                    zoneIds(first, player.id, Zone.HAND) != zoneIds(second, player.id, Zone.HAND)
            }
            check(anyPlayerZoneChanged) {
                "Expected turn-order mulligan shuffles to bind different RNG segments to players"
            }
        }
    }

    test("RNG-07 roster reversal is separate from start-player reversal") {
        AUDIT_SEEDS.forEach { seed ->
            val forward = initialize(seed, startingPlayerIndex = 0, roster = AUDIT_PLAYERS)
            val reversed = initialize(seed, startingPlayerIndex = 0, roster = AUDIT_PLAYERS.reversed())

            forward.state.rng.state shouldBe reversed.state.rng.state
            forward.state.turnOrder.first() shouldNotBe reversed.state.turnOrder.first()
            val anyPlayerZoneChanged = AUDIT_PLAYERS.any { player ->
                zoneIds(forward.state, player.id, Zone.LIBRARY) !=
                    zoneIds(reversed.state, player.id, Zone.LIBRARY)
            }
            check(anyPlayerZoneChanged) {
                "Expected roster reversal to bind library-shuffle streams to different players"
            }
        }
    }
})

private val AUDIT_SEEDS = listOf(0L, 1L, 42L, 987_654_321L)

private data class AuditPlayer(
    val name: String,
    val id: EntityId,
    val commander: String,
    val deck: Deck,
)

private val AUDIT_PLAYERS: List<AuditPlayer> by lazy {
    listOf(
        lockedPlayer("akiri-v0.1.txt", "audit-akiri"),
        lockedPlayer("chevill-v0.1.txt", "audit-chevill"),
    )
}

private fun registry(): CardRegistry = CardRegistry().apply {
    MtgSetCatalog.all.forEach { set ->
        register(set.cards)
        register(set.basicLands)
    }
}

private fun initialize(
    seed: Long,
    startingPlayerIndex: Int,
    skipMulligans: Boolean = true,
    useHandSmoother: Boolean = false,
    roster: List<AuditPlayer> = AUDIT_PLAYERS,
) = GameInitializer(registry()).initializeGame(
    GameConfig(
        players = roster.map { player ->
            PlayerConfig(
                name = player.name,
                deck = player.deck,
                playerId = player.id,
                commanderCardName = player.commander,
            )
        },
        startingHandSize = 7,
        skipMulligans = skipMulligans,
        useHandSmoother = useHandSmoother,
        startingPlayerIndex = startingPlayerIndex,
        format = Format.Commander(),
        attackMode = AttackMode.MULTIPLE,
        seed = seed,
    ),
)

private data class ObservedSetup(
    val environment: GameEnvironment,
    val observation: TrainingObservation,
)

private fun observedAtSetup(seed: Long, startingPlayerIndex: Int): ObservedSetup {
    val environment = GameEnvironment.create(registry())
    val gym = GameGymEnv(
        environment = environment,
        perspectivePlayerIndex = 0,
        observationBuilder = ObservationBuilder(cardRegistry = registry()),
    )
    val result = gym.reset(
        gameConfig = configFor(seed, startingPlayerIndex),
        maxSteps = 100,
    )
    return ObservedSetup(
        environment = environment,
        observation = result.observation as TrainingObservation,
    )
}

private fun configFor(seed: Long, startingPlayerIndex: Int): GameConfig = GameConfig(
    players = AUDIT_PLAYERS.map { player ->
        PlayerConfig(
            name = player.name,
            deck = player.deck,
            playerId = player.id,
            commanderCardName = player.commander,
        )
    },
    startingHandSize = 7,
    skipMulligans = true,
    useHandSmoother = false,
    startingPlayerIndex = startingPlayerIndex,
    format = Format.Commander(),
    attackMode = AttackMode.MULTIPLE,
    seed = seed,
)

private fun takeOneMulliganPerTurn(state: GameState): GameState {
    val handler = MulliganHandler(registry())
    var current = state
    current.turnOrder.forEach { playerId ->
        val result = handler.handleTakeMulligan(current, TakeMulligan(playerId))
        check(result.isSuccess) { "Mulligan failed for $playerId: ${result.error}" }
        current = result.newState
    }
    return current
}

private fun zoneIds(state: GameState, playerId: EntityId, zone: Zone): List<EntityId> =
    state.getZone(ZoneKey(playerId, zone))

private fun lockedPlayer(fileName: String, idValue: String): AuditPlayer {
    val lines = Files.readAllLines(repositoryRoot().resolve("docs/ml/curriculum").resolve(fileName))
    val cards = lines
        .filter { it.matches(Regex("^\\d{3}\\t.*")) }
        .map { it.substringAfterLast('\t') }
    check(cards.size == 100) { "Locked deck $fileName has ${cards.size} cards" }
    return AuditPlayer(
        name = cards.first(),
        id = EntityId(idValue),
        commander = cards.first(),
        deck = Deck(cards.drop(1)),
    )
}

private fun repositoryRoot(): Path = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
    .first { Files.isDirectory(it.resolve("docs/ml/curriculum")) }
