package com.wingedsheep.engine.mechanics.combat

import com.wingedsheep.engine.state.GameState
import com.wingedsheep.engine.mechanics.layers.ProjectedState
import com.wingedsheep.engine.state.components.battlefield.BattlefieldEntryTimestampComponent
import com.wingedsheep.engine.state.components.combat.AttackedDefenderKind
import com.wingedsheep.engine.state.components.combat.AttackingComponent
import com.wingedsheep.engine.state.components.identity.CardComponent
import com.wingedsheep.engine.state.components.identity.ControllerComponent
import com.wingedsheep.engine.state.components.identity.LifeTotalComponent
import com.wingedsheep.sdk.model.EntityId

/**
 * Helpers for the *defending* side of multiplayer combat (CR 802.2).
 *
 * In a Free-for-All game the attacking player declares each creature against a specific
 * player or planeswalker, so a single combat can have several defending players at once.
 * "The defending player" is therefore not a single fixed seat — it is derived per attacking
 * creature from where that creature is attacking (CR 802.2a). These helpers answer "who is
 * defending in this combat" so block declaration can be offered to each of them, in turn
 * order starting from the active player (APNAP, CR 101.4).
 */
object CombatDefenders {

    /**
     * Stamp the attack target's rule-relevant identity at declaration time. A later combat-damage
     * pass must not infer an attacked planeswalker/battle's defending relationship from its
     * current controller or protector: CR 506.4 removes that object from combat when either
     * relationship changes.
     */
    fun attackingComponentFor(
        state: GameState,
        projected: ProjectedState,
        defenderId: EntityId,
        bandId: String? = null,
    ): AttackingComponent {
        val kind = defenderKind(state, projected, defenderId)
        return AttackingComponent(
            defenderId = defenderId,
            bandId = bandId,
            defenderKindAtDeclaration = kind,
            defenderControllerAtDeclaration = kind
                ?.takeUnless { it == AttackedDefenderKind.PLAYER }
                ?.let { projected.getController(defenderId) },
            defenderProtectorAtDeclaration = kind
                ?.takeIf { it == AttackedDefenderKind.BATTLE }
                ?.let { com.wingedsheep.engine.mechanics.battle.Battles.protectorOf(state, defenderId) },
            defenderBattlefieldObjectTimestampAtDeclaration = kind
                ?.takeUnless { it == AttackedDefenderKind.PLAYER }
                ?.let {
                    state.getEntity(defenderId)
                        ?.get<BattlefieldEntryTimestampComponent>()
                        ?.timestamp
                },
        )
    }

    /**
     * True only when [targetId] is still the object/player that [attackerId] may damage this
     * combat. This is the single combat-damage recipient predicate used by the assignment board,
     * automatic/manual assignment, and prevention aggregation.
     *
     * CR 506.4c leaves an attacker in combat after its attacked planeswalker/battle is gone, but
     * CR 510.1b gives that unblocked attacker no combat damage recipient. CR 702.19f likewise
     * forbids ordinary trample from inventing a player recipient after the attacked object is
     * gone. Players use [GameState.activePlayers], not the historical [GameState.turnOrder].
     */
    fun isCurrentAttackedRecipient(
        state: GameState,
        projected: ProjectedState,
        attackerId: EntityId,
        targetId: EntityId,
    ): Boolean {
        val attacking = state.getEntity(attackerId)?.get<AttackingComponent>() ?: return false
        if (attacking.defenderId != targetId) return false

        // Legacy/synthetic player-only components can still be inferred safely. An object target
        // without declaration metadata cannot prove the original relationship, so fail closed
        // rather than treating the current controller/protector as historical truth.
        val declaredKind = attacking.defenderKindAtDeclaration
            ?: if (isLivePlayer(state, targetId)) AttackedDefenderKind.PLAYER else return false
        return when (declaredKind) {
            AttackedDefenderKind.PLAYER -> isLivePlayer(state, targetId)
            AttackedDefenderKind.PLANESWALKER ->
                isCurrentPlaneswalkerRecipient(state, projected, attacking, targetId)
            AttackedDefenderKind.BATTLE ->
                isCurrentBattleRecipient(state, projected, attacking, targetId)
        }
    }

    private fun defenderKind(
        state: GameState,
        projected: ProjectedState,
        defenderId: EntityId,
    ): AttackedDefenderKind? = when {
        isLivePlayer(state, defenderId) -> AttackedDefenderKind.PLAYER
        defenderId !in state.getBattlefield() -> null
        projected.isPlaneswalker(defenderId) -> AttackedDefenderKind.PLANESWALKER
        projected.isBattle(defenderId) -> AttackedDefenderKind.BATTLE
        else -> null
    }

    private fun isLivePlayer(state: GameState, entityId: EntityId): Boolean {
        val container = state.getEntity(entityId) ?: return false
        return entityId in state.activePlayers &&
            container.get<LifeTotalComponent>() != null &&
            container.get<CardComponent>() == null
    }

    private fun isCurrentPlaneswalkerRecipient(
        state: GameState,
        projected: ProjectedState,
        attacking: AttackingComponent,
        targetId: EntityId,
    ): Boolean {
        if (targetId !in state.getBattlefield() || !projected.isPlaneswalker(targetId)) return false
        if (!sameBattlefieldObject(state, attacking, targetId)) return false
        val controller = projected.getController(targetId) ?: return false
        if (controller !in state.activePlayers) return false
        return attacking.defenderControllerAtDeclaration == controller
    }

    private fun isCurrentBattleRecipient(
        state: GameState,
        projected: ProjectedState,
        attacking: AttackingComponent,
        targetId: EntityId,
    ): Boolean {
        if (targetId !in state.getBattlefield() || !projected.isBattle(targetId)) return false
        if (!sameBattlefieldObject(state, attacking, targetId)) return false
        val controller = projected.getController(targetId) ?: return false
        val protector = com.wingedsheep.engine.mechanics.battle.Battles.protectorOf(state, targetId)
            ?: return false
        if (controller !in state.activePlayers || protector !in state.activePlayers) return false
        return attacking.defenderControllerAtDeclaration == controller &&
            attacking.defenderProtectorAtDeclaration == protector
    }

    private fun sameBattlefieldObject(
        state: GameState,
        attacking: AttackingComponent,
        targetId: EntityId,
    ): Boolean {
        val declaredTimestamp = attacking.defenderBattlefieldObjectTimestampAtDeclaration ?: return true
        return state.getEntity(targetId)
            ?.get<BattlefieldEntryTimestampComponent>()
            ?.timestamp == declaredTimestamp
    }

    /**
     * The player defending against an attack aimed at [defenderId]: a player defends as
     * themselves, a planeswalker defends on behalf of its controller (CR 508.5), and a **battle
     * defends on behalf of its protector, not its controller** (CR 310.8d — for a battle being
     * attacked, every rule and effect that refers to the defending player means its protector).
     * That asymmetry is the whole point of a Siege: its controller attacks it while an opponent
     * defends it.
     */
    fun defendingPlayerOf(state: GameState, defenderId: EntityId): EntityId {
        if (defenderId in state.activePlayers) return defenderId
        com.wingedsheep.engine.mechanics.battle.Battles.protectorOf(state, defenderId)
            ?.let { return it }
        return state.getEntity(defenderId)?.get<ControllerComponent>()?.playerId ?: defenderId
    }

    /**
     * Resolve the defending player from the attack declaration, preserving CR 508.5's historical
     * controller/protector relationship after a planeswalker/battle is removed from combat.
     */
    fun defendingPlayerOf(state: GameState, attacking: AttackingComponent): EntityId {
        return when (attacking.defenderKindAtDeclaration) {
            AttackedDefenderKind.PLAYER -> attacking.defenderId
            AttackedDefenderKind.PLANESWALKER ->
                attacking.defenderControllerAtDeclaration
                    ?: defendingPlayerOf(state, attacking.defenderId)
            AttackedDefenderKind.BATTLE ->
                attacking.defenderProtectorAtDeclaration
                    ?: defendingPlayerOf(state, attacking.defenderId)
            null -> defendingPlayerOf(state, attacking.defenderId)
        }
    }

    /** Every distinct defending player in the current combat: anyone who has a creature attacking
     *  them (or their planeswalkers/battles) — and, under shared team turns (Two-Headed Giant), their
     *  whole team (CR 805.10a: every member of the nonactive team is a defending player, so an
     *  un-attacked teammate may still declare blockers to protect the team). Without shared team
     *  turns — Team vs. Team (CR 808) and non-team games — only the directly-attacked players defend
     *  (`sharedTurnTeam` is a singleton there), so a teammate can't block for you. */
    fun defendingPlayers(state: GameState): Set<EntityId> =
        state.getBattlefield()
            .mapNotNull { state.getEntity(it)?.get<AttackingComponent>() }
            .map { defendingPlayerOf(state, it) }
            .flatMap { state.sharedTurnTeam(it) }
            .toSet()

    /** True if [playerId] is a defending player in the current combat. */
    fun isDefendingPlayer(state: GameState, playerId: EntityId): Boolean =
        defendingPlayers(state).contains(playerId)

    /**
     * The opponents [attackingPlayer]'s creatures are allowed to attack under the game's
     * [com.wingedsheep.sdk.core.AttackMode] (CR 802 / 803). This is the single source of truth
     * for the attack-mode seat restriction: the legal-action enumerator filters its
     * `validAttackTargets` hint by it, and `AttackModeDefenderRule` enforces it at declaration.
     *
     * - [AttackMode.MULTIPLE] — every opponent still in the game (CR 802.2).
     * - [AttackMode.LEFT] — only the opponent in the next remaining seat (CR 803.1a). Turn order
     *   proceeds to the left (CR 103.7b), so "the player to your left" is [GameState.getNextPlayer].
     * - [AttackMode.RIGHT] — only the opponent in the previous remaining seat (CR 803.1b), via
     *   [GameState.getPreviousPlayer].
     *
     * A planeswalker/battle is attackable iff its controller/protector is in this set (the caller
     * maps it). Departed players are already skipped by the seat helpers, so in Free-for-All the
     * left/right neighbour is always an opponent exactly one seat away.
     */
    fun legalDefendingPlayers(state: GameState, attackingPlayer: EntityId): Set<EntityId> =
        when (state.attackMode) {
            com.wingedsheep.sdk.core.AttackMode.MULTIPLE -> state.getOpponents(attackingPlayer).toSet()
            com.wingedsheep.sdk.core.AttackMode.LEFT ->
                setOf(state.getNextPlayer(attackingPlayer)).minus(attackingPlayer)
            com.wingedsheep.sdk.core.AttackMode.RIGHT ->
                setOf(state.getPreviousPlayer(attackingPlayer)).minus(attackingPlayer)
        }

    /**
     * The defending players ordered for sequential block declaration: turn order starting
     * from the active player (CR 101.4 APNAP). The active player is never a defender, so in
     * practice this is the defenders in turn order after the active player, wrapping around.
     */
    fun defendingPlayersInApnapOrder(state: GameState): List<EntityId> {
        val defenders = defendingPlayers(state).filter { it in state.activePlayers }.toSet()
        if (defenders.isEmpty()) return emptyList()
        val order = state.activePlayers
        if (order.isEmpty()) return defenders.toList()
        val startIdx = state.activePlayerId?.let { order.indexOf(it) }?.coerceAtLeast(0) ?: 0
        return (order.indices)
            .map { order[(startIdx + it) % order.size] }
            .filter { it in defenders }
    }
}
