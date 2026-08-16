package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
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

    fun replaceCardsInZones(
        state: GameState,
        opponent: EntityId,
        replacementName: String,
        zones: Set<Zone>
    ): GameState {
        val replacement = CardEntityFactory
            .create(registry().requireCard(replacementName), opponent)
            .get<CardComponent>()
        val hiddenIds = zones.flatMap { state.getZone(ZoneKey(opponent, it)) }
        val entities = state.entities.toMutableMap()
        hiddenIds.forEach { id ->
            entities[id] = checkNotNull(entities[id]).with(checkNotNull(replacement))
        }
        return state.copy(entities = entities)
    }

    fun replaceOpponentHiddenCards(
        state: GameState,
        opponent: EntityId,
        replacementName: String
    ): GameState = replaceCardsInZones(
        state,
        opponent,
        replacementName,
        setOf(Zone.HAND, Zone.LIBRARY)
    )

    fun replaceCard(
        state: GameState,
        owner: EntityId,
        entityId: EntityId,
        replacementName: String
    ): GameState {
        val replacement = CardEntityFactory
            .create(registry().requireCard(replacementName), owner)
            .get<CardComponent>()
        return state.copy(
            entities = state.entities + (
                entityId to checkNotNull(state.entities[entityId]).with(checkNotNull(replacement))
                )
        )
    }

    fun observation(state: GameState, perspective: EntityId): TrainingObservation =
        ObservationBuilder().build(state, perspective, emptyList()).observation as TrainingObservation

    fun result(state: GameState, perspective: EntityId): ObservationResult =
        ObservationBuilder().build(state, perspective, emptyList())

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

    test("own hand identity remains visible when paired hidden worlds differ") {
        val base = environment()
        val owner = base.playerIds[1]
        val goblinState = replaceCardsInZones(
            base.state,
            owner,
            "Raging Goblin",
            setOf(Zone.HAND)
        )

        val mountainObservation = observation(base.state, owner)
        val goblinObservation = observation(goblinState, owner)
        val mountainHand = mountainObservation.zones.single {
            it.ownerId == owner && it.zoneType == Zone.HAND
        }
        val goblinHand = goblinObservation.zones.single {
            it.ownerId == owner && it.zoneType == Zone.HAND
        }

        mountainHand.cards.size shouldBe mountainHand.size
        goblinHand.cards.size shouldBe goblinHand.size
        mountainHand.cards.map { it.cardDefinitionId }.toSet() shouldNotBe
            goblinHand.cards.map { it.cardDefinitionId }.toSet()
        mountainObservation.stateDigest shouldNotBe goblinObservation.stateDigest
    }

    test("library identity and order stay hidden for owner and opponent") {
        val base = environment()
        val owner = base.playerIds[1]
        val pairedState = replaceCardsInZones(
            base.state,
            owner,
            "Raging Goblin",
            setOf(Zone.LIBRARY)
        )

        listOf(base.playerIds[0], owner).forEach { perspective ->
            val mountainObservation = observation(base.state, perspective)
            val goblinObservation = observation(pairedState, perspective)
            val mountainLibrary = mountainObservation.zones.single {
                it.ownerId == owner && it.zoneType == Zone.LIBRARY
            }
            val goblinLibrary = goblinObservation.zones.single {
                it.ownerId == owner && it.zoneType == Zone.LIBRARY
            }

            mountainLibrary.hidden.shouldBe(true)
            goblinLibrary.hidden.shouldBe(true)
            mountainLibrary.cards.shouldBeEmpty()
            goblinLibrary.cards.shouldBeEmpty()
            mountainLibrary.size shouldBe goblinLibrary.size
            mountainObservation.stateDigest shouldBe goblinObservation.stateDigest
        }
    }

    test("command zone is public with a stable entity identity") {
        val base = environment()
        val owner = base.playerIds[1]
        val commandId = base.state.getHand(owner).first()
        val handKey = ZoneKey(owner, Zone.HAND)
        val commandKey = ZoneKey(owner, Zone.COMMAND)
        val zones = base.state.zones.toMutableMap()
        zones[handKey] = base.state.getHand(owner).drop(1)
        zones[commandKey] = base.state.getZone(commandKey) + commandId
        val state = base.state.copy(zones = zones)

        val command = observation(state, base.playerIds[0]).zones.single {
            it.ownerId == owner && it.zoneType == Zone.COMMAND
        }

        command.hidden.shouldBeFalse()
        command.size shouldBe 1
        command.cards.single().entityId shouldBe commandId
        command.cards.single().ownerId shouldBe owner
        command.cards.single().zone shouldBe Zone.COMMAND
    }

    test("non-acting perspective receives no legal actions or action registry") {
        val env = environment()
        val actingPlayer = env.playerIds[0]
        val nonActingPerspective = env.playerIds[1]
        val result = ObservationBuilder().build(
            env.state,
            nonActingPerspective,
            env.legalActions()
        )

        result.observation.agentToAct shouldBe actingPlayer
        result.observation.legalActions.shouldBeEmpty()
        result.registry shouldBe ActionRegistry.EMPTY
    }

    test("actor action view retains structured source identity") {
        val env = environment()
        val actor = env.playerIds[0]
        val sourceId = env.state.getHand(actor).first()
        val action = LegalAction(
            action = CastSpell(actor, sourceId),
            actionType = "CAST_SPELL",
            description = "Cast Mountain",
            validTargets = listOf(env.playerIds[1]),
            targetCount = 1,
            minTargets = 0,
            manaCostString = "{1}"
        )

        val view = ObservationBuilder().build(env.state, actor, listOf(action))
            .observation.legalActions.single()
        view.sourceEntityId shouldBe sourceId
        view.targetEntityIds shouldBe listOf(env.playerIds[1])
        view.manaCost shouldBe "{1}"
    }

    test("pending decision is generic and action-free for non-owner perspective") {
        val env = environment()
        val owner = env.playerIds[1]
        val sourceId = env.state.getHand(owner).first()
        val state = env.state.copy(
            pendingDecision = YesNoDecision(
                id = "private-decision-1",
                playerId = owner,
                prompt = "Choose Raging Goblin",
                context = DecisionContext(
                    sourceId = sourceId,
                    sourceName = "Raging Goblin",
                    triggeringEntityId = sourceId,
                    effectHint = "Reveal Raging Goblin"
                )
            )
        )

        val ownerResult = result(state, owner)
        val otherResult = result(state, env.playerIds[0])
        ownerResult.observation.pendingDecision!!.prompt shouldBe "Choose Raging Goblin"
        val generic = otherResult.observation.pendingDecision!!
        generic.kind shouldBe PendingDecisionKind.GENERIC
        generic.decisionId.shouldBeNull()
        generic.prompt shouldBe ""
        generic.sourceEntityId.shouldBeNull()
        generic.sourceName.shouldBeNull()
        generic.triggeringEntityId.shouldBeNull()
        generic.effectHint.shouldBeNull()
        generic.requiresStructuredResponse shouldBe true
        otherResult.observation.legalActions.shouldBeEmpty()
        otherResult.registry shouldBe ActionRegistry.EMPTY
    }

    test("face-down exile omits unauthorized card objects but keeps total size") {
        val base = environment()
        val opponent = base.playerIds[1]
        val hiddenId = base.state.getHand(opponent)[0]
        val visibleId = base.state.getHand(opponent)[1]
        val goblinState = replaceCard(base.state, opponent, hiddenId, "Raging Goblin")

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
                zones = zones
            )
        }

        val mountainObservation = observation(moveToExile(base.state), base.playerIds[0])
        val goblinObservation = observation(moveToExile(goblinState), base.playerIds[0])
        val mountainExile = mountainObservation.zones.single {
            it.ownerId == opponent && it.zoneType == Zone.EXILE
        }
        val goblinExile = goblinObservation.zones.single {
            it.ownerId == opponent && it.zoneType == Zone.EXILE
        }

        mountainExile.size shouldBe 2
        goblinExile.size shouldBe 2
        mountainExile.cards.size shouldBe 1
        goblinExile.cards.size shouldBe 1
        mountainExile.cards.single().entityId shouldBe visibleId
        goblinExile.cards.single().entityId shouldBe visibleId
        mountainObservation.stateDigest shouldBe goblinObservation.stateDigest
    }

    test("face-down stack spell hides underlying identity") {
        val base = environment()
        val opponent = base.playerIds[1]
        val hiddenId = base.state.getHand(opponent).first()
        val goblinState = replaceCard(base.state, opponent, hiddenId, "Raging Goblin")

        fun moveToStack(state: GameState): GameState {
            val handKey = ZoneKey(opponent, Zone.HAND)
            val zones = state.zones.toMutableMap()
            zones[handKey] = state.getHand(opponent).drop(1)
            return state.copy(
                entities = state.entities + (
                    hiddenId to checkNotNull(state.getEntity(hiddenId)).with(FaceDownComponent)
                    ),
                zones = zones,
                stack = state.stack + hiddenId
            )
        }

        val mountainStack = observation(moveToStack(base.state), base.playerIds[0]).stack.single()
        val goblinStack = observation(moveToStack(goblinState), base.playerIds[0]).stack.single()

        mountainStack.entityId shouldBe hiddenId
        goblinStack.entityId shouldBe hiddenId
        mountainStack.name shouldBe goblinStack.name
        mountainStack.name shouldNotBe "Mountain"
        goblinStack.name shouldNotBe "Raging Goblin"
        mountainStack.oracleText shouldBe goblinStack.oracleText
    }

    test("public stack target metadata changes observation and digest") {
        val base = environment()
        val owner = base.playerIds[1]
        val stackId = base.state.getHand(owner).first()

        fun withTarget(target: EntityId): GameState {
            val handKey = ZoneKey(owner, Zone.HAND)
            val zones = base.state.zones.toMutableMap()
            zones[handKey] = base.state.getHand(owner).drop(1)
            val stackEntity = checkNotNull(base.state.getEntity(stackId)).with(
                TargetsComponent(listOf(ChosenTarget.Player(target)))
            )
            return base.state.copy(
                entities = base.state.entities + (stackId to stackEntity),
                zones = zones,
                stack = listOf(stackId)
            )
        }

        val first = observation(withTarget(base.playerIds[0]), base.playerIds[0])
        val second = observation(withTarget(base.playerIds[1]), base.playerIds[0])

        first.stack.single().targets shouldBe listOf(base.playerIds[0])
        second.stack.single().targets shouldBe listOf(base.playerIds[1])
        first.stateDigest shouldNotBe second.stateDigest
    }
})
