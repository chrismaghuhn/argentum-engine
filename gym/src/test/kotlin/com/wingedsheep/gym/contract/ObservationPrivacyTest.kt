package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ObservationPrivacyTest : FunSpec({

    fun registry(): CardRegistry =
        CardRegistry().also {
            it.register(PortalSet.cards)
            it.register(PortalSet.basicLands)
        }

    fun environment(): GameEnvironment {
        val env = GameEnvironment.create(registry())
        env.reset(
            GameConfig(
                players = listOf(
                    PlayerConfig("Alice", Deck.of("Mountain" to 20)),
                    PlayerConfig("Bob", Deck.of("Mountain" to 20))
                ),
                skipMulligans = true,
                startingPlayerIndex = 0
            )
        )
        return env
    }

    fun replaceOpponentHiddenCards(
        state: GameState,
        opponent: EntityId,
        replacementName: String
    ): GameState {
        val replacement = CardEntityFactory
            .create(registry().requireCard(replacementName), opponent)
            .get<CardComponent>()
        val hiddenIds = state.getHand(opponent) + state.getLibrary(opponent)
        val entities = state.entities.toMutableMap()
        hiddenIds.forEach { id ->
            entities[id] = checkNotNull(entities[id]).with(checkNotNull(replacement))
        }
        return state.copy(entities = entities)
    }

    fun observation(state: GameState, perspective: EntityId): TrainingObservation =
        ObservationBuilder().build(state, perspective, emptyList()).observation as TrainingObservation

    fun moveFirstOpponentHandCardFaceDownToBattlefield(
        state: GameState,
        opponent: EntityId
    ): Pair<GameState, EntityId> {
        val cardId = state.getHand(opponent).first()
        val handKey = ZoneKey(opponent, Zone.HAND)
        val battlefieldKey = ZoneKey(opponent, Zone.BATTLEFIELD)
        val zones = state.zones.toMutableMap()
        zones[handKey] = state.getHand(opponent).drop(1)
        zones[battlefieldKey] = state.getBattlefield(opponent) + cardId
        val entity = checkNotNull(state.getEntity(cardId)).with(FaceDownComponent)
        return state.copy(
            entities = state.entities + (cardId to entity),
            zones = zones
        ) to cardId
    }

    test("opponent hand identity is absent from the complete masked observation") {
        val base = environment()
        val mountainState = base.state
        val goblinState = replaceOpponentHiddenCards(
            base.state,
            base.playerIds[1],
            "Raging Goblin"
        )
        val perspective = base.playerIds[0]

        val mountainObservation = observation(mountainState, perspective)
        val goblinObservation = observation(goblinState, perspective)

        val mountainHand = mountainObservation.zones.single {
            it.ownerId == base.playerIds[1] && it.zoneType == Zone.HAND
        }
        val goblinHand = goblinObservation.zones.single {
            it.ownerId == base.playerIds[1] && it.zoneType == Zone.HAND
        }

        mountainHand.cards.shouldBeEmpty()
        goblinHand.cards.shouldBeEmpty()
        mountainHand.size shouldBe goblinHand.size
        mountainObservation shouldBe goblinObservation
        mountainObservation.stateDigest shouldBe goblinObservation.stateDigest
    }

    test("opponent face-down battlefield identity is not observable") {
        val base = environment()
        val opponent = base.playerIds[1]
        val (mountainState, mountainId) =
            moveFirstOpponentHandCardFaceDownToBattlefield(base.state, opponent)
        val goblinBase = replaceOpponentHiddenCards(base.state, opponent, "Raging Goblin")
        val (goblinState, goblinId) =
            moveFirstOpponentHandCardFaceDownToBattlefield(goblinBase, opponent)
        val perspective = mountainState.turnOrder.first()

        val mountainObservation = observation(mountainState, perspective)
        val goblinObservation = observation(goblinState, perspective)
        val mountainCard = mountainObservation.zones.single {
            it.zoneType == Zone.BATTLEFIELD && it.ownerId == mountainState.turnOrder[1]
        }.cards.single()
        val goblinCard = goblinObservation.zones.single {
            it.zoneType == Zone.BATTLEFIELD && it.ownerId == goblinState.turnOrder[1]
        }.cards.single()

        mountainCard.entityId shouldBe mountainId
        goblinCard.entityId shouldBe goblinId
        mountainCard.cardDefinitionId.shouldBeNull()
        goblinCard.cardDefinitionId.shouldBeNull()
        mountainCard.name shouldBe goblinCard.name
        mountainCard.name shouldNotBe "Mountain"
        goblinCard.name shouldNotBe "Raging Goblin"
        mountainCard.oracleText shouldBe goblinCard.oracleText
        mountainCard.manaCost shouldBe goblinCard.manaCost
        mountainCard.types shouldBe goblinCard.types
        mountainCard.power shouldBe goblinCard.power
        mountainCard.toughness shouldBe goblinCard.toughness
        mountainObservation.stateDigest shouldBe goblinObservation.stateDigest
    }
})
