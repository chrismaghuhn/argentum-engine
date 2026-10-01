package com.wingedsheep.engine.legalactions.enumerators

import com.wingedsheep.engine.core.CrewVehicle
import com.wingedsheep.engine.handlers.actions.ability.CrewSaddleContributionEvaluator
import com.wingedsheep.engine.legalactions.ActionEnumerator
import com.wingedsheep.engine.legalactions.TapForPowerCreatureData
import com.wingedsheep.engine.legalactions.EnumerationContext
import com.wingedsheep.engine.legalactions.LegalAction
import com.wingedsheep.engine.mechanics.EffectiveKeywordAbilityResolver
import com.wingedsheep.engine.mechanics.combat.rules.AttackAvailability
import com.wingedsheep.engine.state.components.battlefield.TappedComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.model.EntityId

/**
 * Enumerates crew actions for Vehicle permanents controlled by the player.
 *
 * A Vehicle can be crewed by tapping any number of untapped creatures whose
 * total power meets or exceeds the crew requirement. Summoning sickness does
 * NOT prevent a creature from crewing.
 */
class CrewEnumerator : ActionEnumerator {

    override fun enumerate(context: EnumerationContext): List<LegalAction> {
        val result = mutableListOf<LegalAction>()
        val state = context.state
        val playerId = context.playerId
        val projected = context.projected
        // Every Vehicle sees the same candidate creatures, so answer "could it attack?" once per
        // creature rather than once per (Vehicle, creature) pair.
        val canAttackCache = HashMap<EntityId, Boolean>()

        for (entityId in context.battlefieldPermanents) {
            val container = state.getEntity(entityId) ?: continue
            val cardComponent = container.get<CardComponent>() ?: continue
            // By definition id, not name — `CrewVehicleHandler` resolves the crew keyword by id,
            // so a renamed copy of a Vehicle (CR 707.9) would otherwise be crewable by the engine
            // but never offered the Crew action.
            val crewAbilities = EffectiveKeywordAbilityResolver.effectiveCrewAbilities(
                state = state,
                cardRegistry = context.cardRegistry,
                targetId = entityId
            )
            if (crewAbilities.isEmpty()) continue

            // Crew is an activated ability (CR 702.122a) — mirror `CrewVehicleHandler`'s
            // "players can't activate abilities" check so it's never offered and then refused.
            if (context.castPermissionUtils.isActivationPreventedForPlayer(state, entityId, playerId)) continue

            for (crewAbility in crewAbilities) {
                // "Crew N. Activate only once each turn." — once it's already been crewed this
                // turn, that effective instance is no longer available (Luxurious Locomotive).
                if (crewAbility.ability.onceEachTurn) {
                    val crewActivations = container
                        .get<com.wingedsheep.engine.state.components.battlefield.CrewSaddleContributorsComponent>()
                        ?.crewActivations ?: 0
                    if (crewActivations >= 1) continue
                }

                // Find all untapped creatures controlled by the player that can crew.
                //
                // This eligibility rule (untapped, controlled by the payer, projected, no
                // summoning-sickness check) is duplicated in
                // `com.wingedsheep.engine.mechanics.cost.VariablePermanentsCost.candidates`, which
                // Teamwork N (CR 702.194a) pays through — the two agree, but nothing enforces that.
                // The *measure* deliberately differs and must stay split: crew sums through
                // `CrewSaddleContributionEvaluator` so crew-specific statics ("crews Vehicles as
                // though its power were 2 greater") count, which they must not for teamwork.
                // Folding the selection (not the measure) into the shared helper is an open
                // follow-up.
                val validCrewCreatures = mutableListOf<TapForPowerCreatureData>()
                var totalAvailablePower = 0
                for (creatureId in context.battlefieldPermanents) {
                    if (creatureId == entityId) continue // Vehicle can't crew itself
                    if (!projected.isCreature(creatureId)) continue
                    val creatureContainer = state.getEntity(creatureId) ?: continue
                    if (creatureContainer.has<TappedComponent>()) continue
                    // Summoning sickness does NOT prevent crewing.
                    // What it contributes, not its raw power — `CrewVehicleHandler` measures the
                    // cost through the same evaluator, so a creature that "crews Vehicles as though
                    // its power were 2 greater" must read that way here or the client's progress
                    // bar would refuse a crew the engine accepts.
                    val power = CrewSaddleContributionEvaluator.evaluate(
                        state = state,
                        projected = projected,
                        cardRegistry = context.cardRegistry,
                        creatureId = creatureId
                    )
                    val creatureName = creatureContainer.get<CardComponent>()?.name ?: "Unknown"
                    val canAttack = canAttackCache.getOrPut(creatureId) {
                        AttackAvailability.canAttack(state, projected, creatureId, playerId, context.cardRegistry, context.predicateEvaluator)
                    }
                    validCrewCreatures.add(
                        TapForPowerCreatureData(creatureId, creatureName, power, canAttack)
                    )
                    totalAvailablePower += power
                }

                val canAfford = totalAvailablePower >= crewAbility.ability.n
                result.add(
                    LegalAction(
                        actionType = "CrewVehicle",
                        description = "Crew ${cardComponent.name}",
                        action = CrewVehicle(
                            playerId = playerId,
                            vehicleId = entityId,
                            crewCreatures = emptyList(),
                            crewAbilityKey = crewAbility.key
                        ),
                        affordable = canAfford,
                        tapForPower = true,
                        tapForPowerRequired = crewAbility.ability.n,
                        tapForPowerCreatures = validCrewCreatures
                    )
                )
            }
        }

        return result
    }
}
