package com.wingedsheep.engine.handlers.actions.ability

import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.core.CrewOrSaddleContributionEvent
import com.wingedsheep.engine.core.CrewOrSaddleKind
import com.wingedsheep.engine.core.ExecutionResult
import com.wingedsheep.engine.core.GameEvent
import com.wingedsheep.engine.core.tap
import com.wingedsheep.engine.event.TriggerDetector
import com.wingedsheep.engine.event.TriggerProcessor
import com.wingedsheep.engine.core.EngineServices
import com.wingedsheep.engine.handlers.actions.ActionHandler
import com.wingedsheep.engine.mechanics.EffectiveKeywordAbilityResolver
import com.wingedsheep.engine.mechanics.stack.StackResolver
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.CrewSaddleContributorsComponent
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.stack.ActivatedAbilityOnStackComponent
import com.wingedsheep.sdk.model.CharacteristicValue
import com.wingedsheep.sdk.scripting.values.DynamicAmount
import com.wingedsheep.sdk.scripting.effects.BecomeCreatureEffect
import com.wingedsheep.sdk.scripting.targets.EffectTarget
import kotlin.reflect.KClass

/**
 * Handler for the CrewVehicle action.
 *
 * Crew is an activated ability on Vehicles. The cost is tapping creatures with
 * total power >= the crew requirement. The effect (Vehicle becomes an artifact
 * creature until end of turn) goes on the stack.
 */
class CrewVehicleHandler(
    private val cardRegistry: CardRegistry,
    private val stackResolver: StackResolver,
    private val triggerDetector: TriggerDetector,
    private val triggerProcessor: TriggerProcessor
) : ActionHandler<CrewVehicle> {
    override val actionType: KClass<CrewVehicle> = CrewVehicle::class

    override fun validate(state: GameState, action: CrewVehicle): String? {
        if (state.priorityPlayerId != action.playerId) {
            return "You don't have priority"
        }

        val vehicleContainer = state.getEntity(action.vehicleId)
            ?: return "Vehicle not found: ${action.vehicleId}"

        val vehicleCard = vehicleContainer.get<CardComponent>()
            ?: return "Not a card: ${action.vehicleId}"

        // Vehicle must be on the battlefield
        if (action.vehicleId !in state.getBattlefield()) {
            return "Vehicle is not on the battlefield"
        }

        // Vehicle must be controlled by the player (use projected state)
        val projected = state.projectedState
        val vehicleController = projected.getController(action.vehicleId)
        if (vehicleController != action.playerId) {
            return "You don't control this vehicle"
        }

        // Resolve the same effective printed/runtime/static grant instances as CrewEnumerator.
        // A serialized key identifies the exact instance when more than one requirement is legal.
        val crewAbilities = EffectiveKeywordAbilityResolver.effectiveCrewAbilities(
            state = state,
            cardRegistry = cardRegistry,
            targetId = action.vehicleId
        )
        if (crewAbilities.isEmpty()) return "This permanent doesn't have crew"

        if (action.crewCreatures.isEmpty()) {
            return "Must select at least one creature to crew"
        }

        // Validate each crew creature
        var totalPower = 0
        for (creatureId in action.crewCreatures) {
            if (creatureId == action.vehicleId) {
                return "A vehicle cannot crew itself"
            }

            val creatureContainer = state.getEntity(creatureId)
                ?: return "Creature not found: $creatureId"

            if (creatureId !in state.getBattlefield()) {
                return "Creature is not on the battlefield: $creatureId"
            }

            // Must be controlled by the player
            val creatureController = projected.getController(creatureId)
            if (creatureController != action.playerId) {
                return "You don't control creature: $creatureId"
            }

            // Must be a creature (use projected state)
            if (!projected.isCreature(creatureId)) {
                return "Not a creature: $creatureId"
            }

            // Must not be tapped
            if (creatureContainer.has<TappedComponent>()) {
                return "Creature is already tapped: $creatureId"
            }

            // Summoning sickness does NOT prevent crewing (CR 702.122c)
            totalPower += CrewSaddleContributionEvaluator.evaluate(
                state = state,
                projected = projected,
                cardRegistry = cardRegistry,
                creatureId = creatureId
            )
        }

        val crewActivations = vehicleContainer.get<CrewSaddleContributorsComponent>()?.crewActivations ?: 0
        val selectedCrewAbility = if (action.crewAbilityKey != null) {
            val matches = crewAbilities.filter { it.key == action.crewAbilityKey }
            if (matches.size != 1) return "Crew ability is stale or ambiguous"
            matches.single()
        } else {
            val payable = crewAbilities.filter { crewAbility ->
                totalPower >= crewAbility.ability.n &&
                    (!crewAbility.ability.onceEachTurn || crewActivations < 1)
            }
            when {
                payable.size == 1 -> payable.single()
                // Preserve the ordinary payment error for legacy/direct actions when the
                // permanent has exactly one Crew instance but the selected creatures cannot pay.
                payable.isEmpty() && crewAbilities.size == 1 -> crewAbilities.single()
                else -> return "Crew ability identity is required"
            }
        }

        // "Crew N. Activate only once each turn." (Luxurious Locomotive): block a second crew
        // activation this turn. Vanilla Crew (onceEachTurn = false) has no such cap.
        if (selectedCrewAbility.ability.onceEachTurn && crewActivations >= 1) {
            return "This Vehicle's crew ability can only be activated once each turn"
        }

        if (totalPower < selectedCrewAbility.ability.n) {
            return "Total power ($totalPower) is less than crew requirement (${selectedCrewAbility.ability.n})"
        }

        return null
    }

    override fun execute(state: GameState, action: CrewVehicle): ExecutionResult {
        val vehicleContainer = state.getEntity(action.vehicleId)
            ?: return ExecutionResult.error(state, "Vehicle not found")

        val vehicleCard = vehicleContainer.get<CardComponent>()
            ?: return ExecutionResult.error(state, "Not a card")

        val cardDef = cardRegistry.getCard(vehicleCard.cardDefinitionId)
            ?: return ExecutionResult.error(state, "Card definition not found")

        var currentState = state
        val events = mutableListOf<GameEvent>()

        // Pay the cost: tap each crew creature
        for (creatureId in action.crewCreatures) {
            val (tappedState, tapEvent) = tap(currentState, creatureId)
            currentState = tappedState
            tapEvent?.let {
                events.add(it)
                events.add(
                    CrewOrSaddleContributionEvent(
                        contributorId = creatureId,
                        permanentId = action.vehicleId,
                        controllerId = action.playerId,
                        kind = CrewOrSaddleKind.CREW
                    )
                )
            }
        }

        // Record the crew so Vehicle payoffs can read "creatures that crewed it this turn"
        // (e.g. Luxurious Locomotive). Union across activations within the turn.
        currentState = currentState.updateEntity(action.vehicleId) { c ->
            val existing = c.get<CrewSaddleContributorsComponent>()
            c.with(
                CrewSaddleContributorsComponent(
                    creatureIds = (existing?.creatureIds ?: emptySet()) + action.crewCreatures,
                    crewActivations = (existing?.crewActivations ?: 0) + 1
                )
            )
        }

        // Create the crew ability effect: Vehicle becomes an artifact creature
        // with its base P/T until end of turn
        val stats = cardDef.creatureStats
        val basePower = (stats?.power as? CharacteristicValue.Fixed)?.value ?: 0
        val baseToughness = (stats?.toughness as? CharacteristicValue.Fixed)?.value ?: 0
        val crewEffect = BecomeCreatureEffect(
            target = EffectTarget.Self,
            power = DynamicAmount.Fixed(basePower),
            toughness = DynamicAmount.Fixed(baseToughness),
            keywords = cardDef.keywords
        )

        // Put the crew ability on the stack
        val abilityOnStack = ActivatedAbilityOnStackComponent(
            sourceId = action.vehicleId,
            sourceName = vehicleCard.name,
            controllerId = action.playerId,
            effect = crewEffect
        )

        val stackResult = stackResolver.putActivatedAbility(
            currentState, abilityOnStack
        )
        currentState = stackResult.newState
        events.addAll(stackResult.events)

        // Detect and process triggers from tapping creatures
        val allEvents = events.toList()
        val triggers = triggerDetector.detectTriggers(currentState, allEvents)
        if (triggers.isNotEmpty()) {
            val triggerResult = triggerProcessor.processTriggers(currentState, triggers)

            if (triggerResult.isPaused) {
                return ExecutionResult.paused(
                    triggerResult.state.withPriority(action.playerId),
                    triggerResult.pendingDecision!!,
                    allEvents + triggerResult.events,
                    diagnostics = stackResult.diagnostics + triggerResult.diagnostics,
                )
            }

            return ExecutionResult.success(
                triggerResult.newState.withPriority(action.playerId),
                allEvents + triggerResult.events,
                stackResult.diagnostics + triggerResult.diagnostics,
            )
        }

        return ExecutionResult.success(
            currentState.withPriority(action.playerId),
            allEvents,
            stackResult.diagnostics,
        )
    }

    companion object {
        fun create(services: EngineServices): CrewVehicleHandler {
            return CrewVehicleHandler(
                services.cardRegistry,
                services.stackResolver,
                services.triggerDetector,
                services.triggerProcessor
            )
        }
    }
}
