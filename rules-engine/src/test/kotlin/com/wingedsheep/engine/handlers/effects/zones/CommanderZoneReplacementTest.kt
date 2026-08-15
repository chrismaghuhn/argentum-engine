package com.wingedsheep.engine.handlers.effects.zones

import com.wingedsheep.engine.handlers.EffectContext
import com.wingedsheep.engine.handlers.PipelineState
import com.wingedsheep.engine.core.EffectResult
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.ContinuationFrame
import com.wingedsheep.engine.core.ZoneChangeEvent
import com.wingedsheep.engine.core.YesNoDecision
import com.wingedsheep.engine.core.YesNoResponse
import com.wingedsheep.engine.core.engineSerializersModule
import com.wingedsheep.engine.replacement.PendingGameEvent
import com.wingedsheep.engine.replacement.ProcessorResult
import com.wingedsheep.engine.replacement.ReplacementEffectProcessor
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.ComponentContainer
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.ZoneKey
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.CommanderComponent
import com.wingedsheep.engine.state.components.identity.OwnerComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
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
import com.wingedsheep.sdk.scripting.RedirectZoneChange
import com.wingedsheep.sdk.scripting.effects.MoveToZoneEffect
import com.wingedsheep.sdk.scripting.effects.MoveCollectionEffect
import com.wingedsheep.sdk.scripting.effects.CardDestination
import com.wingedsheep.sdk.scripting.effects.DiscoverEffect
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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

    fun addLibrarySentinel(state: GameState): GameState {
        val sentinel = ComponentContainer.of(
            CardComponent(
                cardDefinitionId = "Library Sentinel",
                name = "Library Sentinel",
                manaCost = ManaCost.ZERO,
                typeLine = TypeLine(cardTypes = setOf(CardType.CREATURE)),
                ownerId = playerId,
            ),
            OwnerComponent(playerId),
        )
        return state
            .withEntity(libraryCardId, sentinel)
            .addToZone(ZoneKey(playerId, Zone.LIBRARY), libraryCardId)
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
