package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.state.components.battlefield.DamageComponent
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.combat.BlockedComponent
import com.wingedsheep.engine.state.components.combat.BlockingComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.sdk.core.Keyword
import com.wingedsheep.sdk.model.EntityId

/**
 * Supplies combat-damage arithmetic used by the modern assignment graph.
 *
 * Key rules:
 * - CR 510.1c/d: the source's controller chooses how to divide its damage.
 * - CR 702.2b: Deathtouch - any amount of damage is considered lethal.
 * - CR 702.19: Trample - excess damage can be assigned to defending player.
 */
class DamageCalculator(
    private val cardRegistry: CardRegistry? = null,
) {

    /**
     * Result of calculating lethal damage for a creature.
     */
    data class LethalDamageInfo(
        val creatureId: EntityId,
        val toughness: Int,
        val damageAlreadyMarked: Int,
        val lethalAmount: Int,
        val sourceHasDeathtouch: Boolean
    )

    /**
     * Result of auto-calculating damage distribution.
     */
    data class DamageDistribution(
        /** Map of target (creature or player) to damage amount */
        val assignments: Map<EntityId, Int>,
        /** Total damage assigned */
        val totalAssigned: Int,
        /** Damage that couldn't be assigned (shouldn't happen normally) */
        val unassignedDamage: Int
    )

    /**
     * Calculate a display hint for the amount that would make a blocker lethal
     * for a trample plan. Ordinary assignment legality never consumes this
     * value.
     *
     * @param state Current game state
     * @param creatureId The creature receiving damage
     * @param sourceId The source dealing damage (to check for deathtouch)
     * @return LethalDamageInfo with calculated values
     */
    fun calculateLethalDamage(
        state: GameState,
        creatureId: EntityId,
        sourceId: EntityId
    ): LethalDamageInfo {
        val creatureContainer = state.getEntity(creatureId)
        val damageMarked = creatureContainer?.get<DamageComponent>()?.amount ?: 0

        // Use projected values for toughness (includes floating effects like +4/+4)
        val projected = state.projectedState
        val toughness = projected.getToughness(creatureId) ?: 0

        // Check if source has deathtouch (using projected keywords)
        val hasDeathtouch = projected.hasKeyword(sourceId, Keyword.DEATHTOUCH)

        val remaining = (toughness - damageMarked).coerceAtLeast(0)
        val lethalAmount = if (remaining == 0) 0 else if (hasDeathtouch) 1 else remaining

        return LethalDamageInfo(
            creatureId = creatureId,
            toughness = toughness,
            damageAlreadyMarked = damageMarked,
            lethalAmount = lethalAmount,
            sourceHasDeathtouch = hasDeathtouch
        )
    }

    /**
     * Seed a complete assignment plan for an attacker.
     *
     * The result is only a deterministic UI default. Ordinary combat does not
     * encode a damage-assignment order or a generic lethal-first rule. When the
     * source has trample, this default uses the explicit trample lethal
     * prerequisite so the seeded drain is legal; the validator remains the
     * authoritative semantic gate.
     */
    fun calculateAutoDamageDistribution(
        state: GameState,
        attackerId: EntityId
    ): DamageDistribution {
        val attackerContainer = state.getEntity(attackerId)
            ?: return DamageDistribution(emptyMap(), 0, 0)

        attackerContainer.get<CardComponent>()
            ?: return DamageDistribution(emptyMap(), 0, 0)

        // Use projected values for power and keywords (includes floating effects like +4/+4)
        val projected = state.projectedState
        val attackerPower = CombatDamageUtils.getAssignedCombatDamage(state, projected, attackerId, cardRegistry)
        if (attackerPower <= 0) {
            return DamageDistribution(emptyMap(), 0, 0)
        }

        val blockers = attackerContainer.get<BlockedComponent>()
            ?.blockerIds
            ?.filter { it in state.getBattlefield() }
            .orEmpty()
        if (blockers.isEmpty()) {
            return DamageDistribution(emptyMap(), 0, attackerPower)
        }

        val hasTrample = projected.hasKeyword(attackerId, Keyword.TRAMPLE)
        if (!hasTrample) {
            // Ordinary combat damage is freely divisible. This is merely a stable
            // default, never a legality constraint or a lethal-first algorithm.
            val assignments = mapOf(blockers.first() to attackerPower)
            return DamageDistribution(assignments, attackerPower, 0)
        }

        // Trample is the one explicit exception: damage may be assigned to the
        // defending object only after the blockers have received lethal damage.
        // This is a trample-specific requirement, not the obsolete generic
        // damage-assignment order.
        val lethalAmounts = blockers.associateWith { blockerId ->
            calculateLethalDamage(state, blockerId, attackerId).lethalAmount
        }
        val totalLethal = lethalAmounts.values.sum()
        if (attackerPower < totalLethal) {
            // There is no excess to assign to the defending object. Any complete
            // split among blockers is legal; keep the neutral deterministic seed.
            val assignments = mapOf(blockers.first() to attackerPower)
            return DamageDistribution(assignments, attackerPower, 0)
        }

        val assignments = linkedMapOf<EntityId, Int>()
        var remaining = attackerPower
        for (blockerId in blockers) {
            val lethal = lethalAmounts.getValue(blockerId)
            assignments[blockerId] = lethal
            remaining -= lethal
        }

        val defenderId = state.getEntity(attackerId)?.get<AttackingComponent>()?.defenderId
        val hasLiveDefender = defenderId != null && (
            defenderId in state.turnOrder ||
                (defenderId in state.getBattlefield() &&
                    (projected.isPlaneswalker(defenderId) || projected.isBattle(defenderId)))
            )
        if (remaining > 0 && hasLiveDefender) {
            assignments[defenderId] = remaining
            return DamageDistribution(assignments, attackerPower, 0)
        }

        // A malformed/synthetic state without an attacked defender cannot make
        // use of trample drain; retain a complete blocker-only plan instead.
        if (remaining > 0) {
            assignments[blockers.first()] = assignments.getValue(blockers.first()) + remaining
        }
        return DamageDistribution(assignments, attackerPower, 0)
    }

    /**
     * Check if an attacker requires manual damage assignment.
     *
     * Manual assignment is needed when:
     * - Attacker has trample and is blocked (player can choose split)
     * - Attacker has enough power to kill multiple blockers with options
     * - User preference is set to always manually assign
     */
    fun requiresManualAssignment(
        state: GameState,
        attackerId: EntityId,
        userPreference: Boolean = false
    ): Boolean {
        if (userPreference) return true

        val attackerContainer = state.getEntity(attackerId) ?: return false
        attackerContainer.get<CardComponent>() ?: return false

        val blockedComponent = attackerContainer.get<BlockedComponent>()
        val blockerIds = blockedComponent?.blockerIds?.filter { it in state.getBattlefield() } ?: return false
        if (blockerIds.isEmpty()) return false

        // Use projected values for keywords and power (includes floating effects like +4/+4)
        val projected = state.projectedState

        // Single blocker without trample = no choice (all damage goes to that one blocker).
        if (blockerIds.size <= 1 && !projected.hasKeyword(attackerId, Keyword.TRAMPLE)) {
            return false
        }

        // Trample or two or more blockers always present a choice: the modern
        // assignment board must collect the source's complete split explicitly.
        return true
    }

    /**
     * Seed a complete assignment plan for a blocker that blocks multiple
     * attackers. The default is intentionally arbitrary and never consults
     * legacy attacker-order state or a generic lethal-first rule.
     */
    fun calculateBlockerDamageDistribution(
        state: GameState,
        blockerId: EntityId,
        pendingDamage: Map<EntityId, Int> = emptyMap()
    ): DamageDistribution {
        val blockerContainer = state.getEntity(blockerId)
            ?: return DamageDistribution(emptyMap(), 0, 0)

        blockerContainer.get<CardComponent>()
            ?: return DamageDistribution(emptyMap(), 0, 0)

        val projected = state.projectedState
        val blockerPower = CombatDamageUtils.getAssignedCombatDamage(state, projected, blockerId, cardRegistry)
        if (blockerPower <= 0) {
            return DamageDistribution(emptyMap(), 0, 0)
        }

        val blockingComponent = blockerContainer.get<BlockingComponent>()
            ?: return DamageDistribution(emptyMap(), 0, 0)

        val attackers = blockingComponent.blockedAttackerIds
            .filter { it in state.getBattlefield() }

        if (attackers.isEmpty()) {
            return DamageDistribution(emptyMap(), 0, blockerPower)
        }

        // The parameter remains for source compatibility with old callers; it
        // is intentionally not part of ordinary assignment legality.
        @Suppress("UNUSED_VARIABLE")
        val ignoredPendingDamage = pendingDamage
        return DamageDistribution(
            assignments = mapOf(attackers.first() to blockerPower),
            totalAssigned = blockerPower,
            unassignedDamage = 0,
        )
    }

}
