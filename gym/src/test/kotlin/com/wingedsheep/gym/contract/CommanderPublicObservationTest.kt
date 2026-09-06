package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.gym.GameGymEnv
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString

class CommanderPublicObservationTest : FunSpec({

    fun registry(): CardRegistry = CardRegistry().apply {
        register(PortalSet.cards)
        register(PortalSet.basicLands)
    }

    fun environment(): GameEnvironment {
        val env = GameEnvironment.create(registry())
        env.reset(
            GameConfig(
                format = Format.Commander(),
                startingHandSize = 3,
                players = listOf(
                    PlayerConfig(
                        name = "Alice",
                        deck = Deck.of("Mountain" to 99),
                        commanderCardName = "Raging Goblin",
                    ),
                    PlayerConfig(
                        name = "Bob",
                        deck = Deck.of("Mountain" to 99),
                        commanderCardName = "Raging Goblin",
                    ),
                ),
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = 7L,
            ),
        )
        return env
    }

    fun publicState(
        env: GameEnvironment,
        state: com.wingedsheep.engine.state.GameState = env.state,
        perspective: com.wingedsheep.sdk.model.EntityId = env.playerIds.first(),
    ): CommanderPublicStateV1 = checkNotNull(
        ObservationBuilder(cardRegistry = registry())
            .build(state, perspective, emptyList())
            .commanderPublicState,
    )

    test("CMD-PUB-01 exposes designated commanders without using runtime entity identity") {
        val env = environment()
        val public = publicState(env)

        public.commanders shouldHaveSize 2
        public.commanders.map { it.ownerPlayerId } shouldBe env.playerIds
        public.commanders.forEach { commander ->
            commander.publicCommanderIdentity.isNotBlank().shouldBeTrue()
            commander.publicCurrentZone shouldBe CommanderPublicZoneKind.COMMAND
            commander.castsFromCommandZone shouldBe 0
        }

        public.canonicalJson() shouldNotContain env.state.getZone(env.playerIds.first(), Zone.COMMAND).single().value
    }

    test("CMD-PUB-02 designation survives battlefield movement and authoritative cast count") {
        val env = environment()
        val owner = env.playerIds.first()
        val commanderId = env.state.getZone(owner, Zone.COMMAND).single()
        val state = env.state
            .moveToZone(
                commanderId,
                ZoneKey(owner, Zone.COMMAND),
                ZoneKey(owner, Zone.BATTLEFIELD),
            )
            .updateEntity(commanderId) {
                it.with(CommanderComponent(ownerId = owner, castsFromCommandZone = 2))
            }

        val commander = publicState(env, state).commanders.single { it.ownerPlayerId == owner }
        commander.publicCommanderIdentity.isNotBlank().shouldBeTrue()
        commander.publicCurrentZone shouldBe CommanderPublicZoneKind.BATTLEFIELD
        commander.castsFromCommandZone shouldBe 2
    }

    test("CMD-PUB-03 leaves current effective commander cost to the legal-domain authority") {
        val env = environment()
        val public = publicState(env)

        public.canonicalJson() shouldNotContain "manaCost"
        public.canonicalJson() shouldNotContain "currentEffectiveManaCost"
    }

    test("CMD-PUB-04 commander damage projects from the authoritative ledger") {
        val env = environment()
        val owner = env.playerIds.first()
        val defendingPlayer = env.playerIds[1]
        val commanderId = env.state.getZone(owner, Zone.COMMAND).single()
        val state = env.state
            .recordCommanderDamage(commanderId, defendingPlayer, 3)
            .recordCommanderDamage(commanderId, owner, 1)

        val commander = publicState(env, state).commanders.single { it.ownerPlayerId == owner }
        commander.commanderDamageThreshold shouldBe 21
        commander.damageByDefendingPlayer.map { it.defendingPlayerId } shouldBe env.playerIds
        commander.damageByDefendingPlayer.map { it.cumulativeDamage } shouldBe listOf(1, 3)
    }

    test("CMD-PUB-05 same printed commander names remain owner-distinct") {
        val env = environment()
        val commanders = publicState(env).commanders

        commanders.map { it.ownerPlayerId to it.publicCommanderIdentity }.toSet() shouldHaveSize 2
        commanders.map { it.ownerPlayerId }.distinct() shouldHaveSize 2
    }

    test("CMD-PUB-07 hidden physical zone has no entity or library-position leakage") {
        val env = environment()
        val owner = env.playerIds.first()
        val commanderId = env.state.getZone(owner, Zone.COMMAND).single()
        val state = env.state.moveToZone(
            commanderId,
            ZoneKey(owner, Zone.COMMAND),
            ZoneKey(owner, Zone.LIBRARY),
        )

        val ownerView = publicState(env, state, owner).commanders.single { it.ownerPlayerId == owner }
        val opponentView = publicState(env, state, env.playerIds[1]).commanders.single { it.ownerPlayerId == owner }
        ownerView.publicCurrentZone shouldBe CommanderPublicZoneKind.LIBRARY
        opponentView.publicCurrentZone shouldBe CommanderPublicZoneKind.LIBRARY

        val encoded = publicState(env, state, env.playerIds[1]).canonicalJson()
        encoded shouldNotContain commanderId.value
        encoded shouldNotContain "libraryIndex"
    }

    test("CMD-PUB-06 hidden-only opponent card changes do not alter commander public state") {
        val env = environment()
        val hiddenCard = env.state.getLibrary(env.playerIds[1]).first()
        val replacement = checkNotNull(
            CardEntityFactory.create(registry().requireCard("Raging Goblin"), env.playerIds[1])
                .get<CardComponent>(),
        )
        val pairedState = env.state.copy(
            entities = env.state.entities + (
                hiddenCard to checkNotNull(env.state.entities[hiddenCard]).with(replacement)
                ),
        )

        publicState(env, env.state, env.playerIds.first()) shouldBe
            publicState(env, pairedState, env.playerIds.first())
    }

    test("CMD-PUB-08 Commander state is outside the unchanged V1 wire projection") {
        val env = environment()
        val result = ObservationBuilder(cardRegistry = registry())
            .build(env.state, env.playerIds.first(), emptyList())
        val observation = result.observation as TrainingObservation
        val encoded = A3SemanticJson.strictJson.encodeToString(
            TrainingObservation.serializer(),
            observation,
        )

        encoded shouldNotContain "commanderPublicState"
        PlayerObservationV1.from(observation).wireSchemaHash shouldBe observation.schemaHash
        result.commanderPublicState!!.version shouldBe COMMANDER_PUBLIC_STATE_V1_VERSION
    }

    test("Commander public state survives the trusted GameGymEnv observation seam") {
        val env = environment()
        val gym = GameGymEnv(
            environment = env,
            perspectivePlayerIndex = 0,
            observationBuilder = ObservationBuilder(cardRegistry = registry()),
        )

        val first = gym.observe()
        val second = gym.observe()
        first.commanderPublicState shouldBe second.commanderPublicState
        first.commanderPublicState!!.commanders shouldHaveSize 2
    }

    test("Commander public damage entries use deterministic player order") {
        val env = environment()
        val owner = env.playerIds.first()
        val commanderId = env.state.getZone(owner, Zone.COMMAND).single()
        val first = env.state
            .recordCommanderDamage(commanderId, env.playerIds[1], 3)
            .recordCommanderDamage(commanderId, owner, 1)
        val second = env.state
            .recordCommanderDamage(commanderId, owner, 1)
            .recordCommanderDamage(commanderId, env.playerIds[1], 3)

        publicState(env, first).canonicalJson() shouldBe publicState(env, second).canonicalJson()
    }
})
