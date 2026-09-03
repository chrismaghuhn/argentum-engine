package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val FIXTURE_SEED = 70L

class TrajectoryObservationProjectionTest : FunSpec({

    fun registry(): CardRegistry = CardRegistry().also {
        it.register(PortalSet.cards)
        it.register(PortalSet.basicLands)
    }

    fun environment(): GameEnvironment {
        val env = GameEnvironment.create(registry())
        env.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20)),
                ),
                startingHandSize = 2,
                skipMulligans = true,
                startingPlayerIndex = 0,
                seed = FIXTURE_SEED,
            )
        )
        return env
    }

    fun observation(
        env: GameEnvironment,
        state: GameState = env.state,
        perspective: EntityId = env.playerIds.first(),
        legalActions: List<LegalAction> = emptyList(),
    ): TrainingObservation = ObservationBuilder(cardRegistry = registry())
        .build(state, perspective, legalActions)
        .observation as TrainingObservation

    fun pendingObservation(
        env: GameEnvironment,
        decisionId: String = "decision-original",
        prompt: String = "Choose whether to continue",
        sourceName: String? = "Mountain",
        effectHint: String? = "A presentation hint",
        sourceEntityId: EntityId? = env.state.getHand(env.playerIds.first()).first(),
        triggeringEntityId: EntityId? = env.state.getHand(env.playerIds.first()).last(),
    ): TrainingObservation {
        val decision = YesNoDecision(
            id = decisionId,
            playerId = env.playerIds.first(),
            prompt = prompt,
            context = DecisionContext(
                sourceId = sourceEntityId,
                sourceName = sourceName,
                triggeringEntityId = triggeringEntityId,
                effectHint = effectHint,
            ),
        )
        return observation(
            env,
            state = env.state.copy(pendingDecision = decision),
            perspective = env.playerIds.first(),
        )
    }

    fun replaceCardsInZones(
        state: GameState,
        owner: EntityId,
        replacementName: String,
        zones: Set<Zone>,
    ): GameState {
        val replacement = checkNotNull(CardEntityFactory
            .create(registry().requireCard(replacementName), owner)
            .get<CardComponent>())
        val hiddenIds = zones.flatMap { state.getZone(ZoneKey(owner, it)) }
        val entities = state.entities.toMutableMap()
        hiddenIds.forEach { id ->
            entities[id] = checkNotNull(entities[id]).with(replacement)
        }
        return state.copy(entities = entities)
    }

    fun replaceCard(
        state: GameState,
        owner: EntityId,
        cardId: EntityId,
        replacementName: String,
    ): GameState {
        val replacement = checkNotNull(CardEntityFactory
            .create(registry().requireCard(replacementName), owner)
            .get<CardComponent>())
        return state.copy(
            entities = state.entities + (
                cardId to checkNotNull(state.entities[cardId]).with(replacement)
                )
        )
    }

    fun moveFirstHandCardFaceDown(
        state: GameState,
        owner: EntityId,
    ): Pair<GameState, EntityId> {
        val cardId = state.getHand(owner).first()
        val handKey = ZoneKey(owner, Zone.HAND)
        val battlefieldKey = ZoneKey(owner, Zone.BATTLEFIELD)
        val zones = state.zones.toMutableMap()
        zones[handKey] = state.getHand(owner).drop(1)
        zones[battlefieldKey] = state.getBattlefield(owner) + cardId
        val entity = checkNotNull(state.getEntity(cardId)).with(FaceDownComponent)
        return state.copy(
            entities = state.entities + (cardId to entity),
            zones = zones,
        ) to cardId
    }

    fun project(observation: TrainingObservation): PlayerObservationV1 =
        ObservationCanonicalizer.playerObservationV1(observation)

    fun canonicalJson(projection: PlayerObservationV1): String = projection.canonicalJson()

    test("action IDs do not change the transport-free projection") {
        val env = environment()
        val base = observation(env, legalActions = env.legalActions())
        val changed = base.copy(
            legalActions = base.legalActions.mapIndexed { index, action ->
                action.copy(actionId = index + 1000)
            }
        )

        project(base) shouldBe project(changed)
        canonicalJson(project(base)) shouldBe canonicalJson(project(changed))
        project(base).semanticDigest() shouldBe project(changed).semanticDigest()
    }

    test("pending decision IDs do not change the transport-free projection") {
        val env = environment()
        val base = pendingObservation(env)
        val changed = base.copy(
            pendingDecision = base.pendingDecision!!.copy(decisionId = "decision-next")
        )

        project(base) shouldBe project(changed)
        canonicalJson(project(base)) shouldBe canonicalJson(project(changed))
    }

    test("a visible semantic field changes the projection") {
        val env = environment()
        val base = observation(env)
        val changed = base.copy(turnNumber = base.turnNumber + 1)

        project(base) shouldNotBe project(changed)
        project(base).semanticDigest() shouldNotBe project(changed).semanticDigest()
    }

    test("pending sourceEntityId is semantic") {
        val env = environment()
        val base = pendingObservation(env)
        val changed = base.copy(
            pendingDecision = base.pendingDecision!!.copy(sourceEntityId = EntityId("semantic-source"))
        )

        project(base) shouldNotBe project(changed)
        project(base).semanticDigest() shouldNotBe project(changed).semanticDigest()
    }

    test("pending triggeringEntityId is semantic") {
        val env = environment()
        val base = pendingObservation(env)
        val changed = base.copy(
            pendingDecision = base.pendingDecision!!.copy(
                triggeringEntityId = EntityId("semantic-trigger")
            )
        )

        project(base) shouldNotBe project(changed)
        project(base).semanticDigest() shouldNotBe project(changed).semanticDigest()
    }

    test("pending prompt is presentation-only") {
        val env = environment()
        val base = pendingObservation(env)
        val changed = base.copy(
            pendingDecision = base.pendingDecision!!.copy(prompt = "Different wording")
        )

        project(base) shouldBe project(changed)
    }

    test("pending sourceName is presentation-only") {
        val env = environment()
        val base = pendingObservation(env)
        val changed = base.copy(
            pendingDecision = base.pendingDecision!!.copy(sourceName = "Different source label")
        )

        project(base) shouldBe project(changed)
    }

    test("pending effectHint is presentation-only") {
        val env = environment()
        val base = pendingObservation(env)
        val changed = base.copy(
            pendingDecision = base.pendingDecision!!.copy(effectHint = "Different effect hint")
        )

        project(base) shouldBe project(changed)
    }

    test("hidden opponent hand identity does not change the projection") {
        val env = environment()
        val opponent = env.playerIds.last()
        val first = observation(env, perspective = env.playerIds.first())
        val hiddenVariant = replaceCardsInZones(
            env.state,
            opponent,
            "Raging Goblin",
            setOf(Zone.HAND),
        )
        val second = observation(env, state = hiddenVariant, perspective = env.playerIds.first())

        first.stateDigest shouldBe second.stateDigest
        project(first) shouldBe project(second)
        canonicalJson(project(first)) shouldBe canonicalJson(project(second))
        canonicalJson(project(second)).contains("Raging Goblin").shouldBeFalse()
    }

    test("hidden opponent library identity and order do not change the projection") {
        val env = environment()
        val opponent = env.playerIds.last()
        val first = observation(env, perspective = env.playerIds.first())
        val hiddenVariant = replaceCardsInZones(
            env.state,
            opponent,
            "Raging Goblin",
            setOf(Zone.LIBRARY),
        ).let { state ->
            state.copy(
                zones = state.zones + (
                    ZoneKey(opponent, Zone.LIBRARY) to state.getLibrary(opponent).reversed()
                    )
            )
        }
        val second = observation(env, state = hiddenVariant, perspective = env.playerIds.first())

        first.stateDigest shouldBe second.stateDigest
        project(first) shouldBe project(second)
        canonicalJson(project(first)) shouldBe canonicalJson(project(second))
        canonicalJson(project(second)).contains("Raging Goblin").shouldBeFalse()
    }

    test("face-down opponent card remains redacted in the projection") {
        val env = environment()
        val opponent = env.playerIds.last()
        val hiddenId = env.state.getHand(opponent).first()
        val hiddenState = replaceCard(
            env.state,
            opponent,
            hiddenId,
            "Raging Goblin",
        )
        val (faceDownState, faceDownId) = moveFirstHandCardFaceDown(hiddenState, opponent)
        val observation = observation(env, state = faceDownState, perspective = env.playerIds.first())
        val card = observation.zones.single {
            it.ownerId == opponent && it.zoneType == Zone.BATTLEFIELD
        }.cards.single { it.entityId == faceDownId }
        val projection = project(observation)
        val projectedCard = projection.zones.single {
            it.ownerId == opponent && it.zoneType == Zone.BATTLEFIELD
        }.cards.single { it.entityId == faceDownId }

        card.cardDefinitionId shouldBe null
        card.name shouldNotBe "Raging Goblin"
        projectedCard.cardDefinitionId shouldBe null
        projectedCard.name shouldNotBe "Raging Goblin"
        canonicalJson(projection).contains("Raging Goblin").shouldBeFalse()
    }

    test("mixed hidden exile preserves only the visible public card") {
        val env = environment()
        val opponent = env.playerIds.last()
        val hiddenId = env.state.getHand(opponent).first()
        val visibleId = env.state.getHand(opponent).last()
        val hiddenState = replaceCard(
            env.state,
            opponent,
            hiddenId,
            "Raging Goblin",
        )

        fun moveToExile(state: GameState): GameState {
            val handKey = ZoneKey(opponent, Zone.HAND)
            val exileKey = ZoneKey(opponent, Zone.EXILE)
            val zones = state.zones.toMutableMap()
            zones[handKey] = state.getHand(opponent).drop(2)
            zones[exileKey] = state.getExile(opponent) + listOf(hiddenId, visibleId)
            return state.copy(
                entities = state.entities + (
                    hiddenId to checkNotNull(state.getEntity(hiddenId)).with(FaceDownComponent)
                    ),
                zones = zones,
            )
        }

        val first = observation(env, state = moveToExile(env.state), perspective = env.playerIds.first())
        val second = observation(env, state = moveToExile(hiddenState), perspective = env.playerIds.first())
        val firstProjection = project(first)
        val secondProjection = project(second)
        val firstExile = firstProjection.zones.single {
            it.ownerId == opponent && it.zoneType == Zone.EXILE
        }
        val secondExile = secondProjection.zones.single {
            it.ownerId == opponent && it.zoneType == Zone.EXILE
        }

        firstExile.size shouldBe 2
        secondExile.size shouldBe 2
        firstExile.cards.map { it.entityId } shouldBe listOf(visibleId)
        secondExile.cards.map { it.entityId } shouldBe listOf(visibleId)
        firstProjection shouldBe secondProjection
        canonicalJson(secondProjection).contains("Raging Goblin").shouldBeFalse()
    }

    test("authorized revealed information remains represented") {
        val env = environment()
        val owner = env.playerIds.last()
        val perspective = env.playerIds.first()
        val revealedId = env.state.getLibrary(owner).first()
        val revealedState = env.state.updateEntity(revealedId) {
            it.with(RevealedToComponent.to(perspective))
        }
        val observation = observation(env, state = revealedState, perspective = perspective)
        val projection = project(observation)
        val revealedCard = projection.zones.single {
            it.ownerId == owner && it.zoneType == Zone.LIBRARY
        }.cards.single { it.entityId == revealedId }

        revealedCard.cardDefinitionId.shouldNotBeNull()
        revealedCard.name shouldBe "Mountain"
    }

    test("projection omits legal domains, routing IDs, and presentation text") {
        val env = environment()
        val source = pendingObservation(env)
        val projection = project(source)
        val encoded = canonicalJson(projection)

        encoded.contains("legalActions").shouldBeFalse()
        encoded.contains("structuredDomain").shouldBeFalse()
        encoded.contains("actionId").shouldBeFalse()
        encoded.contains("decisionId").shouldBeFalse()
        encoded.contains("prompt").shouldBeFalse()
        encoded.contains("sourceName").shouldBeFalse()
        encoded.contains("effectHint").shouldBeFalse()
    }

    test("projection carries an explicit schema identity and round-trips") {
        val env = environment()
        val source = observation(env)
        val projection = project(source)
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
            classDiscriminator = "type"
            allowStructuredMapKeys = true
        }
        val encoded = json.encodeToString(PlayerObservationV1.serializer(), projection)

        projection.projectionVersion shouldBe PLAYER_OBSERVATION_V1_VERSION
        projection.projectionSchemaIdentity shouldBe PLAYER_OBSERVATION_V1_SCHEMA_IDENTITY
        projection.wireSchemaHash shouldBe source.schemaHash
        projection.observationDigest shouldBe source.stateDigest
        json.decodeFromString(PlayerObservationV1.serializer(), encoded) shouldBe projection
    }

    test("unknown future projection versions fail closed") {
        val env = environment()
        val projection = project(observation(env))
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
            classDiscriminator = "type"
            allowStructuredMapKeys = true
        }
        val encoded = json.encodeToString(PlayerObservationV1.serializer(), projection)
        val unknownVersion = buildJsonObject {
            json.parseToJsonElement(encoded).jsonObject.forEach { (key, value) -> put(key, value) }
            put("projectionVersion", JsonPrimitive(2))
        }.toString()

        shouldThrow<IllegalArgumentException> {
            json.decodeFromString(PlayerObservationV1.serializer(), unknownVersion)
        }
    }

    test("existing StateDigest remains the source observation binding") {
        val env = environment()
        val observation = observation(env, legalActions = env.legalActions())
        val existingDigest = observation.stateDigest

        project(observation).observationDigest shouldBe existingDigest
        StateDigest.compute(observation) shouldBe existingDigest
    }
})
