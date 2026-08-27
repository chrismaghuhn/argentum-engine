package com.wingedsheep.gym.contract

import com.wingedsheep.engine.core.GameConfig
import com.wingedsheep.engine.core.PlayerConfig
import com.wingedsheep.engine.core.CardEntityFactory
import com.wingedsheep.engine.core.ActivateAbility
import com.wingedsheep.engine.core.CastSpell
import com.wingedsheep.engine.core.DeclareAttackers
import com.wingedsheep.engine.core.DiagnosticCode
import com.wingedsheep.engine.core.DecisionContext
import com.wingedsheep.engine.core.DecisionPhase
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.OptionMetadata
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.legalactions.AttackDeclarationDomainSupport
import com.wingedsheep.engine.legalactions.RulesAttackBandConstraints
import com.wingedsheep.engine.legalactions.RulesAttackDeclarationDomain
import com.wingedsheep.engine.legalactions.TargetDomainSupport
import com.wingedsheep.engine.legalactions.TargetInfo
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.event.GrantedStaticAbility
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.EmblemActivatedAbilityComponent
import com.wingedsheep.engine.state.components.identity.FaceDownComponent
import com.wingedsheep.engine.state.components.identity.RevealedToComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.engine.state.components.stack.TargetsComponent
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.state.components.battlefield.ClassLevelComponent
import com.wingedsheep.gym.GameEnvironment
import com.wingedsheep.mtg.sets.definitions.ktk.KhansOfTarkirSet
import com.wingedsheep.mtg.sets.definitions.por.PortalSet
import com.wingedsheep.mtg.sets.definitions.blb.cards.BuildersTalent
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.dsl.Costs
import com.wingedsheep.sdk.dsl.Effects
import com.wingedsheep.sdk.model.Deck
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.AbilityId
import com.wingedsheep.sdk.scripting.ActivatedAbility
import com.wingedsheep.sdk.scripting.Duration
import com.wingedsheep.sdk.scripting.GrantActivatedAbility
import com.wingedsheep.sdk.scripting.LookAtTopOfLibrary
import com.wingedsheep.sdk.scripting.RevealTopOfLibrary
import com.wingedsheep.sdk.scripting.filters.unified.GroupFilter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ObservationPrivacyTest : FunSpec({

    fun registry(): CardRegistry =
        CardRegistry().also {
            it.register(PortalSet.cards)
            it.register(PortalSet.basicLands)
        }

    fun registryWithLens(): CardRegistry = registry().also {
        it.register(KhansOfTarkirSet.cards)
    }

    fun abilityCard(firstAbilityId: AbilityId, secondAbilityId: AbilityId? = null) =
        KhansOfTarkirSet.cards.single { it.name == "Abzan Banner" }.let { template ->
            val sourceAbility = template.script.activatedAbilities.first()
            val abilities = if (secondAbilityId == null) {
                template.script.activatedAbilities.mapIndexed { index, ability ->
                    if (index == 0) ability.copy(id = firstAbilityId) else ability
                }
            } else {
                listOf(
                    sourceAbility.copy(id = firstAbilityId),
                    sourceAbility.copy(id = secondAbilityId)
                )
            }
            template.copy(script = template.script.copy(activatedAbilities = abilities))
        }

    fun stateWithAbilityCard(
        state: GameState,
        playerId: EntityId,
        cardDefinition: com.wingedsheep.sdk.model.CardDefinition
    ): Pair<GameState, EntityId> {
        val sourceId = state.getHand(playerId).first()
        val entity = CardEntityFactory.create(cardDefinition, playerId)
        return state.copy(entities = state.entities + (sourceId to entity)) to sourceId
    }

    fun moveCardToBattlefield(state: GameState, playerId: EntityId, cardId: EntityId): GameState {
        val handKey = ZoneKey(playerId, Zone.HAND)
        val battlefieldKey = ZoneKey(playerId, Zone.BATTLEFIELD)
        val zones = state.zones.toMutableMap()
        zones[handKey] = state.getHand(playerId).filterNot { it == cardId }
        zones[battlefieldKey] = state.getBattlefield(playerId) + cardId
        return state.copy(zones = zones)
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

    fun observation(
        state: GameState,
        perspective: EntityId,
        cardRegistry: CardRegistry = registry()
    ): TrainingObservation =
        ObservationBuilder(cardRegistry = cardRegistry)
            .build(state, perspective, emptyList()).observation as TrainingObservation

    fun result(
        state: GameState,
        perspective: EntityId,
        cardRegistry: CardRegistry = registry()
    ): ObservationResult =
        ObservationBuilder(cardRegistry = cardRegistry).build(state, perspective, emptyList())

    fun targetedAction(
        state: GameState,
        playerId: EntityId,
        targetId: EntityId,
    ): LegalAction = LegalAction(
        action = CastSpell(playerId, state.getHand(playerId).first()),
        actionType = "CAST_SPELL",
        description = "Targeted privacy probe",
        requiresTargets = true,
        targetRequirements = listOf(
            TargetInfo(
                index = 0,
                description = "target",
                minTargets = 1,
                maxTargets = 1,
                validTargets = listOf(targetId),
                targetZone = "Battlefield",
            )
        ),
        targetDomainSupport = TargetDomainSupport.SUPPORTED,
    )

    fun targetedResult(
        state: GameState,
        perspective: EntityId,
        targetId: EntityId,
        cardRegistry: CardRegistry = registry(),
    ): ObservationResult = ObservationBuilder(cardRegistry = cardRegistry).build(
        state = state,
        perspectivePlayerId = perspective,
        legalActions = listOf(targetedAction(state, perspective, targetId)),
    )

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

    fun grantStaticAbilityToBattlefieldCard(
        state: GameState,
        playerId: EntityId,
        ability: com.wingedsheep.sdk.scripting.StaticAbility
    ): GameState {
        val anchorId = state.getHand(playerId).last()
        val handKey = ZoneKey(playerId, Zone.HAND)
        val battlefieldKey = ZoneKey(playerId, Zone.BATTLEFIELD)
        val zones = state.zones.toMutableMap()
        zones[handKey] = state.getHand(playerId).dropLast(1)
        zones[battlefieldKey] = state.getBattlefield(playerId) + anchorId
        return state.copy(
            zones = zones,
            grantedStaticAbilities = state.grantedStaticAbilities + GrantedStaticAbility(
                entityId = anchorId,
                ability = ability,
                duration = Duration.Permanent
            )
        )
    }

    fun observationForAbility(
        state: GameState,
        perspective: EntityId,
        sourceId: EntityId,
        abilityId: AbilityId,
        cardRegistry: CardRegistry = registry()
    ): TrainingObservation = ObservationBuilder(cardRegistry = cardRegistry)
        .build(
            state,
            perspective,
            listOf(
                LegalAction(
                    action = ActivateAbility(perspective, sourceId, abilityId),
                    actionType = "ACTIVATE_ABILITY",
                    description = "Activate ability"
                )
            )
        ).observation as TrainingObservation

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

    test("face-down battlefield target remains addressable without identity leakage") {
        val base = environment()
        val viewer = base.playerIds[0]
        val opponent = base.playerIds[1]
        val (state, faceDownId) =
            moveFirstOpponentHandCardFaceDownToBattlefield(base.state, opponent)

        val result = targetedResult(state, viewer, faceDownId)
        result.diagnostics.shouldBeEmpty()

        val observation = result.observation as TrainingObservation
        val targetDomain = observation.legalActions.single().targetDomain
            .shouldNotBeNull()
        targetDomain.requirements.single().candidates shouldBe listOf(faceDownId)

        val card = observation.zones.single {
            it.ownerId == opponent && it.zoneType == Zone.BATTLEFIELD
        }.cards.single { it.entityId == faceDownId }
        card.cardDefinitionId.shouldBeNull()
        card.name shouldNotBe "Mountain"
        card.name shouldNotBe "Raging Goblin"
    }

    test("own controlled face-down battlefield identity remains visible") {
        val base = environment()
        val owner = base.playerIds[0]
        val (state, cardId) = moveFirstOpponentHandCardFaceDownToBattlefield(base.state, owner)

        val card = observation(state, owner).zones.single {
            it.ownerId == owner && it.zoneType == Zone.BATTLEFIELD
        }.cards.single { it.entityId == cardId }

        card.cardDefinitionId.shouldNotBeNull()
        card.name shouldBe "Mountain"
        card.faceDown shouldBe true
    }

    test("battlefield look-at permission reveals an opponent face-down permanent") {
        val base = environment()
        val viewer = base.playerIds[0]
        val opponent = base.playerIds[1]
        val (faceDownState, faceDownId) =
            moveFirstOpponentHandCardFaceDownToBattlefield(base.state, opponent)
        val lensId = base.state.getHand(viewer).last()
        val lensCard = checkNotNull(CardEntityFactory
            .create(registryWithLens().requireCard("Lens of Clarity"), viewer)
            .get<CardComponent>())
        val handKey = ZoneKey(viewer, Zone.HAND)
        val battlefieldKey = ZoneKey(viewer, Zone.BATTLEFIELD)
        val zones = faceDownState.zones.toMutableMap()
        zones[handKey] = faceDownState.getHand(viewer).filterNot { it == lensId }
        zones[battlefieldKey] = faceDownState.getBattlefield(viewer) + lensId
        val state = faceDownState.copy(
            entities = faceDownState.entities +
                (lensId to checkNotNull(faceDownState.entities[lensId]).with(lensCard)),
            zones = zones
        )

        val card = observation(state, viewer, registryWithLens()).zones.single {
            it.ownerId == opponent && it.zoneType == Zone.BATTLEFIELD
        }.cards.single { it.entityId == faceDownId }

        card.cardDefinitionId.shouldNotBeNull()
        card.name shouldBe "Mountain"
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

    test("public attack order is unchanged when only hidden opponent cards differ") {
        val base = environment()
        val perspective = base.playerIds[0]
        val opponent = base.playerIds[1]
        val domain = RulesAttackDeclarationDomain(
            attackerOrder = listOf(perspective),
            attackerToDefenders = linkedMapOf(perspective to listOf(opponent)),
            mandatoryAttackers = emptyList(),
            canDeclareZeroAttackers = true,
            maxAttackers = null,
            coAttackerRequirements = emptyMap(),
            bandConstraints = RulesAttackBandConstraints(
                bandingAttackersByDefender = emptyMap(),
                nonBandingAttackersByDefender = linkedMapOf(opponent to listOf(perspective)),
            ),
        )
        val action = LegalAction(
            action = DeclareAttackers(perspective, emptyMap()),
            actionType = "DeclareAttackers",
            description = "public attack privacy probe",
            attackDeclarationDomain = domain,
            attackDeclarationDomainSupport = AttackDeclarationDomainSupport.SUPPORTED,
        )
        val hiddenVariant = replaceOpponentHiddenCards(base.state, opponent, "Raging Goblin")

        val first = ObservationBuilder(cardRegistry = registry())
            .build(base.state, perspective, listOf(action)).observation as TrainingObservation
        val second = ObservationBuilder(cardRegistry = registry())
            .build(hiddenVariant, perspective, listOf(action)).observation as TrainingObservation

        first.legalActions.single().attackDeclarationDomain!!.attackerOrder shouldBe
            second.legalActions.single().attackDeclarationDomain!!.attackerOrder
        ObservationCanonicalizer.semanticJson(first) shouldBe ObservationCanonicalizer.semanticJson(second)
        first.stateDigest shouldBe second.stateDigest
    }

    test("individually revealed library cards are visible without exposing other cards") {
        val base = environment()
        val owner = base.playerIds[1]
        val perspective = base.playerIds[0]
        val topId = base.state.getLibrary(owner).first()
        val hiddenId = base.state.getLibrary(owner)[1]
        val revealedState = base.state.updateEntity(topId) {
            it.with(RevealedToComponent.to(perspective))
        }

        val library = observation(revealedState, perspective).zones.single {
            it.ownerId == owner && it.zoneType == Zone.LIBRARY
        }

        library.size shouldBe base.state.getLibrary(owner).size
        library.cards.map { it.entityId } shouldBe listOf(topId)
        library.cards.single().name shouldBe "Mountain"
        library.cards.none { it.entityId == hiddenId } shouldBe true
    }

    test("public and private top-library visibility uses the shared Visibility rules") {
        val base = environment()
        val owner = base.playerIds[1]
        val opponent = base.playerIds[0]
        val ownerTopId = base.state.getLibrary(owner).first()
        val publicState = grantStaticAbilityToBattlefieldCard(
            base.state,
            owner,
            RevealTopOfLibrary
        )
        val publicLibrary = observation(publicState, opponent).zones.single {
            it.ownerId == owner && it.zoneType == Zone.LIBRARY
        }
        publicLibrary.cards.map { it.entityId } shouldBe listOf(ownerTopId)

        val privateState = grantStaticAbilityToBattlefieldCard(
            base.state,
            owner,
            LookAtTopOfLibrary
        )
        val ownerLibrary = observation(privateState, owner).zones.single {
            it.ownerId == owner && it.zoneType == Zone.LIBRARY
        }
        val opponentLibrary = observation(privateState, opponent).zones.single {
            it.ownerId == owner && it.zoneType == Zone.LIBRARY
        }
        ownerLibrary.cards.map { it.entityId } shouldBe listOf(ownerTopId)
        opponentLibrary.cards.shouldBeEmpty()
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
        val result = ObservationBuilder(cardRegistry = registry()).build(
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
            requiresTargets = true,
            targetRequirements = listOf(
                TargetInfo(
                    index = 0,
                    description = "target player",
                    minTargets = 0,
                    maxTargets = 1,
                    validTargets = listOf(env.playerIds[1]),
                    targetZone = "Player",
                )
            ),
            targetDomainSupport = TargetDomainSupport.SUPPORTED,
            manaCostString = "{1}"
        )

        val view = ObservationBuilder(cardRegistry = registry()).build(env.state, actor, listOf(action))
            .observation.legalActions.single()
        view.sourceEntityId shouldBe sourceId
        view.targetEntityIds shouldBe listOf(env.playerIds[1])
        view.manaCost shouldBe "{1}"
    }

    test("different structured abilities on one source change the semantic digest") {
        val env = environment()
        val actor = env.playerIds[0]
        val card = abilityCard(AbilityId("ability_a"), AbilityId("ability_b"))
        val cardRegistry = registry().also { it.register(card) }
        val (state, sourceId) = stateWithAbilityCard(env.state, actor, card)

        fun observationFor(abilityId: String): TrainingObservation {
            val action = LegalAction(
                action = ActivateAbility(actor, sourceId, AbilityId(abilityId)),
                actionType = "ACTIVATE_ABILITY",
                description = "Activate ability"
            )
            return ObservationBuilder(cardRegistry = cardRegistry)
                .build(state, actor, listOf(action)).observation as TrainingObservation
        }

        val first = observationFor("ability_a")
        val second = observationFor("ability_b")

        first.legalActions.single().description shouldBe second.legalActions.single().description
        first.stateDigest shouldNotBe second.stateDigest
    }

    test("generated ability handles alone do not change the semantic digest") {
        val env = environment()
        val actor = env.playerIds[0]
        val firstCard = abilityCard(AbilityId("ability_123"))
        val secondCard = abilityCard(AbilityId("ability_987"))
        val firstRegistry = registry().also { it.register(firstCard) }
        val secondRegistry = registry().also { it.register(secondCard) }
        val (firstState, sourceId) = stateWithAbilityCard(env.state, actor, firstCard)
        val (secondState, secondSourceId) = stateWithAbilityCard(env.state, actor, secondCard)
        secondSourceId shouldBe sourceId

        fun observationFor(
            state: GameState,
            cardRegistry: CardRegistry,
            abilityId: AbilityId
        ): TrainingObservation = ObservationBuilder(cardRegistry = cardRegistry)
            .build(
                state,
                actor,
                listOf(
                    LegalAction(
                        action = ActivateAbility(actor, sourceId, abilityId),
                        actionType = "ACTIVATE_ABILITY",
                        description = "Activate ability"
                    )
                )
            ).observation as TrainingObservation

        val first = observationFor(firstState, firstRegistry, AbilityId("ability_123"))
        val second = observationFor(secondState, secondRegistry, AbilityId("ability_987"))

        ObservationCanonicalizer.semanticJson(first) shouldBe ObservationCanonicalizer.semanticJson(second)
        first.stateDigest shouldBe second.stateDigest
    }

    test("distinct activated-ability ordinals remain digest-distinct when their handles differ") {
        val env = environment()
        val actor = env.playerIds[0]
        val card = abilityCard(AbilityId("ability_101"), AbilityId("ability_202"))
        val cardRegistry = registry().also { it.register(card) }
        val (state, sourceId) = stateWithAbilityCard(env.state, actor, card)

        fun observationFor(abilityId: AbilityId): TrainingObservation = ObservationBuilder(
            cardRegistry = cardRegistry
        ).build(
            state,
            actor,
            listOf(
                LegalAction(
                    action = ActivateAbility(actor, sourceId, abilityId),
                    actionType = "ACTIVATE_ABILITY",
                    description = "Activate ability"
                )
            )
        ).observation as TrainingObservation

        observationFor(AbilityId("ability_101")).stateDigest shouldNotBe
            observationFor(AbilityId("ability_202")).stateDigest
    }

    test("distinct statically granted generated abilities remain digest-distinct") {
        val env = environment()
        val actor = env.playerIds[0]
        val firstAbility = ActivatedAbility(
            id = AbilityId("ability_123"),
            cost = Costs.Free,
            effect = Effects.DrawCards(1)
        )
        val secondAbility = ActivatedAbility(
            id = AbilityId("ability_456"),
            cost = Costs.Free,
            effect = Effects.GainLife(3)
        )
        val firstGrant = GrantActivatedAbility(
            ability = firstAbility,
            filter = GroupFilter.source()
        )
        val secondGrant = GrantActivatedAbility(
            ability = secondAbility,
            filter = GroupFilter.source()
        )
        val stateWithFirstGrant = grantStaticAbilityToBattlefieldCard(env.state, actor, firstGrant)
        val sourceId = stateWithFirstGrant.getBattlefield(actor).last()
        val state = stateWithFirstGrant.copy(
            grantedStaticAbilities = stateWithFirstGrant.grantedStaticAbilities +
                GrantedStaticAbility(sourceId, secondGrant, Duration.Permanent)
        )

        observationForAbility(state, actor, sourceId, firstAbility.id).stateDigest shouldNotBe
            observationForAbility(state, actor, sourceId, secondAbility.id).stateDigest
    }

    test("donor-derived generated handles alone do not change the semantic digest") {
        val env = environment()
        val actor = env.playerIds[0]
        val firstAbility = ActivatedAbility(
            id = AbilityId("donor_101_ability_7"),
            cost = Costs.Free,
            effect = Effects.DrawCards(1)
        )
        val secondAbility = firstAbility.copy(id = AbilityId("donor_202_ability_7"))
        val firstGrant = GrantActivatedAbility(
            ability = firstAbility,
            filter = GroupFilter.source()
        )
        val stateWithFirstGrant = grantStaticAbilityToBattlefieldCard(env.state, actor, firstGrant)
        val sourceId = stateWithFirstGrant.getBattlefield(actor).last()
        val secondGrant = firstGrant.copy(ability = secondAbility)
        val secondState = stateWithFirstGrant.copy(
            grantedStaticAbilities = listOf(
                GrantedStaticAbility(sourceId, secondGrant, Duration.Permanent)
            )
        )

        val first = observationForAbility(stateWithFirstGrant, actor, sourceId, firstAbility.id)
        val second = observationForAbility(secondState, actor, sourceId, secondAbility.id)
        ObservationCanonicalizer.semanticJson(first) shouldBe ObservationCanonicalizer.semanticJson(second)
        first.stateDigest shouldBe second.stateDigest
    }

    test("distinct emblem-granted generated abilities remain digest-distinct") {
        val env = environment()
        val actor = env.playerIds[0]
        val sourceId = env.state.getHand(actor).first()
        val emblemId = env.state.getHand(actor).last()
        val firstAbility = ActivatedAbility(
            id = AbilityId("ability_700"),
            cost = Costs.Free,
            effect = Effects.DrawCards(1)
        )
        val secondAbility = ActivatedAbility(
            id = AbilityId("ability_701"),
            cost = Costs.Free,
            effect = Effects.GainLife(3)
        )

        fun stateWithEmblemAbility(ability: ActivatedAbility): GameState {
            val emblem = checkNotNull(env.state.getEntity(emblemId)).with(
                EmblemActivatedAbilityComponent(GroupFilter.source(), listOf(ability))
            )
            return env.state.copy(entities = env.state.entities + (emblemId to emblem))
        }

        observationForAbility(stateWithEmblemAbility(firstAbility), actor, sourceId, firstAbility.id)
            .stateDigest shouldNotBe
            observationForAbility(stateWithEmblemAbility(secondAbility), actor, sourceId, secondAbility.id)
                .stateDigest
    }

    test("distinct intrinsic mana abilities remain resolved and digest-distinct") {
        val env = environment()
        val actor = env.playerIds[0]
        val dualLand = registry().requireCard("Mountain").copy(
            name = "Gym Dual Basic Land",
            typeLine = TypeLine.parse("Basic Land — Mountain Forest")
        )
        val cardRegistry = registry().also { it.register(dualLand) }
        val (stateWithLand, sourceId) = stateWithAbilityCard(env.state, actor, dualLand)
        val state = moveCardToBattlefield(stateWithLand, actor, sourceId)

        val redObservation = observationForAbility(
            state,
            actor,
            sourceId,
            AbilityId.intrinsicMana('R'),
            cardRegistry
        )
        val greenObservation = observationForAbility(
            state,
            actor,
            sourceId,
            AbilityId.intrinsicMana('G'),
            cardRegistry
        )
        val red = redObservation.legalActions.single()
        val green = greenObservation.legalActions.single()
        val redKey = checkNotNull(red.actionSemantics)["abilityKey"]!!.jsonObject
        val greenKey = checkNotNull(green.actionSemantics)["abilityKey"]!!.jsonObject

        redKey["origin"]!!.jsonPrimitive.content shouldBe "intrinsic"
        greenKey["origin"]!!.jsonPrimitive.content shouldBe "intrinsic"
        redKey["ability"]!!.jsonObject["effect"]!!.jsonObject["color"]!!.jsonPrimitive.content shouldBe "RED"
        greenKey["ability"]!!.jsonObject["effect"]!!.jsonObject["color"]!!.jsonPrimitive.content shouldBe "GREEN"
        redKey["unresolved"] shouldBe null
        greenKey["unresolved"] shouldBe null
        redObservation.stateDigest shouldNotBe greenObservation.stateDigest
    }

    test("class level-up ability is resolved with its next-level semantics") {
        val env = environment()
        val actor = env.playerIds[0]
        val cardRegistry = registry().also { it.register(BuildersTalent) }
        val (stateWithClass, sourceId) = stateWithAbilityCard(env.state, actor, BuildersTalent)
        val leveledEntity = checkNotNull(stateWithClass.getEntity(sourceId))
            .with(ClassLevelComponent(currentLevel = 1))
        val state = moveCardToBattlefield(
            stateWithClass.copy(entities = stateWithClass.entities + (sourceId to leveledEntity)),
            actor,
            sourceId
        )

        val action = observationForAbility(
            state,
            actor,
            sourceId,
            AbilityId.classLevelUp(2),
            cardRegistry
        ).legalActions.single()
        val abilityKey = checkNotNull(action.actionSemantics)["abilityKey"]!!.jsonObject
        val ability = abilityKey["ability"]!!.jsonObject
        val cardDefinitionId = checkNotNull(state.getEntity(sourceId)).get<CardComponent>()!!.cardDefinitionId

        abilityKey["origin"]!!.jsonPrimitive.content shouldBe "classLevelUp"
        abilityKey["ordinal"]!!.jsonPrimitive.content shouldBe "2"
        abilityKey["cardDefinitionId"]!!.jsonPrimitive.content shouldBe cardDefinitionId
        ability["cost"]!!.jsonObject["atom"]!!.jsonObject["cost"]!!.jsonPrimitive.content shouldBe "{W}"
        ability["effect"]!!.jsonObject["targetLevel"]!!.jsonPrimitive.content shouldBe "2"
        abilityKey["unresolved"] shouldBe null
    }

    test("different structured cast choices change the semantic digest") {
        val env = environment()
        val actor = env.playerIds[0]
        val sourceId = env.state.getHand(actor).first()

        fun observationFor(castFaceDown: Boolean): TrainingObservation {
            val action = LegalAction(
                action = CastSpell(
                    playerId = actor,
                    cardId = sourceId,
                    castFaceDown = castFaceDown
                ),
                actionType = "CAST_SPELL",
                description = "Cast card"
            )
            return ObservationBuilder(cardRegistry = registry())
                .build(env.state, actor, listOf(action)).observation as TrainingObservation
        }

        observationFor(false).stateDigest shouldNotBe observationFor(true).stateDigest
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

    test("delayed-trigger candidate metadata is not exposed to a non-owner perspective") {
        val env = environment()
        val owner = env.playerIds[1]
        val other = env.playerIds[0]
        val state = env.state.copy(
            pendingDecision = ChooseOptionDecision(
                id = "delayed-occurrence-private",
                playerId = owner,
                prompt = "Choose a simultaneous occurrence",
                context = DecisionContext(phase = DecisionPhase.TRIGGER),
                options = listOf("Trigger for player ${owner.value}", "Trigger for player ${other.value}"),
                optionMetadata = listOf(
                    OptionMetadata(triggeringPlayerId = owner),
                    OptionMetadata(triggeringPlayerId = other)
                )
            )
        )

        val hidden = result(state, other)
        hidden.observation.pendingDecision!!.kind shouldBe PendingDecisionKind.GENERIC
        hidden.observation.pendingDecision!!.prompt shouldBe ""
        hidden.observation.legalActions.shouldBeEmpty()
        hidden.registry shouldBe ActionRegistry.EMPTY
    }

    test("hidden-zone target references fail closed without authoritative addressability") {
        val base = environment()
        val viewer = base.playerIds[0]
        val opponent = base.playerIds[1]
        val hiddenHandId = base.state.getHand(opponent).first()
        val hiddenLibraryId = base.state.getLibrary(opponent).first()
        val faceDownExileId = base.state.getHand(opponent)[1]
        val handKey = ZoneKey(opponent, Zone.HAND)
        val exileKey = ZoneKey(opponent, Zone.EXILE)
        val faceDownExileState = base.state.copy(
            entities = base.state.entities + (
                faceDownExileId to checkNotNull(base.state.getEntity(faceDownExileId))
                    .with(FaceDownComponent)
                ),
            zones = base.state.zones +
                (handKey to base.state.getHand(opponent).filterNot { it == faceDownExileId }) +
                (exileKey to base.state.getExile(opponent) + faceDownExileId),
        )

        listOf(
            base.state to hiddenHandId,
            base.state to hiddenLibraryId,
            faceDownExileState to faceDownExileId,
            base.state to EntityId("missing-target-without-visibility-metadata"),
        ).forEach { (state, targetId) ->
            val result = targetedResult(state, viewer, targetId)
            result.diagnostics.map { it.code } shouldBe
                listOf(DiagnosticCode.ACTION_TARGET_DOMAIN_UNSUPPORTED)
            result.observation.legalActions.shouldBeEmpty()
        }
    }

    test("ordinary hidden library target reference remains withheld") {
        val base = environment()
        val viewer = base.playerIds[0]
        val hiddenLibraryId = base.state.getLibrary(base.playerIds[1]).first()

        val result = targetedResult(base.state, viewer, hiddenLibraryId)

        result.diagnostics.map { it.code } shouldBe
            listOf(DiagnosticCode.ACTION_TARGET_DOMAIN_UNSUPPORTED)
        result.observation.legalActions.shouldBeEmpty()
    }

    test("visibility-authorized top library target reference is published") {
        val base = environment()
        val owner = base.playerIds[1]
        val viewer = base.playerIds[0]
        val topLibraryId = base.state.getLibrary(owner).first()
        val state = grantStaticAbilityToBattlefieldCard(
            base.state,
            owner,
            RevealTopOfLibrary
        )

        val result = targetedResult(state, viewer, topLibraryId)

        result.diagnostics.shouldBeEmpty()
        val targetDomain = (result.observation as TrainingObservation)
            .legalActions.single()
            .targetDomain
            .shouldNotBeNull()
        targetDomain.requirements.single().candidates shouldBe listOf(topLibraryId)
    }

    test("an explicitly revealed hidden-zone object may be addressed without identity in the domain") {
        val base = environment()
        val viewer = base.playerIds[0]
        val opponent = base.playerIds[1]
        val revealedId = base.state.getHand(opponent).first()
        val revealedState = base.state.updateEntity(revealedId) {
            it.with(RevealedToComponent.to(viewer))
        }

        val result = targetedResult(revealedState, viewer, revealedId)
        result.diagnostics.shouldBeEmpty()
        val targetDomain = (result.observation as TrainingObservation)
            .legalActions.single()
            .targetDomain
            .shouldNotBeNull()
        targetDomain.requirements.single().candidates shouldBe listOf(revealedId)
        (result.observation as TrainingObservation).zones.single {
            it.ownerId == opponent && it.zoneType == Zone.HAND
        }.cards.shouldBeEmpty()
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
                    hiddenId to checkNotNull(state.getEntity(hiddenId)).with(
                        SpellOnStackComponent(casterId = opponent, castFaceDown = true)
                    )
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
