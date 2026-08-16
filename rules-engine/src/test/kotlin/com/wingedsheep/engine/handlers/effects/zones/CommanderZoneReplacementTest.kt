package com.wingedsheep.engine.handlers.effects.zones

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.ContinuationFrame
import com.wingedsheep.engine.core.EffectContinuation
import com.wingedsheep.engine.core.ChooseOptionDecision
import com.wingedsheep.engine.core.LibraryReorderedEvent
import com.wingedsheep.engine.core.LibraryShuffledEvent
import com.wingedsheep.engine.core.OrderedResponse
import com.wingedsheep.engine.core.OptionChosenResponse
import com.wingedsheep.engine.core.OptionalReplacementContinuation
import com.wingedsheep.engine.core.ReorderLibraryDecision
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.replacement.PendingGameEvent
import com.wingedsheep.engine.replacement.ProcessorResult
import com.wingedsheep.engine.replacement.GatheredReplacement
import com.wingedsheep.engine.replacement.ReplacementEffectProcessor
import com.wingedsheep.engine.replacement.ReplacementEffectIdentity
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.SelfZoneRedirectComponent
import com.wingedsheep.engine.state.components.battlefield.ReplacementEffectSourceComponent
import com.wingedsheep.engine.state.components.battlefield.ExileOnLeaveBattlefieldComponent
import com.wingedsheep.engine.state.components.stack.ChosenTarget
import com.wingedsheep.engine.state.components.stack.SpellOnStackComponent
import com.wingedsheep.sdk.core.CardType
import com.wingedsheep.sdk.core.Format
import com.wingedsheep.sdk.core.ManaCost
import com.wingedsheep.sdk.core.TypeLine
import com.wingedsheep.sdk.core.Zone
import com.wingedsheep.sdk.model.EntityId
import com.wingedsheep.sdk.scripting.EventPattern
import com.wingedsheep.sdk.scripting.GameObjectFilter
import com.wingedsheep.sdk.scripting.CommanderZoneReplacement
import com.wingedsheep.sdk.scripting.RedirectZoneChange
import com.wingedsheep.sdk.scripting.RedirectZoneChangeWithEffect
import com.wingedsheep.sdk.scripting.effects.GainLifeEffect
import com.wingedsheep.sdk.scripting.effects.CardOrder
import com.wingedsheep.sdk.scripting.effects.MoveToZoneEffect
import com.wingedsheep.sdk.scripting.effects.MoveCollectionEffect
import com.wingedsheep.sdk.scripting.effects.CardDestination
import com.wingedsheep.sdk.scripting.effects.DiscoverEffect
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Red conformance tests for CR 903.9b. The pending zone-change decision must
 * happen before the physical hand/library transition, including COMMAND -> HAND.
 */
class CommanderZoneReplacementTest : FunSpec({

    val playerId = EntityId.generate()
    val opponentId = EntityId.generate()
    val commanderId = EntityId.generate()
    val libraryCardId = EntityId.generate()
    val secondLibraryCardId = EntityId.generate()
    val normalAId = EntityId.generate()
    val normalBId = EntityId.generate()
    val executor = MoveToZoneEffectExecutor(CardRegistry())

    fun stateWithCommanderIn(
        zone: Zone,
        format: Format = Format.Commander(),
        controllerId: EntityId = playerId,
    ): GameState {
        val commander = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "Test Commander",
                name = "Test Commander",
                manaCost = ManaCost.parse("{2}{R}"),
                typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                oracleText = "",
                ownerId = playerId,
            ),
            OwnerComponent(playerId),
            CommanderComponent(ownerId = playerId),
        ).let { container ->
            if (controllerId == playerId) container
            else container.with(ControllerComponent(controllerId))
        }
        val players = if (controllerId == playerId) listOf(playerId) else listOf(playerId, opponentId)
        return GameState(format = format)
            .withEntity(playerId, ComponentContainer.EMPTY)
            .withEntity(opponentId, ComponentContainer.EMPTY)
            .withEntity(commanderId, commander)
            .addToZone(ZoneKey(playerId, zone), commanderId)
            .copy(turnOrder = players)
    }

    fun move(state: GameState, destination: Zone, placement: com.wingedsheep.sdk.scripting.effects.ZonePlacement = com.wingedsheep.sdk.scripting.effects.ZonePlacement.Default) = executor.execute(
        state = state,
        effect = MoveToZoneEffect(
            target = EffectTarget.ContextTarget(0),
            destination = destination,
            placement = placement,
        ),
        context = EffectContext(
            sourceId = null,
            controllerId = playerId,
            targets = listOf(ChosenTarget.Permanent(commanderId)),
        ),
    )

    fun moveWithServices(
        services: EngineServices,
        state: GameState,
        destination: Zone,
        placement: com.wingedsheep.sdk.scripting.effects.ZonePlacement = com.wingedsheep.sdk.scripting.effects.ZonePlacement.Default,
    ) = services.effectExecutorRegistry.execute(
        state,
        MoveToZoneEffect(
            EffectTarget.ContextTarget(0),
            destination,
            placement = placement,
        ),
        EffectContext(
            sourceId = null,
            controllerId = playerId,
            targets = listOf(ChosenTarget.Permanent(commanderId)),
        ),
    )

    fun moveThroughReplacementPipeline(
        state: GameState,
        destination: Zone,
    ): EffectResult = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
        .moveToZoneWithReplacements(
            state = state,
            entityId = commanderId,
            destinationZone = destination,
            fromZoneKey = ZoneKey(playerId, Zone.BATTLEFIELD),
            context = EffectContext(sourceId = null, controllerId = playerId),
        )

    fun resumeYesNo(services: EngineServices, initial: EffectResult, choice: Boolean): ExecutionResult {
        val decision = initial.pendingDecision as YesNoDecision
        return services.continuationHandler.resume(
            initial.state.clearPendingDecision(),
            YesNoResponse(decision.id, choice),
        )
    }

    fun resumeExecutionYesNo(services: EngineServices, initial: ExecutionResult, choice: Boolean): ExecutionResult {
        val decision = initial.pendingDecision as YesNoDecision
        return services.continuationHandler.resume(
            initial.state.clearPendingDecision(),
            YesNoResponse(decision.id, choice),
        )
    }

    fun addLibraryCard(state: GameState, cardId: EntityId, name: String): GameState {
        val card = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = name,
                name = name,
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                ownerId = playerId,
            ),
            OwnerComponent(playerId),
        )
        return state
            .withEntity(cardId, card)
            .addToZone(ZoneKey(playerId, Zone.LIBRARY), cardId)
    }

    fun addLibrarySentinel(state: GameState): GameState =
        addLibraryCard(state, libraryCardId, "Library Sentinel")

    fun progenitusLikeRedirect() = RedirectZoneChange(
        newDestination = Zone.LIBRARY,
        appliesTo = EventPattern.ZoneChangeEvent(
            from = Zone.BATTLEFIELD,
            to = Zone.GRAVEYARD,
        ),
        selfOnly = true,
        shuffleIntoLibrary = true,
        reveal = true,
    )

    fun stateWithProgenitusLikeCommander(): GameState {
        val redirect = progenitusLikeRedirect()
        return addLibraryCard(
            addLibrarySentinel(
                stateWithCommanderIn(Zone.BATTLEFIELD).updateEntity(commanderId) {
                    it.with(ControllerComponent(playerId))
                        .with(SelfZoneRedirectComponent(listOf(redirect)))
                        .with(ReplacementEffectSourceComponent(listOf(redirect)))
                }
            ),
            secondLibraryCardId,
            "Second Library Card",
        )
    }

    test("RC-01: a Progenitus-shaped Commander redirect to COMMAND still shuffles the library") {
        val services = EngineServices(CardRegistry())
        val initial = moveThroughReplacementPipeline(stateWithProgenitusLikeCommander(), Zone.GRAVEYARD)

        initial.isPaused shouldBe true
        val resumed = resumeYesNo(services, initial, choice = true)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)).contains(commanderId) shouldBe false
        resumed.events.filterIsInstance<LibraryShuffledEvent>().count { it.playerId == playerId } shouldBe 1
        resumed.state.rng shouldNotBe initial.state.rng
        resumed.events.filterIsInstance<ZoneChangeEvent>().count { it.entityId == commanderId } shouldBe 1
    }

    test("RC-02: a Progenitus-shaped Commander redirect declined to library shuffles normally") {
        val services = EngineServices(CardRegistry())
        val initial = moveThroughReplacementPipeline(stateWithProgenitusLikeCommander(), Zone.GRAVEYARD)

        val resumed = resumeYesNo(services, initial, choice = false)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe emptyList()
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)).contains(commanderId) shouldBe true
        resumed.events.filterIsInstance<LibraryShuffledEvent>().count { it.playerId == playerId } shouldBe 1
        resumed.state.rng shouldNotBe initial.state.rng
        resumed.events.filterIsInstance<ZoneChangeEvent>().count { it.entityId == commanderId } shouldBe 1
    }

    test("RC-03: an ordinary Commander library redirect does not create a phantom shuffle") {
        val services = EngineServices(CardRegistry())
        val initial = moveWithServices(
            services,
            addLibrarySentinel(stateWithCommanderIn(Zone.BATTLEFIELD)),
            Zone.LIBRARY,
            com.wingedsheep.sdk.scripting.effects.ZonePlacement.Bottom,
        )

        val resumed = resumeYesNo(services, initial, choice = true)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        resumed.events.filterIsInstance<LibraryShuffledEvent>() shouldBe emptyList()
        resumed.state.rng shouldBe initial.state.rng
    }

    test("RC-04: pending zone changes retain a generic shuffle obligation after a later destination change") {
        val services = EngineServices(CardRegistry())
        val state = stateWithProgenitusLikeCommander()
        val pending = PendingGameEvent.ZoneChangePending(
            entityId = commanderId,
            ownerId = playerId,
            fromZoneKey = ZoneKey(playerId, Zone.BATTLEFIELD),
            destinationZone = Zone.GRAVEYARD,
        )
        val first = pending.applyReplacement(progenitusLikeRedirect(), state)
            .shouldBeInstanceOf<com.wingedsheep.engine.replacement.ReplacementOutcome.Modified>()
            .modifiedEvent.shouldBeInstanceOf<PendingGameEvent.ZoneChangePending>()
        val finalPending = first.applyReplacement(CommanderZoneReplacement, state)
            .shouldBeInstanceOf<com.wingedsheep.engine.replacement.ReplacementOutcome.Modified>()
            .modifiedEvent.shouldBeInstanceOf<PendingGameEvent.ZoneChangePending>()

        finalPending.destinationZone shouldBe Zone.COMMAND
        finalPending.redirectResult?.shuffleIntoLibrary shouldBe true
        finalPending.residualObligations.shuffleOwnerLibrary shouldBe true

        val transition = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
            .performPendingZoneChange(state, finalPending)
        transition.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        transition.state.getZone(ZoneKey(playerId, Zone.LIBRARY)).contains(commanderId) shouldBe false
        transition.events.filterIsInstance<LibraryShuffledEvent>().count { it.playerId == playerId } shouldBe 1
        transition.state.rng shouldNotBe state.rng
    }

    fun addNormalCard(state: GameState, cardId: EntityId, name: String): GameState {
        val card = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = name,
                name = name,
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                ownerId = playerId,
            ),
            OwnerComponent(playerId),
        )
        return state
            .withEntity(cardId, card)
            .addToZone(ZoneKey(playerId, Zone.BATTLEFIELD), cardId)
    }

    fun stateWithCommanderOnControllerBattlefield(): GameState =
        stateWithCommanderIn(Zone.BATTLEFIELD, controllerId = opponentId).copy(
            zones = mapOf(
                ZoneKey(playerId, Zone.BATTLEFIELD) to emptyList(),
                ZoneKey(opponentId, Zone.BATTLEFIELD) to listOf(commanderId),
                ZoneKey(playerId, Zone.LIBRARY) to emptyList(),
            ),
        )

    fun moveControlledCommanderToLibrary(
        services: EngineServices,
        state: GameState,
    ): EffectResult {
        val effect = MoveCollectionEffect(
            from = "cards",
            destination = CardDestination.ToZone(
                zone = Zone.LIBRARY,
                placement = com.wingedsheep.sdk.scripting.effects.ZonePlacement.Bottom,
            ),
        )
        return services.effectExecutorRegistry.execute(
            state,
            effect,
            EffectContext(
                sourceId = null,
                controllerId = opponentId,
                pipeline = PipelineState.EMPTY.copy(
                    storedCollections = mapOf("cards" to listOf(commanderId)),
                ),
            ),
        )
    }

    fun orderedMovePrompt(
        services: EngineServices,
        state: GameState,
        cards: List<EntityId>,
        placement: com.wingedsheep.sdk.scripting.effects.ZonePlacement,
        storeMovedAs: String? = null,
    ): EffectResult {
        val effect = MoveCollectionEffect(
            from = "cards",
            destination = CardDestination.ToZone(
                zone = Zone.LIBRARY,
                placement = placement,
            ),
            order = CardOrder.ControllerChooses,
            storeMovedAs = storeMovedAs,
        )
        val context = EffectContext(
            sourceId = null,
            controllerId = playerId,
            pipeline = PipelineState.EMPTY.copy(
                storedCollections = mapOf("cards" to cards),
            ),
        )
        return services.effectExecutorRegistry.execute(state, effect, context)
    }

    fun resumeOrderedMove(
        services: EngineServices,
        orderPrompt: EffectResult,
        orderedCards: List<EntityId>,
    ): ExecutionResult {
        val decision = orderPrompt.pendingDecision.shouldBeInstanceOf<ReorderLibraryDecision>()
        return services.continuationHandler.resume(
            orderPrompt.state.clearPendingDecision(),
            OrderedResponse(decision.id, orderedCards),
        )
    }

    test("MC-ORDER-01: top order keeps Commander intended first when owner says NO") {
        val services = EngineServices(CardRegistry())
        val state = addLibrarySentinel(
            addNormalCard(stateWithCommanderIn(Zone.BATTLEFIELD), normalAId, "Normal Card")
        )
        val orderPrompt = orderedMovePrompt(
            services,
            state,
            listOf(commanderId, normalAId),
            com.wingedsheep.sdk.scripting.effects.ZonePlacement.Top,
        )
        val commanderPrompt = resumeOrderedMove(services, orderPrompt, listOf(commanderId, normalAId))
        val resumed = resumeExecutionYesNo(services, commanderPrompt, choice = false)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe
            listOf(commanderId, normalAId, libraryCardId)
    }

    test("MC-ORDER-02: top order keeps ordinary cards relative when Commander chooses COMMAND") {
        val services = EngineServices(CardRegistry())
        val state = addLibrarySentinel(
            addNormalCard(
                addNormalCard(stateWithCommanderIn(Zone.BATTLEFIELD), normalAId, "Normal A"),
                normalBId,
                "Normal B",
            )
        )
        val orderPrompt = orderedMovePrompt(
            services,
            state,
            listOf(commanderId, normalAId, normalBId),
            com.wingedsheep.sdk.scripting.effects.ZonePlacement.Top,
        )
        val commanderPrompt = resumeOrderedMove(
            services,
            orderPrompt,
            listOf(commanderId, normalAId, normalBId),
        )
        val resumed = resumeExecutionYesNo(services, commanderPrompt, choice = true)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe
            listOf(normalAId, normalBId, libraryCardId)
    }

    test("MC-ORDER-03: bottom order keeps Commander intended last when owner says NO") {
        val services = EngineServices(CardRegistry())
        val state = addLibrarySentinel(
            addNormalCard(stateWithCommanderIn(Zone.BATTLEFIELD), normalAId, "Normal Card")
        )
        val orderPrompt = orderedMovePrompt(
            services,
            state,
            listOf(normalAId, commanderId),
            com.wingedsheep.sdk.scripting.effects.ZonePlacement.Bottom,
        )
        val commanderPrompt = resumeOrderedMove(services, orderPrompt, listOf(normalAId, commanderId))
        val resumed = resumeExecutionYesNo(services, commanderPrompt, choice = false)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe
            listOf(libraryCardId, normalAId, commanderId)
    }

    test("MC-ORDER-04: ordinary cards around Commander retain the selected sequence") {
        val services = EngineServices(CardRegistry())
        val state = addLibrarySentinel(
            addNormalCard(
                addNormalCard(stateWithCommanderIn(Zone.BATTLEFIELD), normalAId, "Normal A"),
                normalBId,
                "Normal B",
            )
        )
        val orderPrompt = orderedMovePrompt(
            services,
            state,
            listOf(normalAId, commanderId, normalBId),
            com.wingedsheep.sdk.scripting.effects.ZonePlacement.Top,
        )
        val commanderPrompt = resumeOrderedMove(
            services,
            orderPrompt,
            listOf(normalAId, commanderId, normalBId),
        )
        val resumed = resumeExecutionYesNo(services, commanderPrompt, choice = false)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe
            listOf(normalAId, commanderId, normalBId, libraryCardId)
        resumed.events.filterIsInstance<LibraryReorderedEvent>().size shouldBe 1
        resumed.events.last().shouldBeInstanceOf<LibraryReorderedEvent>()
    }

    test("MoveCollection storeMovedAs survives a Commander pause into the next pipeline step") {
        val services = EngineServices(CardRegistry())
        val state = addLibrarySentinel(
            addNormalCard(stateWithCommanderIn(Zone.BATTLEFIELD), normalAId, "Normal Card")
        )
        val context = EffectContext(
            sourceId = null,
            controllerId = playerId,
            pipeline = PipelineState.EMPTY.copy(
                storedCollections = mapOf("cards" to listOf(commanderId, normalAId)),
            ),
        )
        val trailingMove = MoveCollectionEffect(
            from = "moved",
            destination = CardDestination.ToZone(Zone.GRAVEYARD),
        )
        val withTrailingEffect = state.pushContinuation(
            EffectContinuation(
                decisionId = "pending",
                remainingEffects = listOf(trailingMove),
                effectContext = context,
            )
        )
        val orderPrompt = services.effectExecutorRegistry.execute(
            withTrailingEffect,
            MoveCollectionEffect(
                from = "cards",
                destination = CardDestination.ToZone(
                    zone = Zone.LIBRARY,
                    placement = com.wingedsheep.sdk.scripting.effects.ZonePlacement.Top,
                ),
                order = CardOrder.ControllerChooses,
                storeMovedAs = "moved",
            ),
            context,
        )

        val commanderPrompt = resumeOrderedMove(services, orderPrompt, listOf(commanderId, normalAId))
        val resumed = resumeExecutionYesNo(services, commanderPrompt, choice = false)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.GRAVEYARD)).toSet() shouldBe
            setOf(commanderId, normalAId)
    }

    test("ordered MoveCollection Commander pause is serializable and fork-safe") {
        val services = EngineServices(CardRegistry())
        val state = addLibrarySentinel(
            addNormalCard(stateWithCommanderIn(Zone.BATTLEFIELD), normalAId, "Normal Card")
        )
        val orderPrompt = orderedMovePrompt(
            services,
            state,
            listOf(commanderId, normalAId),
            com.wingedsheep.sdk.scripting.effects.ZonePlacement.Top,
        )
        val commanderPrompt = resumeOrderedMove(services, orderPrompt, listOf(commanderId, normalAId))
        val frame = commanderPrompt.state.continuationStack.last()
        val json = Json { serializersModule = engineSerializersModule }

        val encoded = json.encodeToString(ContinuationFrame.serializer(), frame)
        val decoded = json.decodeFromString<ContinuationFrame>(encoded)
        decoded shouldBe frame

        val forkedState = commanderPrompt.state.copy(
            continuationStack = commanderPrompt.state.continuationStack.dropLast(1) + decoded,
        )
        val resumed = resumeExecutionYesNo(
            services,
            commanderPrompt.copy(state = forkedState),
            choice = false,
        )

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe
            listOf(commanderId, normalAId, libraryCardId)
    }

    test("commander from battlefield to hand pauses before moving") {
        val result = move(stateWithCommanderIn(Zone.BATTLEFIELD), Zone.HAND)

        result.isPaused shouldBe true
        result.state.getZone(ZoneKey(playerId, Zone.BATTLEFIELD)) shouldBe listOf(commanderId)
        result.state.getZone(ZoneKey(playerId, Zone.HAND)) shouldBe emptyList()
    }

    test("CZ-13: commander from command zone to hand also pauses") {
        val result = move(stateWithCommanderIn(Zone.COMMAND), Zone.HAND)

        result.isPaused shouldBe true
        result.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        result.state.getZone(ZoneKey(playerId, Zone.HAND)) shouldBe emptyList()
    }

    test("YES resolves through the continuation and leaves the commander in the command zone") {
        val services = EngineServices(CardRegistry())
        val initial = moveWithServices(services, stateWithCommanderIn(Zone.BATTLEFIELD), Zone.HAND)
        val resumed = resumeYesNo(services, initial, choice = true)

        resumed.error shouldBe null
        resumed.pendingDecision shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        resumed.state.getZone(ZoneKey(playerId, Zone.HAND)) shouldBe emptyList()
        resumed.events.filterIsInstance<ZoneChangeEvent>().map { it.toZone } shouldBe listOf(Zone.COMMAND)
    }

    test("NO resolves through the continuation and moves the commander to hand") {
        val services = EngineServices(CardRegistry())
        val initial = moveWithServices(services, stateWithCommanderIn(Zone.COMMAND), Zone.HAND)
        val resumed = resumeYesNo(services, initial, choice = false)

        resumed.error shouldBe null
        resumed.pendingDecision shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe emptyList()
        resumed.state.getZone(ZoneKey(playerId, Zone.HAND)) shouldBe listOf(commanderId)
        resumed.events.filterIsInstance<ZoneChangeEvent>().map { it.toZone } shouldBe listOf(Zone.HAND)
        com.wingedsheep.engine.mechanics.sba.permanent.CommanderZoneChoiceCheck(
            com.wingedsheep.engine.handlers.DecisionHandler()
        ).check(resumed.state).isPaused shouldBe false
    }

    test("library YES replaces before the commander enters the library") {
        val services = EngineServices(CardRegistry())
        val initial = moveWithServices(
            services,
            addLibrarySentinel(stateWithCommanderIn(Zone.BATTLEFIELD)),
            Zone.LIBRARY,
            com.wingedsheep.sdk.scripting.effects.ZonePlacement.Bottom,
        )
        val resumed = resumeYesNo(services, initial, choice = true)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe listOf(libraryCardId)
        resumed.events.filterIsInstance<ZoneChangeEvent>().map { it.toZone } shouldBe listOf(Zone.COMMAND)
    }

    test("library YES also applies when an intrinsic leave-battlefield replacement is present") {
        val services = EngineServices(CardRegistry())
        val state = stateWithCommanderIn(Zone.BATTLEFIELD).updateEntity(commanderId) {
            it.with(ExileOnLeaveBattlefieldComponent)
        }
        val initial = moveWithServices(
            services,
            addLibrarySentinel(state),
            Zone.LIBRARY,
            com.wingedsheep.sdk.scripting.effects.ZonePlacement.Bottom,
        )

        initial.isPaused shouldBe true
        val resumed = resumeYesNo(services, initial, choice = true)

        resumed.error shouldBe null
        resumed.pendingDecision shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        resumed.state.getZone(ZoneKey(playerId, Zone.EXILE)) shouldBe emptyList()
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe listOf(libraryCardId)
    }

    test("COMMAND to library also exposes the 903.9b choice") {
        val services = EngineServices(CardRegistry())
        val initial = moveWithServices(
            services,
            addLibrarySentinel(stateWithCommanderIn(Zone.COMMAND)),
            Zone.LIBRARY,
            com.wingedsheep.sdk.scripting.effects.ZonePlacement.Bottom,
        )

        initial.isPaused shouldBe true
        initial.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        val resumed = resumeYesNo(services, initial, choice = false)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe emptyList()
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe listOf(libraryCardId, commanderId)
    }

    test("a commander spell moving from the stack to hand also uses 903.9b") {
        val services = EngineServices(CardRegistry())
        val stackState = stateWithCommanderIn(Zone.COMMAND)
            .removeFromZone(ZoneKey(playerId, Zone.COMMAND), commanderId)
            .copy(stack = listOf(commanderId))
            .updateEntity(commanderId) { it.with(SpellOnStackComponent(playerId)) }
        val initial = moveWithServices(services, stackState, Zone.HAND)

        initial.isPaused shouldBe true
        initial.state.stack shouldBe listOf(commanderId)
        val resumed = resumeYesNo(services, initial, choice = true)

        resumed.error shouldBe null
        resumed.state.stack shouldBe emptyList()
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        resumed.state.getEntity(commanderId)?.has<SpellOnStackComponent>() shouldBe false
    }

    test("MoveCollection hand entry pauses before its Commander is physically moved") {
        val services = EngineServices(CardRegistry())
        val state = stateWithCommanderIn(Zone.BATTLEFIELD)
        val effect = MoveCollectionEffect(
            from = "cards",
            destination = CardDestination.ToZone(Zone.HAND),
        )
        val context = EffectContext(
            sourceId = null,
            controllerId = playerId,
            pipeline = PipelineState.EMPTY.copy(
                storedCollections = mapOf("cards" to listOf(commanderId)),
            ),
        )
        val initial = services.effectExecutorRegistry.execute(state, effect, context)

        initial.isPaused shouldBe true
        initial.state.getZone(ZoneKey(playerId, Zone.BATTLEFIELD)) shouldBe listOf(commanderId)
        val resumed = resumeYesNo(services, initial, choice = true)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        resumed.state.getZone(ZoneKey(playerId, Zone.HAND)) shouldBe emptyList()
    }

    test("ControllerChooses library entry runs Commander replacement before physical movement") {
        val services = EngineServices(CardRegistry())
        val effect = MoveCollectionEffect(
            from = "cards",
            destination = CardDestination.ToZone(Zone.LIBRARY),
            order = CardOrder.ControllerChooses,
        )
        val context = EffectContext(
            sourceId = null,
            controllerId = playerId,
            pipeline = PipelineState.EMPTY.copy(
                storedCollections = mapOf("cards" to listOf(commanderId)),
            ),
        )
        val orderPrompt = services.effectExecutorRegistry.execute(
            stateWithCommanderIn(Zone.BATTLEFIELD), effect, context
        )
        orderPrompt.pendingDecision.shouldBeInstanceOf<ReorderLibraryDecision>()

        val orderDecision = orderPrompt.pendingDecision as ReorderLibraryDecision
        val replacementPrompt = services.continuationHandler.resume(
            orderPrompt.state.clearPendingDecision(),
            OrderedResponse(orderDecision.id, listOf(commanderId)),
        )

        replacementPrompt.isPaused shouldBe true
        replacementPrompt.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        replacementPrompt.state.getZone(ZoneKey(playerId, Zone.BATTLEFIELD)) shouldBe listOf(commanderId)
        replacementPrompt.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe emptyList()
    }

    test("RedirectZoneChangeWithEffect riders use the replacement controller") {
        val replacementSourceId = EntityId.generate()
        val replacementSource = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "Life Redirect",
                name = "Life Redirect",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT)),
                ownerId = playerId,
            ),
            OwnerComponent(playerId),
            ControllerComponent(opponentId),
        )
        val state = stateWithCommanderIn(Zone.BATTLEFIELD, controllerId = opponentId)
            .withEntity(replacementSourceId, replacementSource)
        val pending = PendingGameEvent.ZoneChangePending(
            entityId = commanderId,
            ownerId = playerId,
            fromZoneKey = ZoneKey(playerId, Zone.BATTLEFIELD),
            destinationZone = Zone.GRAVEYARD,
        )
        val effect = RedirectZoneChangeWithEffect(
            newDestination = Zone.EXILE,
            additionalEffect = GainLifeEffect(2),
            appliesTo = EventPattern.ZoneChangeEvent(
                filter = GameObjectFilter.Any,
                from = Zone.BATTLEFIELD,
                to = Zone.GRAVEYARD,
            ),
        )
        val gathered = GatheredReplacement(
            identity = ReplacementEffectIdentity.BattlefieldIdentity(replacementSourceId, 0),
            effect = effect,
            sourceControllerId = opponentId,
            description = effect.description,
        )

        val modified = pending.applyReplacement(gathered, state)
            .shouldBeInstanceOf<com.wingedsheep.engine.replacement.ReplacementOutcome.Modified>()
            .modifiedEvent.shouldBeInstanceOf<PendingGameEvent.ZoneChangePending>()

        modified.redirectResult?.effectControllerId shouldBe opponentId
    }

    test("Discover bottoming its non-hit Commander uses the 903.9b pipeline") {
        val services = EngineServices(CardRegistry())
        val initial = services.effectExecutorRegistry.execute(
            stateWithCommanderIn(Zone.LIBRARY),
            DiscoverEffect(DynamicAmount.Fixed(0)),
            EffectContext(sourceId = null, controllerId = playerId),
        )

        initial.isPaused shouldBe true
        initial.state.getZone(ZoneKey(playerId, Zone.EXILE)) shouldBe listOf(commanderId)
        initial.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe emptyList()

        val resumed = resumeYesNo(services, initial, choice = true)

        resumed.error shouldBe null
        resumed.pendingDecision shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe emptyList()
    }

    test("Discover bottoming an exiled Commander before its hit remains pause-safe") {
        val services = EngineServices(CardRegistry())
        val initial = services.effectExecutorRegistry.execute(
            addLibrarySentinel(stateWithCommanderIn(Zone.LIBRARY)),
            DiscoverEffect(DynamicAmount.Fixed(0)),
            EffectContext(sourceId = null, controllerId = playerId),
        )

        initial.isPaused shouldBe true
        val afterMayCast = resumeYesNo(services, initial, choice = false)
        afterMayCast.isPaused shouldBe true
        val resumed = resumeExecutionYesNo(services, afterMayCast, choice = true)

        resumed.error shouldBe null
        resumed.pendingDecision shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        resumed.state.getZone(ZoneKey(playerId, Zone.HAND)) shouldBe listOf(libraryCardId)
    }

    test("library NO preserves the requested placement and does not invoke SBA") {
        val services = EngineServices(CardRegistry())
        val initial = moveWithServices(
            services,
            addLibrarySentinel(stateWithCommanderIn(Zone.BATTLEFIELD)),
            Zone.LIBRARY,
            com.wingedsheep.sdk.scripting.effects.ZonePlacement.Bottom,
        )
        val resumed = resumeYesNo(services, initial, choice = false)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe emptyList()
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe listOf(libraryCardId, commanderId)
        resumed.events.filterIsInstance<ZoneChangeEvent>().map { it.toZone } shouldBe listOf(Zone.LIBRARY)
        com.wingedsheep.engine.mechanics.sba.permanent.CommanderZoneChoiceCheck(
            com.wingedsheep.engine.handlers.DecisionHandler()
        ).check(resumed.state).isPaused shouldBe false
    }

    test("903.9b choice belongs to the commander owner, not its battlefield controller") {
        val services = EngineServices(CardRegistry())
        val initial = moveWithServices(
            services,
            stateWithCommanderIn(Zone.BATTLEFIELD, controllerId = opponentId),
            Zone.HAND,
        )

        val decision = initial.pendingDecision.shouldBeInstanceOf<YesNoDecision>()
        decision.playerId shouldBe playerId
    }

    test("MC-CONTROL-01: a Commander stored on its controller's battlefield still pauses for its owner") {
        val services = EngineServices(CardRegistry())
        val initial = moveControlledCommanderToLibrary(
            services,
            stateWithCommanderOnControllerBattlefield(),
        )

        initial.isPaused shouldBe true
        initial.pendingDecision.shouldBeInstanceOf<YesNoDecision>().playerId shouldBe playerId
        initial.state.getZone(ZoneKey(opponentId, Zone.BATTLEFIELD)) shouldBe listOf(commanderId)
        initial.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe emptyList()

        val resumed = resumeYesNo(services, initial, choice = true)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe emptyList()
    }

    test("MC-CONTROL-02: declining the choice moves the controlled Commander to its owner's library") {
        val services = EngineServices(CardRegistry())
        val initial = moveControlledCommanderToLibrary(
            services,
            stateWithCommanderOnControllerBattlefield(),
        )

        initial.isPaused shouldBe true
        initial.pendingDecision.shouldBeInstanceOf<YesNoDecision>().playerId shouldBe playerId
        initial.state.getZone(ZoneKey(opponentId, Zone.BATTLEFIELD)) shouldBe listOf(commanderId)
        initial.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe emptyList()

        val resumed = resumeYesNo(services, initial, choice = false)

        resumed.error shouldBe null
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe emptyList()
        resumed.state.getZone(ZoneKey(playerId, Zone.LIBRARY)) shouldBe listOf(commanderId)
    }

    test("CR 616 ordering choice belongs to the current commander controller") {
        val replacementSourceId = EntityId.generate()
        val replacementSource = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "Controlled Redirect",
                name = "Controlled Redirect",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT)),
                ownerId = opponentId,
            ),
            OwnerComponent(opponentId),
            ControllerComponent(opponentId),
            ReplacementEffectSourceComponent(
                listOf(
                    RedirectZoneChange(
                        newDestination = Zone.EXILE,
                        appliesTo = EventPattern.ZoneChangeEvent(
                            filter = GameObjectFilter.Any,
                            from = Zone.BATTLEFIELD,
                            to = Zone.HAND,
                        ),
                    )
                )
            ),
        )
        val state = stateWithCommanderIn(Zone.BATTLEFIELD, controllerId = opponentId)
            .withEntity(replacementSourceId, replacementSource)
            .addToZone(ZoneKey(opponentId, Zone.BATTLEFIELD), replacementSourceId)
        val initial = moveWithServices(EngineServices(CardRegistry()), state, Zone.HAND)

        val decision = initial.pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()
        decision.playerId shouldBe opponentId
    }

    test("CR 616 ordering does not let the controller answer the owner's 903.9b choice") {
        val replacementSourceId = EntityId.generate()
        val replacementSource = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "Controlled Exile",
                name = "Controlled Exile",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT)),
                ownerId = opponentId,
            ),
            OwnerComponent(opponentId),
            ControllerComponent(opponentId),
            ReplacementEffectSourceComponent(
                listOf(
                    RedirectZoneChange(
                        newDestination = Zone.EXILE,
                        appliesTo = EventPattern.ZoneChangeEvent(
                            filter = GameObjectFilter.Any,
                            from = Zone.BATTLEFIELD,
                            to = Zone.HAND,
                        ),
                    )
                )
            ),
        )
        val state = stateWithCommanderOnControllerBattlefield()
            .withEntity(replacementSourceId, replacementSource)
            .addToZone(ZoneKey(opponentId, Zone.BATTLEFIELD), replacementSourceId)
        val services = EngineServices(CardRegistry())

        val initial = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
            .moveToZoneWithReplacements(
                state = state,
                entityId = commanderId,
                destinationZone = Zone.HAND,
                fromZoneKey = ZoneKey(opponentId, Zone.BATTLEFIELD),
                context = EffectContext(sourceId = null, controllerId = opponentId),
            )
        val ordering = initial.pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()
        ordering.playerId shouldBe opponentId
        val commanderOption = ordering.options.indexOfFirst { it.startsWith("Test Commander -") }
        commanderOption shouldNotBe -1

        val ownerPrompt = services.continuationHandler.resume(
            initial.state.clearPendingDecision(),
            OptionChosenResponse(ordering.id, commanderOption),
        )

        ownerPrompt.isPaused shouldBe true
        ownerPrompt.pendingDecision.shouldBeInstanceOf<YesNoDecision>().playerId shouldBe playerId
        ownerPrompt.state.getZone(ZoneKey(opponentId, Zone.BATTLEFIELD)) shouldBe
            listOf(commanderId, replacementSourceId)
        ownerPrompt.state.getZone(ZoneKey(playerId, Zone.HAND)) shouldBe emptyList()

        val declined = services.continuationHandler.resume(
            ownerPrompt.state.clearPendingDecision(),
            YesNoResponse(ownerPrompt.pendingDecision.id, choice = false),
        )
        declined.error shouldBe null
        declined.state.getZone(ZoneKey(playerId, Zone.EXILE)) shouldBe listOf(commanderId)
    }

    test("CTRL-DECLINE-01: CR 616 ordering has no controller-owned Commander decline option") {
        val replacementSourceId = EntityId.generate()
        val replacementSource = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "Controlled Exile",
                name = "Controlled Exile",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT)),
                ownerId = opponentId,
            ),
            OwnerComponent(opponentId),
            ControllerComponent(opponentId),
            ReplacementEffectSourceComponent(
                listOf(
                    RedirectZoneChange(
                        newDestination = Zone.EXILE,
                        appliesTo = EventPattern.ZoneChangeEvent(
                            filter = GameObjectFilter.Any,
                            from = Zone.BATTLEFIELD,
                            to = Zone.HAND,
                        ),
                    )
                )
            ),
        )
        val state = stateWithCommanderOnControllerBattlefield()
            .withEntity(replacementSourceId, replacementSource)
            .addToZone(ZoneKey(opponentId, Zone.BATTLEFIELD), replacementSourceId)

        val initial = com.wingedsheep.engine.handlers.effects.ZoneTransitionService
            .moveToZoneWithReplacements(
                state = state,
                entityId = commanderId,
                destinationZone = Zone.HAND,
                fromZoneKey = ZoneKey(opponentId, Zone.BATTLEFIELD),
                context = EffectContext(sourceId = null, controllerId = opponentId),
            )

        val ordering = initial.pendingDecision.shouldBeInstanceOf<ChooseOptionDecision>()
        ordering.playerId shouldBe opponentId
        ordering.options.any { it.startsWith("Test Commander -") } shouldBe true
        ordering.options.any { it.startsWith("Controlled Exile -") } shouldBe true
        ordering.options.none { it.startsWith("Decline:") } shouldBe true
    }

    test("optional Commander YES preserves ordinary replacement identities already applied") {
        val replacementSourceId = EntityId.generate()
        val ordinaryReplacement = RedirectZoneChange(
            newDestination = Zone.LIBRARY,
            appliesTo = EventPattern.ZoneChangeEvent(
                filter = GameObjectFilter.Any,
                from = Zone.BATTLEFIELD,
            ),
        )
        val replacementSource = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "Prior Redirect",
                name = "Prior Redirect",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT)),
                ownerId = playerId,
            ),
            OwnerComponent(playerId),
            ControllerComponent(playerId),
            ReplacementEffectSourceComponent(listOf(ordinaryReplacement)),
        )
        val state = stateWithCommanderIn(Zone.BATTLEFIELD)
            .withEntity(replacementSourceId, replacementSource)
            .addToZone(ZoneKey(playerId, Zone.BATTLEFIELD), replacementSourceId)
        val pending = PendingGameEvent.ZoneChangePending(
            entityId = commanderId,
            ownerId = playerId,
            fromZoneKey = ZoneKey(playerId, Zone.BATTLEFIELD),
            destinationZone = Zone.HAND,
        )
        val processor = ReplacementEffectProcessor()
        val gathered = processor.gatherReplacements(state, pending)
            .first { it.effect == ordinaryReplacement }

        val result = processor.applySingle(state, gathered, pending, emptySet())
        val paused = result.shouldBeInstanceOf<ProcessorResult.Paused>()
        val continuation = paused.state.continuationStack.last()
            .shouldBeInstanceOf<OptionalReplacementContinuation>()

        continuation.alreadyApplied shouldBe setOf(gathered.identity)
    }

    test("pending Commander replacement state is serializable and fork-safe") {
        val services = EngineServices(CardRegistry())
        val initial = moveWithServices(services, stateWithCommanderIn(Zone.BATTLEFIELD), Zone.HAND)
        val pausedState = initial.state
        val frame = pausedState.continuationStack.last()
        val json = Json { serializersModule = engineSerializersModule }

        val encoded = json.encodeToString(ContinuationFrame.serializer(), frame)
        val decoded = json.decodeFromString<ContinuationFrame>(encoded)
        decoded shouldBe frame

        val decodedState = pausedState.copy(
            continuationStack = pausedState.continuationStack.dropLast(1) + decoded,
        )
        val resumed = resumeYesNo(services, initial.copy(state = decodedState), choice = true)
        pausedState.getZone(ZoneKey(playerId, Zone.BATTLEFIELD)) shouldBe listOf(commanderId)
        pausedState.getZone(ZoneKey(playerId, Zone.HAND)) shouldBe emptyList()
        resumed.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
    }

    test("non-commanders move normally without a Commander choice") {
        val regularId = EntityId.generate()
        val regular = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "Regular Card",
                name = "Regular Card",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                ownerId = playerId,
            ),
            OwnerComponent(playerId),
        )
        val state = GameState(format = Format.Commander())
            .withEntity(playerId, ComponentContainer.EMPTY)
            .withEntity(regularId, regular)
            .addToZone(ZoneKey(playerId, Zone.BATTLEFIELD), regularId)
            .copy(turnOrder = listOf(playerId))
        val services = EngineServices(CardRegistry())
        val result = services.effectExecutorRegistry.execute(
            state,
            MoveToZoneEffect(EffectTarget.SpecificEntity(regularId), Zone.HAND),
            EffectContext(sourceId = null, controllerId = playerId),
        )

        result.isPaused shouldBe false
        result.pendingDecision shouldBe null
        result.state.getZone(ZoneKey(playerId, Zone.HAND)) shouldBe listOf(regularId)
    }

    test("alwaysDivertToCommand is an automatic YES inside the replacement pipeline") {
        val services = EngineServices(CardRegistry())
        val state = stateWithCommanderIn(Zone.BATTLEFIELD).copy(
            format = Format.Commander(alwaysDivertToCommand = true)
        )
        val result = services.effectExecutorRegistry.execute(
            state,
            MoveToZoneEffect(EffectTarget.ContextTarget(0), Zone.HAND),
            EffectContext(
                sourceId = null,
                controllerId = playerId,
                targets = listOf(ChosenTarget.Permanent(commanderId)),
            ),
        )

        result.isPaused shouldBe false
        result.error shouldBe null
        result.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        result.state.getZone(ZoneKey(playerId, Zone.HAND)) shouldBe emptyList()
    }

    test("903.9b auto-YES also applies when the commander starts in the command zone") {
        val services = EngineServices(CardRegistry())
        val result = moveWithServices(
            services,
            stateWithCommanderIn(Zone.COMMAND).copy(
                format = Format.Commander(alwaysDivertToCommand = true)
            ),
            Zone.HAND,
        )

        result.isPaused shouldBe false
        result.error shouldBe null
        result.state.getZone(ZoneKey(playerId, Zone.COMMAND)) shouldBe listOf(commanderId)
        result.state.getZone(ZoneKey(playerId, Zone.HAND)) shouldBe emptyList()
    }

    test("a declined candidate is quiet for the unchanged event but reappears after a modification") {
        val redirectSourceId = EntityId.generate()
        val redirectSource = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "Command Redirect",
                name = "Command Redirect",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.ENCHANTMENT)),
                ownerId = playerId,
            ),
            OwnerComponent(playerId),
            ControllerComponent(playerId),
            ReplacementEffectSourceComponent(
                listOf(
                    RedirectZoneChange(
                        newDestination = Zone.LIBRARY,
                        appliesTo = EventPattern.ZoneChangeEvent(
                            filter = GameObjectFilter.Any,
                            from = Zone.COMMAND,
                            to = Zone.COMMAND,
                        ),
                    )
                )
            ),
        )
        val state = stateWithCommanderIn(Zone.COMMAND)
            .withEntity(redirectSourceId, redirectSource)
            .addToZone(ZoneKey(playerId, Zone.BATTLEFIELD), redirectSourceId)
        val pending = PendingGameEvent.ZoneChangePending(
            entityId = commanderId,
            ownerId = playerId,
            fromZoneKey = ZoneKey(playerId, Zone.COMMAND),
            destinationZone = Zone.HAND,
        )
        val processor = ReplacementEffectProcessor()

        val first = processor.process(state, pending) as ProcessorResult.Paused
        val firstContinuation = first.state.continuationStack.last()
            as com.wingedsheep.engine.core.OptionalReplacementContinuation
        val unchanged = processor.processAfterOptionalDecline(
            state = first.state.clearPendingDecision().copy(continuationStack = emptyList()),
            event = pending,
            gathered = firstContinuation.gathered,
            alreadyApplied = firstContinuation.alreadyApplied,
        )
        unchanged shouldBe ProcessorResult.Pass

        val changed = processor.applySingle(
            state = first.state.clearPendingDecision().copy(continuationStack = emptyList()),
            gathered = firstContinuation.gathered,
            event = pending,
            alreadyApplied = firstContinuation.alreadyApplied,
        )
        changed.shouldBeInstanceOf<ProcessorResult.Paused>()
    }
})
